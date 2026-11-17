// Checks Care, the port of scripts/newsletter/care-core.mjs, on made-up rules and timing dates. The answers were
// worked out by running care-core.mjs itself in Node. The whole path through the real rules and every county's
// timing is checked against expected/care.json in RemindersTests.
import Foundation
import XCTest
@testable import HereaboutsCore

final class CareTests: XCTestCase {
    private let anchors = Anchors(lastSpring: 100, firstFall: 300, hardSpring: 90, hardFall: 310, highs50: nil)

    private func rule(_ id: String, _ order: Double, pair: String? = nil, from: (String, Int) = ("lastSpring", 0),
                      to: (String, Int) = ("lastSpring", 6), categories: [String] = ["perennial"]) -> CareRule {
        CareRule(id: id, heading: "H \(id)", categories: categories, from: .init(anchor: from.0, days: from.1),
                 to: .init(anchor: to.0, days: to.1), facts: ["f \(id)"], pair: pair, order: order)
    }

    private func plants() throws -> [Plant] {
        let text = """
        [{"id":"b-plant","common":"Bee Balm","sci":"B b","category":"perennial"},
         {"id":"a-plant","common":"Aster","sci":"A a","category":"perennial"},
         {"id":"oak","common":"Oak","sci":"Q q","category":"tree"},
         {"id":"Z-plant","common":"Zed","sci":"Z z","category":"perennial"}]
        """
        return try JSONDecoder().decode([Plant].self, from: Data(text.utf8))
    }

    private var rules: [CareRule] {
        [
            rule("r5", 5), rule("r1", 1), rule("r1b", 1),
            rule("p2", 2, pair: "pp"), rule("p9", 9, pair: "pp", from: ("firstFall", 0), to: ("firstFall", 6)),
            rule("r3", 3), rule("t0", 0, categories: ["tree"]), rule("r4", 4),
        ]
    }

    func testRuleWindow() {
        let w1 = Care.ruleWindow(rule("a", 1, from: ("firstFall", 0), to: ("lastSpring", 0)), anchors: anchors)
        XCTAssertEqual(w1, Care.Window(start: 300, end: 100))
        let w2 = Care.ruleWindow(rule("b", 1, from: ("lastSpring", -120), to: ("lastSpring", 250)), anchors: anchors)
        XCTAssertEqual(w2, Care.Window(start: 1, end: 365))
        XCTAssertNil(Care.ruleWindow(rule("c", 1, from: ("highs50", 0), to: ("highs50", 27)), anchors: anchors))
        XCTAssertNil(Care.ruleWindow(rule("d", 1, from: ("unknown", 0), to: ("lastSpring", 0)), anchors: anchors))
        XCTAssertNil(Care.ruleWindow(rule("e", 1), anchors: nil))
        let w3 = Care.ruleWindow(rule("f", 1, from: ("lastSpring", -110), to: ("lastSpring", -95)), anchors: anchors)
        XCTAssertEqual(w3, Care.Window(start: 355, end: 5))
    }

    func testIsDueWeek() {
        XCTAssertTrue(Care.isDueWeek(Care.Window(start: 300, end: 100), sendDay: 365))
        XCTAssertFalse(Care.isDueWeek(Care.Window(start: 5, end: 10), sendDay: 362))
        XCTAssertFalse(Care.isDueWeek(Care.Window(start: 5, end: 10), sendDay: 361))
        XCTAssertFalse(Care.isDueWeek(Care.Window(start: 5, end: 10), sendDay: 363))
        XCTAssertTrue(Care.isDueWeek(Care.Window(start: 5, end: 10), sendDay: 364))
        XCTAssertFalse(Care.isDueWeek(Care.Window(start: 100, end: 120), sendDay: 93))
        XCTAssertTrue(Care.isDueWeek(Care.Window(start: 100, end: 120), sendDay: 94))
        XCTAssertTrue(Care.isDueWeek(Care.Window(start: 360, end: 365), sendDay: 400))
        XCTAssertFalse(Care.isDueWeek(nil, sendDay: 100))
    }

    func testSendDay() {
        XCTAssertEqual(Care.sendDay("2027-04-10"), 100)
        XCTAssertEqual(Care.sendDay("2028-02-29"), 59)
        XCTAssertEqual(Care.sendDay("2028-03-01"), 60)
        XCTAssertEqual(Care.sendDay("2027-04-40"), 130)
        XCTAssertNil(Care.sendDay("2027-13-01"))
        XCTAssertNil(Care.sendDay("2027-4-10"))
        XCTAssertNil(Care.sendDay("2027-04-10T08:00"))
    }

    private func summary(_ jobs: [Job]) -> [String] {
        jobs.map { "\($0.ruleId) \($0.examplePlantIds) \($0.pair ?? "nil")" }
    }

    func testDueJobsSlotsPairsAndExamples() throws {
        let p = try plants()
        let jobs = Care.dueJobs(rules: rules, anchors: anchors,
                                nativePlantIds: ["b-plant", "a-plant", "not", "Z-plant", "a-plant"],
                                sendDate: "2027-04-10", plants: p)
        let examples = #"["Z-plant", "a-plant", "a-plant"]"#
        XCTAssertEqual(summary(jobs), ["r1 \(examples) nil", "r1b \(examples) nil", "p2 \(examples) pp", "p9 \(examples) pp",
                                       "r3 \(examples) nil"])
        XCTAssertEqual(jobs.first?.heading, "H r1")
        XCTAssertEqual(jobs.first?.facts, ["f r1"])

        let all = Care.dueJobs(rules: rules, anchors: anchors, nativePlantIds: ["oak", "b-plant"], sendDate: "2027-04-10",
                               plants: p, maxExamples: .max)
        XCTAssertEqual(all.map(\.ruleId), ["t0", "r1", "r1b", "p2", "p9"])
        XCTAssertEqual(all.map(\.examplePlantIds), [["oak"], ["b-plant"], ["b-plant"], ["b-plant"], ["b-plant"]])

        let fall = Care.dueJobs(rules: rules, anchors: anchors, nativePlantIds: ["b-plant"], sendDate: "2027-10-27", plants: p)
        XCTAssertEqual(fall.map(\.ruleId), ["p2", "p9"])

        XCTAssertEqual(Care.dueJobs(rules: rules, anchors: anchors, nativePlantIds: ["b-plant"], sendDate: "bad", plants: p), [])
        XCTAssertEqual(Care.dueJobs(rules: rules, anchors: nil, nativePlantIds: ["b-plant"], sendDate: "2027-04-10", plants: p), [])
        let lastDropped = Care.dueJobs(rules: rules, anchors: anchors, nativePlantIds: ["a-plant", "b-plant"],
                                       sendDate: "2027-04-10", plants: p, maxExamples: -1)
        XCTAssertEqual(lastDropped.first?.examplePlantIds, ["a-plant"])
    }

    func testBundleRulesAndAnchorsLoad() throws {
        let tables = Expected.bundleDir.appendingPathComponent("tables")
        let rules = try Care.rules(from: Data(contentsOf: tables.appendingPathComponent("care-rules.json")))
        XCTAssertEqual(rules.count, 14)
        XCTAssertEqual(rules.filter { $0.pair == "stems" }.map(\.id), ["stems-tidy", "stems-pollinator"])
        let anchors = try Care.anchorsByCounty(from: Data(contentsOf: tables.appendingPathComponent("care-anchors.json")))
        XCTAssertEqual(anchors.count, 3109)
        XCTAssertNotNil(anchors["21111"]?.lastSpring)
    }
}
