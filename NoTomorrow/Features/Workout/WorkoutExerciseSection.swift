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
                    Text(name).font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).lineLimit(1)
                    Text([WorkoutStrings.sets(exercise.sets.count), lastLine].compactMap { $0 }.joined(separator: " · "))
                        .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular().lineLimit(1)
                }
                Spacer(minLength: 8)
                Image(systemName: exercise.isDone ? "checkmark" : "chevron.right")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(exercise.isDone ? NT.Colors.ember : NT.Colors.ink3)
            }
            .frame(height: 60)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: Expanded

    private var expanded: some View {
        VStack(alignment: .leading, spacing: 10) {
            header
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
                    Text(name).font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).lineLimit(1)
                    Text(subtitle).font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular().lineLimit(1)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Spacer(minLength: 8)
            Menu {
                Button("warmup.add", systemImage: "flame") {
                    withAnimation(.easeInOut(duration: 0.2)) { model.addWarmups(to: exercise) }
                }
                .disabled(model.warmupSteps(for: exercise).isEmpty)
                Button("workout.removeExercise", role: .destructive) { model.remove(exercise) }
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
