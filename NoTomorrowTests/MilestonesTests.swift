import XCTest
@testable import NoTomorrow

/// Lifetime milestones: tiers, which workout crossed each, progress toward the next and the lb display.
final class MilestonesTests: XCTestCase {
    private var calendar: Calendar = {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Europe/Warsaw")!
        return cal
    }()

    private func date(_ y: Int, _ m: Int, _ d: Int) -> Date {
        calendar.date(from: DateComponents(year: y, month: m, day: d, hour: 12))!
    }

    private func session(_ day: Date, volume: Double = 0, lifts: [Milestones.Kind: Double] = [:]) -> Milestones.Session {
        Milestones.Session(workoutID: UUID(), startedAt: day, volumeKg: volume, heaviestKg: lifts)
    }

    private func milestone(_ milestones: [Milestones.Milestone], _ kind: Milestones.Kind,
                           _ target: Double) -> Milestones.Milestone? {
        milestones.first { $0.kind == kind && $0.target == target }
    }

    func testTiers() {
        XCTAssertEqual(Milestones.workoutTiers, [1, 10, 25, 50, 100, 250, 500])
        XCTAssertEqual(Milestones.volumeTiersKg, [1_000, 10_000, 50_000, 100_000, 500_000, 1_000_000])
        XCTAssertEqual(Milestones.weekStreakTiers, [4, 12, 26, 52])
        XCTAssertEqual(Milestones.multiplier(.bench), 1)
        XCTAssertEqual(Milestones.multiplier(.squat), 1.5)
        XCTAssertEqual(Milestones.multiplier(.deadlift), 2)
        XCTAssertNil(Milestones.multiplier(.workouts))
        XCTAssertEqual(Milestones.lift(forExerciseID: "Barbell_Bench_Press_-_Medium_Grip"), .bench)
        XCTAssertEqual(Milestones.lift(forExerciseID: "Barbell_Squat"), .squat)
        XCTAssertEqual(Milestones.lift(forExerciseID: "Barbell_Deadlift"), .deadlift)
        XCTAssertNil(Milestones.lift(forExerciseID: "Dumbbell_Bench_Press"))
    }

    func testWithoutBodyWeightTheLiftsAreLeftOut() {
        let all = Milestones.evaluate(sessions: [], bodyWeightKg: nil, calendar: calendar)
        XCTAssertEqual(all.count, 7 + 6 + 4)
        XCTAssertFalse(all.contains { $0.kind.isLift })
        XCTAssertEqual(Milestones.evaluate(sessions: [], bodyWeightKg: 80, calendar: calendar).count, 7 + 6 + 4 + 3)
        XCTAssertNil(Milestones.latest(all))
    }

    func testWorkoutCountCrossings() {
        // Given out of order: evaluation sorts by start.
        let sessions = (1...12).map { session(date(2026, 1, $0)) }.reversed()
        let all = Milestones.evaluate(sessions: Array(sessions), bodyWeightKg: nil, calendar: calendar)
        let first = milestone(all, .workouts, 1)
        XCTAssertEqual(first?.achieved?.date, date(2026, 1, 1))
        let ten = milestone(all, .workouts, 10)
        XCTAssertEqual(ten?.achieved?.date, date(2026, 1, 10))
        let twentyFive = milestone(all, .workouts, 25)
        XCTAssertNil(twentyFive?.achieved)
        XCTAssertEqual(twentyFive?.current, 12)
        XCTAssertEqual(twentyFive?.progress ?? 0, 12.0 / 25, accuracy: 0.0001)

        let tenth = sessions.first { $0.startedAt == date(2026, 1, 10) }!
        let crossed = Milestones.crossed(by: tenth.workoutID, in: all)
        XCTAssertEqual(crossed.map(\.kind), [.workouts])
        XCTAssertEqual(crossed.first?.target, 10)
    }

