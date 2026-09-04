import { Hono } from 'hono';
import { z } from 'zod';
import { HttpError, ok, parseJson } from '../http.js';
import type { AppDeps, AppEnv } from '../types.js';

const tokenSchema = z.object({
  /** APNs device token as hex (the iOS client sends `Data` rendered as lowercase hex). */
  token: z.string().regex(/^[0-9a-fA-F]{32,512}$/, 'token must be the APNs device token as hex'),
  /** Defaults to the environment this server's APNs provider targets. */
  environment: z.enum(['sandbox', 'production']).optional(),
});

export function pushRoutes(deps: AppDeps): Hono<AppEnv> {
  const r = new Hono<AppEnv>();

  r.post('/push/token', async (c) => {
    if (!deps.env.apns) throw new HttpError(503, 'push_unavailable', 'Push notifications are not configured on this server');
    const userId = c.get('userId');
    const body = await parseJson(c, tokenSchema);
    const environment = body.environment ?? (deps.env.apns.production ? 'production' : 'sandbox');
    await deps.sql`
      insert into push_tokens (token, user_id, environment, updated_at)
      values (${body.token.toLowerCase()}, ${userId}, ${environment}, now())
      on conflict (token) do update set user_id = excluded.user_id, environment = excluded.environment, updated_at = now()`;
    return ok(c);
  });

  r.delete('/push/token', async (c) => {
    const body = await parseJson(c, tokenSchema.pick({ token: true }));
    await deps.sql`delete from push_tokens where token = ${body.token.toLowerCase()} and user_id = ${c.get('userId')}`;
    return ok(c);
  });

  return r;
}
