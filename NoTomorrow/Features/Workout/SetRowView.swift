import SwiftUI
import SwiftData

/// One 44 pt row of the set table: kind/number menu · previous ghost · weight cell · reps (or seconds) cell · check.
/// Weights show and parse in the user's unit (stored in kg); for a body-weight or timed exercise it is added weight.
struct SetRowView: View {
    let set: SetEntry
    let exercise: WorkoutExercise
    let model: ActiveWorkoutModel
    let isCurrent: Bool
    var focus: FocusState<SetField?>.Binding
    var onToggle: () -> Void
    var onDelete: () -> Void

    private var unit: WeightUnit { model.unit }
    private var tracking: ExerciseTracking { exercise.exercise?.tracking ?? .weightReps }
    private var previous: ActiveWorkoutModel.SetValue? { model.previous(for: set, in: exercise) }

    var body: some View {
        HStack(spacing: 8) {
            SetKindMenu(kind: set.kind, number: model.setNumber(for: set, in: exercise),
                        onKind: { model.setKind($0, for: set) }, onDelete: onDelete)
            Text(previous.map { Fmt.set($0.weightKg, $0.reps, seconds: $0.seconds, tracking: tracking, unit: unit) } ?? "—")
                .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2).tabular()
                .frame(maxWidth: .infinity)
            SetNumberCell(text: SetInput.text(weightKg: set.weightKg, unit: unit),
                          field: SetField(setID: set.persistentModelID, isReps: false), focus: focus,
                          keyboard: .decimalPad, isCurrent: isCurrent, isEnabled: !set.isCompleted) { text in
                set.weightKg = SetInput.weightKg(text, unit: unit)
            }
            SetNumberCell(text: SetInput.amountText(reps: set.reps, seconds: set.seconds, tracking: tracking),
                          field: SetField(setID: set.persistentModelID, isReps: true), focus: focus,
                          keyboard: .numberPad, isCurrent: isCurrent, isEnabled: !set.isCompleted) { text in
                if tracking == .duration { set.seconds = SetInput.seconds(text) } else { set.reps = SetInput.reps(text) }
            }
            SetCheckButton(isOn: set.isCompleted, action: onToggle)
        }
        .frame(height: NT.Size.control)
        // A done row sits on a faint ember tint; its numbers stay at full strength.
        .background {
            if set.isCompleted {
                RoundedRectangle(cornerRadius: NT.Radius.cell, style: .continuous)
                    .fill(NT.Colors.ember.opacity(0.07))
            }
        }
        .onAppear { model.prefillFromPrevious(set, in: exercise) }
    }
}
