// Checks that the bundle's data/ and tables/ folders decode into the Swift types, with nothing lost.
// Every expected value is read from the bundle's own JSON, never written here, so routine edits to prices,
// shipping areas or the tables do not break these tests; only a change in the files' shape does.
import Foundation
import XCTest
@testable import HereaboutsCore

// MARK: - reading expected values out of the raw JSON

private func str(_ j: JSON?) -> String? {
    if case .string(let s)? = j { return s }
    return nil
}

private func bool(_ j: JSON?) -> Bool? {
    if case .bool(let b)? = j { return b }
    return nil
}

private func num(_ j: JSON?) -> Double? {
    if case .number(let n)? = j { return n }
    return nil
}

private func strings(_ j: JSON?) -> [String]? {
    guard case .array(let a)? = j else { return nil }
    return a.compactMap { str($0) }
}

private func numbers(_ j: JSON?) -> [Double]? {
    guard case .array(let a)? = j else { return nil }
    return a.compactMap { num($0) }
}

private func object(_ j: JSON?) -> [String: JSON]? {
    if case .object(let o)? = j { return o }
    return nil
}

/// The website's reading of a shipping area: "contiguous-48", a list of states, { allExcept: [...] }, and any
/// other value as shipping nowhere. A missing or null field is nil.
private func shipsTo(_ j: JSON?) -> ShipsTo? {
    switch j {
    case nil, .null?: return nil
    case .string(let s)?: return s == "contiguous-48" ? .contiguous48 : .states([])
    case .array?: return .states(strings(j) ?? [])
    case .object(let o)?: return strings(o["allExcept"]).map { ShipsTo.allExcept($0) } ?? .states([])
    default: return .states([])
    }
}

/// Collects mismatches and reports the count plus the first few, so a broken table gives one readable failure.
private struct Mismatches {
    var lines: [String] = []
    mutating func check<T: Equatable>(_ actual: T, _ expected: T, _ what: @autoclosure () -> String) {
        if actual != expected { lines.append("\(what()): expected \(expected), got \(actual)") }
    }
    func report(_ name: String, file: StaticString = #filePath, line: UInt = #line) {
        if !lines.isEmpty {
            XCTFail("\(name): \(lines.count) mismatches, first: \(lines.prefix(5).joined(separator: "; "))", file: file, line: line)
        }
    }
}

final class DataTests: XCTestCase {
    private var dataDir: URL { Expected.bundleDir.appendingPathComponent("data") }

    func testVendorsIsAnObject() throws {
        // vendors.json is an object keyed by seller name, and every seller decodes with each field the website reads.
        guard case .object(let raw) = try Expected.bundleJSON("data/vendors.json") else {
            return XCTFail("vendors.json is not an object")
        }
        let data = try AppData.load(from: dataDir)
        XCTAssertGreaterThan(raw.count, 0)
        XCTAssertEqual(Set(data.vendors.keys), Set(raw.keys))
        var m = Mismatches()
        for (key, json) in raw {
            guard let o = object(json), let v = data.vendors[key] else { continue }
            m.check(v.name, key, "\(key) name is the key")
            let expected = Vendor(name: str(o["name"]) ?? "", short: str(o["short"]), location: str(o["location"]), coords: numbers(o["coords"]),
                                  storefront: bool(o["storefront"]), affiliate: bool(o["affiliate"]), shipsTo: shipsTo(o["shipsTo"]),
                                  shipsExcept: strings(o["shipsExcept"]))
            m.check(v, expected, key)
        }
        m.report("vendors.json")
    }

