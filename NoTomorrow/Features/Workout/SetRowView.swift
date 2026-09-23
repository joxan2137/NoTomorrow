import SwiftUI
import SwiftData

/// One 44 pt row of the set table: kind/number menu · previous ghost · weight cell · reps cell · check.
/// Weights show and parse in the user's unit (stored in kg).
struct SetRowView: View {
    let set: SetEntry
    let exercise: WorkoutExercise
    let model: ActiveWorkoutModel
    let isCurrent: Bool
    var focus: FocusState<SetField?>.Binding
    var onToggle: () -> Void
    var onDelete: () -> Void

    private var unit: WeightUnit { model.unit }
    private var previous: ActiveWorkoutModel.SetValue? { model.previous(for: set, in: exercise) }

    var body: some View {
        HStack(spacing: 8) {
            SetKindMenu(kind: set.kind, number: model.setNumber(for: set, in: exercise),
                        onKind: { model.setKind($0, for: set) }, onDelete: onDelete)
            Text(previous.map { Fmt.set($0.weightKg, $0.reps, unit: unit) } ?? "—")
                .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2).tabular()
                .frame(maxWidth: .infinity)
            SetNumberCell(text: SetInput.text(weightKg: set.weightKg, unit: unit),
                          field: SetField(setID: set.persistentModelID, isReps: false), focus: focus,
                          keyboard: .decimalPad, isCurrent: isCurrent, isEnabled: !set.isCompleted) { text in
                set.weightKg = SetInput.weightKg(text, unit: unit)
            }
            SetNumberCell(text: SetInput.text(reps: set.reps),
                          field: SetField(setID: set.persistentModelID, isReps: true), focus: focus,
                          keyboard: .numberPad, isCurrent: isCurrent, isEnabled: !set.isCompleted) { text in
                set.reps = SetInput.reps(text)
            }
            SetCheckButton(isOn: set.isCompleted, action: onToggle)
        }
        .frame(height: NT.Size.control)
        .opacity(set.isCompleted ? 0.55 : 1)
        .onAppear { model.prefillFromPrevious(set, in: exercise) }
    }
}
