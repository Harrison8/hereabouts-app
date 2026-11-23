// Checks Labels against expected/labels.json: for every plant, the words the website's index.html writes on its card
// and page. Then a few made-up values that the data does not have, with the website's answers worked out by running
// its functions in Node.
import Foundation
import XCTest
@testable import HereaboutsCore

final class LabelsTests: XCTestCase {
    private func loadData() throws -> AppData {
        try AppData.load(from: Expected.bundleDir.appendingPathComponent("data"))
    }

    /// A plant read from JSON text with the given fields added to a plain one.
    private func plant(_ fields: String) throws -> Plant {
        let text = #"{"id":"x","common":"Ex","sci":"Ex ample","category":"perennial""# + (fields.isEmpty ? "" : "," + fields) + "}"
        return try JSONDecoder().decode(Plant.self, from: Data(text.utf8))
    }

    private func rows(_ list: [[String]], mono: Bool) -> JSON {
        .array(list.map { row -> JSON in
            var a = row.map { JSON.string($0) }
            if mono, let label = row.first, Labels.isMono(label) { a.append(.bool(true)) }
            return .array(a)
        })
    }

    private func answers(_ p: Plant) -> [String: JSON] {
        let facts = Labels.growingFacts(p)
        var out: [String: JSON] = [:]
        out["bloomRange"] = Labels.bloomRange(p.bloomMonths).map { JSON.string($0) } ?? JSON.null
        out["height"] = JSON.string(Labels.ftRange(p.height))
        out["spread"] = JSON.string(Labels.ftRange(p.spread))
        out["sun"] = JSON.string(Labels.sun(p.sun))
        out["moisture"] = JSON.string(Labels.moisture(p.moisture))
        out["growth"] = Labels.growth(p.maturity?.years).map { JSON.string($0) } ?? JSON.null
        out["category"] = JSON.string(Labels.category(p.category))
        var seasons: [JSON] = []
        for r in Labels.seasonRows(p) {
            let runs: [JSON] = r.runs.map { run -> JSON in JSON.array(run.map { JSON.number(Double($0)) }) }
            seasons.append(JSON.object(["label": JSON.string(r.label), "state": JSON.string(r.state), "runs": JSON.array(runs)]))
        }
        out["seasonRows"] = JSON.array(seasons)
        out["growingFacts"] = JSON.object(["quick": rows(facts.quick, mono: true), "more": rows(facts.more, mono: false),
                                           "flowers": rows(facts.flowers, mono: false)])
        if let m = Labels.mainPhoto(p) {
            out["mainPhoto"] = JSON.object(["phase": JSON.string(m.season), "url": JSON.string(m.photo.url),
                                            "alt": JSON.string(m.photo.alt), "credit": JSON.string(m.photo.credit)])
        } else {
            out["mainPhoto"] = JSON.null
        }
        out["plantTitle"] = JSON.string(Labels.plantTitle(p))
        return out
    }

