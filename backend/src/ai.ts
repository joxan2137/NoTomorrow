import { lookupNutrition } from './food.js';
import type { GeminiConfig } from './env.js';
import {
  estimateRequestText,
  estimateSchema,
  estimateSystemInstruction,
  forGemini,
  labelRequestText,
  labelSchema,
  labelSystemInstruction,
  loadSpec,
  notesContext,
  type AISpec,
  type JsonSchema,
} from './aiSpec.js';
import { AIOutputError, finalizeEstimateText, finalizeLabelText, groundWithDatabase, type FinalEstimate, type LabelReading } from './aiFinalize.js';

/**
 * Gemini calls for the food-photo estimate and the nutrition-label read. Prompts, schemas and
 * post-processing come from `data/ai/estimate-spec.json` (see aiSpec.ts / aiFinalize.ts), shared with
 * the apps' bring-your-own-key paths. Transport: Interactions API first, `generateContent` when the
 * model is not served there (404) or rejects the Interactions request shape (400); then the next model
 * of the fallback chain on 429/5xx/timeouts.
 */

export const GEMINI_BASE_URL = 'https://generativelanguage.googleapis.com';
export const MEAL_SLOTS = ['breakfast', 'lunch', 'snack', 'dinner'] as const;
export type MealSlot = (typeof MEAL_SLOTS)[number];
export const THINKING_LEVELS = ['minimal', 'low', 'medium', 'high'] as const;
export type ThinkingLevel = (typeof THINKING_LEVELS)[number];

/** One HTTP attempt (one model, one API) may take this long… */
export const ATTEMPT_TIMEOUT_MS = 35_000;
/** …and the whole chain this long, which stays inside the apps' 90 s request timeout. */
export const TOTAL_BUDGET_MS = 65_000;

export type GeminiErrorKind = 'busy' | 'timeout' | 'network' | 'upstream';

/**
 * Transport or provider failure. `billed` is true when Google may have charged for a request in this
 * chain (a 2xx answer was received, or an attempt timed out after the request was sent); the route
 * refunds the daily quota only when it is false.
 */
export class GeminiError extends Error {
  constructor(
    message: string,
    readonly status?: number,
    readonly kind: GeminiErrorKind = 'upstream',
    readonly billed = false,
  ) {
    super(message);
    this.name = 'GeminiError';
  }
}

/** Gemini answered (and billed) but the answer is not usable. */
export class AIParseError extends Error {
  readonly billed = true;
  constructor(
    message: string,
    readonly code: string = 'unparseable',
  ) {
    super(message);
    this.name = 'AIParseError';
  }
}

export interface AIUsage {
  model: string;
  api: 'interactions' | 'generateContent';
  inputTokens: number | null;
  outputTokens: number | null;
  thoughtTokens: number | null;
  cachedTokens: number | null;
  ms: number;
}

/** Pulls the model's text out of either API's response shape, skipping thought parts. */
export function extractText(json: unknown): string | null {
  if (!json || typeof json !== 'object') return null;
  const root = json as Record<string, unknown>;
  if (typeof root.output_text === 'string') return root.output_text;
  const texts: string[] = [];
  const visit = (node: unknown, depth: number): void => {
    if (!node || depth > 8) return;
    if (Array.isArray(node)) {
      for (const n of node) visit(n, depth + 1);
      return;
    }
    if (typeof node !== 'object') return;
    const o = node as Record<string, unknown>;
    if (o.thought === true || o.type === 'thought') return;
    if (typeof o.text === 'string' && (o.type === undefined || o.type === 'text')) texts.push(o.text);
    for (const key of ['steps', 'outputs', 'output', 'candidates', 'content', 'parts']) if (key in o) visit(o[key], depth + 1);
  };
  visit(root, 0);
  return texts.length > 0 ? texts.join('') : null;
}

