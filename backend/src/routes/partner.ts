import { Hono } from 'hono';
import {
  attendanceDTO,
  displayNameOf,
  headsUpDTO,
  partnerDTO,
  scheduleDTO,
  type AttendanceRow,
  type HeadsUpRow,
  type ScheduleRow,
} from '../dto.js';
import { HttpError } from '../http.js';
import { activePairing } from '../pairing.js';
import { addDays, localParts, mondayOf } from '../time.js';
import type { AppDeps, AppEnv } from '../types.js';
import { getUser } from '../users.js';

async function loadPartnerState(deps: AppDeps, userId: string) {
  const { sql } = deps;
  const pairing = await activePairing(sql, userId);
  if (!pairing) throw new HttpError(404, 'not_paired', 'You are not paired with anyone');
  const [me, partnerUser] = await Promise.all([getUser(sql, userId), getUser(sql, pairing.partnerId)]);
  if (!me || !partnerUser) throw new HttpError(404, 'not_paired', 'Your gym bro no longer exists');

  const tz = me.tz;
  const today = localParts(deps.now(), tz).day;
  // Monday of last week through Sunday of next week.
  const from = mondayOf(addDays(today, -7));
  const to = addDays(mondayOf(addDays(today, 7)), 6);

  const [scheduleRows, partnerAttendance, myAttendance, headsUps] = await Promise.all([
    sql<ScheduleRow[]>`
      select user_id, weekdays, default_minute, overrides, remind_hour_before, ask_if_skipped_at_21, updated_at
      from schedules where user_id = ${pairing.partnerId}`,
    sql<AttendanceRow[]>`
      select user_id, day::text as day, status, scheduled_minute, reason, note, make_up_day::text as make_up_day, updated_at
      from attendance where user_id = ${pairing.partnerId} and day between ${from} and ${to} order by day`,
    sql<AttendanceRow[]>`
      select user_id, day::text as day, status, scheduled_minute, reason, note, make_up_day::text as make_up_day, updated_at
      from attendance where user_id = ${userId} and day between ${from} and ${to} order by day`,
    sql<HeadsUpRow[]>`
      select id, from_user, to_user, kind, text, session_day::text as session_day, sent_at, read_at
      from heads_ups
      where (from_user = ${userId} and to_user = ${pairing.partnerId})
         or (from_user = ${pairing.partnerId} and to_user = ${userId})
      order by sent_at desc limit 20`,
  ]);

  const partner = partnerDTO(partnerUser, pairing.createdAt);
  const schedule = scheduleDTO(scheduleRows[0] ?? null);
  return {
    partner,
    partnerName: displayNameOf(partnerUser),
    schedule,
    partnerSchedule: schedule,
    attendance: partnerAttendance.map((row) => attendanceDTO(row, 'partner', tz)),
    myAttendance: myAttendance.map((row) => attendanceDTO(row, 'me', tz)),
    headsUps: headsUps.map((row) => headsUpDTO(row, userId, tz)),
  };
}

export function partnerRoutes(deps: AppDeps): Hono<AppEnv> {
  const r = new Hono<AppEnv>();

  // Spec shape: partner rows in `attendance`, caller's rows in `myAttendance`.
  r.get('/partner', async (c) => c.json(await loadPartnerState(deps, c.get('userId'))));

  // iOS `PartnerState`: one merged `attendance` list, each row tagged with `participant`.
  r.get('/partner/state', async (c) => {
    const state = await loadPartnerState(deps, c.get('userId'));
    return c.json({
      partner: state.partner,
      partnerName: state.partnerName,
      partnerSchedule: state.partnerSchedule,
      attendance: [...state.attendance, ...state.myAttendance],
      headsUps: state.headsUps,
    });
  });

  return r;
}
