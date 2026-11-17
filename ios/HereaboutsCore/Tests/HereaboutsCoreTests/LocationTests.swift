// Checks LocationLookup against expected/location.json: every case there is the website's own answer
// (location.js run by scripts/app/expected-answers.mjs), and the Swift code must give exactly the same one.
import Foundation
import XCTest
@testable import HereaboutsCore

final class LocationTests: XCTestCase {
    private func loadTables() throws -> Tables {
        try Tables.load(from: Expected.bundleDir.appendingPathComponent("tables"))
    }

    // MARK: - turning answers into JSON shaped like the website's

    private func json(_ loc: Location) -> JSON {
        .object([
            "countyFips": .string(loc.countyFips), "countyName": .string(loc.countyName), "stateFips": .string(loc.stateFips),
            "stateName": .string(loc.stateName), "stateUsps": .string(loc.stateUsps), "ecoregion": .string(loc.ecoregion),
            "lat": loc.lat.map(JSON.number) ?? JSON.null, "lon": loc.lon.map(JSON.number) ?? JSON.null,
        ])
    }

    private func json(_ kind: InputKind) -> JSON {
        switch kind {
        case .empty: return .object(["kind": .string("empty")])
        case .zip(let zip): return .object(["kind": .string("zip"), "zip": .string(zip)])
        case .address(let text): return .object(["kind": .string("address"), "text": .string(text)])
        case .state(let code, let name): return .object(["kind": .string("state"), "code": .string(code), "name": .string(name)])
        case .place(let text): return .object(["kind": .string("place"), "text": .string(text)])
        }
    }

    /// A ZIP or coordinates answer: the Location, `{ error }`, or null when there is no ZIP to use.
    private func json(_ result: LocationResult) -> JSON {
        switch result {
        case .found(let loc): return json(loc)
        case .notFound: return .null
        case .outsideUS48: return .object(["error": .string(result.error ?? "")])
        }
    }

    private func json(_ result: PlaceResult) -> JSON {
        switch result {
        case .found(let loc, _): return json(loc)
        case .ambiguous(let states): return .object(["error": .string(result.error ?? ""), "states": .array(states.map { .string($0) })])
        case .outsideUS48(_), .notFound: return .object(["error": .string(result.error ?? "")])
        }
    }

    private func location(_ value: Any?) -> Location? {
        guard let d = value as? [String: Any] else { return nil }
        func s(_ k: String) -> String { d[k] as? String ?? "" }
        func n(_ k: String) -> Double? { d[k] as? Double }
        return Location(countyFips: s("countyFips"), countyName: s("countyName"), stateFips: s("stateFips"), stateName: s("stateName"),
                        stateUsps: s("stateUsps"), ecoregion: s("ecoregion"), lat: n("lat"), lon: n("lon"))
    }

    // MARK: - the expected answers

    func testEveryExpectedCase() throws {
        let tables = try loadTables()
        let cases = Expected.cases("location.json")
        var counts: [String: Int] = [:]
        var failed = 0
        for (index, c) in cases.enumerated() {
            let kind = c["kind"] as? String ?? "(none)"
            counts[kind, default: 0] += 1
            let text = c["text"] as? String ?? ""
            let actual: JSON
            switch kind {
            case "classify":
                actual = json(LocationLookup.classify(text))
            case "zip":
                // An unknown ZIP is .notFound, which json(_:) writes as null, the website's answer.
                actual = json(LocationLookup.zip(c["zip"] as? String ?? "", tables: tables))
            case "place":
                actual = json(LocationLookup.place(text, tables: tables))
            case "placeKind":
                actual = LocationLookup.place(text, tables: tables).kind.map { JSON.string($0.rawValue) } ?? JSON.null
            case "coords":
                actual = json(LocationLookup.nearest(lat: c["lat"] as? Double ?? .nan, lon: c["lon"] as? Double ?? .nan, tables: tables))
            case "message":
                if let code = c["code"] as? String {
                    actual = .string(LocationLookup.stateMessage(code))
                } else {
                    actual = .string(LocationLookup.ambiguousMessage(text, states: (c["states"] as? [Any])?.compactMap { $0 as? String } ?? []))
                }
            case "header":
                actual = .string(LocationLookup.headerText(location(c["location"])))
            case "coverage":
                let served = c["served"] as? Bool
                XCTAssertNotNil(served, "case \(index): served should be true or false")
                actual = .string(LocationLookup.coverageText(Int(c["n"] as? Double ?? -1), served: served ?? false))
            default:
                XCTFail("case \(index): unknown kind \(kind)")
                failed += 1
                continue
            }
            let expected = JSON(any: c["result"])
            if actual != expected {
                failed += 1
                let input = JSON(any: c.filter { $0.key != "result" && $0.key != "kind" })
                XCTFail("location.json case \(index) (\(kind)) \(input): expected \(expected), got \(actual)")
            }
        }
        // No quiet pass: every kind the file is made of must be there.
        for kind in ["classify", "zip", "place", "placeKind", "coords", "message", "header", "coverage"] {
            XCTAssertGreaterThan(counts[kind] ?? 0, 0, "location.json has no \(kind) cases")
        }
        print("location.json: \(cases.count) cases (\(counts.sorted { $0.key < $1.key }.map { "\($0.key) \($0.value)" }.joined(separator: ", "))), \(failed) failed")
    }

