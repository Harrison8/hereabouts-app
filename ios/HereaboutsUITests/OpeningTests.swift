// The opening: the home map fills in around the tag, then the first screen opens out of the tag's point. Every other
// screen test skips it with -uiTestSkipOpening; these run it, check it is gone and the screen under it works within a
// second, and keep three of its frames as screenshots. -uiTestOpeningAt <ms> holds the opening still at that moment,
// so a frame can be photographed. A Debug build under test also leaves a mark once the opening has been on screen,
// "Opening played" or "Opening held", which stays after the opening goes, so a test can tell an opening that played
// and ended from one that never came up.
import XCTest

final class OpeningTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    /// The opening's cover. Debug builds launched by a screen test show it to the tests under this name; everywhere
    /// else it is hidden from VoiceOver.
    @MainActor
    private func cover(_ app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: "opening").firstMatch
    }

    /// The mark the opening leaves once it has been on screen.
    @MainActor
    private func shown(_ app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: "openingShown").firstMatch
    }

    @MainActor
    private func shoot(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    @MainActor
    func testWithACountyTheOpeningEndsOnPlantsAndTheTabsWorkWithinASecond() {
        let app = XCUIApplication()
        // A ZIP code in Fairfax County, Virginia, so the county has a point on the map.
        app.launchArguments = ["-uiTestZip", "22030"]
        app.launch()
        XCTAssertTrue(shown(app).waitForExistence(timeout: 5), "The opening never came up")
        XCTAssertEqual(shown(app).label, "Opening played")
        XCTAssertTrue(cover(app).waitForNonExistence(timeout: 1), "The opening was still up a second after launch")
        let garden = app.tabBars.buttons["Garden"]
        XCTAssertTrue(garden.waitForExistence(timeout: 1), "No Garden tab a second after the opening")
        XCTAssertTrue(app.navigationBars["Plants"].exists, "The app did not open on Plants")
        XCTAssertTrue(garden.isHittable, "The Garden tab cannot be tapped")
        garden.tap()
        XCTAssertTrue(app.navigationBars["My garden"].waitForExistence(timeout: 5), "Tapping Garden did not open it")
        shoot(app, "Opening end, with a county")
    }

    @MainActor
    func testWithNoCountyTheOpeningEndsOnTheZipScreen() {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestFirstOpen"]
        app.launch()
        XCTAssertTrue(shown(app).waitForExistence(timeout: 5), "The opening never came up")
        XCTAssertEqual(shown(app).label, "Opening played")
        XCTAssertTrue(cover(app).waitForNonExistence(timeout: 1), "The opening was still up a second after launch")
        let zip = app.textFields["zipField"]
        XCTAssertTrue(zip.waitForExistence(timeout: 1), "No ZIP field a second after the opening")
        XCTAssertTrue(zip.isHittable, "The ZIP field cannot be tapped")
        zip.tap()
        shoot(app, "Opening end, first open")
    }

    /// The loading screen (0 ms), the map filling in (150 ms) and the first screen opening (350 ms).
    @MainActor
    func testThreeFramesOfTheOpening() {
        for (index, ms) in [0, 150, 350].enumerated() {
            let app = XCUIApplication()
            app.launchArguments = ["-uiTestZip", "22030", "-uiTestOpeningAt", String(ms)]
            app.launch()
            XCTAssertTrue(cover(app).waitForExistence(timeout: 20), "The opening held at \(ms) ms is not on screen")
            XCTAssertEqual(shown(app).label, "Opening held")
            shoot(app, "Opening \(index + 1), \(ms) ms")
            app.terminate()
        }
    }

    @MainActor
    func testTheTestSwitchSkipsTheOpening() {
        let app = XCUIApplication()
        // Held at 0 ms, the opening would stay up for good, so its absence shows the switch worked.
        app.launchArguments = ["-uiTestLocation", "51059", "-uiTestOpeningAt", "0", "-uiTestSkipOpening"]
        app.launch()
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 20), "No tab bar after launch")
        XCTAssertFalse(cover(app).exists, "The opening was put up although -uiTestSkipOpening was given")
        XCTAssertFalse(shown(app).exists, "The opening came up although -uiTestSkipOpening was given")
    }

    /// -uiTestNotificationTap goes the way a real tap does: the window connects with the reminder in hand, which must
    /// keep the opening from coming up, and the tap itself reaches the app a second later. Held at 0 ms, an opening
    /// that came up would leave its mark even after that late tap took it away.
    @MainActor
    func testOpeningFromAReminderShowsNoOpening() {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestLocation", "51059", "-uiTestOpeningAt", "0", "-uiTestNotificationTap"]
        app.launch()
        XCTAssertTrue(app.navigationBars["My garden"].waitForExistence(timeout: 20), "A reminder tap did not open Garden")
        XCTAssertFalse(cover(app).exists, "The opening was put up for a launch from a reminder")
        XCTAssertFalse(shown(app).exists, "The opening came up for a launch from a reminder, then went")
    }
}
