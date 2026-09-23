import { describe, expect, it, vi } from 'vitest';
import {
  AIParseError,
  GEMINI_BASE_URL,
  GeminiError,
  estimateFood,
  extractText,
  extractUsage,
  readLabel,
} from '../src/ai.js';
import { estimateRequestText, estimateSystemInstruction, labelSystemInstruction, loadSpec } from '../src/aiSpec.js';

const spec = loadSpec();
const config = { apiKey: 'test-key', model: 'gemini-3.8-flash', fallbackModels: [] as string[] };
const input = { imageBase64: 'AAAA', meal: 'dinner' as const, locale: 'pl', notes: '6 pierogów, ok. 200 g' };

const V2 = JSON.stringify({
  foods: [{
    name: 'Pierogi ruskie', cooking: 'boiled', genericKey: 'pierogi_ruskie', portionCount: 6, portionUnit: 'szt.', gramsPerUnit: 35,
    grams: 210, per100: { kcal: 190, protein: 6, carbs: 29, fat: 6, alcohol: 0 }, nutritionSource: 'estimated', barcode: '', isGuess: false, confidence: 0.8,
  }],
  scaleReferenceUsed: 'talerz', assumptions: ['Gotowane'], questions: ['Z okrasą?'], overallConfidence: 0.75,
});

const LABEL = JSON.stringify({
  legible: true, name: 'Serek wiejski', brand: 'Piątnica', basis: 'per100g', servingSizeG: -1,
  values: { kcal: 97, kj: 406, protein: 11, carbs: 2, fat: 5, fiber: -1, sugar: 2, salt: 0.63 }, packageSizeG: 200, barcode: '', confidence: 0.9,
});

type Call = { url: string; body: Record<string, any>; init: RequestInit };

function recorder(respond: (call: Call, index: number) => Response | Promise<Response>) {
  const calls: Call[] = [];
  const fetchImpl = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
    const call = { url: String(url), body: init?.body ? JSON.parse(String(init.body)) : {}, init: init ?? {} };
    calls.push(call);
    return respond(call, calls.length - 1);
  });
  return { calls, fetchImpl: fetchImpl as unknown as typeof fetch };
}

const interactionsOk = (text: string, usage: Record<string, number> = {}) =>
  new Response(JSON.stringify({ outputs: [{ type: 'thought', text: 'hmm' }, { type: 'text', text }], usage }), { status: 200 });

function hasKeyDeep(node: unknown, key: string): boolean {
  if (Array.isArray(node)) return node.some((n) => hasKeyDeep(n, key));
  if (!node || typeof node !== 'object') return false;
  return Object.entries(node).some(([k, v]) => k === key || hasKeyDeep(v, key));
}

describe('estimateFood: Interactions request', () => {
  it('sends prompt v2 as a static system instruction, image first at high resolution, thinking low, no temperature', async () => {
    const { calls, fetchImpl } = recorder(() => interactionsOk(V2, { total_input_tokens: 3300, total_output_tokens: 420, total_thought_tokens: 600, total_cached_tokens: 2000 }));
    const { estimate, usage } = await estimateFood(config, input, fetchImpl);

    const call = calls[0]!;
    expect(call.url).toBe(`${GEMINI_BASE_URL}/v1beta/interactions`);
    expect(call.url).not.toContain('test-key');
    expect((call.init.headers as Record<string, string>)['x-goog-api-key']).toBe('test-key');
    expect(call.init.signal).toBeInstanceOf(AbortSignal);
    expect(call.body).toMatchObject({
      model: 'gemini-3.8-flash',
      store: false,
      system_instruction: estimateSystemInstruction(spec, 'pl'),
      generation_config: { thinking_level: 'low' },
      input: [
        { type: 'image', data: 'AAAA', mime_type: 'image/jpeg', resolution: 'high' },
        { type: 'text', text: estimateRequestText(spec, 'dinner', '6 pierogów, ok. 200 g') },
      ],
      response_format: { type: 'text', mime_type: 'application/json' },
    });
    expect(call.body.input[1].text).toContain('"6 pierogów, ok. 200 g"');
    expect(call.body.response_format.schema.properties.foods.items.properties.genericKey.enum).toContain('pierogi_ruskie');
    expect(hasKeyDeep(call.body, 'additionalProperties')).toBe(false);
    expect(hasKeyDeep(call.body, 'temperature')).toBe(false);

    // Finalized v2 answer: grounded to the table, weight in notes → no confidence cap.
    expect(estimate).toMatchObject({ version: 2, totals: { kcal: 420 }, overallConfidence: 0.75 });
    expect(estimate.foods[0]).toMatchObject({ kcal: 420, protein: 13.7, proteinG: 13.7, confidence: 0.8, nutritionSource: 'generic_table', portionCount: 6, gramsPerUnit: 35 });
    expect(usage).toMatchObject({ model: 'gemini-3.8-flash', api: 'interactions', inputTokens: 3300, outputTokens: 420, thoughtTokens: 600, cachedTokens: 2000 });
  });

  it('takes the thinking level from config (GEMINI_THINKING_LEVEL)', async () => {
    const { calls, fetchImpl } = recorder(() => interactionsOk(V2));
    await estimateFood({ ...config, thinkingLevel: 'medium' }, input, fetchImpl);
    expect(calls[0]!.body.generation_config).toEqual({ thinking_level: 'medium' });
  });
});

