// The opening inside the app: the loading screen's settings, colour and tag as the phone reads them, when the opening
// starts and when it does not, and the map it draws, with each area's shade from the website's home map.
import HereaboutsCore
import SwiftUI
import UIKit
import XCTest
@testable import Hereabouts

final class OpeningUnitTests: XCTestCase {
    @MainActor
    func testTheLoadingScreenIsKraftWithTheTagCentredOnTheWholeScreen() throws {
        let launch = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "UILaunchScreen") as? [String: Any])
        XCTAssertEqual(launch["UIColorName"] as? String, "LaunchKraft")
        XCTAssertEqual(launch["UIImageName"] as? String, "LaunchTag")
        XCTAssertEqual(launch["UIImageRespectsSafeAreaInsets"] as? Bool, false)

        let kraft = try XCTUnwrap(UIColor(named: "LaunchKraft"))
        var (r, g, b, a): (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 0)
        XCTAssertTrue(kraft.getRed(&r, green: &g, blue: &b, alpha: &a))
        XCTAssertEqual(Int((r * 255).rounded()), 0xEC)
        XCTAssertEqual(Int((g * 255).rounded()), 0xE5)
        XCTAssertEqual(Int((b * 255).rounded()), 0xD3)
        XCTAssertEqual(a, 1)

        // The phone shows the tag at its own size, which must be the opening's 160 points.
        let tag = try XCTUnwrap(UIImage(named: "LaunchTag"))
        XCTAssertEqual(tag.size, CGSize(width: OpeningLayout.tagSize, height: OpeningLayout.tagSize))
    }

    @MainActor
    private func opening(_ arguments: [String], reduceMotion: Bool = false) -> Opening {
        let shipped = AppModel.forLaunch(arguments: [])
        return Opening(geo: shipped.data.geo, location: nil, arguments: arguments, reduceMotion: reduceMotion)
    }

    @MainActor
    func testItIsDecidedWhenMadeAndNotWithReduceMotion() {
        let plain = opening([])
        XCTAssertEqual(plain.start, .play)
        // The clock starts when the first frame appears, not when the app is made; until then every frame is 0 ms.
        XCTAssertNil(plain.startedAt)
        XCTAssertEqual(plain.elapsedMs(at: .now), 0)
        XCTAssertNil(plain.shown)
        plain.appeared()
        XCTAssertNotNil(plain.startedAt)
        XCTAssertEqual(plain.shown, .play)

        XCTAssertEqual(opening([], reduceMotion: true).start, .off)
    }

    @MainActor
    func testAReminderTapStopsItAndAScreenTestCanTurnItOffOrHoldIt() {
        defer { Opening.launchedFromReminder = false }
        let tapped = opening([])
        tapped.stop()
        XCTAssertTrue(tapped.ended)

        // The window connecting because a reminder was tapped takes away the opening the app already made, and one
        // made after it does not start.
        let launched = opening([])
        Opening.reminderOpenedTheApp()
        XCTAssertTrue(launched.ended)
        XCTAssertEqual(opening([]).start, .off)
        Opening.launchedFromReminder = false

        XCTAssertEqual(opening(["-uiTestSkipOpening"]).start, .off)
        let held = opening(["-uiTestOpeningAt", "150"])
        XCTAssertEqual(held.start, .hold(ms: 150))
        held.appeared()
        XCTAssertEqual(held.shown, .hold(ms: 150))
        XCTAssertEqual(opening(["-uiTestOpeningAt", "150", "-uiTestSkipOpening"]).start, .off)
    }

    @MainActor
    func testItGoesAfterHalfASecond() async {
        let o = opening([])
        XCTAssertEqual(o.start, .play)
        o.appeared()
        let before = Date.now
        await o.playToEnd()
        XCTAssertTrue(o.ended)
        XCTAssertGreaterThan(Date.now.timeIntervalSince(before), 0.4)
    }

    func testTheMapHasEveryAreaInItsHomeMapShadeAndTheNearestStartsFirst() throws {
        let shipped = try AppData.load(from: AppModel.bundleURL.appendingPathComponent("data"))
        let map = try XCTUnwrap(RangeMapShapes.shared)
        let fairfax = Location(countyFips: "51059", countyName: "Fairfax County", stateFips: "51",
                               stateName: "Virginia", stateUsps: "VA", ecoregion: "", lat: 38.85, lon: -77.31)
        let opening = try XCTUnwrap(OpeningMap.build(geo: shipped.geo, location: fairfax))
        XCTAssertEqual(opening.areas.count, map.shapes.regions.count)
        XCTAssertEqual(opening.scale, 1000 / map.shapes.width)
        let steps = HomeMap.steps(geo: shipped.geo, regions: Array(map.shapes.regions.keys))
        for (area, (code, _)) in zip(opening.areas, map.paths) {
            XCTAssertEqual(area.strength, steps[code].map { HomeMap.strengths[$0] }, "area \(code)")
        }
        XCTAssertGreaterThan(Set(opening.areas.map(\.strength)).count, 2)
        // Fairfax County's point; nearer areas start sooner, and the farthest wait the full 360 ms.
        let dot = try XCTUnwrap(RangeMapView.point(fairfax, map: map.shapes))
        XCTAssertEqual(opening.county, OpeningPoint(x: Double(dot.x), y: Double(dot.y)))
        XCTAssertLessThan(opening.areas.map(\.delayMs).min() ?? 360, 360)
        XCTAssertEqual(opening.areas.map(\.delayMs).max(), 360)

        let nowhere = try XCTUnwrap(OpeningMap.build(geo: shipped.geo, location: nil))
        XCTAssertEqual(nowhere.county, OpeningPoint(x: map.shapes.width / 2, y: map.shapes.height / 2))
    }
}
