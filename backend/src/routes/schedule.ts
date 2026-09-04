import { Hono } from 'hono';
import { z } from 'zod';
import { scheduleDTO, type ScheduleRow } from '../dto.js';
import { parseJson } from '../http.js';
import type { AppDeps, AppEnv } from '../types.js';

const minute = z.number().int().min(0).max(1439);

const scheduleSchema = z.object({
  weekdays: z.array(z.number().int().min(1).max(7)).max(7),
  defaultMinuteOfDay: minute,
  overrides: z.record(z.string().regex(/^[1-7]$/, 'override keys are ISO weekdays 1–7'), minute).default({}),
  remindHourBefore: z.boolean().optional(),
  askIfSkippedAt21: z.boolean().optional(),
});

export function scheduleRoutes(deps: AppDeps): Hono<AppEnv> {
  const r = new Hono<AppEnv>();

  /** Upsert of the caller's gym schedule (weekdays, default minute, per-weekday overrides, job toggles). */
  r.put('/schedule', async (c) => {
    const userId = c.get('userId');
    const body = await parseJson(c, scheduleSchema);
    const weekdays = [...new Set(body.weekdays)].sort((a, b) => a - b);
    const remind = body.remindHourBefore ?? null;
    const ask = body.askIfSkippedAt21 ?? null;
    const rows = await deps.sql<ScheduleRow[]>`
      insert into schedules (user_id, weekdays, default_minute, overrides, remind_hour_before, ask_if_skipped_at_21, updated_at)
      values (${userId}, ${weekdays}, ${body.defaultMinuteOfDay}, ${deps.sql.json(body.overrides)},
              ${remind ?? true}, ${ask ?? true}, now())
      on conflict (user_id) do update set
        weekdays = excluded.weekdays,
        default_minute = excluded.default_minute,
        overrides = excluded.overrides,
        remind_hour_before = coalesce(${remind}::boolean, schedules.remind_hour_before),
        ask_if_skipped_at_21 = coalesce(${ask}::boolean, schedules.ask_if_skipped_at_21),
        updated_at = now()
      returning user_id, weekdays, default_minute, overrides, remind_hour_before, ask_if_skipped_at_21, updated_at`;
    return c.json(scheduleDTO(rows[0] ?? null));
  });

  r.get('/schedule', async (c) => {
    const rows = await deps.sql<ScheduleRow[]>`
      select user_id, weekdays, default_minute, overrides, remind_hour_before, ask_if_skipped_at_21, updated_at
      from schedules where user_id = ${c.get('userId')}`;
    return c.json(scheduleDTO(rows[0] ?? null));
  });

  return r;
}