    func testShipsToShapes() throws {
        // Each shape of shipping area, written out here so the test does not depend on which sellers use which.
        func decode(_ text: String) throws -> ShipsTo? {
            let json = #"{"name":"S","shipsTo":"# + text + "}"
            return try JSONDecoder().decode(Vendor.self, from: Data(json.utf8)).shipsTo
        }
        XCTAssertEqual(try decode(#""contiguous-48""#), .contiguous48)
        XCTAssertEqual(try decode(#"["KY","IN"]"#), .states(["KY", "IN"]))
        XCTAssertEqual(try decode("[]"), .states([]))
        XCTAssertEqual(try decode(#"{"allExcept":["CA"]}"#), .allExcept(["CA"]))
        XCTAssertEqual(try decode(#""somewhere""#), .states([]))
        XCTAssertEqual(try decode("12"), .states([]))
        XCTAssertNil(try decode("null"))
    }

    func testEveryPlantDecodes() throws {
        guard case .array(let raw) = try Expected.bundleJSON("data/plants.json") else {
            return XCTFail("plants.json is not a list")
        }
        let data = try AppData.load(from: dataDir)
        XCTAssertGreaterThan(raw.count, 0)
        XCTAssertEqual(data.plants.count, raw.count)
        XCTAssertEqual(Set(data.plants.map(\.id)).count, data.plants.count, "plant ids repeat")
        var m = Mismatches()
        for (plant, json) in zip(data.plants, raw) {
            guard let o = object(json) else {
                m.lines.append("a plant is not an object")
                continue
            }
            let id = plant.id
            m.check(id, str(o["id"]) ?? "", "id")
            m.check(plant.common, str(o["common"]) ?? "", "\(id) common")
            m.check(plant.sci, str(o["sci"]) ?? "", "\(id) sci")
            m.check(plant.category, str(o["category"]) ?? "", "\(id) category")
            m.check(plant.usda, str(o["usda"]), "\(id) usda")
            m.check(plant.height, numbers(o["height"]) ?? [], "\(id) height")
            m.check(plant.spread, numbers(o["spread"]) ?? [], "\(id) spread")
            m.check(plant.sun, strings(o["sun"]) ?? [], "\(id) sun")
            m.check(plant.moisture, strings(o["moisture"]) ?? [], "\(id) moisture")
            m.check(plant.bloomColor, strings(o["bloomColor"]) ?? [], "\(id) bloomColor")
            m.check(plant.wildlife, strings(o["wildlife"]) ?? [], "\(id) wildlife")
            m.check(plant.zones, strings(o["zones"]) ?? [], "\(id) zones")
            m.check(plant.months, strings(o["months"]) ?? [], "\(id) months")
            m.check(plant.bloomMonths, (numbers(o["bloomMonths"]) ?? []).map { Int($0) }, "\(id) bloomMonths")
            m.check(plant.fallColor, str(o["fallColor"]), "\(id) fallColor")
            m.check(plant.evergreen, bool(o["evergreen"]), "\(id) evergreen")
            m.check(plant.notes, str(o["notes"]), "\(id) notes")
            // Plants with photos keep every phase; plants without get an empty list.
            var photos: [String: Photo] = [:]
            for (phase, p) in object(o["phasePhotos"]) ?? [:] {
                let po = object(p) ?? [:]
                photos[phase] = Photo(url: str(po["url"]) ?? "", alt: str(po["alt"]) ?? "", credit: str(po["credit"]) ?? "")
            }
            m.check(plant.phasePhotos, photos, "\(id) phasePhotos")
            let maturity = object(o["maturity"]).map { Maturity(years: str($0["years"]), notes: str($0["notes"])) }
            m.check(plant.maturity, maturity, "\(id) maturity")
            var vendors: [VendorEntry] = []
            if case .array(let list)? = o["vendors"] {
                for e in list {
                    let eo = object(e) ?? [:]
                    vendors.append(VendorEntry(name: str(eo["name"]) ?? "", url: str(eo["url"]), price: str(eo["price"]),
                                               size: str(eo["size"]), stock: str(eo["stock"]), shipsTo: strings(eo["shipsTo"])))
                }
            }
            m.check(plant.vendors, vendors, "\(id) vendors")
        }
        m.report("plants.json")
        XCTAssertGreaterThan(data.plants.filter { !$0.phasePhotos.isEmpty }.count, 0, "no plant has photos")
        XCTAssertGreaterThan(data.plants.filter { !$0.vendors.isEmpty }.count, 0, "no plant has sellers")
    }

    func testRegionsAndVersion() throws {
        let data = try AppData.load(from: dataDir)
        guard let regions = try object(Expected.bundleJSON("data/plant-regions.json")),
              let version = try object(Expected.bundleJSON("data/version.json")) else {
            return XCTFail("plant-regions.json or version.json is not an object")
        }
        var geo: [String: PlantGeo] = [:]
        for (id, g) in object(regions["plants"]) ?? [:] {
            geo[id] = PlantGeo(eco: strings(object(g)?["eco"]) ?? [], intro: strings(object(g)?["intro"]) ?? [])
        }
        XCTAssertEqual(data.geo, geo)
        XCTAssertEqual(data.geo.count, data.plants.count)
        XCTAssertEqual(data.served, strings(regions["served"]) ?? [])
        XCTAssertFalse(data.served.isEmpty)
        XCTAssertEqual(data.version, DataVersion(schema: Int(num(version["schema"]) ?? -1), dataDate: str(version["dataDate"]) ?? "",
                                                  dataHash: str(version["dataHash"]) ?? ""))
        XCTAssertEqual(data.version.dataHash.count, 64)
    }

    func testUnknownFieldsIgnored() throws {
        let plant = """
        {"id":"x","common":"X","sci":"X x","category":"tree","height":[1,2.5],"spread":[1],"sun":["full"],"moisture":[],
         "bloomColor":[],"wildlife":[],"zones":[],"months":[],"bloomMonths":[],"nativeKY":true,"someNewField":{"a":[1,"b"]},
         "vendors":[{"name":"S","inStock":true,"form":"plant","variantId":"1","shipsTo":["KY"],"another":null}]}
        """
        let p = try JSONDecoder().decode(Plant.self, from: Data(plant.utf8))
        XCTAssertEqual(p.id, "x")
        XCTAssertEqual(p.height, [1, 2.5])
        XCTAssertNil(p.usda)
        XCTAssertNil(p.maturity)
        XCTAssertTrue(p.phasePhotos.isEmpty)
        XCTAssertEqual(p.vendors, [VendorEntry(name: "S", shipsTo: ["KY"])])

        let vendor = """
        {"name":"S","coords":null,"storefront":false,"shipsTo":{"allExcept":["ID"]},"confirmed":true,"carriesCount":3,"kind":"x"}
        """
        let v = try JSONDecoder().decode(Vendor.self, from: Data(vendor.utf8))
        XCTAssertEqual(v.shipsTo, ShipsTo.allExcept(["ID"]))
        XCTAssertNil(v.coords)

        let geo = #"{"eco":["1"],"intro":[],"extra":1}"#
        XCTAssertEqual(try JSONDecoder().decode(PlantGeo.self, from: Data(geo.utf8)), PlantGeo(eco: ["1"], intro: []))
    }

    func testTablesLoad() throws {
        let tables = try Tables.load(from: Expected.bundleDir.appendingPathComponent("tables"))
        func raw(_ name: String) throws -> [String: JSON] {
            guard let o = try object(Expected.bundleJSON("tables/" + name)) else {
                XCTFail("\(name) is not an object")
                return [:]
            }
            return o
        }
        var m = Mismatches()

        // zip-county.json: { zip: [countyFips, lat, lon] }
        let zips = try raw("zip-county.json")
        XCTAssertGreaterThan(zips.count, 0)
        XCTAssertEqual(tables.zipCounty.count, zips.count)
        XCTAssertEqual(tables.sortedZips, zips.keys.sorted())
        for (zip, row) in zips {
            guard case .array(let r) = row, r.count >= 3, let fips = str(r[0]), let lat = num(r[1]), let lon = num(r[2]) else {
                m.lines.append("zip \(zip) row has an unexpected shape")
                continue
            }
            m.check(tables.zipCounty[zip], ZipRow(countyFips: fips, lat: lat, lon: lon), "zip \(zip)")
        }

        // place-county.json: { name: [[stateCode, countyFips, lat, lon, default?], ...] }; a fifth element of 1 is the clear choice.
        let places = try raw("place-county.json")
        XCTAssertGreaterThan(places.count, 0)
        XCTAssertEqual(tables.placeCounty.count, places.count)
        var defaults = 0
        for (name, rows) in places {
            guard case .array(let list) = rows else {
                m.lines.append("place \(name) is not a list")
                continue
            }
            let expected: [PlaceRow] = list.compactMap { row in
                guard case .array(let r) = row, r.count >= 4 else { return nil }
                let isDefault = r.count > 4 && num(r[4]) == 1
                return PlaceRow(state: str(r[0]) ?? "", countyFips: str(r[1]) ?? "", lat: num(r[2]) ?? .nan, lon: num(r[3]) ?? .nan,
                                isDefault: isDefault)
            }
            defaults += expected.filter(\.isDefault).count
            m.check(tables.placeCounty[name] ?? [], expected, "place \(name)")
        }
        XCTAssertGreaterThan(defaults, 0, "no place row is marked as the clear choice")

        // states.json: { stateFips: [name, usps] }
        let states = try raw("states.json")
        XCTAssertEqual(tables.states.count, states.count)
        for (fips, row) in states {
            let r = strings(row) ?? []
            let want: StateName? = r.count == 2 ? StateName(name: r[0], usps: r[1]) : nil
            m.check(tables.states[fips], want, "state \(fips)")
        }
        XCTAssertEqual(tables.coveredStates, Set(tables.states.values.map(\.usps)).union(["DC"]))

        // The three { key: text } tables.
        for (name, table) in [("county-names.json", tables.countyNames), ("county-ecoregion.json", tables.countyEcoregion),
                              ("ecoregion-names.json", tables.ecoregionNames)] {
            let r = try raw(name)
            XCTAssertGreaterThan(r.count, 0, name)
            m.check(table, r.compactMapValues { str($0) }, name)
        }
        m.report("tables")
    }
}
