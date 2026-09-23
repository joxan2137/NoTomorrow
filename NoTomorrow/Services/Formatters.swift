import Foundation

/// Locale-aware formatting helpers. Never hand-roll dates or decimal separators (Polish uses "82,5 kg", "12 500 kg").
enum Fmt {
    /// Locale used for every formatter: the user's language override (Settings / onboarding) on top of the device region.
    static var locale: Locale { AppLocale.effective(languageOverride: UserDefaults.standard.string(forKey: "nt.language")) }

    /// Catalog lookup that follows `locale` immediately (no relaunch needed).
    static func localized(_ key: String.LocalizationValue) -> String {
        var resource = LocalizedStringResource(key)
        resource.locale = locale
        return String(localized: resource)
    }

    /// Pounds per kilogram. Weights are stored in kg and converted only for display and input.
    static let lbPerKg = 2.2046226218

    static func weight(_ kg: Double, unit: WeightUnit = .kg, withUnit: Bool = true) -> String {
        let value = unit == .kg ? kg : kg * lbPerKg
        let number = value.formatted(.number.precision(.fractionLength(0...1)).locale(locale))
        return withUnit ? "\(number)\u{00A0}\(unit.rawValue)" : number
    }

    static func kcal(_ value: Double, withUnit: Bool = true) -> String {
        let number = Int(value.rounded()).formatted(.number.grouping(.automatic).locale(locale))
        return withUnit ? "\(number)\u{00A0}kcal" : number
    }

    static func grams(_ value: Double) -> String {
        "\(Int(value.rounded()).formatted(.number.locale(locale)))\u{00A0}g"
    }

    /// "12 500 kg": whole numbers, converted to `unit`.
    static func volume(_ kg: Double, unit: WeightUnit = .kg, withUnit: Bool = true) -> String {
        let value = unit == .kg ? kg : kg * lbPerKg
        let number = Int(value.rounded()).formatted(.number.grouping(.automatic).locale(locale))
        return withUnit ? "\(number)\u{00A0}\(unit.rawValue)" : number
    }

    /// "85 × 7"
    static func set(_ kg: Double, _ reps: Int, unit: WeightUnit = .kg) -> String {
        "\(weight(kg, unit: unit, withUnit: false)) × \(reps)"
    }

    /// "1:12"
    static func clock(_ seconds: TimeInterval) -> String {
        let s = max(0, Int(seconds.rounded()))
        return String(format: "%d:%02d", s / 60, s % 60)
    }

    /// Workout clock: "42:10", then "1:02:03" from an hour on (never "62:03").
    static func elapsed(_ seconds: TimeInterval) -> String {
        let s = max(0, Int(seconds.rounded(.down)))
        guard s >= 3600 else { return clock(TimeInterval(s)) }
        return String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }

    /// "52 min" / "1 h 12 min"
    static func duration(_ seconds: TimeInterval) -> String {
        let minutes = Int(seconds / 60)
        if minutes < 60 { return localized("\(minutes) min") }
        return localized("\(minutes / 60) h \(minutes % 60) min")
    }

    /// "18:00" in the user's clock style.
    static func time(_ date: Date) -> String {
        date.formatted(.dateTime.hour().minute().locale(locale))
    }

    static func time(minuteOfDay: Int) -> String {
        var comps = Calendar.current.dateComponents([.year, .month, .day], from: .now)
        comps.hour = minuteOfDay / 60
        comps.minute = minuteOfDay % 60
        return time(Calendar.current.date(from: comps) ?? .now)
    }

    /// "Friday, 4 September" (en) / "piątek, 4 września" (pl)
    static func longDay(_ date: Date) -> String {
        date.formatted(.dateTime.weekday(.wide).day().month(.wide).locale(locale))
    }

