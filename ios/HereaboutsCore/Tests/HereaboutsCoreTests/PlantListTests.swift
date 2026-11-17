// Checks the plant list against the website's: every case in apps/shared/bundle/expected/list.json gives the same
// plants in the same order, and the same "Also matching" lists. Each case sets the page's state, filters PLANTS
// with passes, sorts with sortPlants, and asks hiddenNameMatches only when the search box is not empty.
import Foundation
import XCTest
@testable import HereaboutsCore

final class PlantListTests: XCTestCase {
    private func loadData() throws -> AppData {
        try AppData.load(from: Expected.bundleDir.appendingPathComponent("data"))
    }

    private func strings(_ x: Any?) -> [String] {
        (x as? [Any] ?? []).compactMap { $0 as? String }
    }

    private func location(_ x: Any?) -> Location? {
        guard let o = x as? [String: Any] else { return nil }
        return Location(countyFips: o["countyFips"] as? String ?? "", countyName: o["countyName"] as? String ?? "",
                        stateFips: o["stateFips"] as? String ?? "", stateName: o["stateName"] as? String ?? "",
                        stateUsps: o["stateUsps"] as? String ?? "", ecoregion: o["ecoregion"] as? String ?? "",
                        lat: o["lat"] as? Double, lon: o["lon"] as? Double)
    }

    /// The page's state for one case.
    private func state(_ c: [String: Any]) throws -> FilterState {
        let s = try XCTUnwrap(c["state"] as? [String: Any], "case has no state")
        let sortText = s["sort"] as? String ?? ""
        let sort = try XCTUnwrap(SortKey(rawValue: sortText), "unknown sort \(sortText)")
        let buyTexts = strings(s["buy"])
        let buy = buyTexts.compactMap { BuyMode(rawValue: $0) }
        XCTAssertEqual(buy.count, buyTexts.count, "unknown buy mode in \(buyTexts)")
        let months = (s["bloomMonth"] as? [Any] ?? []).compactMap { ($0 as? Double).map { Int($0) } }
        let maxHeight = try XCTUnwrap(s["maxHeight"] as? Double, "no maxHeight")
        let search = try XCTUnwrap(s["search"] as? String, "no search")
        return FilterState(
            sun: Set(strings(s["sun"])), moisture: Set(strings(s["moisture"])), category: Set(strings(s["category"])),
            wildlife: Set(strings(s["wildlife"])), zones: Set(strings(s["zones"])), bloomMonth: Set(months),
            bloomColor: Set(strings(s["bloomColor"])), maxHeight: maxHeight,
            buy: Set(buy), search: search, sort: sort,
            location: location(c["location"]))
    }

    /// The list as the page's render() builds it: ids in order, and the two "Also matching" lists.
    private func render(_ st: FilterState, _ data: AppData) -> (ids: [String], county: [String], filters: [String]) {
        let shown = PlantList.sorted(data.plants.filter { PlantList.passes($0, st, data: data) }, st, data: data)
        if st.search.isEmpty { return (ids: shown.map { $0.id }, county: [], filters: []) }
        let hidden = PlantList.hidden(shown, st, data: data)
        return (ids: shown.map { $0.id }, county: hidden.county.map { $0.id }, filters: hidden.filters.map { $0.id })
    }

    /// Where two id lists first differ, for a readable failure.
    private func difference(_ what: String, _ got: [String], _ want: [String]) -> String? {
        if got == want { return nil }
        let i = zip(got, want).prefix { $0.0 == $0.1 }.count
        let g = i < got.count ? got[i] : "(end)"
        let w = i < want.count ? want[i] : "(end)"
        return "\(what) differ at \(i) of \(want.count): expected \(w), got \(g) (\(got.count) plants)"
    }

