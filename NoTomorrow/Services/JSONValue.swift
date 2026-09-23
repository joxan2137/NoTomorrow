import Foundation

// MARK: - Ordered JSON

/// A JSON value that keeps object key order. The AI paths need it twice: the response schemas are sent to the
/// providers in the spec's property order (the models answer in that order: identify, portion, then density), and the
/// model's answer is parsed exactly like JavaScript `JSON.parse` does on the backend, so the shared finalizer fixtures
/// agree bit for bit (numbers go through `Double(String)`, which rounds correctly; `JSONSerialization` may not).
enum JSONValue: Equatable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object(JSONObject)

    var objectValue: JSONObject? { if case .object(let o) = self { return o }; return nil }
    var arrayValue: [JSONValue]? { if case .array(let a) = self { return a }; return nil }
    var stringValue: String? { if case .string(let s) = self { return s }; return nil }
    var numberValue: Double? { if case .number(let n) = self { return n }; return nil }
    var boolValue: Bool? { if case .bool(let b) = self { return b }; return nil }

    /// Member of an object; nil for anything else.
    subscript(key: String) -> JSONValue? { objectValue?[key] }

    // MARK: Parsing

    struct ParseError: Error, Equatable {
        /// Byte offset in the UTF-8 input.
        let offset: Int
    }

    /// Strict RFC 8259 JSON, as `JSON.parse`: no trailing commas, no single quotes, only whitespace around the value.
    /// A duplicated key keeps its first position and its last value.
    static func parse(_ text: String) throws -> JSONValue {
        var parser = JSONParser(bytes: Array(text.utf8))
        return try parser.document()
    }

    static func parse(_ data: Data) throws -> JSONValue {
        var parser = JSONParser(bytes: [UInt8](data))
        return try parser.document()
    }

    // MARK: Serialising

    /// Compact JSON with object keys in order. Integral numbers print without a fraction (`8192`), like JavaScript.
    func serialized() -> String {
        var out = ""
        write(to: &out)
        return out
    }

    func serializedData() -> Data { Data(serialized().utf8) }

    private func write(to out: inout String) {
        switch self {
        case .null: out += "null"
        case .bool(let b): out += b ? "true" : "false"
        case .number(let n): out += Self.numberText(n)
        case .string(let s): out += Self.stringLiteral(s)
        case .array(let items):
            out += "["
            for (index, item) in items.enumerated() {
                if index > 0 { out += "," }
                item.write(to: &out)
            }
            out += "]"
        case .object(let object):
            out += "{"
            for (index, entry) in object.entries.enumerated() {
                if index > 0 { out += "," }
                out += Self.stringLiteral(entry.key)
                out += ":"
                entry.value.write(to: &out)
            }
            out += "}"
        }
    }

    private static func numberText(_ n: Double) -> String {
        guard n.isFinite else { return "null" }
        if n == n.rounded(), abs(n) < 1e15 { return String(Int64(n)) }
        return "\(n)"
    }

    /// Exactly JavaScript `JSON.stringify(string)`: `"` and `\` escaped, U+0008/9/A/C/D as `\b \t \n \f \r`, any other
    /// code point below U+0020 as `\u00xx` (lower-case hex), everything else (`/` and Polish letters included) as is.
    /// Not `JSONEncoder`, which escapes `/` as `\/`.
    static func stringLiteral(_ s: String) -> String {
        var out = "\""
        for scalar in s.unicodeScalars {
            switch scalar.value {
            case 0x22: out += "\\\""
            case 0x5C: out += "\\\\"
            case 0x08: out += "\\b"
            case 0x09: out += "\\t"
            case 0x0A: out += "\\n"
            case 0x0C: out += "\\f"
            case 0x0D: out += "\\r"
            case 0..<0x20: out += String(format: "\\u%04x", scalar.value)
            default: out.unicodeScalars.append(scalar)
            }
        }
        out += "\""
        return out
    }
}

/// JSON object members in insertion order. Equality ignores order, as JSON does.
struct JSONObject: Equatable, Sendable {
    private(set) var keys: [String] = []
    private var storage: [String: JSONValue] = [:]

    init() {}

    init(_ pairs: KeyValuePairs<String, JSONValue>) {
        for (key, value) in pairs { self[key] = value }
    }

