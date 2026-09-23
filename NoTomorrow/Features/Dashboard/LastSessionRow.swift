import SwiftUI

/// "Last session" header (day · name · duration · n PRs) with one chip per PR set. Tapping opens that workout's
/// detail (the Train tab when there is none yet).
struct LastSessionRow: View {
    var workout: Workout?
    var units: WeightUnit = .kg
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .firstTextBaseline) {
                    Text("dashboard.lastSession").font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink)
                    Spacer(minLength: 12)
                    if let workout { summary(for: workout) }
                }
                if let workout {
                    let prSets = prSets(in: workout)
                    if !prSets.isEmpty {
                        FlowChips(items: prSets.map { chipText($0) })
                    }
                } else {
                    Text("dashboard.noSessionsYet")
                        .font(NT.Fonts.subheadline)
                        .foregroundStyle(NT.Colors.ink2)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScale())
    }

    private func summary(for workout: Workout) -> some View {
        var text = Text(verbatim: "\(dayLabel(workout)) · \(workout.name) · \(Fmt.duration(workout.duration))")
        if workout.prCount > 0 {
            text = text + Text(verbatim: " · ")
                + Text(verbatim: String(format: String(localized: "dashboard.prs"), workout.prCount))
                    .foregroundStyle(NT.Colors.ember)
        }
        return text
            .font(NT.Fonts.subheadline)
            .foregroundStyle(NT.Colors.ink2)
            .tabular()
            .multilineTextAlignment(.trailing)
    }

    /// Weekday for anything in the last six days, otherwise day + month.
    private func dayLabel(_ workout: Workout) -> String {
        let when = workout.endedAt ?? workout.startedAt
        let cal = Calendar.current
        let weekAgo = cal.date(byAdding: .day, value: -6, to: cal.startOfDay(for: .now)) ?? .distantPast
        return when >= weekAgo ? Fmt.weekdayShort(when) : Fmt.dayMonth(when)
    }

    private func prSets(in workout: Workout) -> [SetEntry] {
        workout.sortedExercises.flatMap { $0.sortedSets.filter(\.isPR) }
    }

    private func chipText(_ set: SetEntry) -> String {
        let name = set.workoutExercise?.exercise?.localizedName ?? ""
        return "\(name) \(Fmt.set(set.weightKg, set.reps, unit: units))"
    }
}

/// Wrapping row of 32 pt trophy chips.
private struct FlowChips: View {
    var items: [String]

    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(Array(items.enumerated()), id: \.offset) { _, text in
                HStack(spacing: 6) {
                    Image(systemName: "trophy.fill")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(NT.Colors.ember)
                    Text(text)
                        .font(NT.Fonts.footnote)
                        .foregroundStyle(NT.Colors.ink)
                        .tabular()
                        .lineLimit(1)
                }
                .padding(.horizontal, 12)
                .frame(height: 32)
                .background(NT.Colors.surface, in: Capsule())
            }
        }
    }
}

/// Minimal left-to-right wrapping layout (iOS 16+ `Layout`).
private struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        return arrange(width: width, subviews: subviews).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let result = arrange(width: bounds.width, subviews: subviews)
        for (index, origin) in result.origins.enumerated() {
            subviews[index].place(at: CGPoint(x: bounds.minX + origin.x, y: bounds.minY + origin.y),
                                  proposal: .unspecified)
        }
    }

    private func arrange(width: CGFloat, subviews: Subviews) -> (size: CGSize, origins: [CGPoint]) {
        var origins: [CGPoint] = []
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, maxX: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            origins.append(CGPoint(x: x, y: y))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            maxX = max(maxX, x - spacing)
        }
        return (CGSize(width: maxX, height: y + rowHeight), origins)
    }
}
