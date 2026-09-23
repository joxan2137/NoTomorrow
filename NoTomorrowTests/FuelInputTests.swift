import XCTest
@testable import NoTomorrow

/// Number fields and small pure helpers behind meal editing: `NumberInput` (same vectors as Android
/// `Parsing.decimal`), ungrouped edit-field prefills that parse back, the edit sheets' day stepper and the Settings
/// version label.
final class FuelInputTests: XCTestCase {

    // MARK: - NumberInput

    func testDecimalAcceptsCommaDotAndDigitGroupSpaces() {
        let accepted: [(String, Double)] = [
            ("72,5", 72.5), ("72.5", 72.5), (" 72 ", 72), ("0", 0), (",5", 0.5), ("5,", 5), ("+2", 2), ("-3", -3),
            ("1e3", 1000),
            ("12 500", 12500),                 // U+0020
            ("1\u{00A0}234,5", 1234.5),        // NBSP (Fmt output, older iOS pl grouping)
            ("12\u{202F}345,5", 12345.5),      // narrow NBSP (current pl grouping)
            ("1\u{2009}234", 1234), ("1\u{2007}234", 1234), ("7\t2", 72),
        ]
        for (text, expected) in accepted {
            XCTAssertEqual(NumberInput.decimal(text), expected, "\(text.debugDescription)")
        }
    }

    func testDecimalRejectsAnythingButAFinitePlainNumber() {
        for text in ["", " ", "abc", "1.234.5", "1,234.5", "1.234,5", "inf", "nan", "Infinity", "0x10", "1.5f", "--1", ".", "e5"] {
            XCTAssertNil(NumberInput.decimal(text), "\(text.debugDescription)")
        }
    }

    func testNonNegativeRejectsNegatives() {
        XCTAssertNil(NumberInput.nonNegative("-3"))
        XCTAssertEqual(NumberInput.nonNegative("0"), 0)
        XCTAssertEqual(NumberInput.nonNegative("82,4"), 82.4)
    }

    // MARK: - Edit-field prefills (l10n-a11y-11)

    func testFieldTextNeverGroups() {
        XCTAssertEqual(FuelText.fieldText(1234.5, locale: Locale(identifier: "en_US")), "1234.5")
        XCTAssertEqual(FuelText.fieldText(1234.5, locale: Locale(identifier: "pl_PL")), "1234,5")
        XCTAssertEqual(FuelText.fieldText(12345.46, locale: Locale(identifier: "pl_PL")), "12345,5")
        XCTAssertEqual(FuelText.fieldText(1234.5, locale: Locale(identifier: "en_PL")), "1234,5")
        XCTAssertEqual(FuelText.fieldText(1234.5, locale: Locale(identifier: "de_DE")), "1234,5")
        XCTAssertEqual(FuelText.fieldText(150, locale: Locale(identifier: "pl_PL")), "150")
        XCTAssertEqual(FuelText.fieldText(72.04, locale: Locale(identifier: "pl_PL")), "72", "at most one decimal")
        XCTAssertEqual(FuelText.fieldText(0, locale: Locale(identifier: "en_US")), "0")
    }

    func testFieldTextParsesBackInEveryLocale() {
        for id in ["en_US", "en_GB", "en_PL", "pl_PL", "de_DE", "fr_FR"] {
            let locale = Locale(identifier: id)
            for value in [0.0, 5, 72.5, 999.9, 1234.5, 12345.5, 250_000] {
                let text = FuelText.fieldText(value, locale: locale)
                XCTAssertEqual(NumberInput.decimal(text), value, "\(id): \(text)")
            }
        }
    }

    // MARK: - Day stepper

    func testDayStepperStaysInThePast() {
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: calendar.date(from: DateComponents(year: 2026, month: 9, day: 22, hour: 12))!)
        let yesterday = calendar.date(byAdding: .day, value: -1, to: today)!
        let threeDaysAgo = calendar.date(byAdding: .day, value: -3, to: today)!

        XCTAssertEqual(EntryDayStepper.step(today, by: -1, today: today), yesterday)
        XCTAssertEqual(EntryDayStepper.step(yesterday, by: 1, today: today), today)
        XCTAssertEqual(EntryDayStepper.step(today, by: 1, today: today), today, "never past today")
        XCTAssertEqual(EntryDayStepper.step(threeDaysAgo.addingTimeInterval(15 * 3600), by: 1, today: today),
                       calendar.date(byAdding: .day, value: -2, to: today)!, "result is a start of day")
        XCTAssertEqual(EntryDayStepper.step(calendar.date(byAdding: .day, value: 5, to: today)!, by: 0, today: today), today)
    }

    func testDayStepperCrossesMonthsAndDST() {
        let calendar = Calendar.current
        let firstOfNovember = calendar.date(from: DateComponents(year: 2026, month: 11, day: 1))!
        XCTAssertEqual(EntryDayStepper.step(firstOfNovember, by: -1, today: firstOfNovember),
                       calendar.date(from: DateComponents(year: 2026, month: 10, day: 31))!)
        // 25 Oct 2026 is the European DST end (a 25-hour day).
        let dstEnd = calendar.date(from: DateComponents(year: 2026, month: 10, day: 25))!
        let next = EntryDayStepper.step(dstEnd, by: 1, today: firstOfNovember)
        XCTAssertEqual(next, calendar.date(from: DateComponents(year: 2026, month: 10, day: 26))!)
    }

    // MARK: - Settings version

    func testAppVersionLabel() {
        XCTAssertEqual(AppVersion.label(info: ["CFBundleShortVersionString": "0.2.1", "CFBundleVersion": "57"]), "0.2.1 (57)")
        XCTAssertEqual(AppVersion.label(info: ["CFBundleShortVersionString": "0.2.1"]), "0.2.1")
        XCTAssertEqual(AppVersion.label(info: ["CFBundleShortVersionString": "0.2.1", "CFBundleVersion": ""]), "0.2.1")
        XCTAssertEqual(AppVersion.label(info: nil), "–")
        XCTAssertFalse(AppVersion.label().isEmpty)
        XCTAssertNotEqual(Fmt.localized("settings.version \("0.2.1 (57)")"), "settings.version 0.2.1 (57)",
                          "the key is in the catalog")
    }
}
