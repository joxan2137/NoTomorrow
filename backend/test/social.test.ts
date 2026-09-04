import { SignJWT, createLocalJWKSet, decodeJwt, decodeProtectedHeader, exportJWK, exportPKCS8, generateKeyPair, jwtVerify, importSPKI, exportSPKI } from 'jose';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import {
  APPLE_ISSUER,
  APPLE_REVOKE_URL,
  APPLE_TOKEN_URL,
  AppleAuthError,
  exchangeAppleCode,
  makeAppleClientSecret,
  revokeAppleRefreshToken,
  verifyAppleIdentityToken,
  verifyAppleServerNotification,
} from '../src/auth/apple.js';
import { GoogleAuthError, verifyGoogleIdToken } from '../src/auth/google.js';
import { decryptSecret, encryptSecret, sha256Hex } from '../src/crypto.js';
import type { AppleConfig } from '../src/env.js';

const BUNDLE = 'app.notomorrow.ios';

let jwks: ReturnType<typeof createLocalJWKSet>;
let rsaPrivate: CryptoKey;
let appleConfig: AppleConfig;

beforeAll(async () => {
  const rsa = await generateKeyPair('RS256', { extractable: true });
  rsaPrivate = rsa.privateKey;
  const jwk = await exportJWK(rsa.publicKey);
  jwks = createLocalJWKSet({ keys: [{ ...jwk, kid: 'k1', alg: 'RS256', use: 'sig' }] });
  const es = await generateKeyPair('ES256', { extractable: true });
  appleConfig = { teamId: 'TEAM123', bundleId: BUNDLE, keyId: 'KEY456', privateKeyPem: await exportPKCS8(es.privateKey) };
  // Round-trip the public key so the client-secret test can verify signatures below.
  publicEs = await importSPKI(await exportSPKI(es.publicKey), 'ES256');
});
let publicEs: CryptoKey;

async function appleToken(claims: Record<string, unknown>, opts: { aud?: string; iss?: string; alg?: 'RS256' } = {}) {
  return new SignJWT(claims)
    .setProtectedHeader({ alg: opts.alg ?? 'RS256', kid: 'k1' })
    .setIssuer(opts.iss ?? APPLE_ISSUER)
    .setAudience(opts.aud ?? BUNDLE)
    .setSubject(String(claims.sub ?? '001234.abc'))
    .setIssuedAt()
    .setExpirationTime('5m')
    .sign(rsaPrivate);
}

describe('Apple identity token', () => {
  it('accepts a valid token and normalizes string booleans', async () => {
    const token = await appleToken({ sub: '001234.abc', email: 'x@privaterelay.appleid.com', email_verified: 'true', is_private_email: 'true' });
    expect(await verifyAppleIdentityToken(token, BUNDLE, jwks)).toEqual({
      sub: '001234.abc',
      email: 'x@privaterelay.appleid.com',
      emailVerified: true,
      isPrivateEmail: true,
    });
  });

  it('rejects the wrong audience/issuer and expired tokens', async () => {
    await expect(verifyAppleIdentityToken(await appleToken({}, { aud: 'other.app' }), BUNDLE, jwks)).rejects.toBeInstanceOf(AppleAuthError);
    await expect(verifyAppleIdentityToken(await appleToken({}, { iss: 'https://evil' }), BUNDLE, jwks)).rejects.toBeInstanceOf(AppleAuthError);
    const later = new Date(Date.now() + 10 * 60_000);
    await expect(verifyAppleIdentityToken(await appleToken({}), BUNDLE, jwks, { now: later })).rejects.toBeInstanceOf(AppleAuthError);
    await expect(verifyAppleIdentityToken('garbage', BUNDLE, jwks)).rejects.toBeInstanceOf(AppleAuthError);
  });

  it('checks the nonce as raw or sha256(raw)', async () => {
    const hashed = await appleToken({ nonce: sha256Hex('raw-nonce') });
    await expect(verifyAppleIdentityToken(hashed, BUNDLE, jwks, { nonce: 'raw-nonce' })).resolves.toBeTruthy();
    await expect(verifyAppleIdentityToken(hashed, BUNDLE, jwks, { nonce: 'other' })).rejects.toMatchObject({ code: 'nonce_mismatch' });
    const raw = await appleToken({ nonce: 'raw-nonce' });
    await expect(verifyAppleIdentityToken(raw, BUNDLE, jwks, { nonce: 'raw-nonce' })).resolves.toBeTruthy();
  });
});