    func testEveryExpectedCase() throws {
        let data = try loadData()
        let byId = Dictionary(data.plants.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
        let cases = Expected.cases("labels.json")
        XCTAssertEqual(cases.count, data.plants.count, "labels.json should have one case per plant")
        let fields: Set<String> = ["plant", "bloomRange", "height", "spread", "sun", "moisture", "growth", "category",
                                   "seasonRows", "growingFacts", "mainPhoto", "plantTitle"]
        var bad: [String] = []
        for c in cases {
            let id = c["plant"] as? String ?? ""
            if Set(c.keys) != fields { bad.append("\(id): fields \(c.keys.sorted())"); continue }
            guard let p = byId[id] else { bad.append("\(id): no such plant"); continue }
            let mine = answers(p)
            for key in fields.subtracting(["plant"]).sorted() {
                let want = JSON(any: c[key])
                let got = mine[key] ?? .null
                if got != want { bad.append("\(id) \(key): got \(got), want \(want)") }
            }
        }
        XCTAssertTrue(bad.isEmpty, "\(bad.count) mismatches in labels.json; first: \(bad.prefix(5).joined(separator: " | "))")
        print("labels.json: \(cases.count) cases checked")
    }

    func testPhotoOrderMatchesWebsite() {
        let want = (Expected.field("labels.json", "photoOrder") as? [Any] ?? []).compactMap { $0 as? String }
        XCTAssertEqual(Labels.photoOrder, want)
    }

    func testPhotosFollowPhotoOrderAndStartWithMainPhoto() throws {
        let data = try loadData()
        var checked = 0
        for p in data.plants {
            let list = Labels.photos(p)
            let rank = list.map { Labels.photoOrder.firstIndex(of: $0.season) ?? -1 }
            XCTAssertEqual(rank, rank.sorted(), "\(p.id): photos out of order")
            XCTAssertEqual(list.count, p.phasePhotos.keys.filter { Labels.photoOrder.contains($0) }.count, p.id)
            XCTAssertEqual(list.first?.season, Labels.mainPhoto(p)?.season, p.id)
            XCTAssertEqual(list.first?.photo, Labels.mainPhoto(p)?.photo, p.id)
            checked += 1
        }
        XCTAssertGreaterThan(checked, 0)
        let made = try plant(#""phasePhotos":{"dormant":{"url":"d","alt":"","credit":""},"green":{"url":"g","alt":"","credit":""},"odd":{"url":"o","alt":"","credit":""}}"#)
        XCTAssertEqual(Labels.photos(made).map(\.season), ["green", "dormant"])
        XCTAssertEqual(Labels.mainPhoto(made)?.photo.url, "g")
        XCTAssertNil(Labels.mainPhoto(try plant("")))
    }

    // MARK: - values the data does not have (answers from the website's functions in Node)

    func testGrowthShorthand() {
        let cases: [(String, String)] = [
            ("2-3", "2–3 years to full size"), (" 2 – 3 ", "2–3 years to full size"), ("02-10", "02–10 years to full size"),
            ("~2 yrs; 1-2 ft/yr", "About 2 years; 1–2 ft a year"), ("~ 3 yr", "About 3 year"), ("6 in. tall", "6 in tall"),
            ("6 in.", "6 in"), ("6 in.;", "6 in;"), ("in.x", "In.x"), ("twin. 1", "Twin. 1"), ("1-2-3", "1–2-3"),
            ("pyrs and yrs2 yrs", "Pyrs and yrs2 years"), ("shrub-like 2-3", "Shrub-like 2–3"), ("ft/yrs", "Ft/years"),
            ("é 1-2 yr", "É 1–2 year"), ("2-3 years to fill in.", "2–3 years to fill in"),
        ]
        for (input, want) in cases { XCTAssertEqual(Labels.growth(input), want, input) }
        XCTAssertNil(Labels.growth(nil))
    }

    func testFtRangeRoundsHalvesUpLikeToFixed() {
        XCTAssertEqual(Labels.ftRange([0.25, 0.75]), "0.3–0.8 ft")
        XCTAssertEqual(Labels.ftRange([1.25, 2]), "1.3–2 ft")
        XCTAssertEqual(Labels.ftRange([0.35, 1]), "0.3–1 ft")
        XCTAssertEqual(Labels.ftRange([3, 3]), "3 ft")
        XCTAssertEqual(Labels.ftRange([0.5, 0.5]), "0.5 ft")
        XCTAssertEqual(Labels.ftRange([1, 1.5]), "1–1.5 ft")
        XCTAssertEqual(Labels.ftRange([0.05, 2.45]), "0.1–2.5 ft")
        XCTAssertEqual(Labels.ftRange([1]), "")
        XCTAssertEqual(JSText.toFixed(-0.04, 1), "-0.0")
        XCTAssertEqual(JSText.toFixed(2, 2), "2.00")
    }

    func testBloomRangeSunAndMoisture() {
        XCTAssertEqual(Labels.bloomRange([5, 5, 6]), "May, May–Jun")
        XCTAssertEqual(Labels.bloomRange([12, 1]), "Jan, Dec")
        XCTAssertEqual(Labels.bloomRange([3, 4, 5, 7, 9, 10]), "Mar–May, Jul, Sep–Oct")
        XCTAssertNil(Labels.bloomRange([]))
        // Months outside 1-12, as the website writes them.
        XCTAssertEqual(Labels.bloomRange([13]), "")
        XCTAssertEqual(Labels.bloomRange([0, 5]), ", May")
        XCTAssertEqual(Labels.bloomRange([13, 14]), "undefined–undefined")
        XCTAssertEqual(Labels.sun(["shade", "full"]), "Full sun or full shade")
        XCTAssertEqual(Labels.sun(["part"]), "Part shade")
        XCTAssertEqual(Labels.sun(["full", "part", "shade"]), "Full sun to full shade")
        XCTAssertEqual(Labels.sun(["x"]), "")
        XCTAssertEqual(Labels.moisture(["wet", "dry"]), "Dry or wet")
        XCTAssertEqual(Labels.moisture(["medium", "wet"]), "Medium to wet")
        XCTAssertEqual(Labels.category("grass"), "Grass / Sedge")
        XCTAssertEqual(Labels.category("cactus"), "")
    }

    func testMonoRowsAreHeightAndWidth() {
        XCTAssertTrue(Labels.isMono("Height"))
        XCTAssertTrue(Labels.isMono("Width"))
        XCTAssertFalse(Labels.isMono("Sun"))
        XCTAssertFalse(Labels.isMono("Soil"))
    }

    func testCreditTextKeepsTheLicenceNameTogether() {
        let nb = "\u{00A0}"
        let hy = "\u{2011}"
        XCTAssertEqual(Labels.creditText("Jane Doe, CC BY-SA 4.0"), "Jane Doe, CC\(nb)BY\(hy)SA\(nb)4.0")
        XCTAssertEqual(Labels.creditText("A, B, Public domain"), "A, B, Public\(nb)domain")
        XCTAssertEqual(Labels.creditText("No licence here"), "No licence here")
        XCTAssertEqual(Labels.creditText("Name, Some Place"), "Name, Some Place")
        XCTAssertEqual(Labels.creditText(""), "")
        // A source in brackets stays breakable: only the licence name is held.
        XCTAssertEqual(Labels.creditText("NPS, Public Domain (NPS)"), "NPS, Public\(nb)Domain (NPS)")
        XCTAssertEqual(Labels.creditText("Jane Doe, CC BY (iNaturalist)"), "Jane Doe, CC\(nb)BY (iNaturalist)")
    }
}