describe('estimateFood: fallbacks', () => {
  it('falls back to generateContent on 404 with the same prompt, JSON schema and thinking level', async () => {
    const { calls, fetchImpl } = recorder((_c, i) => i === 0
      ? new Response('{"error":{"code":404,"message":"not found"}}', { status: 404 })
      : new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: V2 }] } }], usageMetadata: { promptTokenCount: 3000, candidatesTokenCount: 400, thoughtsTokenCount: 500, cachedContentTokenCount: 0 } }), { status: 200 }));
    const { estimate, usage, warnings } = await estimateFood(config, input, fetchImpl);

    expect(calls.map((c) => c.url)).toEqual([`${GEMINI_BASE_URL}/v1beta/interactions`, `${GEMINI_BASE_URL}/v1beta/models/gemini-3.8-flash:generateContent`]);
    const body = calls[1]!.body;
    expect(body.systemInstruction).toEqual({ parts: [{ text: estimateSystemInstruction(spec, 'pl') }] });
    expect(body.contents[0].parts[0]).toEqual({ inlineData: { mimeType: 'image/jpeg', data: 'AAAA' } });
    expect(body.contents[0].parts[1].text).toBe(estimateRequestText(spec, 'dinner', input.notes));
    expect(body.generationConfig).toMatchObject({ responseMimeType: 'application/json', thinkingConfig: { thinkingLevel: 'low' }, mediaResolution: 'MEDIA_RESOLUTION_HIGH' });
    expect(body.generationConfig.responseJsonSchema.properties.foods.items.properties.per100.required).toContain('alcohol');
    expect(hasKeyDeep(body, 'responseSchema')).toBe(false);
    expect(hasKeyDeep(body, 'temperature')).toBe(false);
    expect(estimate.foods).toHaveLength(1);
    expect(usage).toMatchObject({ api: 'generateContent', inputTokens: 3000, outputTokens: 400, thoughtTokens: 500, cachedTokens: 0 });
    expect(warnings).toEqual([]);
  });

  it('retries a 400 from Interactions on generateContent and reports it as a warning', async () => {
    const { calls, fetchImpl } = recorder((_c, i) => i === 0
      ? new Response('{"error":{"code":400,"message":"Unknown name \\"resolution\\""}}', { status: 400 })
      : new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: V2 }] } }] }), { status: 200 }));
    const { warnings } = await estimateFood(config, input, fetchImpl);
    expect(calls).toHaveLength(2);
    expect(warnings[0]).toMatch(/interactions 400 .*resolution/);
  });

  it('tries the next model on 429/5xx and reports an unbilled busy error when all fail', async () => {
    const { calls, fetchImpl } = recorder(() => new Response('busy', { status: 503 }));
    const err = await estimateFood({ ...config, fallbackModels: ['gemini-3.5-flash-lite'] }, input, fetchImpl).catch((e) => e);
    expect(calls.map((c) => c.body.model)).toEqual(['gemini-3.8-flash', 'gemini-3.5-flash-lite']);
    expect(err).toBeInstanceOf(GeminiError);
    expect(err).toMatchObject({ status: 503, kind: 'busy', billed: false });
  });

  it('stops on a non-retryable error from both APIs', async () => {
    const { calls, fetchImpl } = recorder(() => new Response('{"error":{"message":"API key not valid"}}', { status: 400 }));
    const err = await estimateFood({ ...config, fallbackModels: ['other'] }, input, fetchImpl).catch((e) => e);
    expect(calls).toHaveLength(2);
    expect(err).toMatchObject({ kind: 'upstream', status: 400, billed: false });
  });

  it('marks network failures unbilled and timeouts billed, and moves on to the next model', async () => {
    const network = recorder(() => { throw new TypeError('fetch failed'); });
    await expect(estimateFood(config, input, network.fetchImpl)).rejects.toMatchObject({ kind: 'network', billed: false, status: 503 });

    const timeout = recorder((_c, i) => {
      if (i === 0) throw new DOMException('The operation timed out.', 'TimeoutError');
      return new Response('busy', { status: 503 });
    });
    const err = await estimateFood({ ...config, fallbackModels: ['gemini-3.5-flash-lite'] }, input, timeout.fetchImpl).catch((e) => e);
    expect(timeout.calls).toHaveLength(2);
    // The last error decides the kind; the earlier timeout makes the chain possibly billed.
    expect(err).toMatchObject({ kind: 'busy', billed: true });
  });

  it('gives each attempt its own timeout inside the overall budget', async () => {
    const { calls, fetchImpl } = recorder((call, i) => i === 0
      ? new Promise<Response>((_resolve, reject) => {
          call.init.signal!.addEventListener('abort', () => reject(call.init.signal!.reason));
        })
      : interactionsOk(V2));
    const result = await estimateFood({ ...config, fallbackModels: ['gemini-3.5-flash-lite'] }, input, fetchImpl, { attemptMs: 30, totalMs: 5000 });
    expect(calls.map((c) => c.body.model)).toEqual(['gemini-3.8-flash', 'gemini-3.5-flash-lite']);
    expect(result.usage.model).toBe('gemini-3.5-flash-lite');
  });

  it('reports a timeout when the whole budget runs out', async () => {
    const { fetchImpl } = recorder((call) => new Promise<Response>((_resolve, reject) => {
      call.init.signal!.addEventListener('abort', () => reject(call.init.signal!.reason));
    }));
    await expect(estimateFood(config, input, fetchImpl, { attemptMs: 20, totalMs: 40 })).rejects.toMatchObject({ kind: 'timeout', status: 504, billed: true });
  });
});

