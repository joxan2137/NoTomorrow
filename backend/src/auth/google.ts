import { createRemoteJWKSet, errors as joseErrors, jwtVerify, type JWTVerifyGetKey } from 'jose';

export const GOOGLE_JWKS_URL = 'https://www.googleapis.com/oauth2/v3/certs';
export const GOOGLE_ISSUERS = ['https://accounts.google.com', 'accounts.google.com'];

let cachedJwks: JWTVerifyGetKey | null = null;
export function googleJwks(): JWTVerifyGetKey {
  cachedJwks ??= createRemoteJWKSet(new URL(GOOGLE_JWKS_URL));
  return cachedJwks;
}

export class GoogleAuthError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'GoogleAuthError';
  }
}

export interface GoogleIdentity {
  sub: string;
  email: string | null;
  emailVerified: boolean;
  name: string | null;
}

/** Verifies a Google Sign-In ID token: RS256 only, `iss` in the two Google issuers, `aud` in the allowlist. */
export async function verifyGoogleIdToken(
  idToken: string,
  clientIds: string[],
  jwks: JWTVerifyGetKey,
  now?: Date,
): Promise<GoogleIdentity> {
  let payload;
  try {
    ({ payload } = await jwtVerify(idToken, jwks, {
      issuer: GOOGLE_ISSUERS,
      audience: clientIds,
      algorithms: ['RS256'],
      currentDate: now,
    }));
  } catch (err) {
    if (err instanceof joseErrors.JOSEError) throw new GoogleAuthError('id token rejected');
    throw err;
  }
  if (typeof payload.sub !== 'string' || payload.sub === '') throw new GoogleAuthError('id token has no sub');
  return {
    sub: payload.sub,
    email: typeof payload.email === 'string' ? payload.email : null,
    emailVerified: payload.email_verified === true || payload.email_verified === 'true',
    name: typeof payload.name === 'string' && payload.name.trim() !== '' ? payload.name.trim() : null,
  };
}
