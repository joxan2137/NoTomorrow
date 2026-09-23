import Foundation
import SwiftData

extension Notification.Name {
    /// A finished workout was edited or deleted. Screens that cache derived workout data reload.
    static let workoutHistoryDidChange = Notification.Name("nt.workoutHistoryDidChange")
}

// MARK: - Draft

/// Unsaved copy of a finished workout for the editor. Plain values only: nothing reaches SwiftData before Save,
/// so Cancel is free and the exercise picker saving its own context can't commit half an edit.
struct WorkoutDraft: Equatable {
    var name: String
    var notes: String
    var startedAt: Date
    /// Seconds. Kept exact until the user steps it, so an untouched duration never moves a set's time.
    var duration: TimeInterval
    var exercises: [ExerciseDraft]

    var endedAt: Date { startedAt.addingTimeInterval(duration) }

    /// Duration steps: 5 minutes, from 5 minutes to 12 hours.
    static let durationStep = 5
    static let durationRange = 5...720

    /// Exercise ids in the draft (the picker shows them as "In").
    var exerciseIDs: Set<String> { Set(exercises.map(\.exerciseID)) }

    /// Save needs a start in the past and an end no later than a minute from now.
    func isTimeValid(now: Date = .now) -> Bool {
        startedAt <= now && endedAt <= now.addingTimeInterval(60)
    }

    var canShorten: Bool { duration > Double(Self.durationRange.lowerBound * 60) }
    var canLengthen: Bool { duration < Double(Self.durationRange.upperBound * 60) }
}

struct ExerciseDraft: Identifiable, Equatable {
    let id: UUID
    /// The `WorkoutExercise` this came from; nil when it was added in the editor.
    var sourceID: PersistentIdentifier?
    var exerciseID: String
    var name: String
    var primaryMuscle: String?
    var restSeconds: Int
    var sets: [SetDraft]
}

struct SetDraft: Identifiable, Equatable {
    /// The values a row had when the editor opened.
    struct Saved: Equatable {
        var kind: SetKind
        var weightKg: Double
        var reps: Int
        var isDone: Bool
    }

    let id: UUID
    /// The `SetEntry` this came from; nil when it was added in the editor.
    var sourceID: PersistentIdentifier?
    var kind: SetKind
    var weightKg: Double
    var reps: Int
    /// Ticked. It only counts (✓, saved as completed) while it has reps: clearing the reps to retype them
    /// does not lose the tick, and nothing is ever logged as "0 × 0".
    var isDone: Bool
    /// `completedAt` as saved. Save remaps it when the start or the duration changes.
    var originalCompletedAt: Date?
    /// The row as saved, for a row that came from the workout; nil for one added in the editor.
    var saved: Saved? = nil

    /// Counts as completed: ticked with reps, or a row completed before the no-"0 × 0" rule (reps 0) that the user
    /// has left exactly as it was. Saving any other edit (a rename) must not un-log it, which could also take the
    /// day's attendance with it.
    var isLogged: Bool { isDone && (reps > 0 || isUntouchedCompleted) }

    /// Completed when the editor opened and not changed since.
    var isUntouchedCompleted: Bool {
        guard let saved, saved.isDone, originalCompletedAt != nil else { return false }
        return saved == Saved(kind: kind, weightKg: weightKg, reps: reps, isDone: isDone)
    }
}

extension WorkoutDraft {
    init(workout: Workout) {
        name = workout.name
        notes = workout.notes
        startedAt = workout.startedAt
        duration = max(0, (workout.endedAt ?? workout.startedAt).timeIntervalSince(workout.startedAt))
        exercises = workout.sortedExercises.map { entry in
            ExerciseDraft(
                id: UUID(), sourceID: entry.persistentModelID,
                exerciseID: entry.exercise?.id ?? "", name: entry.exercise?.localizedName ?? "",
                primaryMuscle: entry.exercise?.primaryMuscles.first, restSeconds: entry.restSeconds,
                sets: entry.sortedSets.map { set in
                    SetDraft(id: UUID(), sourceID: set.persistentModelID, kind: set.kind, weightKg: set.weightKg,
                             reps: set.reps, isDone: set.isCompleted, originalCompletedAt: set.completedAt,
                             saved: .init(kind: set.kind, weightKg: set.weightKg, reps: set.reps, isDone: set.isCompleted))
                })
        }
    }

