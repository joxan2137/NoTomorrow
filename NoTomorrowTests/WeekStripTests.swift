import XCTest
@testable import NoTomorrow

/// The v2 week strip marks: the who-trained dots under each circle and the thin ring on a gym day still ahead.
@MainActor
final class WeekStripTests: XCTestCase {

    private func day(isToday: Bool = false, isGymDay: Bool = true,
                     me: DayState = .rest, partner: DayState = .rest) -> WeekDay {
        WeekDay(date: Date(timeIntervalSince1970: 0), isoWeekday: 1, isToday: isToday, isGymDay: isGymDay,
                myState: me, partnerState: partner)
    }

    // MARK: Dots

    func testDotsShowYouThenYourPartner() {
        XCTAssertEqual(WeekStripView.dots(for: day(me: .attended, partner: .attended), isPaired: true),
                       [.you, .partnerTrained])
        XCTAssertEqual(WeekStripView.dots(for: day(me: .attended, partner: .missed), isPaired: true),
                       [.you, .partnerMissed])
        XCTAssertEqual(WeekStripView.dots(for: day(me: .missed, partner: .attended), isPaired: true),
                       [.partnerTrained], "a miss of mine is the rose ring, not a dot")
    }

    func testPartnerCancellationIsARoseDot() {
        XCTAssertEqual(WeekStripView.dots(for: day(partner: .cancelled(reason: "sick")), isPaired: true), [.partnerMissed])
    }

    func testSoloShowsOnlyYourOwnDot() {
        XCTAssertEqual(WeekStripView.dots(for: day(me: .attended, partner: .attended), isPaired: false), [.you])
        XCTAssertEqual(WeekStripView.dots(for: day(partner: .missed), isPaired: false), [])
    }

    func testNoDotsWhileNothingHappened() {
        for state in [DayState.rest, .planned, .confirmed] {
            XCTAssertEqual(WeekStripView.dots(for: day(me: state, partner: state), isPaired: true), [])
        }
        XCTAssertEqual(WeekStripView.dots(for: day(isToday: true, me: .attended), isPaired: true), [.you],
                       "today keeps its white circle and still shows the dot")
    }

    // MARK: Upcoming ring

    func testUpcomingGymDayIsPlannedOrConfirmedAndNotToday() {
        XCTAssertTrue(WeekStripView.isUpcomingGymDay(day(me: .planned)))
        XCTAssertTrue(WeekStripView.isUpcomingGymDay(day(me: .confirmed)))
        XCTAssertFalse(WeekStripView.isUpcomingGymDay(day(isToday: true, me: .planned)))
    }

    func testSettledAndUnscheduledDaysStayPlain() {
        XCTAssertFalse(WeekStripView.isUpcomingGymDay(day(me: .attended)))
        XCTAssertFalse(WeekStripView.isUpcomingGymDay(day(me: .missed)))
        XCTAssertFalse(WeekStripView.isUpcomingGymDay(day(me: .cancelled(reason: nil))))
        XCTAssertFalse(WeekStripView.isUpcomingGymDay(day(me: .rest)), "a past gym day with no record")
        XCTAssertFalse(WeekStripView.isUpcomingGymDay(day(isGymDay: false, me: .planned)))
    }
}
