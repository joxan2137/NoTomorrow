/**
 * Pure date/time helpers. "Day keys" are `YYYY-MM-DD` strings in the user's own time zone;
 * instants are JS Dates. Nothing here touches the database.
 */

export const DAY_KEY_RE = /^\d{4}-\d{2}-\d{2}$/;
const DAY_MS = 86_400_000;

export interface LocalParts {
  /** `YYYY-MM-DD` in the zone. */
  day: string;
  /** ISO weekday: 1 = Monday … 7 = Sunday. */
  isoWeekday: number;
  /** Minutes since local midnight, 0…1439. */
  minuteOfDay: number;
}

const formatters = new Map<string, Intl.DateTimeFormat>();

function formatter(tz: string): Intl.DateTimeFormat {
  let f = formatters.get(tz);
  if (!f) {
    f = new Intl.DateTimeFormat('en-US', {
      timeZone: tz,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
    formatters.set(tz, f);
  }
  return f;
}

export function isValidTimeZone(tz: string): boolean {
  try {
    formatter(tz);
    return true;
  } catch {
    return false;
  }
}

function splitDay(day: string): [number, number, number] {
  const [y, m, d] = day.split('-').map(Number);
  return [y ?? 0, m ?? 1, d ?? 1];
}

export function isValidDayKey(day: string): boolean {
  if (!DAY_KEY_RE.test(day)) return false;
  const [y, m, d] = splitDay(day);
  const dt = new Date(Date.UTC(y, m - 1, d));
  return dt.getUTCFullYear() === y && dt.getUTCMonth() === m - 1 && dt.getUTCDate() === d;
}

export function isoWeekdayOf(day: string): number {
  const [y, m, d] = splitDay(day);
  const dow = new Date(Date.UTC(y, m - 1, d)).getUTCDay();
  return dow === 0 ? 7 : dow;
}

export function localParts(date: Date, tz: string): LocalParts {
  const parts = formatter(tz).formatToParts(date);
  const get = (type: Intl.DateTimeFormatPartTypes): string => parts.find((p) => p.type === type)?.value ?? '';
  const day = `${get('year')}-${get('month')}-${get('day')}`;
  const hour = Number(get('hour')) % 24;
  const minute = Number(get('minute'));
  return { day, isoWeekday: isoWeekdayOf(day), minuteOfDay: hour * 60 + minute };
}

/** The instant of local midnight at the start of `day` in `tz` (DST-safe). */
export function zonedMidnight(day: string, tz: string): Date {
  const [y, m, d] = splitDay(day);
  const target = Date.UTC(y, m - 1, d);
  let guess = target;
  for (let i = 0; i < 3; i++) {
    const p = localParts(new Date(guess), tz);
    const [py, pm, pd] = splitDay(p.day);
    const localAsUtc = Date.UTC(py, pm - 1, pd, Math.floor(p.minuteOfDay / 60), p.minuteOfDay % 60);
    const diff = localAsUtc - target;
    if (diff === 0) break;
    guess -= diff;
  }
  return new Date(guess);
}

/**
 * Accepts a `YYYY-MM-DD` day key or an ISO-8601 instant (what the iOS client sends for
 * `Date` fields) and returns the day key in `tz`, or null when unparseable.
 */
export function toDayKey(input: string, tz: string): string | null {
  const s = input.trim();
  if (DAY_KEY_RE.test(s)) return isValidDayKey(s) ? s : null;
  const t = Date.parse(s);
  if (Number.isNaN(t)) return null;
  return localParts(new Date(t), tz).day;
}

/** ISO-8601 in UTC without fractional seconds — the only form Swift's `.iso8601` decoder accepts. */
export function isoNoMillis(date: Date): string {
  return date.toISOString().replace(/\.\d{3}Z$/, 'Z');
}

export function addDays(day: string, n: number): string {
  const [y, m, d] = splitDay(day);
  return new Date(Date.UTC(y, m - 1, d) + n * DAY_MS).toISOString().slice(0, 10);
}

export function mondayOf(day: string): string {
  return addDays(day, 1 - isoWeekdayOf(day));
}

/** Label for a day relative to `today`, used in push copy. */
export function relativeDayLabel(day: string, today: string): 'today' | 'tomorrow' | { weekday: number } {
  if (day === today) return 'today';
  if (day === addDays(today, 1)) return 'tomorrow';
  return { weekday: isoWeekdayOf(day) };
}
