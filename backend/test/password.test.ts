import { describe, expect, it } from 'vitest';
import {
  LoginThrottle,
  hashPassword,
  loadBreachedList,
  validatePassword,
  validateUsername,
  verifyPassword,
} from '../src/auth/password.js';

describe('validateUsername', () => {
  it('normalizes to lower case and accepts [a-z0-9_.] of length 3–24', () => {
    expect(validateUsername('Tomek_1')).toEqual({ ok: true, username: 'tomek_1' });
    expect(validateUsername('  a.b  ')).toEqual({ ok: true, username: 'a.b' });
    expect(validateUsername('x'.repeat(24)).ok).toBe(true);
  });

  it('rejects short, long and out-of-charset names', () => {
    expect(validateUsername('ab').ok).toBe(false);
    expect(validateUsername('x'.repeat(25)).ok).toBe(false);
    expect(validateUsername('with space').ok).toBe(false);
    expect(validateUsername('émile').ok).toBe(false);
    expect(validateUsername('dash-ed').ok).toBe(false);
  });
});

describe('validatePassword', () => {
  const breached = new Set(['correct horse battery', 'password123']);

  it('enforces 10–128 characters and nothing else compositional', () => {
    expect(validatePassword('123456789', 'user', breached)).toMatchObject({ ok: false, error: 'password_too_short' });
    expect(validatePassword('a'.repeat(129), 'user', breached)).toMatchObject({ ok: false, error: 'password_too_long' });
    expect(validatePassword('all lowercase words here', 'user', breached)).toEqual({ ok: true });
    expect(validatePassword('🍕🍕🍕🍕🍕🍕🍕🍕🍕🍕', 'user', breached)).toEqual({ ok: true });
  });

  it('rejects passwords containing the username (case-insensitive)', () => {
    expect(validatePassword('MyTomek_1Secret', 'Tomek_1', breached)).toMatchObject({ ok: false, error: 'password_contains_username' });
  });

  it('rejects breached passwords regardless of case', () => {
    expect(validatePassword('Correct Horse Battery', 'user', breached)).toMatchObject({ ok: false, error: 'password_breached' });
  });

  it('ships a bundled top-10k list that contains the classics', () => {
    const list = loadBreachedList();
    expect(list.size).toBeGreaterThanOrEqual(10_000);
    expect(list.has('password')).toBe(true);
    expect(list.has('123456')).toBe(true);
    expect(validatePassword('qwerty123456', 'user')).toMatchObject({ ok: false, error: 'password_breached' });
  });
});

describe('argon2 hashing', () => {
  it('verifies the right password and rejects the wrong one', async () => {
    const hash = await hashPassword('a fine passphrase');
    expect(hash.startsWith('$argon2id$')).toBe(true);
    expect(hash).toContain('m=19456');
    expect(hash).toContain('t=2');
    expect(hash).toContain('p=1');
    expect(await verifyPassword(hash, 'a fine passphrase')).toBe(true);
    expect(await verifyPassword(hash, 'a fine passphrasE')).toBe(false);
  });

  it('runs the dummy hash for missing users and always returns false', async () => {
    expect(await verifyPassword(null, 'anything at all')).toBe(false);
    expect(await verifyPassword('not-a-hash', 'anything at all')).toBe(false);
  });
});

describe('LoginThrottle', () => {
  it('allows five failures, then delays exponentially', () => {
    const t = new LoginThrottle({ baseDelayMs: 1000 });
    let now = 1_000_000;
    for (let i = 0; i < 5; i++) {
      expect(t.check('Bob', now)).toEqual({ allowed: true });
      t.recordFailure('bob', now);
    }
    expect(t.check('bob', now)).toEqual({ allowed: false, retryAfterMs: 1000, locked: false });
    now += 1000;
    expect(t.check('bob', now)).toEqual({ allowed: true });
    t.recordFailure('bob', now);
    expect(t.check('bob', now)).toEqual({ allowed: false, retryAfterMs: 2000, locked: false });
    t.recordFailure('bob', now);
    expect(t.check('bob', now)).toEqual({ allowed: false, retryAfterMs: 4000, locked: false });
  });

  it('caps the delay, resets on success and hard-locks after the hard limit', () => {
    const t = new LoginThrottle({ softLimit: 5, hardLimit: 8, baseDelayMs: 1000, maxDelayMs: 3000, lockMs: 60_000 });
    const now = 5_000_000;
    for (let i = 0; i < 7; i++) t.recordFailure('eve', now);
    expect(t.check('eve', now)).toEqual({ allowed: false, retryAfterMs: 3000, locked: false });
    t.recordSuccess('eve');
    expect(t.check('eve', now)).toEqual({ allowed: true });
    for (let i = 0; i < 8; i++) t.recordFailure('eve', now);
    expect(t.check('eve', now)).toEqual({ allowed: false, retryAfterMs: 60_000, locked: true });
    expect(t.check('eve', now + 59_999).allowed).toBe(false);
    expect(t.check('eve', now + 60_000).allowed).toBe(true);
  });
});
