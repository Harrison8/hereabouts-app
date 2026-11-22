// Checks the opening's sums against the approved preview, the rules for when it plays, and the home map's shades
// against the website's own home map (expected/home-map.json).
//
// The preview's numbers below were worked out with the preview's own formulas, for its 393 x 852 point phone with
// Fairfax County's point under the tag.
import Foundation
import XCTest
@testable import HereaboutsCore

final class OpeningTests: XCTestCase {
    private let fairfax = OpeningPoint(x: 546.2722381929555, y: 176.57759987397765)

    // MARK: Where things sit

    func testThePreviewPhoneLayout() {
        let layout = OpeningLayout(screenWidth: 393, screenHeight: 852)
        XCTAssertEqual(layout.tag, OpeningRect(x: 116.5, y: 346, width: 160, height: 160))
        XCTAssertEqual(layout.point, OpeningPoint(x: 196.5, y: 491))
        XCTAssertEqual(layout.backing, OpeningRect(x: 145.25, y: 362.25, width: 102.5, height: 72.5))
        XCTAssertEqual(layout.maxRadius, 530.8603312784956, accuracy: 1e-9)
        let scale = OpeningMotion.scale(mapWidth: 640)
        XCTAssertEqual(scale, 1.5625)
        let origin = layout.mapOrigin(county: fairfax, scale: scale)
        XCTAssertEqual(origin.x, -657.0503721764929, accuracy: 1e-9)
        XCTAssertEqual(origin.y, 215.09750019690995, accuracy: 1e-9)
    }

    func testAnotherPhoneSizeCentresTheTagOnTheWholeScreen() {
        // iPhone 17: 402 x 874 points.
        let layout = OpeningLayout(screenWidth: 402, screenHeight: 874)
        XCTAssertEqual(layout.tag, OpeningRect(x: 121, y: 357, width: 160, height: 160))
        XCTAssertEqual(layout.point, OpeningPoint(x: 201, y: 502))
        XCTAssertEqual(layout.maxRadius, 542.7448566560759, accuracy: 1e-9)
    }

    func testWithNoCountyTheMapsMiddleSitsUnderTheTagsPoint() {
        let middle = OpeningMotion.county(nil, mapWidth: 640, mapHeight: 407)
        XCTAssertEqual(middle, OpeningPoint(x: 320, y: 203.5))
        XCTAssertEqual(OpeningMotion.county(fairfax, mapWidth: 640, mapHeight: 407), fairfax)
        let layout = OpeningLayout(screenWidth: 402, screenHeight: 874)
        let origin = layout.mapOrigin(county: middle, scale: OpeningMotion.scale(mapWidth: 640))
        XCTAssertEqual(origin, OpeningPoint(x: -299, y: 184.03125))
    }

    // MARK: Timing

    func testEachAreaFillsOnTheSpringThatSettlesIn200ms() {
        let want: [(Double, Double)] = [
            (-10, 0), (0, 0), (20, 0.17088153540753814), (50, 0.5540519811031672), (100, 0.8851640951426633),
            (150, 0.9750181501148801), (200, 0.9949994292746207), (300, 0.999824537012993),
        ]
        for (ms, value) in want {
            XCTAssertEqual(OpeningMotion.fill(ms), value, accuracy: 1e-12, "at \(ms) ms")
        }
    }

    func testNearerAreasStartSoonerAndNoneLaterThan360ms() {
        let want: [(Double, Double)] = [(0, 0), (100, 58.064516129032256), (310, 180), (620, 360), (1000, 360)]
        for (distance, delay) in want {
            XCTAssertEqual(OpeningMotion.delay(distance: distance), delay, accuracy: 1e-12, "at \(distance) points")
        }
        // 64 map units east of the county is 100 points on screen.
        let east = OpeningPoint(x: fairfax.x + 64, y: fairfax.y)
        XCTAssertEqual(OpeningMotion.delay(from: fairfax, to: east, scale: 1.5625), 58.064516129032256, accuracy: 1e-9)
    }

