import XCTest
import SwiftData
@testable import NoTomorrow

/// Editing and deleting finished workouts: the draft, the set timeline, the records rebuild, attendance, and the
/// save / delete writers.
@MainActor
final class WorkoutEditTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }
    private let cal = Calendar.current

    override func setUpWithError() throws {
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
    }

    override func tearDown() {
        container = nil
    }

    // MARK: Fixtures

    private func exercise(_ id: String = "Barbell_Bench_Press_-_Medium_Grip") -> Exercise {
        var d = FetchDescriptor<Exercise>(predicate: #Predicate { $0.id == id })
        d.fetchLimit = 1
        if let existing = try? context.fetch(d).first { return existing }
        let exercise = Exercise(id: id, name: id, primaryMuscles: ["chest"])
        context.insert(exercise)
        return exercise
    }

    /// A finished workout of one exercise; each set is (kg, reps) completed one minute apart from the start.
    @discardableResult
    private func finished(_ name: String = "Push A", start: Date, minutes: Double = 60, exercise: Exercise? = nil,
                          sets: [(Double, Int)], kinds: [SetKind]? = nil) -> Workout {
        let workout = Workout(name: name, startedAt: start)
        workout.endedAt = start.addingTimeInterval(minutes * 60)
        context.insert(workout)
        let entry = WorkoutExercise(order: 0, exercise: exercise ?? self.exercise())
        context.insert(entry)
        entry.workout = workout
        for (index, (kg, reps)) in sets.enumerated() {
            let set = SetEntry(order: index, kind: kinds?[index] ?? .normal, weightKg: kg, reps: reps)
            set.completedAt = start.addingTimeInterval(Double(index + 1) * 60)
            context.insert(set)
            set.workoutExercise = entry
        }
        try? context.save()
        return workout
    }

    private func daysAgo(_ n: Int, hour: Int = 18) -> Date {
        let day = cal.date(byAdding: .day, value: -n, to: cal.startOfDay(for: .now)) ?? .now
        return cal.date(byAdding: .hour, value: hour, to: day) ?? day
    }

    private func flags(_ workout: Workout) -> [Bool] {
        workout.sortedExercises.flatMap(\.sortedSets).map(\.isPR)
    }

    private func row(_ id: Int, group: Int = 0, order: Int = 0, at minute: Double?, kind: SetKind = .normal,
                     kg: Double, reps: Int) -> RecordService.RecordRow {
        RecordService.RecordRow(id: id, group: group, order: order,
                                completedAt: minute.map { Date(timeIntervalSinceReferenceDate: $0 * 60) },
                                kind: kind, weightKg: kg, reps: reps)
    }

    // MARK: Records rebuild (pure)

    func testRebuildMatchesTheTickTimeRuleOnAChain() {
        // 80×5, 85×5, 82.5×7 (e1RM 101.75 > 99.17): each one is a PR when ticked in order.
        let rows = [row(1, group: 1, at: 1, kg: 80, reps: 5), row(2, group: 2, at: 2, kg: 85, reps: 5),
                    row(3, group: 3, at: 3, kg: 82.5, reps: 7)]
        var result = RecordService.rebuildFlags(rows)
        XCTAssertEqual([1, 2, 3].map { result[$0]?.isPR }, [true, true, true])

        // The first one edited to 90×5: it stays the first record, the later two no longer beat it.
        var edited = rows
        edited[0].weightKg = 90
        result = RecordService.rebuildFlags(edited)
        XCTAssertEqual([1, 2, 3].map { result[$0]?.isPR }, [true, false, false])

        // Deleting the first: 85×5 becomes the first record, 82.5×7 still beats it by e1RM.
        result = RecordService.rebuildFlags(Array(rows.dropFirst()))
        XCTAssertEqual([2, 3].map { result[$0]?.isPR }, [true, true])
    }

    func testRebuildSetRecordFollowsTheBestRepsAtThatWeight() {
        let rows = [row(1, group: 1, at: 1, kg: 100, reps: 5), row(2, group: 2, at: 2, kg: 60, reps: 10),
                    row(3, group: 3, at: 3, kg: 60, reps: 12)]
        var result = RecordService.rebuildFlags(rows)
        XCTAssertEqual(result[3], RecordService.RecordFlags(isPR: false, isSetRecord: true), "12 > 10 at 60 kg")

        var edited = rows
        edited[1].reps = 12
        result = RecordService.rebuildFlags(edited)
        XCTAssertEqual(result[3], RecordService.RecordFlags(isPR: false, isSetRecord: false), "12 no longer beats 12")
    }

    func testRebuildTiesOnlySeeEarlierRowsOfTheSameExerciseEntry() {
        // Same instant, same WorkoutExercise: the second row sees the first.
        var result = RecordService.rebuildFlags([row(1, group: 1, order: 0, at: 5, kg: 80, reps: 5),
                                                 row(2, group: 1, order: 1, at: 5, kg: 80, reps: 5)])
        XCTAssertEqual(result[1]?.isPR, true)
        XCTAssertEqual(result[2], RecordService.RecordFlags(isPR: false, isSetRecord: false))

        // Same instant in different entries: neither sees the other, both are first records.
        result = RecordService.rebuildFlags([row(1, group: 1, order: 0, at: 5, kg: 80, reps: 5),
                                             row(2, group: 2, order: 1, at: 5, kg: 80, reps: 5)])
        XCTAssertEqual(result[1]?.isPR, true)
        XCTAssertEqual(result[2]?.isPR, true)
    }

    func testRebuildNeverFlagsWarmupsEmptyOrOpenSets() {
        let result = RecordService.rebuildFlags([row(1, at: 1, kind: .warmup, kg: 200, reps: 5),
                                                 row(2, order: 1, at: 2, kg: 100, reps: 0),
                                                 row(3, order: 2, at: nil, kg: 300, reps: 5),
                                                 row(4, order: 3, at: 3, kg: 60, reps: 5)])
        XCTAssertEqual(result[1], RecordService.RecordFlags(isPR: false, isSetRecord: false))
        XCTAssertEqual(result[2], RecordService.RecordFlags(isPR: false, isSetRecord: false))
        XCTAssertEqual(result[3], RecordService.RecordFlags(isPR: false, isSetRecord: false))
        XCTAssertEqual(result[4]?.isPR, true, "warm-ups and 0-rep sets are no bar to beat")
    }

    func testRebuildRepairsAStaleWarmupFlag() {
        let w = finished(start: daysAgo(3), sets: [(60, 5), (80, 5)], kinds: [.warmup, .normal])
        let warmup = w.sortedExercises[0].sortedSets[0]
        warmup.isPR = true

        RecordService.rebuild(exerciseIDs: [exercise().id], in: context)

        XCTAssertEqual(flags(w), [false, true])
    }

    // MARK: Timeline

    func testRemapIdentityShiftAndScale() {
        let start = Date(timeIntervalSinceReferenceDate: 1_000_000)
        let end = start.addingTimeInterval(3600)
        let t = start.addingTimeInterval(600.123)
        XCTAssertEqual(WorkoutTimeline.remap(t, oldStart: start, oldEnd: end, newStart: start, newEnd: end), t)

        let day = 86_400.0
        let shifted = WorkoutTimeline.remap(t, oldStart: start, oldEnd: end,
                                            newStart: start.addingTimeInterval(-day), newEnd: end.addingTimeInterval(-day))
        XCTAssertEqual(shifted.timeIntervalSince(start.addingTimeInterval(-day)), 600.123, accuracy: 1e-6)

        let halved = WorkoutTimeline.remap(t, oldStart: start, oldEnd: end,
                                           newStart: start, newEnd: start.addingTimeInterval(1800))
        XCTAssertEqual(halved.timeIntervalSince(start), 300.0615, accuracy: 1e-6)

        let late = end.addingTimeInterval(120)
        XCTAssertEqual(WorkoutTimeline.remap(late, oldStart: start, oldEnd: end,
                                             newStart: start, newEnd: start.addingTimeInterval(1800)),
                       start.addingTimeInterval(1800), "clamped into the new range")
    }

    func testRemapKeepsTies() {
        let start = Date(timeIntervalSinceReferenceDate: 5_000_000)
        let end = start.addingTimeInterval(3000)
        let tie = start.addingTimeInterval(1234.5678)
        let a = WorkoutTimeline.remap(tie, oldStart: start, oldEnd: end,
                                      newStart: start.addingTimeInterval(-7777), newEnd: end.addingTimeInterval(-9999))
        let b = WorkoutTimeline.remap(tie, oldStart: start, oldEnd: end,
                                      newStart: start.addingTimeInterval(-7777), newEnd: end.addingTimeInterval(-9999))
        XCTAssertEqual(a, b)
    }

    func testNewRowsBorrowANeighboursTime() {
        let start = Date(timeIntervalSinceReferenceDate: 2_000_000)
        func set(_ minute: Double?, done: Bool = true) -> SetDraft {
            SetDraft(id: UUID(), sourceID: nil, kind: .normal, weightKg: 50, reps: 5, isDone: done,
                     originalCompletedAt: minute.map { start.addingTimeInterval($0 * 60) })
        }
        let first = [set(nil), set(10), set(12), set(nil)]
        let second = [set(nil), set(nil)]
        let draft = WorkoutDraft(name: "A", notes: "", startedAt: start, duration: 3600, exercises: [
            ExerciseDraft(id: UUID(), sourceID: nil, exerciseID: "a", name: "A", primaryMuscle: nil, restSeconds: 90, sets: first),
            ExerciseDraft(id: UUID(), sourceID: nil, exerciseID: "b", name: "B", primaryMuscle: nil, restSeconds: 90, sets: second),
        ])

        let times = WorkoutTimeline.completedTimes(for: draft, oldStart: start, oldEnd: start.addingTimeInterval(3600))

        XCTAssertEqual(times[first[0].id], start.addingTimeInterval(600), "no earlier row: the next timed one")
        XCTAssertEqual(times[first[3].id], start.addingTimeInterval(720), "appended: the last timed row")
        XCTAssertEqual(times[second[0].id], start.addingTimeInterval(720), "new exercise: latest time above it")
        XCTAssertEqual(times[second[1].id], start.addingTimeInterval(720))

        let lonely = WorkoutDraft(name: "A", notes: "", startedAt: start, duration: 3600, exercises: [
            ExerciseDraft(id: UUID(), sourceID: nil, exerciseID: "a", name: "A", primaryMuscle: nil, restSeconds: 90,
                          sets: [set(nil), set(nil, done: false)]),
        ])
        let lonelyTimes = WorkoutTimeline.completedTimes(for: lonely, oldStart: start, oldEnd: start)
        XCTAssertEqual(lonelyTimes[lonely.exercises[0].sets[0].id], start, "nothing timed at all: the start")
        XCTAssertNil(lonelyTimes[lonely.exercises[0].sets[1].id], "open rows get no time")
    }

    // MARK: Draft

    func testDraftDurationSnapsToFiveMinutes() {
        var draft = WorkoutDraft(name: "A", notes: "", startedAt: .now, duration: 52 * 60 + 37, exercises: [])
        draft.stepDuration(by: 1)
        XCTAssertEqual(draft.duration, 55 * 60)
        draft.stepDuration(by: -1)
        XCTAssertEqual(draft.duration, 50 * 60)

        draft.duration = 3 * 60
        XCTAssertFalse(draft.canShorten)
        draft.stepDuration(by: 1)
        XCTAssertEqual(draft.duration, 5 * 60)

        draft.duration = 800 * 60
        XCTAssertFalse(draft.canLengthen)
        draft.stepDuration(by: -1)
        XCTAssertEqual(draft.duration, 720 * 60, "never above 12 h")
    }

    func testDraftDayAndTimeKeepTheRest() {
        let start = daysAgo(2, hour: 18).addingTimeInterval(37)   // 18:00:37
        var draft = WorkoutDraft(name: "A", notes: "", startedAt: start, duration: 3600, exercises: [])

        draft.setDay(daysAgo(5, hour: 9))
        XCTAssertEqual(cal.component(.hour, from: draft.startedAt), 18)
        XCTAssertEqual(cal.component(.second, from: draft.startedAt), 37)
        XCTAssertTrue(cal.isDate(draft.startedAt, inSameDayAs: daysAgo(5)))

        draft.setDay(start)
        XCTAssertEqual(draft.startedAt, start, "back to the same day gives back the exact start")

        draft.setTime(daysAgo(0, hour: 7).addingTimeInterval(15 * 60))
        XCTAssertEqual(cal.component(.hour, from: draft.startedAt), 7)
        XCTAssertEqual(cal.component(.minute, from: draft.startedAt), 15)
        XCTAssertTrue(cal.isDate(draft.startedAt, inSameDayAs: start), "the time picker never moves the day")
    }

    func testDraftTimeValidity() {
        let now = Date.now
        var draft = WorkoutDraft(name: "A", notes: "", startedAt: now.addingTimeInterval(-3600), duration: 3600, exercises: [])
        XCTAssertTrue(draft.isTimeValid(now: now))
        draft.duration = 3700
        XCTAssertFalse(draft.isTimeValid(now: now), "ends in the future")
        draft.startedAt = now.addingTimeInterval(60)
        draft.duration = 300
        XCTAssertFalse(draft.isTimeValid(now: now))
    }

    func testDraftRowsNeverLogZeroReps() {
        let w = finished(start: daysAgo(1), sets: [(60, 8)], kinds: [.warmup])
        let model = WorkoutEditModel(workout: w, unit: .kg)
        let exerciseID = model.draft.exercises[0].id

        model.draft.addSet(to: exerciseID)
        let added = model.draft.exercises[0].sets[1]
        XCTAssertEqual(added.kind, .normal, "a warm-up is copied as a normal set")
        XCTAssertEqual(added.weightKg, 60)
        XCTAssertTrue(added.isLogged, "added rows start done")
        XCTAssertTrue(model.isDirty)

        model.setReps("", for: added.id, in: exerciseID)
        XCTAssertFalse(model.draft.exercises[0].sets[1].isLogged, "no reps, no ✓")
        XCTAssertFalse(model.draft.toggleDone(added.id, in: exerciseID), "an empty row can't be ticked")
        model.setReps("10", for: added.id, in: exerciseID)
        XCTAssertTrue(model.draft.exercises[0].sets[1].isLogged, "the tick survives retyping the reps")

        XCTAssertTrue(model.draft.toggleDone(added.id, in: exerciseID))
        XCTAssertFalse(model.draft.exercises[0].sets[1].isDone)
    }

    func testRenamingKeepsALegacyZeroRepSetAndTheDay() {
        context.insert(GymSchedule(weekdays: [1, 2, 3, 4, 5, 6, 7]))
        let start = daysAgo(3)
        // Logged before the no-"0 × 0" rule: its only completed set has no reps.
        let w = finished(start: start, sets: [(80, 0)])
        AttendanceService.markAttended(day: start, context: context)
        XCTAssertEqual(w.completedSetCount, 1)

        let model = WorkoutEditModel(workout: w, unit: .kg)
        XCTAssertTrue(model.draft.exercises[0].sets[0].isLogged, "shown ✓ as it was saved")
        model.draft.name = "Push B"
        model.save(to: w, context: context)

        XCTAssertEqual(w.name, "Push B")
        XCTAssertEqual(w.completedSetCount, 1, "a rename does not un-log it")
        XCTAssertEqual(w.sortedExercises[0].sortedSets[0].completedAt, start.addingTimeInterval(60))
        XCTAssertEqual(AttendanceService.record(for: start, participant: .me, context: context)?.status, .attended)
    }

    func testEditingALegacyZeroRepSetAppliesTheRule() {
        let w = finished(start: daysAgo(3), sets: [(80, 0)])
        let model = WorkoutEditModel(workout: w, unit: .kg)
        let exercise = model.draft.exercises[0]
        let row = exercise.sets[0].id

        XCTAssertTrue(model.draft.toggleDone(row, in: exercise.id))
        XCTAssertFalse(model.draft.exercises[0].sets[0].isLogged)
        XCTAssertTrue(model.draft.toggleDone(row, in: exercise.id), "a mistaken untick can be undone")
        XCTAssertTrue(model.draft.exercises[0].sets[0].isLogged)

        model.setWeight("85", for: row, in: exercise.id)
        XCTAssertFalse(model.draft.exercises[0].sets[0].isLogged, "a changed row needs reps like any other")
        XCTAssertFalse(model.draft.toggleDone(row, in: exercise.id))
        model.save(to: w, context: context)
        XCTAssertEqual(w.completedSetCount, 0)
    }

    func testDraftMoveAndRemoveExercises() {
        var draft = WorkoutDraft(name: "A", notes: "", startedAt: .now, duration: 60, exercises: [])
        for id in ["a", "b", "c"] {
            draft.appendExercise(id: id, name: id, primaryMuscle: nil, restSeconds: 90, template: (40, 10))
        }
        draft.appendExercise(id: "a", name: "a", primaryMuscle: nil, restSeconds: 90, template: nil)
        XCTAssertEqual(draft.exercises.map(\.exerciseID), ["a", "b", "c"], "no duplicates")

        draft.moveExercise(draft.exercises[2].id, by: -1)
        XCTAssertEqual(draft.exercises.map(\.exerciseID), ["a", "c", "b"])
        draft.moveExercise(draft.exercises[0].id, by: -1)
        XCTAssertEqual(draft.exercises.map(\.exerciseID), ["a", "c", "b"], "the first can't move up")
        draft.removeExercise(draft.exercises[1].id)
        XCTAssertEqual(draft.exercises.map(\.exerciseID), ["a", "b"])
        XCTAssertEqual(draft.exercises[0].sets.map(\.reps), [10])
        XCTAssertTrue(draft.exercises[0].sets[0].isLogged)
    }

    // MARK: Save

    func testSaveWritesTheDraftBack() throws {
        let start = daysAgo(2)
        let w = finished(start: start, minutes: 60, sets: [(80, 5), (80, 5), (80, 4)])
        let model = WorkoutEditModel(workout: w, unit: .kg)
        let ex = model.draft.exercises[0]

        model.draft.name = "   "
        model.draft.notes = " Felt strong \n"
        model.draft.deleteSet(ex.sets[1].id, in: ex.id)
        XCTAssertTrue(model.draft.toggleDone(ex.sets[2].id, in: ex.id), "untick the last set")
        model.draft.duration = 45 * 60
        model.save(to: w, context: context)

        XCTAssertEqual(w.name, "Push A", "an empty name keeps the old one")
        XCTAssertEqual(w.notes, "Felt strong")
        XCTAssertEqual(w.endedAt, start.addingTimeInterval(45 * 60))
        let sets = w.sortedExercises[0].sortedSets
        XCTAssertEqual(sets.map(\.order), [0, 1], "rows re-indexed without gaps")
        XCTAssertNotNil(sets[0].completedAt)
        XCTAssertNil(sets[1].completedAt)
        XCTAssertFalse(sets[1].isPR)
        XCTAssertEqual(sets[0].completedAt, start.addingTimeInterval(45), "60 → 45 min scales the offsets")
        XCTAssertEqual(try context.fetch(FetchDescriptor<SetEntry>()).count, 2)
    }

    func testUntouchedSaveKeepsTimesExactly() {
        let w = finished(start: daysAgo(1), minutes: 52.6, sets: [(80, 5), (82.5, 5)])
        let before = w.sortedExercises[0].sortedSets.map(\.completedAt)
        let model = WorkoutEditModel(workout: w, unit: .kg)
        model.draft.name = "Push B"

        model.save(to: w, context: context)

        XCTAssertEqual(w.sortedExercises[0].sortedSets.map(\.completedAt), before)
        XCTAssertEqual(w.name, "Push B")
    }

    func testEditingAnOldSetRebuildsLaterRecords() {
        let w1 = finished(start: daysAgo(3), sets: [(80, 5)])
        let w2 = finished(start: daysAgo(2), sets: [(85, 5)])
        let w3 = finished(start: daysAgo(1), sets: [(82.5, 7)])
        RecordService.rebuild(exerciseIDs: [exercise().id], in: context)
        XCTAssertEqual([w1, w2, w3].flatMap(flags), [true, true, true])

        let model = WorkoutEditModel(workout: w1, unit: .kg)
        let ex = model.draft.exercises[0]
        model.setWeight("90", for: ex.sets[0].id, in: ex.id)
        model.save(to: w1, context: context)

        XCTAssertEqual([w1, w2, w3].flatMap(flags), [true, false, false])
    }

    func testMovingAWorkoutEarlierReordersRecords() {
        let w1 = finished(start: daysAgo(4), sets: [(80, 5)])
        let w2 = finished(start: daysAgo(2), sets: [(100, 5)])
        RecordService.rebuild(exerciseIDs: [exercise().id], in: context)
        XCTAssertEqual([w1, w2].flatMap(flags), [true, true])

        let model = WorkoutEditModel(workout: w2, unit: .kg)
        model.draft.setDay(daysAgo(6))
        model.save(to: w2, context: context)

        XCTAssertEqual(flags(w2), [true], "now the first record")
        XCTAssertEqual(flags(w1), [false], "80 no longer beats 100")
        XCTAssertTrue(cal.isDate(w2.sortedExercises[0].sortedSets[0].completedAt ?? .distantPast, inSameDayAs: daysAgo(6)),
                      "set times move with the workout")
    }

    func testAddedExerciseGetsOneLoggedRowFromLastTime() {
        let squat = exercise("Barbell_Squat")
        finished("Legs", start: daysAgo(5), exercise: squat, sets: [(100, 5), (105, 3)])
        let w = finished(start: daysAgo(1), sets: [(80, 5)])
        let model = WorkoutEditModel(workout: w, unit: .kg)

        model.append([squat], editing: w, context: context)
        let ex = model.draft.exercises[1]
        model.draft.moveExercise(ex.id, by: -1)
        model.save(to: w, context: context)

        let entries = w.sortedExercises
        XCTAssertEqual(entries.map { $0.exercise?.id }, ["Barbell_Squat", exercise().id])
        let added = entries[0].sortedSets
        XCTAssertEqual(added.map(\.weightKg), [100])
        XCTAssertEqual(added.map(\.reps), [5])
        XCTAssertNotNil(added[0].completedAt)
        XCTAssertEqual(entries[0].restSeconds, 120, "heavy compound rest")
    }

    func testRemovingAnExerciseDeletesItsSets() throws {
        let w = finished(start: daysAgo(1), sets: [(80, 5), (80, 5)])
        let model = WorkoutEditModel(workout: w, unit: .kg)
        model.draft.removeExercise(model.draft.exercises[0].id)

        model.save(to: w, context: context)

        XCTAssertTrue(w.exercises.isEmpty)
        XCTAssertEqual(try context.fetch(FetchDescriptor<SetEntry>()).count, 0)
        XCTAssertEqual(try context.fetch(FetchDescriptor<WorkoutExercise>()).count, 0)
    }

    // MARK: Delete

    func testDeleteRebuildsRecordsAndPostsTheChange() throws {
        let w1 = finished(start: daysAgo(3), sets: [(90, 5)])
        let w2 = finished(start: daysAgo(2), sets: [(85, 5)])
        let w3 = finished(start: daysAgo(1), sets: [(82.5, 7)])
        RecordService.rebuild(exerciseIDs: [exercise().id], in: context)
        XCTAssertEqual([w1, w2, w3].flatMap(flags), [true, false, false])
        let posted = expectation(forNotification: .workoutHistoryDidChange, object: nil)

        WorkoutEditor.delete(w1, in: context)

        wait(for: [posted], timeout: 1)
        XCTAssertEqual([w2, w3].flatMap(flags), [true, true])
        XCTAssertEqual(try context.fetch(FetchDescriptor<Workout>()).count, 2)
        XCTAssertEqual(try context.fetch(FetchDescriptor<SetEntry>()).count, 2)
    }

    // MARK: Attendance

    func testAttendanceDecisions() {
        let today = daysAgo(0, hour: 12)
        let mon = daysAgo(3), tue = daysAgo(2)
        let everyDay: (Date) -> Bool = { _ in true }
        func status(_ map: [Date: AttendanceStatus]) -> (Date) -> AttendanceStatus? {
            { day in map[self.cal.startOfDay(for: day)] }
        }
        let attendedMon = status([cal.startOfDay(for: mon): .attended])

        XCTAssertEqual(AttendanceService.workoutDayChanges(oldDay: mon, newDay: tue, oldDayStillAttended: false,
                                                           isGymDay: everyDay, myStatus: attendedMon, today: today),
                       [.markAttended(cal.startOfDay(for: tue)), .markMissed(cal.startOfDay(for: mon))])

        XCTAssertEqual(AttendanceService.workoutDayChanges(oldDay: mon, newDay: mon, oldDayStillAttended: false,
                                                           isGymDay: everyDay, myStatus: attendedMon, today: today),
                       [], "same day: nothing to do")

        XCTAssertEqual(AttendanceService.workoutDayChanges(oldDay: mon, newDay: nil, oldDayStillAttended: true,
                                                           isGymDay: everyDay, myStatus: attendedMon, today: today),
                       [], "another workout still counts for that day")

        let cancelledMon = status([cal.startOfDay(for: mon): .cancelled])
        XCTAssertEqual(AttendanceService.workoutDayChanges(oldDay: mon, newDay: nil, oldDayStillAttended: false,
                                                           isGymDay: everyDay, myStatus: cancelledMon, today: today),
                       [], "only attended is ever reverted")

        let attendedToday = status([cal.startOfDay(for: today): .attended])
        XCTAssertEqual(AttendanceService.workoutDayChanges(oldDay: today, newDay: daysAgo(1), oldDayStillAttended: false,
                                                           isGymDay: everyDay, myStatus: attendedToday, today: today),
                       [.markAttended(cal.startOfDay(for: daysAgo(1))), .clear(cal.startOfDay(for: today))],
                       "today goes back to the schedule rather than missed")

        XCTAssertEqual(AttendanceService.workoutDayChanges(oldDay: mon, newDay: tue, oldDayStillAttended: false,
                                                           isGymDay: { _ in false }, myStatus: attendedMon, today: today),
                       [.markAttended(cal.startOfDay(for: tue)), .clear(cal.startOfDay(for: mon))],
                       "rest days: a workout still counts there (Finish's rule), the old record is dropped")

        XCTAssertEqual(AttendanceService.workoutDayChanges(oldDay: nil, newDay: daysAgo(-1), oldDayStillAttended: false,
                                                           isGymDay: everyDay, myStatus: { _ in nil }, today: today),
                       [], "never attended in the future")
    }

    func testMakeUpDayDecisions() {
        let today = daysAgo(0, hour: 12)
        let restDays: (Date) -> Bool = { _ in false }
        let attended: (Date) -> AttendanceStatus? = { _ in .attended }
        let makeUp: (Date) -> Bool = { _ in true }

        XCTAssertEqual(AttendanceService.workoutDayChanges(oldDay: today, newDay: nil, oldDayStillAttended: false,
                                                           isGymDay: restDays, myStatus: attended, isMakeUpDay: makeUp,
                                                           today: today),
                       [.markPlanned(cal.startOfDay(for: today))], "today's make-up is planned again")

        XCTAssertEqual(AttendanceService.workoutDayChanges(oldDay: daysAgo(3), newDay: nil, oldDayStillAttended: false,
                                                           isGymDay: restDays, myStatus: attended, isMakeUpDay: makeUp,
                                                           today: today),
                       [.markMissed(cal.startOfDay(for: daysAgo(3)))], "a past make-up day is missed, as the sweep says")

        XCTAssertEqual(AttendanceService.workoutDayChanges(oldDay: daysAgo(3), newDay: nil, oldDayStillAttended: true,
                                                           isGymDay: restDays, myStatus: attended, isMakeUpDay: makeUp,
                                                           today: today),
                       [], "another workout still counts for it")
    }

    func testMovingAWorkoutMovesAttendance() {
        let schedule = GymSchedule(weekdays: [1, 2, 3, 4, 5, 6, 7])
        context.insert(schedule)
        let oldStart = daysAgo(3)
        let w = finished(start: oldStart, sets: [(80, 5)])
        AttendanceService.markAttended(day: oldStart, context: context)

        let model = WorkoutEditModel(workout: w, unit: .kg)
        model.draft.setDay(daysAgo(2))
        model.save(to: w, context: context)

        XCTAssertEqual(AttendanceService.record(for: oldStart, participant: .me, context: context)?.status, .missed)
        XCTAssertEqual(AttendanceService.record(for: daysAgo(2), participant: .me, context: context)?.status, .attended)
    }

    func testMovingTodaysWorkoutToYesterdayClearsToday() {
        context.insert(GymSchedule(weekdays: [1, 2, 3, 4, 5, 6, 7]))
        let start = cal.startOfDay(for: .now).addingTimeInterval(1)
        let w = finished(start: start, minutes: 0.5, sets: [(80, 5)])
        AttendanceService.markAttended(day: start, context: context)

        let model = WorkoutEditModel(workout: w, unit: .kg)
        model.draft.setDay(daysAgo(1))
        model.save(to: w, context: context)

        XCTAssertNil(AttendanceService.record(for: .now, participant: .me, context: context),
                     "today derives from the schedule again")
        XCTAssertEqual(AttendanceService.record(for: daysAgo(1), participant: .me, context: context)?.status, .attended)
    }

    func testDeletingOneOfTwoWorkoutsKeepsTheDayAttended() {
        context.insert(GymSchedule(weekdays: [1, 2, 3, 4, 5, 6, 7]))
        let morning = finished(start: daysAgo(2, hour: 8), sets: [(80, 5)])
        finished(start: daysAgo(2, hour: 18), sets: [(82.5, 5)])
        AttendanceService.markAttended(day: daysAgo(2), context: context)

        WorkoutEditor.delete(morning, in: context)

        XCTAssertEqual(AttendanceService.record(for: daysAgo(2), participant: .me, context: context)?.status, .attended)
    }
}