    /// Setting a new key appends it; setting an existing key keeps its position; `nil` removes it.
    subscript(key: String) -> JSONValue? {
        get { storage[key] }
        set {
            if let newValue {
                if storage.updateValue(newValue, forKey: key) == nil { keys.append(key) }
            } else if storage.removeValue(forKey: key) != nil {
                keys.removeAll { $0 == key }
            }
        }
    }

    var count: Int { keys.count }

    var entries: [(key: String, value: JSONValue)] {
        keys.compactMap { key in storage[key].map { (key, $0) } }
    }

    static func == (lhs: JSONObject, rhs: JSONObject) -> Bool { lhs.storage == rhs.storage }
}

extension JSONValue: ExpressibleByStringLiteral, ExpressibleByBooleanLiteral, ExpressibleByIntegerLiteral,
    ExpressibleByFloatLiteral, ExpressibleByArrayLiteral, ExpressibleByDictionaryLiteral {
    init(stringLiteral value: String) { self = .string(value) }
    init(booleanLiteral value: Bool) { self = .bool(value) }
    init(integerLiteral value: Int) { self = .number(Double(value)) }
    init(floatLiteral value: Double) { self = .number(value) }
    init(arrayLiteral elements: JSONValue...) { self = .array(elements) }
    init(dictionaryLiteral elements: (String, JSONValue)...) {
        var object = JSONObject()
        for (key, value) in elements { object[key] = value }
        self = .object(object)
    }
}

// MARK: - Parser

private struct JSONParser {
    let bytes: [UInt8]
    var i = 0

    private static let maxDepth = 512

    mutating func document() throws -> JSONValue {
        skipWhitespace()
        let value = try value(depth: 0)
        skipWhitespace()
        guard i == bytes.count else { throw JSONValue.ParseError(offset: i) }
        return value
    }

    private var failure: JSONValue.ParseError { JSONValue.ParseError(offset: i) }

    private mutating func skipWhitespace() {
        while i < bytes.count, [0x20, 0x09, 0x0A, 0x0D].contains(bytes[i]) { i += 1 }
    }

    private mutating func value(depth: Int) throws -> JSONValue {
        guard depth < Self.maxDepth, i < bytes.count else { throw failure }
        switch bytes[i] {
        case UInt8(ascii: "{"): return try object(depth: depth)
        case UInt8(ascii: "["): return try array(depth: depth)
        case UInt8(ascii: "\""): return .string(try string())
        case UInt8(ascii: "t"): try literal("true"); return .bool(true)
        case UInt8(ascii: "f"): try literal("false"); return .bool(false)
        case UInt8(ascii: "n"): try literal("null"); return .null
        case UInt8(ascii: "-"), UInt8(ascii: "0")...UInt8(ascii: "9"): return .number(try number())
        default: throw failure
        }
    }

    private mutating func literal(_ word: String) throws {
        for byte in word.utf8 {
            guard i < bytes.count, bytes[i] == byte else { throw failure }
            i += 1
        }
    }

    private mutating func object(depth: Int) throws -> JSONValue {
        i += 1
        var object = JSONObject()
        skipWhitespace()
        if i < bytes.count, bytes[i] == UInt8(ascii: "}") {
            i += 1
            return .object(object)
        }
        while true {
            skipWhitespace()
            guard i < bytes.count, bytes[i] == UInt8(ascii: "\"") else { throw failure }
            let key = try string()
            skipWhitespace()
            guard i < bytes.count, bytes[i] == UInt8(ascii: ":") else { throw failure }
            i += 1
            skipWhitespace()
            object[key] = try value(depth: depth + 1)
            skipWhitespace()
            guard i < bytes.count else { throw failure }
            if bytes[i] == UInt8(ascii: ",") { i += 1; continue }
            if bytes[i] == UInt8(ascii: "}") { i += 1; return .object(object) }
            throw failure
        }
    }

    private mutating func array(depth: Int) throws -> JSONValue {
        i += 1
        var items: [JSONValue] = []
        skipWhitespace()
        if i < bytes.count, bytes[i] == UInt8(ascii: "]") {
            i += 1
            return .array(items)
        }
        while true {
            skipWhitespace()
            items.append(try value(depth: depth + 1))
            skipWhitespace()
            guard i < bytes.count else { throw failure }
            if bytes[i] == UInt8(ascii: ",") { i += 1; continue }
            if bytes[i] == UInt8(ascii: "]") { i += 1; return .array(items) }
            throw failure
        }
    }

