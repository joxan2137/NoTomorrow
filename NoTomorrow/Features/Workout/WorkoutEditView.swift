import SwiftUI
import SwiftData

/// Edit mode of the workout detail sheet: name, date, start time, duration and notes, then every exercise laid out
/// like the active table (the Previous column left blank), Add exercise, and Delete workout at the bottom.
/// Everything edits the model's draft; the sheet's Save writes it.
struct WorkoutEditView: View {
    @Bindable var model: WorkoutEditModel
    let workout: Workout
    /// Delete confirmed: the presenter closes the sheet, then deletes.
    var onDelete: () -> Void

    private enum TextFieldID: Hashable { case name, notes }

    @Environment(\.modelContext) private var context
    @FocusState private var focus: SetField?
    @FocusState private var textFocus: TextFieldID?
    @State private var showsPicker = false
    @State private var showsDeleteConfirm = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                details
                if !model.draft.isTimeValid() {
                    Text("workout.edit.endsInFuture")
                        .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ember)
                        .padding(.horizontal, 16)
                        .padding(.top, 8)
                }
                notes.padding(.top, 18)
                SectionHeader(title: "workout.exercises")
                    .padding(.top, NT.Spacing.section)
                    .padding(.bottom, 4)
                ForEach(Array(model.draft.exercises.enumerated()), id: \.element.id) { index, exercise in
                    WorkoutEditExerciseSection(model: model, exercise: exercise, isFirst: index == 0,
                                               isLast: index == model.draft.exercises.count - 1, focus: $focus)
                    Hairline().padding(.top, 8)
                }
                GhostButton(title: "workout.addExercise", systemImage: "plus") {
                    dismissKeyboard()
                    showsPicker = true
                }
                .padding(.top, 14)
                STGroup {
                    STActionRow(label: "workout.edit.delete", color: NT.Colors.bad) {
                        dismissKeyboard()
                        showsDeleteConfirm = true
                    }
                    // On the row itself: iOS 26 shows the dialog as a popover pointing at the view it is attached to.
                    .confirmationDialog("workout.edit.deleteConfirm", isPresented: $showsDeleteConfirm,
                                        titleVisibility: .visible) {
                        Button("workout.edit.delete", role: .destructive, action: onDelete)
                        Button("common.cancel", role: .cancel) {}
                    }
                }
                .padding(.top, 28)
            }
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.bottom, NT.Spacing.section)
        }
        .scrollDismissesKeyboard(.interactively)
        .animation(.easeInOut(duration: 0.2), value: model.draft.exercises.map(\.id))
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("common.done", action: dismissKeyboard)
                    .font(NT.Fonts.headline)
            }
        }
        .sheet(isPresented: $showsPicker) {
            ExercisePickerView(alreadyIn: model.draft.exerciseIDs) { picked in
                withAnimation(.easeInOut(duration: 0.2)) { model.append(picked, editing: workout, context: context) }
            }
        }
    }

    private func dismissKeyboard() {
        focus = nil
        textFocus = nil
    }

    // MARK: Details

    private var details: some View {
        STGroup {
            HStack(spacing: 12) {
                Text("workout.edit.name").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink)
                TextField("", text: $model.draft.name,
                          prompt: Text(verbatim: model.original.name).foregroundStyle(NT.Colors.ink3))
                    .font(NT.Fonts.body).foregroundStyle(NT.Colors.ink)
                    .multilineTextAlignment(.trailing)
                    .submitLabel(.done)
                    .focused($textFocus, equals: .name)
            }
            .frame(minHeight: NT.Size.control)
            HStack(spacing: 12) {
                Text("workout.edit.date").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink)
                Spacer(minLength: 8)
                DatePicker("workout.edit.date", selection: day, in: ...Date.now, displayedComponents: .date)
                    .labelsHidden()
            }
            .frame(minHeight: 52)
            HStack(spacing: 12) {
                Text("workout.edit.startTime").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink)
                Spacer(minLength: 8)
                DatePicker("workout.edit.startTime", selection: time, displayedComponents: .hourAndMinute)
                    .labelsHidden()
            }
            .frame(minHeight: 52)
            durationRow
        }
        .tint(NT.Colors.ember)
    }

    private var day: Binding<Date> {
        Binding(get: { model.draft.startedAt }, set: { model.draft.setDay($0) })
    }

    private var time: Binding<Date> {
        Binding(get: { model.draft.startedAt }, set: { model.draft.setTime($0) })
    }

    private var durationRow: some View {
        HStack(spacing: 8) {
            Text("workout.edit.duration").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink)
            Spacer(minLength: 8)
            stepButton("minus", isEnabled: model.draft.canShorten) { model.draft.stepDuration(by: -1) }
            Text(Fmt.duration(model.draft.duration))
                .font(NT.Fonts.body).foregroundStyle(NT.Colors.ink).tabular()
                .frame(minWidth: 84)
            stepButton("plus", isEnabled: model.draft.canLengthen) { model.draft.stepDuration(by: 1) }
                .padding(.trailing, -4)
        }
        .frame(minHeight: 52)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("workout.edit.duration"))
        .accessibilityValue(Text(Fmt.duration(model.draft.duration)))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: if model.draft.canLengthen { model.draft.stepDuration(by: 1) }
            case .decrement: if model.draft.canShorten { model.draft.stepDuration(by: -1) }
            @unknown default: break
            }
        }
    }

    private func stepButton(_ symbol: String, isEnabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(isEnabled ? NT.Colors.ink : NT.Colors.ink3)
                .frame(width: 36, height: 36)
                .background(NT.Colors.surface2, in: Circle())
                .frame(width: NT.Size.control, height: NT.Size.control)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressScale())
        .disabled(!isEnabled)
    }

    // MARK: Notes

    private var notes: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("workout.edit.notes").eyebrow().padding(.leading, 16)
            TextField("workout.edit.notes", text: $model.draft.notes,
                      prompt: Text("workout.edit.notesPlaceholder").foregroundStyle(NT.Colors.ink3), axis: .vertical)
                .font(NT.Fonts.body).foregroundStyle(NT.Colors.ink)
                .lineLimit(3...8)
                .focused($textFocus, equals: .notes)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.tile, style: .continuous))
        }
    }
}