describe('estimateFood: answers', () => {
  it('treats a 2xx without text as a billed upstream error', async () => {
    const { fetchImpl } = recorder(() => new Response('{}', { status: 200 }));
    await expect(estimateFood(config, input, fetchImpl)).rejects.toMatchObject({ name: 'GeminiError', kind: 'upstream', billed: true });
  });

  it('turns an unusable answer into a billed AIParseError, but skips single bad items', async () => {
    const { fetchImpl } = recorder(() => interactionsOk('I cannot see any food.'));
    const err = await estimateFood(config, input, fetchImpl).catch((e) => e);
    expect(err).toBeInstanceOf(AIParseError);
    expect(err).toMatchObject({ code: 'no_json', billed: true });

    const partial = recorder(() => interactionsOk(JSON.stringify({ foods: [
      { name: 'Okrasa', grams: 0, per100: { kcal: 600, protein: 9, carbs: 0, fat: 62, alcohol: 0 } },
      { name: 'Ziemniaki', grams: 250, genericKey: 'potatoes_boiled', nutritionSource: 'estimated', confidence: 0.6 },
    ], overallConfidence: 0.6 })));
    const { estimate } = await estimateFood(config, { ...input, notes: '' }, partial.fetchImpl);
    expect(estimate.foods.map((f) => f.name)).toEqual(['Ziemniaki']);
    expect(estimate.skipped).toEqual([{ index: 0, name: 'Okrasa', reason: 'invalid_grams' }]);
  });

  it('grounds a readable barcode with Open Food Facts after finalizing', async () => {
    const answer = JSON.stringify({ foods: [{ name: 'Skyr', cooking: 'packaged', genericKey: 'none', portionCount: 1, portionUnit: 'kubek', gramsPerUnit: 150, grams: 150,
      per100: { kcal: 70, protein: 11, carbs: 5, fat: 0.5, alcohol: 0 }, nutritionSource: 'visible_label', barcode: '5901234123457', isGuess: false, confidence: 0.9 }],
      scaleReferenceUsed: 'kubek', assumptions: [], questions: [], overallConfidence: 0.9 });
    const { calls, fetchImpl } = recorder((call) => call.url.startsWith(GEMINI_BASE_URL)
      ? interactionsOk(answer)
      : new Response(JSON.stringify({ status: 1, product: { code: '5901234123457', nutriments: { 'energy-kcal_100g': 64, proteins_100g: 12, carbohydrates_100g: 3.9, fat_100g: 0 } } }), { status: 200 }));
    const { estimate } = await estimateFood(config, { ...input, notes: '' }, fetchImpl);
    expect(calls[1]!.url).toContain('openfoodfacts.org/api/v2/product/5901234123457.json');
    expect(estimate.foods[0]).toMatchObject({ kcal: 96, protein: 18, nutritionSource: 'open_food_facts', per100: { kcal: 64, protein: 12 } });
    expect(estimate.totals.kcal).toBe(96);
  });
});

