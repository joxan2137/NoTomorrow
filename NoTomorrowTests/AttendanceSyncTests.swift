import XCTest
import SwiftData
@testable import NoTomorrow

/// Attendance that reaches the backend (Finish, workout edits, the outbox) and the local rules around it: any day
/// counts from the workout's start, make-up days are planned, and the sweep judges each day once.
@MainActor
final class AttendanceSyncTests: XCTestCase {
    private struct Report: Equatable {
        var day: Date
        var status: AttendanceStatus
    }

    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }
    private var defaults: UserDefaults!
    private var suiteName = ""
    private var reports: [Report] = []
    private var savedReporter: AttendanceSync.Reporter?
    private let cal = Calendar.current

    override func setUpWithError() throws {
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
        suiteName = "AttendanceSyncTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        reports = []
        savedReporter = AttendanceSync.reporter
        AttendanceSync.reporter = { [weak self] day, status in
            self?.reports.append(Report(day: Calendar.current.startOfDay(for: day), status: status))
        }
    }

    override func tearDown() {
        if let savedReporter { AttendanceSync.reporter = savedReporter }
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        container = nil
    }

    // MARK: Fixtures

    private var todayStart: Date { cal.startOfDay(for: .now) }

    private func day(_ offset: Int) -> Date {
        cal.date(byAdding: .day, value: offset, to: todayStart)!
    }

    private func at(_ offset: Int, hour: Int, minute: Int = 0) -> Date {
        cal.date(byAdding: .minute, value: hour * 60 + minute, to: day(offset))!
    }

    /// A workout with one exercise and one set per entry of `done`.
    private func workout(start: Date, done: [Bool], finishedAfter minutes: Double? = nil) -> Workout {
        let workout = Workout(name: "Push A", startedAt: start)
        if let minutes { workout.endedAt = start.addingTimeInterval(minutes * 60) }
        context.insert(workout)
        let exercise = Exercise(id: "ex-\(UUID().uuidString)", name: "Bench", primaryMuscles: ["chest"])
        context.insert(exercise)
        let entry = WorkoutExercise(order: 0, exercise: exercise)
        context.insert(entry)
        entry.workout = workout
        for (index, isDone) in done.enumerated() {
            let set = SetEntry(order: index, weightKg: 80, reps: 5)
            set.completedAt = isDone ? start.addingTimeInterval(Double(index + 1) * 60) : nil
            context.insert(set)
            set.workoutExercise = entry
        }
        try? context.save()
        return workout
    }

    private func status(on day: Date) -> AttendanceStatus? {
        AttendanceService.record(for: day, participant: .me, context: context)?.status
    }

    private func myRecords() -> [AttendanceRecord] {
        ((try? context.fetch(FetchDescriptor<AttendanceRecord>(sortBy: [SortDescriptor(\.day)]))) ?? [])
            .filter { $0.participant == .me }
    }

    // MARK: Finish (ios-correctness-6, ios-correctness-2)

    func testFinishOnARestDayCountsTheStartDayAndReportsIt() {
        context.insert(GymSchedule(weekdays: []))
        let start = at(-1, hour: 22, minute: 30)
        let w = workout(start: start, done: [true, false])

        ActiveWorkoutModel(workout: w, context: context).finish(now: at(0, hour: 0, minute: 20))

        XCTAssertEqual(status(on: day(-1)), .attended, "no schedule check; the day is the start's, not the finish's")
        XCTAssertNil(status(on: day(0)))
        XCTAssertEqual(reports, [Report(day: day(-1), status: .attended)])
    }

    func testFinishWithNothingDoneMarksAndReportsNothing() {
        context.insert(GymSchedule(weekdays: [1, 2, 3, 4, 5, 6, 7]))
        let w = workout(start: .now.addingTimeInterval(-600), done: [false])

        ActiveWorkoutModel(workout: w, context: context).finish()

        XCTAssertTrue(myRecords().isEmpty)
        XCTAssertTrue(reports.isEmpty)
    }

    /// Finished with a set done (the day counts), then "Edit sets" and every set unticked.
    private func reopenedAndEmptied(start: Date) -> (Workout, ActiveWorkoutModel) {
        let w = workout(start: start, done: [true])
        let model = ActiveWorkoutModel(workout: w, context: context)
        model.finish(now: start.addingTimeInterval(30))
        model.reopen()
        for set in w.exercises.flatMap(\.sets) { model.uncomplete(set) }
        return (w, model)
    }

    func testEditSetsThenFinishingWithNothingDoneRevertsTheDay() {
        context.insert(GymSchedule(weekdays: [1, 2, 3, 4, 5, 6, 7]))
        let start = todayStart.addingTimeInterval(1)
        let (w, model) = reopenedAndEmptied(start: start)
        XCTAssertEqual(status(on: start), .attended, "the first Finish counted the day")

        model.finish(now: start.addingTimeInterval(40))

        XCTAssertEqual(w.completedSetCount, 0)
        XCTAssertNil(status(on: start), "today derives from the schedule again")
        XCTAssertEqual(reports, [Report(day: cal.startOfDay(for: start), status: .attended),
                                 Report(day: cal.startOfDay(for: start), status: .planned)],
                       "the server hears the day is planned again")
    }

    func testEditSetsThenDiscardingRevertsTheDay() async {
        context.insert(GymSchedule(weekdays: [1, 2, 3, 4, 5, 6, 7]))
        let session = WorkoutSessionController(defaults: defaults)
        let start = todayStart.addingTimeInterval(1)
        let w = workout(start: start, done: [true])
        session.begin(w)
        let model = session.model(for: w, context: context)
        model.finish(now: start.addingTimeInterval(30))
        model.reopen()
        for set in w.exercises.flatMap(\.sets) { model.uncomplete(set) }
        XCTAssertEqual(status(on: start), .attended)

        let deletion = model.discard(session: session)

        XCTAssertNil(status(on: start), "reverted at once, not when the row goes")
        XCTAssertEqual(reports.last, Report(day: cal.startOfDay(for: start), status: .planned))
        XCTAssertNil(session.activeWorkoutID)
        await deletion.value
        XCTAssertNil(status(on: start))
    }

    func testDiscardingAnEmptyWorkoutKeepsADayAnotherWorkoutCounted() async {
        context.insert(GymSchedule(weekdays: [1, 2, 3, 4, 5, 6, 7]))
        let earlier = workout(start: todayStart.addingTimeInterval(1), done: [true], finishedAfter: 0.5)
        AttendanceService.markAttended(day: earlier.startedAt, context: context)
        let session = WorkoutSessionController(defaults: defaults)
        let empty = workout(start: todayStart.addingTimeInterval(60), done: [false])
        session.begin(empty)

        await session.model(for: empty, context: context).discard(session: session).value
        ActiveWorkoutModel(workout: workout(start: todayStart.addingTimeInterval(90), done: [false]), context: context)
            .finish()

        XCTAssertEqual(status(on: todayStart), .attended, "the earlier workout still counts for the day")
        XCTAssertTrue(reports.isEmpty)
    }

    // MARK: Edits report what they moved

    func testMovingAWorkoutReportsBothDays() {
        context.insert(GymSchedule(weekdays: [1, 2, 3, 4, 5, 6, 7]))
        let w = workout(start: at(-3, hour: 18), done: [true], finishedAfter: 60)
        AttendanceService.markAttended(day: w.startedAt, context: context)

        let model = WorkoutEditModel(workout: w, unit: .kg)
        model.draft.setDay(day(-2))
        model.save(to: w, context: context)

        XCTAssertEqual(reports, [Report(day: day(-2), status: .attended), Report(day: day(-3), status: .missed)])
    }

    func testMovingTodaysWorkoutAwayPlansTodayAgainOnTheServer() {
        context.insert(GymSchedule(weekdays: [1, 2, 3, 4, 5, 6, 7]))
        let w = workout(start: todayStart.addingTimeInterval(1), done: [true], finishedAfter: 0.5)
        AttendanceService.markAttended(day: w.startedAt, context: context)

        let model = WorkoutEditModel(workout: w, unit: .kg)
        model.draft.setDay(day(-1))
        model.save(to: w, context: context)

        XCTAssertEqual(reports, [Report(day: day(-1), status: .attended), Report(day: day(0), status: .planned)])
    }

    func testDeletingAWorkoutReportsTheMiss() {
        context.insert(GymSchedule(weekdays: [1, 2, 3, 4, 5, 6, 7]))
        let w = workout(start: at(-2, hour: 18), done: [true], finishedAfter: 60)
        AttendanceService.markAttended(day: w.startedAt, context: context)

        WorkoutEditor.delete(w, in: context)

        XCTAssertEqual(reports, [Report(day: day(-2), status: .missed)])
    }

    // MARK: Make-up days

    /// Can't make it on `gymDay` with `makeUp` picked, then a workout finished on the make-up day.
    private func trainedOnMakeUpDay(gymDay: Date, makeUp: Date, start: Date) -> Workout {
        context.insert(GymSchedule(weekdays: [cal.isoWeekday(for: gymDay)]))
        AttendanceService.markMissed(day: gymDay, reason: "work", note: nil, makeUp: makeUp, context: context)
        AttendanceService.markPlanned(day: makeUp, context: context)
        let w = workout(start: start, done: [true], finishedAfter: 0.5)
        AttendanceService.markAttended(day: w.startedAt, context: context)
        return w
    }

    func testDeletingTodaysMakeUpWorkoutPlansTheMakeUpAgain() {
        let w = trainedOnMakeUpDay(gymDay: day(-1), makeUp: day(0), start: todayStart.addingTimeInterval(1))

        WorkoutEditor.delete(w, in: context)

        XCTAssertEqual(AttendanceService.record(for: day(0), participant: .me, context: context)?.status, .planned,
                       "the make-up plan survives, not a plain rest day")
        XCTAssertEqual(AttendanceService.record(for: day(-1), participant: .me, context: context)?.status, .cancelled)
        XCTAssertEqual(reports, [Report(day: day(0), status: .planned)])
    }

    func testMovingAPastMakeUpWorkoutMarksTheMakeUpMissed() {
        let w = trainedOnMakeUpDay(gymDay: day(-4), makeUp: day(-3), start: at(-3, hour: 18))

        let model = WorkoutEditModel(workout: w, unit: .kg)
        model.draft.setDay(day(-2))
        model.save(to: w, context: context)

        XCTAssertEqual(AttendanceService.record(for: day(-3), participant: .me, context: context)?.status, .missed,
                       "what the sweep writes for a make-up day that passed untrained")
        XCTAssertEqual(AttendanceService.record(for: day(-2), participant: .me, context: context)?.status, .attended)
        XCTAssertEqual(reports, [Report(day: day(-2), status: .attended), Report(day: day(-3), status: .missed)])
    }

    func testIsMakeUpDayOnlyReadsMyCancellations() {
        AttendanceService.upsert(day: day(-2), participant: .partner, context: context).makeUpDay = day(1)
        XCTAssertFalse(AttendanceService.isMakeUpDay(day(1), context: context))
        AttendanceService.markMissed(day: day(-1), reason: nil, note: nil, makeUp: at(1, hour: 9), context: context)
        XCTAssertTrue(AttendanceService.isMakeUpDay(at(1, hour: 20), context: context))
    }

    // MARK: Can't make it after training

    func testCantMakeItNeverCancelsATrainedDay() {
        AttendanceService.markAttended(day: day(0), context: context)

        let record = AttendanceService.markMissed(day: day(0), reason: "tired", note: "later", makeUp: day(2), context: context)

        XCTAssertEqual(record.status, .attended)
        XCTAssertNil(record.reason)
        XCTAssertNil(record.makeUpDay)
        // A workout edit's silent miss is still allowed to revert it.
        AttendanceService.markMissed(day: day(0), reason: nil, note: nil, makeUp: nil, status: .missed, context: context)
        XCTAssertEqual(AttendanceService.record(for: day(0), participant: .me, context: context)?.status, .missed)
    }

    func testBroCantMakeItLeavesATrainedDayAlone() async {
        let client = SpyBackendClient()
        let bro = BroService(client: client, attendanceOutbox: AttendanceOutbox(defaults: defaults), syncTarget: { .remote("me") })
        AttendanceService.markAttended(day: day(0), context: context)

        await bro.cantMakeIt(reason: "tired", note: nil, makeUpDay: nil, sessionDay: day(0), in: context)

        let calls = await client.attendanceCalls
        XCTAssertTrue(calls.isEmpty, "no cancellation reaches the server or the partner")
        XCTAssertEqual(AttendanceService.record(for: day(0), participant: .me, context: context)?.status, .attended)
    }

    func testCantMakeItIsOfferedOnlyBeforeTraining() {
        XCTAssertTrue(NextSessionCard.offersCantMakeIt(sessionIsToday: true, myState: .planned, trainedToday: false))
        XCTAssertTrue(NextSessionCard.offersCantMakeIt(sessionIsToday: true, myState: .confirmed, trainedToday: false))
        XCTAssertFalse(NextSessionCard.offersCantMakeIt(sessionIsToday: true, myState: .attended, trainedToday: false))
        XCTAssertFalse(NextSessionCard.offersCantMakeIt(sessionIsToday: true, myState: .planned, trainedToday: true),
                       "a finished workout today hides it even before the record says attended")
        XCTAssertFalse(NextSessionCard.offersCantMakeIt(sessionIsToday: true, myState: .cancelled(reason: nil), trainedToday: false))
        XCTAssertFalse(NextSessionCard.offersCantMakeIt(sessionIsToday: false, myState: .planned, trainedToday: false))
    }

    func testWireStatus() {
        let d = day(-1)
        let gym: (Date) -> Bool = { _ in true }
        let rest: (Date) -> Bool = { _ in false }
        XCTAssertEqual(AttendanceService.wireStatus(for: .markAttended(d), isGymDay: rest)?.status, .attended)
        XCTAssertEqual(AttendanceService.wireStatus(for: .markMissed(d), isGymDay: gym)?.status, .missed)
        XCTAssertEqual(AttendanceService.wireStatus(for: .markPlanned(d), isGymDay: rest)?.status, .planned,
                       "a make-up day is planned on the server too")
        XCTAssertEqual(AttendanceService.wireStatus(for: .clear(d), isGymDay: gym)?.status, .planned)
        XCTAssertNil(AttendanceService.wireStatus(for: .clear(d), isGymDay: rest), "a rest day has no server equivalent")
    }

    // MARK: Outbox

    func testOutboxKeepsTheLatestStatusPerDay() {
        let outbox = AttendanceOutbox(defaults: defaults)
        outbox.put(day: day(-1), status: .confirmed)
        outbox.put(day: day(-2), status: .attended)
        outbox.put(day: at(-1, hour: 21), status: .attended)

        XCTAssertEqual(outbox.entries, [
            AttendanceOutbox.Entry(day: WireDay.string(day(-2)), status: .attended),
            AttendanceOutbox.Entry(day: WireDay.string(day(-1)), status: .attended),
        ])
    }

    func testOutboxRemoveKeepsANewerStatus() {
        let outbox = AttendanceOutbox(defaults: defaults)
        outbox.put(day: day(0), status: .attended)
        let sent = outbox.entries[0]
        outbox.put(day: day(0), status: .missed)

        outbox.remove(sent)
        XCTAssertEqual(outbox.entries.map(\.status), [.missed], "replaced while the request was in flight")

        outbox.remove(outbox.entries[0])
        XCTAssertTrue(outbox.entries.isEmpty)
        XCTAssertNil(defaults.object(forKey: AttendanceOutbox.key))
    }

    func testOnlyOutrightRejectionsAreDropped() {
        XCTAssertTrue(AttendanceOutbox.isRejected(BackendError.http(status: 400, code: "invalid_day", message: "")))
        XCTAssertTrue(AttendanceOutbox.isRejected(BackendError.http(status: 404, code: "user_not_found", message: "")))
        XCTAssertFalse(AttendanceOutbox.isRejected(BackendError.http(status: 401, code: "unauthorized", message: "")))
        XCTAssertFalse(AttendanceOutbox.isRejected(BackendError.http(status: 429, code: "rate_limited", message: "")))
        XCTAssertFalse(AttendanceOutbox.isRejected(BackendError.http(status: 503, code: "busy", message: "")))
        XCTAssertFalse(AttendanceOutbox.isRejected(BackendError.network))
        XCTAssertFalse(AttendanceOutbox.isRejected(URLError(.notConnectedToInternet)))
    }

    func testOutboxKeepsEachAccountsWriteForTheSameDay() {
        let outbox = AttendanceOutbox(defaults: defaults)
        let anna = AttendanceOutbox.Target.remote("anna"), ben = AttendanceOutbox.Target.remote("ben")
        outbox.put(day: day(-1), status: .attended, target: anna)
        outbox.put(day: day(-1), status: .missed, target: ben)
        outbox.put(day: day(-1), status: .confirmed, target: .demo)

        XCTAssertEqual(outbox.entries(for: anna).map(\.status), [.attended])
        XCTAssertEqual(outbox.entries(for: ben).map(\.status), [.missed])
        XCTAssertEqual(outbox.entries(for: .demo).map(\.status), [.confirmed])

        outbox.clear(target: anna)
        XCTAssertTrue(outbox.entries(for: anna).isEmpty)
        XCTAssertEqual(outbox.entries.count, 2, "the other account's writes wait for it")
        outbox.clear()
        XCTAssertNil(defaults.object(forKey: AttendanceOutbox.key))
    }

    func testUntaggedEntryIsSentByWhoeverIsSignedInAndReplaced() throws {
        let legacy = Data(#"[{"day":"\#(WireDay.string(day(-1)))","status":"confirmed"}]"#.utf8)
        defaults.set(legacy, forKey: AttendanceOutbox.key)
        let outbox = AttendanceOutbox(defaults: defaults)
        let anna = AttendanceOutbox.Target.remote("anna")

        XCTAssertEqual(outbox.entries(for: anna).map(\.status), [.confirmed])
        XCTAssertEqual(outbox.entries(for: .demo).map(\.status), [.confirmed])

        outbox.put(day: day(-1), status: .attended, target: anna)
        XCTAssertEqual(outbox.entries, [AttendanceOutbox.Entry(day: WireDay.string(day(-1)), status: .attended, target: anna)])
    }

    func testOutboxJSONIsAndroidsAndSkipsWhatItCannotRead() throws {
        let android = """
        [{"day":"2026-09-20","status":"cancelled","reason":"sick","note":"flu","makeUpDay":"2026-09-22",\
        "backend":"remote","account":"u1"},{"day":"2026-09-21","status":"skipped"},\
        {"day":"2026-09-19","status":"attended","backend":"demo"}]
        """
        defaults.set(Data(android.utf8), forKey: AttendanceOutbox.key)
        let outbox = AttendanceOutbox(defaults: defaults)

        XCTAssertEqual(outbox.entries, [
            AttendanceOutbox.Entry(day: "2026-09-19", status: .attended, target: .demo),
            AttendanceOutbox.Entry(day: "2026-09-20", status: .cancelled, reason: "sick", note: "flu",
                                   makeUpDay: "2026-09-22", target: .remote("u1")),
        ], "an unknown status drops that entry only")

        outbox.put(day: day(0), status: .attended, target: .remote("u1"))
        let data = try XCTUnwrap(defaults.data(forKey: AttendanceOutbox.key))
        let written = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [[String: String]])
        XCTAssertEqual(written.first { $0["day"] == "2026-09-20" },
                       ["day": "2026-09-20", "status": "cancelled", "reason": "sick", "note": "flu",
                        "makeUpDay": "2026-09-22", "backend": "remote", "account": "u1"])
        XCTAssertEqual(written.first { $0["day"] == "2026-09-19" }, ["day": "2026-09-19", "status": "attended", "backend": "demo"],
                       "empty fields are left out")
    }

    // MARK: BroService sync

    func testReportSendsTheStatusPairedOrNot() async {
        let client = SpyBackendClient()
        let bro = BroService(client: client, attendanceOutbox: AttendanceOutbox(defaults: defaults), syncTarget: { .remote("me") })

        await bro.reportAttendance(day: at(-1, hour: 19), status: .attended)

        let calls = await client.attendanceCalls
        XCTAssertEqual(calls.map(\.day), [WireDay.string(day(-1))])
        XCTAssertEqual(calls.map(\.status), [.attended])
        XCTAssertTrue(bro.attendanceOutbox.entries.isEmpty)
        XCTAssertNil(bro.lastError, "background sync never raises the banner")
    }

    func testOfflineReportStaysQueuedUntilTheNextRefresh() async {
        let client = SpyBackendClient()
        await client.fail(with: BackendError.network)
        let bro = BroService(client: client, attendanceOutbox: AttendanceOutbox(defaults: defaults), syncTarget: { .remote("me") })

        await bro.reportAttendance(day: day(-1), status: .attended)
        XCTAssertEqual(bro.attendanceOutbox.entries.map(\.status), [.attended])
        XCTAssertNil(bro.lastError)

        await client.fail(with: nil)
        await bro.refresh(in: context)

        let calls = await client.attendanceCalls
        XCTAssertEqual(calls.map(\.status), [.attended, .attended], "one failed try, then the retry")
        XCTAssertTrue(bro.attendanceOutbox.entries.isEmpty)
    }

    func testRejectedWriteIsDroppedAndTheRestStillGoOut() async {
        let client = SpyBackendClient()
        await client.reject(day: WireDay.string(day(-3)))
        let outbox = AttendanceOutbox(defaults: defaults)
        outbox.put(day: day(-3), status: .attended)
        outbox.put(day: day(-2), status: .attended)
        let bro = BroService(client: client, attendanceOutbox: outbox, syncTarget: { .remote("me") })

        await bro.flushAttendanceOutbox()

        let calls = await client.attendanceCalls
        XCTAssertEqual(calls.map(\.day), [WireDay.string(day(-3)), WireDay.string(day(-2))])
        XCTAssertTrue(outbox.entries.isEmpty)
    }

    func testSignedOutSendsAndQueuesNothing() async {
        let client = SpyBackendClient()
        let bro = BroService(client: client, attendanceOutbox: AttendanceOutbox(defaults: defaults), syncTarget: { nil })

        await bro.reportAttendance(day: day(0), status: .attended)

        let calls = await client.attendanceCalls
        XCTAssertTrue(calls.isEmpty)
        XCTAssertTrue(bro.attendanceOutbox.entries.isEmpty)
    }

    func testQueuedWritesOnlyReachTheirOwnAccount() async {
        let client = SpyBackendClient()
        await client.fail(with: BackendError.network)
        var target: AttendanceOutbox.Target? = .remote("anna")
        let bro = BroService(client: client, attendanceOutbox: AttendanceOutbox(defaults: defaults), syncTarget: { target })
        await bro.reportAttendance(day: day(-1), status: .attended)
        await client.fail(with: nil)

        // Signed out, then another account signs in; then the demo switch.
        target = nil
        await bro.flushAttendanceOutbox()
        target = .remote("ben")
        await bro.flushAttendanceOutbox()
        target = .demo
        await bro.flushAttendanceOutbox()
        var calls = await client.attendanceCalls
        XCTAssertEqual(calls.count, 1, "only the first, failed try")
        XCTAssertEqual(bro.attendanceOutbox.entries.map(\.status), [.attended])

        target = .remote("anna")
        await bro.flushAttendanceOutbox()
        calls = await client.attendanceCalls
        XCTAssertEqual(calls.count, 2)
        XCTAssertTrue(bro.attendanceOutbox.entries.isEmpty)
    }

    func testOfflineCantMakeItIsQueuedWithItsDetailsAndRetried() async {
        let client = SpyBackendClient()
        await client.fail(with: BackendError.network)
        let bro = BroService(client: client, attendanceOutbox: AttendanceOutbox(defaults: defaults), syncTarget: { .remote("me") })
        // A confirm for the same day still waiting to go out.
        bro.attendanceOutbox.put(day: day(0), status: .confirmed, target: .remote("me"))

        await bro.cantMakeIt(reason: "sick", note: "", makeUpDay: day(2), sessionDay: day(0), in: context)

        XCTAssertEqual(bro.attendanceOutbox.entries, [
            AttendanceOutbox.Entry(day: WireDay.string(day(0)), status: .cancelled, reason: "sick",
                                   makeUpDay: WireDay.string(day(2)), target: .remote("me")),
        ], "the cancellation replaces the queued confirm, so a later flush cannot overwrite it")
        XCTAssertNil(bro.lastError, "background sync never raises the banner")
        XCTAssertEqual(status(on: day(0)), .cancelled)

        await client.fail(with: nil)
        await bro.refresh(in: context)

        let calls = await client.attendanceCalls
        XCTAssertEqual(calls.last, SpyBackendClient.Call(day: WireDay.string(day(0)), status: .cancelled, reason: "sick",
                                                         note: nil, makeUpDay: WireDay.string(day(2))))
        XCTAssertFalse(calls.contains { $0.status == .confirmed }, "the stale confirm never goes out")
        XCTAssertTrue(bro.attendanceOutbox.entries.isEmpty)
    }

    func testSignedOutCantMakeItSendsNothing() async {
        let client = SpyBackendClient()
        let bro = BroService(client: client, attendanceOutbox: AttendanceOutbox(defaults: defaults), syncTarget: { nil })

        await bro.cantMakeIt(reason: "sick", note: nil, makeUpDay: nil, sessionDay: day(0), in: context)

        let calls = await client.attendanceCalls
        XCTAssertTrue(calls.isEmpty)
        XCTAssertTrue(bro.attendanceOutbox.entries.isEmpty)
        XCTAssertEqual(status(on: day(0)), .cancelled, "the local record is still written")
    }

    // MARK: Make-up day (ios-correctness-6)

    func testMakeUpDayFollowsTheServerRule() {
        let empty = day(2), cancelled = day(3), attended = day(-1), confirmed = day(0)
        AttendanceService.markMissed(day: cancelled, reason: "sick", note: nil, makeUp: nil, context: context)
        AttendanceService.markAttended(day: attended, context: context)
        AttendanceService.markConfirmed(day: confirmed, context: context)

        for d in [empty, cancelled, attended, confirmed] { AttendanceService.markPlanned(day: d, context: context) }

        XCTAssertEqual(status(on: empty), .planned)
        XCTAssertEqual(status(on: cancelled), .planned)
        XCTAssertNil(AttendanceService.record(for: cancelled, participant: .me, context: context)?.reason)
        XCTAssertEqual(status(on: attended), .attended)
        XCTAssertEqual(status(on: confirmed), .confirmed)
    }

    // MARK: Sweep (ios-correctness-5)

    private var cursor: AttendanceService.SweepCursor { AttendanceService.SweepCursor(defaults: defaults) }

    func testFirstSweepJudgesYesterdayAlone() {
        let schedule = GymSchedule(weekdays: [1, 2, 3, 4, 5, 6, 7])
        context.insert(schedule)
        AttendanceService.markPlanned(day: day(-3), context: context)

        AttendanceService.markPastPlannedAsMissed(schedule: schedule, context: context, cursor: cursor)

        XCTAssertEqual(myRecords().map(\.day), [day(-3), day(-1)])
        XCTAssertEqual(status(on: day(-1)), .missed, "the old sweep never judged the day it ran on")
        XCTAssertNil(status(on: day(-2)), "the past before that is never re-judged by today's schedule")
        XCTAssertEqual(status(on: day(-3)), .planned, "already the old sweep's")
        XCTAssertEqual(cursor.sweptThrough, day(-1))

        AttendanceService.markPastPlannedAsMissed(schedule: schedule, context: context, cursor: cursor)
        XCTAssertEqual(myRecords().count, 2, "judged once")
    }

    func testFirstSweepWithoutAScheduleStillStartsTheCursor() {
        AttendanceService.markPastPlannedAsMissed(schedule: nil, context: context, cursor: cursor)

        XCTAssertTrue(myRecords().isEmpty)
        XCTAssertEqual(cursor.sweptThrough, day(-1))
    }

    func testSweepCursorIsACalendarDayInEveryTimeZone() throws {
        var warsaw = Calendar(identifier: .gregorian)
        warsaw.timeZone = try XCTUnwrap(TimeZone(identifier: "Europe/Warsaw"))
        var losAngeles = Calendar(identifier: .gregorian)
        losAngeles.timeZone = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
        let judged = try XCTUnwrap(warsaw.date(from: DateComponents(year: 2026, month: 9, day: 20, hour: 0)))

        AttendanceService.SweepCursor(defaults: defaults, calendar: warsaw).sweptThrough = judged

        XCTAssertEqual(defaults.object(forKey: AttendanceService.SweepCursor.key) as? String, "2026-09-20")
        let afterFlyingWest = try XCTUnwrap(AttendanceService.SweepCursor(defaults: defaults, calendar: losAngeles).sweptThrough)
        let day = losAngeles.dateComponents([.year, .month, .day, .hour], from: afterFlyingWest)
        XCTAssertEqual([day.year, day.month, day.day, day.hour], [2026, 9, 20, 0],
                       "still the 20th, not the 19th, so the 20th is not judged again")
    }

    func testSweepCursorReadsTheEarlierInstantFormat() {
        let instant = at(-2, hour: 0)
        defaults.set(instant.timeIntervalSinceReferenceDate, forKey: AttendanceService.SweepCursor.key)

        XCTAssertEqual(cursor.sweptThrough, day(-2))
        cursor.sweptThrough = day(-1)
        XCTAssertEqual(defaults.object(forKey: AttendanceService.SweepCursor.key) as? String, WireDay.string(day(-1)))
    }

    func testSweepJudgesOnlyTheDaysSinceTheCursor() {
        let schedule = GymSchedule(weekdays: [1, 2, 3, 4, 5, 6, 7])
        context.insert(schedule)
        cursor.sweptThrough = day(-3)
        AttendanceService.markAttended(day: day(-1), context: context)

        AttendanceService.markPastPlannedAsMissed(schedule: schedule, context: context, cursor: cursor)

        XCTAssertEqual(myRecords().map(\.day), [day(-2), day(-1)])
        XCTAssertEqual(status(on: day(-2)), .missed)
        XCTAssertEqual(status(on: day(-1)), .attended)
        XCTAssertNil(status(on: day(-3)), "already judged")
        XCTAssertEqual(cursor.sweptThrough, day(-1))
    }

    func testChangingGymDaysNeverTurnsPastRestDaysIntoMisses() {
        let schedule = GymSchedule(weekdays: [])
        context.insert(schedule)
        cursor.sweptThrough = day(-8)
        AttendanceService.markPastPlannedAsMissed(schedule: schedule, context: context, cursor: cursor)
        XCTAssertTrue(myRecords().isEmpty)

        // Every day becomes a gym day: the week already judged as rest stays rest.
        schedule.weekdays = [1, 2, 3, 4, 5, 6, 7]
        AttendanceService.markPastPlannedAsMissed(schedule: schedule, context: context, cursor: cursor)
        XCTAssertTrue(myRecords().isEmpty)
        XCTAssertEqual(AttendanceService.currentStreak(records: myRecords()), 0)

        // The next day that ends is judged by the new schedule.
        AttendanceService.markPastPlannedAsMissed(schedule: schedule, context: context, today: day(1), cursor: cursor)
        XCTAssertEqual(myRecords().map(\.day), [day(0)])
        XCTAssertEqual(cursor.sweptThrough, day(0))
    }

    func testSkippedMakeUpDayIsSweptToMissed() {
        let schedule = GymSchedule(weekdays: [])
        context.insert(schedule)
        cursor.sweptThrough = day(-4)
        AttendanceService.markPlanned(day: day(-2), context: context)

        AttendanceService.markPastPlannedAsMissed(schedule: schedule, context: context, cursor: cursor)

        XCTAssertEqual(status(on: day(-2)), .missed, "planned on a rest day still has to happen")
        XCTAssertEqual(myRecords().count, 1)
    }
}

