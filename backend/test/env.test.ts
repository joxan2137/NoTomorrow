import { describe, expect, it } from 'vitest';
import { EnvError, loadEnv } from '../src/env.js';

const KEY_B64 = Buffer.alloc(32, 7).toString('base64');
const PEM = '-----BEGIN PRIVATE KEY-----\nMIGT\n-----END PRIVATE KEY-----';

const minimal = {
  DATABASE_URL: 'postgres://u:p@localhost:5432/db',
  JWT_SECRET: 'x'.repeat(32),
  REFRESH_PEPPER: 'y'.repeat(16),
  TOKEN_ENC_KEY: KEY_B64,
};

describe('loadEnv', () => {
  it('boots with only the required variables and disables every optional provider', () => {
    const env = loadEnv(minimal);
    expect(env.port).toBe(8080);
    expect(env.apple).toBeNull();
    expect(env.google).toBeNull();
    expect(env.apns).toBeNull();
    expect(env.gemini).toBeNull();
    expect(env.aiDailyLimit).toBe(30);
    expect(env.jobsEnabled).toBe(true);
    expect(env.tokenEncKey).toHaveLength(32);
    expect(env.warnings).toHaveLength(4);
  });

  it('fails clearly (naming variables, never values) when required config is missing or malformed', () => {
    expect(() => loadEnv({ ...minimal, JWT_SECRET: 'short' })).toThrow(EnvError);
    expect(() => loadEnv({ ...minimal, TOKEN_ENC_KEY: Buffer.alloc(16).toString('base64') })).toThrow(/TOKEN_ENC_KEY/);
    try {
      loadEnv({ ...minimal, DATABASE_URL: '' });
      expect.unreachable();
    } catch (err) {
      expect(err).toBeInstanceOf(EnvError);
      expect((err as Error).message).toContain('DATABASE_URL');
      expect((err as Error).message).not.toContain(KEY_B64);
    }
  });

  it('treats a partially configured provider as a configuration error', () => {
    expect(() => loadEnv({ ...minimal, APPLE_TEAM_ID: 'T', APPLE_BUNDLE_ID: 'b' })).toThrow(/APPLE_KEY_ID/);
    expect(() => loadEnv({ ...minimal, APNS_KEY_ID: 'K' })).toThrow(/APNs is partially configured/);
  });

  it('decodes base64 PEM keys, client id lists and booleans', () => {
    const env = loadEnv({
      ...minimal,
      APPLE_TEAM_ID: 'TEAM',
      APPLE_BUNDLE_ID: 'app.notomorrow',
      APPLE_KEY_ID: 'KID',
      APPLE_PRIVATE_KEY_P8_B64: Buffer.from(PEM).toString('base64'),
      GOOGLE_CLIENT_IDS: ' a.apps.googleusercontent.com, b.apps.googleusercontent.com ,',
      APNS_KEY_ID: 'AK',
      APNS_TEAM_ID: 'TEAM',
      APNS_P8_B64: PEM,
      APNS_TOPIC: 'app.notomorrow',
      APNS_PRODUCTION: 'true',
      GEMINI_API_KEY: 'g',
      AI_DAILY_LIMIT: '5',
      JOBS_ENABLED: '0',
    });
    expect(env.apple).toEqual({ teamId: 'TEAM', bundleId: 'app.notomorrow', keyId: 'KID', privateKeyPem: PEM });
    expect(env.google?.clientIds).toEqual(['a.apps.googleusercontent.com', 'b.apps.googleusercontent.com']);
    expect(env.apns).toMatchObject({ keyId: 'AK', topic: 'app.notomorrow', production: true, keyPem: PEM });
    expect(env.gemini).toEqual({ apiKey: 'g', model: 'gemini-3.1-flash-lite' });
    expect(env.aiDailyLimit).toBe(5);
    expect(env.jobsEnabled).toBe(false);
    expect(env.warnings).toEqual([]);
  });

  it('rejects a p8 that is not a PEM', () => {
    expect(() => loadEnv({ ...minimal, APNS_KEY_ID: 'AK', APNS_TEAM_ID: 'T', APNS_P8_B64: 'bm90IGEgcGVt', APNS_TOPIC: 't' })).toThrow(/APNS_P8_B64/);
  });
});
