import SwiftUI

/// Finished workout: name · date · duration · volume, PR count in ember, chevron. Tap opens the detail sheet.
struct WorkoutHistoryRow: View {
    var workout: Workout
    var unit: WeightUnit = .kg
    var action: () -> Void

    private var meta: String {
        [Fmt.dayMonth(workout.startedAt), Fmt.duration(workout.duration), Fmt.volume(workout.totalVolumeKg)]
            .joined(separator: " · ")
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(workout.name)
                        .font(NT.Fonts.headline)
                        .foregroundStyle(NT.Colors.ink)
                        .lineLimit(1)
                    Text(meta)
                        .font(NT.Fonts.footnote)
                        .foregroundStyle(NT.Colors.ink2)
                        .tabular()
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                if workout.prCount > 0 {
                    Text(WorkoutStrings.prs(workout.prCount))
                        .font(NT.Fonts.footnoteBold)
                        .foregroundStyle(NT.Colors.ember)
                        .tabular()
                }
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(NT.Colors.ink3)
            }
            .frame(minHeight: 64)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScale())
    }
}
