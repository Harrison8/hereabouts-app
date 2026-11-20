// The app's frame: with a county already chosen, the three tabs are there and each opens its own screen.
import XCTest

final class ShellTests: XCTestCase {
    @MainActor
    func testThreeTabs() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        // Fairfax County, Virginia, so the app skips the first-open screen.
        app.launchArguments = ["-uiTestLocation", "51059"]
        app.launch()

        let tabBar = app.tabBars.firstMatch
        XCTAssertTrue(tabBar.waitForExistence(timeout: 20), "No tab bar after launch")

        for name in ["Plants", "Garden", "Settings"] {
            let button = tabBar.buttons[name]
            XCTAssertTrue(button.exists, "The tab bar has no \(name) button")
            button.tap()
            XCTAssertTrue(app.navigationBars[name].waitForExistence(timeout: 10), "Tapping \(name) did not show its title")

            let shot = XCTAttachment(screenshot: app.screenshot())
            shot.name = name
            shot.lifetime = .keepAlways
            add(shot)
        }
    }
}
