import SwiftUI

/// 64 pt picker row: name + muscles, last set (or "Never done"), then a selection ring or "In".
struct ExercisePickerRow: View {
    enum State { case available, selected, alreadyIn }

    var exercise: Exercise
    var unit: WeightUnit = .kg
    var state: State
    var action: () -> Void

    private var lastSetLabel: String? {
        guard let set = RecordService.lastSet(for: exercise) else { return nil }
        return Fmt.set(set.weightKg, set.reps, unit: unit)
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(exercise.localizedName)
                        .font(NT.Fonts.headline)
                        .foregroundStyle(state == .alreadyIn ? NT.Colors.ink2 : NT.Colors.ink)
                        .lineLimit(1)
                    Text(WorkoutStrings.subtitle(for: exercise))
                        .font(NT.Fonts.footnote)
                        .foregroundStyle(NT.Colors.ink2)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                if let lastSetLabel {
                    Text(lastSetLabel)
                        .font(NT.Fonts.footnote)
                        .foregroundStyle(NT.Colors.ink2)
                        .tabular()
                } else {
                    Text("exercises.neverDone")
                        .font(NT.Fonts.footnote)
                        .foregroundStyle(NT.Colors.ink2)
                }
                trailing
                    .frame(width: 26, height: NT.Size.control)
            }
            .frame(height: 64)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScale())
        .disabled(state == .alreadyIn)
        .overlay(alignment: .bottom) { Hairline() }
    }

    @ViewBuilder
    private var trailing: some View {
        switch state {
        case .alreadyIn:
            Text("exercises.inWorkout")
                .font(NT.Fonts.footnote)
                .foregroundStyle(NT.Colors.ink3)
        case .selected:
            ZStack {
                Circle().fill(NT.Colors.ink)
                Image(systemName: "checkmark")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(NT.Colors.onPrimary)
            }
            .frame(width: 26, height: 26)
        case .available:
            Circle()
                .strokeBorder(NT.Colors.ink3, lineWidth: 1.5)
                .frame(width: 26, height: 26)
        }
    }
}

/// "+ Create "bench" as a new exercise" — 48 pt row under the results.
struct CreateExerciseRow: View {
    var query: String
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: "plus")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(NT.Colors.ink2)
                Text(WorkoutStrings.create(query))
                    .font(NT.Fonts.subheadline)
                    .foregroundStyle(NT.Colors.ink2)
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .frame(height: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScale())
    }
}
