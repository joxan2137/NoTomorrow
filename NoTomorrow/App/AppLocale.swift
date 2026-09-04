import Foundation

/// Builds the locale used for SwiftUI text lookup and formatting.
/// A language override keeps the device REGION (24-hour clock, decimal comma, Monday-first week),
/// so "English" on a phone set to Poland yields `en_PL`, not `en_US`.
enum AppLocale {
    static func effective(languageOverride: String?) -> Locale {
        guard let lang = languageOverride, !lang.isEmpty else { return Locale.current }
        let region = Locale.current.region?.identifier ?? "PL"
        return Locale(identifier: "\(lang)_\(region)")
    }
}