    // MARK: Editing

    /// Moves the start to `day`, keeping the time of day.
    mutating func setDay(_ day: Date, calendar: Calendar = .current) {
        let delta = calendar.dateComponents([.day], from: calendar.startOfDay(for: startedAt),
                                            to: calendar.startOfDay(for: day)).day ?? 0
        guard delta != 0, let moved = calendar.date(byAdding: .day, value: delta, to: startedAt) else { return }
        startedAt = moved
    }

    /// Moves the start to `time`'s hour and minute on the same day (wall clock, so a DST night doesn't shift it).
    /// Seconds stay, so picking the old time again gives back the exact start.
    mutating func setTime(_ time: Date, calendar: Calendar = .current) {
        let new = calendar.dateComponents([.hour, .minute], from: time)
        let old = calendar.dateComponents([.hour, .minute, .second], from: startedAt)
        guard let hour = new.hour, let minute = new.minute, hour != old.hour || minute != old.minute,
              let from = calendar.date(bySettingHour: old.hour ?? 0, minute: old.minute ?? 0, second: old.second ?? 0,
                                       of: startedAt),
              let to = calendar.date(bySettingHour: hour, minute: minute, second: old.second ?? 0, of: startedAt)
        else { return }
        startedAt = startedAt.addingTimeInterval(to.timeIntervalSince(from))
    }

    /// − / + on the duration: snaps to the 5-minute grid (52 min → 50 or 55), within 5 min … 12 h.
    mutating func stepDuration(by direction: Int) {
        let step = Double(Self.durationStep)
        let minutes = (duration / 60 * 100).rounded() / 100
        let snapped = direction > 0 ? (floor(minutes / step) + 1) * step : (ceil(minutes / step) - 1) * step
        let clamped = min(max(snapped, Double(Self.durationRange.lowerBound)), Double(Self.durationRange.upperBound))
        duration = clamped * 60
    }

    /// Runs `change` on one set.
    mutating func updateSet(_ setID: UUID, in exerciseID: UUID, _ change: (inout SetDraft) -> Void) {
        guard let e = exercises.firstIndex(where: { $0.id == exerciseID }),
              let s = exercises[e].sets.firstIndex(where: { $0.id == setID }) else { return }
        change(&exercises[e].sets[s])
    }

    /// Ticks or unticks a row. A tick that would not count can't be made (no new "0 × 0" sets): returns false. An
    /// untouched legacy 0-rep row unticked by mistake can be ticked back.
    @discardableResult
    mutating func toggleDone(_ setID: UUID, in exerciseID: UUID) -> Bool {
        var accepted = true
        updateSet(setID, in: exerciseID) { set in
            if set.isLogged {
                set.isDone = false
            } else {
                var ticked = set
                ticked.isDone = true
                if ticked.isLogged { set.isDone = true } else { accepted = false }
            }
        }
        return accepted
    }

    /// "+ Add set": copies the last row (a warm-up becomes a normal set). Added rows start ticked: editing a past
    /// workout is logging after the fact. An empty one counts once it has reps.
    mutating func addSet(to exerciseID: UUID) {
        guard let e = exercises.firstIndex(where: { $0.id == exerciseID }) else { return }
        let last = exercises[e].sets.last
        let kind: SetKind = last.map { $0.kind == .warmup ? .normal : $0.kind } ?? .normal
        exercises[e].sets.append(SetDraft(id: UUID(), sourceID: nil, kind: kind, weightKg: last?.weightKg ?? 0,
                                          reps: last?.reps ?? 0, isDone: true, originalCompletedAt: nil))
    }

    mutating func deleteSet(_ setID: UUID, in exerciseID: UUID) {
        guard let e = exercises.firstIndex(where: { $0.id == exerciseID }) else { return }
        exercises[e].sets.removeAll { $0.id == setID }
    }

