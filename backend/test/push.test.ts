import { describe, expect, it } from 'vitest';
import { dayLabel, defaultHeadsUpText, formatMinute, normalizeLocale, pushText, weekdayName } from '../src/i18n.js';
import { buildApnsPayload } from '../src/push.js';

describe('buildApnsPayload', () => {
  it('produces the alert shape with sound, thread-id, type and extra data', () => {
    const payload = buildApnsPayload('en', { kind: 'paired', name: 'Tomek' }, { partnerId: 'abc' });
    expect(payload).toEqual({
      partnerId: 'abc',
      type: 'paired',
      aps: { alert: { title: 'Gym bro', body: 'Tomek paired with you' }, sound: 'default', 'thread-id': 'bro' },
    });
  });

  it('never lets extra data clobber aps or type', () => {
    const payload = buildApnsPayload('en', { kind: 'skipCheck' }, { aps: 'nope', type: 'nope' });
    expect(payload.type).toBe('skipCheck');
    expect(payload.aps.alert.body).toBe('Did you skip today? Tap to log it.');
  });

  it('localizes for the recipient (pl)', () => {
    expect(buildApnsPayload('pl', { kind: 'paired', name: 'Tomek' }).aps.alert).toEqual({
      title: 'Ziomek z siłowni',
      body: 'Tomek jest teraz twoim ziomkiem',
    });
  });
});

describe('pushText', () => {
  it('attendance confirmed — today vs another day', () => {
    expect(pushText('en', { kind: 'attendanceConfirmed', name: 'Tomek', time: '18:00', day: 'today' }).body).toBe('Tomek is in for 18:00');
    expect(pushText('en', { kind: 'attendanceConfirmed', name: 'Tomek', time: '18:00', day: 'tomorrow' }).body).toBe('Tomek is in for 18:00 tomorrow');
    expect(pushText('pl', { kind: 'attendanceConfirmed', name: 'Tomek', time: '18:00', day: { weekday: 6 } }).body).toBe('Tomek będzie o 18:00 w sobotę');
  });

  it('attendance cancelled — reason and make-up proposal are optional', () => {
    expect(pushText('en', { kind: 'attendanceCancelled', name: 'Tomek', day: 'today' }).body).toBe("Tomek can't make it today");
    expect(pushText('en', { kind: 'attendanceCancelled', name: 'Tomek', day: 'today', reason: 'sick' }).body).toBe("Tomek can't make it today · sick");
    expect(pushText('en', { kind: 'attendanceCancelled', name: 'Tomek', day: 'today', reason: 'sick', makeUpWeekday: 6 }).body).toBe(
      "Tomek can't make it today · sick · proposes Saturday",
    );
    expect(pushText('pl', { kind: 'attendanceCancelled', name: 'Tomek', day: 'today', reason: 'chory', makeUpWeekday: 6 }).body).toBe(
      'Tomek nie da rady dziś · chory · proponuje sobotę',
    );
  });

  it('heads-ups carry the sender name and text as the body', () => {
    expect(pushText('en', { kind: 'headsUp', name: 'Tomek', text: '15 min late' })).toEqual({ title: 'Heads-up', body: 'Tomek: 15 min late' });
    expect(pushText('pl', { kind: 'headsUp', name: 'Tomek', text: "Let's go" }).body).toBe("Tomek: Let's go");
  });

  it('reminder and skip check exist in both languages', () => {
    for (const locale of ['en', 'pl'] as const) {
      expect(pushText(locale, { kind: 'reminder', time: '18:00' }).body).toContain('18:00');
      expect(pushText(locale, { kind: 'skipCheck' }).body.length).toBeGreaterThan(10);
      expect(pushText(locale, { kind: 'unpaired', name: 'Ola' }).body).toContain('Ola');
    }
  });
});

describe('i18n helpers', () => {
  it('normalizes locales and formats minutes/weekdays', () => {
    expect(normalizeLocale('pl-PL')).toBe('pl');
    expect(normalizeLocale('en_US')).toBe('en');
    expect(normalizeLocale('de')).toBe('en');
    expect(normalizeLocale(null)).toBe('en');
    expect(formatMinute(1080)).toBe('18:00');
    expect(formatMinute(5)).toBe('00:05');
    expect(weekdayName('en', 1)).toBe('Monday');
    expect(weekdayName('pl', 7)).toBe('niedziela');
    expect(dayLabel('en', { weekday: 3 })).toBe('on Wednesday');
    expect(dayLabel('pl', { weekday: 3 })).toBe('w środę');
    expect(defaultHeadsUpText('en', 'letsGo')).toBe("Let's go");
    expect(defaultHeadsUpText('pl', 'runningLate')).toBe('Spóźnię się');
    expect(defaultHeadsUpText('en', 'custom')).toBe('');
  });
});
