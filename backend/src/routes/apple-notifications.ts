import { Hono } from 'hono';
import { z } from 'zod';
import { AppleAuthError, verifyAppleServerNotification } from '../auth/apple.js';
import { HttpError, ok, parseJson } from '../http.js';
import type { AppDeps, AppEnv } from '../types.js';
import { deleteUserAccount } from './account.js';

const notificationSchema = z.object({ payload: z.string().min(1).max(16_384) });

/** Event types after which the account must go (Apple's name is `account-delete`; the spec says `account-deleted`). */
const DELETE_EVENTS = new Set(['consent-revoked', 'account-delete', 'account-deleted']);

/** Apple server-to-server notifications (configured in the Apple developer portal for the service). */
export function appleNotificationRoutes(deps: AppDeps): Hono<AppEnv> {
  const r = new Hono<AppEnv>();

  r.post('/apple/notifications', async (c) => {
    const config = deps.env.apple;
    if (!config) throw new HttpError(503, 'apple_unavailable', 'Sign in with Apple is not configured on this server');
    const body = await parseJson(c, notificationSchema);

    let event;
    try {
      event = await verifyAppleServerNotification(body.payload, config.bundleId, deps.jwks.apple, deps.now());
    } catch (err) {
      if (err instanceof AppleAuthError) throw new HttpError(400, 'invalid_notification', 'Notification signature or payload rejected');
      throw err;
    }

    if (DELETE_EVENTS.has(event.type)) {
      const rows = await deps.sql<{ userId: string }[]>`
        select user_id from identities where provider = 'apple' and subject = ${event.sub}`;
      const userId = rows[0]?.userId;
      if (userId) await deleteUserAccount(deps, userId);
      deps.log.info({ type: event.type, found: Boolean(userId) }, 'apple server notification processed');
    } else {
      deps.log.info({ type: event.type }, 'apple server notification ignored');
    }
    return ok(c);
  });

  return r;
}
