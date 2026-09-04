import { Hono } from 'hono';
import { streamSSE } from 'hono/streaming';
import type { AppDeps, AppEnv } from '../types.js';

export const SSE_HEARTBEAT_MS = 25_000;

/**
 * `GET /events`: server-sent events for changes made by the partner (attendance, headsUp, pairing).
 * A comment heartbeat every 25 s keeps Fly's 600 s idle timeout and mobile radios happy.
 */
export function eventRoutes(deps: AppDeps, heartbeatMs: number = SSE_HEARTBEAT_MS): Hono<AppEnv> {
  const r = new Hono<AppEnv>();

  r.get('/events', (c) => {
    const userId = c.get('userId');
    return streamSSE(
      c,
      async (stream) => {
        let alive = true;
        const unsubscribe = deps.events.subscribe(userId, (event) => {
          if (!alive) return;
          stream.writeSSE({ event: event.type, data: JSON.stringify(event) }).catch(() => {
            alive = false;
          });
        });
        const stop = () => {
          alive = false;
          unsubscribe();
        };
        stream.onAbort(stop);
        try {
          await stream.writeSSE({ event: 'ready', data: JSON.stringify({ type: 'ready' }) });
          while (alive) {
            await stream.sleep(heartbeatMs);
            if (!alive) break;
            await stream.write(': heartbeat\n\n');
          }
        } catch {
          // Client went away mid-write; nothing to do.
        } finally {
          stop();
        }
      },
      async (err) => {
        deps.log.debug({ err, userId }, 'sse stream ended with error');
      },
    );
  });

  return r;
}
