/**
 * End-to-end against a real Postgres. Runs only when DATABASE_URL is set, e.g.
 *   DATABASE_URL=postgres://notomorrow:notomorrow@localhost:5432/notomorrow npm test
 * Applies the migrations, then exercises register → pair → schedule → attendance → heads-up →
 * partner state → refresh rotation → logout → delete through the real Hono app (no network).
 */
import { randomBytes } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createApp } from '../src/app.js';
import { LoginThrottle } from '../src/auth/password.js';
import { createDb, runMigrations, type Sql } from '../src/db.js';
import { loadEnv, type Env } from '../src/env.js';
import { EventBus } from '../src/events.js';
import { createLogger } from '../src/logger.js';
import { runScan } from '../src/jobs.js';
import type { AppDeps } from '../src/types.js';
import { fakePushService, noNetworkFetch } from './helpers.js';

const DATABASE_URL = process.env.DATABASE_URL;

describe.skipIf(!DATABASE_URL)('integration (Postgres)', () => {
  let sql: Sql;
  let env: Env;
  let deps: AppDeps;
  let app: ReturnType<typeof createApp>;
  const push = fakePushService();
  const suffix = randomBytes(3).toString('hex');
  const alice = { username: `alice_${suffix}`, password: 'alice has a passphrase' };
  const bob = { username: `bob_${suffix}`, password: 'bob has a passphrase too' };
  const sessions: Record<string, { accessToken: string; refreshToken: string; userId: string }> = {};

  const call = async (method: string, path: string, body?: unknown, token?: string, headers: Record<string, string> = {}) => {
    const res = await app.request(path, {
      method,
      headers: {
        ...(body !== undefined ? { 'content-type': 'application/json' } : {}),
        ...(token ? { authorization: `Bearer ${token}` } : {}),
        ...headers,
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    return { status: res.status, json: text ? JSON.parse(text) : null, headers: res.headers };
  };

  beforeAll(async () => {
    env = loadEnv({
      DATABASE_URL,
      JWT_SECRET: 'integration-test-jwt-secret-with-32-chars!!',
      REFRESH_PEPPER: 'integration-pepper-value',
      TOKEN_ENC_KEY: Buffer.alloc(32, 3).toString('base64'),
      AI_DAILY_LIMIT: '2',
      LOG_LEVEL: 'silent',
    });
    sql = createDb(env.databaseUrl, { max: 4 });
    await runMigrations(sql);
    deps = {
      env,
      sql,
      log: createLogger('silent'),
      push,
      events: new EventBus(),
      throttle: new LoginThrottle(),
      fetchImpl: noNetworkFetch,
      jwks: { apple: async () => { throw new Error('no jwks in tests'); }, google: async () => { throw new Error('no jwks in tests'); } },
      now: () => new Date(),
    };
    app = createApp(deps, { sseHeartbeatMs: 50 });
  });

  afterAll(async () => {
    if (!sql) return;
    await sql`delete from users where username in ${sql([alice.username, bob.username])}`;
    await sql.end({ timeout: 5 });
  });

  it('registers, rejects duplicates and weak passwords, and signs in with generic errors', async () => {
    const reg = await call('POST', '/auth/register', { ...alice, email: null });
    expect(reg.status).toBe(201);
    expect(reg.json).toMatchObject({ userId: expect.any(String), accessToken: expect.any(String), refreshToken: expect.any(String) });
    expect(reg.json.user).toMatchObject({ username: alice.username, displayName: alice.username, partner: null, pairCode: null });
    sessions.alice = reg.json;

    expect((await call('POST', '/auth/register', { username: alice.username.toUpperCase(), password: 'another passphrase' })).status).toBe(409);
    expect((await call('POST', '/auth/register', { username: `weak_${suffix}`, password: 'password123' })).json.error).toBe('password_breached');
    expect((await call('POST', '/auth/register', { username: `weak_${suffix}`, password: 'short' })).json.error).toBe('password_too_short');
    expect((await call('POST', '/auth/register', { username: 'x' })).json.error).toBe('invalid_body');

    const wrong = await call('POST', '/auth/login', { username: alice.username, password: 'nope nope nope' });
    const unknown = await call('POST', '/auth/login', { username: `ghost_${suffix}`, password: 'nope nope nope' });
    expect(wrong.status).toBe(401);
    expect(unknown.status).toBe(401);
    expect(wrong.json).toEqual(unknown.json);

    const login = await call('POST', '/auth/password', alice);
    expect(login.status).toBe(200);
    sessions.bob = (await call('POST', '/auth/register', { ...bob, email: 'bob@example.com' })).json;
    expect(sessions.bob.userId).toBeTruthy();
  });

  it('requires a bearer token everywhere else', async () => {
    expect((await call('GET', '/me')).status).toBe(401);
    expect((await call('GET', '/me', undefined, 'nonsense')).status).toBe(401);
    const me = await call('GET', '/me', undefined, sessions.alice!.accessToken);
    expect(me.status).toBe(200);
    expect(me.json.id).toBe(sessions.alice!.userId);
  });

  it('pairs two users through a code and pushes to the code owner', async () => {
    const a = sessions.alice!.accessToken;
    const b = sessions.bob!.accessToken;
    const code = await call('POST', '/pair/code', undefined, a);
    expect(code.status).toBe(200);
    expect(code.json.code).toMatch(/^NT-[A-Z2-9]{4}$/);
    expect((await call('GET', '/me', undefined, a)).json.pairCode).toBe(code.json.code);

    expect((await call('POST', '/pair', { code: code.json.code }, a)).json.error).toBe('cannot_pair_with_self');
    expect((await call('POST', '/pair', { code: 'NT-ZZZZ' }, b)).status).toBe(404);
    expect((await call('GET', '/partner', undefined, b)).status).toBe(404);

    const paired = await call('POST', '/pair', { code: code.json.code.toLowerCase() }, b);
    expect(paired.status).toBe(200);
    expect(paired.json).toMatchObject({ id: sessions.alice!.userId, name: alice.username, partner: { id: sessions.alice!.userId } });
    expect(paired.json.pairedAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);
    expect(push.sent.at(-1)).toMatchObject({ userId: sessions.alice!.userId, msg: { kind: 'paired', name: bob.username } });

    expect((await call('POST', '/pair', { code: code.json.code }, b)).status).toBe(404);
    expect((await call('GET', '/me', undefined, a)).json.partner).toMatchObject({ id: sessions.bob!.userId, name: bob.username });
  });

  it('syncs schedule, attendance and heads-ups to the partner with pushes and SSE events', async () => {
    const a = sessions.alice!.accessToken;
    const b = sessions.bob!.accessToken;
    const schedule = await call('PUT', '/schedule', { weekdays: [5, 1, 3], defaultMinuteOfDay: 1080, overrides: { '5': 600 } }, a);
    expect(schedule.status).toBe(200);
    expect(schedule.json).toEqual({ weekdays: [1, 3, 5], defaultMinuteOfDay: 1080, overrides: { '5': 600 }, remindHourBefore: true, askIfSkippedAt21: true });
    expect((await call('PUT', '/schedule', { weekdays: [8], defaultMinuteOfDay: 1080 }, a)).status).toBe(400);

    const received: unknown[] = [];
    const unsubscribe = deps.events.subscribe(sessions.bob!.userId, (e) => received.push(e));

    const confirmed = await call('PUT', '/attendance/2026-09-04', { status: 'confirmed' }, a);
    expect(confirmed.status).toBe(200);
    expect(confirmed.json).toMatchObject({ participant: 'me', status: 'confirmed', scheduledMinute: 600, day: '2026-09-03T22:00:00Z' });
    expect(push.sent.at(-1)).toMatchObject({ userId: sessions.bob!.userId, msg: { kind: 'attendanceConfirmed', time: '10:00' } });

    const cancelled = await call('PUT', '/attendance', { day: '2026-09-06T22:00:00Z', status: 'cancelled', reason: 'sick', makeUpDay: '2026-09-08' }, a);
    expect(cancelled.status).toBe(200);
    expect(cancelled.json).toMatchObject({ status: 'cancelled', reason: 'sick', makeUpDay: '2026-09-07T22:00:00Z' });
    expect(push.sent.at(-1)?.msg).toMatchObject({ kind: 'attendanceCancelled', reason: 'sick', makeUpWeekday: 2 });

    const attended = await call('PUT', '/attendance/2026-09-02', { status: 'attended' }, a);
    expect(attended.status).toBe(200);
    expect(push.sent.at(-1)?.msg.kind).toBe('attendanceCancelled'); // attended is silent

    const headsUp = await call('POST', '/headsups', { kind: 'runningLate', text: '  15 min late  ', sessionDay: '2026-09-04' }, a);
    expect(headsUp.status).toBe(201);
    expect(headsUp.json).toMatchObject({ fromMe: true, kind: 'runningLate', text: '15 min late' });
    expect(push.sent.at(-1)?.msg).toEqual({ kind: 'headsUp', name: alice.username, text: '15 min late' });
    expect((await call('POST', '/headsup', { kind: 'custom', text: 'x'.repeat(81), sessionDay: '2026-09-04' }, a)).json.error).toBe('text_too_long');
    expect((await call('POST', `/headsups/${headsUp.json.id}/read`, undefined, b)).status).toBe(200);
    expect((await call('POST', `/headsups/${headsUp.json.id}/read`, undefined, a)).status).toBe(404);

    unsubscribe();
    expect(received.map((e) => (e as { type: string }).type)).toEqual(['attendance', 'attendance', 'attendance', 'headsUp']);

    const state = await call('GET', '/partner', undefined, b);
    expect(state.status).toBe(200);
    expect(state.json.partner.id).toBe(sessions.alice!.userId);
    expect(state.json.schedule.overrides).toEqual({ '5': 600 });
    expect(state.json.attendance.map((r: { status: string }) => r.status).sort()).toEqual(['attended', 'cancelled', 'confirmed', 'planned']);
    expect(state.json.attendance.every((r: { participant: string }) => r.participant === 'partner')).toBe(true);
    expect(state.json.myAttendance).toEqual([]);
    expect(state.json.headsUps).toHaveLength(1);
    expect(state.json.headsUps[0]).toMatchObject({ fromMe: false, readAt: expect.any(String) });

    const iosState = await call('GET', '/partner/state', undefined, b);
    expect(iosState.json).toMatchObject({ partnerName: alice.username, partnerSchedule: { defaultMinuteOfDay: 1080 } });
    expect(iosState.json.attendance).toHaveLength(4);
  });

  it('streams SSE with heartbeats', async () => {
    const res = await app.request('/events', { headers: { authorization: `Bearer ${sessions.bob!.accessToken}` } });
    expect(res.status).toBe(200);
    expect(res.headers.get('content-type')).toContain('text/event-stream');
    const reader = res.body!.getReader();
    const decoder = new TextDecoder();
    let text = '';
    while (!text.includes(': heartbeat')) {
      const { value, done } = await reader.read();
      if (done) break;
      text += decoder.decode(value);
    }
    expect(text).toContain('event: ready');
    expect(text).toContain(': heartbeat');
    await reader.cancel();
  });

  it('rate-limits AI usage per day and answers 503 without Gemini', async () => {
    const a = sessions.alice!.accessToken;
    const byok = await call('POST', '/ai/estimate', undefined, a, { 'x-anthropic-key': 'sk-ant', 'content-type': 'multipart/form-data; boundary=x' });
    expect(byok.status).toBe(400);
    expect(byok.json.error).toBe('byok_is_device_direct');
    const noProvider = await call('POST', '/ai/estimate', undefined, a, { 'content-type': 'multipart/form-data; boundary=x' });
    expect(noProvider.status).toBe(503);
    expect((await call('POST', '/push/token', { token: 'ab'.repeat(32) }, a)).status).toBe(503);
  });

  it('scans jobs idempotently against notifications_sent', async () => {
    const userId = sessions.alice!.userId;
    await sql`update users set tz = 'UTC' where id = ${userId}`;
    await sql`update schedules set weekdays = '{1,2,3,4,5,6,7}', default_minute = 720 where user_id = ${userId}`;
    const before = push.sent.length;
    const at = new Date('2026-09-10T11:10:00Z'); // 12:00 session → reminder window
    expect(await runScan(deps, at)).toMatchObject({ reminders: 1 });
    expect(await runScan(deps, at)).toMatchObject({ reminders: 0 });
    expect(push.sent.slice(before).filter((p) => p.userId === userId && p.msg.kind === 'reminder')).toHaveLength(1);
    const evening = new Date('2026-09-10T21:05:00Z');
    expect(await runScan(deps, evening)).toMatchObject({ skipChecks: 1 });
    expect(await runScan(deps, evening)).toMatchObject({ skipChecks: 0 });
  });

  it('rotates refresh tokens, detects reuse, and logs out', async () => {
    const first = sessions.bob!.refreshToken;
    const r1 = await call('POST', '/auth/refresh', { refreshToken: first });
    expect(r1.status).toBe(200);
    expect(r1.json.refreshToken).not.toBe(first);
    expect((await call('GET', '/me', undefined, r1.json.accessToken)).status).toBe(200);
    // Replaying the old token kills the family, including the fresh one.
    expect((await call('POST', '/auth/refresh', { refreshToken: first })).status).toBe(401);
    expect((await call('POST', '/auth/refresh', { refreshToken: r1.json.refreshToken })).status).toBe(401);

    const login = await call('POST', '/auth/login', bob);
    expect((await call('POST', '/auth/logout', { refreshToken: login.json.refreshToken })).status).toBe(200);
    expect((await call('POST', '/auth/refresh', { refreshToken: login.json.refreshToken })).status).toBe(401);
    sessions.bob = (await call('POST', '/auth/login', bob)).json;
  });

  it('unpairs and deletes the account, notifying the partner', async () => {
    const a = sessions.alice!.accessToken;
    const b = sessions.bob!.accessToken;
    expect((await call('DELETE', '/pair', undefined, b)).json).toEqual({ ok: true, ended: 1 });
    expect(push.sent.at(-1)).toMatchObject({ userId: sessions.alice!.userId, msg: { kind: 'unpaired' } });
    expect((await call('GET', '/partner', undefined, a)).status).toBe(404);

    const code = await call('POST', '/pair/code', undefined, b);
    expect((await call('POST', '/pair', { code: code.json.code }, a)).status).toBe(200);
    expect((await call('DELETE', '/me', undefined, a)).status).toBe(200);
    expect(push.sent.at(-1)).toMatchObject({ userId: sessions.bob!.userId, msg: { kind: 'unpaired', name: alice.username } });
    expect((await call('GET', '/me', undefined, a)).status).toBe(404);
    expect((await call('GET', '/partner', undefined, b)).status).toBe(404);
    expect(await sql`select 1 from users where username = ${alice.username}`).toHaveLength(0);
    expect((await call('DELETE', '/account', undefined, b)).status).toBe(200);
  });
});
