import type { AISpec, Per100 } from './aiSpec.js';
import type { NotesContext } from './aiSpec.js';

/**
 * Reference finalizer for the AI food estimate (schema v2) and the nutrition-label read.
 *
 * Every step and every rounding rule here is part of the cross-platform contract: the iOS and Android
 * bring-your-own-key paths port this file 1:1 and are tested against `data/ai/fixtures/*.json`.
 * Keep operations in the documented order; floating-point results must match bit for bit before rounding.
 */

// MARK: - Numbers

/** Half-up to one decimal for non-negative values: floor(x * 10 + 0.5) / 10. */
export function round1(x: number): number {
  return Math.floor(x * 10 + 0.5) / 10;
}

/** Half-up to two decimals for non-negative values: floor(x * 100 + 0.5) / 100. */
export function round2(x: number): number {
  return Math.floor(x * 100 + 0.5) / 100;
}

export function clamp01(x: number): number {
  return Math.min(1, Math.max(0, x));
}

const NUMERIC_STRING = /^[-+]?\d+(?:[.,]\d+)?$/;

/** A finite JSON number, or a string like "12", "-3", "4,5", "4.5" (trimmed); anything else is null. */
export function num(value: unknown): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (!NUMERIC_STRING.test(trimmed)) return null;
    const parsed = Number(trimmed.replace(',', '.'));
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

/** The first key whose value parses as a number. */
function firstNum(obj: Record<string, unknown>, keys: string[]): number | null {
  for (const key of keys) {
    const value = num(obj[key]);
    if (value !== null) return value;
  }
  return null;
}

/** Trimmed string, cut to `max` UTF-16 code units; non-strings become "". */
function text(value: unknown, max: number): string {
  return typeof value === 'string' ? value.trim().slice(0, max) : '';
}

function isTrue(value: unknown): boolean {
  return value === true || (typeof value === 'string' && value.trim().toLowerCase() === 'true');
}

function shortStrings(value: unknown, maxCount: number, maxLength: number): string[] {
  if (!Array.isArray(value)) return [];
  const out: string[] = [];
  for (const item of value) {
    if (out.length >= maxCount) break;
    const s = text(item, maxLength);
    if (s) out.push(s);
  }
  return out;
}

/** GTIN-8/12/13/14 with a valid mod-10 check digit. */
export function validGtin(code: string): boolean {
  if (!/^(?:\d{8}|\d{12}|\d{13}|\d{14})$/.test(code)) return false;
  const digits = [...code].map(Number);
  const check = digits.pop()!;
  let sum = 0;
  for (let i = 0; i < digits.length; i++) {
    // Weight 3 on the digit next to the check digit, then alternating 1, 3, …
    sum += digits[digits.length - 1 - i]! * (i % 2 === 0 ? 3 : 1);
  }
  return (10 - (sum % 10)) % 10 === check;
}

// MARK: - Model text → JSON

export type AIOutputErrorCode = 'no_json' | 'invalid_json' | 'not_object' | 'no_foods_array';

/** The model's answer cannot be used at all (as opposed to single items, which are skipped). */
export class AIOutputError extends Error {
  constructor(readonly code: AIOutputErrorCode) {
    super(`model output unusable: ${code}`);
    this.name = 'AIOutputError';
  }
}

/** Removes every ``` / ```json / ```JSON fence, then returns the text from the first `{` to the last `}`. */
export function extractJsonObject(input: string): string | null {
  const unfenced = input.replace(/```(?:json|JSON)?/g, '');
  const start = unfenced.indexOf('{');
  const end = unfenced.lastIndexOf('}');
  if (start === -1 || end === -1 || end <= start) return null;
  return unfenced.slice(start, end + 1);
}

/**
 * Model text → JSON value. Tries, in order: strict JSON; with trailing commas removed
 * (`,` + spaces/tabs/newlines before `}` or `]`); additionally with every `'` replaced by `"`.
 */
