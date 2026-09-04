import { describe, expect, it } from 'vitest';
import { dayInstant } from '../src/dto.js';
import { addDays, isValidDayKey, isValidTimeZone, isoNoMillis, isoWeekdayOf, localParts, mondayOf, relativeDayLabel, toDayKey, zonedMidnight } from '../src/time.js';

describe('localParts', () => {
  it('converts an instant into the zone-local day, ISO weekday and minute of day', () => {
    // 2026-07-01 is a Wednesday; Warsaw is UTC+2 in summer.
    expect(localParts(new Date('2026-07-01T10:05:00Z'), 'Europe/Warsaw')).toEqual({ day: '2026-07-01', isoWeekday: 3, minuteOfDay: 12 * 60 + 5 });
    // Just before local midnight rolls the day in Warsaw but not in UTC.
    expect(localParts(new Date('2026-07-01T22:30:00Z'), 'Europe/Warsaw').day).toBe('2026-07-02');
    expect(localParts(new Date('2026-07-01T22:30:00Z'), 'UTC').day).toBe('2026-07-01');
    // Midnight local must be minute 0, not 24 * 60.
    expect(localParts(new Date('2026-01-10T23:00:00Z'), 'Europe/Warsaw').minuteOfDay).toBe(0);
  });

  it('knows ISO weekdays with Sunday = 7', () => {
    expect(isoWeekdayOf('2026-09-06')).toBe(7);
    expect(isoWeekdayOf('2026-09-07')).toBe(1);
  });
});

describe('zonedMidnight / dayInstant', () => {
  it('finds local midnight across DST', () => {
    expect(zonedMidnight('2026-07-01', 'Europe/Warsaw').toISOString()).toBe('2026-06-30T22:00:00.000Z');
    expect(zonedMidnight('2026-01-15', 'Europe/Warsaw').toISOString()).toBe('2026-01-14T23:00:00.000Z');
    expect(zonedMidnight('2026-03-29', 'Europe/Warsaw').toISOString()).toBe('2026-03-28T23:00:00.000Z');
    expect(zonedMidnight('2026-03-30', 'Europe/Warsaw').toISOString()).toBe('2026-03-29T22:00:00.000Z');
    expect(zonedMidnight('2026-07-01', 'America/New_York').toISOString()).toBe('2026-07-01T04:00:00.000Z');
    expect(zonedMidnight('2026-07-01', 'UTC').toISOString()).toBe('2026-07-01T00:00:00.000Z');
  });

  it('emits Swift-friendly ISO-8601 without fractional seconds', () => {
    expect(dayInstant('2026-07-01', 'Europe/Warsaw')).toBe('2026-06-30T22:00:00Z');
    expect(isoNoMillis(new Date('2026-07-01T10:05:03.456Z'))).toBe('2026-07-01T10:05:03Z');
  });
});

describe('toDayKey', () => {
  it('accepts day keys and ISO instants, mapping instants to the zone-local day', () => {
    expect(toDayKey('2026-09-04', 'Europe/Warsaw')).toBe('2026-09-04');
    expect(toDayKey('2026-09-03T22:00:00Z', 'Europe/Warsaw')).toBe('2026-09-04');
    expect(toDayKey('2026-09-03T22:00:00Z', 'UTC')).toBe('2026-09-03');
    expect(toDayKey('2026-02-30', 'UTC')).toBeNull();
    expect(toDayKey('yesterday', 'UTC')).toBeNull();
    expect(isValidDayKey('2024-02-29')).toBe(true);
    expect(isValidDayKey('2023-02-29')).toBe(false);
  });
});

describe('calendar helpers', () => {
  it('addDays, mondayOf and relative labels', () => {
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28');
    expect(mondayOf('2026-09-04')).toBe('2026-08-31');
    expect(mondayOf('2026-08-31')).toBe('2026-08-31');
    expect(mondayOf('2026-09-06')).toBe('2026-08-31');
    expect(relativeDayLabel('2026-09-04', '2026-09-04')).toBe('today');
    expect(relativeDayLabel('2026-09-05', '2026-09-04')).toBe('tomorrow');
    expect(relativeDayLabel('2026-09-06', '2026-09-04')).toEqual({ weekday: 7 });
  });

  it('validates IANA zones', () => {
    expect(isValidTimeZone('Europe/Warsaw')).toBe(true);
    expect(isValidTimeZone('Mars/Olympus')).toBe(false);
  });
});
