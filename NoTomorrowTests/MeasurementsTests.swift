import XCTest
import SwiftData
@testable import NoTomorrow

/// Body measurements: inches for lb users, per-kind summaries, and one reading per kind per day.
@MainActor
final class MeasurementsTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }

    override func setUpWithError() throws {
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
    }

    override func tearDown() {
        container = nil
    }

    func testInchesOnlyForLengthsAndPoundUsers() {
        XCTAssertEqual(Measurements.display(254, kind: .waist, unit: .lb), 100, accuracy: 0.001)
        XCTAssertEqual(Measurements.display(84, kind: .waist, unit: .kg), 84)
        XCTAssertEqual(Measurements.display(18, kind: .bodyFat, unit: .lb), 18)
        XCTAssertEqual(Measurements.stored(33, kind: .arm, unit: .lb), 83.82, accuracy: 0.001)
    }

    func testSummariesKeepKindOrderAndChange() {
        let day = { (n: Double) in Date(timeIntervalSince1970: n * 86_400) }
        let summaries = Measurements.summaries([
            .init(kind: .arm, day: day(1), value: 38),
            .init(kind: .waist, day: day(3), value: 84),
            .init(kind: .waist, day: day(1), value: 86.5),
        ])
        XCTAssertEqual(summaries.map(\.kind), [.waist, .arm])
        XCTAssertEqual(summaries[0].latest.value, 84)
        XCTAssertEqual(summaries[0].change ?? 0, -2.5, accuracy: 0.001)
        XCTAssertNil(summaries[1].change)
    }

    func testSavingTwiceADayReplacesThatKind() throws {
        Measurements.save([.waist: 85, .chest: 100], in: context)
        Measurements.save([.waist: 84.5], in: context)
        let rows = try context.fetch(FetchDescriptor<BodyMeasurement>())
        XCTAssertEqual(rows.count, 2)
        XCTAssertEqual(rows.first { $0.kind == .waist }?.value, 84.5)
    }
}
