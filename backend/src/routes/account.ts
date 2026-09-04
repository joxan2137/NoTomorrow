import { Hono, type Context } from 'hono';
import { z } from 'zod';
import { AppleAuthError, revokeAppleRefreshToken } from '../auth/apple.js';
import { appleSignInSchema, googleSignInSchema, resolveAppleIdentity, resolveGoogleIdentity } from '../auth/social.js';
import { decryptSecret } from '../crypto.js';
import { displayNameOf } from '../dto.js';
import { HttpError, ok, parseJson } from '../http.js';
import { normalizeLocale } from '../i18n.js';
import { endPairings } from '../pairing.js';
import { isValidTimeZone } from '../time.js';
import type { AppDeps, AppEnv } from '../types.js';
import { getUser, linkIdentity, loadMe } from '../users.js';

const patchMeSchema = z
  .object({
    displayName: z.string().trim().min(1).max(40).optional(),
    locale: z.string().trim().min(2).max(16).optional(),
    tz: z.string().trim().min(1).max(64).optional(),
  })
  .strict();

const linkSchema = z.discriminatedUnion('provider', [
  appleSignInSchema.extend({ provider: z.literal('apple') }),
  googleSignInSchema.extend({ provider: z.literal('google') }),
]);

/**
 * Full account deletion: revoke Apple tokens (best effort), end pairings (notify the partner),
 * delete the user row (everything else cascades). Shared by `DELETE /me` and Apple's
 * server-to-server `consent-revoked` / `account-delete` events.
 */
export async function deleteUserAccount(deps: AppDeps, userId: string): Promise<boolean> {
  const { sql, log, push, events } = deps;
  const user = await getUser(sql, userId);
  if (!user) return false;

  const appleIdentities = await sql<{ appleRefreshTokenEnc: Uint8Array | null }[]>`
    select apple_refresh_token_enc from identities where user_id = ${userId} and provider = 'apple'`;
  for (const ident of appleIdentities) {
    if (!ident.appleRefreshTokenEnc) continue;
    if (!deps.env.apple) {
      log.warn({ userId }, 'apple identity present but Sign in with Apple is not configured; skipping revoke');
      continue;
    }
    try {
      const token = decryptSecret(deps.env.tokenEncKey, ident.appleRefreshTokenEnc);
      await revokeAppleRefreshToken(deps.env.apple, token, deps.fetchImpl, deps.now());
    } catch (err) {
      const reason = err instanceof AppleAuthError ? err.code : err instanceof Error ? err.name : 'unknown';
      log.warn({ userId, reason }, 'apple token revoke failed; continuing with deletion');
    }
  }

  const partners = await endPairings(sql, userId);
  for (const partnerId of partners) {
    await push.send(partnerId, { kind: 'unpaired', name: displayNameOf(user) }, { partnerId: userId });
    events.publish(partnerId, { type: 'pairing', action: 'ended', partnerId: userId });
  }

  await sql`delete from users where id = ${userId}`;
  log.info({ userId, partnersNotified: partners.length }, 'account deleted');
  return true;
}

export function accountRoutes(deps: AppDeps): Hono<AppEnv> {
  const r = new Hono<AppEnv>();

  r.get('/me', async (c) => {
    const me = await loadMe(deps.sql, c.get('userId'));
    if (!me) throw new HttpError(404, 'user_not_found', 'Account no longer exists');
    return c.json(me);
  });

  // Not in the backend spec, but push copy and the 21:00 job depend on locale/tz.
  r.patch('/me', async (c) => {
    const userId = c.get('userId');
    const body = await parseJson(c, patchMeSchema);
    if (body.tz !== undefined && !isValidTimeZone(body.tz)) throw new HttpError(400, 'invalid_tz', 'Unknown IANA time zone');
    const displayName = body.displayName ?? null;
    const locale = body.locale !== undefined ? normalizeLocale(body.locale) : null;
    const tz = body.tz ?? null;
    const rows = await deps.sql<{ id: string }[]>`
      update users set
        display_name = coalesce(${displayName}, display_name),
        locale = coalesce(${locale}, locale),
        tz = coalesce(${tz}, tz)
      where id = ${userId} returning id`;
    if (rows.length === 0) throw new HttpError(404, 'user_not_found', 'Account no longer exists');
    return c.json(await loadMe(deps.sql, userId));
  });

  const remove = async (c: Context<AppEnv>) => {
    await deleteUserAccount(deps, c.get('userId'));
    return ok(c);
  };
  r.delete('/me', remove);
  // The iOS client calls DELETE /account.
  r.delete('/account', remove);

  r.post('/account/identities', async (c) => {
    const userId = c.get('userId');
    const body = await parseJson(c, linkSchema);
    const ident = body.provider === 'apple' ? await resolveAppleIdentity(deps, body) : await resolveGoogleIdentity(deps, body);
    const result = await linkIdentity(deps.sql, userId, ident);
    if (result === 'conflict') {
      throw new HttpError(409, 'identity_belongs_to_other_user', 'That sign-in is already linked to a different account');
    }
    const me = await loadMe(deps.sql, userId);
    return c.json({ linked: result === 'linked', alreadyLinked: result === 'already_linked', user: me });
  });

  return r;
}
