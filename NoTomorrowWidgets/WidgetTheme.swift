import SwiftUI
import WidgetKit

/// The app's design tokens for the extension, which cannot see `NT` (app target). Values are `NT.Colors` / `NT.Fonts`.
enum W {
    static let ground = Color(red: 10/255, green: 10/255, blue: 11/255)
    static let surface = Color(red: 28/255, green: 28/255, blue: 30/255)
    static let surface2 = Color(red: 42/255, green: 42/255, blue: 46/255)
    static let ink = Color(red: 242/255, green: 242/255, blue: 244/255)
    static let ink2 = Color(red: 235/255, green: 235/255, blue: 245/255).opacity(0.6)
    static let ink3 = Color(red: 235/255, green: 235/255, blue: 245/255).opacity(0.42)
    static let ember = Color(red: 255/255, green: 106/255, blue: 43/255)
    static let good = Color(red: 48/255, green: 209/255, blue: 88/255)
    static let bad = Color(red: 255/255, green: 55/255, blue: 95/255)
    static let track = surface2
    static let hairline = Color.white.opacity(0.12)
    static let border = Color.white.opacity(0.20)

    static let protein = Color(red: 0x5E/255, green: 0xB8/255, blue: 0xFF/255)
    static let carbs = Color(red: 0xF5/255, green: 0xC0/255, blue: 0x4A/255)
    static let fat = Color(red: 0xB1/255, green: 0x8C/255, blue: 0xFF/255)

    /// `NT.Colors.heat`, levels 0…4.
    static let heat: [Color] = [
        surface2,
        Color(red: 0x69/255, green: 0x39/255, blue: 0x27/255),
        Color(red: 0x98/255, green: 0x49/255, blue: 0x2B/255),
        Color(red: 0xCA/255, green: 0x59/255, blue: 0x2C/255),
        ember,
    ]

    /// Big Shoulders Display ExtraBold (bundled with the extension too) — hero numbers only.
    static func display(_ size: CGFloat) -> Font { .custom("BigShouldersDisplayThin-ExtraBold", size: size) }

    static let eyebrow = Font.system(size: 11, weight: .semibold)
    static let headline = Font.system(size: 17, weight: .semibold)
    static let subheadlineBold = Font.system(size: 15, weight: .semibold)
    static let footnote = Font.system(size: 13, weight: .regular)
    static let caption = Font.system(size: 12, weight: .medium)
}

/// Strings for the extension, resolved in the app's language (`nt.language` travels in the snapshot), from the same
/// catalog the app uses (`Localizable.xcstrings` is a member of both targets).
enum WidgetText {
    /// Set per render from the snapshot, before any view reads a string.
    static var languageOverride: String?

    static var language: String {
        if let languageOverride, !languageOverride.isEmpty { return languageOverride }
        return Bundle.main.preferredLocalizations.first ?? "en"
    }

    /// Same rule as the app's `AppLocale.effective`: the device locale, or the chosen language with the device's
    /// region, so numbers, dates and times keep the user's regional format.
    static var locale: Locale {
        guard let languageOverride, !languageOverride.isEmpty else { return .current }
        return Locale(identifier: "\(languageOverride)_\(Locale.current.region?.identifier ?? "PL")")
    }

    private static func bundle(for language: String) -> Bundle {
        guard let path = Bundle.main.path(forResource: language, ofType: "lproj"), let bundle = Bundle(path: path)
        else { return .main }
        return bundle
    }

    static func string(_ key: String) -> String {
        NSLocalizedString(key, bundle: bundle(for: language), value: key, comment: "")
    }

    /// A catalog value with `%@` / `%lld` placeholders ("widget.week.done %lld %lld").
    static func format(_ key: String, _ args: CVarArg...) -> String {
        String(format: string(key), locale: locale, arguments: args)
    }

    static func kcal(_ value: Double) -> String {
        Int(value.rounded()).formatted(.number.grouping(.automatic).locale(locale))
    }

    static func grams(_ value: Double) -> String {
        "\(Int(value.rounded()).formatted(.number.locale(locale)))\u{00A0}g"
    }

    /// 90 → "1:30".
    static func duration(_ seconds: Int) -> String {
        String(format: "%d:%02d", seconds / 60, seconds % 60)
    }

    static func time(_ date: Date) -> String {
        date.formatted(.dateTime.hour().minute().locale(locale))
    }

    /// Today / Tomorrow / the weekday's name.
    static func relativeDay(_ date: Date, now: Date) -> String {
        let cal = Calendar.current
        if cal.isDate(date, inSameDayAs: now) { return string("day.today") }
        if let tomorrow = cal.date(byAdding: .day, value: 1, to: now), cal.isDate(date, inSameDayAs: tomorrow) {
            return string("day.tomorrow")
        }
        return date.formatted(.dateTime.weekday(.wide).locale(locale)).capitalized(with: locale)
    }

    static func monthShort(_ date: Date) -> String {
        date.formatted(.dateTime.month(.abbreviated).locale(locale))
    }
}

extension Calendar {
    /// ISO weekday: 1 = Monday … 7 = Sunday (the app's `Calendar.isoWeekday(for:)`).
    func widgetISOWeekday(for date: Date) -> Int {
        let wd = component(.weekday, from: date)
        return wd == 1 ? 7 : wd - 1
    }

    func nextMidnight(after date: Date) -> Date {
        let start = startOfDay(for: date)
        return self.date(byAdding: .day, value: 1, to: start) ?? start.addingTimeInterval(86_400)
    }
}

extension View {
    /// The widgets' ground, on every iOS the extension runs on.
    func widgetGround() -> some View {
        containerBackground(for: .widget) { W.ground }
    }

    func eyebrowStyle(_ color: Color = W.ink2) -> some View {
        font(W.eyebrow).tracking(0.9).textCase(.uppercase).foregroundStyle(color)
    }
}
