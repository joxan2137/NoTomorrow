import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import argon2 from 'argon2';
import { randomToken } from '../crypto.js';

/**
 * Username/password policy (NIST 800-63B flavoured): length only, no composition rules,
 * blocklist of the top-10k breached passwords, no username inside the password.
 * Hashing: argon2id m=19456 KiB, t=2, p=1 (OWASP minimum).
 */

export const USERNAME_RE = /^[a-z0-9_.]{3,24}$/;
export const PASSWORD_MIN_LENGTH = 10;
export const PASSWORD_MAX_LENGTH = 128;
export const BREACHED_LIST_PATH = fileURLToPath(new URL('../../data/breached-passwords.txt', import.meta.url));

export const ARGON2_OPTIONS = { type: argon2.argon2id, memoryCost: 19456, timeCost: 2, parallelism: 1 } as const;

export type UsernameError = 'username_invalid';
export type PasswordError = 'password_too_short' | 'password_too_long' | 'password_contains_username' | 'password_breached';

export function normalizeUsername(raw: string): string {
  return raw.trim().toLowerCase();
}

export function validateUsername(raw: string): { ok: true; username: string } | { ok: false; error: UsernameError; message: string } {
  const username = normalizeUsername(raw);
  if (!USERNAME_RE.test(username)) {
    return { ok: false, error: 'username_invalid', message: 'Username must be 3–24 characters: letters, digits, "_" or "."' };
  }
  return { ok: true, username };
}

const listCache = new Map<string, Set<string>>();

/** Lower-cased set of breached passwords; cached per path for the process lifetime. */
export function loadBreachedList(path: string = BREACHED_LIST_PATH): Set<string> {
  let set = listCache.get(path);
  if (set) return set;
  set = new Set<string>();
  for (const line of readFileSync(path, 'utf8').split('\n')) {
    const value = line.trim();
    if (value && !value.startsWith('#')) set.add(value.toLowerCase());
  }
  listCache.set(path, set);
  return set;
}

export function validatePassword(
  password: string,
  username: string,
  breached: Set<string> = loadBreachedList(),
): { ok: true } | { ok: false; error: PasswordError; message: string } {
  const length = Array.from(password).length;
  if (length < PASSWORD_MIN_LENGTH) {
    return { ok: false, error: 'password_too_short', message: `Password must be at least ${PASSWORD_MIN_LENGTH} characters` };
  }
  if (length > PASSWORD_MAX_LENGTH) {
    return { ok: false, error: 'password_too_long', message: `Password must be at most ${PASSWORD_MAX_LENGTH} characters` };
  }
  const lower = password.toLowerCase();
  const user = normalizeUsername(username);
  if (user.length >= 3 && lower.includes(user)) {
    return { ok: false, error: 'password_contains_username', message: 'Password must not contain your username' };
  }
  if (breached.has(lower)) {
    return { ok: false, error: 'password_breached', message: 'That password appears in known breaches; pick another' };
  }
  return { ok: true };
}

export function hashPassword(password: string): Promise<string> {
  return argon2.hash(password, ARGON2_OPTIONS);
}

let dummyHash: Promise<string> | null = null;

/**
 * Verifies against `hash`; when the account does not exist (`hash === null`) a throw-away hash
 * is verified instead so the response time does not reveal whether the username exists.
 */
export async function verifyPassword(hash: string | null, password: string): Promise<boolean> {
  dummyHash ??= argon2.hash(randomToken(24), ARGON2_OPTIONS);
  const target = hash ?? (await dummyHash);
  try {
    const matches = await argon2.verify(target, password);
    return matches && hash !== null;
  } catch {
    return false;
  }
}

export interface ThrottleOptions {
  /** Failures before delays kick in. */
  softLimit?: number;
  /** Failures before the username is hard-locked. */
  hardLimit?: number;
  baseDelayMs?: number;
  maxDelayMs?: number;
  /** How long a hard lock lasts. */
  lockMs?: number;
  /** Failure counters older than this are forgotten. */
  windowMs?: number;
}

export type ThrottleDecision = { allowed: true } | { allowed: false; retryAfterMs: number; locked: boolean };

interface ThrottleEntry {
  failures: number;
  lastFailureAt: number;
  lockedUntil: number;
}

/**
 * Per-username login throttle: after `softLimit` failures each further attempt must wait
 * 2^(n - softLimit) × base (capped), after `hardLimit` the username is locked for `lockMs`.
 * In-memory — fine for one machine; counters reset on restart.
 */
export class LoginThrottle {
  private readonly entries = new Map<string, ThrottleEntry>();
  private readonly softLimit: number;
  private readonly hardLimit: number;
  private readonly baseDelayMs: number;
  private readonly maxDelayMs: number;
  private readonly lockMs: number;
  private readonly windowMs: number;

  constructor(opts: ThrottleOptions = {}) {
    this.softLimit = opts.softLimit ?? 5;
    this.hardLimit = opts.hardLimit ?? 100;
    this.baseDelayMs = opts.baseDelayMs ?? 1_000;
    this.maxDelayMs = opts.maxDelayMs ?? 15 * 60_000;
    this.lockMs = opts.lockMs ?? 24 * 60 * 60_000;
    this.windowMs = opts.windowMs ?? 24 * 60 * 60_000;
  }

  check(username: string, now: number = Date.now()): ThrottleDecision {
    const key = normalizeUsername(username);
    const entry = this.entries.get(key);
    if (!entry) return { allowed: true };
    if (entry.lockedUntil > now) return { allowed: false, retryAfterMs: entry.lockedUntil - now, locked: true };
    if (now - entry.lastFailureAt > this.windowMs) {
      this.entries.delete(key);
      return { allowed: true };
    }
    if (entry.failures < this.softLimit) return { allowed: true };
    const delay = Math.min(this.maxDelayMs, this.baseDelayMs * 2 ** (entry.failures - this.softLimit));
    const until = entry.lastFailureAt + delay;
    return until > now ? { allowed: false, retryAfterMs: until - now, locked: false } : { allowed: true };
  }

  recordFailure(username: string, now: number = Date.now()): void {
    const key = normalizeUsername(username);
    const entry = this.entries.get(key) ?? { failures: 0, lastFailureAt: now, lockedUntil: 0 };
    entry.failures += 1;
    entry.lastFailureAt = now;
    if (entry.failures >= this.hardLimit) entry.lockedUntil = now + this.lockMs;
    this.entries.set(key, entry);
    if (this.entries.size > 10_000) this.prune(now);
  }

  recordSuccess(username: string): void {
    this.entries.delete(normalizeUsername(username));
  }

  prune(now: number = Date.now()): void {
    for (const [key, entry] of this.entries) {
      if (entry.lockedUntil <= now && now - entry.lastFailureAt > this.windowMs) this.entries.delete(key);
    }
  }
}