    // MARK: - parts the expected file does not cover

    func testRoundPosition() {
        // Math.round(x * 100) / 100: halves go up (toward +infinity), so -0.125 gives -0.12, not -0.13.
        XCTAssertEqual(LocationLookup.roundPosition(38.84629), 38.85)
        XCTAssertEqual(LocationLookup.roundPosition(-77.30449), -77.3)
        XCTAssertEqual(LocationLookup.roundPosition(-0.125), -0.12)
        XCTAssertEqual(LocationLookup.roundPosition(0.125), 0.13)
        XCTAssertEqual(LocationLookup.roundPosition(1.005), 1)
        XCTAssertEqual(LocationLookup.roundPosition(-85.755), -85.75)
    }

    func testNativeHere() throws {
        let louisville = Location(countyFips: "21111", countyName: "Jefferson County", stateFips: "21", stateName: "Kentucky",
                                  stateUsps: "KY", ecoregion: "71", lat: 38.25, lon: -85.75)
        let geo = ["p": PlantGeo(eco: ["71"], intro: ["18"])]
        XCTAssertTrue(LocationLookup.nativeHere("p", louisville, geo: geo))
        XCTAssertFalse(LocationLookup.nativeHere("no-such-plant", louisville, geo: geo))
        let coast = Location(countyFips: "41007", countyName: "Clatsop County", stateFips: "41", stateName: "Oregon",
                             stateUsps: "OR", ecoregion: "1", lat: nil, lon: nil)
        XCTAssertFalse(LocationLookup.nativeHere("p", coast, geo: geo))
        // With the real data: every plant is native in its own ecoregions, unless introduced in that state.
        let data = try AppData.load(from: Expected.bundleDir.appendingPathComponent("data"))
        for (id, g) in data.geo {
            for eco in g.eco {
                let here = Location(countyFips: "21111", countyName: "", stateFips: "21", stateName: "", stateUsps: "", ecoregion: eco,
                                    lat: nil, lon: nil)
                XCTAssertEqual(LocationLookup.nativeHere(id, here, geo: data.geo), !g.intro.contains("21"), id)
            }
        }
        // Native to the ecoregion but flagged as introduced in the state.
        XCTAssertFalse(LocationLookup.nativeHere("p", louisville, geo: ["p": PlantGeo(eco: ["71"], intro: ["21"])]))
    }

    func testErrorWordsAreTheWebsites() {
        XCTAssertEqual(PlaceResult.ambiguous(states: ["MO"]).error, "ambiguous")
        XCTAssertEqual(PlaceResult.outsideUS48(kind: nil).error, "outside_us48")
        XCTAssertEqual(PlaceResult.notFound.error, "not_found")
        XCTAssertEqual(LocationResult.outsideUS48.error, "outside_us48")
        XCTAssertEqual(LocationResult.notFound.error, "not_found")
        XCTAssertEqual(PlaceKind.city.rawValue, "city")
        XCTAssertEqual(PlaceKind.county.rawValue, "county")
    }

    func testPlaceNamesInOneForm() {
        XCTAssertEqual(LocationLookup.normalizePlaceName("  Saint  Louis ,  MO, "), "st louis, mo")
        XCTAssertEqual(LocationLookup.normalizePlaceName("Coeur d’Alene"), "coeur dalene")
        XCTAssertEqual(LocationLookup.normalizePlaceName("Cañon City"), "canon city")
        XCTAssertEqual(LocationLookup.normalizePlaceName("Winston-Salem/Fort Worth"), "winston salem ft worth")
        XCTAssertEqual(LocationLookup.normalizePlaceName("Mountain View"), "mountain view")
        XCTAssertEqual(LocationLookup.displayName("  winston-salem   nc "), "Winston-Salem Nc")
        XCTAssertEqual(LocationLookup.displayName("o'fallon"), "O'fallon")
    }
}
