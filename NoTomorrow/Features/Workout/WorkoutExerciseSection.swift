import SwiftUI
import SwiftData

/// One exercise of the active workout: expanded (header + set table + add set) or collapsed to a 60 pt row.
struct WorkoutExerciseSection: View {
    let exercise: WorkoutExercise
    let model: ActiveWorkoutModel
    let isExpanded: Bool
    var focus: FocusState<SetField?>.Binding
    var onToggleSet: (SetEntry) -> Void

    @Environment(\.modelContext) private var context

    private var name: String { exercise.exercise?.localizedName ?? "" }

    private var lastLine: String? {
        guard let last = model.lastSet(for: exercise) else { return nil }
        return "\(String(localized: "workout.last")): \(Fmt.weight(last.weightKg)) × \(last.reps)"
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
            columnHeader
            ForEach(exercise.sortedSets) { set in
                SetRowView(set: set, exercise: exercise, model: model,
                           isCurrent: model.currentSetID(in: exercise) == set.persistentModelID,
                           focus: focus) { onToggleSet(set) }
                if model.hintSetID == set.persistentModelID, let best = model.hintBest {
                    hint(set: set, best: best)
                        .transition(.scale(scale: 0.9, anchor: .leading).combined(with: .opacity))
                }
            }
            addSetRow
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

    private var columnHeader: some View {
        HStack(spacing: 8) {
            Text("workout.set").frame(width: 36)
            Text("workout.previous").frame(maxWidth: .infinity)
            Text(verbatim: "kg").frame(width: 60)
            Text("workout.reps").frame(width: 60)
            Color.clear.frame(width: 48, height: 1)
        }
        .font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink2)
        .padding(.top, 4)
    }

    private func hint(set: SetEntry, best: (weightKg: Double, reps: Int)) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "trophy").font(.system(size: 12, weight: .semibold))
            Text("workout.beatsBest \(Fmt.set(set.weightKg, set.reps)) \(Fmt.set(best.weightKg, best.reps))")
                .font(NT.Fonts.footnoteBold).tabular()
        }
        .foregroundStyle(NT.Colors.ember)
        .padding(.horizontal, 12)
        .frame(height: 32)
        .background(NT.Colors.emberTint, in: RoundedRectangle(cornerRadius: NT.Radius.cell, style: .continuous))
    }

    private var addSetRow: some View {
        Button { model.addSet(to: exercise) } label: {
            HStack(spacing: 6) {
                Image(systemName: "plus").font(.system(size: 14, weight: .semibold))
                Text("workout.addSet").font(NT.Fonts.subheadline)
            }
            .foregroundStyle(NT.Colors.ink2)
            .frame(height: 32)
            .frame(minWidth: NT.Size.control, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
