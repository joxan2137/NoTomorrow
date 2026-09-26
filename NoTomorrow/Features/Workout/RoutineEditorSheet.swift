import SwiftUI
import SwiftData

/// What the routine editor opens on: an existing routine, or a new one (blank, or from a finished workout).
struct RoutineEditRequest: Identifiable {
    let id = UUID()
    var routine: Routine?
    var draft: RoutineDraft

    @MainActor
    static func edit(_ routine: Routine) -> RoutineEditRequest {
        RoutineEditRequest(routine: routine, draft: RoutineStore.draft(of: routine))
    }

    static func new(_ draft: RoutineDraft = RoutineDraft()) -> RoutineEditRequest {
        RoutineEditRequest(routine: nil, draft: draft)
    }
}

/// Create or edit a routine (Cancel · New routine / Edit routine · Save): its name, then one card per exercise with
/// sets × reps steppers and a rest menu, Add exercise, and Delete routine at the bottom for an existing one.
/// Everything edits a draft; Save writes it (`RoutineStore`).
struct RoutineEditorSheet: View {
    let request: RoutineEditRequest

    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context
    @State private var draft: RoutineDraft
    @State private var otherNames: [String] = []
    @State private var showsPicker = false
    @State private var showsDeleteConfirm = false
    @State private var showsDiscard = false
    @FocusState private var nameFocused: Bool

    init(request: RoutineEditRequest) {
        self.request = request
        _draft = State(initialValue: request.draft)
    }

    private var isNew: Bool { request.routine == nil }
    private var isDirty: Bool { draft != request.draft || isNew && !draft.items.isEmpty }
    private var canSave: Bool { draft.canSave(otherNames: otherNames) }

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    nameField
                    if draft.isNameTaken(otherNames: otherNames) {
                        Text("routine.nameTaken")
                            .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ember)
                            .padding(.horizontal, 16)
                            .padding(.top, 8)
                    }
                    SectionHeader(title: "workout.exercises",
                                  trailing: draft.items.isEmpty ? nil : Text(verbatim: "\(draft.items.count)").monospacedDigit())
                        .padding(.top, NT.Spacing.section)
                        .padding(.bottom, 8)
                    if draft.items.isEmpty {
                        Text("routine.empty")
                            .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                            .padding(.vertical, 12)
                    }
                    VStack(spacing: 10) {
                        let letters = draft.supersetLetters
                        ForEach(Array(draft.items.enumerated()), id: \.element.id) { index, item in
                            RoutineItemCard(item: item, isFirst: index == 0, isLast: index == draft.items.count - 1,
                                            supersetLetter: letters[index], draft: $draft)
                        }
                    }
                    GhostButton(title: "workout.addExercise", systemImage: "plus") {
                        nameFocused = false
                        showsPicker = true
                    }
                    .padding(.top, 14)
                    if let routine = request.routine {
                        STGroup {
                            STActionRow(label: "routine.delete", color: NT.Colors.bad) {
                                nameFocused = false
                                showsDeleteConfirm = true
                            }
                            .confirmationDialog("routine.deleteConfirm", isPresented: $showsDeleteConfirm,
                                                titleVisibility: .visible) {
                                Button("routine.delete", role: .destructive) {
                                    dismiss()
                                    RoutineStore.delete(routine, in: context)
                                }
                                Button("common.cancel", role: .cancel) {}
                            }
                        }
                        .padding(.top, 28)
                    }
                }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.bottom, NT.Spacing.section)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .animation(.easeInOut(duration: 0.2), value: draft.items.map(\.id))
        .ntScreenBackground()
        .presentationBackground(NT.Colors.ground)
        .presentationDragIndicator(.visible)
        .interactiveDismissDisabled(isDirty)
        .onAppear {
            otherNames = RoutineStore.names(in: context, excluding: request.routine)
            if isNew && draft.trimmedName.isEmpty { nameFocused = true }
        }
        .alert("routine.discardConfirm", isPresented: $showsDiscard) {
            Button("workout.edit.discard", role: .destructive) { dismiss() }
            Button("workout.edit.keepEditing", role: .cancel) {}
        }
        .sheet(isPresented: $showsPicker) {
            ExercisePickerView(alreadyIn: draft.exerciseIDs) { picked in
                for exercise in picked {
                    draft.append(RoutineItemDraft(exerciseID: exercise.id, name: exercise.localizedName,
                                                  primaryMuscle: exercise.primaryMuscles.first))
                }
            }
        }
    }

    // MARK: Header

    private var header: some View {
        ZStack {
            Text(isNew ? "routine.new" : "routine.edit")
                .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).lineLimit(1)
            HStack {
                Button {
                    if isDirty { showsDiscard = true } else { dismiss() }
                } label: {
                    Text("common.cancel").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink2)
                        .frame(minHeight: NT.Size.control)
                }
                Spacer()
                Button {
                    RoutineStore.save(draft, into: request.routine, in: context)
                    Haptics.tap()
                    dismiss()
                } label: {
                    Text("common.save").font(NT.Fonts.headline)
                        .foregroundStyle(canSave ? NT.Colors.ember : NT.Colors.ink3)
                        .frame(minHeight: NT.Size.control)
                }
                .disabled(!canSave)
            }
        }
        .padding(.horizontal, NT.Spacing.screenH)
        .padding(.top, 16)
        .padding(.bottom, 12)
    }

    private var nameField: some View {
        STGroup {
            HStack(spacing: 12) {
                Text("workout.edit.name").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink)
                TextField("", text: $draft.name,
                          prompt: Text("routine.namePlaceholder").foregroundStyle(NT.Colors.ink3))
                    .font(NT.Fonts.body).foregroundStyle(NT.Colors.ink)
                    .multilineTextAlignment(.trailing)
                    .submitLabel(.done)
                    .focused($nameFocused)
            }
            .frame(minHeight: NT.Size.control)
        }
    }
}

