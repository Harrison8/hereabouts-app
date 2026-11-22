// The app's frame: with a county already chosen, the three tabs are there and each opens its own screen.
import XCTest

final class ShellTests: XCTestCase {
    @MainActor
    func testThreeTabs() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        // Fairfax County, Virginia, so the app skips the first-open screen.
        app.launchArguments = ["-uiTestSkipOpening", "-uiTestLocation", "51059"]
        app.launch()

        let tabBar = app.tabBars.firstMatch
        XCTAssertTrue(tabBar.waitForExistence(timeout: 20), "No tab bar after launch")

        for name in ["Plants", "Garden", "Settings"] {
            let button = tabBar.buttons[name]
            XCTAssertTrue(button.exists, "The tab bar has no \(name) button")
            button.tap()
            // The Garden tab is titled "My garden", as the approved picture has it.
            let title = name == "Garden" ? "My garden" : name
            XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 10), "Tapping \(name) did not show its title")

            let shot = XCTAttachment(screenshot: app.screenshot())
            shot.name = name
            shot.lifetime = .keepAlways
            add(shot)
        }
    }
}