    private mutating func string() throws -> String {
        i += 1
        var buffer: [UInt8] = []
        while i < bytes.count {
            let byte = bytes[i]
            switch byte {
            case UInt8(ascii: "\""):
                i += 1
                return String(decoding: buffer, as: UTF8.self)
            case UInt8(ascii: "\\"):
                i += 1
                guard i < bytes.count else { throw failure }
                let escape = bytes[i]
                i += 1
                switch escape {
                case UInt8(ascii: "\""): buffer.append(0x22)
                case UInt8(ascii: "\\"): buffer.append(0x5C)
                case UInt8(ascii: "/"): buffer.append(0x2F)
                case UInt8(ascii: "b"): buffer.append(0x08)
                case UInt8(ascii: "f"): buffer.append(0x0C)
                case UInt8(ascii: "n"): buffer.append(0x0A)
                case UInt8(ascii: "r"): buffer.append(0x0D)
                case UInt8(ascii: "t"): buffer.append(0x09)
                case UInt8(ascii: "u"):
                    var unit = try hex4()
                    var scalar: Unicode.Scalar?
                    if (0xD800...0xDBFF).contains(unit), i + 1 < bytes.count,
                       bytes[i] == UInt8(ascii: "\\"), bytes[i + 1] == UInt8(ascii: "u") {
                        let mark = i
                        i += 2
                        let low = try hex4()
                        if (0xDC00...0xDFFF).contains(low) {
                            unit = 0x10000 + ((unit - 0xD800) << 10) + (low - 0xDC00)
                            scalar = Unicode.Scalar(unit)
                        } else {
                            i = mark
                        }
                    } else {
                        scalar = Unicode.Scalar(unit)
                    }
                    // A lone surrogate cannot live in a Swift string; JavaScript keeps it, we substitute U+FFFD.
                    buffer.append(contentsOf: String(Character(scalar ?? "\u{FFFD}")).utf8)
                default:
                    throw failure
                }
            case 0x00..<0x20:
                throw failure
            default:
                buffer.append(byte)
                i += 1
            }
        }
        throw failure
    }

    private mutating func hex4() throws -> UInt32 {
        guard i + 4 <= bytes.count else { throw failure }
        var value: UInt32 = 0
        for _ in 0..<4 {
            let byte = bytes[i]
            let digit: UInt32
            switch byte {
            case UInt8(ascii: "0")...UInt8(ascii: "9"): digit = UInt32(byte - UInt8(ascii: "0"))
            case UInt8(ascii: "a")...UInt8(ascii: "f"): digit = UInt32(byte - UInt8(ascii: "a") + 10)
            case UInt8(ascii: "A")...UInt8(ascii: "F"): digit = UInt32(byte - UInt8(ascii: "A") + 10)
            default: throw failure
            }
            value = value * 16 + digit
            i += 1
        }
        return value
    }

    /// `-? (0 | [1-9][0-9]*) (. [0-9]+)? ([eE] [+-]? [0-9]+)?`, converted with `Double(String)` (correctly rounded).
    private mutating func number() throws -> Double {
        let start = i
        if bytes[i] == UInt8(ascii: "-") { i += 1 }
        guard i < bytes.count, isDigit(bytes[i]) else { throw failure }
        if bytes[i] == UInt8(ascii: "0") {
            i += 1
        } else {
            while i < bytes.count, isDigit(bytes[i]) { i += 1 }
        }
        if i < bytes.count, bytes[i] == UInt8(ascii: ".") {
            i += 1
            guard i < bytes.count, isDigit(bytes[i]) else { throw failure }
            while i < bytes.count, isDigit(bytes[i]) { i += 1 }
        }
        if i < bytes.count, bytes[i] == UInt8(ascii: "e") || bytes[i] == UInt8(ascii: "E") {
            i += 1
            if i < bytes.count, bytes[i] == UInt8(ascii: "+") || bytes[i] == UInt8(ascii: "-") { i += 1 }
            guard i < bytes.count, isDigit(bytes[i]) else { throw failure }
            while i < bytes.count, isDigit(bytes[i]) { i += 1 }
        }
        guard let value = Double(String(decoding: bytes[start..<i], as: UTF8.self)) else { throw failure }
        return value
    }

    private func isDigit(_ byte: UInt8) -> Bool { byte >= UInt8(ascii: "0") && byte <= UInt8(ascii: "9") }
}