    /// "Fri, 4 Sep" / "pt., 4 wrz"
    static func shortDay(_ date: Date) -> String {
        date.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated).locale(locale))
    }

    /// "Wed" / "śr."
    static func weekdayShort(_ date: Date) -> String {
        date.formatted(.dateTime.weekday(.abbreviated).locale(locale))
    }

    /// "4 Sep" / "4 wrz"
    static func dayMonth(_ date: Date) -> String {
        date.formatted(.dateTime.day().month(.abbreviated).locale(locale))
    }

    /// Fuel's day title: "Today" / "Yesterday" / "Mon, 21 Sep", with the year added outside the current one
    /// ("Sun, 21 Sep 2025").
    static func dayTitle(_ date: Date, now: Date = .now) -> String {
        let cal = Calendar.current
        if cal.isDate(date, inSameDayAs: now) { return localized("day.today") }
        if let yesterday = cal.date(byAdding: .day, value: -1, to: now), cal.isDate(date, inSameDayAs: yesterday) {
            return localized("day.yesterday")
        }
        if cal.component(.year, from: date) != cal.component(.year, from: now) {
            return shortDay(date) + " " + date.formatted(.dateTime.year().locale(locale))
        }
        return shortDay(date)
    }

    /// "Sep" / "wrz"
    static func monthShort(_ date: Date) -> String {
        date.formatted(.dateTime.month(.abbreviated).locale(locale))
    }

    /// "93%" / "93%"
    static func percent(_ ratio: Double) -> String {
        ratio.formatted(.percent.precision(.fractionLength(0)).locale(locale))
    }

    /// Relative phrasing for the next session: "Today", "Tomorrow", or the weekday.
    static func relativeDay(_ date: Date) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(date) { return localized("day.today") }
        if cal.isDateInTomorrow(date) { return localized("day.tomorrow") }
        return date.formatted(.dateTime.weekday(.wide).locale(locale)).capitalized(with: locale)
    }

    /// "in 4 h 12 min"
    static func countdown(to date: Date) -> String {
        let seconds = max(0, date.timeIntervalSinceNow)
        let minutes = Int(seconds / 60)
        if minutes < 60 { return localized("in \(minutes) min") }
        let h = minutes / 60, m = minutes % 60
        if h >= 24 {
            let days = h / 24
            return localized("in \(days) d \(h % 24) h")
        }
        return localized("in \(h) h \(m) min")
    }
}

/// The mirror image of `Fmt`: text a user typed into a number field, back to a number. Same rules as Android
/// `Parsing.decimal`: "72,5" and "72.5" both parse, digit-group spaces (U+0020, U+00A0, U+202F, U+2009, U+2007, tab)
/// are ignored, and anything that is not a finite plain decimal is nil ("1.234.5", "inf", "0x10").
enum NumberInput {
    private static let spaces: Set<Character> = [" ", "\u{00A0}", "\u{202F}", "\u{2009}", "\u{2007}", "\t"]
    private static let decimalPattern = #/[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?/#

    static func decimal(_ text: String) -> Double? {
        var cleaned = ""
        for c in text where !spaces.contains(c) {
            cleaned.append(c == "," ? "." : c)
        }
        cleaned = cleaned.trimmingCharacters(in: .whitespacesAndNewlines)
        guard cleaned.wholeMatch(of: decimalPattern) != nil, let value = Double(cleaned), value.isFinite else { return nil }
        return value
    }

    /// `decimal`, rejecting negatives.
    static func nonNegative(_ text: String) -> Double? {
        decimal(text).flatMap { $0 >= 0 ? $0 : nil }
    }
}

extension Calendar {
    /// ISO weekday: 1 = Monday … 7 = Sunday.
    func isoWeekday(for date: Date) -> Int {
        let wd = component(.weekday, from: date)   // 1 = Sunday
        return wd == 1 ? 7 : wd - 1
    }

    /// Monday of the week containing `date` (respecting local week start would put Sunday first in en_US; the app is Monday-first by design).
    func startOfISOWeek(for date: Date) -> Date {
        let iso = isoWeekday(for: date)
        return startOfDay(for: self.date(byAdding: .day, value: -(iso - 1), to: date) ?? date)
    }
}
