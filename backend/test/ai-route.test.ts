import { Hono } from 'hono';
import { describe, expect, it, vi } from 'vitest';
import { createApp } from '../src/app.js';
import { aiRoutes } from '../src/routes/ai.js';
import { HttpError } from '../src/http.js';
import type { AppDeps, AppEnv } from '../src/types.js';

const V2_ANSWER = {
  foods: [{
    name: 'Ryż', cooking: 'boiled', genericKey: 'none', portionCount: 1, portionUnit: 'porcja', gramsPerUnit: 150, grams: 150,
    per100: { kcal: 130, protein: 2.7, carbs: 28, fat: 0.3, alcohol: 0 }, nutritionSource: 'estimated', barcode: '', isGuess: false, confidence: 0.6,
  }],
  overallConfidence: 0.6, scaleReferenceUsed: 'waga', assumptions: ['Ryż ugotowany'], questions: ['Dodano olej?'],
};

const LABEL_ANSWER = {
  legible: true, name: 'Serek wiejski', brand: 'Piątnica', basis: 'per100g', servingSizeG: -1,
  values: { kcal: 97, kj: 406, protein: 11, carbs: 2, fat: 5, fiber: -1, sugar: 2, salt: 0.63 }, packageSizeG: 200, barcode: '', confidence: 0.93,
};

const answer = (value: unknown) => new Response(JSON.stringify({ output_text: typeof value === 'string' ? value : JSON.stringify(value) }));

function fixture(envOverrides: Record<string, unknown> = {}) {
  const sql = vi.fn(async (..._args: unknown[]) => [{ count: 1 }] as unknown[]);
  const fetchImpl = vi.fn(async (url: string | URL | Request, _init?: RequestInit) => {
    const body = _init?.body ? JSON.parse(String(_init.body)) : {};
    const isLabel = String(body.system_instruction ?? '').includes('nutrition table');
    return answer(isLabel ? LABEL_ANSWER : V2_ANSWER);
  });
  const log = { warn: vi.fn(), info: vi.fn() };
  const deps = { sql, fetchImpl, log, now: () => new Date('2026-09-08T12:00:00Z'),
    env: { gemini: { apiKey: 'private-test-key', model: 'gemini-3.8-flash', fallbackModels: [] }, aiDailyLimit: 30, aiAllowedUsers: [], ...envOverrides } } as unknown as AppDeps;
  const app = new Hono<AppEnv>();
  app.use('*', async (c, next) => { c.set('userId', 'test-user'); await next(); });
  app.onError((error, c) => error instanceof HttpError ? c.json(error.body(), error.status as 400, error.headers) : c.json({ error: 'unexpected' }, 500));
  app.route('/', aiRoutes(deps));
  const jpeg = () => new File([new Uint8Array([255, 216, 255, 0])], 'plate.jpg', { type: 'image/jpeg' });
  const form = (notes: string) => {
    const data = new FormData();
    data.append('image', jpeg());
    data.append('meal', 'lunch'); data.append('locale', 'pl'); data.append('notes', notes);
    return data;
  };
  const labelForm = (image: File | null = jpeg()) => {
    const data = new FormData();
    if (image) data.append('image', image);
    data.append('locale', 'pl');
    return data;
  };
  /** SQL statements issued, as text, to tell the quota insert from the refund update. */
  const statements = () => sql.mock.calls.map((call) => (call[0] as unknown as string[]).join('?').replace(/\s+/g, ' ').trim());
  return { app, sql, fetchImpl, form, labelForm, log, statements };
}

