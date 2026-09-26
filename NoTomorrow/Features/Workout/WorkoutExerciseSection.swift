import SwiftUI
import SwiftData

/// One exercise of the active workout: expanded (header + suggested weight + set table + add set) or collapsed to a
/// 60 pt row.
struct WorkoutExerciseSection: View {
    let exercise: WorkoutExercise
    let model: ActiveWorkoutModel
    let isExpanded: Bool
    var focus: FocusState<SetField?>.Binding
    var onToggleSet: (SetEntry) -> Void
    var onDeleteSet: (SetEntry) -> Void

    @Environment(\.modelContext) private var context
    @State private var showsNote = false
    @State private var showsHistory = false
    @State private var showsReplace = false

    private var name: String { exercise.exercise?.localizedName ?? "" }

    private var lastLine: String? {
        guard let last = model.lastSet(for: exercise) else { return nil }
        return "\(String(localized: "workout.last")): \(Fmt.weight(last.weightKg, unit: model.unit)) × \(last.reps)"
    }

    var body: some View {
        if isExpanded { expanded } else { collapsed }
    }

    // MARK: Collapsed

    private var collapsed: some View {
        Button { model.toggleExpanded(exercise) } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    if let letter = model.supersetLetter(for: exercise) { SupersetTag(letter: letter) }
                    Text(name).font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).lineLimit(1)
                    Text([WorkoutStrings.sets(exercise.sets.count), lastLine].compactMap { $0 }.joined(separator: " · "))
                        .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular().lineLimit(1)
                }
                Spacer(minLength: 8)
                Image(systemName: exercise.isDone ? "checkmark" : "chevron.right")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(exercise.isDone ? NT.Colors.ember : NT.Colors.ink3)
            }
            .frame(minHeight: 60)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: Expanded

    private var expanded: some View {
        VStack(alignment: .leading, spacing: 10) {
            header
            if showsNote || !exercise.notes.isEmpty {
                ExerciseNoteField(exercise: exercise, previous: model.previousNote(for: exercise))
                    .transition(.opacity)
            }
            if let suggestion = model.suggestion(for: exercise) {
                suggestionRow(suggestion)
                    .transition(.opacity)
            }
            SetColumnHeader(unit: model.unit)
            ForEach(exercise.sortedSets) { set in
                SetRowView(set: set, exercise: exercise, model: model,
                           isCurrent: model.currentSetID(in: exercise) == set.persistentModelID,
                           focus: focus, onToggle: { onToggleSet(set) }, onDelete: { onDeleteSet(set) })
                if model.hintSetID == set.persistentModelID, let best = model.hintBest {
                    hint(set: set, best: best)
                        .transition(.scale(scale: 0.9, anchor: .leading).combined(with: .opacity))
                }
            }
            AddSetButton { model.addSet(to: exercise) }
        }
        .padding(.top, 12)
        .animation(.spring(response: 0.35, dampingFraction: 0.7), value: model.hintSetID)
    }

    private var header: some View {
        HStack(alignment: .center) {
            Button { model.toggleExpanded(exercise) } label: {
                VStack(alignment: .leading, spacing: 2) {
                    if let letter = model.supersetLetter(for: exercise) { SupersetTag(letter: letter) }
                    Text(name).font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).lineLimit(1)
                    Text(subtitle).font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular().lineLimit(1)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Spacer(minLength: 8)
            Menu {
                Button("history.title", systemImage: "clock.arrow.circlepath") { showsHistory = true }
                Button("warmup.add", systemImage: "flame") {
                    withAnimation(.easeInOut(duration: 0.2)) { model.addWarmups(to: exercise) }
                }
                .disabled(model.warmupSteps(for: exercise).isEmpty)
                restMenu
                Button("workout.edit.moveUp", systemImage: "arrow.up") {
                    withAnimation(.easeInOut(duration: 0.2)) { model.move(exercise, by: -1) }
                }
                .disabled(!model.canMove(exercise, by: -1))
                Button("workout.edit.moveDown", systemImage: "arrow.down") {
                    withAnimation(.easeInOut(duration: 0.2)) { model.move(exercise, by: 1) }
                }
                .disabled(!model.canMove(exercise, by: 1))
                if model.canReplace(exercise) {
                    Button("workout.replaceExercise", systemImage: "arrow.triangle.2.circlepath") { showsReplace = true }
                }
                if model.canLinkWithNext(exercise) {
                    Button("superset.linkNext", systemImage: "link") {
                        withAnimation(.easeInOut(duration: 0.2)) { model.linkWithNext(exercise) }
                    }
                }
                if exercise.supersetGroup != nil {
                    Button("superset.unlink", systemImage: "link.badge.plus") {
                        withAnimation(.easeInOut(duration: 0.2)) { model.unlinkSuperset(exercise) }
                    }
                }
                if exercise.notes.isEmpty && !showsNote {
                    Button("note.add", systemImage: "note.text") {
                        withAnimation(.easeInOut(duration: 0.2)) { showsNote = true }
                    }
                }
                Button("workout.removeExercise", role: .destructive) { model.remove(exercise) }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(NT.Colors.ink2)
                    .frame(width: NT.Size.control, height: NT.Size.control)
                    .contentShape(Rectangle())
            }
            .menuIndicator(.hidden)
            .sheet(isPresented: $showsReplace) {
                ExercisePickerView(replacingIn: model.workout) { replacement in
                    withAnimation(.easeInOut(duration: 0.2)) { _ = model.replace(exercise, with: replacement) }
                }
            }
        }
        .sheet(isPresented: $showsHistory) {
            if let ex = exercise.exercise {
                ExerciseHistorySheet(exercise: ex, unit: model.unit, excluding: model.workout)
            }
        }
    }

    /// "Rest timer  1:30" submenu: Default (the Rest length setting) and 30 s to 5 min, the current one ticked.
    private var restMenu: some View {
        let current = exercise.restSeconds
        let defaultSeconds = model.defaultRestSeconds(for: exercise)
        return Menu {
            ForEach(WorkoutRest.options(current: current, defaultSeconds: defaultSeconds), id: \.self) { option in
                Button {
                    model.setRest(option.seconds, for: exercise)
                } label: {
                    if WorkoutRest.isChecked(option, current: current, defaultSeconds: defaultSeconds) {
                        Label(WorkoutRest.label(option), systemImage: "checkmark")
                    } else {
                        Text(WorkoutRest.label(option))
                    }
                }
            }
        } label: {
            Label("workout.restTimer", systemImage: "timer")
            Text(Fmt.clock(TimeInterval(current)))
        }
    }

    private var subtitle: String {
        let muscle = exercise.exercise?.primaryMuscles.first.map(WorkoutStrings.muscle)
        return [muscle, lastLine].compactMap { $0 }.joined(separator: " · ")
    }

    /// "↑ Try 82.5 kg today" over "8 · 8 · 8 at 80 kg last time", and Use, which puts that weight in the open sets.
    private func suggestionRow(_ suggestion: ActiveWorkoutModel.Suggestion) -> some View {
        let reps = suggestion.reps.map { "\($0)" }.joined(separator: " · ")
        return HStack(spacing: 10) {
            Image(systemName: "arrow.up")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(NT.Colors.ember)
            VStack(alignment: .leading, spacing: 1) {
                Text("workout.suggest.try \(Fmt.weight(suggestion.toKg, unit: model.unit))")
                    .font(NT.Fonts.footnoteBold).foregroundStyle(NT.Colors.ink).tabular()
                Text("workout.suggest.last \(reps) \(Fmt.weight(suggestion.fromKg, unit: model.unit))")
                    .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular()
            }
            Spacer(minLength: 8)
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { model.useSuggestion(suggestion, in: exercise) }
            } label: {
                Text("workout.suggest.use")
                    .font(NT.Fonts.subheadlineBold).foregroundStyle(NT.Colors.ink)
                    .frame(minWidth: NT.Size.control, minHeight: 36)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    private func hint(set: SetEntry, best: (weightKg: Double, reps: Int)) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "trophy").font(.system(size: 12, weight: .semibold))
            Text("workout.beatsBest \(Fmt.set(set.weightKg, set.reps, unit: model.unit)) \(Fmt.set(best.weightKg, best.reps, unit: model.unit))")
                .font(NT.Fonts.footnoteBold).tabular()
        }
        .foregroundStyle(NT.Colors.ember)
        .padding(.horizontal, 12)
        .frame(height: 32)
        .background(NT.Colors.emberTint, in: RoundedRectangle(cornerRadius: NT.Radius.cell, style: .continuous))
    }
}