    func testVolumeCrossesOnTheWorkoutThatTopsTheTier() {
        let a = session(date(2026, 3, 2), volume: 600)
        let b = session(date(2026, 3, 4), volume: 500)      // 1 100 → 1 t
        let c = session(date(2026, 3, 6), volume: 9_000)    // 10 100 → 10 t
        let all = Milestones.evaluate(sessions: [c, a, b], bodyWeightKg: nil, calendar: calendar)
        XCTAssertEqual(milestone(all, .volume, 1_000)?.achieved?.workoutID, b.workoutID)
        XCTAssertEqual(milestone(all, .volume, 10_000)?.achieved?.workoutID, c.workoutID)
        let next = milestone(all, .volume, 50_000)
        XCTAssertEqual(next?.current ?? 0, 10_100, accuracy: 0.001)
        XCTAssertFalse(next?.isAchieved ?? true)
        XCTAssertEqual(Milestones.crossed(by: c.workoutID, in: all).map(\.target), [10_000])
    }

    func testLongestStreakKeepsTheBestRun() {
        // Weeks of 5, 12, 19, 26 Jan (4 in a row), a gap, then 2 more.
        let days = [date(2026, 1, 5), date(2026, 1, 7), date(2026, 1, 13), date(2026, 1, 21), date(2026, 1, 28),
                    date(2026, 2, 16), date(2026, 2, 23)]
        XCTAssertEqual(Milestones.longestStreaks(days, calendar: calendar), [1, 1, 2, 3, 4, 4, 4])
        let sessions = days.map { session($0) }
        let all = Milestones.evaluate(sessions: sessions, bodyWeightKg: nil, calendar: calendar)
        let four = milestone(all, .weekStreak, 4)
        XCTAssertEqual(four?.achieved?.workoutID, sessions[4].workoutID)
        let twelve = milestone(all, .weekStreak, 12)
        XCTAssertEqual(twelve?.current, 4)
        XCTAssertNil(twelve?.achieved)
    }

    func testStreakAcrossTheYearBoundary() {
        let days = [date(2025, 12, 22), date(2025, 12, 29), date(2026, 1, 5)]
        XCTAssertEqual(Milestones.longestStreaks(days, calendar: calendar), [1, 2, 3])
    }

    func testLiftsAgainstBodyWeight() {
        let a = session(date(2026, 4, 1), lifts: [.bench: 70, .squat: 100, .deadlift: 150])
        let b = session(date(2026, 4, 8), lifts: [.bench: 80, .squat: 110])
        let all = Milestones.evaluate(sessions: [a, b], bodyWeightKg: 80, calendar: calendar)
        let bench = all.first { $0.kind == .bench }
        XCTAssertEqual(bench?.target, 80)
        XCTAssertEqual(bench?.achieved?.workoutID, b.workoutID)
        let squat = all.first { $0.kind == .squat }
        XCTAssertEqual(squat?.target, 120)
        XCTAssertEqual(squat?.current, 110)
        XCTAssertNil(squat?.achieved)
        let deadlift = all.first { $0.kind == .deadlift }
        XCTAssertEqual(deadlift?.target, 160)
        XCTAssertEqual(deadlift?.current, 150)
        XCTAssertEqual(Milestones.crossed(by: b.workoutID, in: all).map(\.kind), [.bench])
    }

    func testLatestAndNext() {
        let a = session(date(2026, 5, 1), volume: 1_200)
        let b = session(date(2026, 5, 3), volume: 100)
        let all = Milestones.evaluate(sessions: [a, b], bodyWeightKg: nil, calendar: calendar)
        // a crossed 1 workout and 1 t; b crossed nothing, so the latest is a's last track.
        XCTAssertEqual(Milestones.latest(all)?.kind, .volume)
        let next = Milestones.next(all)
        XCTAssertEqual(Set(next.map(\.kind)), [.workouts, .volume, .weekStreak])
        XCTAssertEqual(next.count, 3)
        // Closest to done first: 2/10 workouts (0.2), 1 300 / 10 000 (0.13), 1/4 weeks (0.25).
        XCTAssertEqual(next.map(\.kind), [.weekStreak, .workouts, .volume])
    }

    func testLbDisplayConvertsKilograms() {
        XCTAssertEqual(Milestones.displayAmount(1_000, unit: .kg), 1_000)
        XCTAssertEqual(Milestones.displayAmount(1_000, unit: .lb), 2_204.6226, accuracy: 0.001)
        let ton = Milestones.Milestone(kind: .volume, target: 1_000, current: 0, achieved: nil)
        XCTAssertTrue(MilestoneText.title(ton, unit: .lb).contains("lb"))
        XCTAssertTrue(MilestoneText.title(ton, unit: .kg).contains("kg"))
    }
}
