import SwiftUI

/// One routine: name, "5 exercises · Bench, Press, Raise", and a white Start pill.
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
            StartPill(title: "workout.start", action: onStart)
        }
        .padding(.vertical, 14)
    }
}

/// Compact PrimaryButton: white capsule, ink label, hugs its content. For a button that sits beside text in a row.
struct StartPill: View {
    var title: LocalizedStringKey
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(NT.Fonts.subheadlineBold)
                .foregroundStyle(NT.Colors.onPrimary)
                .lineLimit(1)
                .padding(.horizontal, 18)
                .frame(height: NT.Size.control)
                .background(NT.Colors.ink, in: Capsule())
        }
        .buttonStyle(PressScale())
    }
}
