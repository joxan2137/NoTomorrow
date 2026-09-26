import SwiftUI

// MARK: - Range

/// Time window for the e1RM history and its deltas.
enum ProgressRange: String, CaseIterable, Identifiable {
    case m1, m3, y1, all
    var id: String { rawValue }

    var titleKey: LocalizedStringKey {
        switch self {
        case .m1: "range.1m"
        case .m3: "range.3m"
        case .y1: "range.1y"
        case .all: "range.all"
        }
    }

    /// Length of the window in days; nil = everything.
    var days: Int? {
        switch self {
        case .m1: 30
        case .m3: 90
        case .y1: 365
        case .all: nil
        }
    }

    var start: Date? {
        guard let days else { return nil }
        return Calendar.current.date(byAdding: .day, value: -days, to: Calendar.current.startOfDay(for: .now))
    }

    /// "14 kg in 3 months"
    func deltaLabel(_ value: String) -> LocalizedStringKey {
        switch self {
        case .m1: "progress.delta.1m \(value)"
        case .m3: "progress.delta.3m \(value)"
        case .y1: "progress.delta.1y \(value)"
        case .all: "progress.delta.all \(value)"
        }
    }
}

// MARK: - Segmented control

/// Pill segmented control matching the prototypes (surface track, surface-3 selected segment).
struct ProgressSegmented<Option: Hashable>: View {
    var options: [Option]
    var label: (Option) -> LocalizedStringKey
    @Binding var selection: Option
    var segmentHeight: CGFloat = 32
    var inset: CGFloat = 2
    var radius: CGFloat = 8
    var font: Font = NT.Fonts.caption

    var body: some View {
        HStack(spacing: 0) {
            ForEach(options, id: \.self) { option in
                let isSelected = option == selection
                Button {
                    withAnimation(.easeOut(duration: 0.15)) { selection = option }
                } label: {
                    Text(label(option))
                        .font(font)
                        .fontWeight(isSelected ? .semibold : .regular)
                        .foregroundStyle(isSelected ? NT.Colors.ink : NT.Colors.ink2)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .frame(maxWidth: .infinity)
                        .frame(height: segmentHeight)
                        .background(
                            isSelected ? NT.Colors.surface3 : .clear,
                            in: RoundedRectangle(cornerRadius: radius, style: .continuous)
                        )
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isSelected ? .isSelected : [])
            }
        }
        .padding(inset)
        .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: radius + inset, style: .continuous))
    }
}

// MARK: - Delta chip

/// "↑ 14 kg in 3 months": ember on `emberTint` when positive, `ink2` on `neutral` otherwise. `neutral` is `surface`
/// on the ground (`ExerciseProgressView`) and `surface2` inside a card (the Progress home focal card).
struct ProgressDeltaChip: View {
    var delta: Double
    var range: ProgressRange
    var unit: WeightUnit
    var neutral: Color = NT.Colors.surface

    private var positive: Bool { delta > 0 }

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: positive ? "arrow.up" : (delta < 0 ? "arrow.down" : "minus"))
                .font(.system(size: 12, weight: .bold))
            Text(range.deltaLabel(Fmt.signedWeight(delta, unit: unit, withUnit: true)))
                .font(NT.Fonts.footnoteBold)
                .tabular()
        }
        .foregroundStyle(positive ? NT.Colors.ember : NT.Colors.ink2)
        .padding(.horizontal, 12)
        .frame(height: 30)
        .background(positive ? NT.Colors.emberTint : neutral, in: Capsule())
    }
}

// MARK: - Relative PR phrasing

enum ProgressPhrase {
    /// Row subtitle: "PR today", "PR Wednesday", "3 weeks since PR", "stalled 5 weeks", "No PR yet".
    static func lastPR(_ date: Date?) -> LocalizedStringKey {
        guard let date else { return "progress.noPRYet" }
        let days = daysSince(date)
        switch days {
        case 0: return "progress.prToday"
        case 1: return "progress.prYesterday"
        case 2...6: return "progress.prOn \(weekday(date))"
        case 7..<30: return "progress.weeksSincePR \(days / 7)"
        default: return "progress.stalledWeeks \(days / 7)"
        }
    }

    /// Tile value: "Today", "Yesterday", "9 days ago", "26 Aug".
    static func ago(_ date: Date) -> LocalizedStringKey {
        let days = daysSince(date)
        switch days {
        case 0: return "day.today"
        case 1: return "progress.yesterday"
        case 2..<30: return "progress.daysAgo \(days)"
        default: return "\(Fmt.dayMonth(date))"
        }
    }

    /// Eyebrow date: "Wednesday" within the week, "26 Aug" beyond.
    static func eyebrowDate(_ date: Date) -> String {
        let days = daysSince(date)
        if days == 0 { return String(localized: "day.today") }
        if days == 1 { return String(localized: "progress.yesterday") }
        if days < 7 { return weekday(date) }
        return Fmt.dayMonth(date)
    }

    static func daysSince(_ date: Date) -> Int {
        let cal = Calendar.current
        return cal.dateComponents([.day], from: cal.startOfDay(for: date), to: cal.startOfDay(for: .now)).day ?? 0
    }

    private static func weekday(_ date: Date) -> String {
        date.formatted(.dateTime.weekday(.wide)).capitalized(with: Locale.current)
    }
}

// MARK: - Number helpers local to Progress

extension Fmt {
    /// "+17" / "−3" — signed weight without unit, for delta columns.
    static func signedWeight(_ kg: Double, unit: WeightUnit = .kg, withUnit: Bool = false) -> String {
        let value = unit == .kg ? kg : kg * 2.2046226218
        let number = value.formatted(.number.precision(.fractionLength(0...1)).sign(strategy: .always(includingZero: true)))
        return withUnit ? "\(number)\u{00A0}\(unit.rawValue)" : number
    }

    /// "+5%" for week-over-week change; `ratio` is a fraction (0.05).
    static func signedPercent(_ ratio: Double) -> String {
        ratio.formatted(.percent.precision(.fractionLength(0)).sign(strategy: .always(includingZero: true)))
    }
}
