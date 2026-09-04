import { z } from 'zod';
import { encryptSecret } from '../crypto.js';
import { HttpError } from '../http.js';
import type { AppDeps } from '../types.js';
import type { IdentityInput } from '../users.js';
import { AppleAuthError, exchangeAppleCode, verifyAppleIdentityToken } from './apple.js';
import { GoogleAuthError, verifyGoogleIdToken } from './google.js';

/** `fullName` arrives either as a string or as the PersonNameComponents the iOS SDK hands out. */
const fullNameSchema = z
  .union([z.string(), z.object({ givenName: z.string().nullish(), familyName: z.string().nullish() })])
  .nullish();

export const appleSignInSchema = z.object({
  identityToken: z.string().min(1).max(8192),
  authorizationCode: z.string().min(1).max(2048),
  fullName: fullNameSchema,
  nonce: z.string().min(1).max(512).nullish(),
});
export type AppleSignInBody = z.infer<typeof appleSignInSchema>;

export const googleSignInSchema = z.object({
  idToken: z.string().min(1).max(8192),
});
export type GoogleSignInBody = z.infer<typeof googleSignInSchema>;

function fullNameToString(fullName: AppleSignInBody['fullName']): string | null {
  if (!fullName) return null;
  const s = typeof fullName === 'string' ? fullName : [fullName.givenName, fullName.familyName].filter(Boolean).join(' ');
  const trimmed = s.trim().slice(0, 80);
  return trimmed === '' ? null : trimmed;
}

/** Verifies the identity token, exchanges the code, and returns the identity to upsert/link. */
export async function resolveAppleIdentity(deps: AppDeps, body: AppleSignInBody): Promise<IdentityInput> {
  const config = deps.env.apple;
  if (!config) throw new HttpError(503, 'apple_unavailable', 'Sign in with Apple is not configured on this server');

  let identity;
  try {
    identity = await verifyAppleIdentityToken(body.identityToken, config.bundleId, deps.jwks.apple, {
      nonce: body.nonce ?? undefined,
      now: deps.now(),
    });
  } catch (err) {
    if (err instanceof AppleAuthError) throw new HttpError(401, 'invalid_identity_token', 'Apple identity token was rejected');
    throw err;
  }

  let exchange;
  try {
    exchange = await exchangeAppleCode(config, body.authorizationCode, deps.fetchImpl, deps.now());
  } catch (err) {
    if (err instanceof AppleAuthError) {
      deps.log.warn({ reason: err.code }, 'apple authorization code exchange failed');
      throw new HttpError(401, 'apple_code_exchange_failed', 'Apple rejected the authorization code');
    }
    throw err;
  }

  return {
    provider: 'apple',
    subject: identity.sub,
    email: identity.email,
    emailVerified: identity.emailVerified,
    displayName: fullNameToString(body.fullName),
    appleRefreshTokenEnc: encryptSecret(deps.env.tokenEncKey, exchange.refreshToken),
  };
}

export async function resolveGoogleIdentity(deps: AppDeps, body: GoogleSignInBody): Promise<IdentityInput> {
  const config = deps.env.google;
  if (!config) throw new HttpError(503, 'google_unavailable', 'Google Sign-In is not configured on this server');
  try {
    const identity = await verifyGoogleIdToken(body.idToken, config.clientIds, deps.jwks.google, deps.now());
    return {
      provider: 'google',
      subject: identity.sub,
      email: identity.email,
      emailVerified: identity.emailVerified,
      displayName: identity.name,
      appleRefreshTokenEnc: null,
    };
  } catch (err) {
    if (err instanceof GoogleAuthError) throw new HttpError(401, 'invalid_id_token', 'Google ID token was rejected');
    throw err;
  }
}