describe('readLabel', () => {
  it('sends the label prompt and schema and returns per-100 g values', async () => {
    const { calls, fetchImpl } = recorder(() => interactionsOk(LABEL));
    const { reading, usage } = await readLabel(config, { imageBase64: 'BBBB', locale: 'pl' }, fetchImpl);
    const body = calls[0]!.body;
    expect(body.system_instruction).toBe(labelSystemInstruction(spec, 'pl'));
    expect(body.input).toEqual([
      { type: 'image', data: 'BBBB', mime_type: 'image/jpeg', resolution: 'high' },
      { type: 'text', text: spec.label.requestText },
    ]);
    expect(Object.keys(body.response_format.schema.properties.values.properties)).toEqual(['kcal', 'kj', 'protein', 'carbs', 'fat', 'fiber', 'sugar', 'salt']);
    expect(reading).toMatchObject({ legible: true, per100: { kcal: 97, protein: 11, carbs: 2, fat: 5, fiber: null, sugar: 2, salt: 0.63 }, needsReview: false });
    expect(usage.api).toBe('interactions');
  });

  it('turns a non-JSON label answer into AIParseError', async () => {
    const { fetchImpl } = recorder(() => interactionsOk('[1,2]'));
    await expect(readLabel(config, { imageBase64: 'BBBB', locale: 'en' }, fetchImpl)).rejects.toMatchObject({ name: 'AIParseError', code: 'no_json' });
  });
});

describe('extractText / extractUsage', () => {
  it('reads output_text, Interactions outputs and generateContent candidates, skipping thoughts', () => {
    expect(extractText({ output_text: 'A' })).toBe('A');
    expect(extractText({ outputs: [{ type: 'text', text: 'B' }] })).toBe('B');
    expect(extractText({ outputs: [{ type: 'message', content: [{ type: 'text', text: 'C' }] }] })).toBe('C');
    expect(extractText({ candidates: [{ content: { parts: [{ text: 'D' }, { text: 'E' }] } }] })).toBe('DE');
    expect(extractText({ candidates: [{ content: { parts: [{ thought: true, text: 'not JSON' }, { text: '{"foods":[]}' }] } }] })).toBe('{"foods":[]}');
    expect(extractText({ candidates: [] })).toBeNull();
    expect(extractText('nope')).toBeNull();
  });

  it('reads token usage from either API and tolerates its absence', () => {
    expect(extractUsage({ usage: { total_input_tokens: 1, total_output_tokens: 2, total_thought_tokens: 3, total_cached_tokens: 4 } }))
      .toEqual({ inputTokens: 1, outputTokens: 2, thoughtTokens: 3, cachedTokens: 4 });
    expect(extractUsage({})).toEqual({ inputTokens: null, outputTokens: null, thoughtTokens: null, cachedTokens: null });
  });
});