/// The exercise's note for this workout, saved as it is typed. Its placeholder is the note from last time, so a
/// seat height or grip written once shows up again the next session.
private struct ExerciseNoteField: View {
    let exercise: WorkoutExercise
    let previous: String?

    @Environment(\.modelContext) private var context
    @State private var text = ""

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "note.text")
                .font(.system(size: 13, weight: .semibold)).foregroundStyle(NT.Colors.ink3)
            TextField("note.add", text: $text, prompt: prompt, axis: .vertical)
                .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink)
                .lineLimit(1...4)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.cell, style: .continuous))
        .onAppear { text = exercise.notes }
        // "Replace exercise" clears the note: the field must not keep (and later write back) the old one.
        .onChange(of: exercise.exercise?.id) { _, _ in text = exercise.notes }
        .onChange(of: text) { _, new in
            guard new != exercise.notes else { return }
            exercise.notes = new
            try? context.save()
        }
    }

    private var prompt: Text {
        if let previous {
            Text("note.last \(previous)").foregroundStyle(NT.Colors.ink3)
        } else {
            Text("note.placeholder").foregroundStyle(NT.Colors.ink3)
        }
    }
}

/// "SUPERSET A" over the name of an exercise in a superset.
struct SupersetTag: View {
    let letter: String

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "link").font(.system(size: 9, weight: .bold))
            Text("superset.tag \(letter)")
        }
        .eyebrow(NT.Colors.ember)
    }
}