    func testTheFirstScreenOpensOutOfTheTagsPointFrom250msAndIsWholeAt500ms() {
        let maxRadius = 530.8603312784956
        let want: [(Double, Double)] = [
            (0, 0), (250, 0), (260, 31.841081956973472), (300, 148.1514329836053), (350, 271.49538369528955),
            (400, 374.1857854739207), (450, 459.68100884410717), (499, 529.5608248779678), (500, maxRadius),
            (700, maxRadius),
        ]
        for (ms, radius) in want {
            XCTAssertEqual(OpeningMotion.radius(ms, maxRadius: maxRadius), radius, accuracy: 1e-9, "at \(ms) ms")
        }
        XCTAssertEqual(OpeningMotion.lengthMs, 500)
        XCTAssertEqual(OpeningMotion.openAtMs, 250)
    }

    // MARK: When it plays

    func testItPlaysOnAPlainLaunch() {
        XCTAssertEqual(OpeningRule.start(reduceMotion: false, fromReminder: false, shownBefore: false,
                                         skipForTests: false, holdAtMs: nil), .play)
    }

    func testItDoesNotPlayWithReduceMotionAReminderTapAReturnFromTheBackgroundOrTheTestSwitch() {
        let cases: [(String, Bool, Bool, Bool, Bool)] = [
            ("Reduce Motion", true, false, false, false),
            ("opened from a reminder", false, true, false, false),
            ("already shown this launch", false, false, true, false),
            ("-uiTestSkipOpening", false, false, false, true),
        ]
        for (name, reduce, reminder, shown, skip) in cases {
            XCTAssertEqual(OpeningRule.start(reduceMotion: reduce, fromReminder: reminder, shownBefore: shown,
                                             skipForTests: skip, holdAtMs: nil), OpeningStart.off, name)
            // Holding a frame for a screenshot never overrides a reason not to play.
            XCTAssertEqual(OpeningRule.start(reduceMotion: reduce, fromReminder: reminder, shownBefore: shown,
                                             skipForTests: skip, holdAtMs: 150), OpeningStart.off, name)
        }
    }

    func testAScreenTestCanHoldOneFrame() {
        func hold(_ ms: Double) -> OpeningStart {
            OpeningRule.start(reduceMotion: false, fromReminder: false, shownBefore: false, skipForTests: false, holdAtMs: ms)
        }
        XCTAssertEqual(hold(150), .hold(ms: 150))
        XCTAssertEqual(hold(0), .hold(ms: 0))
        XCTAssertEqual(hold(-5), .hold(ms: 0))
        XCTAssertEqual(hold(900), .hold(ms: 500))
    }

    // MARK: The home map's shades

    func testTheShadeStepsAreTheWebsites() {
        let want: [(Int, Int)] = [(0, 0), (1, 1), (20, 1), (21, 2), (75, 2), (76, 3), (125, 3), (126, 4), (900, 4)]
        for (count, step) in want {
            XCTAssertEqual(HomeMap.step(plantCount: count), step, "\(count) plants")
        }
        XCTAssertEqual(HomeMap.strengths, [0.07, 0.2, 0.4, 0.66, 0.92])
    }

    func testEachPlantCountsOnceForEveryRegionItIsNativeTo() {
        let geo = [
            "a": PlantGeo(eco: ["1", "2"], intro: []),
            "b": PlantGeo(eco: ["2"], intro: ["51"]),
        ]
        let steps = HomeMap.steps(geo: geo, regions: ["1", "2", "3"])
        XCTAssertEqual(steps, ["1": 1, "2": 1, "3": 0])
        XCTAssertEqual(HomeMap.counts(geo: geo), ["1": 1, "2": 2])
    }

    func testTheShadesMatchTheWebsitesHomeMap() throws {
        let data = try AppData.load(from: Expected.bundleDir.appendingPathComponent("data"))
        let shapes = try RangeShapes.load(from: Expected.bundleDir.appendingPathComponent("tables")
            .appendingPathComponent("range-shapes.json"))
        let steps = HomeMap.steps(geo: data.geo, regions: Array(shapes.regions.keys))
        let cases = Expected.cases("home-map.json")
        XCTAssertEqual(cases.count, shapes.regions.count, "home-map.json should have one case per region")
        var bad: [String] = []
        for c in cases {
            let region = c["region"] as? String ?? ""
            let want = Int(c["step"] as? Double ?? -1)
            if steps[region] != want { bad.append("region \(region): \(String(describing: steps[region])), want \(want)") }
        }
        XCTAssertTrue(bad.isEmpty, "\(bad.count) mismatches in home-map.json; first: \(bad.prefix(5).joined(separator: " | "))")
        print("home-map.json: \(cases.count) regions checked")
    }
}
