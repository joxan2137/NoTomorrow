import { randomUUID } from 'node:crypto';
import { randomToken, sha256Hex } from '../crypto.js';
import type { Queryable } from '../db.js';

/**
 * Opaque refresh tokens: 32 random bytes (base64url), stored only as sha256(token + pepper),
 * valid 90 days, rotated on every use. Presenting a token that was already rotated is treated
 * as theft: the whole family (every token descended from the original login) is revoked.
 */

export const REFRESH_TOKEN_TTL_MS = 90 * 24 * 60 * 60 * 1000;

export interface RefreshTokenRow {
  id: string;
  userId: string;
  tokenHash: string;
  family: string;
  expiresAt: Date;
  revokedAt: Date | null;
  replacedBy: string | null;
}

export interface RefreshTokenStore {
  findByHash(hash: string): Promise<RefreshTokenRow | null>;
  insert(row: RefreshTokenRow): Promise<void>;
  /** Marks a live token as rotated. Returns false when it was already revoked (concurrent or replayed use). */
  markRotated(id: string, replacedBy: string, at: Date): Promise<boolean>;
  revokeFamily(family: string, at: Date): Promise<void>;
}

export function hashRefreshToken(token: string, pepper: string): string {
  return sha256Hex(token + pepper);
}

function newRow(userId: string, family: string, tokenHash: string, now: Date, id = randomUUID()): RefreshTokenRow {
  return {
    id,
    userId,
    tokenHash,
    family,
    expiresAt: new Date(now.getTime() + REFRESH_TOKEN_TTL_MS),
    revokedAt: null,
    replacedBy: null,
  };
}

export async function issueRefreshToken(
  store: RefreshTokenStore,
  pepper: string,
  userId: string,
  opts: { family?: string; now?: Date } = {},
): Promise<{ token: string; row: RefreshTokenRow }> {
  const now = opts.now ?? new Date();
  const token = randomToken(32);
  const row = newRow(userId, opts.family ?? randomUUID(), hashRefreshToken(token, pepper), now);
  await store.insert(row);
  return { token, row };
}

export type RotateResult =
  | { ok: true; userId: string; token: string; row: RefreshTokenRow }
  | { ok: false; reason: 'invalid' | 'expired' | 'reused' };

export async function rotateRefreshToken(
  store: RefreshTokenStore,
  pepper: string,
  token: string,
  now: Date = new Date(),
): Promise<RotateResult> {
  if (!token) return { ok: false, reason: 'invalid' };
  const current = await store.findByHash(hashRefreshToken(token, pepper));
  if (!current) return { ok: false, reason: 'invalid' };
  if (current.revokedAt) {
    if (current.replacedBy) {
      // Reuse of a rotated token: someone else holds the newer one. Kill the lineage.
      await store.revokeFamily(current.family, now);
      return { ok: false, reason: 'reused' };
    }
    return { ok: false, reason: 'invalid' };
  }
  if (current.expiresAt.getTime() <= now.getTime()) return { ok: false, reason: 'expired' };

  const nextId = randomUUID();
  const rotated = await store.markRotated(current.id, nextId, now);
  if (!rotated) {
    await store.revokeFamily(current.family, now);
    return { ok: false, reason: 'reused' };
  }
  const next = randomToken(32);
  const row = newRow(current.userId, current.family, hashRefreshToken(next, pepper), now, nextId);
  await store.insert(row);
  return { ok: true, userId: current.userId, token: next, row };
}

/** Logout: revokes the token and everything in its family. Returns false when the token is unknown. */
export async function revokeRefreshToken(
  store: RefreshTokenStore,
  pepper: string,
  token: string,
  now: Date = new Date(),
): Promise<boolean> {
  if (!token) return false;
  const row = await store.findByHash(hashRefreshToken(token, pepper));
  if (!row) return false;
  await store.revokeFamily(row.family, now);
  return true;
}

export function pgRefreshTokenStore(sql: Queryable): RefreshTokenStore {
  return {
    async findByHash(hash) {
      const rows = await sql<RefreshTokenRow[]>`
        select id, user_id, token_hash, family, expires_at, revoked_at, replaced_by
        from refresh_tokens where token_hash = ${hash}`;
      return rows[0] ?? null;
    },
    async insert(row) {
      await sql`
        insert into refresh_tokens (id, user_id, token_hash, family, expires_at)
        values (${row.id}, ${row.userId}, ${row.tokenHash}, ${row.family}, ${row.expiresAt})`;
    },
    async markRotated(id, replacedBy, at) {
      const rows = await sql<{ id: string }[]>`
        update refresh_tokens set revoked_at = ${at}, replaced_by = ${replacedBy}
        where id = ${id} and revoked_at is null
        returning id`;
      return rows.length > 0;
    },
    async revokeFamily(family, at) {
      await sql`update refresh_tokens set revoked_at = coalesce(revoked_at, ${at}) where family = ${family}`;
    },
  };
}
