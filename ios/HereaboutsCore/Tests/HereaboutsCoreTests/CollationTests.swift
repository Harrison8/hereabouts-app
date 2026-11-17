// Checks that hereaboutsCompare puts strings in the website's order: collation.json lists every plant's common
// and Latin name plus crafted strings, sorted by the website's own sortPlants (JavaScript localeCompare).
import Foundation
import XCTest
@testable import HereaboutsCore

/// A fixed shuffle (SplitMix64), so a failing order can be repeated.
private struct SplitMix: RandomNumberGenerator {
    var state: UInt64
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

final class CollationTests: XCTestCase {
    /// The website's sorted list from collation.json.
    private func websiteOrder() -> [String] {
        let cases = Expected.cases("collation.json")
        XCTAssertEqual(cases.count, 1)
        return (cases.first?["sorted"] as? [Any])?.compactMap { $0 as? String } ?? []
    }

    func testCollationMatchesWebsite() {
        let want = websiteOrder()
        XCTAssertGreaterThan(want.count, 300)

        // Sorting a shuffled copy gives the website's order.
        var rng = SplitMix(state: 20_261_116)
        let shuffled = want.shuffled(using: &rng)
        let got = PlantList.stableSorted(shuffled) { i, j in Collation.order(shuffled[i], shuffled[j]) }
        let moved = zip(got, want).enumerated().filter { $0.element.0 != $0.element.1 }
        if let first = moved.first {
            XCTFail("\(moved.count) of \(want.count) strings out of place; first at \(first.offset): expected \"\(first.element.1)\", got \"\(first.element.0)\"")
        }

        // Every pair compares the right way round, both ways: no two strings tie.
        var wrong: [String] = []
        var pairs = 0
        for i in want.indices {
            for j in want.indices where i < j {
                pairs += 1
                let forward = hereaboutsCompare(want[i], want[j])
                let backward = hereaboutsCompare(want[j], want[i])
                if forward != .orderedAscending || backward != .orderedDescending {
                    wrong.append("\"\(want[i])\" vs \"\(want[j])\": \(forward.rawValue), \(backward.rawValue)")
                }
            }
        }
        if !wrong.isEmpty {
            XCTFail("\(wrong.count) of \(pairs) pairs compare the wrong way; first: " + wrong.prefix(5).joined(separator: "; "))
        }
    }

    func testTableIsTheSharedList() throws {
        // collation.json's table is scripts/app/collation-table.mjs, the list the bundle check uses to refuse plant
        // names this comparator cannot sort. Collation.table must hold exactly the same characters and weights.
        let rows = try XCTUnwrap(Expected.field("collation.json", "table") as? [Any], "collation.json has no table")
        var want: [[UInt32]] = []
        for row in rows {
            let numbers = try XCTUnwrap(row as? [Any]).map { (x: Any) -> UInt32 in
                guard let d = x as? Double, d >= 0, d < 4_294_967_296 else { return UInt32.max }
                return UInt32(d)
            }
            want.append(numbers)
        }
        let got: [[UInt32]] = Collation.table.keys.sorted().map { (cp: UInt32) -> [UInt32] in
            let w = Collation.table[cp]!
            return [cp, w.primary, w.secondary, w.tertiary]
        }
        XCTAssertGreaterThan(want.count, 130)
        XCTAssertEqual(got.count, want.count)
        let differ = zip(got, want).filter { $0.0 != $0.1 }
        if let first = differ.first {
            XCTFail("\(differ.count) rows differ; first: Swift \(first.0), shared list \(first.1)")
        }
    }

    func testReadableExamples() {
        // The brief's example order: spaces, then digits one at a time, then letters; accents after plain letters;
        // lower case before upper case.
        let order = [" apple", "1 apple", "10 apples", "2 apples", "a1", "a10", "a2", "A2", "apple", "Apple", "\u{E4}pple"]
        for (a, b) in zip(order, order.dropFirst()) {
            XCTAssertEqual(hereaboutsCompare(a, b), .orderedAscending, "\(a) < \(b)")
            XCTAssertEqual(hereaboutsCompare(b, a), .orderedDescending, "\(b) > \(a)")
        }
        XCTAssertEqual(hereaboutsCompare("Jack in the Pulpit", "Jack in the Pulpit"), .orderedSame)
        // A precomposed letter and the same letter written as letter plus accent are the same string to ICU.
        XCTAssertEqual(hereaboutsCompare("\u{E9}clair", "e\u{301}clair"), .orderedSame)
        // cote < coté < côte < côté: the first accent difference decides.
        let accents = ["cote", "cot\u{E9}", "c\u{F4}te", "c\u{F4}t\u{E9}"]
        for (a, b) in zip(accents, accents.dropFirst()) {
            XCTAssertEqual(hereaboutsCompare(a, b), .orderedAscending, "\(a) < \(b)")
        }
    }
}
