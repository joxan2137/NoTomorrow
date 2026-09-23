import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/**
 * Loader for `data/ai/estimate-spec.json`, the single source of truth for the AI food estimate and
 * the nutrition-label read. The iOS and Android apps bundle the same file for their bring-your-own-key
 * paths and must assemble prompts, schemas and the notes context exactly as the functions below do;
 * `data/ai/fixtures/prompt-*.json` pins the assembled strings for all three implementations.
 */

export const SPEC_PATH = fileURLToPath(new URL('../data/ai/estimate-spec.json', import.meta.url));
export const FIXTURES_DIR = fileURLToPath(new URL('../data/ai/fixtures/', import.meta.url));

export interface Per100 {
  kcal: number;
  protein: number;
  carbs: number;
  fat: number;
  alcohol: number;
}

export interface GenericFood {
  key: string;
  en: string;
  pl: string;
  per100: Per100;
  unit: { pl: string; en: string; typical: number; min: number; max: number };
  source: string;
}

export interface Tolerance {
  rel: number;
  abs: number;
  repair: boolean;
}

export type JsonSchema = Record<string, unknown>;

export interface AISpec {
  version: number;
  languages: Record<string, string>;
  estimate: {
    promptLines: string[];
    languageLine: string;
    genericFoodFormat: string;
    genericFoodSeparator: string;
    requestTemplate: string;
    schema: JsonSchema;
  };
  label: {
    promptLines: string[];
    languageLine: string;
    requestText: string;
    schema: JsonSchema;
    reviewTolerance: { rel: number; abs: number };
    reviewBelowConfidence: number;
    maxServingSizeG: number;
    maxPackageSizeG: number;
  };
  atwater: {
    protein: number;
    carbs: number;
    fat: number;
    alcohol: number;
    fiber: number;
    kjPerKcal: number;
    tolerance: Record<'estimated' | 'generic_table' | 'visible_label' | 'user_notes', Tolerance>;
  };
  limits: {
    maxItems: number;
    maxGrams: number;
    maxKcalPer100: number;
    maxMassPer100: number;
    portionMismatchGrams: number;
    defaultConfidence: number;
    energyRepairPenalty: number;
    confidenceCapWithoutReference: number;
    maxNameLength: number;
    maxUnitLength: number;
    maxBrandLength: number;
    maxScaleReferenceLength: number;
    maxAssumptions: number;
    maxQuestions: number;
    maxTextLength: number;
    maxNotesLength: number;
  };
  context: { weightPattern: string; referencePattern: string };
  providers: {
    gemini: { model: string; thinkingLevel: string; imageResolution: string; generateContentMediaResolution: string };
    claude: { model: string; anthropicVersion: string; effort: string; estimateMaxTokens: number; labelMaxTokens: number };
  };
  genericFoods: GenericFood[];
}

export class SpecError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SpecError';
  }
}

let cached: AISpec | null = null;

/** Reads and validates the spec. The default path is memoised; any other path is read fresh (tests). */
export function loadSpec(path: string = SPEC_PATH): AISpec {
  if (path === SPEC_PATH && cached) return cached;
  const spec = parseSpec(readFileSync(path, 'utf8'));
  if (path === SPEC_PATH) cached = spec;
  return spec;
}

export function parseSpec(text: string): AISpec {
  const spec = JSON.parse(text) as AISpec;
  if (spec.version !== 2) throw new SpecError(`unsupported spec version ${String(spec.version)}`);
  const keys = new Set<string>();
  for (const food of spec.genericFoods ?? []) {
    if (!/^[a-z0-9_]+$/.test(food.key) || food.key === 'none') throw new SpecError(`bad generic food key ${food.key}`);
    if (keys.has(food.key)) throw new SpecError(`duplicate generic food key ${food.key}`);
    keys.add(food.key);
    const { typical, min, max } = food.unit;
    if (![typical, min, max].every(Number.isInteger) || min > typical || typical > max) throw new SpecError(`bad unit for ${food.key}`);
  }
  if (!genericKeyNode(spec.estimate.schema)) throw new SpecError('estimate schema has no foods.items.properties.genericKey');
  for (const pattern of [spec.context.weightPattern, spec.context.referencePattern]) new RegExp(pattern);
  return spec;
}

function genericKeyNode(schema: JsonSchema): Record<string, unknown> | null {
  const foods = (schema.properties as Record<string, JsonSchema> | undefined)?.foods;
  const items = foods?.items as JsonSchema | undefined;
  const node = (items?.properties as Record<string, unknown> | undefined)?.genericKey;
  return node && typeof node === 'object' ? (node as Record<string, unknown>) : null;
}