    private func describe(_ st: FilterState) -> String {
        var parts = ["sort \(st.sort.rawValue)", "county \(st.location?.countyFips ?? "none")"]
        if !st.search.isEmpty { parts.append("search \"\(st.search)\"") }
        if !st.sun.isEmpty { parts.append("sun \(st.sun.sorted())") }
        if !st.moisture.isEmpty { parts.append("moisture \(st.moisture.sorted())") }
        if !st.category.isEmpty { parts.append("category \(st.category.sorted())") }
        if !st.wildlife.isEmpty { parts.append("wildlife \(st.wildlife.sorted())") }
        if !st.zones.isEmpty { parts.append("zones \(st.zones.sorted())") }
        if !st.bloomMonth.isEmpty { parts.append("bloomMonth \(st.bloomMonth.sorted())") }
        if !st.bloomColor.isEmpty { parts.append("bloomColor \(st.bloomColor.sorted())") }
        if st.maxHeight != 100 { parts.append("maxHeight \(st.maxHeight)") }
        if !st.buy.isEmpty { parts.append("buy \(st.buy.map { $0.rawValue }.sorted())") }
        return parts.joined(separator: ", ")
    }

    func testEveryListCase() throws {
        let data = try loadData()
        let cases = Expected.cases("list.json")
        XCTAssertGreaterThan(cases.count, 100)
        var bad: [String] = []
        for (n, c) in cases.enumerated() {
            let st = try state(c)
            let got = render(st, data)
            let also = c["also"] as? [String: Any] ?? [:]
            let found = [
                difference("ids", got.ids, strings(c["ids"])),
                difference("also.county", got.county, strings(also["county"])),
                difference("also.filters", got.filters, strings(also["filters"])),
            ].compactMap { $0 }
            if !found.isEmpty { bad.append("case \(n) (\(describe(st))): " + found.joined(separator: "; ")) }
        }
        if !bad.isEmpty {
            XCTFail("\(bad.count) of \(cases.count) list cases differ; first:\n" + bad.prefix(5).joined(separator: "\n"))
        }
    }

