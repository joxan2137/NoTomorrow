import { describe, expect, it, vi } from 'vitest';
import { GEMINI_BASE_URL, GeminiError, buildPrompt, estimateFood, extractJsonObject, extractText, parseEstimate } from '../src/ai.js';

const CLEAN = JSON.stringify({
  foods: [
    { name: 'Grilled chicken breast', grams: 150, kcal: 247.5, proteinG: 46.2, carbsG: 0, fatG: 5.4, confidence: 0.82, isGuess: false },
    { name: 'Olive oil', grams: 10, kcal: 88.4, proteinG: 0, carbsG: 0, fatG: 10, confidence: 0.4, isGuess: true },
  ],
  overallConfidence: 0.71,
  scaleReferenceUsed: 'dinner plate',
});

const FENCED = `Sure! Here is the estimate you asked for:

\`\`\`json
{
  "foods": [
    {"name": "Ryż", "grams": "150", "kcal": 195.333, "proteinG": "4,1", "carbsG": 43.02, "fatG": 0.4, "confidence": 1.4, "isGuess": "false",},
    {"name": "", "grams": 10, "kcal": 10, "proteinG": 0, "carbsG": 0, "fatG": 0, "confidence": 0.5, "isGuess": false},
    {"name": "Masło", "grams": 8, "kcal": 57, "protein": 0, "carbs": 0, "fat": 6.5, "confidence": 0.3, "isGuess": true},
  ],
  "overallConfidence": "0.66",
  "scaleReferenceUsed": "fork"
}
\`\`\`
Let me know if you want more detail.`;

describe('parseEstimate', () => {
  it('parses a clean response and keeps both *G and short macro keys, rounded to 1 decimal', () => {
    const est = parseEstimate(CLEAN);
    expect(est.foods).toHaveLength(2);
    expect(est.foods[0]).toEqual({
      name: 'Grilled chicken breast',
      grams: 150,
      kcal: 247.5,
      proteinG: 46.2,
      carbsG: 0,
      fatG: 5.4,
      protein: 46.2,
      carbs: 0,
      fat: 5.4,
      confidence: 0.8,
      isGuess: false,
    });
    expect(est.foods[1]?.isGuess).toBe(true);
    expect(est.overallConfidence).toBe(0.7);
    expect(est.scaleReferenceUsed).toBe('dinner plate');
  });

  it('survives fences, prose, trailing commas, string numbers, decimal commas and alias keys', () => {
    const est = parseEstimate(FENCED);
    expect(est.foods.map((f) => f.name)).toEqual(['Ryż', 'Masło']);
    expect(est.foods[0]).toMatchObject({ grams: 150, kcal: 195.3, proteinG: 4.1, carbsG: 43, confidence: 1, isGuess: false });
    expect(est.foods[1]).toMatchObject({ proteinG: 0, fatG: 6.5, fat: 6.5, isGuess: true });
    expect(est.overallConfidence).toBe(0.7);
    expect(est.scaleReferenceUsed).toBe('fork');
  });

  it('falls back to the mean confidence and "none" when the model omits fields', () => {
    const est = parseEstimate('{"foods":[{"name":"apple","grams":180,"kcal":94,"confidence":0.6},{"name":"pear","grams":170,"kcal":97,"confidence":0.8}]}');
    expect(est.overallConfidence).toBe(0.7);
    expect(est.scaleReferenceUsed).toBe('none');
    expect(est.foods[0]).toMatchObject({ proteinG: 0, carbsG: 0, fatG: 0, isGuess: false });
  });

  it('throws AIParseError when there is no JSON at all', () => {
    expect(() => parseEstimate('I cannot see any food in this picture.')).toThrow(/no JSON object/);
    expect(() => parseEstimate('{"foods": [unterminated}')).toThrow(/not valid JSON/);
    expect(() => parseEstimate('{"foods": [unterminated')).toThrow(/no JSON object/);
    expect(extractJsonObject('nothing here')).toBeNull();
  });
});

describe('extractText', () => {
  it('reads output_text, Interactions outputs and generateContent candidates', () => {
    expect(extractText({ output_text: 'A' })).toBe('A');
    expect(extractText({ outputs: [{ type: 'text', text: 'B' }] })).toBe('B');
    expect(extractText({ outputs: [{ type: 'message', content: [{ type: 'text', text: 'C' }] }] })).toBe('C');
    expect(extractText({ candidates: [{ content: { parts: [{ text: 'D' }, { text: 'E' }] } }] })).toBe('DE');
    expect(extractText({ candidates: [] })).toBeNull();
    expect(extractText('nope')).toBeNull();
  });
});

describe('buildPrompt', () => {
  it('asks for names in the user language and embeds the schema', () => {
    expect(buildPrompt('pl', 'dinner')).toContain('in Polish');
    expect(buildPrompt('en', 'lunch')).toContain('shows a lunch');
    expect(buildPrompt('en', 'snack')).toContain('"overallConfidence"');
  });
});

