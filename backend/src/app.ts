import { Hono } from 'hono';
import { bodyLimit } from 'hono/body-limit';
import { HTTPException } from 'hono/http-exception';
import { secureHeaders } from 'hono/secure-headers';
import { HttpError } from './http.js';
import { requireAuth } from './middleware/auth.js';
import { accountRoutes } from './routes/account.js';
import { aiRoutes } from './routes/ai.js';
import { appleNotificationRoutes } from './routes/apple-notifications.js';
import { attendanceRoutes } from './routes/attendance.js';
import { authRoutes } from './routes/auth.js';
import { eventRoutes } from './routes/events.js';
import { headsUpRoutes } from './routes/headsups.js';
import { pairRoutes } from './routes/pair.js';
import { partnerRoutes } from './routes/partner.js';
import { pushRoutes } from './routes/push.js';
import { scheduleRoutes } from './routes/schedule.js';
import type { AppDeps, AppEnv } from './types.js';

/** Everything else requires `Authorization: Bearer <access token>`. */
export const PUBLIC_PATHS = new Set([
  '/healthz',
  '/auth/register',
  '/auth/login',
  '/auth/password',
  '/auth/apple',
  '/auth/google',
  '/auth/refresh',
  '/auth/logout',
  '/apple/notifications',
]);

const JSON_BODY_LIMIT = 64 * 1024;
const MULTIPART_BODY_LIMIT = 6 * 1024 * 1024;

export interface CreateAppOptions {
  /** Shorter SSE heartbeat for tests. */
  sseHeartbeatMs?: number;
}

export function createApp(deps: AppDeps, opts: CreateAppOptions = {}): Hono<AppEnv> {
  const app = new Hono<AppEnv>();
  const { log } = deps;

  app.use('*', async (c, next) => {
    const started = performance.now();
    await next();
    const ms = Math.round(performance.now() - started);
    // Path only — never the query string, headers or body, which may carry tokens.
    const entry = { method: c.req.method, path: c.req.path, status: c.res.status, ms };
    if (c.req.path === '/healthz') log.debug(entry, 'request');
    else log.info(entry, 'request');
  });
  app.use('*', secureHeaders());

  const jsonLimit = bodyLimit({ maxSize: JSON_BODY_LIMIT, onError: (c) => c.json({ error: 'payload_too_large' }, 413) });
  const multipartLimit = bodyLimit({ maxSize: MULTIPART_BODY_LIMIT, onError: (c) => c.json({ error: 'payload_too_large' }, 413) });
  app.use('*', (c, next) => (c.req.path === '/ai/estimate' ? multipartLimit : jsonLimit)(c, next));

  const auth = requireAuth(deps.env.jwtSecret, deps.now);
  app.use('*', (c, next) => (PUBLIC_PATHS.has(c.req.path) ? next() : auth(c, next)));

  app.get('/healthz', async (c) => {
    try {
      await deps.sql`select 1`;
      return c.json({ ok: true });
    } catch (err) {
      log.error({ err }, 'healthz database check failed');
      return c.json({ ok: false, error: 'database_unavailable' }, 503);
    }
  });

  app.route('/', authRoutes(deps));
  app.route('/', accountRoutes(deps));
  app.route('/', pairRoutes(deps));
  app.route('/', partnerRoutes(deps));
  app.route('/', scheduleRoutes(deps));
  app.route('/', attendanceRoutes(deps));
  app.route('/', headsUpRoutes(deps));
  app.route('/', eventRoutes(deps, opts.sseHeartbeatMs));
  app.route('/', pushRoutes(deps));
  app.route('/', aiRoutes(deps));
  app.route('/', appleNotificationRoutes(deps));

  app.notFound((c) => c.json({ error: 'not_found', message: `No route for ${c.req.method} ${c.req.path}` }, 404));

  app.onError((err, c) => {
    if (err instanceof HttpError) return c.json(err.body(), err.status, err.headers);
    if (err instanceof HTTPException) {
      return c.json({ error: 'http_error', message: err.message || `HTTP ${err.status}` }, err.status);
    }
    log.error({ err, method: c.req.method, path: c.req.path }, 'unhandled error');
    return c.json({ error: 'internal', message: 'Internal server error' }, 500);
  });

  return app;
}
