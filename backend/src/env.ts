import { z } from 'zod';

/**
 * Environment configuration, validated once at boot.
 * Required: DATABASE_URL, JWT_SECRET, REFRESH_PEPPER, TOKEN_ENC_KEY.
 * Optional providers (Apple, Google, APNs, Gemini) become `null` when absent so the
 * routes that need them can answer 503 instead of the process crashing at boot.
 */

const emptyToUndefined = (v: unknown): unknown => (typeof v === 'string' && v.trim() === '' ? undefined : v);
const optStr = z.preprocess(emptyToUndefined, z.string().trim().optional());
const optBool = z.preprocess(emptyToUndefined, z.union([z.boolean(), z.string()]).optional());
const withDefault = <T extends z.ZodTypeAny>(schema: T) => z.preprocess(emptyToUndefined, schema);

const LOG_LEVELS = ['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent'] as const;

const rawSchema = z.object({
  NODE_ENV: withDefault(z.enum(['development', 'test', 'production']).default('development')),
  PORT: withDefault(z.coerce.number().int().min(1).max(65535).default(8080)),
  LOG_LEVEL: withDefault(z.enum(LOG_LEVELS).default('info')),
  DATABASE_URL: z.string().min(1, 'DATABASE_URL is required'),
  JWT_SECRET: z.string().min(32, 'JWT_SECRET must be at least 32 characters'),
  REFRESH_PEPPER: z.string().min(16, 'REFRESH_PEPPER must be at least 16 characters'),
  TOKEN_ENC_KEY: z.string().min(1, 'TOKEN_ENC_KEY is required (32 random bytes, base64)'),
  APPLE_TEAM_ID: optStr,
  APPLE_BUNDLE_ID: optStr,
  APPLE_KEY_ID: optStr,
  APPLE_PRIVATE_KEY_P8_B64: optStr,
  GOOGLE_CLIENT_IDS: optStr,
  APNS_KEY_ID: optStr,
  APNS_TEAM_ID: optStr,
  APNS_P8_B64: optStr,
  APNS_TOPIC: optStr,
  APNS_PRODUCTION: optBool,
  GEMINI_API_KEY: optStr,
  GEMINI_MODEL: withDefault(z.string().trim().default('gemini-3.1-flash-lite')),
  AI_DAILY_LIMIT: withDefault(z.coerce.number().int().min(0).default(30)),
  JOBS_ENABLED: optBool,
});

export interface AppleConfig {
  teamId: string;
  bundleId: string;
  keyId: string;
  privateKeyPem: string;
}
export interface GoogleConfig {
  clientIds: string[];
}
export interface ApnsConfig {
  keyId: string;
  teamId: string;
  keyPem: string;
  topic: string;
  production: boolean;
}
export interface GeminiConfig {
  apiKey: string;
  model: string;
}

export interface Env {
  nodeEnv: 'development' | 'test' | 'production';
  port: number;
  logLevel: (typeof LOG_LEVELS)[number];
  databaseUrl: string;
  jwtSecret: string;
  refreshPepper: string;
  tokenEncKey: Buffer;
  apple: AppleConfig | null;
  google: GoogleConfig | null;
  apns: ApnsConfig | null;
  gemini: GeminiConfig | null;
  aiDailyLimit: number;
  jobsEnabled: boolean;
  /** Human-readable notes about providers that are disabled (logged at boot, never secrets). */
  warnings: string[];
}

export class EnvError extends Error {
  constructor(public readonly problems: string[]) {
    super(`Invalid environment:\n  - ${problems.join('\n  - ')}`);
    this.name = 'EnvError';
  }
}

function parseBool(v: string | boolean | undefined, fallback: boolean): boolean {
  if (v === undefined) return fallback;
  if (typeof v === 'boolean') return v;
  return ['1', 'true', 'yes', 'on'].includes(v.trim().toLowerCase());
}

/** Accepts either raw PEM or base64-encoded PEM (the `_B64` convention used by fly secrets). */
function decodePem(value: string): string | null {
  const trimmed = value.trim();
  if (trimmed.startsWith('-----BEGIN')) return trimmed.replace(/\\n/g, '\n');
  const decoded = Buffer.from(trimmed, 'base64').toString('utf8').trim();
  return decoded.startsWith('-----BEGIN') ? decoded : null;
}

