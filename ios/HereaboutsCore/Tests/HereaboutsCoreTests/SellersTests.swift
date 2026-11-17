// Checks the "Where to buy" list against the website's: every case in apps/shared/bundle/expected/sellers.json.
// A case with no kind is buyRowsView(plant, location, "hereabouts.garden") from index.html, run as the phone app
// (channel app) or as the website (channel web). A case with kind "summary" is shipsSummary from sellers.js.
import Foundation
import XCTest
@testable import HereaboutsCore

final class SellersTests: XCTestCase {
    private func loadData() throws -> AppData {
        try AppData.load(from: Expected.bundleDir.appendingPathComponent("data"))
    }

    private func location(_ x: Any?) -> Location? {
        guard let o = x as? [String: Any] else { return nil }
        return Location(countyFips: o["countyFips"] as? String ?? "", countyName: o["countyName"] as? String ?? "",
                        stateFips: o["stateFips"] as? String ?? "", stateName: o["stateName"] as? String ?? "",
                        stateUsps: o["stateUsps"] as? String ?? "", ecoregion: o["ecoregion"] as? String ?? "",
                        lat: o["lat"] as? Double, lon: o["lon"] as? Double)
    }

    private static let rowFields: Set<String> = [
        "name", "short", "place", "href", "label", "ships", "local", "miles", "kind", "directions", "affiliate", "price", "size",
    ]
    private static let viewFields: Set<String> = ["rows", "noSellerText", "hasAffiliate"]
    private static let viewCaseFields: Set<String> = ["plant", "location", "channel", "view"]
    private static let summaryCaseFields: Set<String> = ["kind", "vendor", "category", "entryShipsTo", "result"]

    private func text(_ s: String?) -> JSON { s.map { JSON.string($0) } ?? .null }

    /// A row as the website's row object.
    private func json(_ r: BuyRow) -> JSON {
        .object([
            "name": .string(r.name), "short": .string(r.short), "place": text(r.place), "href": .string(r.href),
            "label": text(r.label), "ships": text(r.ships), "local": .bool(r.local),
            "miles": r.miles.map { JSON.number(Double($0)) } ?? .null, "kind": .string(r.kind),
            "directions": text(r.directions), "affiliate": .bool(r.affiliate), "price": text(r.price), "size": text(r.size),
        ])
    }

    /// The first difference between a view and the expected one, or nil when they are the same.
    private func difference(_ got: BuyView, _ want: [String: Any]) -> String? {
        if Set(want.keys) != Self.viewFields { return "view has fields \(want.keys.sorted())" }
        let wantRows = (want["rows"] as? [Any] ?? []).map { ($0 as? [String: Any]) ?? [:] }
        for (i, row) in wantRows.enumerated() where Set(row.keys) != Self.rowFields {
            return "row \(i) has fields \(row.keys.sorted())"
        }
        if got.rows.count != wantRows.count { return "\(got.rows.count) rows, expected \(wantRows.count)" }
        for (i, (g, w)) in zip(got.rows, wantRows).enumerated() {
            let gj = json(g)
            let wj = JSON(any: w)
            if gj != wj { return "row \(i): got \(gj), expected \(wj)" }
        }
        let wantText = want["noSellerText"] as? String
        if got.noSellerText != wantText { return "noSellerText \(got.noSellerText ?? "nil"), expected \(wantText ?? "nil")" }
        if got.hasAffiliate != (want["hasAffiliate"] as? Bool) { return "hasAffiliate \(got.hasAffiliate)" }
        return nil
    }

