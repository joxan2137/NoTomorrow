import type { GeminiConfig } from './env.js';
import { normalizeLocale } from './i18n.js';

/**
 * Gemini food-photo estimation. Interactions API first, `generateContent` when the model is
 * not served there (404). Parsing is deliberately forgiving: models fence, prefix prose, or
 * emit numbers as strings, and the app would rather get a rough estimate than an error.
 */

export const GEMINI_BASE_URL = 'https://generativelanguage.googleapis.com';
export const MEAL_SLOTS = ['breakfast', 'lunch', 'snack', 'dinner'] as const;
export type MealSlot = (typeof MEAL_SLOTS)[number];

export interface AIFood {
  name: string;
  grams: number;
  kcal: number;
  proteinG: number;
  carbsG: number;
  fatG: number;
  /** Aliases of the *G fields: the iOS `AIFood` decoder reads `protein`/`carbs`/`fat`. */
  protein: number;
  carbs: number;
  fat: number;
  confidence: number;
  isGuess: boolean;
}

export interface AIEstimate {
  foods: AIFood[];
  overallConfidence: number;
  scaleReferenceUsed: string;
}

export const RESPONSE_SCHEMA = {
  type: 'object',
  properties: {
    foods: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          name: { type: 'string' },
          grams: { type: 'number' },
          kcal: { type: 'number' },
          proteinG: { type: 'number' },
          carbsG: { type: 'number' },
          fatG: { type: 'number' },
          confidence: { type: 'number' },
          isGuess: { type: 'boolean' },
        },
        required: ['name', 'grams', 'kcal', 'proteinG', 'carbsG', 'fatG', 'confidence', 'isGuess'],
      },
    },
    overallConfidence: { type: 'number' },
    scaleReferenceUsed: { type: 'string' },
  },
  required: ['foods', 'overallConfidence', 'scaleReferenceUsed'],
} as const;

const LOCALE_NAMES: Record<'en' | 'pl', string> = { en: 'English', pl: 'Polish' };

export function buildPrompt(locale: string, meal: MealSlot): string {
  const language = LOCALE_NAMES[normalizeLocale(locale)];
  return [
    `You are a nutrition estimator for a fitness app. The photo shows a ${meal}.`,
    'Identify each distinct food item that is visible and list it separately.',
    'Estimate the portion of each item in grams using visible scale references: a dinner plate is about 26–28 cm across,',
    'a fork is about 19 cm long, a fist is about 150 g of cooked rice, a palm is about 100–120 g of cooked meat,',
    'a thumb is about 1 tablespoon (≈ 14 g) of fat.',
    'When cooking fat (oil, butter) is likely but not visible, add it as its own item with "isGuess": true.',
    'Do not underestimate large or oily portions; when unsure between two sizes, pick the larger.',
    'Compute kcal, protein, carbohydrates and fat for the estimated grams from standard nutrition tables (USDA or national food composition data).',
    `Write each item's "name" in ${language}, short, as a person would say it. All other fields are numbers or booleans.`,
    '"confidence" and "overallConfidence" are between 0 and 1. "scaleReferenceUsed" names the reference you relied on, or "none".',
    'Respond with JSON only, matching this schema exactly:',
    JSON.stringify(RESPONSE_SCHEMA),
  ].join('\n');
}

export class GeminiError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message);
    this.name = 'GeminiError';
  }
}

export class AIParseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'AIParseError';
  }
}

const round1 = (n: number): number => Math.round(n * 10) / 10;
const clamp01 = (n: number): number => Math.min(1, Math.max(0, n));

function num(v: unknown): number | null {
  if (typeof v === 'number') return Number.isFinite(v) ? v : null;
  if (typeof v === 'string') {
    const n = Number(v.replace(',', '.').replace(/[^0-9.eE+-]/g, ''));
    return Number.isFinite(n) && v.trim() !== '' ? n : null;
  }
  return null;
}

function pick(obj: Record<string, unknown>, keys: string[]): unknown {
  for (const key of keys) if (key in obj) return obj[key];
  return undefined;
}

