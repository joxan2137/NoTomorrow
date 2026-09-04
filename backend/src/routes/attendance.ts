import { Hono, type Context } from 'hono';
import { z } from 'zod';
import { ATTENDANCE_STATUSES, attendanceDTO, displayNameOf, scheduleDTO, scheduledMinuteFor, type AttendanceRow, type ScheduleRow } from '../dto.js';
import { HttpError, parseJson } from '../http.js';
import { formatMinute } from '../i18n.js';
import { activePairing } from '../pairing.js';
import { isoWeekdayOf, localParts, relativeDayLabel, toDayKey } from '../time.js';
import type { AppDeps, AppEnv } from '../types.js';
import { getUser } from '../users.js';

const attendanceSchema = z.object({
  /** Only used by `PUT /attendance` (the iOS client); the spec route carries the day in the path. */
  day: z.string().min(8).max(40).optional(),
  status: z.enum(ATTENDANCE_STATUSES),
  reason: z.string().trim().max(200).nullish(),
  note: z.string().trim().max(500).nullish(),
  makeUpDay: z.string().min(8).max(40).nullish(),
  scheduledMinute: z.number().int().min(0).max(1439).nullish(),
});

export function attendanceRoutes(deps: AppDeps): Hono<AppEnv> {
  const r = new Hono<AppEnv>();
  const { sql, push, events } = deps;

  const upsert = async (c: Context<AppEnv>, dayParam?: string) => {
    const userId = c.get('userId');
    const body = await parseJson(c, attendanceSchema);
    const me = await getUser(sql, userId);
    if (!me) throw new HttpError(404, 'user_not_found', 'Account no longer exists');

    const dayInput = dayParam ?? body.day;
    if (!dayInput) throw new HttpError(400, 'day_required', 'Provide the day as YYYY-MM-DD');
    const day = toDayKey(dayInput, me.tz);
    if (!day) throw new HttpError(400, 'invalid_day', 'Day must be YYYY-MM-DD or an ISO-8601 date-time');
    const makeUpDay = body.makeUpDay ? toDayKey(body.makeUpDay, me.tz) : null;
    if (body.makeUpDay && !makeUpDay) throw new HttpError(400, 'invalid_make_up_day', 'makeUpDay must be YYYY-MM-DD or an ISO-8601 date-time');

    const scheduleRows = await sql<ScheduleRow[]>`
      select user_id, weekdays, default_minute, overrides, remind_hour_before, ask_if_skipped_at_21, updated_at
      from schedules where user_id = ${userId}`;
    const schedule = scheduleDTO(scheduleRows[0] ?? null);
    const scheduledMinute = body.scheduledMinute ?? scheduledMinuteFor(schedule, isoWeekdayOf(day));
    const reason = body.reason || null;
    const note = body.note || null;

    const rows = await sql.begin(async (tx) => {
      const upserted = await tx<AttendanceRow[]>`
        insert into attendance (user_id, day, status, scheduled_minute, reason, note, make_up_day, updated_at)
        values (${userId}, ${day}, ${body.status}, ${scheduledMinute}, ${reason}, ${note}, ${makeUpDay}, now())
        on conflict (user_id, day) do update set
          status = excluded.status,
          scheduled_minute = excluded.scheduled_minute,
          reason = excluded.reason,
          note = excluded.note,
          make_up_day = excluded.make_up_day,
          updated_at = now()
        returning user_id, day::text as day, status, scheduled_minute, reason, note, make_up_day::text as make_up_day, updated_at`;
      if (makeUpDay && makeUpDay !== day) {
        // The make-up day becomes a planned session unless the user already logged something real there.
        await tx`
          insert into attendance (user_id, day, status, scheduled_minute)
          values (${userId}, ${makeUpDay}, 'planned', ${scheduledMinuteFor(schedule, isoWeekdayOf(makeUpDay))})
          on conflict (user_id, day) do update set status = 'planned', updated_at = now()
          where attendance.status in ('cancelled', 'missed')`;
      }
      return upserted;
    });
    const row = rows[0]!;

    const pairing = await activePairing(sql, userId);
    if (pairing) {
      const partner = await getUser(sql, pairing.partnerId);
      if (partner) {
        const name = displayNameOf(me);
        const today = localParts(deps.now(), partner.tz).day;
        const label = relativeDayLabel(day, today);
        const extra = { day, status: body.status, partnerId: userId };
        if (body.status === 'confirmed') {
          await push.send(partner.id, { kind: 'attendanceConfirmed', name, time: formatMinute(scheduledMinute), day: label }, extra);
        } else if (body.status === 'cancelled') {
          await push.send(
            partner.id,
            { kind: 'attendanceCancelled', name, reason, day: label, makeUpWeekday: makeUpDay ? isoWeekdayOf(makeUpDay) : null },
            extra,
          );
        }
        // `attended` (and planned/missed) are silent.
        events.publish(partner.id, { type: 'attendance', ...attendanceDTO(row, 'partner', partner.tz) });
      }
    }
    return c.json(attendanceDTO(row, 'me', me.tz));
  };

  r.put('/attendance/:day', (c) => upsert(c, c.req.param('day')));
  // The iOS client sends the day in the body.
  r.put('/attendance', (c) => upsert(c));

  return r;
}
