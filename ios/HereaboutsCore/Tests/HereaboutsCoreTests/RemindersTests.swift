// Checks Reminders against expected/care.json: for each case (a time zone, a moment, a county and some saved plants)
// the Saturday reminders the website's scripts/app/reminders.mjs makes, with the real care rules and the county's
// real timing dates. Then the Saturday and message rules on their own.
import Foundation
import XCTest
@testable import HereaboutsCore

final class RemindersTests: XCTestCase {
    private func calendar(_ tz: String) throws -> Calendar {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = try XCTUnwrap(TimeZone(identifier: tz), "unknown time zone \(tz)")
        return cal
    }

    /// "YYYY-MM-DDTHH:mm" as local time in the calendar's zone.
    private func date(_ text: String, _ cal: Calendar) -> Date? {
        let parts = text.split(whereSeparator: { $0 == "-" || $0 == "T" || $0 == ":" }).compactMap { Int($0) }
        guard parts.count == 5 else { return nil }
        return cal.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: parts[3], minute: parts[4]))
    }

    /// A date as "YYYY-MM-DDTHH:mm" in the calendar's zone.
    private func text(_ date: Date, _ cal: Calendar) -> String {
        let c = cal.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        func pad(_ n: Int?) -> String { let v = n ?? 0; return v < 10 ? "0\(v)" : "\(v)" }
        return "\(c.year ?? 0)-\(pad(c.month))-\(pad(c.day))T\(pad(c.hour)):\(pad(c.minute))"
    }

    func testEveryExpectedCase() throws {
        let data = try AppData.load(from: Expected.bundleDir.appendingPathComponent("data"))
        let tables = Expected.bundleDir.appendingPathComponent("tables")
        let rules = try Care.rules(from: Data(contentsOf: tables.appendingPathComponent("care-rules.json")))
        let anchors = try Care.anchorsByCounty(from: Data(contentsOf: tables.appendingPathComponent("care-anchors.json")))
        let cases = Expected.cases("care.json")
        let fields: Set<String> = ["tz", "now", "county", "savedIds", "result"]
        var bad: [String] = []
        var withReminders = 0
        for (index, c) in cases.enumerated() {
            if Set(c.keys) != fields { bad.append("case \(index): fields \(c.keys.sorted())"); continue }
            let cal = try calendar(c["tz"] as? String ?? "")
            let nowText = c["now"] as? String ?? ""
            guard let now = date(nowText, cal) else { bad.append("case \(index): bad now \(nowText)"); continue }
            let saved = (c["savedIds"] as? [Any] ?? []).compactMap { $0 as? String }
            let county = c["county"] as? String ?? ""
            let got = Reminders.weekly(rules: rules, anchors: anchors[county], savedIds: saved, plants: data.plants, now: now,
                                       calendar: cal)
            var list: [JSON] = []
            for r in got {
                let ruleIds: [JSON] = r.jobs.map { JSON.string($0.ruleId) }
                list.append(JSON.object(["id": JSON.number(Double(r.id)), "at": JSON.string(text(r.at, cal)),
                                         "title": JSON.string(r.title), "body": JSON.string(r.body),
                                         "ruleIds": JSON.array(ruleIds)]))
            }
            let gotJSON = JSON.array(list)
            let want = JSON(any: c["result"])
            if !got.isEmpty { withReminders += 1 }
            if gotJSON != want {
                bad.append("case \(index) (\(c["tz"] ?? "") \(nowText) county \(county) \(saved)): got \(gotJSON), want \(want)")
            }
        }
        XCTAssertGreaterThan(withReminders, 0)
        XCTAssertTrue(bad.isEmpty, "\(bad.count) mismatches in care.json; first: \(bad.prefix(5).joined(separator: " | "))")
        print("care.json: \(cases.count) cases checked, \(withReminders) with reminders")
    }

    func testPhoneCalendarDoesNotChangeTheReminders() throws {
        // A phone set to the Japanese calendar gets the same reminders: the website's dates are always Gregorian.
        let data = try AppData.load(from: Expected.bundleDir.appendingPathComponent("data"))
        let tables = Expected.bundleDir.appendingPathComponent("tables")
        let rules = try Care.rules(from: Data(contentsOf: tables.appendingPathComponent("care-rules.json")))
        let anchors = try Care.anchorsByCounty(from: Data(contentsOf: tables.appendingPathComponent("care-anchors.json")))
        let c = try XCTUnwrap(Expected.cases("care.json").first {
            ($0["tz"] as? String) == "America/New_York" && !(($0["result"] as? [Any]) ?? []).isEmpty
        })
        let gregorian = try calendar("America/New_York")
        var japanese = Calendar(identifier: .japanese)
        japanese.timeZone = gregorian.timeZone
        let now = try XCTUnwrap(date(c["now"] as? String ?? "", gregorian))
        let saved = (c["savedIds"] as? [Any] ?? []).compactMap { $0 as? String }
        let county = c["county"] as? String ?? ""
        let want = Reminders.weekly(rules: rules, anchors: anchors[county], savedIds: saved, plants: data.plants, now: now,
                                    calendar: gregorian)
        let got = Reminders.weekly(rules: rules, anchors: anchors[county], savedIds: saved, plants: data.plants, now: now,
                                   calendar: japanese)
        XCTAssertFalse(want.isEmpty)
        XCTAssertEqual(got, want)
        XCTAssertEqual(got.first.map { Reminders.id(for: $0.at, calendar: japanese) }, want.first?.id)
    }

    func testSaturdaysAhead() throws {
        let cal = try calendar("America/New_York")
        // Saturday 2027-01-02 at 07:59: that morning is the first.
        let early = try XCTUnwrap(date("2027-01-02T07:59", cal))
        let list = Reminders.saturdaysAhead(now: early, calendar: cal)
        XCTAssertEqual(list.count, 8)
        XCTAssertEqual(list.first.map { text($0, cal) }, "2027-01-02T08:00")
        XCTAssertEqual(list.last.map { text($0, cal) }, "2027-02-20T08:00")
        // At 08:00 exactly it is not later than now, so the next Saturday is the first.
        let eight = try XCTUnwrap(date("2027-01-02T08:00", cal))
        XCTAssertEqual(Reminders.saturdaysAhead(now: eight, weeks: 2, calendar: cal).map { text($0, cal) },
                       ["2027-01-09T08:00", "2027-01-16T08:00"])
        // Across the March clock change the time stays 08:00.
        let march = try XCTUnwrap(date("2027-03-10T12:00", cal))
        XCTAssertEqual(Reminders.saturdaysAhead(now: march, weeks: 2, calendar: cal).map { text($0, cal) },
                       ["2027-03-13T08:00", "2027-03-20T08:00"])
        XCTAssertEqual(Reminders.id(for: list[0], calendar: cal), 20270102)
    }

    func testBody() throws {
        let text = """
        [{"id":"b-plant","common":"Bee Balm","sci":"B b","category":"perennial"},
         {"id":"a-plant","common":"Aster","sci":"A a","category":"perennial"},
         {"id":"oak","common":"Oak","sci":"Q q","category":"tree"},
         {"id":"Z-plant","common":"Zed","sci":"Z z","category":"perennial"}]
        """
        let plants = try JSONDecoder().decode([Plant].self, from: Data(text.utf8))
        func job(_ heading: String, _ pair: String?, _ ids: [String]) -> Job {
            Job(ruleId: heading, heading: heading, facts: [], examplePlantIds: ids, pair: pair)
        }
        let jobs = [
            job("One", "x", ["a-plant"]), job("Two", "x", ["b-plant"]), job("Three", nil, ["a-plant", "b-plant", "Z-plant"]),
            job("Four", "", ["nope"]), job("Five", "", ["oak", "a-plant"]),
        ]
        XCTAssertEqual(Reminders.body(jobs: jobs, plants: plants),
                       "One: your aster.\nThree: your aster, bee balm and zed.\nFive: your oak and aster.")
        XCTAssertEqual(Reminders.title, "This week in your garden")
    }
}
