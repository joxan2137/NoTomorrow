import { isoNoMillis, zonedMidnight } from './time.js';

/**
 * Wire shapes consumed by the iOS app (`Services/BackendClient.swift`). Dates are ISO-8601 UTC
 * without fractional seconds; "day" fields are the instant of local midnight in the caller's
 * time zone so Swift's `.iso8601` decoder lands on the right calendar day.
 */

export const ATTENDANCE_STATUSES = ['planned', 'confirmed', 'attended', 'missed', 'cancelled'] as const;
export type AttendanceStatus = (typeof ATTENDANCE_STATUSES)[number];

export const HEADS_UP_KINDS = ['cantMakeIt', 'runningLate', 'letsGo', 'custom', 'makeUpProposal'] as const;
export type HeadsUpKind = (typeof HEADS_UP_KINDS)[number];

export interface UserRow {
  id: string;
  username: string | null;
  email: string | null;
  emailVerified: boolean;
  displayName: string;
  locale: string;
  tz: string;
  createdAt: Date;
}

export interface ScheduleRow {
  userId: string;
  weekdays: number[];
  defaultMinute: number;
  overrides: Record<string, number>;
  remindHourBefore: boolean;
  askIfSkippedAt21: boolean;
  updatedAt: Date;
}

export interface AttendanceRow {
  userId: string;
  day: string;
  status: AttendanceStatus;
  scheduledMinute: number;
  reason: string | null;
  note: string | null;
  makeUpDay: string | null;
  updatedAt: Date;
}

export interface HeadsUpRow {
  id: string;
  fromUser: string;
  toUser: string;
  kind: HeadsUpKind;
  text: string;
  sessionDay: string;
  sentAt: Date;
  readAt: Date | null;
}

export interface PartnerDTO {
  id: string;
  /** What the iOS `Partner` decodes. */
  name: string;
  /** Same value under the name the backend spec uses. */
  displayName: string;
  pairedAt: string;
}

export interface MeDTO {
  id: string;
  username: string;
  displayName: string;
  email: string | null;
  emailVerified: boolean;
  locale: string;
  tz: string;
  partner: PartnerDTO | null;
  pairCode: string | null;
  createdAt: string;
}

export interface ScheduleDTO {
  weekdays: number[];
  defaultMinuteOfDay: number;
  overrides: Record<string, number>;
  remindHourBefore: boolean;
  askIfSkippedAt21: boolean;
}

export interface AttendanceDTO {
  day: string;
  participant: 'me' | 'partner';
  status: AttendanceStatus;
  scheduledMinute: number;
  reason: string | null;
  note: string | null;
  makeUpDay: string | null;
  updatedAt: string;
}

export interface HeadsUpDTO {
  id: string;
  fromMe: boolean;
  fromUser: string;
  toUser: string;
  kind: HeadsUpKind;
  text: string;
  sessionDay: string;
  sentAt: string;
  readAt: string | null;
}

export function displayNameOf(user: { displayName: string; username: string | null }): string {
  return user.displayName.trim() || user.username || 'Gym bro';
}

/** Local midnight of `day` in `tz`, as the ISO instant the app decodes. */
export function dayInstant(day: string, tz: string): string {
  return isoNoMillis(zonedMidnight(day, tz));
}

export function partnerDTO(user: { id: string; displayName: string; username: string | null }, pairedAt: Date): PartnerDTO {
  const name = displayNameOf(user);
  return { id: user.id, name, displayName: name, pairedAt: isoNoMillis(pairedAt) };
}

export function meDTO(user: UserRow, partner: PartnerDTO | null, pairCode: string | null): MeDTO {
  return {
    id: user.id,
    // Apple/Google accounts have no username; the app decodes a non-optional string.
    username: user.username ?? '',
    displayName: user.displayName,
    email: user.email,
    emailVerified: user.emailVerified,
    locale: user.locale,
    tz: user.tz,
    partner,
    pairCode,
    createdAt: isoNoMillis(user.createdAt),
  };
}

export const DEFAULT_SCHEDULE: ScheduleDTO = {
  weekdays: [1, 3, 5],
  defaultMinuteOfDay: 1080,
  overrides: {},
  remindHourBefore: true,
  askIfSkippedAt21: true,
};

export function scheduleDTO(row: ScheduleRow | null): ScheduleDTO {
  if (!row) return { ...DEFAULT_SCHEDULE, overrides: {} };
  return {
    weekdays: [...row.weekdays].sort((a, b) => a - b),
    defaultMinuteOfDay: row.defaultMinute,
    overrides: { ...row.overrides },
    remindHourBefore: row.remindHourBefore,
    askIfSkippedAt21: row.askIfSkippedAt21,
  };
}

/** Minute of day for an ISO weekday according to a schedule (override, else default). */
export function scheduledMinuteFor(schedule: ScheduleDTO, isoWeekday: number): number {
  return schedule.overrides[String(isoWeekday)] ?? schedule.defaultMinuteOfDay;
}

export function attendanceDTO(row: AttendanceRow, participant: 'me' | 'partner', tz: string): AttendanceDTO {
  return {
    day: dayInstant(row.day, tz),
    participant,
    status: row.status,
    scheduledMinute: row.scheduledMinute,
    reason: row.reason,
    note: row.note,
    makeUpDay: row.makeUpDay ? dayInstant(row.makeUpDay, tz) : null,
    updatedAt: isoNoMillis(row.updatedAt),
  };
}

export function headsUpDTO(row: HeadsUpRow, callerId: string, tz: string): HeadsUpDTO {
  return {
    id: row.id,
    fromMe: row.fromUser === callerId,
    fromUser: row.fromUser,
    toUser: row.toUser,
    kind: row.kind,
    text: row.text,
    sessionDay: dayInstant(row.sessionDay, tz),
    sentAt: isoNoMillis(row.sentAt),
    readAt: row.readAt ? isoNoMillis(row.readAt) : null,
  };
}
