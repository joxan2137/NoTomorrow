import SwiftUI
import SwiftData

/// Detail of a finished workout: tiles, notes, then every exercise with its completed sets and record badges.
/// Edit switches the same sheet to the editor (Cancel · Edit workout · Save); Delete lives at the editor's bottom.
struct WorkoutDetailSheet: View {
    var workout: Workout
    var unit: WeightUnit = .kg
    /// Delete confirmed in the editor. The presenter closes the sheet and deletes once it is gone.
    var onDelete: (Workout) -> Void = { _ in }

    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context
    @State private var editModel: WorkoutEditModel?
    @State private var showsDiscard = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let editModel {
                editHeader(editModel)
                WorkoutEditView(model: editModel, workout: workout) { onDelete(workout) }
                    .transition(.opacity)
            } else {
                header
                details
                    .transition(.opacity)
            }
        }
        .ntScreenBackground()
        .presentationBackground(NT.Colors.ground)
        .presentationDragIndicator(.visible)
        // A swipe must not throw away unsaved edits; Cancel asks first.
        .interactiveDismissDisabled(editModel?.isDirty == true)
        // An alert, so "Keep editing" stays visible: on iOS 26 a confirmation dialog turns into a popover without its
        // cancel button.
        .alert("workout.edit.discardConfirm", isPresented: $showsDiscard) {
            Button("workout.edit.discard", role: .destructive) { endEditing() }
            Button("workout.edit.keepEditing", role: .cancel) {}
        }
    }

    // MARK: Read

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 16) {
                Text(WorkoutStrings.displayName(workout.name))
                    .font(NT.Fonts.title2)
                    .foregroundStyle(NT.Colors.ink)
                    .lineLimit(1)
                Spacer()
                Button(action: beginEditing) {
                    Text("common.edit").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink2)
                        .frame(minHeight: NT.Size.control)
                }
                Button { dismiss() } label: {
                    Text("common.done").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink2)
                        .frame(minHeight: NT.Size.control)
                }
            }
            Text(verbatim: "\(Fmt.longDay(workout.startedAt)) · \(Fmt.time(workout.startedAt))")
                .font(NT.Fonts.footnote)
                .foregroundStyle(NT.Colors.ink2)
        }
        .padding(.horizontal, NT.Spacing.screenH)
        .padding(.top, 20)
        .padding(.bottom, 16)
    }

    private var details: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                tiles
                if workout.prCount > 0 {
                    HStack(spacing: 6) {
                        Image(systemName: "trophy.fill").font(.system(size: 12, weight: .semibold))
                        Text(WorkoutStrings.prs(workout.prCount)).font(NT.Fonts.footnoteBold).tabular()
                    }
                    .foregroundStyle(NT.Colors.ember)
                    .padding(.top, 12)
                }
                if !workout.notes.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("workout.edit.notes").eyebrow()
                        Text(workout.notes)
                            .font(NT.Fonts.subheadline)
                            .foregroundStyle(NT.Colors.ink)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.top, 18)
                }
                SectionHeader(title: "workout.exercises")
                    .padding(.top, NT.Spacing.section)
                    .padding(.bottom, 4)
                if workout.exercises.isEmpty {
                    Text("workout.noSetsLogged")
                        .font(NT.Fonts.subheadline)
                        .foregroundStyle(NT.Colors.ink2)
                        .padding(.vertical, 12)
                } else {
                    ForEach(workout.sortedExercises, id: \.persistentModelID) { item in
                        WorkoutDetailExercise(item: item, unit: unit)
                        Hairline()
                    }
                }
            }
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.bottom, NT.Spacing.section)
        }
    }

    private var tiles: some View {
        HStack(spacing: 10) {
            StatTile(label: "workout.time", value: Fmt.duration(workout.duration))
            StatTile(label: "workout.sets", value: "\(workout.completedSetCount)")
            StatTile(label: "workout.volume", value: Fmt.volume(workout.totalVolumeKg, unit: unit))
        }
    }

    // MARK: Edit

    private func editHeader(_ model: WorkoutEditModel) -> some View {
        let canSave = model.canSave()
        return ZStack {
            Text("workout.edit.title")
                .font(NT.Fonts.headline)
                .foregroundStyle(NT.Colors.ink)
                .lineLimit(1)
            HStack {
                Button(action: cancelEditing) {
                    Text("common.cancel").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink2)
                        .frame(minHeight: NT.Size.control)
                }
                Spacer()
                Button { save(model) } label: {
                    Text("common.save").font(NT.Fonts.headline)
                        .foregroundStyle(canSave ? NT.Colors.ink : NT.Colors.ink3)
                        .frame(minHeight: NT.Size.control)
                }
                .disabled(!canSave)
            }
        }
        .padding(.horizontal, NT.Spacing.screenH)
        .padding(.top, 20)
        .padding(.bottom, 8)
    }

    private func beginEditing() {
        withAnimation(.easeInOut(duration: 0.2)) { editModel = WorkoutEditModel(workout: workout, unit: unit) }
    }

    private func cancelEditing() {
        if editModel?.isDirty == true {
            showsDiscard = true
        } else {
            endEditing()
        }
    }

    private func endEditing() {
        withAnimation(.easeInOut(duration: 0.2)) { editModel = nil }
    }

    private func save(_ model: WorkoutEditModel) {
        guard model.canSave() else { return }
        model.save(to: workout, context: context)
        Haptics.success()
        endEditing()
    }
}