// MARK: - Exercise card

/// One exercise of the routine: name and muscle with a ⋯ menu (move up / down, remove), then
/// Sets and Reps steppers and the Rest menu.
private struct RoutineItemCard: View {
    let item: RoutineItemDraft
    let isFirst: Bool
    let isLast: Bool
    let supersetLetter: String?
    @Binding var draft: RoutineDraft

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .center, spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    if let supersetLetter { SupersetTag(letter: supersetLetter) }
                    Text(item.name).font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).lineLimit(1)
                    if let muscle = item.primaryMuscle {
                        Text(WorkoutStrings.muscle(muscle))
                            .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).lineLimit(1)
                    }
                }
                Spacer(minLength: 8)
                Menu {
                    if !isFirst { Button("workout.edit.moveUp") { draft.move(item.id, by: -1) } }
                    if !isLast { Button("workout.edit.moveDown") { draft.move(item.id, by: 1) } }
                    if !isLast && !draft.isLinkedToNext(item.id) {
                        Button("superset.linkNext", systemImage: "link") { draft.linkWithNext(item.id) }
                    }
                    if item.supersetGroup != nil {
                        Button("superset.unlink", systemImage: "link.badge.plus") { draft.unlinkSuperset(item.id) }
                    }
                    Button("workout.removeExercise", role: .destructive) { draft.remove(item.id) }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(NT.Colors.ink2)
                        .frame(width: NT.Size.control, height: NT.Size.control)
                        .contentShape(Rectangle())
                }
                .menuIndicator(.hidden)
            }
            HStack(spacing: 8) {
                RoutineStepper(label: "workout.sets", value: item.sets,
                               canDecrease: item.sets > RoutineDraft.setRange.lowerBound,
                               canIncrease: item.sets < RoutineDraft.setRange.upperBound) { draft.stepSets(item.id, by: $0) }
                RoutineStepper(label: "routine.reps", value: item.reps,
                               canDecrease: item.reps > RoutineDraft.repRange.lowerBound,
                               canIncrease: item.reps < RoutineDraft.repRange.upperBound) { draft.stepReps(item.id, by: $0) }
                restMenu
            }
        }
        .padding(14)
        .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.tile, style: .continuous))
    }

    private var restMenu: some View {
        Menu {
            ForEach(RoutineDraft.restOptions, id: \.self) { seconds in
                Button {
                    draft.setRest(item.id, seconds: seconds)
                } label: {
                    if seconds == item.restSeconds {
                        Label(RoutineRest.label(seconds), systemImage: "checkmark")
                    } else {
                        Text(RoutineRest.label(seconds))
                    }
                }
            }
        } label: {
            VStack(spacing: 2) {
                Text("routine.rest").eyebrow()
                Text(RoutineRest.label(item.restSeconds))
                    .font(NT.Fonts.subheadlineBold).foregroundStyle(NT.Colors.ink).tabular().lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .frame(height: 30)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
            .background(NT.Colors.surface2, in: RoundedRectangle(cornerRadius: NT.Radius.cell, style: .continuous))
            .contentShape(Rectangle())
        }
        .menuIndicator(.hidden)
        .accessibilityLabel(Text("routine.rest"))
        .accessibilityValue(Text(RoutineRest.label(item.restSeconds)))
    }
}

/// "Sets  − 3 +": a label over a compact stepper.
private struct RoutineStepper: View {
    var label: LocalizedStringKey
    var value: Int
    var canDecrease: Bool
    var canIncrease: Bool
    var onStep: (Int) -> Void

    var body: some View {
        VStack(spacing: 2) {
            Text(label).eyebrow()
            HStack(spacing: 0) {
                step("minus", delta: -1, isEnabled: canDecrease)
                Text(verbatim: "\(value)")
                    .font(NT.Fonts.subheadlineBold).foregroundStyle(NT.Colors.ink).tabular()
                    .frame(minWidth: 24)
                step("plus", delta: 1, isEnabled: canIncrease)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 6)
        .background(NT.Colors.surface2, in: RoundedRectangle(cornerRadius: NT.Radius.cell, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(label))
        .accessibilityValue(Text(verbatim: "\(value)"))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: if canIncrease { onStep(1) }
            case .decrement: if canDecrease { onStep(-1) }
            @unknown default: break
            }
        }
    }

    private func step(_ symbol: String, delta: Int, isEnabled: Bool) -> some View {
        Button { onStep(delta) } label: {
            Image(systemName: symbol)
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(isEnabled ? NT.Colors.ink : NT.Colors.ink3)
                .frame(width: 30, height: 30)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressScale())
        .disabled(!isEnabled)
    }
}

/// Rest labels for routine lines: "Default" for 0, otherwise "1:30".
enum RoutineRest {
    static func label(_ seconds: Int) -> String {
        seconds <= 0 ? String(localized: "routine.restDefault") : Fmt.clock(TimeInterval(seconds))
    }
}
