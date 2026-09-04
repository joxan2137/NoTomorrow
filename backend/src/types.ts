import type { JWTVerifyGetKey } from 'jose';
import type { LoginThrottle } from './auth/password.js';
import type { Sql } from './db.js';
import type { Env } from './env.js';
import type { EventBus } from './events.js';
import type { Logger } from './logger.js';
import type { PushService } from './push.js';

/** Hono variables set by `requireAuth`. */
export type AppEnv = { Variables: { userId: string } };

/** Everything a route needs; built once in `index.ts`, swapped for fakes in tests. */
export interface AppDeps {
  env: Env;
  sql: Sql;
  log: Logger;
  push: PushService;
  events: EventBus;
  throttle: LoginThrottle;
  /** Outbound HTTP (Apple token exchange, Gemini). Injected so tests never touch the network. */
  fetchImpl: typeof fetch;
  jwks: { apple: JWTVerifyGetKey; google: JWTVerifyGetKey };
  now: () => Date;
}
