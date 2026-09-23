import XCTest
@testable import NoTomorrow

/// The ordered JSON value and the model-text helpers the finalizer relies on: they must behave like `JSON.parse`,
/// `JSON.stringify` and the backend's `num` / `extractJsonObject` / `parseModelJson`.
final class AIJSONTests: XCTestCase {
    // MARK: Parser

    func testParsesLikeJSONParse() throws {
        let value = try JSONValue.parse(#" {"a": [1, -2.5e1, true, null, "xA\n"], "b": {}} "#)
        XCTAssertEqual(value["a"], .array([1, -25, true, .null, "xA\n"]))
        XCTAssertEqual(value["b"], .object(JSONObject()))
    }

    func testRejectsWhatJSONParseRejects() {
        for text in [#"{"a":1,}"#, "{'a':1}", "[1,]", "{\"a\":01}", "{\"a\":.5}", "{\"a\":1} x", "{\"a\":\"\u{1}\"}", "", "nul"] {
            XCTAssertThrowsError(try JSONValue.parse(text), text)
        }
    }

    func testDuplicateKeyKeepsFirstPositionAndLastValue() throws {
        let value = try JSONValue.parse(#"{"a":1,"b":2,"a":3}"#)
        XCTAssertEqual(value.objectValue?.keys, ["a", "b"])
        XCTAssertEqual(value["a"], 3)
    }

    func testNumbersAreCorrectlyRounded() throws {
        XCTAssertEqual(try JSONValue.parse("[0.1, 0.655, 1e400]").arrayValue?.map(\.numberValue), [0.1, 0.655, .infinity])
    }

    func testSurrogatePairsDecode() throws {
        XCTAssertEqual(try JSONValue.parse(#""🍕 \ud800""#), .string("🍕 \u{FFFD}"))
    }

    // MARK: Serialiser

    func testSerialisesInKeyOrderLikeStringify() {
        var object = JSONObject()
        object["z"] = 8192
        object["a"] = "pół/łyżki \"x\"\t\u{1}"
        object["m"] = [0.15, false, .null]
        XCTAssertEqual(JSONValue.object(object).serialized(),
                       #"{"z":8192,"a":"pół/łyżki \"x\"\t\u0001","m":[0.15,false,null]}"#)
    }

    func testStringLiteralMatchesJSONStringify() {
        XCTAssertEqual(JSONValue.stringLiteral("a\"b\\c/d\n\r\u{8}\u{c}\u{1f}ą"), #""a\"b\\c/d\n\r\b\f\u001fą""#)
    }

    // MARK: Model-text helpers

    func testNum() {
        XCTAssertEqual(AIFinalizer.num(.number(12)), 12)
        XCTAssertEqual(AIFinalizer.num(.string(" 4,5 ")), 4.5)
        XCTAssertEqual(AIFinalizer.num(.string("-3")), -3)
        XCTAssertEqual(AIFinalizer.num(.string("+7.25")), 7.25)
        for rejected: JSONValue in [.string("1e3"), .string(""), .string("1,2,3"), .bool(true), .null, .array([]), .string("½")] {
            XCTAssertNil(AIFinalizer.num(rejected), rejected.serialized())
        }
    }

    func testRoundingIsHalfUpOnTheDouble() {
        XCTAssertEqual(AIFinalizer.round1(1.4 * 175 / 100), 2.4)
        XCTAssertEqual(AIFinalizer.round1(0.25), 0.3)
        XCTAssertEqual(AIFinalizer.round2(0.145), 0.14)
    }

    func testValidGTIN() {
        XCTAssertTrue(AIFinalizer.validGTIN("5901234123457"))
        XCTAssertTrue(AIFinalizer.validGTIN("96385074"))
        XCTAssertFalse(AIFinalizer.validGTIN("5901234123458"))
        XCTAssertFalse(AIFinalizer.validGTIN("590123412345"))
        XCTAssertFalse(AIFinalizer.validGTIN("59012341234x7"))
    }

    func testModelTextRepairs() throws {
        XCTAssertEqual(try AIFinalizer.parseModelJSON("Sure:\n```json\n{\"a\":[1,2,],}\n```"), ["a": [1, 2]])
        XCTAssertEqual(try AIFinalizer.parseModelJSON("{'a': 'b'}"), ["a": "b"])
        XCTAssertThrowsError(try AIFinalizer.parseModelJSON("no json here")) { XCTAssertEqual($0 as? AIOutputError, .noJSON) }
        XCTAssertThrowsError(try AIFinalizer.parseModelJSON("} {")) { XCTAssertEqual($0 as? AIOutputError, .noJSON) }
        XCTAssertThrowsError(try AIFinalizer.parseModelJSON("{a: 1}")) { XCTAssertEqual($0 as? AIOutputError, .invalidJSON) }
    }

    func testFixtureComparisonCatchesDifferences() {
        XCTAssertTrue(AIEstimateFinalizerTests.differences(["a": 1, "b": .null], ["a": 1.0000000001, "b": .null]).isEmpty)
        XCTAssertTrue(AIEstimateFinalizerTests.differences(["a": 1], ["a": 1, "b": .null]).isEmpty, "a null may be missing")
        XCTAssertFalse(AIEstimateFinalizerTests.differences(["a": 1], ["a": 2]).isEmpty)
        XCTAssertFalse(AIEstimateFinalizerTests.differences(["a": 1, "c": 1], ["a": 1]).isEmpty)
        XCTAssertFalse(AIEstimateFinalizerTests.differences(["a": [1, 2]], ["a": [2, 1]]).isEmpty)
    }
}
