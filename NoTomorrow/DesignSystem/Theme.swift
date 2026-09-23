import SwiftUI

/// Design tokens for No Tomorrow (direction A, dark-only).
/// Mirrors design/*.dc.html: near-black ground, elevated surfaces, white primary actions,
/// ember reserved for progress, PRs and the next session.
enum NT {

    enum Colors {
        static let ground   = Color(red: 10/255,  green: 10/255,  blue: 11/255)
        static let surface  = Color(red: 28/255,  green: 28/255,  blue: 30/255)
        static let surface2 = Color(red: 42/255,  green: 42/255,  blue: 46/255)
        static let surface3 = Color(red: 56/255,  green: 56/255,  blue: 60/255)
        static let tabBar   = Color(red: 22/255,  green: 22/255,  blue: 24/255)

        static let ink  = Color(red: 242/255, green: 242/255, blue: 244/255)
        static let ink2 = Color(red: 235/255, green: 235/255, blue: 245/255).opacity(0.60)
        static let ink3 = Color(red: 235/255, green: 235/255, blue: 245/255).opacity(0.42)
        static let onPrimary = ground

        static let ember     = Color(red: 255/255, green: 106/255, blue: 43/255)
        static let emberTint = ember.opacity(0.12)
        static let good      = Color(red: 48/255,  green: 209/255, blue: 88/255)
        static let bad       = Color(red: 255/255, green: 55/255,  blue: 95/255)
        static let badTint   = bad.opacity(0.12)

        /// Fuel history levels 0…4 (nothing logged → on target): an OKLCH ramp at ember's hue, lighter and more
        /// saturated per step, ending on ember itself.
        static let heat: [Color] = [surface2, Color(hex: 0x693927), Color(hex: 0x98492B), Color(hex: 0xCA592C), ember]

        static let hairline = Color.white.opacity(0.12)
        static let border   = Color.white.opacity(0.20)
    }

    enum Fonts {
        /// Big Shoulders Display ExtraBold — hero numbers and the wordmark only.
        static func display(_ size: CGFloat) -> Font {
            .custom("BigShouldersDisplayThin-ExtraBold", size: size)
        }
        static let largeTitle  = Font.system(size: 34, weight: .bold)
        static let title1      = Font.system(size: 28, weight: .bold)
        static let title2      = Font.system(size: 22, weight: .bold)
        static let title3      = Font.system(size: 20, weight: .semibold)
        static let headline    = Font.system(size: 17, weight: .semibold)
        static let body        = Font.system(size: 17, weight: .regular)
        static let callout     = Font.system(size: 16, weight: .regular)
        static let subheadline = Font.system(size: 15, weight: .regular)
        static let subheadlineBold = Font.system(size: 15, weight: .semibold)
        static let footnote    = Font.system(size: 13, weight: .regular)
        static let footnoteBold = Font.system(size: 13, weight: .semibold)
        static let caption     = Font.system(size: 12, weight: .medium)
        static let eyebrow     = Font.system(size: 11, weight: .semibold)
    }

    enum Spacing {
        static let screenH: CGFloat = 20
        static let cardPadding: CGFloat = 18
        static let section: CGFloat = 22
        static let row: CGFloat = 12
    }

    enum Radius {
        static let card: CGFloat = 22
        static let tile: CGFloat = 16
        static let field: CGFloat = 12
        static let cell: CGFloat = 10
        static let pill: CGFloat = 28
    }

    enum Size {
        static let primaryButton: CGFloat = 56
        static let cardButton: CGFloat = 52
        static let control: CGFloat = 44
        static let chip: CGFloat = 40
        static let tabBar: CGFloat = 49
    }
}

// MARK: - Text style helpers

struct EyebrowStyle: ViewModifier {
    var color: Color
    func body(content: Content) -> some View {
        content
            .font(NT.Fonts.eyebrow)
            .tracking(0.9)
            .textCase(.uppercase)
            .foregroundStyle(color)
    }
}

extension View {
    /// 11pt semibold uppercase tracked label (section labels, "NEXT SESSION").
    func eyebrow(_ color: Color = NT.Colors.ink2) -> some View {
        modifier(EyebrowStyle(color: color))
    }

    func ntScreenBackground() -> some View {
        self.background(NT.Colors.ground.ignoresSafeArea())
    }

    /// Tabular numerals for every number that sits in a column or ticks.
    func tabular() -> some View {
        self.monospacedDigit()
    }
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: alpha
        )
    }
}
