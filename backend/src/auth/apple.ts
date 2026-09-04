import { SignJWT, createRemoteJWKSet, errors as joseErrors, importPKCS8, jwtVerify, type JWTVerifyGetKey } from 'jose';
import { sha256Hex } from '../crypto.js';
import type { AppleConfig } from '../env.js';

export const APPLE_ISSUER = 'https://appleid.apple.com';
export const APPLE_JWKS_URL = 'https://appleid.apple.com/auth/keys';
export const APPLE_TOKEN_URL = 'https://appleid.apple.com/auth/token';
export const APPLE_REVOKE_URL = 'https://appleid.apple.com/auth/revoke';

let cachedJwks: JWTVerifyGetKey | null = null;
/** Remote JWKS (cached by jose, refreshed on unknown `kid`). */
export function appleJwks(): JWTVerifyGetKey {
  cachedJwks ??= createRemoteJWKSet(new URL(APPLE_JWKS_URL));
  return cachedJwks;
}

export type AppleAuthErrorCode = 'invalid_identity_token' | 'nonce_mismatch' | 'code_exchange_failed' | 'revoke_failed' | 'invalid_notification';

export class AppleAuthError extends Error {
  constructor(
    message: string,
    readonly code: AppleAuthErrorCode,
  ) {
    super(message);
    this.name = 'AppleAuthError';
  }
}

export interface AppleIdentity {
  sub: string;
  email: string | null;
  emailVerified: boolean;
  isPrivateEmail: boolean;
}

const truthy = (v: unknown): boolean => v === true || v === 'true';

/** Verifies the identity token the app received from ASAuthorization (RS256, iss/aud/exp). */
export async function verifyAppleIdentityToken(
  token: string,
  bundleId: string,
  jwks: JWTVerifyGetKey,
  opts: { nonce?: string; now?: Date } = {},
): Promise<AppleIdentity> {
  let payload;
  try {
    ({ payload } = await jwtVerify(token, jwks, {
      issuer: APPLE_ISSUER,
      audience: bundleId,
      algorithms: ['RS256'],
      currentDate: opts.now,
    }));
  } catch (err) {
    if (err instanceof joseErrors.JOSEError) throw new AppleAuthError('identity token rejected', 'invalid_identity_token');
    throw err;
  }
  if (typeof payload.sub !== 'string' || payload.sub === '') throw new AppleAuthError('identity token has no sub', 'invalid_identity_token');
  if (opts.nonce !== undefined) {
    // ASAuthorizationAppleIDRequest.nonce carries sha256(rawNonce); the app sends the raw nonce.
    const claim = payload.nonce;
    if (typeof claim !== 'string' || (claim !== opts.nonce && claim !== sha256Hex(opts.nonce))) {
      throw new AppleAuthError('nonce mismatch', 'nonce_mismatch');
    }
  }
  return {
    sub: payload.sub,
    email: typeof payload.email === 'string' ? payload.email : null,
    emailVerified: truthy(payload.email_verified),
    isPrivateEmail: truthy(payload.is_private_email),
  };
}

/** ES256 client secret for /auth/token and /auth/revoke. Apple allows ≤ 6 months; we mint short-lived ones per call. */
export async function makeAppleClientSecret(config: AppleConfig, now: Date = new Date(), ttlSeconds = 300): Promise<string> {
  const key = await importPKCS8(config.privateKeyPem, 'ES256');
  const iat = Math.floor(now.getTime() / 1000);
  return new SignJWT({})
    .setProtectedHeader({ alg: 'ES256', kid: config.keyId })
    .setIssuer(config.teamId)
    .setSubject(config.bundleId)
    .setAudience(APPLE_ISSUER)
    .setIssuedAt(iat)
    .setExpirationTime(iat + ttlSeconds)
    .sign(key);
}

const FORM_HEADERS = { 'content-type': 'application/x-www-form-urlencoded', accept: 'application/json' };

export async function exchangeAppleCode(
  config: AppleConfig,
  authorizationCode: string,
  fetchImpl: typeof fetch = fetch,
  now: Date = new Date(),
): Promise<{ refreshToken: string; idToken: string | null }> {
  const body = new URLSearchParams({
    client_id: config.bundleId,
    client_secret: await makeAppleClientSecret(config, now),
    code: authorizationCode,
    grant_type: 'authorization_code',
  });
  const res = await fetchImpl(APPLE_TOKEN_URL, { method: 'POST', headers: FORM_HEADERS, body });
  if (!res.ok) throw new AppleAuthError(`token exchange failed with HTTP ${res.status}`, 'code_exchange_failed');
  const json = (await res.json()) as { refresh_token?: unknown; id_token?: unknown };
  if (typeof json.refresh_token !== 'string' || json.refresh_token === '') {
    throw new AppleAuthError('token exchange returned no refresh_token', 'code_exchange_failed');
  }
  return { refreshToken: json.refresh_token, idToken: typeof json.id_token === 'string' ? json.id_token : null };
}

export async function revokeAppleRefreshToken(
  config: AppleConfig,
  refreshToken: string,
  fetchImpl: typeof fetch = fetch,
  now: Date = new Date(),
): Promise<void> {
  const body = new URLSearchParams({
    client_id: config.bundleId,
    client_secret: await makeAppleClientSecret(config, now),
    token: refreshToken,
    token_type_hint: 'refresh_token',
  });
  const res = await fetchImpl(APPLE_REVOKE_URL, { method: 'POST', headers: FORM_HEADERS, body });
  if (!res.ok) throw new AppleAuthError(`revoke failed with HTTP ${res.status}`, 'revoke_failed');
}

export interface AppleServerEvent {
  /** e.g. `consent-revoked`, `account-delete`, `email-disabled`, `email-enabled`. */
  type: string;
  sub: string;
  eventTime: number | null;
}

/** Server-to-server notification: `{payload: <JWS>}` whose `events` claim is a JSON string. */
export async function verifyAppleServerNotification(
  payloadJws: string,
  bundleId: string,
  jwks: JWTVerifyGetKey,
  now?: Date,
): Promise<AppleServerEvent> {
  let payload;
  try {
    ({ payload } = await jwtVerify(payloadJws, jwks, { issuer: APPLE_ISSUER, audience: bundleId, algorithms: ['RS256'], currentDate: now }));
  } catch (err) {
    if (err instanceof joseErrors.JOSEError) throw new AppleAuthError('notification rejected', 'invalid_notification');
    throw err;
  }
  let events: unknown = payload.events;
  if (typeof events === 'string') {
    try {
      events = JSON.parse(events);
    } catch {
      throw new AppleAuthError('events claim is not JSON', 'invalid_notification');
    }
  }
  if (!events || typeof events !== 'object') throw new AppleAuthError('events claim missing', 'invalid_notification');
  const ev = events as { type?: unknown; sub?: unknown; event_time?: unknown };
  if (typeof ev.type !== 'string' || typeof ev.sub !== 'string') throw new AppleAuthError('events claim malformed', 'invalid_notification');
  return { type: ev.type, sub: ev.sub, eventTime: typeof ev.event_time === 'number' ? ev.event_time : null };
}
