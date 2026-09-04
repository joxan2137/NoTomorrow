import { decodeJwt, decodeProtectedHeader } from 'jose';
import { describe, expect, it } from 'vitest';
import { ACCESS_TOKEN_TTL_SECONDS, signAccessToken, verifyAccessToken } from '../src/auth/jwt.js';

const SECRET = 'a-very-long-unit-test-secret-of-at-least-32-chars';
const USER = '22222222-2222-4222-8222-222222222222';

describe('access tokens', () => {
  it('issues an HS256 JWT with sub, iss, aud and a 15-minute exp', async () => {
    const now = new Date('2026-09-04T10:00:00Z');
    const token = await signAccessToken(SECRET, USER, { now });
    expect(decodeProtectedHeader(token)).toMatchObject({ alg: 'HS256', typ: 'JWT' });
    const claims = decodeJwt(token);
    expect(claims.sub).toBe(USER);
    expect(claims.iss).toBe('notomorrow');
    expect(claims.exp! - claims.iat!).toBe(ACCESS_TOKEN_TTL_SECONDS);
    expect(claims.exp).toBe(Math.floor(now.getTime() / 1000) + 900);
  });

  it('verifies with the right secret and reports the user id', async () => {
    const token = await signAccessToken(SECRET, USER);
    const claims = await verifyAccessToken(SECRET, token);
    expect(claims?.userId).toBe(USER);
  });

  it('returns null for wrong secret, expiry, tampering and garbage', async () => {
    const now = new Date('2026-09-04T10:00:00Z');
    const token = await signAccessToken(SECRET, USER, { now });
    expect(await verifyAccessToken('another-secret-that-is-also-32-chars-long!!', token)).toBeNull();
    expect(await verifyAccessToken(SECRET, token, new Date(now.getTime() + 16 * 60_000))).toBeNull();
    expect(await verifyAccessToken(SECRET, token.slice(0, -2) + 'xx')).toBeNull();
    expect(await verifyAccessToken(SECRET, 'not.a.jwt')).toBeNull();
    expect(await verifyAccessToken(SECRET, '')).toBeNull();
  });

  it('rejects tokens with a different algorithm or audience', async () => {
    const { SignJWT } = await import('jose');
    const key = new TextEncoder().encode(SECRET);
    const wrongAud = await new SignJWT({}).setProtectedHeader({ alg: 'HS256' }).setSubject(USER).setIssuer('notomorrow').setAudience('other').setExpirationTime('10m').sign(key);
    expect(await verifyAccessToken(SECRET, wrongAud)).toBeNull();
    const hs384 = await new SignJWT({}).setProtectedHeader({ alg: 'HS384' }).setSubject(USER).setIssuer('notomorrow').setAudience('notomorrow-ios').setExpirationTime('10m').sign(key);
    expect(await verifyAccessToken(SECRET, hs384)).toBeNull();
  });
});
