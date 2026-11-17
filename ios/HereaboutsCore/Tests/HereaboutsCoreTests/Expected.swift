// Reads the expected answers in apps/shared/bundle/expected/ (made by `npm run app:bundle` from the website's own
// code) and compares them with what the Swift code gives.
//
// The files are parsed by the small JSON reader below, not JSONSerialization, so the values have the same Swift
// types on Apple systems and on Linux: true/false become Bool, every number becomes Double, null becomes NSNull.
// (JSONSerialization returns NSNumber, and NSNumber mixes up Bool, Int and Double differently on the two.)
import Foundation
import XCTest

/// A JSON value, for comparing an answer with the expected one. Numbers are Doubles, so 36 and 36.0 are equal.
enum JSON: Equatable, CustomStringConvertible {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSON])
    case object([String: JSON])

    var description: String {
        switch self {
        case .null: return "null"
        case .bool(let b): return b ? "true" : "false"
        case .number(let n): return n == n.rounded() && abs(n) < 1e15 ? String(Int(n)) : String(n)
        case .string(let s): return "\"\(s)\""
        case .array(let a): return "[" + a.map { $0.description }.joined(separator: ",") + "]"
        case .object(let o): return "{" + o.keys.sorted().map { "\"\($0)\":\(o[$0]!.description)" }.joined(separator: ",") + "}"
        }
    }

    /// The value as plain Swift: NSNull, Bool, Double, String, [Any] or [String: Any].
    var any: Any {
        switch self {
        case .null: return NSNull()
        case .bool(let b): return b
        case .number(let n): return n
        case .string(let s): return s
        case .array(let a): return a.map { $0.any }
        case .object(let o): return o.mapValues { $0.any }
        }
    }

    /// Turns a plain Swift value back into JSON. Bool is told apart from numbers by its exact type first, so an
    /// NSNumber holding 1 is never read as true; Int and Double both become numbers.
    init(any x: Any?) {
        guard let x, !(x is NSNull) else { self = .null; return }
        if let j = x as? JSON { self = j; return }
        let t = type(of: x)
        if t == Bool.self { self = .bool(x as! Bool); return }
        if t == Double.self { self = .number(x as! Double); return }
        if t == Int.self { self = .number(Double(x as! Int)); return }
        if t == String.self { self = .string(x as! String); return }
        if let n = x as? NSNumber {
            #if canImport(Darwin)
            if CFGetTypeID(n) == CFBooleanGetTypeID() { self = .bool(n.boolValue); return }
            #endif
            self = .number(n.doubleValue)
            return
        }
        if let s = x as? String { self = .string(s); return }
        if let a = x as? [Any] { self = .array(a.map { JSON(any: $0) }); return }
        if let o = x as? [String: Any] { self = .object(o.mapValues { JSON(any: $0) }); return }
        fatalError("not a JSON value: \(x) (\(t))")
    }
}

/// A strict JSON reader (RFC 8259) over UTF-8 bytes. Numbers are read with Double(String), which rounds correctly,
/// the same as JavaScript's JSON.parse.
struct JSONReader {
    struct Failure: Error, CustomStringConvertible {
        let description: String
    }

    private let b: [UInt8]
    private var i = 0

    static func parse(_ data: Data) throws -> JSON {
        var r = JSONReader(b: [UInt8](data))
        r.skipSpace()
        let v = try r.value()
        r.skipSpace()
        guard r.i == r.b.count else { throw r.fail("text after the value") }
        return v
    }

    private init(b: [UInt8]) {
        self.b = b
    }

    private func fail(_ what: String) -> Failure {
        Failure(description: "JSON: \(what) at byte \(i)")
    }

    private mutating func skipSpace() {
        while i < b.count, b[i] == 0x20 || b[i] == 0x0A || b[i] == 0x0D || b[i] == 0x09 { i += 1 }
    }

    private mutating func expect(_ word: String) throws {
        for c in word.utf8 {
            guard i < b.count, b[i] == c else { throw fail("expected \(word)") }
            i += 1
        }
    }