/// Exercise name + set count, then one line per completed set with PR / set-record badges.
struct WorkoutDetailExercise: View {
    var item: WorkoutExercise
    var unit: WeightUnit = .kg

    private var completed: [SetEntry] { item.sortedSets.filter(\.isCompleted) }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(item.exercise?.localizedName ?? "")
                .font(NT.Fonts.headline)
                .foregroundStyle(NT.Colors.ink)
                .lineLimit(1)
            if completed.isEmpty {
                Text("workout.noSetsLogged").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
            } else {
                ForEach(completed, id: \.persistentModelID) { set in
                    setLine(set)
                }
            }
        }
        .padding(.vertical, 14)
    }

    private func setLine(_ set: SetEntry) -> some View {
        HStack(spacing: 12) {
            Text(setLabel(set))
                .font(NT.Fonts.caption)
                .foregroundStyle(NT.Colors.ink2)
                .tabular()
                .frame(width: 24, height: 24)
                .background(NT.Colors.surface2, in: Circle())
            Text(Fmt.set(set.weightKg, set.reps, unit: unit))
                .font(NT.Fonts.subheadline)
                .foregroundStyle(NT.Colors.ink)
                .tabular()
            Spacer()
            if set.isPR {
                Badge(text: "workout.pr")
            } else if set.isSetRecord {
                Badge(text: "workout.setRecord", color: NT.Colors.ink2)
            }
        }
        .frame(minHeight: 32)
    }

    /// Set number for working sets, counted like the set table (warm-ups don't count); W / D / F glyphs otherwise.
    private func setLabel(_ set: SetEntry) -> String {
        guard set.kind == .normal else { return SetKindMenu.letter(for: set.kind) }
        var number = 0
        for other in item.sortedSets {
            if other.kind != .warmup { number += 1 }
            if other.persistentModelID == set.persistentModelID { break }
        }
        return "\(number)"
    }
}

// MARK: - Presenting

extension View {
    /// Presents a finished workout's detail sheet (read, edit, delete) from any screen.
    func workoutDetailSheet(_ workout: Binding<Workout?>, unit: WeightUnit) -> some View {
        modifier(WorkoutDetailPresenter(workout: workout, unit: unit))
    }
}

/// A confirmed delete closes the sheet first and deletes once it is gone, so nothing renders a deleted model.
private struct WorkoutDetailPresenter: ViewModifier {
    @Binding var workout: Workout?
    var unit: WeightUnit

    @Environment(\.modelContext) private var context
    @State private var pendingDelete: UUID?

    func body(content: Content) -> some View {
        content.sheet(item: $workout, onDismiss: deletePending) { item in
            WorkoutDetailSheet(workout: item, unit: unit) { doomed in
                pendingDelete = doomed.id
                workout = nil
            }
        }
    }

    private func deletePending() {
        guard let id = pendingDelete else { return }
        pendingDelete = nil
        var descriptor = FetchDescriptor<Workout>(predicate: #Predicate { $0.id == id })
        descriptor.fetchLimit = 1
        guard let doomed = try? context.fetch(descriptor).first else { return }
        WorkoutEditor.delete(doomed, in: context)
    }
}