// MARK: - Exercise

/// One exercise in the editor: header with Move up / Move down / Remove, the set table (no Previous), Add set.
/// Rows are never dimmed or locked: every number of a finished workout can be corrected.
private struct WorkoutEditExerciseSection: View {
    let model: WorkoutEditModel
    let exercise: ExerciseDraft
    let isFirst: Bool
    let isLast: Bool
    var focus: FocusState<SetField?>.Binding

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            header
            SetColumnHeader(unit: model.unit, showsPrevious: false)
            ForEach(exercise.sets) { set in
                row(set)
            }
            AddSetButton {
                withAnimation(.easeInOut(duration: 0.2)) { model.draft.addSet(to: exercise.id) }
            }
        }
        .padding(.top, 12)
    }

    private var header: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(exercise.name).font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).lineLimit(1)
                if let muscle = exercise.primaryMuscle {
                    Text(WorkoutStrings.muscle(muscle))
                        .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            Menu {
                if !isFirst {
                    Button("workout.edit.moveUp") { move(by: -1) }
                }
                if !isLast {
                    Button("workout.edit.moveDown") { move(by: 1) }
                }
                Button("workout.removeExercise", role: .destructive) {
                    focus.wrappedValue = nil
                    withAnimation(.easeInOut(duration: 0.2)) { model.draft.removeExercise(exercise.id) }
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(NT.Colors.ink2)
                    .frame(width: NT.Size.control, height: NT.Size.control)
                    .contentShape(Rectangle())
            }
            .menuIndicator(.hidden)
        }
    }

    private func move(by offset: Int) {
        focus.wrappedValue = nil
        withAnimation(.easeInOut(duration: 0.2)) { model.draft.moveExercise(exercise.id, by: offset) }
    }

    private func row(_ set: SetDraft) -> some View {
        HStack(spacing: 8) {
            SetKindMenu(kind: set.kind, number: model.draft.setNumber(of: set.id, in: exercise.id),
                        onKind: { model.setKind($0, for: set.id, in: exercise.id) },
                        onDelete: {
                            focus.wrappedValue = nil
                            withAnimation(.easeInOut(duration: 0.2)) { model.draft.deleteSet(set.id, in: exercise.id) }
                        })
            Color.clear.frame(maxWidth: .infinity, maxHeight: 1)
            SetNumberCell(text: SetInput.text(weightKg: set.weightKg, unit: model.unit),
                          field: SetField(setID: set.id, isReps: false), focus: focus, keyboard: .decimalPad) {
                model.setWeight($0, for: set.id, in: exercise.id)
            }
            SetNumberCell(text: SetInput.text(reps: set.reps),
                          field: SetField(setID: set.id, isReps: true), focus: focus, keyboard: .numberPad) {
                model.setReps($0, for: set.id, in: exercise.id)
            }
            SetCheckButton(isOn: set.isLogged) { toggle(set) }
        }
        .frame(height: NT.Size.control)
    }

    private func toggle(_ set: SetDraft) {
        if model.draft.toggleDone(set.id, in: exercise.id) {
            Haptics.tap()
        } else {
            // Nothing to log without reps: send the user to the reps cell.
            Haptics.warning()
            focus.wrappedValue = SetField(setID: set.id, isReps: true)
        }
    }
}
