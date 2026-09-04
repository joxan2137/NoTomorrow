import type { RefreshTokenRow, RefreshTokenStore } from '../src/auth/tokens.js';
import type { PushMessage } from '../src/i18n.js';
import type { PushService } from '../src/push.js';

/** In-memory stand-in for the `refresh_tokens` table. */
export class MemoryRefreshTokenStore implements RefreshTokenStore {
  readonly rows = new Map<string, RefreshTokenRow>();

  async findByHash(hash: string): Promise<RefreshTokenRow | null> {
    for (const row of this.rows.values()) if (row.tokenHash === hash) return { ...row };
    return null;
  }

  async insert(row: RefreshTokenRow): Promise<void> {
    if (this.rows.has(row.id)) throw new Error('duplicate id');
    this.rows.set(row.id, { ...row });
  }

  async markRotated(id: string, replacedBy: string, at: Date): Promise<boolean> {
    const row = this.rows.get(id);
    if (!row || row.revokedAt) return false;
    row.revokedAt = at;
    row.replacedBy = replacedBy;
    return true;
  }

  async revokeFamily(family: string, at: Date): Promise<void> {
    for (const row of this.rows.values()) if (row.family === family && !row.revokedAt) row.revokedAt = at;
  }
}

export interface SentPush {
  userId: string;
  msg: PushMessage;
  extra: Record<string, unknown>;
}

export function fakePushService(): PushService & { sent: SentPush[] } {
  const sent: SentPush[] = [];
  return {
    enabled: true,
    sent,
    async send(userId, msg, extra = {}) {
      sent.push({ userId, msg, extra });
    },
    async shutdown() {},
  };
}

/** A `fetch` that fails loudly: tests must never reach the network. */
export const noNetworkFetch: typeof fetch = async (input) => {
  throw new Error(`unexpected network call to ${typeof input === 'string' ? input : input instanceof URL ? input.href : input.url}`);
};