interface Group {
  name: string;
  vars: Record<string, string | undefined>;
}

/** All-or-nothing provider groups: fully set → enabled, fully unset → disabled, partial → warning + disabled. */
function providerGroup(group: Group, problems: string[], warnings: string[]): boolean {
  const entries = Object.entries(group.vars);
  const missing = entries.filter(([, v]) => v === undefined).map(([k]) => k);
  if (missing.length === 0) return true;
  if (missing.length === entries.length) {
    warnings.push(`${group.name} disabled (not configured)`);
    return false;
  }
  problems.push(`${group.name} is partially configured; missing ${missing.join(', ')}`);
  return false;
}

export function loadEnv(source: Record<string, string | undefined> = process.env): Env {
  const parsed = rawSchema.safeParse(source);
  if (!parsed.success) {
    throw new EnvError(parsed.error.issues.map((i) => `${i.path.join('.') || '(root)'}: ${i.message}`));
  }
  const r = parsed.data;
  const problems: string[] = [];
  const warnings: string[] = [];

  const tokenEncKey = Buffer.from(r.TOKEN_ENC_KEY, 'base64');
  if (tokenEncKey.length !== 32) problems.push('TOKEN_ENC_KEY must decode to exactly 32 bytes (base64)');

  let apple: AppleConfig | null = null;
  const appleVars = {
    APPLE_TEAM_ID: r.APPLE_TEAM_ID,
    APPLE_BUNDLE_ID: r.APPLE_BUNDLE_ID,
    APPLE_KEY_ID: r.APPLE_KEY_ID,
    APPLE_PRIVATE_KEY_P8_B64: r.APPLE_PRIVATE_KEY_P8_B64,
  };
  if (providerGroup({ name: 'Sign in with Apple', vars: appleVars }, problems, warnings)) {
    const pem = decodePem(r.APPLE_PRIVATE_KEY_P8_B64!);
    if (!pem) problems.push('APPLE_PRIVATE_KEY_P8_B64 is not a base64-encoded PEM private key');
    else apple = { teamId: r.APPLE_TEAM_ID!, bundleId: r.APPLE_BUNDLE_ID!, keyId: r.APPLE_KEY_ID!, privateKeyPem: pem };
  }

  let google: GoogleConfig | null = null;
  if (r.GOOGLE_CLIENT_IDS) {
    const ids = r.GOOGLE_CLIENT_IDS.split(',').map((s) => s.trim()).filter(Boolean);
    if (ids.length === 0) problems.push('GOOGLE_CLIENT_IDS must contain at least one client id');
    else google = { clientIds: ids };
  } else {
    warnings.push('Google Sign-In disabled (not configured)');
  }

  let apns: ApnsConfig | null = null;
  const apnsVars = { APNS_KEY_ID: r.APNS_KEY_ID, APNS_TEAM_ID: r.APNS_TEAM_ID, APNS_P8_B64: r.APNS_P8_B64, APNS_TOPIC: r.APNS_TOPIC };
  if (providerGroup({ name: 'APNs', vars: apnsVars }, problems, warnings)) {
    const pem = decodePem(r.APNS_P8_B64!);
    if (!pem) problems.push('APNS_P8_B64 is not a base64-encoded PEM private key');
    else {
      apns = {
        keyId: r.APNS_KEY_ID!,
        teamId: r.APNS_TEAM_ID!,
        keyPem: pem,
        topic: r.APNS_TOPIC!,
        production: parseBool(r.APNS_PRODUCTION, false),
      };
    }
  }

  let gemini: GeminiConfig | null = null;
  if (r.GEMINI_API_KEY) gemini = { apiKey: r.GEMINI_API_KEY, model: r.GEMINI_MODEL };
  else warnings.push('Gemini AI proxy disabled (not configured)');

  if (problems.length > 0) throw new EnvError(problems);

  return {
    nodeEnv: r.NODE_ENV,
    port: r.PORT,
    logLevel: r.LOG_LEVEL,
    databaseUrl: r.DATABASE_URL,
    jwtSecret: r.JWT_SECRET,
    refreshPepper: r.REFRESH_PEPPER,
    tokenEncKey,
    apple,
    google,
    apns,
    gemini,
    aiDailyLimit: r.AI_DAILY_LIMIT,
    jobsEnabled: parseBool(r.JOBS_ENABLED, true),
    warnings,
  };
}
