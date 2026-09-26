import SwiftUI
import SwiftData

/// Past sessions of one exercise, newest first (exercise menu > History in the active workout): the day and workout,
/// every completed set with its RPE, the session's best e1RM, and the exercise note left that day.
struct ExerciseHistorySheet: View {
    let exercise: Exercise
    let unit: WeightUnit
    /// The workout in progress, left out of the list.
    var excluding: Workout?

    @Environment(\.dismiss) private var dismiss

    struct Session: Identifiable {
        let id: PersistentIdentifier
        let date: Date
        let workoutName: String
        let sets: [SetEntry]
        let note: String
        var bestE1RM: Double { sets.filter { $0.kind != .warmup }.map(\.estimatedOneRepMax).max() ?? 0 }
    }

    /// Finished sessions with at least one completed set, newest first.
    static func sessions(of exercise: Exercise, excluding: Workout?) -> [Session] {
        exercise.usages.compactMap { usage -> Session? in
            guard let workout = usage.workout, workout.endedAt != nil,
                  workout.persistentModelID != excluding?.persistentModelID else { return nil }
            let done = usage.sortedSets.filter(\.isCompleted)
            guard !done.isEmpty else { return nil }
            return Session(id: usage.persistentModelID, date: workout.startedAt, workoutName: workout.name,
                           sets: done, note: usage.notes)
        }
        .sorted { $0.date > $1.date }
    }

    var body: some View {
        let sessions = Self.sessions(of: exercise, excluding: excluding)
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("history.title").eyebrow()
                    Text(exercise.localizedName).font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink).lineLimit(1)
                }
                Spacer()
                Button { dismiss() } label: {
                    Text("common.done").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink2)
                        .frame(minHeight: NT.Size.control)
                }
            }
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.top, 16)
            .padding(.bottom, 8)
            if sessions.isEmpty {
                Text("history.empty")
                    .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                    .padding(.horizontal, NT.Spacing.screenH)
                    .padding(.top, 12)
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(sessions) { session in
                            sessionBlock(session)
                            Hairline()
                        }
                    }
                    .padding(.horizontal, NT.Spacing.screenH)
                    .padding(.bottom, NT.Spacing.section)
                }
            }
        }
        .ntScreenBackground()
        .presentationBackground(NT.Colors.ground)
        .presentationDragIndicator(.visible)
        .presentationDetents([.medium, .large])
    }

    private func sessionBlock(_ session: Session) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(verbatim: "\(Fmt.relativeDay(session.date)) · \(session.workoutName)")
                    .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).lineLimit(1)
                Spacer(minLength: 8)
                if session.bestE1RM > 0 {
                    Text(verbatim: "e1RM \(Fmt.weight(session.bestE1RM, unit: unit))")
                        .font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink2).tabular()
                }
            }
            ForEach(Array(session.sets.enumerated()), id: \.offset) { _, set in
                HStack(spacing: 8) {
                    Text(verbatim: set.kind == .normal ? "·" : SetKindMenu.letter(for: set.kind))
                        .font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink3).frame(width: 14)
                    Text(verbatim: Fmt.set(set.weightKg, set.reps, unit: unit))
                        .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink).tabular()
                    if let rpe = set.rpe {
                        Text(verbatim: "@\(RPE.label(rpe))").font(NT.Fonts.caption).foregroundStyle(NT.Colors.ember)
                    }
                    if set.isPR { Badge(text: "workout.pr") }
                }
            }
            if !session.note.isEmpty {
                Text(session.note)
                    .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.vertical, 14)
    }
}
