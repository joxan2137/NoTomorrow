import { createMiddleware } from 'hono/factory';
import { verifyAccessToken } from '../auth/jwt.js';
import type { AppEnv } from '../types.js';

/** Reads `Authorization: Bearer <jwt>` and sets `c.get('userId')`; 401 for anything else. */
export function requireAuth(secret: string, now: () => Date = () => new Date()) {
  return createMiddleware<AppEnv>(async (c, next) => {
    const header = (c.req.header('authorization') ?? '').trim();
    const match = /^Bearer\s+(\S+)$/i.exec(header);
    const claims = match ? await verifyAccessToken(secret, match[1]!, now()) : null;
    if (!claims) {
      return c.json({ error: 'unauthorized', message: 'A valid access token is required' }, 401, {
        'WWW-Authenticate': 'Bearer realm="notomorrow"',
      });
    }
    c.set('userId', claims.userId);
    await next();
  });
}
