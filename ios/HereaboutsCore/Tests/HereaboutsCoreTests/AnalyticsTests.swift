// Checks Analytics against expected/analytics.json: the app's copies of the website's event list and once-per-session
// list, and what the website's cleanEvent keeps for every case.
import Foundation
import XCTest
@testable import HereaboutsCore

final class AnalyticsTests: XCTestCase {
    private func value(_ x: Any) -> EventValue? {
        switch JSON(any: x) {
        case .bool(let b): return .bool(b)
        case .number(let n): return .number(n)
        case .string(let s): return .string(s)
        default: return nil
        }
    }

    private func json(_ v: EventValue) -> JSON {
        switch v {
        case .bool(let b): return .bool(b)
        case .number(let n): return .number(n)
        case .string(let s): return .string(s)
        }
    }

    func testListsMatchWebsite() {
        let allowed = Expected.field("analytics.json", "allowed") as? [String: Any] ?? [:]
        let want = allowed.mapValues { ($0 as? [Any] ?? []).compactMap { $0 as? String } }
        XCTAssertFalse(want.isEmpty)
        XCTAssertEqual(Analytics.allowed, want)
        let once = (Expected.field("analytics.json", "onceKeys") as? [Any] ?? []).compactMap { $0 as? String }
        XCTAssertFalse(once.isEmpty)
        XCTAssertEqual(Analytics.onceKeys, once)
    }

    func testEveryExpectedCase() {
        let cases = Expected.cases("analytics.json")
        var bad: [String] = []
        for (index, c) in cases.enumerated() {
            if Set(c.keys) != ["event", "props", "result"] { bad.append("case \(index): fields \(c.keys.sorted())"); continue }
            let event = c["event"] as? String ?? ""
            var props: [String: EventValue] = [:]
            for (k, v) in c["props"] as? [String: Any] ?? [:] {
                guard let ev = value(v) else { bad.append("case \(index): property \(k) is not text, a number or true/false"); continue }
                props[k] = ev
            }
            let got = Analytics.clean(event: event, props: props).map { JSON.object($0.mapValues { self.json($0) }) } ?? .null
            let want = JSON(any: c["result"])
            if got != want { bad.append("case \(index) \(event): got \(got), want \(want)") }
        }
        XCTAssertGreaterThan(cases.count, 0)
        XCTAssertTrue(bad.isEmpty, "\(bad.count) mismatches in analytics.json; first: \(bad.prefix(5).joined(separator: " | "))")
        print("analytics.json: \(cases.count) cases checked")
    }

    func testEventValueRoundTrip() throws {
        let props: [String: EventValue] = ["a": .string("x"), "b": .number(12), "c": .bool(true)]
        let data = try JSONEncoder().encode(props)
        XCTAssertEqual(try JSONDecoder().decode([String: EventValue].self, from: data), props)
    }
}