    mutating func removeExercise(_ exerciseID: UUID) {
        exercises.removeAll { $0.id == exerciseID }
    }

    /// Move up (-1) / down (+1) among the exercises.
    mutating func moveExercise(_ exerciseID: UUID, by offset: Int) {
        guard let from = exercises.firstIndex(where: { $0.id == exerciseID }) else { return }
        let to = from + offset
        guard exercises.indices.contains(to) else { return }
        exercises.swapAt(from, to)
    }

    /// An exercise added from the picker: one ticked row, prefilled from the last time it was done.
    mutating func appendExercise(id: String, name: String, primaryMuscle: String?, restSeconds: Int,
                                 template: (weightKg: Double, reps: Int)?) {
        guard !exerciseIDs.contains(id) else { return }
        let set = SetDraft(id: UUID(), sourceID: nil, kind: .normal, weightKg: template?.weightKg ?? 0,
                           reps: template?.reps ?? 0, isDone: true, originalCompletedAt: nil)
        exercises.append(ExerciseDraft(id: UUID(), sourceID: nil, exerciseID: id, name: name,
                                       primaryMuscle: primaryMuscle, restSeconds: restSeconds, sets: [set]))
    }

    /// Row number in the Set column: warm-ups don't count (same as the active table).
    func setNumber(of setID: UUID, in exerciseID: UUID) -> Int {
        guard let exercise = exercises.first(where: { $0.id == exerciseID }) else { return 0 }
        var n = 0
        for set in exercise.sets {
            if set.kind != .warmup { n += 1 }
            if set.id == setID { break }
        }
        return n
    }
}

// MARK: - Timeline

/// Where each done set lands in time after an edit. The records rule orders sets by `completedAt`, so the save keeps
/// the timeline consistent: existing times follow the workout's new start and duration, new ones borrow a neighbour's.
enum WorkoutTimeline {

    /// Order-preserving map of `t` from `[oldStart, oldEnd]` onto `[newStart, newEnd]`, clamped into the new range.
    /// Untouched times come back exactly and a pure shift moves every set by the same amount, so ties stay ties.
    static func remap(_ t: Date, oldStart: Date, oldEnd: Date, newStart: Date, newEnd: Date) -> Date {
        if newStart == oldStart && newEnd == oldEnd { return t }
        let oldSpan = oldEnd.timeIntervalSince(oldStart)
        let newSpan = newEnd.timeIntervalSince(newStart)
        let mapped: Date
        if newSpan == oldSpan || oldSpan <= 0 {
            mapped = t.addingTimeInterval(newStart.timeIntervalSince(oldStart))
        } else {
            mapped = newStart.addingTimeInterval(t.timeIntervalSince(oldStart) * newSpan / oldSpan)
        }
        return min(max(mapped, newStart), max(newStart, newEnd))
    }

    /// `completedAt` for every logged row of the draft. A row that had a time gets it remapped. A row without one
    /// (added, or ticked in the editor) takes the nearest earlier timed row of its exercise, else the nearest later
    /// one (ties then resolve by row order, as the records rule does); an exercise with no timed row takes the
    /// latest time of the exercises above it, else the start.
    static func completedTimes(for draft: WorkoutDraft, oldStart: Date, oldEnd: Date) -> [UUID: Date] {
        let newStart = draft.startedAt
        let newEnd = draft.endedAt
        var times: [UUID: Date] = [:]
        var latestAbove: Date?
        for exercise in draft.exercises {
            let anchors: [Date?] = exercise.sets.map { set in
                guard set.isLogged, let original = set.originalCompletedAt else { return nil }
                return remap(original, oldStart: oldStart, oldEnd: oldEnd, newStart: newStart, newEnd: newEnd)
            }
            for (index, set) in exercise.sets.enumerated() where set.isLogged {
                let time = anchors[index]
                    ?? anchors[..<index].compactMap { $0 }.last
                    ?? anchors[(index + 1)...].compactMap { $0 }.first
                    ?? latestAbove
                    ?? newStart
                times[set.id] = time
                latestAbove = max(latestAbove ?? time, time)
            }
        }
        return times
    }
}
