import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { AIOutputError, finalizeEstimate, finalizeLabel, parseModelJson } from '../src/aiFinalize.js';
import {
  estimateRequestText,
  estimateSchema,
  estimateSystemInstruction,
  forGemini,
  labelRequestText,
  labelSchema,
  labelSystemInstruction,
  notesContext,
  type AISpec,
  type NotesContext,
} from '../src/aiSpec.js';

/**
 * Runs the shared AI fixtures (`data/ai/fixtures/*.json`) against the TypeScript reference.
 * Kinds: `estimate` and `label` (raw model output → finalized JSON or `expectedError`), `context`
 * (notes → NotesContext) and `prompt` (assembled instructions, request texts and schemas).
 */

export interface Fixture {
  kind: 'estimate' | 'label' | 'context' | 'prompt';
  name: string;
  description?: string;
  ctx?: NotesContext;
  raw?: unknown;
  rawText?: string;
  expected?: unknown;
  expectedError?: string;
  cases?: Array<{ notes: string } & NotesContext>;
  input?: { locale: string; meal: string; notes: string };
}

export function readFixtures(dir: string): Array<{ file: string; fixture: Fixture }> {
  return readdirSync(dir)
    .filter((file) => file.endsWith('.json'))
    .sort()
    .map((file) => ({ file, fixture: JSON.parse(readFileSync(join(dir, file), 'utf8')) as Fixture }));
}

/** What the reference produces for a fixture, in the same shape as its `expected` / `expectedError` / `cases`. */
export function produce(fixture: Fixture, spec: AISpec): { expected?: unknown; expectedError?: string; cases?: unknown } {
  switch (fixture.kind) {
    case 'estimate':
    case 'label': {
      try {
        const raw = fixture.rawText !== undefined ? parseModelJson(fixture.rawText) : fixture.raw;
        const expected = fixture.kind === 'estimate'
          ? finalizeEstimate(raw, spec, fixture.ctx ?? { weightGiven: false, measuredReference: false })
          : finalizeLabel(raw, spec);
        return { expected: JSON.parse(JSON.stringify(expected)) };
      } catch (err) {
        if (err instanceof AIOutputError) return { expectedError: err.code };
        throw err;
      }
    }
    case 'context':
      return { cases: (fixture.cases ?? []).map(({ notes }) => ({ notes, ...notesContext(spec, notes) })) };
    case 'prompt': {
      const input = fixture.input!;
      return { expected: promptExpectation(spec, input) };
    }
  }
}

export function promptExpectation(spec: AISpec, input: { locale: string; meal: string; notes: string }): Record<string, unknown> {
  return {
    estimateSystemInstruction: estimateSystemInstruction(spec, input.locale),
    estimateRequestText: estimateRequestText(spec, input.meal, input.notes),
    labelSystemInstruction: labelSystemInstruction(spec, input.locale),
    labelRequestText: labelRequestText(spec),
    notesContext: notesContext(spec, input.notes),
    estimateSchema: estimateSchema(spec),
    estimateSchemaGemini: forGemini(estimateSchema(spec)),
    labelSchema: labelSchema(spec),
    labelSchemaGemini: forGemini(labelSchema(spec)),
  };
}

/** Numbers equal within 1e-9 (the contract's comparison rule); everything else structurally equal. */
export function sameJson(a: unknown, b: unknown, path = '$'): string | null {
  if (typeof a === 'number' && typeof b === 'number') return Math.abs(a - b) <= 1e-9 ? null : `${path}: ${a} ≠ ${b}`;
  if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') return a === b ? null : `${path}: ${JSON.stringify(a)} ≠ ${JSON.stringify(b)}`;
  if (Array.isArray(a) !== Array.isArray(b)) return `${path}: array vs object`;
  if (Array.isArray(a) && Array.isArray(b)) {
    if (a.length !== b.length) return `${path}: length ${a.length} ≠ ${b.length}`;
    for (let i = 0; i < a.length; i++) {
      const diff = sameJson(a[i], b[i], `${path}[${i}]`);
      if (diff) return diff;
    }
    return null;
  }
  const ao = a as Record<string, unknown>;
  const bo = b as Record<string, unknown>;
  const keys = new Set([...Object.keys(ao), ...Object.keys(bo)]);
  for (const key of keys) {
    if (!(key in ao) || !(key in bo)) return `${path}.${key}: missing on one side`;
    const diff = sameJson(ao[key], bo[key], `${path}.${key}`);
    if (diff) return diff;
  }
  return null;
}

/** null when the reference reproduces the fixture, otherwise the first difference. */
export function checkFixture(fixture: Fixture, spec: AISpec): string | null {
  const got = produce(fixture, spec);
  if (fixture.kind === 'context') return sameJson(got.cases, fixture.cases);
  if (fixture.expectedError !== undefined) return got.expectedError === fixture.expectedError ? null : `expected error ${fixture.expectedError}, got ${JSON.stringify(got)}`;
  if (got.expectedError !== undefined) return `unexpected error ${got.expectedError}`;
  return sameJson(got.expected, fixture.expected);
}