function count(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/** Token counts from `usage` (Interactions) or `usageMetadata` (generateContent). */
export function extractUsage(json: unknown): Omit<AIUsage, 'model' | 'api' | 'ms'> {
  const root = (json && typeof json === 'object' ? json : {}) as Record<string, unknown>;
  const usage = root.usage as Record<string, unknown> | undefined;
  if (usage && typeof usage === 'object') {
    return {
      inputTokens: count(usage.total_input_tokens),
      outputTokens: count(usage.total_output_tokens),
      thoughtTokens: count(usage.total_thought_tokens),
      cachedTokens: count(usage.total_cached_tokens),
    };
  }
  const meta = root.usageMetadata as Record<string, unknown> | undefined;
  return {
    inputTokens: count(meta?.promptTokenCount),
    outputTokens: count(meta?.candidatesTokenCount),
    thoughtTokens: count(meta?.thoughtsTokenCount),
    cachedTokens: count(meta?.cachedContentTokenCount),
  };
}

/** What one Gemini call needs: a static system instruction, the per-request text, a schema and a JPEG. */
export interface GeminiPrompt {
  system: string;
  text: string;
  /** Canonical (Claude-compatible) schema; `additionalProperties` is stripped for Gemini here. */
  schema: JsonSchema;
  imageBase64: string;
}

export interface GeminiOptions {
  thinkingLevel: string;
  /** Interactions per-image resolution, e.g. "high". */
  resolution: string;
  /** generateContent `generationConfig.mediaResolution`, e.g. "MEDIA_RESOLUTION_HIGH". */
  mediaResolution: string;
}

/** Interactions body: static system instruction first (implicit caching), image before the request text. */
export function interactionsRequest(model: string, prompt: GeminiPrompt, options: GeminiOptions): Record<string, unknown> {
  return {
    model,
    store: false,
    system_instruction: prompt.system,
    generation_config: { thinking_level: options.thinkingLevel },
    input: [
      { type: 'image', data: prompt.imageBase64, mime_type: 'image/jpeg', resolution: options.resolution },
      { type: 'text', text: prompt.text },
    ],
    response_format: { type: 'text', mime_type: 'application/json', schema: forGemini(prompt.schema) },
  };
}

/** generateContent body (fallback). No temperature: Gemini 3 models are meant to run at the default 1.0. */
export function generateContentRequest(prompt: GeminiPrompt, options: GeminiOptions): Record<string, unknown> {
  return {
    systemInstruction: { parts: [{ text: prompt.system }] },
    contents: [{
      role: 'user',
      parts: [{ inlineData: { mimeType: 'image/jpeg', data: prompt.imageBase64 } }, { text: prompt.text }],
    }],
    generationConfig: {
      responseMimeType: 'application/json',
      responseJsonSchema: forGemini(prompt.schema),
      thinkingConfig: { thinkingLevel: options.thinkingLevel },
      mediaResolution: options.mediaResolution,
    },
  };
}

/** 429 (quota) and 5xx (overloaded / internal) are worth trying on a sibling model. */
function isRetryable(status: number): boolean {
  return status === 429 || status >= 500;
}

function isTimeout(err: unknown): boolean {
  const name = (err as { name?: unknown } | null)?.name;
  return name === 'TimeoutError' || name === 'AbortError';
}

type Attempt =
  | { ok: true; json: unknown; usage: AIUsage }
  | { ok: false; status: number; message: string };

export interface GeminiResult {
  text: string;
  usage: AIUsage;
  /** Non-fatal problems worth logging, e.g. an Interactions 400 that generateContent then answered. */
  warnings: string[];
}

export interface GeminiTiming {
  attemptMs?: number;
  totalMs?: number;
}

/** Runs the model chain and returns the first answer's text. Throws GeminiError (with `billed`). */
export async function callGemini(
  config: GeminiConfig,
  prompt: GeminiPrompt,
  fetchImpl: typeof fetch = fetch,
  timing: GeminiTiming = {},
): Promise<GeminiResult> {
  const spec = loadSpec();
  const options: GeminiOptions = {
    thinkingLevel: config.thinkingLevel ?? spec.providers.gemini.thinkingLevel,
    resolution: spec.providers.gemini.imageResolution,
    mediaResolution: spec.providers.gemini.generateContentMediaResolution,
  };
  const headers = { 'content-type': 'application/json', 'x-goog-api-key': config.apiKey };
  const models = [...new Set([config.model, ...(config.fallbackModels ?? [])])];
  const attemptMs = timing.attemptMs ?? ATTEMPT_TIMEOUT_MS;
  const overall = AbortSignal.timeout(timing.totalMs ?? TOTAL_BUDGET_MS);
  let billed = false;
  let lastError: GeminiError | null = null;
  const warnings: string[] = [];

  /** One POST; the body is read inside the guarded region so a timeout mid-body is still a GeminiError. */
  const attempt = async (model: string, api: AIUsage['api'], url: string, body: unknown): Promise<Attempt> => {
    const started = performance.now();
    const signal = AbortSignal.any([overall, AbortSignal.timeout(attemptMs)]);
    try {
      const response = await fetchImpl(url, { method: 'POST', headers, body: JSON.stringify(body), signal });
      const raw = await response.text();
      if (!response.ok) {
        let message = '';
        try {
          message = String((JSON.parse(raw) as { error?: { message?: unknown } }).error?.message ?? '');
        } catch {
          message = raw.slice(0, 200);
        }
        return { ok: false, status: response.status, message: message.slice(0, 300) };
      }
      billed = true;
      let json: unknown;
      try {
        json = JSON.parse(raw);
      } catch {
        throw new GeminiError(`Gemini answered with a non-JSON body (${model})`, 502, 'upstream', true);
      }
      return { ok: true, json, usage: { model, api, ...extractUsage(json), ms: Math.round(performance.now() - started) } };
    } catch (err) {
      if (err instanceof GeminiError) throw err;
      if (isTimeout(err)) {
        // The request left the server; Google may still have processed (and billed) it.
        billed = true;
        return { ok: false, status: -1, message: 'timeout' };
      }
      return { ok: false, status: 0, message: 'network' };
    }
  };

  for (const model of models) {
    if (overall.aborted) break;
    let result = await attempt(model, 'interactions', `${GEMINI_BASE_URL}/v1beta/interactions`, interactionsRequest(model, prompt, options));
    if (!result.ok && (result.status === 404 || result.status === 400)) {
      // 404: the model is not served by Interactions. 400: this Interactions request shape was refused;
      // generateContent uses long-established fields, and a bad key fails there too.
      if (result.status === 400) warnings.push(`interactions 400 (${model}): ${result.message}`);
      result = await attempt(
        model,
        'generateContent',
        `${GEMINI_BASE_URL}/v1beta/models/${encodeURIComponent(model)}:generateContent`,
        generateContentRequest(prompt, options),
      );
    }
    if (result.ok) {
      const text = extractText(result.json);
      if (!text) throw new GeminiError(`Gemini response contained no text (${model})`, 502, 'upstream', true);
      return { text, usage: result.usage, warnings };
    }
    if (result.status === -1) {
      lastError = new GeminiError(`Gemini timed out (${model})`, 504, 'timeout', billed);
      continue;
    }
    if (result.status === 0) {
      lastError = new GeminiError(`Gemini unreachable (${model})`, 503, 'network', billed);
      continue;
    }
    lastError = new GeminiError(`Gemini failed with HTTP ${result.status} (${model}): ${result.message}`, result.status,
      isRetryable(result.status) ? 'busy' : 'upstream', billed);
    // Overloaded or rate-limited: fall through to the next model in the chain.
    if (isRetryable(result.status)) continue;
    throw lastError;
  }
  if (lastError) throw new GeminiError(lastError.message, lastError.status, lastError.kind, billed);
  throw new GeminiError('no Gemini model configured', 503, 'upstream', billed);
}

// MARK: - Photo estimate

export interface EstimateInput {
  imageBase64: string;
  meal: MealSlot;
  locale: string;
  notes?: string;
}

export interface EstimateResult {
  estimate: FinalEstimate;
  usage: AIUsage;
  warnings: string[];
}

export function estimatePrompt(spec: AISpec, input: EstimateInput): GeminiPrompt {
  return {
    system: estimateSystemInstruction(spec, input.locale),
    text: estimateRequestText(spec, input.meal, input.notes ?? ''),
    schema: estimateSchema(spec),
    imageBase64: input.imageBase64,
  };
}

export async function estimateFood(
  config: GeminiConfig,
  input: EstimateInput,
  fetchImpl: typeof fetch = fetch,
  timing: GeminiTiming = {},
): Promise<EstimateResult> {
  const spec = loadSpec();
  const { text, usage, warnings } = await callGemini(config, estimatePrompt(spec, input), fetchImpl, timing);
  let estimate: FinalEstimate;
  try {
    estimate = finalizeEstimateText(text, spec, notesContext(spec, input.notes ?? ''));
  } catch (err) {
    if (err instanceof AIOutputError) throw new AIParseError(err.message, err.code);
    throw err;
  }
  // Exact product codes only. Never fuzzy-match a pictured meal to an unrelated product.
  await Promise.all(estimate.foods.slice(0, 8).map(async (food) => {
    if (!food.barcode) return;
    const nutrition = await lookupNutrition(food.barcode, fetchImpl);
    if (nutrition) groundWithDatabase(estimate, food, nutrition, 'open_food_facts');
  }));
  return { estimate, usage, warnings };
}

// MARK: - Nutrition label

export interface LabelInput {
  imageBase64: string;
  locale: string;
}

export interface LabelResult {
  reading: LabelReading;
  usage: AIUsage;
  warnings: string[];
}

export function labelPrompt(spec: AISpec, input: LabelInput): GeminiPrompt {
  return {
    system: labelSystemInstruction(spec, input.locale),
    text: labelRequestText(spec),
    schema: labelSchema(spec),
    imageBase64: input.imageBase64,
  };
}

export async function readLabel(
  config: GeminiConfig,
  input: LabelInput,
  fetchImpl: typeof fetch = fetch,
  timing: GeminiTiming = {},
): Promise<LabelResult> {
  const spec = loadSpec();
  const { text, usage, warnings } = await callGemini(config, labelPrompt(spec, input), fetchImpl, timing);
  try {
    return { reading: finalizeLabelText(text, spec), usage, warnings };
  } catch (err) {
    if (err instanceof AIOutputError) throw new AIParseError(err.message, err.code);
    throw err;
  }
}