    private mutating func value() throws -> JSON {
        guard i < b.count else { throw fail("unexpected end") }
        switch b[i] {
        case UInt8(ascii: "{"):
            i += 1
            var o: [String: JSON] = [:]
            skipSpace()
            if i < b.count, b[i] == UInt8(ascii: "}") { i += 1; return .object(o) }
            while true {
                skipSpace()
                guard i < b.count, b[i] == UInt8(ascii: "\"") else { throw fail("expected a key") }
                let k = try string()
                skipSpace()
                try expect(":")
                skipSpace()
                o[k] = try value()
                skipSpace()
                guard i < b.count else { throw fail("unexpected end") }
                if b[i] == UInt8(ascii: ",") { i += 1; continue }
                if b[i] == UInt8(ascii: "}") { i += 1; return .object(o) }
                throw fail("expected , or }")
            }
        case UInt8(ascii: "["):
            i += 1
            var a: [JSON] = []
            skipSpace()
            if i < b.count, b[i] == UInt8(ascii: "]") { i += 1; return .array(a) }
            while true {
                skipSpace()
                a.append(try value())
                skipSpace()
                guard i < b.count else { throw fail("unexpected end") }
                if b[i] == UInt8(ascii: ",") { i += 1; continue }
                if b[i] == UInt8(ascii: "]") { i += 1; return .array(a) }
                throw fail("expected , or ]")
            }
        case UInt8(ascii: "\""):
            return .string(try string())
        case UInt8(ascii: "t"):
            try expect("true")
            return .bool(true)
        case UInt8(ascii: "f"):
            try expect("false")
            return .bool(false)
        case UInt8(ascii: "n"):
            try expect("null")
            return .null
        default:
            let start = i
            let numberBytes = Set("0123456789-+.eE".utf8)
            while i < b.count && numberBytes.contains(b[i]) { i += 1 }
            guard i > start, let text = String(bytes: b[start..<i], encoding: .utf8), let n = Double(text) else {
                throw fail("bad number")
            }
            return .number(n)
        }
    }

    private mutating func hex4() throws -> UInt32 {
        guard i + 4 <= b.count, let text = String(bytes: b[i..<(i + 4)], encoding: .utf8), let v = UInt32(text, radix: 16) else {
            throw fail("bad \\u escape")
        }
        i += 4
        return v
    }

    private mutating func string() throws -> String {
        i += 1 // the opening quote
        var bytes: [UInt8] = []
        while true {
            guard i < b.count else { throw fail("unterminated string") }
            let c = b[i]
            i += 1
            if c == UInt8(ascii: "\"") { break }
            if c != UInt8(ascii: "\\") { bytes.append(c); continue }
            guard i < b.count else { throw fail("unterminated escape") }
            let e = b[i]
            i += 1
            switch e {
            case UInt8(ascii: "\""): bytes.append(0x22)
            case UInt8(ascii: "\\"): bytes.append(0x5C)
            case UInt8(ascii: "/"): bytes.append(0x2F)
            case UInt8(ascii: "b"): bytes.append(0x08)
            case UInt8(ascii: "f"): bytes.append(0x0C)
            case UInt8(ascii: "n"): bytes.append(0x0A)
            case UInt8(ascii: "r"): bytes.append(0x0D)
            case UInt8(ascii: "t"): bytes.append(0x09)
            case UInt8(ascii: "u"):
                var v = try hex4()
                if v >= 0xD800 && v < 0xDC00, i + 6 <= b.count, b[i] == UInt8(ascii: "\\"), b[i + 1] == UInt8(ascii: "u") {
                    let save = i
                    i += 2
                    let low = try hex4()
                    if low >= 0xDC00 && low < 0xE000 {
                        v = 0x10000 + ((v - 0xD800) << 10) + (low - 0xDC00)
                    } else {
                        i = save
                    }
                }
                // A lone surrogate cannot be a Swift String; it becomes U+FFFD.
                let scalar = Unicode.Scalar(v) ?? "\u{FFFD}"
                bytes.append(contentsOf: Array(String(Character(scalar)).utf8))
            default:
                throw fail("bad escape")
            }
        }
        guard let s = String(bytes: bytes, encoding: .utf8) else { throw fail("string is not UTF-8") }
        return s
    }
}

enum Expected {
    /// apps/shared/bundle, found from this file's place: apps/ios/HereaboutsCore/Tests/HereaboutsCoreTests/Expected.swift.
    static var bundleDir: URL {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() } // up to apps/
        return url.appendingPathComponent("shared").appendingPathComponent("bundle")
    }

    /// The parsed file apps/shared/bundle/expected/<file>. Fails the running test if the file is missing or broken.
    static func file(_ file: String) -> JSON {
        let url = bundleDir.appendingPathComponent("expected").appendingPathComponent(file)
        do {
            return try JSONReader.parse(try Data(contentsOf: url))
        } catch {
            XCTFail("Could not read \(url.path): \(error). Run `npm run app:bundle` first.")
            return .null
        }
    }

    /// The `cases` of apps/shared/bundle/expected/<file>, each as a plain dictionary.
    static func cases(_ file: String) -> [[String: Any]] {
        guard case .object(let o) = Self.file(file), case .array(let cases)? = o["cases"] else {
            XCTFail("\(file) has no cases list")
            return []
        }
        return cases.map { ($0.any as? [String: Any]) ?? [:] }
    }

    /// A top-level field other than `cases` of apps/shared/bundle/expected/<file> (list.json's sortMenu,
    /// collation.json's table), as plain Swift, or nil when the file has no such field.
    static func field(_ file: String, _ key: String) -> Any? {
        guard case .object(let o) = Self.file(file) else {
            XCTFail("\(file) is not a JSON object")
            return nil
        }
        return o[key]?.any
    }

    /// The parsed JSON of a file in the bundle, like "data/plants.json".
    static func bundleJSON(_ path: String) throws -> JSON {
        try JSONReader.parse(try Data(contentsOf: bundleDir.appendingPathComponent(path)))
    }
}
