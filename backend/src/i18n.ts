/**
 * Localized strings for push notifications. The recipient's `users.locale` picks the language.
 * Only `en` and `pl` are supported; anything else falls back to English.
 */

export type Locale = 'en' | 'pl';

export type DayLabel = 'today' | 'tomorrow' | { weekday: number };

export type PushMessage =
  | { kind: 'paired'; name: string }
  | { kind: 'unpaired'; name: string }
  | { kind: 'attendanceConfirmed'; name: string; time: string; day: DayLabel }
  | { kind: 'attendanceCancelled'; name: string; reason?: string | null; day: DayLabel; makeUpWeekday?: number | null }
  | { kind: 'headsUp'; name: string; text: string }
  | { kind: 'reminder'; time: string }
  | { kind: 'skipCheck' };

export type HeadsUpKind = 'cantMakeIt' | 'runningLate' | 'letsGo' | 'custom' | 'makeUpProposal';

export function normalizeLocale(input: string | null | undefined): Locale {
  const lang = (input ?? '').trim().toLowerCase().split(/[-_]/)[0];
  return lang === 'pl' ? 'pl' : 'en';
}

/** Minutes since midnight → "18:00" (24-hour clock, used in both locales). */
export function formatMinute(minute: number): string {
  const m = Math.max(0, Math.min(1439, Math.floor(minute)));
  return `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
}

const WEEKDAYS: Record<Locale, string[]> = {
  en: ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'],
  pl: ['poniedziałek', 'wtorek', 'środa', 'czwartek', 'piątek', 'sobota', 'niedziela'],
};
/** Polish accusative, used after "w" (on) and "proponuje" (proposes). */
const WEEKDAYS_PL_ACC = ['poniedziałek', 'wtorek', 'środę', 'czwartek', 'piątek', 'sobotę', 'niedzielę'];

/** ISO weekday 1 = Monday … 7 = Sunday. */
export function weekdayName(locale: Locale, isoWeekday: number): string {
  const idx = Math.min(7, Math.max(1, Math.round(isoWeekday))) - 1;
  return WEEKDAYS[locale][idx] ?? WEEKDAYS.en[idx]!;
}

function weekdayAccusative(locale: Locale, isoWeekday: number): string {
  const idx = Math.min(7, Math.max(1, Math.round(isoWeekday))) - 1;
  return locale === 'pl' ? WEEKDAYS_PL_ACC[idx]! : WEEKDAYS.en[idx]!;
}

/** "today" / "tomorrow" / "on Saturday" (en) — "dziś" / "jutro" / "w sobotę" (pl). */
export function dayLabel(locale: Locale, label: DayLabel): string {
  if (label === 'today') return locale === 'pl' ? 'dziś' : 'today';
  if (label === 'tomorrow') return locale === 'pl' ? 'jutro' : 'tomorrow';
  return locale === 'pl' ? `w ${weekdayAccusative('pl', label.weekday)}` : `on ${weekdayName('en', label.weekday)}`;
}

const TITLE_BRO: Record<Locale, string> = { en: 'Gym bro', pl: 'Ziomek z siłowni' };

export function pushText(locale: Locale, msg: PushMessage): { title: string; body: string } {
  const pl = locale === 'pl';
  switch (msg.kind) {
    case 'paired':
      return { title: TITLE_BRO[locale], body: pl ? `${msg.name} jest teraz twoim ziomkiem` : `${msg.name} paired with you` };
    case 'unpaired':
      return { title: TITLE_BRO[locale], body: pl ? `${msg.name} zakończył(a) parowanie` : `${msg.name} unpaired` };
    case 'attendanceConfirmed': {
      const when = msg.day === 'today' ? '' : ` ${dayLabel(locale, msg.day)}`;
      return { title: TITLE_BRO[locale], body: pl ? `${msg.name} będzie o ${msg.time}${when}` : `${msg.name} is in for ${msg.time}${when}` };
    }
    case 'attendanceCancelled': {
      const reason = msg.reason?.trim();
      let body = pl ? `${msg.name} nie da rady ${dayLabel(locale, msg.day)}` : `${msg.name} can't make it ${dayLabel(locale, msg.day)}`;
      if (reason) body += ` · ${reason}`;
      if (msg.makeUpWeekday) body += pl ? ` · proponuje ${weekdayAccusative('pl', msg.makeUpWeekday)}` : ` · proposes ${weekdayName('en', msg.makeUpWeekday)}`;
      return { title: TITLE_BRO[locale], body };
    }
    case 'headsUp':
      return { title: pl ? 'Wiadomość od ziomka' : 'Heads-up', body: `${msg.name}: ${msg.text}` };
    case 'reminder':
      return {
        title: pl ? 'Siłownia za godzinę' : 'Gym in an hour',
        body: pl ? `Trening o ${msg.time}. Ziomek na ciebie liczy.` : `Session at ${msg.time}. Your bro is counting on you.`,
      };
    case 'skipCheck':
      return {
        title: TITLE_BRO[locale],
        body: pl ? 'Odpuszczasz dzisiaj? Kliknij, żeby to zapisać.' : 'Did you skip today? Tap to log it.',
      };
  }
}

/** Body used for a heads-up when the app sends no text for a preset kind. */
export function defaultHeadsUpText(locale: Locale, kind: HeadsUpKind): string {
  const pl = locale === 'pl';
  switch (kind) {
    case 'runningLate':
      return pl ? 'Spóźnię się' : 'Running late';
    case 'letsGo':
      return pl ? 'Lecimy' : "Let's go";
    case 'cantMakeIt':
      return pl ? 'Nie dam rady' : "Can't make it";
    case 'makeUpProposal':
      return pl ? 'Odrobimy?' : 'Make-up session?';
    case 'custom':
      return '';
  }
}
