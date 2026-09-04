import { describe, expect, it } from 'vitest';
import type { ScheduleDTO } from '../src/dto.js';
import { dueKinds } from '../src/jobs.js';
import { localParts } from '../src/time.js';

const schedule: ScheduleDTO = {
  weekdays: [1, 3, 5],
  defaultMinuteOfDay: 18 * 60,
  overrides: { '5': 10 * 60 },
  remindHourBefore: true,
  askIfSkippedAt21: true,
};

// 2026-09-02 is a Wednesday (gym day), 2026-09-03 a Thursday (rest day), 2026-09-04 a Friday (10:00 override).
const at = (iso: string) => localParts(new Date(iso), 'Europe/Warsaw');

describe('dueKinds', () => {
  it('sends the reminder in the hour before the session, once the window opens', () => {
    expect(dueKinds({ schedule, parts: at('2026-09-02T14:59:00Z'), attendanceStatus: null })).toEqual([]); // 16:59 local
    expect(dueKinds({ schedule, parts: at('2026-09-02T15:00:00Z'), attendanceStatus: null })).toEqual(['reminder']); // 17:00
    expect(dueKinds({ schedule, parts: at('2026-09-02T15:55:00Z'), attendanceStatus: 'planned' })).toEqual(['reminder']);
    expect(dueKinds({ schedule, parts: at('2026-09-02T16:00:00Z'), attendanceStatus: null })).toEqual([]); // 18:00, too late
  });

  it('honours per-weekday overrides', () => {
    expect(dueKinds({ schedule, parts: at('2026-09-04T07:05:00Z'), attendanceStatus: null })).toEqual(['reminder']); // Fri 09:05 for 10:00
    expect(dueKinds({ schedule, parts: at('2026-09-04T15:05:00Z'), attendanceStatus: null })).toEqual([]);
  });

  it('skips the reminder when the session is already attended or cancelled, or the toggle is off', () => {
    expect(dueKinds({ schedule, parts: at('2026-09-02T15:10:00Z'), attendanceStatus: 'attended' })).toEqual([]);
    expect(dueKinds({ schedule, parts: at('2026-09-02T15:10:00Z'), attendanceStatus: 'cancelled' })).toEqual([]);
    expect(dueKinds({ schedule: { ...schedule, remindHourBefore: false }, parts: at('2026-09-02T15:10:00Z'), attendanceStatus: null })).toEqual([]);
  });

  it('asks "did you skip?" at 21:00 local only while the day is still planned/confirmed', () => {
    expect(dueKinds({ schedule, parts: at('2026-09-02T18:59:00Z'), attendanceStatus: 'planned' })).toEqual([]); // 20:59
    expect(dueKinds({ schedule, parts: at('2026-09-02T19:00:00Z'), attendanceStatus: 'planned' })).toEqual(['skipCheck']); // 21:00
    expect(dueKinds({ schedule, parts: at('2026-09-02T19:30:00Z'), attendanceStatus: 'confirmed' })).toEqual(['skipCheck']);
    expect(dueKinds({ schedule, parts: at('2026-09-02T19:30:00Z'), attendanceStatus: null })).toEqual(['skipCheck']);
    expect(dueKinds({ schedule, parts: at('2026-09-02T19:30:00Z'), attendanceStatus: 'attended' })).toEqual([]);
    expect(dueKinds({ schedule, parts: at('2026-09-02T19:30:00Z'), attendanceStatus: 'missed' })).toEqual([]);
    expect(dueKinds({ schedule, parts: at('2026-09-02T20:00:00Z'), attendanceStatus: 'planned' })).toEqual([]); // 22:00, window closed
    expect(dueKinds({ schedule: { ...schedule, askIfSkippedAt21: false }, parts: at('2026-09-02T19:30:00Z'), attendanceStatus: null })).toEqual([]);
  });

  it('does nothing on rest days', () => {
    expect(dueKinds({ schedule, parts: at('2026-09-03T15:10:00Z'), attendanceStatus: null })).toEqual([]);
    expect(dueKinds({ schedule, parts: at('2026-09-03T19:10:00Z'), attendanceStatus: null })).toEqual([]);
  });
});