    func testSortIsStable() throws {
        let data = try loadData()
        let byId = Dictionary(data.plants.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        // "Height (low to high)" in Jefferson County, Kentucky, with no other choices.
        let found = Expected.cases("list.json").first { x in
            let s = x["state"] as? [String: Any] ?? [:]
            let loc = x["location"] as? [String: Any]
            let setsEmpty = ["sun", "moisture", "category", "wildlife", "zones", "bloomMonth", "bloomColor", "buy"]
                .allSatisfy { (s[$0] as? [Any])?.isEmpty ?? false }
            let sort: String? = s["sort"] as? String
            let search: String? = s["search"] as? String
            let maxHeight: Double? = s["maxHeight"] as? Double
            let county: String? = loc?["countyFips"] as? String
            if sort != "height-asc" || search != "" || maxHeight != 100 { return false }
            return setsEmpty && county == "21111"
        }
        let c = try XCTUnwrap(found, "list.json has no plain height-asc case for county 21111")
        let want = strings(c["ids"])
        let heights = want.compactMap { byId[$0]?.height.last }
        XCTAssertEqual(heights.count, want.count)
        let ties = zip(heights, heights.dropFirst()).filter { $0.0 == $0.1 }.count
        XCTAssertGreaterThan(ties, 0, "the case needs plants of equal height to test stability")
        let st0 = try state(c)
        let got = render(st0, data).ids
        XCTAssertNil(difference("ids", got, want))

        // The same sort on the plants in reverse: plants of equal height now come out in reverse data order.
        let reversed = Array(data.plants.reversed())
        let st = FilterState(sort: .heightAsc)
        let sorted = PlantList.sorted(reversed, st, data: data)
        let position = Dictionary(reversed.enumerated().map { ($0.element.id, $0.offset) }, uniquingKeysWith: { a, _ in a })
        for (a, b) in zip(sorted, sorted.dropFirst()) where a.height.last == b.height.last {
            XCTAssertLessThan(position[a.id] ?? 0, position[b.id] ?? 0, "\(a.id) and \(b.id) are the same height and keep their order")
        }
        XCTAssertEqual(sorted.count, reversed.count)
    }

    func testHeightSortsPutPlantsWithoutTwoHeightsLast() throws {
        // 40 plants: p05, p13, p22 and p31 have no heights, p09 and p27 have only one. The rest have two, with many
        // ties. The website's order for a plant without two heights is not defined; this rule is the apps' own, and
        // the Android app's PlantListTest checks the same 40 plants and the same two orders.
        let items = (0..<40).map { i -> String in
            let height: String
            if [5, 13, 22, 31].contains(i) {
                height = "[]"
            } else if [9, 27].contains(i) {
                height = "[4]"
            } else {
                height = "[1,\((i * 7) % 11 + 1)]"
            }
            return "{\"id\":\"\(String(format: "p%02d", i))\",\"common\":\"P\",\"sci\":\"P p\",\"category\":\"tree\",\"height\":\(height)}"
        }
        let plants = try JSONDecoder().decode([Plant].self, from: Data("[\(items.joined(separator: ","))]".utf8))
        XCTAssertEqual(plants.count, 40)
        let asc = "p00 p11 p33 p08 p19 p30 p16 p38 p02 p24 p35 p10 p21 p32 p07 p18 p29 p04 p15 p26 p37 p01 p12 p23 "
            + "p34 p20 p06 p17 p28 p39 p03 p14 p25 p36 p05 p09 p13 p22 p27 p31"
        let desc = "p03 p14 p25 p36 p06 p17 p28 p39 p20 p01 p12 p23 p34 p04 p15 p26 p37 p07 p18 p29 p10 p21 p32 p02 "
            + "p24 p35 p16 p38 p08 p19 p30 p00 p11 p33 p05 p09 p13 p22 p27 p31"
        let data = try loadData()
        XCTAssertEqual(PlantList.sorted(plants, FilterState(sort: .heightAsc), data: data).map(\.id),
                       asc.split(separator: " ").map(String.init))
        XCTAssertEqual(PlantList.sorted(plants, FilterState(sort: .heightDesc), data: data).map(\.id),
                       desc.split(separator: " ").map(String.init))
    }

    func testSortMenuIsTheWebsites() throws {
        // list.json's sortMenu is index.html's sort menu, read when the bundle was built: the same sorts, in the same
        // order, with the same labels.
        let menu = try XCTUnwrap(Expected.field("list.json", "sortMenu") as? [Any], "list.json has no sortMenu")
        let options = menu.compactMap { $0 as? [String: Any] }
        XCTAssertEqual(options.count, menu.count)
        XCTAssertEqual(SortKey.allCases.map { $0.rawValue }, options.map { $0["value"] as? String ?? "" })
        XCTAssertEqual(SortKey.allCases.map { $0.label }, options.map { $0["label"] as? String ?? "" })

        // The list cases run the sorts in the menu's order.
        var seen: [String] = []
        for c in Expected.cases("list.json") {
            if let sort = (c["state"] as? [String: Any])?["sort"] as? String, !seen.contains(sort) { seen.append(sort) }
        }
        XCTAssertEqual(SortKey.allCases.map { $0.rawValue }, seen)
    }

    func testTypeCounts() throws {
        let data = try loadData()
        let counts = PlantList.typeCounts(data.plants)
        XCTAssertEqual(counts.map { $0.category }, PlantList.categoryOrder.filter { c in data.plants.contains { $0.category == c } })
        XCTAssertEqual(counts.map { $0.count }.reduce(0, +), data.plants.count)
        XCTAssertTrue(counts.allSatisfy { $0.count > 0 })
        let ferns = PlantList.typeCounts(data.plants.filter { $0.category == "fern" })
        XCTAssertEqual(ferns.map { $0.category }, ["fern"])
    }

    func testNormalizeName() {
        XCTAssertEqual(PlantList.normalizeName("  Culver\u{2019}s  Root "), "culvers root")
        XCTAssertEqual(PlantList.normalizeName("Jack\u{2013}in\u{2013}the\u{2013}Pulpit"), "jack in the pulpit")
        XCTAssertEqual(PlantList.normalizeName("\u{C9}chinacea"), "echinacea")
        XCTAssertEqual(PlantList.normalizeName("black-eyed `susan`"), "black eyed susan")
        XCTAssertEqual(PlantList.normalizeName("   "), "")
    }
}