describe('estimateFood', () => {
  const config = { apiKey: 'test-key', model: 'gemini-3.1-flash-lite' };
  const input = { imageBase64: 'AAAA', meal: 'dinner' as const, locale: 'en' };

  it('uses the Interactions API and never leaks the key into the URL', async () => {
    const fetchImpl = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      expect(String(url)).toBe(`${GEMINI_BASE_URL}/v1beta/interactions`);
      expect(String(url)).not.toContain('test-key');
      expect((init?.headers as Record<string, string>)['x-goog-api-key']).toBe('test-key');
      const body = JSON.parse(String(init?.body));
      expect(body.model).toBe(config.model);
      expect(body.input[1]).toEqual({ type: 'image', data: 'AAAA', mime_type: 'image/jpeg' });
      expect(body.response_format.mime_type).toBe('application/json');
      return new Response(JSON.stringify({ output_text: CLEAN }), { status: 200 });
    });
    const est = await estimateFood(config, input, fetchImpl as unknown as typeof fetch);
    expect(est.foods).toHaveLength(2);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it('falls back to generateContent when Interactions returns 404 for the model', async () => {
    const calls: string[] = [];
    const fetchImpl = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      calls.push(String(url));
      if (calls.length === 1) return new Response('{"error":{"code":404}}', { status: 404 });
      const body = JSON.parse(String(init?.body));
      expect(body.generationConfig.responseMimeType).toBe('application/json');
      expect(body.generationConfig.responseSchema.type).toBe('OBJECT');
      expect(body.contents[0].parts[1].inlineData.mimeType).toBe('image/jpeg');
      return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: FENCED }] } }] }), { status: 200 });
    });
    const est = await estimateFood(config, input, fetchImpl as unknown as typeof fetch);
    expect(calls).toEqual([`${GEMINI_BASE_URL}/v1beta/interactions`, `${GEMINI_BASE_URL}/v1beta/models/gemini-3.1-flash-lite:generateContent`]);
    expect(est.foods.map((f) => f.name)).toEqual(['Ryż', 'Masło']);
  });

  it('surfaces upstream failures as GeminiError with the status', async () => {
    const fetchImpl = vi.fn(async () => new Response('busy', { status: 503 }));
    await expect(estimateFood(config, input, fetchImpl as unknown as typeof fetch)).rejects.toMatchObject({ name: 'GeminiError', status: 503 });
    const empty = vi.fn(async () => new Response('{}', { status: 200 }));
    await expect(estimateFood(config, input, empty as unknown as typeof fetch)).rejects.toBeInstanceOf(GeminiError);
  });
});

describe('accuracy safeguards', () => {
  it('computes portions from per-100g nutrition instead of accepting model arithmetic', () => {
    const result = parseEstimate(JSON.stringify({ foods: [{ name: 'Skyr', grams: 150, kcal: 999, proteinG: 999,
      per100: { kcal: 64, protein: 12, carbs: 4, fat: 0 }, confidence: 0.6, isGuess: false }], assumptions: ['150 g eaten'], questions: [] }));
    expect(result.foods[0]).toMatchObject({ kcal: 96, proteinG: 18, carbsG: 6, fatG: 0 });
    expect(result.assumptions).toEqual(['150 g eaten']);
  });
  it('rejects impossible mass, energy density, and malformed responses', () => {
    expect(() => parseEstimate('{"foods":[{"name":"rice","grams":0}]}')).toThrow(/mass/);
    expect(() => parseEstimate('{"foods":[{"name":"rice","grams":10,"kcal":1000}]}')).toThrow(/implausible/);
    expect(() => parseEstimate('{}')).toThrow(/foods array/);
    expect(() => parseEstimate('{"foods":[{"name":"rice","grams":100,"per100":{"kcal":100,"protein":110,"carbs":0,"fat":0}}]}')).toThrow(/per-100g/);
  });
  it('ignores thought text when reading a structured answer', () => {
    expect(extractText({ candidates: [{ content: { parts: [{ thought: true, text: 'not JSON' }, { text: '{"foods":[]}' }] } }] })).toBe('{"foods":[]}');
  });
  it('sends weighed-portion notes and disables server-side interaction storage', async () => {
    const fetcher = vi.fn(async (_url: unknown, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body));
      expect(body.store).toBe(false);
      expect(body.input[0].text).toContain('150 g ugotowanego ryżu');
      expect(init?.signal).toBeDefined();
      return new Response(JSON.stringify({ output_text: CLEAN }));
    });
    await estimateFood({ apiKey: 'test', model: 'gemini-3.8-flash', fallbackModels: [] },
      { imageBase64: 'AA', locale: 'pl', meal: 'lunch', notes: '150 g ugotowanego ryżu' }, fetcher);
  });
});