    func testEverySellerCase() throws {
        let data = try loadData()
        let byId = Dictionary(data.plants.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        var bad: [String] = []
        var count = 0
        var channels: Set<String> = []
        for (n, c) in Expected.cases("sellers.json").enumerated() {
            if let kind = c["kind"] {
                if (kind as? String) != "summary" { bad.append("case \(n): unknown kind \(kind)") }
                continue
            }
            if Set(c.keys) != Self.viewCaseFields {
                bad.append("case \(n): unknown fields \(c.keys.sorted())")
                continue
            }
            count += 1
            let plantId = c["plant"] as? String ?? ""
            let channelText = c["channel"] as? String ?? ""
            guard let plant = byId[plantId], let channel = Channel(rawValue: channelText), let want = c["view"] as? [String: Any] else {
                bad.append("case \(n): plant \(plantId), channel \(channelText) or view not readable")
                continue
            }
            channels.insert(channelText)
            let loc = location(c["location"])
            if c["location"] != nil, !(c["location"] is NSNull), loc == nil { bad.append("case \(n): location not readable") }
            let got = Sellers.buyView(plant, loc, channel: channel, data: data)
            if let d = difference(got, want) {
                bad.append("case \(n) (\(plantId), \(loc?.countyFips ?? "no location"), \(channelText)): \(d)")
            }
        }
        print("sellers.json: \(count) seller cases")
        XCTAssertGreaterThan(count, 1000)
        XCTAssertEqual(channels, ["app", "web"])
        if !bad.isEmpty {
            XCTFail("\(bad.count) of \(count) seller cases differ; first:\n" + bad.prefix(5).joined(separator: "\n"))
        }
    }

    func testEverySummaryCase() throws {
        let data = try loadData()
        var bad: [String] = []
        var count = 0
        for (n, c) in Expected.cases("sellers.json").enumerated() where c["kind"] != nil {
            guard c["kind"] as? String == "summary" else { continue } // testEverySellerCase fails on other kinds
            if Set(c.keys) != Self.summaryCaseFields {
                bad.append("case \(n): unknown fields \(c.keys.sorted())")
                continue
            }
            count += 1
            let name = c["vendor"] as? String ?? ""
            let category = c["category"] as? String ?? ""
            guard let vendor = data.vendors[name], let want = c["result"] as? String else {
                bad.append("case \(n): vendor \(name) or result not readable")
                continue
            }
            // entry = { shipsTo: entryShipsTo }, or no entry when entryShipsTo is null.
            var entry: VendorEntry? = nil
            if let list = c["entryShipsTo"] as? [Any] {
                entry = VendorEntry(name: name, shipsTo: list.compactMap { $0 as? String })
            } else if !(c["entryShipsTo"] is NSNull) {
                bad.append("case \(n): entryShipsTo not readable")
                continue
            }
            let got = Sellers.shipsSummary(vendor, category: category, entry: entry)
            if got != want { bad.append("case \(n) (\(name), \(category), \(String(describing: c["entryShipsTo"]))): got \"\(got)\", expected \"\(want)\"") }
        }
        print("sellers.json: \(count) summary cases")
        XCTAssertGreaterThan(count, 100)
        if !bad.isEmpty {
            XCTFail("\(bad.count) of \(count) summary cases differ; first:\n" + bad.prefix(5).joined(separator: "\n"))
        }
    }

    func testLowerFortyEightIsTheWebsitesStateList() throws {
        // sellers.js reads the lower 48 from STATE_NAMES, which the bundle carries as tables/states.json. The Swift
        // list comes from location.js's states instead, so a change to either shows here.
        let tables = try Tables.load(from: Expected.bundleDir.appendingPathComponent("tables"))
        XCTAssertEqual(Sellers.contiguousUsps, Set(tables.states.values.map { $0.usps }))
        XCTAssertEqual(Sellers.contiguousUsps.count, 49)
    }

    func testDistanceIsTheLocationLookups() {
        let a = (38.25, -85.75)
        let b = (34.518252, -89.030319)
        XCTAssertEqual(Sellers.distanceMi(a, b), LocationLookup.distanceMi((lat: a.0, lon: a.1), (lat: b.0, lon: b.1)))
        XCTAssertGreaterThan(Sellers.distanceMi(a, b), 300)
        XCTAssertEqual(Sellers.distanceMi(a, a), 0)
    }

    func testMilesRoundTheWayJavaScriptDoes() {
        // "LOCAL · 25 mi" uses Math.round, which sends halves up (toward +infinity), unlike Swift's .rounded().
        XCTAssertEqual(JSText.mathRound(24.5), 25)
        XCTAssertEqual(JSText.mathRound(24.4999), 24)
        XCTAssertEqual(JSText.mathRound(0.5), 1)
        XCTAssertEqual(JSText.mathRound(-2.5), -2)
        XCTAssertEqual(JSText.mathRound(-0.4).sign, .minus) // Math.round(-0.4) is -0
        XCTAssertEqual(JSText.mathRound(75), 75)
    }

    func testNoLocationListsEverySellerWithWhereItShips() throws {
        let data = try loadData()
        let plant = try XCTUnwrap(data.plants.first { $0.vendors.count > 1 })
        let view = Sellers.buyView(plant, nil, channel: .app, data: data)
        XCTAssertEqual(view.rows.map { $0.name }, plant.vendors.map { $0.name })
        XCTAssertTrue(view.rows.allSatisfy { $0.label == nil && $0.kind == "shop" && !$0.local })
        XCTAssertNil(view.noSellerText)
    }
}