describe('POST /ai/estimate', () => {
  it('returns the v2 estimate and keeps every field old app builds decode', async () => {
    const f = fixture();
    const response = await f.app.request('/ai/estimate', { method: 'POST', body: f.form('150 g ugotowanego ryżu') });
    expect(response.status).toBe(200);
    const json = await response.json() as Record<string, any>;
    // Legacy contract (iOS AIFood / AIEstimate decoders, Android AIFoodSerializer).
    expect(json).toMatchObject({ overallConfidence: 0.6, scaleReferenceUsed: 'waga', questions: ['Dodano olej?'], assumptions: ['Ryż ugotowany'] });
    expect(json.foods[0]).toMatchObject({ name: 'Ryż', grams: 150, kcal: 195, protein: 4.1, carbs: 42, fat: 0.5, proteinG: 4.1, carbsG: 42, fatG: 0.5, confidence: 0.6, isGuess: false });
    // v2 additions.
    expect(json).toMatchObject({ version: 2, totals: { kcal: 195, protein: 4.1, carbs: 42, fat: 0.5 }, skipped: [] });
    expect(json.foods[0]).toMatchObject({ per100: { kcal: 130, protein: 2.7, carbs: 28, fat: 0.3, alcohol: 0 }, portionCount: 1, portionUnit: 'porcja', gramsPerUnit: 150, nutritionSource: 'estimated', cooking: 'boiled', genericKey: 'none', barcode: '' });
    expect(f.statements()).toHaveLength(1);
    const call = (f.fetchImpl.mock.calls as unknown as [string, RequestInit][])[0];
    expect(String(call?.[1].body)).toContain('150 g ugotowanego ryżu');
    expect(f.log.info).toHaveBeenCalledWith(expect.objectContaining({ ai: 'estimate', model: 'gemini-3.8-flash', api: 'interactions', foods: 1 }), 'ai usage');
  });

  it('rejects oversized notes before reserving quota or contacting Gemini', async () => {
    const f = fixture();
    const response = await f.app.request('/ai/estimate', { method: 'POST', body: f.form('x'.repeat(1501)) });
    expect(response.status).toBe(400);
    expect(f.sql).not.toHaveBeenCalled(); expect(f.fetchImpl).not.toHaveBeenCalled();
  });

  it('keeps the quota unit when Gemini answered (billed) but the answer is unusable', async () => {
    const f = fixture();
    f.fetchImpl.mockImplementationOnce(async () => answer('{}'));
    const response = await f.app.request('/ai/estimate', { method: 'POST', body: f.form('') });
    expect(response.status).toBe(502);
    expect(await response.json()).toMatchObject({ error: 'ai_unparseable' });
    expect(f.statements()).toHaveLength(1);
    expect(f.statements()[0]).toMatch(/^insert into ai_usage/);
  });

  it('refunds the quota unit when Gemini was busy (429/5xx, not billed)', async () => {
    const f = fixture();
    f.fetchImpl.mockImplementation(async () => new Response('overloaded', { status: 503 }));
    const response = await f.app.request('/ai/estimate', { method: 'POST', body: f.form('') });
    expect(response.status).toBe(503);
    expect(await response.json()).toMatchObject({ error: 'ai_busy' });
    expect(f.statements()).toHaveLength(2);
    expect(f.statements()[1]).toMatch(/^update ai_usage set count = greatest\(count - 1, 0\)/);
  });

  it('refunds when Gemini was unreachable, keeps the unit on a timeout (Google may have billed)', async () => {
    const offline = fixture();
    offline.fetchImpl.mockImplementation(async () => { throw new TypeError('fetch failed'); });
    const r1 = await offline.app.request('/ai/estimate', { method: 'POST', body: offline.form('') });
    expect(r1.status).toBe(503);
    expect(offline.statements()).toHaveLength(2);

    const slow = fixture();
    slow.fetchImpl.mockImplementation(async () => { throw new DOMException('The operation timed out.', 'TimeoutError'); });
    const r2 = await slow.app.request('/ai/estimate', { method: 'POST', body: slow.form('') });
    expect(r2.status).toBe(504);
    expect(await r2.json()).toMatchObject({ error: 'ai_timeout' });
    expect(slow.statements()).toHaveLength(1);
  });

  it('answers 429 ai_daily_limit when the quota insert is refused, without calling Gemini', async () => {
    const f = fixture();
    f.sql.mockImplementationOnce(async () => []);
    const response = await f.app.request('/ai/estimate', { method: 'POST', body: f.form('') });
    expect(response.status).toBe(429);
    expect(response.headers.get('retry-after')).toBe('3600');
    expect(await response.json()).toMatchObject({ error: 'ai_daily_limit' });
    expect(f.fetchImpl).not.toHaveBeenCalled();
  });

  it('refuses users outside AI_ALLOWED_USERS before reading the body or reserving quota', async () => {
    const f = fixture({ aiAllowedUsers: ['test123'] });
    f.sql.mockImplementationOnce(async () => [{ id: 'test-user', username: 'Someone' }]);
    const response = await f.app.request('/ai/estimate', { method: 'POST', body: f.form('') });
    expect(response.status).toBe(403);
    expect(String((await response.json() as { message: string }).message)).toMatch(/whitelist/);
    expect(f.sql).toHaveBeenCalledTimes(1);
    expect(f.fetchImpl).not.toHaveBeenCalled();
  });

  it('lets a whitelisted username through, case-insensitively', async () => {
    const f = fixture({ aiAllowedUsers: ['test123'] });
    f.sql.mockImplementationOnce(async () => [{ id: 'test-user', username: 'Test123' }]);
    const response = await f.app.request('/ai/estimate', { method: 'POST', body: f.form('') });
    expect(response.status).toBe(200);
    expect(f.fetchImpl).toHaveBeenCalledTimes(1);
  });
});

