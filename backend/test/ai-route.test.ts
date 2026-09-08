import { Hono } from 'hono';
import { describe, expect, it, vi } from 'vitest';
import { aiRoutes } from '../src/routes/ai.js';
import { HttpError } from '../src/http.js';
import type { AppDeps, AppEnv } from '../src/types.js';

function fixture(envOverrides: Record<string, unknown> = {}) {
  const sql = vi.fn(async () => [{ count: 1 }] as unknown[]);
  const fetchImpl = vi.fn(async () => new Response(JSON.stringify({ output_text: JSON.stringify({
    foods: [{ name: 'Ryż', grams: 150, per100: { kcal: 130, protein: 2.7, carbs: 28, fat: 0.3 }, confidence: 0.6, isGuess: false }],
    overallConfidence: 0.6, scaleReferenceUsed: 'waga', assumptions: ['Ryż ugotowany'], questions: ['Dodano olej?'],
  }) })));
  const deps = { sql, fetchImpl, log: { warn: vi.fn() }, now: () => new Date('2026-09-08T12:00:00Z'),
    env: { gemini: { apiKey: 'private-test-key', model: 'gemini-3.8-flash', fallbackModels: [] }, aiDailyLimit: 30, aiAllowedUsers: [], ...envOverrides } } as unknown as AppDeps;
  const app = new Hono<AppEnv>();
  app.use('*', async (c, next) => { c.set('userId', 'test-user'); await next(); });
  app.onError((error, c) => error instanceof HttpError ? c.json({ error: error.message }, error.status as 400) : c.json({ error: 'unexpected' }, 500));
  app.route('/', aiRoutes(deps));
  const form = (notes: string) => {
    const data = new FormData();
    data.append('image', new File([new Uint8Array([255, 216, 255, 0])], 'plate.jpg', { type: 'image/jpeg' }));
    data.append('meal', 'lunch'); data.append('locale', 'pl'); data.append('notes', notes);
    return data;
  };
  return { app, sql, fetchImpl, form };
}

describe('photo endpoint', () => {
  it('forwards meal details and returns accuracy metadata with deterministic totals', async () => {
    const f = fixture();
    const response = await f.app.request('/ai/estimate', { method: 'POST', body: f.form('150 g ugotowanego ryżu') });
    expect(response.status).toBe(200);
    expect(await response.json()).toMatchObject({ foods: [{ kcal: 195, protein: 4.1 }], questions: ['Dodano olej?'], assumptions: ['Ryż ugotowany'] });
    expect(f.sql).toHaveBeenCalledTimes(1);
    const call = (f.fetchImpl.mock.calls as unknown as [string, RequestInit][])[0];
    expect(String(call?.[1].body)).toContain('150 g ugotowanego ryżu');
  });
  it('rejects oversized notes before reserving quota or contacting Gemini', async () => {
    const f = fixture();
    const response = await f.app.request('/ai/estimate', { method: 'POST', body: f.form('x'.repeat(1501)) });
    expect(response.status).toBe(400);
    expect(f.sql).not.toHaveBeenCalled(); expect(f.fetchImpl).not.toHaveBeenCalled();
  });
  it('releases quota when the provider response is invalid', async () => {
    const f = fixture();
    f.fetchImpl.mockImplementationOnce(async () => new Response(JSON.stringify({ output_text: '{}' })));
    const response = await f.app.request('/ai/estimate', { method: 'POST', body: f.form('') });
    expect(response.status).toBe(502);
    expect(f.sql).toHaveBeenCalledTimes(2);
  });
  it('refuses users outside AI_ALLOWED_USERS before reading the body or reserving quota', async () => {
    const f = fixture({ aiAllowedUsers: ['test123'] });
    f.sql.mockImplementationOnce(async () => [{ id: 'test-user', username: 'Someone' }]);
    const response = await f.app.request('/ai/estimate', { method: 'POST', body: f.form('') });
    expect(response.status).toBe(403);
    expect(String((await response.json() as { error: string }).error)).toMatch(/whitelist/);
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
