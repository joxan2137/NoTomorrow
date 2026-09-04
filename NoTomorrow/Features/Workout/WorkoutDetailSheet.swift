import SwiftUI

/// Read-only detail of a finished workout: tiles, then every exercise with its completed sets and record badges.
struct WorkoutDetailSheet: View {
    var workout: Workout
    var unit: WeightUnit = .kg
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
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
        .ntScreenBackground()
        .presentationBackground(NT.Colors.ground)
        .presentationDragIndicator(.visible)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(workout.name)
                    .font(NT.Fonts.title2)
                    .foregroundStyle(NT.Colors.ink)
                    .lineLimit(1)
                Spacer()
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

    private var tiles: some View {
        HStack(spacing: 10) {
            StatTile(label: "workout.time", value: Fmt.duration(workout.duration))
            StatTile(label: "workout.sets", value: "\(workout.completedSetCount)")
            StatTile(label: "workout.volume", value: Fmt.volume(workout.totalVolumeKg))
        }
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

    /// Set number for normal sets; W / D / F glyphs for warm-up, drop and failure (same glyphs as the log screen).
    private func setLabel(_ set: SetEntry) -> String {
        switch set.kind {
        case .normal: "\(set.order + 1)"
        case .warmup: "W"
        case .drop: "D"
        case .failure: "F"
        }
    }
}