/** `pl`, `pl-PL`, `pl_PL` → Polish; everything else → English. */
export function normalizeSpecLocale(locale: string | null | undefined): 'en' | 'pl' {
  const lang = (locale ?? '').trim().toLowerCase().split(/[-_]/)[0];
  return lang === 'pl' ? 'pl' : 'en';
}

export function languageName(spec: AISpec, locale: string): string {
  return spec.languages[normalizeSpecLocale(locale)] ?? spec.languages.en ?? 'English';
}

/** Literal replace-all (no `$` patterns), the same operation as Swift `replacingOccurrences` / Kotlin `replace`. */
export function substitute(template: string, token: string, value: string): string {
  return template.split(token).join(value);
}

/** The `{genericFoods}` substitution: one formatted entry per table row, in file order. */
export function genericFoodsText(spec: AISpec): string {
  return spec.genericFoods
    .map((food) => {
      let entry = spec.estimate.genericFoodFormat;
      entry = substitute(entry, '{key}', food.key);
      entry = substitute(entry, '{pl}', food.pl);
      entry = substitute(entry, '{unit}', food.unit.pl);
      entry = substitute(entry, '{typical}', String(food.unit.typical));
      entry = substitute(entry, '{min}', String(food.unit.min));
      return substitute(entry, '{max}', String(food.unit.max));
    })
    .join(spec.estimate.genericFoodSeparator);
}

/** Static system instruction for the photo estimate: identical for every request in one language. */
export function estimateSystemInstruction(spec: AISpec, locale: string): string {
  const table = genericFoodsText(spec);
  const lines = spec.estimate.promptLines.map((line) => substitute(line, '{genericFoods}', table));
  lines.push(substitute(spec.estimate.languageLine, '{language}', languageName(spec, locale)));
  return lines.join('\n');
}

/**
 * `JSON.stringify` of a string: `"`, `\`, U+0008 `\b`, U+0009 `\t`, U+000A `\n`, U+000C `\f`, U+000D `\r`
 * are escaped; other code points below U+0020 become `\u00xx` (lower-case hex); everything else, `/` and
 * non-ASCII letters included, is copied as is.
 */
export function jsonStringLiteral(value: string): string {
  return JSON.stringify(value);
}

/** Per-request text: meal slot plus the user's notes (first `maxNotesLength` UTF-16 units, JSON-quoted). */
export function estimateRequestText(spec: AISpec, meal: string, notes = ''): string {
  const quoted = jsonStringLiteral(notes.slice(0, spec.limits.maxNotesLength));
  return substitute(substitute(spec.estimate.requestTemplate, '{meal}', meal), '{notes}', quoted);
}

export function labelSystemInstruction(spec: AISpec, locale: string): string {
  return [...spec.label.promptLines, substitute(spec.label.languageLine, '{language}', languageName(spec, locale))].join('\n');
}

export function labelRequestText(spec: AISpec): string {
  return spec.label.requestText;
}

export function genericKeys(spec: AISpec): string[] {
  return [...spec.genericFoods.map((food) => food.key), 'none'];
}

/** The estimate schema with `genericKey.enum` filled in (table keys in file order, then "none"). */
export function estimateSchema(spec: AISpec): JsonSchema {
  const schema = structuredClone(spec.estimate.schema);
  genericKeyNode(schema)!.enum = genericKeys(spec);
  return schema;
}

export function labelSchema(spec: AISpec): JsonSchema {
  return structuredClone(spec.label.schema);
}

/**
 * Gemini gets the same schema with every `additionalProperties` key removed (Claude requires
 * `additionalProperties: false` on every object; Gemini does not need it to stay inside the schema).
 */
export function forGemini(schema: JsonSchema): JsonSchema {
  const strip = (node: unknown): unknown => {
    if (Array.isArray(node)) return node.map(strip);
    if (!node || typeof node !== 'object') return node;
    const out: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(node as Record<string, unknown>)) {
      if (key !== 'additionalProperties') out[key] = strip(value);
    }
    return out;
  };
  return strip(schema) as JsonSchema;
}

export interface NotesContext {
  /** The notes state a mass or volume ("150 g", "0,5 l"). */
  weightGiven: boolean;
  /** The notes state a measured length ("my plate is 26 cm", the app's "Measured reference:" line). */
  measuredReference: boolean;
}

/** Both patterns run case-sensitively on the lower-cased notes (full Unicode lower-casing). */
export function notesContext(spec: AISpec, notes: string): NotesContext {
  const text = notes.toLowerCase();
  return {
    weightGiven: new RegExp(spec.context.weightPattern).test(text),
    measuredReference: new RegExp(spec.context.referencePattern).test(text),
  };
}