/** Strips code fences and surrounding prose, returning the outermost `{…}` or null. */
export function extractJsonObject(text: string): string | null {
  const unfenced = text.replace(/```(?:json|JSON)?/g, '');
  const start = unfenced.indexOf('{');
  const end = unfenced.lastIndexOf('}');
  if (start === -1 || end === -1 || end <= start) return null;
  return unfenced.slice(start, end + 1);
}

function parseLoose(json: string): unknown {
  try {
    return JSON.parse(json);
  } catch {
    // Common model slips: trailing commas, single-quoted strings.
    const repaired = json.replace(/,\s*([}\]])/g, '$1').replace(/'/g, '"');
    return JSON.parse(repaired);
  }
}

export function parseEstimate(text: string): AIEstimate {
  const json = extractJsonObject(text);
  if (!json) throw new AIParseError('no JSON object in model output');
  let raw: unknown;
  try {
    raw = parseLoose(json);
  } catch {
    throw new AIParseError('model output is not valid JSON');
  }
  if (!raw || typeof raw !== 'object') throw new AIParseError('model output is not an object');
  const obj = raw as Record<string, unknown>;
  const list = Array.isArray(obj.foods) ? obj.foods : Array.isArray(obj.items) ? obj.items : [];

  const foods: AIFood[] = [];
  for (const item of list) {
    if (!item || typeof item !== 'object') continue;
    const f = item as Record<string, unknown>;
    const name = typeof f.name === 'string' ? f.name.trim() : '';
    if (!name) continue;
    const grams = Math.max(0, num(f.grams) ?? 0);
    const kcal = Math.max(0, num(pick(f, ['kcal', 'calories'])) ?? 0);
    const protein = Math.max(0, num(pick(f, ['proteinG', 'protein_g', 'protein'])) ?? 0);
    const carbs = Math.max(0, num(pick(f, ['carbsG', 'carbs_g', 'carbs', 'carbohydrates'])) ?? 0);
    const fat = Math.max(0, num(pick(f, ['fatG', 'fat_g', 'fat'])) ?? 0);
    const confidence = clamp01(num(f.confidence) ?? 0.5);
    const isGuess = f.isGuess === true || f.isGuess === 'true' || f.is_guess === true;
    foods.push({
      name: name.slice(0, 80),
      grams: round1(grams),
      kcal: round1(kcal),
      proteinG: round1(protein),
      carbsG: round1(carbs),
      fatG: round1(fat),
      protein: round1(protein),
      carbs: round1(carbs),
      fat: round1(fat),
      confidence: round1(confidence),
      isGuess,
    });
  }

  const mean = foods.length > 0 ? foods.reduce((s, f) => s + f.confidence, 0) / foods.length : 0;
  const overall = num(pick(obj, ['overallConfidence', 'overall_confidence']));
  return {
    foods,
    overallConfidence: round1(clamp01(overall ?? mean)),
    scaleReferenceUsed: typeof obj.scaleReferenceUsed === 'string' ? obj.scaleReferenceUsed.slice(0, 120) : 'none',
  };
}

/** Pulls the model's text out of either API's response shape. */
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
    if (typeof o.text === 'string' && (o.type === undefined || o.type === 'text')) texts.push(o.text);
    for (const key of ['outputs', 'output', 'candidates', 'content', 'parts']) if (key in o) visit(o[key], depth + 1);
  };
  visit(root, 0);
  return texts.length > 0 ? texts.join('') : null;
}

/** Gemini's `responseSchema` is an OpenAPI subset with upper-case type names. */
function toOpenApiSchema(schema: unknown): unknown {
  if (Array.isArray(schema)) return schema.map(toOpenApiSchema);
  if (!schema || typeof schema !== 'object') return schema;
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(schema as Record<string, unknown>)) {
    out[k] = k === 'type' && typeof v === 'string' ? v.toUpperCase() : toOpenApiSchema(v);
  }
  return out;
}

export function interactionsRequest(model: string, prompt: string, imageBase64: string): Record<string, unknown> {
  return {
    model,
    input: [
      { type: 'text', text: prompt },
      { type: 'image', data: imageBase64, mime_type: 'image/jpeg' },
    ],
    response_format: { type: 'text', mime_type: 'application/json', schema: RESPONSE_SCHEMA },
  };
}

export function generateContentRequest(prompt: string, imageBase64: string): Record<string, unknown> {
  return {
    contents: [{ role: 'user', parts: [{ text: prompt }, { inlineData: { mimeType: 'image/jpeg', data: imageBase64 } }] }],
    generationConfig: { responseMimeType: 'application/json', responseSchema: toOpenApiSchema(RESPONSE_SCHEMA), temperature: 0.2 },
  };
}

export interface EstimateInput {
  imageBase64: string;
  meal: MealSlot;
  locale: string;
}

export async function estimateFood(config: GeminiConfig, input: EstimateInput, fetchImpl: typeof fetch = fetch): Promise<AIEstimate> {
  const prompt = buildPrompt(input.locale, input.meal);
  const headers = { 'content-type': 'application/json', 'x-goog-api-key': config.apiKey };
  const first = await fetchImpl(`${GEMINI_BASE_URL}/v1beta/interactions`, {
    method: 'POST',
    headers,
    body: JSON.stringify(interactionsRequest(config.model, prompt, input.imageBase64)),
  });
  let json: unknown;
  if (first.status === 404) {
    const second = await fetchImpl(`${GEMINI_BASE_URL}/v1beta/models/${encodeURIComponent(config.model)}:generateContent`, {
      method: 'POST',
      headers,
      body: JSON.stringify(generateContentRequest(prompt, input.imageBase64)),
    });
    if (!second.ok) throw new GeminiError(`generateContent failed with HTTP ${second.status}`, second.status);
    json = await second.json();
  } else if (!first.ok) {
    throw new GeminiError(`interactions failed with HTTP ${first.status}`, first.status);
  } else {
    json = await first.json();
  }
  const text = extractText(json);
  if (!text) throw new GeminiError('Gemini response contained no text');
  return parseEstimate(text);
}
