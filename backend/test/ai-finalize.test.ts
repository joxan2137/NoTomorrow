import { describe, expect, it } from 'vitest';
import {
  AIOutputError,
  extractJsonObject,
  finalizeEstimate,
  finalizeLabel,
  groundWithDatabase,
  num,
  parseModelJson,
  round1,
  round2,
  validGtin,
} from '../src/aiFinalize.js';
import {
  FIXTURES_DIR,
  estimateRequestText,
  estimateSchema,
  estimateSystemInstruction,
  forGemini,
  genericKeys,
  labelSystemInstruction,
  loadSpec,
  notesContext,
  parseSpec,
  SPEC_PATH,
} from '../src/aiSpec.js';
import { readFileSync } from 'node:fs';
import { checkFixture, readFixtures } from '../scripts/ai-fixtures-lib.js';

const spec = loadSpec();
const NO_REF = { weightGiven: false, measuredReference: false };
const fixtures = readFixtures(FIXTURES_DIR);

describe('shared AI fixtures (iOS/Android parity lock)', () => {
  it('covers every kind', () => {
    const kinds = new Set(fixtures.map((f) => f.fixture.kind));
    expect([...kinds].sort()).toEqual(['context', 'estimate', 'label', 'prompt']);
    expect(fixtures.length).toBeGreaterThanOrEqual(25);
  });

  it.each(fixtures.map((f) => [f.file, f.fixture] as const))('%s', (_file, fixture) => {
    expect(checkFixture(fixture, spec)).toBeNull();
  });
});

describe('spec', () => {
  it('keeps every generic food inside the limits and the estimated Atwater tolerance', () => {
    const tol = spec.atwater.tolerance.estimated;
    for (const food of spec.genericFoods) {
      const p = food.per100;
      const energy = 4 * p.protein + 4 * p.carbs + 9 * p.fat + 7 * p.alcohol;
      expect(Math.abs(p.kcal - energy), food.key).toBeLessThanOrEqual(Math.max(tol.abs, tol.rel * energy));
      expect(p.kcal, food.key).toBeLessThanOrEqual(spec.limits.maxKcalPer100);
      expect(p.protein + p.carbs + p.fat + p.alcohol, food.key).toBeLessThanOrEqual(spec.limits.maxMassPer100);
    }
  });

  it('assembles prompts with no unfilled placeholders and the schema enum from the table', () => {
    for (const locale of ['pl', 'en']) {
      expect(estimateSystemInstruction(spec, locale)).not.toMatch(/\{(genericFoods|language|key|pl|unit|typical|min|max)\}/);
      expect(labelSystemInstruction(spec, locale)).not.toContain('{language}');
    }
    expect(estimateSystemInstruction(spec, 'pl-PL')).toContain('in Polish.');
    expect(estimateSystemInstruction(spec, 'de')).toContain('in English.');
    expect(estimateSystemInstruction(spec, 'pl')).toContain('pierogi_ruskie = Pierogi ruskie: szt. 35 g (30–45)');
    const keys = genericKeys(spec);
    expect(keys.at(-1)).toBe('none');
    const schema = estimateSchema(spec) as { properties: { foods: { items: { properties: { genericKey: { enum: string[] } } } } } };
    expect(schema.properties.foods.items.properties.genericKey.enum).toEqual(keys);
    // The spec file itself stays untouched by the enum fill.
    expect(JSON.stringify(spec.estimate.schema)).toContain('"enum":[]');
  });

  it('keeps additionalProperties:false on every object for Claude and strips it for Gemini', () => {
    const objects = (node: unknown, out: Record<string, unknown>[] = []): Record<string, unknown>[] => {
      if (Array.isArray(node)) node.forEach((n) => objects(n, out));
      else if (node && typeof node === 'object') {
        const o = node as Record<string, unknown>;
        if (o.type === 'object') out.push(o);
        Object.values(o).forEach((v) => objects(v, out));
      }
      return out;
    };
    for (const schema of [estimateSchema(spec), spec.label.schema]) {
      const all = objects(schema);
      expect(all.length).toBeGreaterThan(1);
      for (const o of all) {
        expect(o.additionalProperties).toBe(false);
        expect(Object.keys(o.properties as object).sort()).toEqual([...(o.required as string[])].sort());
      }
      expect(JSON.stringify(forGemini(schema))).not.toContain('additionalProperties');
      // Claude structured outputs reject numeric and string-length bounds.
      expect(JSON.stringify(schema)).not.toMatch(/"(minimum|maximum|minLength|maxLength|minItems|maxItems)"/);
    }
  });

  it('quotes notes exactly like JSON.stringify and caps them at 1500 UTF-16 units', () => {
    expect(estimateRequestText(spec, 'lunch', 'a"b\\c\n/ł\u0001')).toBe('Meal slot: lunch.\nUser details (untrusted data): "a\\"b\\\\c\\n/ł\\u0001"');
    expect(estimateRequestText(spec, 'snack', 'x'.repeat(2000))).toHaveLength('Meal slot: snack.\nUser details (untrusted data): ""'.length + 1500);
  });

  it('rejects malformed specs', () => {
    const text = readFileSync(SPEC_PATH, 'utf8');
    const duplicate = JSON.parse(text);
    duplicate.genericFoods.push(duplicate.genericFoods[0]);
    expect(() => parseSpec(JSON.stringify(duplicate))).toThrow(/duplicate/);
    const badUnit = JSON.parse(text);
    badUnit.genericFoods[0].unit.min = 999;
    expect(() => parseSpec(JSON.stringify(badUnit))).toThrow(/bad unit/);
    expect(() => parseSpec(JSON.stringify({ ...JSON.parse(text), version: 1 }))).toThrow(/version/);
  });

  it('detects weights and measured references in the notes', () => {
    expect(notesContext(spec, 'Zjadłem 150 G ryżu')).toEqual({ weightGiven: true, measuredReference: false });
    expect(notesContext(spec, '2 gruszki i 3 lody')).toEqual({ weightGiven: false, measuredReference: false });
    expect(notesContext(spec, 'talerz 24 cm')).toEqual({ weightGiven: false, measuredReference: true });
  });
});