export function parseModelJson(input: string): unknown {
  const json = extractJsonObject(input);
  if (json === null) throw new AIOutputError('no_json');
  const attempts = [json];
  const noTrailingCommas = json.replace(/,[ \t\r\n]*([}\]])/g, '$1');
  attempts.push(noTrailingCommas, noTrailingCommas.replace(/'/g, '"'));
  for (const attempt of attempts) {
    try {
      return JSON.parse(attempt);
    } catch {
      // next repair
    }
  }
  throw new AIOutputError('invalid_json');
}

// MARK: - Estimate

export type NutritionSource = 'estimated' | 'generic_table' | 'visible_label' | 'user_notes' | 'open_food_facts';

export const COOKING = ['raw', 'boiled', 'steamed', 'stewed', 'baked', 'grilled', 'pan_fried', 'deep_fried', 'prepared', 'packaged'] as const;
const MODEL_SOURCES = ['visible_label', 'user_notes', 'estimated'] as const;

export type Adjustment =
  | 'grams_from_portion'
  | 'generic_table'
  | 'per100_from_totals'
  | 'energy_repaired'
  | 'energy_mismatch_kept'
  | 'portion_normalized'
  | 'confidence_capped'
  | 'open_food_facts';

export type SkipReason = 'not_object' | 'no_name' | 'invalid_grams' | 'no_nutrition' | 'invalid_per100' | 'too_many_items';

export interface FinalFood {
  name: string;
  grams: number;
  /** Totals for `grams`, computed from the rounded `per100`. */
  kcal: number;
  protein: number;
  carbs: number;
  fat: number;
  /** Legacy aliases of protein/carbs/fat kept for old app builds. */
  proteinG: number;
  carbsG: number;
  fatG: number;
  confidence: number;
  isGuess: boolean;
  barcode: string;
  nutritionSource: NutritionSource;
  cooking: string;
  genericKey: string;
  portionCount: number;
  portionUnit: string;
  gramsPerUnit: number;
  per100: Per100;
  adjustments: Adjustment[];
}

export interface Totals {
  kcal: number;
  protein: number;
  carbs: number;
  fat: number;
}

export interface SkippedItem {
  index: number;
  name: string;
  reason: SkipReason;
}

export interface FinalEstimate {
  version: 2;
  foods: FinalFood[];
  totals: Totals;
  overallConfidence: number;
  scaleReferenceUsed: string;
  assumptions: string[];
  questions: string[];
  skipped: SkippedItem[];
}

function atwaterEnergy(spec: AISpec, p: Per100): number {
  const a = spec.atwater;
  return a.protein * p.protein + a.carbs * p.carbs + a.fat * p.fat + a.alcohol * p.alcohol;
}

function roundPer100(p: Per100): Per100 {
  return { kcal: round1(p.kcal), protein: round1(p.protein), carbs: round1(p.carbs), fat: round1(p.fat), alcohol: round1(p.alcohol) };
}

/** Sets per100 (rounded) and recomputes the item totals from it and the item's grams. */
function setNutrition(food: FinalFood, per100: Per100): void {
  const p = roundPer100(per100);
  food.per100 = p;
  food.kcal = round1(p.kcal * food.grams / 100);
  food.protein = food.proteinG = round1(p.protein * food.grams / 100);
  food.carbs = food.carbsG = round1(p.carbs * food.grams / 100);
  food.fat = food.fatG = round1(p.fat * food.grams / 100);
}

export function computeTotals(foods: FinalFood[]): Totals {
  let kcal = 0;
  let protein = 0;
  let carbs = 0;
  let fat = 0;
  for (const food of foods) {
    kcal += food.kcal;
    protein += food.protein;
    carbs += food.carbs;
    fat += food.fat;
  }
  return { kcal: round1(kcal), protein: round1(protein), carbs: round1(carbs), fat: round1(fat) };
}

/** Server-side grounding after finalize (barcode → Open Food Facts); updates the item and the estimate totals. */
export function groundWithDatabase(estimate: FinalEstimate, food: FinalFood, per100: Omit<Per100, 'alcohol'> & { alcohol?: number }, source: NutritionSource): void {
  setNutrition(food, { kcal: per100.kcal, protein: per100.protein, carbs: per100.carbs, fat: per100.fat, alcohol: per100.alcohol ?? 0 });
  food.nutritionSource = source;
  if (source === 'open_food_facts') food.adjustments.push('open_food_facts');
  estimate.totals = computeTotals(estimate.foods);
}

/**
 * Raw model JSON (schema v2, or a legacy v1 reply with totals only) → the finalized estimate.
 * Throws AIOutputError only when the answer has no foods array at all; bad items are skipped.
 */
export function finalizeEstimate(raw: unknown, spec: AISpec, ctx: NotesContext): FinalEstimate {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new AIOutputError('not_object');
  const obj = raw as Record<string, unknown>;
  const list = Array.isArray(obj.foods) ? obj.foods : Array.isArray(obj.items) ? obj.items : null;
  if (!list) throw new AIOutputError('no_foods_array');

  const L = spec.limits;
  const capActive = !ctx.weightGiven && !ctx.measuredReference;
  const table = new Map(spec.genericFoods.map((food) => [food.key, food]));
  const foods: FinalFood[] = [];
  const skipped: SkippedItem[] = [];

  list.forEach((item, index) => {
    if (index >= L.maxItems) {
      const name = item && typeof item === 'object' ? text((item as Record<string, unknown>).name, L.maxNameLength) : '';
      skipped.push({ index, name, reason: 'too_many_items' });
      return;
    }
    if (!item || typeof item !== 'object' || Array.isArray(item)) {
      skipped.push({ index, name: '', reason: 'not_object' });
      return;
    }
    const f = item as Record<string, unknown>;
    const adjustments: Adjustment[] = [];

    // 1. Name.
    const name = text(f.name, L.maxNameLength);
    if (!name) {
      skipped.push({ index, name: '', reason: 'no_name' });
      return;
    }

    // 2. Grams, repaired from count × grams per unit when missing or out of range.
    const countRaw = num(f.portionCount);
    let count = countRaw !== null && countRaw > 0 ? round1(countRaw) : null;
    if (count !== null && count <= 0) count = null;
    const perUnitRaw = num(f.gramsPerUnit);
    const perUnit = perUnitRaw !== null && perUnitRaw > 0 ? perUnitRaw : null;
    const gramsOk = (g: number | null): g is number => g !== null && g > 0 && g <= L.maxGrams;
    let grams = firstNum(f, ['grams', 'estimatedGrams', 'estimated_grams']);
    if (!gramsOk(grams) && count !== null && perUnit !== null) {
      grams = count * perUnit;
      adjustments.push('grams_from_portion');
    }
    if (!gramsOk(grams)) {
      skipped.push({ index, name, reason: 'invalid_grams' });
      return;
    }
    grams = round1(grams);
    if (grams <= 0) {
      skipped.push({ index, name, reason: 'invalid_grams' });
      return;
    }

    // 3. Per-100 g nutrition: generic table, the model's per100, or legacy totals.
    const modelSource = typeof f.nutritionSource === 'string' && (MODEL_SOURCES as readonly string[]).includes(f.nutritionSource)
      ? (f.nutritionSource as NutritionSource)
      : 'estimated';
    let source: NutritionSource = modelSource;
    const keyRaw = typeof f.genericKey === 'string' ? f.genericKey.trim() : '';
    const tableRow = table.get(keyRaw);
    const genericKey = tableRow ? keyRaw : 'none';
    let per100: Per100 | null = null;
    if (tableRow && source === 'estimated') {
      per100 = { ...tableRow.per100 };
      source = 'generic_table';
      adjustments.push('generic_table');
    } else if (f.per100 && typeof f.per100 === 'object' && !Array.isArray(f.per100)) {
      const d = f.per100 as Record<string, unknown>;
      const kcal = num(d.kcal), protein = num(d.protein), carbs = num(d.carbs), fat = num(d.fat);
      const alcohol = num(d.alcohol) ?? 0;
      if (kcal === null || protein === null || carbs === null || fat === null) {
        skipped.push({ index, name, reason: 'invalid_per100' });
        return;
      }
      per100 = { kcal, protein, carbs, fat, alcohol };
    } else {
      const kcal = firstNum(f, ['kcal', 'calories']);
      if (kcal === null) {
        skipped.push({ index, name, reason: 'no_nutrition' });
        return;
      }
      const protein = firstNum(f, ['proteinG', 'protein_g', 'protein']) ?? 0;
      const carbs = firstNum(f, ['carbsG', 'carbs_g', 'carbs', 'carbohydrates']) ?? 0;
      const fat = firstNum(f, ['fatG', 'fat_g', 'fat']) ?? 0;
      per100 = { kcal: kcal * 100 / grams, protein: protein * 100 / grams, carbs: carbs * 100 / grams, fat: fat * 100 / grams, alcohol: 0 };
      adjustments.push('per100_from_totals');
    }

    // 4. Plausibility.
    const values = [per100.kcal, per100.protein, per100.carbs, per100.fat, per100.alcohol];
    if (values.some((v) => v < 0) || per100.kcal > L.maxKcalPer100
      || per100.protein + per100.carbs + per100.fat + per100.alcohol > L.maxMassPer100) {
      skipped.push({ index, name, reason: 'invalid_per100' });
      return;
    }

    // 5. Energy consistency (Atwater 4/4/9/7).
    const energy = atwaterEnergy(spec, per100);
    const tolerance = spec.atwater.tolerance[source as keyof AISpec['atwater']['tolerance']];
    const allowed = Math.max(tolerance.abs, tolerance.rel * energy);
    let repaired = false;
    if (Math.abs(per100.kcal - energy) > allowed) {
      if (tolerance.repair) {
        per100.kcal = Math.min(energy, L.maxKcalPer100);
        repaired = true;
        adjustments.push('energy_repaired');
      } else {
        adjustments.push('energy_mismatch_kept');
      }
    }

    // 6. Portion fields, consistent with grams.
    let portionCount: number;
    let gramsPerUnit: number;
    if (count === null) {
      portionCount = 1;
      gramsPerUnit = grams;
    } else if (perUnit !== null && Math.abs(count * perUnit - grams) <= L.portionMismatchGrams) {
      portionCount = count;
      gramsPerUnit = round1(perUnit);
    } else {
      portionCount = count;
      gramsPerUnit = round1(grams / count);
      adjustments.push('portion_normalized');
    }

    // 7. Confidence.
    let confidence = num(f.confidence) ?? L.defaultConfidence;
    if (repaired) confidence -= L.energyRepairPenalty;
    confidence = clamp01(confidence);
    if (capActive && confidence > L.confidenceCapWithoutReference) {
      confidence = L.confidenceCapWithoutReference;
      adjustments.push('confidence_capped');
    }

    const cooking = typeof f.cooking === 'string' && (COOKING as readonly string[]).includes(f.cooking) ? f.cooking : 'prepared';
    const barcodeRaw = typeof f.barcode === 'string' ? f.barcode.trim() : '';
    const food: FinalFood = {
      name,
      grams,
      kcal: 0, protein: 0, carbs: 0, fat: 0, proteinG: 0, carbsG: 0, fatG: 0,
      confidence: round2(confidence),
      isGuess: isTrue(f.isGuess) || isTrue(f.is_guess),
      barcode: /^\d{8,14}$/.test(barcodeRaw) ? barcodeRaw : '',
      nutritionSource: source,
      cooking,
      genericKey,
      portionCount,
      portionUnit: text(f.portionUnit, L.maxUnitLength),
      gramsPerUnit,
      per100,
      adjustments,
    };
    setNutrition(food, per100);
    foods.push(food);
  });

  // Whole estimate.
  const modelOverall = firstNum(obj, ['overallConfidence', 'overall_confidence']);
  let overall = modelOverall ?? (foods.length > 0 ? foods.reduce((sum, food) => sum + food.confidence, 0) / foods.length : 0);
  overall = clamp01(overall);
  if (capActive && overall > L.confidenceCapWithoutReference) overall = L.confidenceCapWithoutReference;

  return {
    version: 2,
    foods,
    totals: computeTotals(foods),
    overallConfidence: round2(overall),
    scaleReferenceUsed: text(obj.scaleReferenceUsed, L.maxScaleReferenceLength) || 'none',
    assumptions: shortStrings(obj.assumptions, L.maxAssumptions, L.maxTextLength),
    questions: shortStrings(obj.questions, L.maxQuestions, L.maxTextLength),
    skipped,
  };
}

/** Model text (fences, prose and small JSON slips tolerated) → finalized estimate. */
export function finalizeEstimateText(input: string, spec: AISpec, ctx: NotesContext): FinalEstimate {
  return finalizeEstimate(parseModelJson(input), spec, ctx);
}

// MARK: - Nutrition label

export type LabelBasis = 'per100g' | 'per100ml' | 'perServing';
export type UnreadableReason = 'illegible' | 'no_energy' | 'incomplete' | 'no_serving_size' | 'implausible';

export interface LabelPer100 {
  kcal: number;
  protein: number;
  carbs: number;
  fat: number;
  fiber: number | null;
  sugar: number | null;
  salt: number | null;
}

export interface LabelReading {
  version: 1;
  legible: boolean;
  unreadableReason: UnreadableReason | null;
  /** The column the numbers were read from; per100 is always per 100 g (100 ml counts as 100 g). */
  basis: LabelBasis;
  energyFrom: 'kcal' | 'kj' | null;
  name: string;
  brand: string;
  per100: LabelPer100 | null;
  servingSizeG: number | null;
  packageSizeG: number | null;
  barcode: string;
  confidence: number;
  needsReview: boolean;
}

const BASES: readonly LabelBasis[] = ['per100g', 'per100ml', 'perServing'];

/** Raw label JSON → per-100 g values. A readable answer that is not a usable table is `legible: false`, not an error. */
export function finalizeLabel(raw: unknown, spec: AISpec): LabelReading {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new AIOutputError('not_object');
  const obj = raw as Record<string, unknown>;
  const L = spec.limits;
  const S = spec.label;

  const basis: LabelBasis = typeof obj.basis === 'string' && (BASES as readonly string[]).includes(obj.basis) ? (obj.basis as LabelBasis) : 'per100g';
  const size = (value: unknown, max: number): number | null => {
    const n = num(value);
    if (n === null || n <= 0 || n > max) return null;
    const rounded = round1(n);
    return rounded > 0 ? rounded : null;
  };
  const servingSizeG = size(obj.servingSizeG, S.maxServingSizeG);
  const packageSizeG = size(obj.packageSizeG, S.maxPackageSizeG);
  const barcodeRaw = typeof obj.barcode === 'string' ? obj.barcode.trim() : '';
  const confidence = round2(clamp01(num(obj.confidence) ?? L.defaultConfidence));

  const reading: LabelReading = {
    version: 1,
    legible: false,
    unreadableReason: 'illegible',
    basis,
    energyFrom: null,
    name: text(obj.name, L.maxNameLength),
    brand: text(obj.brand, L.maxBrandLength),
    per100: null,
    servingSizeG,
    packageSizeG,
    barcode: validGtin(barcodeRaw) ? barcodeRaw : '',
    confidence,
    needsReview: false,
  };
  const fail = (reason: UnreadableReason): LabelReading => ({ ...reading, unreadableReason: reason });

  if (!isTrue(obj.legible)) return fail('illegible');
  const values = obj.values && typeof obj.values === 'object' && !Array.isArray(obj.values) ? (obj.values as Record<string, unknown>) : {};
  /** Printed value, or null for missing / negative (the -1 "not printed" sentinel). */
  const printed = (key: string): number | null => {
    const n = num(values[key]);
    return n === null || n < 0 ? null : n;
  };

  let kcal = printed('kcal');
  let energyFrom: 'kcal' | 'kj' = 'kcal';
  if (kcal === null) {
    const kj = printed('kj');
    if (kj === null) return fail('no_energy');
    kcal = kj / spec.atwater.kjPerKcal;
    energyFrom = 'kj';
  }
  let protein = printed('protein'), carbs = printed('carbs'), fat = printed('fat');
  if (protein === null || carbs === null || fat === null) return fail('incomplete');
  let fiber = printed('fiber'), sugar = printed('sugar'), salt = printed('salt');

  if (basis === 'perServing') {
    if (servingSizeG === null) return fail('no_serving_size');
    const scale = (v: number): number => v * 100 / servingSizeG;
    kcal = scale(kcal);
    protein = scale(protein);
    carbs = scale(carbs);
    fat = scale(fat);
    fiber = fiber === null ? null : scale(fiber);
    sugar = sugar === null ? null : scale(sugar);
    salt = salt === null ? null : scale(salt);
  }

  if (kcal > L.maxKcalPer100 || protein + carbs + fat + (fiber ?? 0) > L.maxMassPer100) return fail('implausible');

  const per100: LabelPer100 = {
    kcal: round1(kcal),
    protein: round1(protein),
    carbs: round1(carbs),
    fat: round1(fat),
    fiber: fiber === null ? null : round1(fiber),
    sugar: sugar === null ? null : round1(sugar),
    // Salt is printed with two decimals (0,63 g), everything else with at most one.
    salt: salt === null ? null : round2(salt),
  };
  const a = spec.atwater;
  const energy = a.protein * per100.protein + a.carbs * per100.carbs + a.fat * per100.fat + a.fiber * (per100.fiber ?? 0);
  const allowed = Math.max(S.reviewTolerance.abs, S.reviewTolerance.rel * per100.kcal);
  const needsReview = Math.abs(per100.kcal - energy) > allowed
    || (per100.sugar !== null && per100.sugar > per100.carbs)
    || confidence < S.reviewBelowConfidence;

  return { ...reading, legible: true, unreadableReason: null, energyFrom, per100, needsReview };
}

export function finalizeLabelText(input: string, spec: AISpec): LabelReading {
  return finalizeLabel(parseModelJson(input), spec);
}
