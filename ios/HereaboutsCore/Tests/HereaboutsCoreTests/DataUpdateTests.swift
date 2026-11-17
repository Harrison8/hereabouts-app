// Checks the daily data check: when to look for new data, and which downloads are used. Each rejected download
// below is wrong in one way only (its hash is worked out again for the changed files), so it is rejected for that
// reason and no other.
import Foundation
import XCTest
@testable import HereaboutsCore

final class DataUpdateTests: XCTestCase {
    private let current = DataVersion(schema: 1, dataDate: "2026-11-09T21:26:41-05:00", dataHash: String(repeating: "a", count: 64))

    private let goodPlants = Data(#"[{"id":"new-plant","common":"New Plant","sci":"Nova planta","category":"perennial"}]"#.utf8)
    private let goodVendors = Data(#"{"Some Nursery":{"name":"Some Nursery"}}"#.utf8)
    private let goodRegions = Data(#"{"schema":1,"plants":{"new-plant":{"eco":["71"],"intro":[]}},"served":["71"]}"#.utf8)

    private func version(_ plants: Data, _ vendors: Data, _ regions: Data, schema: Int = 1,
                         date: String = "2026-11-12T08:00:00-05:00", hash: String? = nil) -> Data {
        var all = Data()
        all.append(plants)
        all.append(vendors)
        all.append(regions)
        let h = hash ?? SHA256.hex(all)
        return Data(#"{"schema":\#(schema),"dataDate":"\#(date)","dataHash":"\#(h)"}"#.utf8)
    }

    private func accept(_ plants: Data, _ vendors: Data, _ regions: Data, schema: Int = 1, date: String = "2026-11-12T08:00:00-05:00",
                        hash: String? = nil, current: DataVersion? = nil) -> AppData? {
        DataUpdate.accept(version: version(plants, vendors, regions, schema: schema, date: date, hash: hash), plants: plants,
                          vendors: vendors, regions: regions, current: current ?? self.current)
    }

    func testGoodSetIsUsed() throws {
        let data = try XCTUnwrap(accept(goodPlants, goodVendors, goodRegions))
        XCTAssertEqual(data.plants.map(\.id), ["new-plant"])
        XCTAssertEqual(data.vendors["Some Nursery"]?.name, "Some Nursery")
        XCTAssertEqual(data.geo["new-plant"]?.eco, ["71"])
        XCTAssertEqual(data.served, ["71"])
        XCTAssertEqual(data.version.dataDate, "2026-11-12T08:00:00-05:00")
    }

    func testSameDateWithNewHashIsUsed() {
        // A second publish on the same day: same date, different files.
        XCTAssertNotNil(accept(goodPlants, goodVendors, goodRegions, date: current.dataDate))
        // The same moment written in UTC.
        XCTAssertNotNil(accept(goodPlants, goodVendors, goodRegions, date: "2026-11-10T02:26:41Z"))
    }

    func testCutOffPlantsFileIsRejected() {
        let cut = goodPlants.prefix(goodPlants.count - 10)
        XCTAssertNil(accept(Data(cut), goodVendors, goodRegions))
    }

    func testLaterSchemaIsRejected() {
        XCTAssertNil(accept(goodPlants, goodVendors, goodRegions, schema: 2))
    }

    func testVendorsListIsRejected() {
        XCTAssertNil(accept(goodPlants, Data(#"[{"name":"Some Nursery"}]"#.utf8), goodRegions))
    }

    func testHashMismatchIsRejected() {
        XCTAssertNil(accept(goodPlants, goodVendors, goodRegions, hash: String(repeating: "0", count: 64)))
    }

    func testSameHashAsNowIsRejected() {
        var all = Data()
        all.append(goodPlants)
        all.append(goodVendors)
        all.append(goodRegions)
        let now = DataVersion(schema: 1, dataDate: current.dataDate, dataHash: SHA256.hex(all))
        XCTAssertNil(accept(goodPlants, goodVendors, goodRegions, current: now))
    }

    func testOlderDateIsRejected() {
        XCTAssertNil(accept(goodPlants, goodVendors, goodRegions, date: "2026-11-01T00:00:00-05:00"))
        // One second before the current moment, written in UTC.
        XCTAssertNil(accept(goodPlants, goodVendors, goodRegions, date: "2026-11-10T02:26:40Z"))
        XCTAssertNil(accept(goodPlants, goodVendors, goodRegions, date: "not a date"))
        XCTAssertNil(accept(goodPlants, goodVendors, goodRegions, date: "2026-11-12T08:00:00"))
    }

    func testPlantWithoutNameIsRejected() {
        let noCommon = Data(#"[{"id":"new-plant","sci":"Nova planta","category":"perennial"}]"#.utf8)
        XCTAssertNil(accept(noCommon, goodVendors, goodRegions))
        let emptyId = Data(#"[{"id":"","common":"New Plant","sci":"Nova planta","category":"perennial"}]"#.utf8)
        XCTAssertNil(accept(emptyId, goodVendors, goodRegions))
        let noSci = Data(#"[{"id":"new-plant","common":"New Plant","category":"perennial"}]"#.utf8)
        XCTAssertNil(accept(noSci, goodVendors, goodRegions))
        let emptyCategory = Data(#"[{"id":"new-plant","common":"New Plant","sci":"Nova planta","category":""}]"#.utf8)
        XCTAssertNil(accept(emptyCategory, goodVendors, goodRegions))
    }

    func testEmptyPlantsIsRejected() {
        XCTAssertNil(accept(Data("[]".utf8), goodVendors, goodRegions))
    }

    func testNoServedRegionsIsRejected() {
        let noServed = Data(#"{"schema":1,"plants":{"new-plant":{"eco":["71"],"intro":[]}},"served":[]}"#.utf8)
        XCTAssertNil(accept(goodPlants, goodVendors, noServed))
        let noRanges = Data(#"{"schema":1,"plants":{},"served":["71"]}"#.utf8)
        XCTAssertNil(accept(goodPlants, goodVendors, noRanges))
    }

    func testRepeatedPlantIdIsRejected() {
        let twice = Data(#"""
        [{"id":"new-plant","common":"New Plant","sci":"Nova planta","category":"perennial"},
         {"id":"new-plant","common":"Other Plant","sci":"Alia planta","category":"shrub"}]
        """#.utf8)
        XCTAssertNil(accept(twice, goodVendors, goodRegions))
    }

    func testUnreadableCurrentDateSkipsTheDateCheck() {
        // The current dataDate cannot be read: any readable new date is used, as long as the hash differs.
        let unreadable = DataVersion(schema: 1, dataDate: "not a date", dataHash: current.dataHash)
        XCTAssertNotNil(accept(goodPlants, goodVendors, goodRegions, date: "2000-01-01T00:00:00Z", current: unreadable))
        var all = Data()
        all.append(goodPlants)
        all.append(goodVendors)
        all.append(goodRegions)
        let sameHash = DataVersion(schema: 1, dataDate: "not a date", dataHash: SHA256.hex(all))
        XCTAssertNil(accept(goodPlants, goodVendors, goodRegions, current: sameHash))
    }

    func testBrokenVersionFileIsRejected() {
        XCTAssertNil(DataUpdate.accept(version: Data("{".utf8), plants: goodPlants, vendors: goodVendors, regions: goodRegions,
                                       current: current))
    }

    func testBundleDataPassesItsOwnCheck() throws {
        let dir = Expected.bundleDir.appendingPathComponent("data")
        let read = { (name: String) in try Data(contentsOf: dir.appendingPathComponent(name)) }
        let older = DataVersion(schema: 1, dataDate: "2020-01-01T00:00:00Z", dataHash: "")
        let data = try XCTUnwrap(DataUpdate.accept(version: read("version.json"), plants: read("plants.json"),
                                                   vendors: read("vendors.json"), regions: read("plant-regions.json"),
                                                   current: older))
        XCTAssertGreaterThan(data.plants.count, 0)
    }

    func testShouldCheck() {
        let now = Date(timeIntervalSince1970: 2_000_000_000)
        XCTAssertTrue(DataUpdate.shouldCheck(last: nil, now: now))
        XCTAssertTrue(DataUpdate.shouldCheck(last: now.addingTimeInterval(-24 * 3600), now: now))
        XCTAssertTrue(DataUpdate.shouldCheck(last: now.addingTimeInterval(-30 * 3600), now: now))
        XCTAssertFalse(DataUpdate.shouldCheck(last: now.addingTimeInterval(-24 * 3600 + 1), now: now))
        XCTAssertFalse(DataUpdate.shouldCheck(last: now, now: now))
        // The clock was moved back: check again.
        XCTAssertTrue(DataUpdate.shouldCheck(last: now.addingTimeInterval(3600), now: now))
    }

    func testInstant() {
        XCTAssertEqual(DataUpdate.instant("1970-01-01T00:00:00Z"), 0)
        XCTAssertEqual(DataUpdate.instant("1970-01-01T00:00Z"), 0)
        XCTAssertEqual(DataUpdate.instant("2026-11-09T21:26:41-05:00"), DataUpdate.instant("2026-11-10T02:26:41Z"))
        XCTAssertEqual(DataUpdate.instant("2026-11-10T02:26:41Z"), 1_794_277_601)
        XCTAssertEqual(DataUpdate.instant("2000-02-29T12:00:00.5+00:00"), 951_825_600.5)
        XCTAssertNil(DataUpdate.instant("2026-02-29T00:00:00Z"))
        XCTAssertNil(DataUpdate.instant("2026-11-10"))
        XCTAssertNil(DataUpdate.instant("2026-11-10T02:26:41+0500"))
    }
}