describe('Apple client secret and token endpoints', () => {
  it('mints an ES256 JWT with iss=team, sub=bundle, aud=apple and a short exp', async () => {
    const now = new Date('2026-09-04T10:00:00Z');
    const secret = await makeAppleClientSecret(appleConfig, now);
    expect(decodeProtectedHeader(secret)).toEqual({ alg: 'ES256', kid: 'KEY456' });
    const { payload } = await jwtVerify(secret, publicEs, { issuer: 'TEAM123', audience: APPLE_ISSUER, currentDate: now });
    expect(payload.sub).toBe(BUNDLE);
    expect(payload.exp! - payload.iat!).toBe(300);
    expect(payload.exp! - payload.iat!).toBeLessThanOrEqual(6 * 30 * 24 * 3600);
  });

  it('exchanges the code as a form post and returns the refresh token', async () => {
    const fetchImpl = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      expect(String(url)).toBe(APPLE_TOKEN_URL);
      const form = init?.body as URLSearchParams;
      expect(form.get('grant_type')).toBe('authorization_code');
      expect(form.get('client_id')).toBe(BUNDLE);
      expect(form.get('code')).toBe('c0de');
      expect(decodeJwt(form.get('client_secret')!).iss).toBe('TEAM123');
      return new Response(JSON.stringify({ refresh_token: 'r3fresh', id_token: 'idt' }), { status: 200 });
    });
    expect(await exchangeAppleCode(appleConfig, 'c0de', fetchImpl as unknown as typeof fetch)).toEqual({ refreshToken: 'r3fresh', idToken: 'idt' });
  });

  it('maps failed exchanges and revokes to AppleAuthError', async () => {
    const bad = vi.fn(async () => new Response('{"error":"invalid_grant"}', { status: 400 }));
    await expect(exchangeAppleCode(appleConfig, 'x', bad as unknown as typeof fetch)).rejects.toMatchObject({ code: 'code_exchange_failed' });
    await expect(revokeAppleRefreshToken(appleConfig, 'tok', bad as unknown as typeof fetch)).rejects.toMatchObject({ code: 'revoke_failed' });
    const good = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      expect(String(url)).toBe(APPLE_REVOKE_URL);
      expect((init?.body as URLSearchParams).get('token_type_hint')).toBe('refresh_token');
      return new Response('', { status: 200 });
    });
    await expect(revokeAppleRefreshToken(appleConfig, 'tok', good as unknown as typeof fetch)).resolves.toBeUndefined();
  });

  it('parses server-to-server notifications whose events claim is a JSON string', async () => {
    const jws = await appleToken({ events: JSON.stringify({ type: 'consent-revoked', sub: '001234.abc', event_time: 1700000000 }) });
    expect(await verifyAppleServerNotification(jws, BUNDLE, jwks)).toEqual({ type: 'consent-revoked', sub: '001234.abc', eventTime: 1700000000 });
    const noEvents = await appleToken({});
    await expect(verifyAppleServerNotification(noEvents, BUNDLE, jwks)).rejects.toMatchObject({ code: 'invalid_notification' });
  });
});

describe('Google ID token', () => {
  const ids = ['123.apps.googleusercontent.com'];

  async function googleToken(claims: Record<string, unknown>, aud = ids[0]!, iss = 'accounts.google.com') {
    return new SignJWT(claims).setProtectedHeader({ alg: 'RS256', kid: 'k1' }).setIssuer(iss).setAudience(aud).setSubject('g-sub').setIssuedAt().setExpirationTime('5m').sign(rsaPrivate);
  }

  it('accepts both Google issuers and any allow-listed client id', async () => {
    const t = await googleToken({ email: 'a@gmail.com', email_verified: true, name: 'Ala' });
    expect(await verifyGoogleIdToken(t, ids, jwks)).toEqual({ sub: 'g-sub', email: 'a@gmail.com', emailVerified: true, name: 'Ala' });
    const t2 = await googleToken({}, ids[0], 'https://accounts.google.com');
    expect((await verifyGoogleIdToken(t2, ids, jwks)).name).toBeNull();
  });

  it('rejects other audiences and issuers', async () => {
    await expect(verifyGoogleIdToken(await googleToken({}, 'other'), ids, jwks)).rejects.toBeInstanceOf(GoogleAuthError);
    await expect(verifyGoogleIdToken(await googleToken({}, ids[0], 'https://appleid.apple.com'), ids, jwks)).rejects.toBeInstanceOf(GoogleAuthError);
  });
});

describe('AES-256-GCM secret storage', () => {
  it('round-trips and detects tampering', () => {
    const key = Buffer.alloc(32, 9);
    const blob = encryptSecret(key, 'apple-refresh-token');
    expect(blob.length).toBe(12 + 16 + 'apple-refresh-token'.length);
    expect(decryptSecret(key, blob)).toBe('apple-refresh-token');
    const tampered = Buffer.from(blob);
    tampered[tampered.length - 1] ^= 1;
    expect(() => decryptSecret(key, tampered)).toThrow();
    expect(() => decryptSecret(Buffer.alloc(32, 1), blob)).toThrow();
    expect(encryptSecret(key, 'same').equals(encryptSecret(key, 'same'))).toBe(false);
  });
});