/// Records attendance writes; everything else is a harmless stub.
private actor SpyBackendClient: BackendClient {
    struct Call: Equatable {
        var day: String
        var status: AttendanceStatus
        var reason: String? = nil
        var note: String? = nil
        var makeUpDay: String? = nil
    }

    nonisolated let baseURL = URL(string: "https://spy.invalid")!
    private(set) var attendanceCalls: [Call] = []
    private var failure: Error?
    private var rejectedDays: Set<String> = []

    func fail(with error: Error?) { failure = error }
    func reject(day: String) { rejectedDays.insert(day) }

    func setAttendance(day: Date, status: AttendanceStatus, reason: String?, note: String?, makeUpDay: Date?) async throws {
        let key = WireDay.string(day)
        attendanceCalls.append(Call(day: key, status: status, reason: reason, note: note,
                                    makeUpDay: makeUpDay.map { WireDay.string($0) }))
        if rejectedDays.contains(key) { throw BackendError.http(status: 400, code: "invalid_day", message: "") }
        if let failure { throw failure }
    }

    func me() async throws -> Me { Me(id: "spy", username: "spy", displayName: "Spy", email: nil, partner: nil, pairCode: nil) }

    func signIn(apple identityToken: String, authorizationCode: String) async throws -> Session { throw BackendError.network }
    func signIn(google idToken: String) async throws -> Session { throw BackendError.network }
    func signIn(username: String, password: String) async throws -> Session { throw BackendError.network }
    func register(username: String, password: String, email: String?) async throws -> Session { throw BackendError.network }
    func logout(refreshToken: String) async throws {}
    func updateMe(locale: String?, timeZone: String?) async throws {}
    func createPairCode() async throws -> String { throw BackendError.network }
    func pair(withCode: String) async throws -> Partner { throw BackendError.network }
    func unpair() async throws {}
    func pushSchedule(_ s: ScheduleDTO) async throws {}
    func partnerState() async throws -> PartnerState { throw BackendError.network }
    func sendHeadsUp(kind: HeadsUpKind, text: String, sessionDay: Date) async throws {}
    func registerPushToken(_ token: Data) async throws {}
    func estimate(imageJPEG: Data, meal: MealSlot, locale: String, anthropicKey: String?, notes: String) async throws -> AIEstimate {
        throw BackendError.network
    }
    func readLabel(imageJPEG: Data, locale: String) async throws -> LabelReading { throw BackendError.network }
    func deleteAccount() async throws {}
}
