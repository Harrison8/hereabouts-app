// Checks RangeShapes against expected/map.json: where the website's RANGE_SHAPES.point puts a place on the map, and
// for each ecoregion how many separate shapes its outline has and where the first one starts.
import Foundation
import XCTest
@testable import HereaboutsCore

final class RangeMapTests: XCTestCase {
    private func loadShapes() throws -> RangeShapes {
        try RangeShapes.load(from: Expected.bundleDir.appendingPathComponent("tables").appendingPathComponent("range-shapes.json"))
    }

    func testEveryExpectedCase() throws {
        let shapes = try loadShapes()
        let cases = Expected.cases("map.json")
        var points = 0
        var regions = 0
        var bad: [String] = []
        for (index, c) in cases.enumerated() {
            if Set(c.keys) == ["lat", "lon", "point"] {
                points += 1
                let lat = c["lat"] as? Double ?? .nan
                let lon = c["lon"] as? Double ?? .nan
                let want = (c["point"] as? [Any] ?? []).compactMap { $0 as? Double }
                let (x, y) = shapes.point(lon: lon, lat: lat)
                if want.count != 2 || abs(x - want[0]) > 1e-9 || abs(y - want[1]) > 1e-9 {
                    bad.append("case \(index) (\(lat), \(lon)): got (\(x), \(y)), want \(want)")
                }
            } else if Set(c.keys) == ["region", "pathCount", "firstPoint"] {
                regions += 1
                let region = c["region"] as? String ?? ""
                let wantCount = Int(c["pathCount"] as? Double ?? -1)
                let wantFirst = (c["firstPoint"] as? [Any] ?? []).compactMap { $0 as? Double }
                do {
                    let paths = try shapes.paths(region: region)
                    let first = paths.first?.first
                    if paths.count != wantCount { bad.append("region \(region): \(paths.count) shapes, want \(wantCount)") }
                    if wantFirst.count != 2 || first?.0 != wantFirst[0] || first?.1 != wantFirst[1] {
                        bad.append("region \(region): first point \(String(describing: first)), want \(wantFirst)")
                    }
                } catch {
                    bad.append("region \(region): \(error)")
                }
            } else {
                bad.append("case \(index): unexpected fields \(c.keys.sorted())")
            }
        }
        XCTAssertGreaterThan(points, 0)
        XCTAssertEqual(regions, shapes.regions.count, "map.json should have one case per region")
        XCTAssertTrue(bad.isEmpty, "\(bad.count) mismatches in map.json; first: \(bad.prefix(5).joined(separator: " | "))")
        print("map.json: \(points) point cases and \(regions) region cases checked")
    }

    func testEveryShapeIsClosedWithPoints() throws {
        let shapes = try loadShapes()
        for region in shapes.regions.keys {
            let paths = try shapes.paths(region: region)
            XCTAssertFalse(paths.isEmpty, region)
            XCTAssertTrue(paths.allSatisfy { !$0.isEmpty }, region)
        }
        XCTAssertEqual(try shapes.paths(region: "no-such-region").count, 0)
    }

    func testPathsRejectUnknownCommands() {
        let projection = RangeShapes.Projection(n: 1, C: 1, r0: 1, x0: 0, y0: 0, scale: 1, lon0: 0)
        let shapes = RangeShapes(width: 10, height: 10, projection: projection, regions: [
            "curve": "M0 0C1 1 2 2 3 3Z", "relative": "M0 0l1 1Z", "arc": "M0 0A1 1 0 0 1 2 2Z", "cutShort": "M0 0L1",
            "noCommand": "0 0L1 1Z", "afterClose": "M0 0L1 1Z2 2", "lineFirst": "L1 1Z", "good": "M1 2L3 4 5 6ZM7 8L9 10Z",
        ])
        for bad in ["curve", "relative", "arc", "cutShort", "noCommand", "afterClose", "lineFirst"] {
            XCTAssertThrowsError(try shapes.paths(region: bad), bad)
        }
        let good = try? shapes.paths(region: "good")
        XCTAssertEqual(good?.count, 2)
        XCTAssertEqual(good?[0].map { [$0.0, $0.1] }, [[1, 2], [3, 4], [5, 6]])
        XCTAssertEqual(good?[1].map { [$0.0, $0.1] }, [[7, 8], [9, 10]])
    }
}
