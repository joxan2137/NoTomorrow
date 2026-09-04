import SwiftUI
import SwiftData

/// Identifies one numeric cell for the shared keyboard focus.
struct SetField: Hashable {
    var setID: PersistentIdentifier
    var isReps: Bool
}

/// One 44 pt row of the set table: kind/number menu · previous ghost · kg cell · reps cell · check.
struct SetRowView: View {
    let set: SetEntry
    let exercise: WorkoutExercise
    let model: ActiveWorkoutModel
    let isCurrent: Bool
    var focus: FocusState<SetField?>.Binding
    var onToggle: () -> Void

    @State private var weightText = ""
    @State private var repsText = ""

    private var previous: (weightKg: Double, reps: Int)? { model.previous(for: set, in: exercise) }

    var body: some View {
        HStack(spacing: 8) {
            kindMenu
            Text(previous.map { Fmt.set($0.weightKg, $0.reps) } ?? "—")
                .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2).tabular()
                .frame(maxWidth: .infinity)
            cell(text: $weightText, field: SetField(setID: set.persistentModelID, isReps: false), keyboard: .decimalPad)
            cell(text: $repsText, field: SetField(setID: set.persistentModelID, isReps: true), keyboard: .numberPad)
            checkButton
        }
        .frame(height: NT.Size.control)
        .opacity(set.isCompleted ? 0.55 : 1)
        .onAppear(perform: syncFromModel)
        .onChange(of: set.weightKg) { _, new in if parseWeight(weightText) != new { weightText = new > 0 ? Fmt.weight(new, withUnit: false) : "" } }
        .onChange(of: set.reps) { _, new in if parseReps(repsText) != new { repsText = new > 0 ? "\(new)" : "" } }
        .onChange(of: weightText) { _, new in
            let v = parseWeight(new)
            if set.weightKg != v { set.weightKg = v }
        }
        .onChange(of: repsText) { _, new in
            let v = parseReps(new)
            if set.reps != v { set.reps = v }
        }
    }

    // MARK: Set column

    private var kindMenu: some View {
        Menu {
            Button("workout.warmup") { model.setKind(.warmup, for: set) }
            Button("workout.dropset") { model.setKind(.drop, for: set) }
            Button("workout.failure") { model.setKind(.failure, for: set) }
            Button("workout.normalSet") { model.setKind(.normal, for: set) }
        } label: {
            Group {
                if set.kind == .normal {
                    Text("\(model.setNumber(for: set, in: exercise))")
                        .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink).tabular()
                } else {
                    Text(verbatim: kindLetter)
                        .font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink2)
                        .frame(width: 24, height: 24)
                        .background(NT.Colors.surface2, in: Circle())
                }
            }
            .frame(width: 36, height: NT.Size.control)
            .contentShape(Rectangle())
        }
        .menuIndicator(.hidden)
    }

    private var kindLetter: String {
        switch set.kind {
        case .warmup: "W"
        case .drop: "D"
        case .failure: "F"
        case .normal: ""
        }
    }

    // MARK: Cells

    private func cell(text: Binding<String>, field: SetField, keyboard: UIKeyboardType) -> some View {
        let isFocused = focus.wrappedValue == field
        let border: Color = isFocused ? NT.Colors.ink : (isCurrent ? NT.Colors.border : .clear)
        return TextField("", text: text)
            .font(NT.Fonts.body).foregroundStyle(NT.Colors.ink).tabular()
            .multilineTextAlignment(.center)
            .keyboardType(keyboard)
            .focused(focus, equals: field)
            .disabled(set.isCompleted)
            .frame(width: 60, height: NT.Size.control)
            .background(NT.Colors.surface2, in: RoundedRectangle(cornerRadius: NT.Radius.cell, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: NT.Radius.cell, style: .continuous).strokeBorder(border, lineWidth: 1.5))
    }

    private var checkButton: some View {
        Button(action: onToggle) {
            ZStack {
                if set.isCompleted {
                    Circle().fill(NT.Colors.ink)
                    Image(systemName: "checkmark")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(NT.Colors.onPrimary)
                } else {
                    Circle().strokeBorder(NT.Colors.ink3, lineWidth: 1.5)
                }
            }
            .frame(width: 28, height: 28)
            .frame(width: 48, height: NT.Size.control)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: Text ↔ model

    private func syncFromModel() {
        if set.weightKg > 0 {
            weightText = Fmt.weight(set.weightKg, withUnit: false)
        } else if weightText.isEmpty, let previous {
            weightText = Fmt.weight(previous.weightKg, withUnit: false)
        }
        if set.reps > 0 {
            repsText = "\(set.reps)"
        } else if repsText.isEmpty, let previous, previous.reps > 0 {
            repsText = "\(previous.reps)"
        }
    }

    private func parseWeight(_ text: String) -> Double {
        let cleaned = text.replacingOccurrences(of: ",", with: ".")
            .replacingOccurrences(of: "\u{00A0}", with: "")
            .replacingOccurrences(of: " ", with: "")
        return Double(cleaned) ?? 0
    }

    private func parseReps(_ text: String) -> Int {
        Int(text.trimmingCharacters(in: .whitespaces)) ?? Int(parseWeight(text).rounded())
    }
}
