import { describe, expect, it } from 'vitest';
import {
  REFRESH_TOKEN_TTL_MS,
  hashRefreshToken,
  issueRefreshToken,
  revokeRefreshToken,
  rotateRefreshToken,
} from '../src/auth/tokens.js';
import { MemoryRefreshTokenStore } from './helpers.js';

const PEPPER = 'unit-test-pepper-value';
const USER = '11111111-1111-4111-8111-111111111111';

describe('refresh token rotation', () => {
  it('issues a base64url token whose peppered sha256 is what gets stored', async () => {
    const store = new MemoryRefreshTokenStore();
    const { token, row } = await issueRefreshToken(store, PEPPER, USER, { now: new Date(0) });
    expect(token).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(row.tokenHash).toBe(hashRefreshToken(token, PEPPER));
    expect(row.tokenHash).not.toContain(token);
    expect(row.expiresAt.getTime()).toBe(REFRESH_TOKEN_TTL_MS);
    expect(store.rows.size).toBe(1);
  });

  it('rotates on every use and keeps the family', async () => {
    const store = new MemoryRefreshTokenStore();
    const first = await issueRefreshToken(store, PEPPER, USER);
    const r1 = await rotateRefreshToken(store, PEPPER, first.token);
    expect(r1.ok).toBe(true);
    if (!r1.ok) return;
    expect(r1.userId).toBe(USER);
    expect(r1.token).not.toBe(first.token);
    expect(r1.row.family).toBe(first.row.family);
    const old = store.rows.get(first.row.id)!;
    expect(old.revokedAt).not.toBeNull();
    expect(old.replacedBy).toBe(r1.row.id);

    const r2 = await rotateRefreshToken(store, PEPPER, r1.token);
    expect(r2.ok).toBe(true);
  });

  it('treats reuse of a rotated token as theft and revokes the whole family', async () => {
    const store = new MemoryRefreshTokenStore();
    const first = await issueRefreshToken(store, PEPPER, USER);
    const r1 = await rotateRefreshToken(store, PEPPER, first.token);
    expect(r1.ok).toBe(true);
    if (!r1.ok) return;

    const replay = await rotateRefreshToken(store, PEPPER, first.token);
    expect(replay).toEqual({ ok: false, reason: 'reused' });
    // The legitimate newest token is now dead too.
    const after = await rotateRefreshToken(store, PEPPER, r1.token);
    expect(after).toEqual({ ok: false, reason: 'invalid' });
    for (const row of store.rows.values()) expect(row.revokedAt).not.toBeNull();
  });

  it('rejects unknown, empty and expired tokens', async () => {
    const store = new MemoryRefreshTokenStore();
    expect(await rotateRefreshToken(store, PEPPER, 'nope')).toEqual({ ok: false, reason: 'invalid' });
    expect(await rotateRefreshToken(store, PEPPER, '')).toEqual({ ok: false, reason: 'invalid' });
    const issued = await issueRefreshToken(store, PEPPER, USER, { now: new Date(0) });
    expect(await rotateRefreshToken(store, PEPPER, issued.token, new Date(REFRESH_TOKEN_TTL_MS))).toEqual({ ok: false, reason: 'expired' });
    expect(await rotateRefreshToken(store, PEPPER, issued.token, new Date(REFRESH_TOKEN_TTL_MS - 1))).toMatchObject({ ok: true });
  });

  it('is pepper-bound: the same token under another pepper is unknown', async () => {
    const store = new MemoryRefreshTokenStore();
    const issued = await issueRefreshToken(store, PEPPER, USER);
    expect(await rotateRefreshToken(store, 'other-pepper', issued.token)).toEqual({ ok: false, reason: 'invalid' });
  });

  it('logout revokes the token and its family; a revoked (not rotated) token is simply invalid', async () => {
    const store = new MemoryRefreshTokenStore();
    const issued = await issueRefreshToken(store, PEPPER, USER);
    expect(await revokeRefreshToken(store, PEPPER, issued.token)).toBe(true);
    expect(await revokeRefreshToken(store, PEPPER, issued.token)).toBe(true);
    expect(await revokeRefreshToken(store, PEPPER, 'unknown')).toBe(false);
    expect(await rotateRefreshToken(store, PEPPER, issued.token)).toEqual({ ok: false, reason: 'invalid' });
  });

  it('handles a concurrent rotation race by revoking the family', async () => {
    const store = new MemoryRefreshTokenStore();
    const issued = await issueRefreshToken(store, PEPPER, USER);
    const original = store.markRotated.bind(store);
    // First rotation attempt loses the race: someone else already marked the row.
    store.markRotated = async (id, replacedBy, at) => {
      store.markRotated = original;
      await original(id, 'someone-else', at);
      return false;
    };
    expect(await rotateRefreshToken(store, PEPPER, issued.token)).toEqual({ ok: false, reason: 'reused' });
    for (const row of store.rows.values()) expect(row.revokedAt).not.toBeNull();
  });
});
