import XCTest
@testable import NoTomorrow

/// Parity lock with the backend (`backend/src/aiFinalize.ts`, `aiSpec.ts`) and Android: every file in
/// `backend/data/ai/fixtures` must come out exactly as the fixture says. Numbers match within 1e-9; a key whose
/// expected value is null may be missing. One assertion per file, named after it.
final class AIEstimateFinalizerTests: XCTestCase {
    private var spec: AIEstimateSpec!

    override func setUpWithError() throws {
        let url = try XCTUnwrap(Bundle.main.url(forResource: "estimate-spec", withExtension: "json"),
                                "estimate-spec.json must be bundled in the app")
        spec = try AIEstimateSpec.load(from: url)
    }

    private func fixtures() throws -> [(name: String, json: JSONValue)] {
        let folder = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "fixtures", withExtension: nil),
                                   "the fixtures folder must be bundled in the test target")
        let files = try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        return try files.map { ($0.lastPathComponent, try JSONValue.parse(Data(contentsOf: $0))) }
    }

    func testEveryFixtureKindIsPresent() throws {
        let all = try fixtures()
        XCTAssertGreaterThanOrEqual(all.count, 29)
        let kinds = Set(all.compactMap { $0.json["kind"]?.stringValue })
        XCTAssertEqual(kinds, ["estimate", "label", "context", "prompt"])
    }

    func testFixtures() throws {
        for (name, fixture) in try fixtures() {
            switch fixture["kind"]?.stringValue {
            case "estimate": checkEstimate(name, fixture)
            case "label": checkLabel(name, fixture)
            case "context": checkContext(name, fixture)
            case "prompt": checkPrompt(name, fixture)
            default: XCTFail("\(name): unknown kind")
            }
        }
    }

    // MARK: Kinds

    private func checkEstimate(_ name: String, _ fixture: JSONValue) {
        let ctx = AIEstimateSpec.NotesContext(weightGiven: fixture["ctx"]?["weightGiven"] == .bool(true),
                                              measuredReference: fixture["ctx"]?["measuredReference"] == .bool(true))
        do {
            let estimate: AIEstimate
            if let text = fixture["rawText"]?.stringValue {
                estimate = try AIFinalizer.finalizeEstimateText(text, spec: spec, context: ctx)
            } else {
                estimate = try AIFinalizer.finalizeEstimate(fixture["raw"] ?? .null, spec: spec, context: ctx)
            }
            if let code = fixture["expectedError"]?.stringValue {
                return XCTFail("\(name): expected error \(code), got an estimate")
            }
            let diffs = Self.differences(estimate.contractJSON, fixture["expected"] ?? .null)
            XCTAssertTrue(diffs.isEmpty, "\(name): \(diffs.prefix(5).joined(separator: "; "))")
        } catch let error as AIOutputError {
            XCTAssertEqual(error.code, fixture["expectedError"]?.stringValue, "\(name): unexpected error")
        } catch {
            XCTFail("\(name): \(error)")
        }
    }

    private func checkLabel(_ name: String, _ fixture: JSONValue) {
        do {
            let reading: LabelReading
            if let text = fixture["rawText"]?.stringValue {
                reading = try AIFinalizer.finalizeLabelText(text, spec: spec)
            } else {
                reading = try AIFinalizer.finalizeLabel(fixture["raw"] ?? .null, spec: spec)
            }
            if let code = fixture["expectedError"]?.stringValue {
                return XCTFail("\(name): expected error \(code), got a reading")
            }
            let diffs = Self.differences(reading.contractJSON, fixture["expected"] ?? .null)
            XCTAssertTrue(diffs.isEmpty, "\(name): \(diffs.prefix(5).joined(separator: "; "))")
        } catch let error as AIOutputError {
            XCTAssertEqual(error.code, fixture["expectedError"]?.stringValue, "\(name): unexpected error")
        } catch {
            XCTFail("\(name): \(error)")
        }
    }

    private func checkContext(_ name: String, _ fixture: JSONValue) {
        for item in fixture["cases"]?.arrayValue ?? [] {
            let notes = item["notes"]?.stringValue ?? ""
            let expected = AIEstimateSpec.NotesContext(weightGiven: item["weightGiven"] == .bool(true),
                                                       measuredReference: item["measuredReference"] == .bool(true))
            XCTAssertEqual(spec.notesContext(notes), expected, "\(name): \(notes)")
        }
    }

    private func checkPrompt(_ name: String, _ fixture: JSONValue) {
        let input = fixture["input"]
        let expected = fixture["expected"]
        let locale = input?["locale"]?.stringValue ?? ""
        let notes = input?["notes"]?.stringValue ?? ""
        XCTAssertEqual(spec.estimateSystemInstruction(locale: locale), expected?["estimateSystemInstruction"]?.stringValue,
                       "\(name): estimate system instruction")
        XCTAssertEqual(spec.estimateRequestText(meal: input?["meal"]?.stringValue ?? "", notes: notes),
                       expected?["estimateRequestText"]?.stringValue, "\(name): estimate request text")
        XCTAssertEqual(spec.labelSystemInstruction(locale: locale), expected?["labelSystemInstruction"]?.stringValue,
                       "\(name): label system instruction")
        XCTAssertEqual(spec.labelRequestText, expected?["labelRequestText"]?.stringValue, "\(name): label request text")
        let context = spec.notesContext(notes)
        XCTAssertEqual(context.weightGiven, expected?["notesContext"]?["weightGiven"] == .bool(true), "\(name): weightGiven")
        XCTAssertEqual(context.measuredReference, expected?["notesContext"]?["measuredReference"] == .bool(true),
                       "\(name): measuredReference")

        let schemas: [(String, JSONValue)] = [
            ("estimateSchema", spec.estimateSchema()),
            ("estimateSchemaGemini", AIEstimateSpec.forGemini(spec.estimateSchema())),
            ("labelSchema", spec.labelSchema()),
            ("labelSchemaGemini", AIEstimateSpec.forGemini(spec.labelSchema())),
        ]
        for (key, actual) in schemas {
            XCTAssertEqual(actual, expected?[key], "\(name): \(key)")
            // The providers answer in property order, so the order is part of the contract too.
            XCTAssertEqual(actual.serialized(), expected?[key]?.serialized(), "\(name): \(key) key order")
        }
    }

    // MARK: Comparison

    /// Paths where `actual` differs from `expected` under the fixture rule.
    static func differences(_ actual: JSONValue?, _ expected: JSONValue, path: String = "$") -> [String] {
        switch expected {
        case .null:
            return actual == nil || actual == .null ? [] : ["\(path): expected null, got \(actual!.serialized())"]
        case .number(let e):
            guard case .number(let a)? = actual else { return ["\(path): expected \(e), got \(actual?.serialized() ?? "nothing")"] }
            return abs(a - e) <= 1e-9 ? [] : ["\(path): expected \(e), got \(a)"]
        case .bool, .string:
            return actual == expected ? [] : ["\(path): expected \(expected.serialized()), got \(actual?.serialized() ?? "nothing")"]
        case .array(let items):
            guard case .array(let actualItems)? = actual, actualItems.count == items.count else {
                return ["\(path): expected \(items.count) items, got \(actual?.serialized() ?? "nothing")"]
            }
            return zip(actualItems, items).enumerated().flatMap { index, pair in
                differences(pair.0, pair.1, path: "\(path)[\(index)]")
            }
        case .object(let object):
            guard case .object(let actualObject)? = actual else { return ["\(path): expected an object"] }
            var out: [String] = []
            for key in actualObject.keys where object[key] == nil { out.append("\(path).\(key): unexpected key") }
            for (key, value) in object.entries { out += differences(actualObject[key], value, path: "\(path).\(key)") }
            return out
        }
    }
}
