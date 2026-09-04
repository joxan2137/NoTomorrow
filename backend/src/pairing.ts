import { randomBytes } from 'node:crypto';
import type { Queryable } from './db.js';

/** No 0/O/1/I so codes are unambiguous when read aloud. 32 symbols → one byte masks uniformly. */
export const PAIR_CODE_CHARSET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
export const PAIR_CODE_PREFIX = 'NT-';
export const PAIR_CODE_LENGTH = 4;
export const PAIR_CODE_TTL_MS = 24 * 60 * 60 * 1000;
export const PAIR_CODE_RE = new RegExp(`^${PAIR_CODE_PREFIX}[${PAIR_CODE_CHARSET}]{${PAIR_CODE_LENGTH}}$`);

export function generatePairCode(random: (n: number) => Uint8Array = (n) => randomBytes(n)): string {
  const bytes = random(PAIR_CODE_LENGTH);
  let out = PAIR_CODE_PREFIX;
  for (let i = 0; i < PAIR_CODE_LENGTH; i++) {
    out += PAIR_CODE_CHARSET[(bytes[i] ?? 0) & 31];
  }
  return out;
}

/** Uppercases, strips whitespace, tolerates a missing "NT-" prefix; null when it cannot be a code. */
export function normalizePairCode(input: string): string | null {
  let s = input.replace(/\s+/g, '').toUpperCase();
  if (!s.startsWith(PAIR_CODE_PREFIX)) s = PAIR_CODE_PREFIX + s.replace(/^NT/, '');
  return PAIR_CODE_RE.test(s) ? s : null;
}

export interface ActivePairing {
  id: string;
  partnerId: string;
  createdAt: Date;
}

interface PairingRow {
  id: string;
  userA: string;
  userB: string;
  createdAt: Date;
}

export async function activePairing(sql: Queryable, userId: string): Promise<ActivePairing | null> {
  const rows = await sql<PairingRow[]>`
    select id, user_a, user_b, created_at
    from pairings
    where (user_a = ${userId} or user_b = ${userId}) and ended_at is null
    limit 1`;
  const row = rows[0];
  if (!row) return null;
  return { id: row.id, partnerId: row.userA === userId ? row.userB : row.userA, createdAt: row.createdAt };
}

/** Ends every active pairing of the user; returns the partner ids that were affected. */
export async function endPairings(sql: Queryable, userId: string): Promise<string[]> {
  const rows = await sql<{ userA: string; userB: string }[]>`
    update pairings set ended_at = now()
    where (user_a = ${userId} or user_b = ${userId}) and ended_at is null
    returning user_a, user_b`;
  return rows.map((r) => (r.userA === userId ? r.userB : r.userA));
}
