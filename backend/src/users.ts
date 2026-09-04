import { isUniqueViolation, type Queryable, type Sql } from './db.js';
import { meDTO, partnerDTO, type MeDTO, type PartnerDTO, type UserRow } from './dto.js';
import { activePairing } from './pairing.js';

export interface IdentityInput {
  provider: 'apple' | 'google';
  subject: string;
  email: string | null;
  emailVerified: boolean;
  /** Only Apple sends the name, and only on the very first sign-in. */
  displayName: string | null;
  /** AES-GCM encrypted Apple refresh token (for `/auth/revoke` on account deletion). */
  appleRefreshTokenEnc: Buffer | null;
}

export async function getUser(sql: Queryable, id: string): Promise<UserRow | null> {
  const rows = await sql<UserRow[]>`
    select id, username, email, email_verified, display_name, locale, tz, created_at
    from users where id = ${id}`;
  return rows[0] ?? null;
}

/**
 * Sign-in with a social identity: the `(provider, subject)` pair is the key. One user row, many
 * identities; never merged by email (Apple relay addresses make that unsafe).
 */
export async function findOrCreateIdentityUser(sql: Sql, ident: IdentityInput): Promise<{ user: UserRow; created: boolean }> {
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      return await sql.begin(async (tx) => {
        const existing = await tx<{ userId: string }[]>`
          select user_id from identities where provider = ${ident.provider} and subject = ${ident.subject}`;
        const found = existing[0];
        if (found) {
          if (ident.appleRefreshTokenEnc) {
            await tx`
              update identities set apple_refresh_token_enc = ${ident.appleRefreshTokenEnc}
              where provider = ${ident.provider} and subject = ${ident.subject}`;
          }
          if (ident.displayName) {
            await tx`update users set display_name = ${ident.displayName} where id = ${found.userId} and display_name = ''`;
          }
          const user = await getUser(tx, found.userId);
          if (!user) throw new Error('identity references a missing user');
          return { user, created: false };
        }
        const inserted = await tx<UserRow[]>`
          insert into users (email, email_verified, display_name)
          values (${ident.email}, ${ident.emailVerified}, ${ident.displayName ?? ''})
          returning id, username, email, email_verified, display_name, locale, tz, created_at`;
        const user = inserted[0]!;
        await tx`
          insert into identities (user_id, provider, subject, email_at_link, apple_refresh_token_enc)
          values (${user.id}, ${ident.provider}, ${ident.subject}, ${ident.email}, ${ident.appleRefreshTokenEnc})`;
        return { user, created: true };
      });
    } catch (err) {
      // Two first sign-ins racing: the loser retries and finds the identity the winner inserted.
      if (attempt === 0 && isUniqueViolation(err)) continue;
      throw err;
    }
  }
  throw new Error('unreachable');
}

export type LinkResult = 'linked' | 'already_linked' | 'conflict';

/** Attaches a social identity to an existing account; refuses when it belongs to someone else. */
export async function linkIdentity(sql: Sql, userId: string, ident: IdentityInput): Promise<LinkResult> {
  try {
    return await sql.begin(async (tx): Promise<LinkResult> => {
      const existing = await tx<{ userId: string }[]>`
        select user_id from identities where provider = ${ident.provider} and subject = ${ident.subject}`;
      const found = existing[0];
      if (found) {
        if (found.userId !== userId) return 'conflict';
        if (ident.appleRefreshTokenEnc) {
          await tx`
            update identities set apple_refresh_token_enc = ${ident.appleRefreshTokenEnc}
            where provider = ${ident.provider} and subject = ${ident.subject}`;
        }
        return 'already_linked';
      }
      await tx`
        insert into identities (user_id, provider, subject, email_at_link, apple_refresh_token_enc)
        values (${userId}, ${ident.provider}, ${ident.subject}, ${ident.email}, ${ident.appleRefreshTokenEnc})`;
      return 'linked';
    });
  } catch (err) {
    if (isUniqueViolation(err)) return 'conflict';
    throw err;
  }
}

/** The `Me` DTO: profile + active partner + current unused pair code. */
export async function loadMe(sql: Queryable, userId: string): Promise<MeDTO | null> {
  const user = await getUser(sql, userId);
  if (!user) return null;
  const pairing = await activePairing(sql, userId);
  let partner: PartnerDTO | null = null;
  if (pairing) {
    const partnerUser = await getUser(sql, pairing.partnerId);
    if (partnerUser) partner = partnerDTO(partnerUser, pairing.createdAt);
  }
  const codes = await sql<{ code: string }[]>`
    select code from pair_codes
    where user_id = ${userId} and used_at is null and expires_at > now()
    order by expires_at desc limit 1`;
  return meDTO(user, partner, codes[0]?.code ?? null);
}
