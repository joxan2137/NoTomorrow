/**
 * In-process pub/sub keyed by user id, feeding the `GET /events` SSE stream.
 * One Fly machine (`min_machines_running = 1`, no horizontal scaling) makes this sufficient;
 * APNs remains the source of truth, SSE is only a live refresh while the Bro screen is open.
 */

export type ServerEventType = 'attendance' | 'headsUp' | 'pairing';

export interface ServerEvent {
  type: ServerEventType;
  [key: string]: unknown;
}

type Listener = (event: ServerEvent) => void;

export class EventBus {
  private readonly subscribers = new Map<string, Set<Listener>>();

  /** Returns an unsubscribe function. */
  subscribe(userId: string, listener: Listener): () => void {
    let set = this.subscribers.get(userId);
    if (!set) {
      set = new Set();
      this.subscribers.set(userId, set);
    }
    set.add(listener);
    return () => {
      const current = this.subscribers.get(userId);
      if (!current) return;
      current.delete(listener);
      if (current.size === 0) this.subscribers.delete(userId);
    };
  }

  publish(userId: string, event: ServerEvent): void {
    const set = this.subscribers.get(userId);
    if (!set) return;
    for (const listener of set) {
      try {
        listener(event);
      } catch {
        // A broken subscriber must never affect the publisher (the HTTP request that caused the event).
      }
    }
  }

  subscriberCount(userId: string): number {
    return this.subscribers.get(userId)?.size ?? 0;
  }
}
