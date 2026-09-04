import { SignJWT, jwtVerify, errors as joseErrors } from 'jose';

export const ACCESS_TOKEN_TTL_SECONDS = 15 * 60;
const ISSUER = 'notomorrow';
const AUDIENCE = 'notomorrow-ios';

const encoder = new TextEncoder();

export interface AccessClaims {
  userId: string;
  expiresAt: Date;
}

export async function signAccessToken(
  secret: string,
  userId: string,
  opts: { ttlSeconds?: number; now?: Date } = {},
): Promise<string> {
  const nowSec = Math.floor((opts.now ?? new Date()).getTime() / 1000);
  const ttl = opts.ttlSeconds ?? ACCESS_TOKEN_TTL_SECONDS;
  return new SignJWT({})
    .setProtectedHeader({ alg: 'HS256', typ: 'JWT' })
    .setSubject(userId)
    .setIssuer(ISSUER)
    .setAudience(AUDIENCE)
    .setIssuedAt(nowSec)
    .setExpirationTime(nowSec + ttl)
    .sign(encoder.encode(secret));
}

/** Returns the claims, or null for any invalid/expired/foreign token (never throws on bad input). */
export async function verifyAccessToken(secret: string, token: string, now?: Date): Promise<AccessClaims | null> {
  try {
    const { payload } = await jwtVerify(token, encoder.encode(secret), {
      issuer: ISSUER,
      audience: AUDIENCE,
      algorithms: ['HS256'],
      currentDate: now,
    });
    if (typeof payload.sub !== 'string' || !payload.sub || typeof payload.exp !== 'number') return null;
    return { userId: payload.sub, expiresAt: new Date(payload.exp * 1000) };
  } catch (err) {
    if (err instanceof joseErrors.JOSEError) return null;
    // Only jose errors are expected; anything else is a programming error worth surfacing.
    throw err;
  }
}