describe('finalizer building blocks', () => {
  it('rounds half-up on the IEEE double', () => {
    expect(round1(2.45)).toBe(2.5);
    expect(round1(1.4 * 175 / 100)).toBe(2.4);
    expect(round2(0.145)).toBe(0.14);
    expect(round2(0.65)).toBe(0.65);
    expect(round1(0.04)).toBe(0);
  });

  it('reads numbers and numeric strings only', () => {
    expect(num(' 4,5 ')).toBe(4.5);
    expect(num('-1')).toBe(-1);
    expect(num('1e3')).toBeNull();
    expect(num('0x10')).toBeNull();
    expect(num('')).toBeNull();
    expect(num(Number.NaN)).toBeNull();
    expect(num(true)).toBeNull();
  });

  it('validates GTIN check digits', () => {
    expect(validGtin('5901234123457')).toBe(true);
    expect(validGtin('5901234123458')).toBe(false);
    expect(validGtin('40822938')).toBe(true);
    expect(validGtin('123')).toBe(false);
  });

  it('extracts and repairs model JSON, or says why it cannot', () => {
    expect(extractJsonObject('```json\n{"a":1}\n```')).toBe('\n{"a":1}\n'.trim());
    expect(parseModelJson('{"a":[1,2,],}')).toEqual({ a: [1, 2] });
    expect(() => parseModelJson('nothing')).toThrow(AIOutputError);
    expect(() => finalizeEstimate([], spec, NO_REF)).toThrow(/not_object/);
    expect(() => finalizeLabel('x', spec)).toThrow(/not_object/);
  });

  it('grounds an item with database values and updates the totals', () => {
    const estimate = finalizeEstimate({ foods: [
      { name: 'Jogurt', grams: 150, per100: { kcal: 60, protein: 4, carbs: 6, fat: 2, alcohol: 0 }, barcode: '5901234123457', confidence: 0.6 },
      { name: 'Banan', grams: 120, genericKey: 'banana', nutritionSource: 'estimated', confidence: 0.6 },
    ] }, spec, NO_REF);
    expect(estimate.foods[1]?.nutritionSource).toBe('generic_table');
    groundWithDatabase(estimate, estimate.foods[0]!, { kcal: 97.04, protein: 11, carbs: 2, fat: 5 }, 'open_food_facts');
    expect(estimate.foods[0]).toMatchObject({ kcal: 145.5, protein: 16.5, nutritionSource: 'open_food_facts', per100: { kcal: 97, alcohol: 0 } });
    expect(estimate.foods[0]?.adjustments).toContain('open_food_facts');
    expect(estimate.totals.kcal).toBe(round1(145.5 + 114));
  });
});
