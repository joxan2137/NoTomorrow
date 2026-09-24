import SwiftUI

/// One routine: name, "5 exercises · Bench, Press, Raise", and a round play button.
struct RoutineRow: View {
    var routine: Routine
    var onStart: () -> Void

    private var exerciseNames: [String] {
        routine.sortedItems.compactMap { $0.exercise?.localizedName }
    }

    private var subtitle: String {
        let count = WorkoutStrings.exercises(exerciseNames.count)
        let preview = exerciseNames.prefix(3).joined(separator: ", ")
        return preview.isEmpty ? count : "\(count) · \(preview)"
    }

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(routine.name)
                    .font(NT.Fonts.headline)
                    .foregroundStyle(NT.Colors.ink)
                    .lineLimit(1)
                Text(subtitle)
                    .font(NT.Fonts.footnote)
                    .foregroundStyle(NT.Colors.ink2)
                    .lineLimit(2)
            }
            Spacer(minLength: 8)
            StartPlayButton(routineName: routine.name, action: onStart)
        }
        .padding(.vertical, 14)
    }
}

/// 40 pt surface-2 circle with an ink `play.fill`: starts the routine beside it. VoiceOver reads
/// "Start workout, Push A".
struct StartPlayButton: View {
    var routineName: String
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "play.fill")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(NT.Colors.ink)
                .frame(width: 40, height: 40)
                .background(NT.Colors.surface2, in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(Text("workout.start") + Text(verbatim: ", \(routineName)"))
    }
}
