import SwiftUI
import SwiftData

/// Progress > Lifts: the latest milestone reached and the next few with how far along they are. The whole card opens
/// `MilestonesView`. The evaluation lives in `Milestones`; the sessions come from `ProgressModel`, which reads the
/// history once per reload instead of on every render.
struct MilestonesCard: View {
    var unit: WeightUnit
    var sessions: [Milestones.Session]

    @Query(sort: \BodyWeightEntry.day) private var weights: [BodyWeightEntry]
    @Query private var profiles: [UserProfile]

    var body: some View {
        let milestones = Milestones.evaluate(
            sessions: sessions,
            bodyWeightKg: Milestones.bodyWeight(entries: weights, profile: profiles.first))
        let latest = Milestones.latest(milestones)
        let upcoming = Array(Milestones.next(milestones).prefix(3))
        let achievedCount = milestones.filter(\.isAchieved).count

        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "milestones.title",
                          trailing: Text(verbatim: "\(achievedCount) / \(milestones.count)").monospacedDigit())
            NavigationLink(value: ProgressHomeView.ProgressDestination.milestones) {
                NTCard {
                    VStack(alignment: .leading, spacing: 14) {
                        if let latest {
                            VStack(alignment: .leading, spacing: 8) {
                                Text("milestones.latest").eyebrow()
                                MilestoneAchievedRow(milestone: latest, unit: unit)
                            }
                        } else {
                            Text("milestones.empty")
                                .font(NT.Fonts.subheadline)
                                .foregroundStyle(NT.Colors.ink2)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        if !upcoming.isEmpty {
                            Hairline()
                            VStack(alignment: .leading, spacing: 12) {
                                Text("milestones.upNext").eyebrow()
                                ForEach(upcoming) { milestone in
                                    MilestoneProgressRow(milestone: milestone, unit: unit)
                                }
                            }
                        }
                        HStack(spacing: 8) {
                            Text("milestones.seeAll").font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink)
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.right")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(NT.Colors.ink3)
                        }
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }
}

/// Trophy, title and the day it was reached.
struct MilestoneAchievedRow: View {
    var milestone: Milestones.Milestone
    var unit: WeightUnit

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle().fill(NT.Colors.ember.opacity(0.14))
                Image(systemName: "trophy")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(NT.Colors.ember)
            }
            .frame(width: 36, height: 36)
            Text(verbatim: MilestoneText.title(milestone, unit: unit))
                .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).tabular()
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let achieved = milestone.achieved {
                Text(verbatim: MilestoneText.date(achieved.date))
                    .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular()
                    .lineLimit(1)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// Title and "32 / 50" over a thin ember bar.
struct MilestoneProgressRow: View {
    var milestone: Milestones.Milestone
    var unit: WeightUnit

    var body: some View {
        VStack(spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(verbatim: MilestoneText.title(milestone, unit: unit))
                    .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink).tabular()
                    .lineLimit(1).minimumScaleFactor(0.8)
                Spacer(minLength: 0)
                Text(verbatim: MilestoneText.progress(milestone, unit: unit))
                    .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular()
                    .lineLimit(1)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(NT.Colors.surface2)
                    Capsule().fill(NT.Colors.ember)
                        .frame(width: geo.size.width * milestone.progress)
                }
            }
            .frame(height: 4)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Copy for a milestone, in the user's unit.
enum MilestoneText {
    static func title(_ milestone: Milestones.Milestone, unit: WeightUnit) -> String {
        switch milestone.kind {
        case .workouts:
            return WorkoutStrings.workouts(Int(milestone.target))
        case .volume:
            return String(localized: "milestone.volume \(Fmt.volume(milestone.target, unit: unit))")
        case .weekStreak:
            return WorkoutStrings.weekStreak(Int(milestone.target))
        case .bench:
            return String(localized: "milestone.bench \(multiple(milestone))")
        case .squat:
            return String(localized: "milestone.squat \(multiple(milestone))")
        case .deadlift:
            return String(localized: "milestone.deadlift \(multiple(milestone))")
        }
    }

    /// "32 / 50", "7 450 / 10 000 kg", "95 / 100 kg".
    static func progress(_ milestone: Milestones.Milestone, unit: WeightUnit) -> String {
        switch milestone.kind {
        case .workouts, .weekStreak:
            return "\(Int(milestone.current)) / \(Int(milestone.target))"
        case .volume:
            return Fmt.volume(milestone.current, unit: unit, withUnit: false) + " / " + Fmt.volume(milestone.target, unit: unit)
        case .bench, .squat, .deadlift:
            return Fmt.weight(milestone.current, unit: unit, withUnit: false) + " / " + Fmt.weight(milestone.target, unit: unit)
        }
    }

    /// "1.5×" / "1,5×"
    static func multiple(_ milestone: Milestones.Milestone) -> String {
        let value = milestone.multiplier ?? 1
        return value.formatted(.number.precision(.fractionLength(0...1)).locale(Fmt.locale)) + "×"
    }

    /// "4 Sep 2026"
    static func date(_ date: Date) -> String {
        date.formatted(.dateTime.day().month(.abbreviated).year().locale(Fmt.locale))
    }
}

extension Milestones {
    /// One session per finished workout with a completed set, as the training calendar counts them.
    static func sessions(from workouts: [Workout]) -> [Session] {
        workouts.compactMap { workout -> Session? in
            guard workout.endedAt != nil, workout.completedSetCount > 0 else { return nil }
            var heaviest: [Kind: Double] = [:]
            for entry in workout.exercises {
                guard let id = entry.exercise?.id, let liftKind = Self.lift(forExerciseID: id) else { continue }
                for set in entry.sets where set.isCompleted && set.kind != .warmup && set.reps > 0 {
                    heaviest[liftKind] = max(heaviest[liftKind] ?? 0, set.weightKg)
                }
            }
            return Session(workoutID: workout.id, startedAt: workout.startedAt,
                           volumeKg: workout.totalVolumeKg, heaviestKg: heaviest)
        }
    }

    /// The latest logged body weight, else the one in the profile.
    static func bodyWeight(entries: [BodyWeightEntry], profile: UserProfile?) -> Double? {
        let latest = entries.max { $0.day < $1.day }?.kg
        if let latest, latest > 0 { return latest }
        if let kg = profile?.bodyWeightKg, kg > 0 { return kg }
        return nil
    }

    /// Everything, read from the store (the finish screen's one-off check). `including` is added when the fetch
    /// does not return it yet (finished but not saved).
    static func evaluate(in context: ModelContext, including workout: Workout? = nil) -> [Milestone] {
        var workouts = (try? context.fetch(FetchDescriptor<Workout>(predicate: #Predicate { $0.endedAt != nil }))) ?? []
        if let workout, !workouts.contains(where: { $0.id == workout.id }) {
            workouts.append(workout)
        }
        let weights = (try? context.fetch(FetchDescriptor<BodyWeightEntry>())) ?? []
        let profile = (try? context.fetch(FetchDescriptor<UserProfile>()))?.first
        return evaluate(sessions: sessions(from: workouts), bodyWeightKg: bodyWeight(entries: weights, profile: profile))
    }
}
