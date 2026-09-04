import Foundation

/// Locale-aware formatting helpers. Never hand-roll dates or decimal separators (Polish uses "82,5 kg", "12 500 kg").
enum Fmt {
    static func weight(_ kg: Double, unit: WeightUnit = .kg, withUnit: Bool = true) -> String {
        let value = unit == .kg ? kg : kg * 2.2046226218
        let number = value.formatted(.number.precision(.fractionLength(0...1)))
        return withUnit ? "\(number)\u{00A0}\(unit.rawValue)" : number
    }

    static func kcal(_ value: Double, withUnit: Bool = true) -> String {
        let number = Int(value.rounded()).formatted(.number.grouping(.automatic))
        return withUnit ? "\(number)\u{00A0}kcal" : number
    }

    static func grams(_ value: Double) -> String {
        "\(Int(value.rounded()).formatted())\u{00A0}g"
    }

    static func volume(_ kg: Double) -> String {
        "\(Int(kg.rounded()).formatted(.number.grouping(.automatic)))\u{00A0}kg"
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

    /// "52 min" / "1 h 12 min"
    static func duration(_ seconds: TimeInterval) -> String {
        let minutes = Int(seconds / 60)
        if minutes < 60 { return String(localized: "\(minutes) min", table: nil) }
        return String(localized: "\(minutes / 60) h \(minutes % 60) min", table: nil)
    }

    /// "18:00" in the user's clock style.
    static func time(_ date: Date) -> String {
        date.formatted(date: .omitted, time: .shortened)
    }

    static func time(minuteOfDay: Int) -> String {
        var comps = Calendar.current.dateComponents([.year, .month, .day], from: .now)
        comps.hour = minuteOfDay / 60
        comps.minute = minuteOfDay % 60
        return time(Calendar.current.date(from: comps) ?? .now)
    }

    /// "Friday, 4 September" (en) / "piątek, 4 września" (pl)
    static func longDay(_ date: Date) -> String {
        date.formatted(.dateTime.weekday(.wide).day().month(.wide))
    }

    /// "Wed" / "śr."
    static func weekdayShort(_ date: Date) -> String {
        date.formatted(.dateTime.weekday(.abbreviated))
    }

    /// "4 Sep" / "4 wrz"
    static func dayMonth(_ date: Date) -> String {
        date.formatted(.dateTime.day().month(.abbreviated))
    }

    /// Relative phrasing for the next session: "Today", "Tomorrow", or the weekday.
    static func relativeDay(_ date: Date) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(date) { return String(localized: "day.today") }
        if cal.isDateInTomorrow(date) { return String(localized: "day.tomorrow") }
        return date.formatted(.dateTime.weekday(.wide)).capitalized(with: Locale.current)
    }

    /// "in 4 h 12 min"
    static func countdown(to date: Date) -> String {
        let seconds = max(0, date.timeIntervalSinceNow)
        let minutes = Int(seconds / 60)
        if minutes < 60 { return String(localized: "in \(minutes) min") }
        let h = minutes / 60, m = minutes % 60
        if h >= 24 {
            let days = h / 24
            return String(localized: "in \(days) d \(h % 24) h")
        }
        return String(localized: "in \(h) h \(m) min")
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
