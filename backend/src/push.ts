import apn from '@parse/node-apn';
import type { Notification, ResponseFailure, ResponseSent, Responses } from '@parse/node-apn';
import type { Sql } from './db.js';
import type { ApnsConfig } from './env.js';
import { normalizeLocale, pushText, type Locale, type PushMessage } from './i18n.js';
import type { Logger } from './logger.js';

export interface ApnsPayload {
  aps: { alert: { title: string; body: string }; sound: 'default'; 'thread-id': 'bro' };
  type: PushMessage['kind'];
  [key: string]: unknown;
}

/** Pure: the APNs JSON for a message, localized for the recipient. */
export function buildApnsPayload(locale: Locale, msg: PushMessage, extra: Record<string, unknown> = {}): ApnsPayload {
  const { title, body } = pushText(locale, msg);
  return {
    ...extra,
    aps: { alert: { title, body }, sound: 'default', 'thread-id': 'bro' },
    type: msg.kind,
  };
}

export interface PushService {
  readonly enabled: boolean;
  /** Never throws: delivery problems are logged, not surfaced to the request that triggered them. */
  send(userId: string, msg: PushMessage, extra?: Record<string, unknown>): Promise<void>;
  shutdown(): Promise<void>;
}

/** The slice of `apn.Provider` we use, so tests can inject a fake. */
export interface ApnsSender {
  send(notification: Notification, recipients: string[]): Promise<Responses<ResponseSent, ResponseFailure>>;
  shutdown(): Promise<void>;
}

const DEAD_TOKEN_REASONS = new Set(['BadDeviceToken', 'Unregistered', 'DeviceTokenNotForTopic']);

export function createPushService(opts: { sql: Sql; log: Logger; config: ApnsConfig | null; sender?: ApnsSender }): PushService {
  const { sql, log, config } = opts;
  let sender: ApnsSender | null = opts.sender ?? null;
  if (!sender && config) {
    sender = new apn.Provider({
      token: { key: config.keyPem, keyId: config.keyId, teamId: config.teamId },
      production: config.production,
    });
  }
  const environment = config?.production ? 'production' : 'sandbox';

  return {
    enabled: sender !== null,

    async send(userId, msg, extra = {}) {
      try {
        if (!sender || !config) {
          log.debug({ userId, kind: msg.kind }, 'push skipped: APNs not configured');
          return;
        }
        const users = await sql<{ locale: string }[]>`select locale from users where id = ${userId}`;
        const user = users[0];
        if (!user) return;
        const tokens = (
          await sql<{ token: string }[]>`select token from push_tokens where user_id = ${userId} and environment = ${environment}`
        ).map((r) => r.token);
        if (tokens.length === 0) return;

        const notification = new apn.Notification();
        notification.topic = config.topic;
        notification.pushType = 'alert';
        notification.priority = 10;
        notification.expiry = Math.floor(Date.now() / 1000) + 3600;
        notification.rawPayload = buildApnsPayload(normalizeLocale(user.locale), msg, extra);

        const result = await sender.send(notification, tokens);
        const dead = result.failed
          .filter((f) => f.status === 410 || DEAD_TOKEN_REASONS.has(f.response?.reason ?? ''))
          .map((f) => f.device);
        if (dead.length > 0) await sql`delete from push_tokens where token in ${sql(dead)}`;
        const otherFailures = result.failed
          .filter((f) => !dead.includes(f.device))
          .map((f) => ({ status: f.status, reason: f.response?.reason, error: f.error?.message }));
        if (otherFailures.length > 0) log.warn({ userId, kind: msg.kind, failures: otherFailures }, 'apns delivery failures');
        log.debug({ userId, kind: msg.kind, sent: result.sent.length, removed: dead.length }, 'push delivered');
      } catch (err) {
        log.error({ err, userId, kind: msg.kind }, 'push failed');
      }
    },

    async shutdown() {
      if (sender) await sender.shutdown();
    },
  };
}