describe('POST /ai/label', () => {
  it('returns per-100 g values read from the label and spends one quota unit', async () => {
    const f = fixture();
    const response = await f.app.request('/ai/label', { method: 'POST', body: f.labelForm() });
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({
      version: 1, legible: true, unreadableReason: null, basis: 'per100g', energyFrom: 'kcal', name: 'Serek wiejski', brand: 'Piątnica',
      per100: { kcal: 97, protein: 11, carbs: 2, fat: 5, fiber: null, sugar: 2, salt: 0.63 },
      servingSizeG: null, packageSizeG: 200, barcode: '', confidence: 0.93, needsReview: false,
    });
    expect(f.statements()).toHaveLength(1);
    const body = JSON.parse(String((f.fetchImpl.mock.calls[0] as unknown as [string, RequestInit])[1].body));
    expect(body.system_instruction).toContain('in Polish');
    expect(f.log.info).toHaveBeenCalledWith(expect.objectContaining({ ai: 'label', legible: true }), 'ai usage');
  });

  it('answers 200 legible:false for an unreadable table (not an error)', async () => {
    const f = fixture();
    f.fetchImpl.mockImplementationOnce(async () => answer({ ...LABEL_ANSWER, legible: false }));
    const response = await f.app.request('/ai/label', { method: 'POST', body: f.labelForm() });
    expect(response.status).toBe(200);
    expect(await response.json()).toMatchObject({ legible: false, unreadableReason: 'illegible', per100: null, name: 'Serek wiejski' });
  });

  it('applies the same guards as /ai/estimate', async () => {
    const notAllowed = fixture({ aiAllowedUsers: ['test123'] });
    notAllowed.sql.mockImplementationOnce(async () => [{ id: 'test-user', username: 'someone' }]);
    expect((await notAllowed.app.request('/ai/label', { method: 'POST', body: notAllowed.labelForm() })).status).toBe(403);
    expect(notAllowed.fetchImpl).not.toHaveBeenCalled();

    const limited = fixture();
    limited.sql.mockImplementationOnce(async () => []);
    const r429 = await limited.app.request('/ai/label', { method: 'POST', body: limited.labelForm() });
    expect(r429.status).toBe(429);
    expect(await r429.json()).toMatchObject({ error: 'ai_daily_limit' });

    const png = fixture();
    const r400 = await png.app.request('/ai/label', { method: 'POST', body: png.labelForm(new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], 'l.png')) });
    expect(r400.status).toBe(400);
    expect(await r400.json()).toMatchObject({ error: 'image_not_jpeg' });
    expect(png.sql).not.toHaveBeenCalled();

    const missing = fixture();
    expect(await (await missing.app.request('/ai/label', { method: 'POST', body: missing.labelForm(null) })).json()).toMatchObject({ error: 'image_required' });

    const byok = fixture();
    const rByok = await byok.app.request('/ai/label', { method: 'POST', headers: { 'x-anthropic-key': 'sk-ant-x' }, body: byok.labelForm() });
    expect(rByok.status).toBe(400);
    expect(await rByok.json()).toMatchObject({ error: 'byok_is_device_direct' });

    const off = fixture({ gemini: null });
    const r503 = await off.app.request('/ai/label', { method: 'POST', body: off.labelForm() });
    expect(r503.status).toBe(503);
    expect(await r503.json()).toMatchObject({ error: 'ai_unavailable' });

    const json = fixture();
    const rJson = await json.app.request('/ai/label', { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' });
    expect(await rJson.json()).toMatchObject({ error: 'multipart_required' });
  });

  it('keeps the unit for a billed unusable answer and refunds an unbilled failure', async () => {
    const garbage = fixture();
    garbage.fetchImpl.mockImplementationOnce(async () => answer('no table here'));
    const r502 = await garbage.app.request('/ai/label', { method: 'POST', body: garbage.labelForm() });
    expect(r502.status).toBe(502);
    expect(await r502.json()).toMatchObject({ error: 'ai_unparseable' });
    expect(garbage.statements()).toHaveLength(1);

    const busy = fixture();
    busy.fetchImpl.mockImplementation(async () => new Response('slow down', { status: 429 }));
    const r503 = await busy.app.request('/ai/label', { method: 'POST', body: busy.labelForm() });
    expect(r503.status).toBe(503);
    expect(busy.statements()).toHaveLength(2);
  });
});

describe('app wiring', () => {
  it('gives /ai/label the multipart body limit (a 100 KB upload reaches auth instead of a 413)', async () => {
    const log = { info: vi.fn(), debug: vi.fn(), warn: vi.fn(), error: vi.fn() };
    const deps = { env: { jwtSecret: 'x'.repeat(40), gemini: null, aiAllowedUsers: [], aiDailyLimit: 1 }, log, sql: vi.fn(), now: () => new Date() } as unknown as AppDeps;
    const app = createApp(deps);
    const big = 'a'.repeat(100 * 1024);
    for (const path of ['/ai/label', '/ai/estimate']) {
      const response = await app.request(path, { method: 'POST', headers: { 'content-type': 'multipart/form-data; boundary=x', 'content-length': String(big.length) }, body: big });
      expect(response.status, path).toBe(401);
    }
    const json = await app.request('/pair', { method: 'POST', headers: { 'content-type': 'application/json', 'content-length': String(big.length) }, body: big });
    expect(json.status).toBe(413);
  });
});
