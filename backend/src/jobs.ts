import { PgBoss } from 'pg-boss';
import type { Sql } from './db.js';
import { scheduleDTO, scheduledMinuteFor, type AttendanceStatus, type ScheduleDTO } from './dto.js';
import { formatMinute } from './i18n.js';
import type { Logger } from './logger.js';
import type { PushService } from './push.js';
import { isValidTimeZone, localParts, type LocalParts } from './time.js';

/**
 * One pg-boss cron every 5 minutes scans for due work. Each notification is recorded in
 * `notifications_sent (user_id, day, kind)` before sending, so overlapping or repeated scans
 * never double-send.
 */

export const SCAN_QUEUE = 'scan-due-notifications';
export const SCAN_CRON = '*/5 * * * *';
export const REMINDER_LEAD_MINUTES = 60;
export const SKIP_CHECK_MINUTE = 21 * 60;
/** How long after 21:00 the skip check may still go out (covers a machine that was asleep). */
const SKIP_CHECK_WINDOW_MINUTES = 60;

export type DueKind = 'reminder' | 'skipCheck';

export interface DueInput {
  schedule: ScheduleDTO;
  parts: LocalParts;
  /** Today's attendance row status, or null when there is none. */
  attendanceStatus: AttendanceStatus | null;
}

/** Pure: which notifications are due at this local time for one user (before dedupe). */
export function dueKinds({ schedule, parts, attendanceStatus }: DueInput): DueKind[] {
  if (!schedule.weekdays.includes(parts.isoWeekday)) return [];
  const out: DueKind[] = [];
  const sessionMinute = scheduledMinuteFor(schedule, parts.isoWeekday);
  const reminderAt = sessionMinute - REMINDER_LEAD_MINUTES;
  const settled = attendanceStatus === 'attended' || attendanceStatus === 'cancelled';
  if (schedule.remindHourBefore && !settled && reminderAt >= 0 && parts.minuteOfDay >= reminderAt && parts.minuteOfDay < sessionMinute) {
    out.push('reminder');
  }
  const stillOpen = attendanceStatus === null || attendanceStatus === 'planned' || attendanceStatus === 'confirmed';
  if (
    schedule.askIfSkippedAt21 &&
    stillOpen &&
    parts.minuteOfDay >= SKIP_CHECK_MINUTE &&
    parts.minuteOfDay < SKIP_CHECK_MINUTE + SKIP_CHECK_WINDOW_MINUTES
  ) {
    out.push('skipCheck');
  }
  return out;
}

interface JobUserRow {
  id: string;
  tz: string;
  weekdays: number[];
  defaultMinute: number;
  overrides: Record<string, number>;
  remindHourBefore: boolean;
  askIfSkippedAt21: boolean;
}

export interface JobDeps {
  sql: Sql;
  log: Logger;
  push: PushService;
}

export async function runScan(deps: JobDeps, now: Date = new Date()): Promise<{ reminders: number; skipChecks: number }> {
  const { sql, log, push } = deps;
  const users = await sql<JobUserRow[]>`
    select u.id, u.tz, s.weekdays, s.default_minute, s.overrides, s.remind_hour_before, s.ask_if_skipped_at_21
    from schedules s join users u on u.id = s.user_id
    where s.remind_hour_before or s.ask_if_skipped_at_21`;
  let reminders = 0;
  let skipChecks = 0;
  for (const u of users) {
    try {
      const tz = isValidTimeZone(u.tz) ? u.tz : 'UTC';
      const parts = localParts(now, tz);
      const schedule = scheduleDTO({ ...u, userId: u.id, updatedAt: now });
      // Cheap pre-check (null status = superset of what can be due) before touching attendance.
      if (dueKinds({ schedule, parts, attendanceStatus: null }).length === 0) continue;
      const rows = await sql<{ status: AttendanceStatus }[]>`
        select status from attendance where user_id = ${u.id} and day = ${parts.day}`;
      const kinds = dueKinds({ schedule, parts, attendanceStatus: rows[0]?.status ?? null });
      for (const kind of kinds) {
        const inserted = await sql`
          insert into notifications_sent (user_id, day, kind) values (${u.id}, ${parts.day}, ${kind})
          on conflict do nothing returning kind`;
        if (inserted.length === 0) continue;
        if (kind === 'reminder') {
          await push.send(u.id, { kind: 'reminder', time: formatMinute(scheduledMinuteFor(schedule, parts.isoWeekday)) }, { day: parts.day });
          reminders += 1;
        } else {
          await push.send(u.id, { kind: 'skipCheck' }, { day: parts.day });
          skipChecks += 1;
        }
      }
    } catch (err) {
      log.error({ err, userId: u.id }, 'notification scan failed for user');
    }
  }
  return { reminders, skipChecks };
}

export interface Jobs {
  start(): Promise<void>;
  stop(): Promise<void>;
}

export function createJobs(deps: JobDeps & { databaseUrl: string }): Jobs {
  const boss = new PgBoss({ connectionString: deps.databaseUrl, schema: 'pgboss' });
  boss.on('error', (err) => deps.log.error({ err }, 'pg-boss error'));
  let started = false;
  return {
    async start() {
      await boss.start();
      if (!(await boss.getQueue(SCAN_QUEUE))) await boss.createQueue(SCAN_QUEUE);
      await boss.schedule(SCAN_QUEUE, SCAN_CRON, {}, { tz: 'UTC' });
      await boss.work(SCAN_QUEUE, async () => {
        const result = await runScan(deps, new Date());
        if (result.reminders > 0 || result.skipChecks > 0) deps.log.info(result, 'notification scan sent pushes');
      });
      started = true;
      deps.log.info({ queue: SCAN_QUEUE, cron: SCAN_CRON }, 'jobs started');
    },
    async stop() {
      if (!started) return;
      started = false;
      await boss.stop({ graceful: true, timeout: 5_000, close: true });
    },
  };
}
