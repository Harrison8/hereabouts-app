// The App Store pictures: six screens of one realistic walk through the app for Jefferson County, Kentucky (the
// ZIP code 40202, Louisville), each attached as "store-<number>-<screen>": the plant list, a plant page with its
// photo and name, the same page scrolled to its month chart, the Where to buy panel, the Garden tab with three saved
// plants and this week's jobs, and the first screen where a ZIP code is typed. Normal text size, light look.
//
// The phone's status bar is set by the workflow before this test runs, so the pictures show a full battery and a
// steady signal. This test is skipped in the everyday runs and runs only from the workflow's store-screenshots job.
//
// Nothing here fails the run for what a screen looks like. A control that cannot be found is written down in the
// "notes" attachment and the walk goes on, so one missing control does not cost the pictures after it.
// Events and links go to a stand-in server on 127.0.0.1 run by the test; nothing reaches an outside address.
import XCTest

final class StoreScreenshotTests: XCTestCase {
    private var server: StubServer!
    private var notes: [String] = []

    override func setUpWithError() throws {
        continueAfterFailure = true
        server = try StubServer()
    }

    override func tearDown() {
        if !notes.isEmpty {
            let attachment = XCTAttachment(string: notes.joined(separator: "\n"))
            attachment.name = "notes-store"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        server.stop()
        server = nil
    }

    // MARK: - launching and reading the screen

    /// The app at the normal text size with `extra` launch switches. The opening animation is skipped.
    @MainActor
    private func launch(_ extra: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestSkipOpening", "-hereaboutsBaseURL", server.baseURL, "-uiTestCaptureLinks"] + extra
        app.launch()
        return app
    }

    private func note(_ line: String) {
        notes.append(line)
    }

    /// Gives pictures time to appear: the photos are read from the app's own files, but drawn a moment later.
    private func settle(_ seconds: TimeInterval = 1.5) {
        Thread.sleep(forTimeInterval: seconds)
    }

    @MainActor
    private func shot(_ app: XCUIApplication, _ screen: String) {
        settle()
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "store-\(screen)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    @MainActor
    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier]
    }

    /// Taps `element` once it is there. Says so in the notes when it never shows.
    @MainActor
    @discardableResult
    private func tap(_ element: XCUIElement, _ what: String, timeout: TimeInterval = 10) -> Bool {
        guard element.waitForExistence(timeout: timeout) else {
            note("MISSING \(what)")
            return false
        }
        guard element.isHittable else {
            note("NOT TAPPABLE \(what): it is there but cannot be tapped (covered or off screen)")
            return false
        }
        element.tap()
        return true
    }

    /// Scrolls up to eight times until the element can be tapped.
    @MainActor
    private func reveal(_ app: XCUIApplication, _ target: XCUIElement) {
        for _ in 0..<8 where !(target.exists && target.isHittable) { app.swipeUp() }
    }

    @MainActor
    private func waitForPlants(_ app: XCUIApplication) {
        if !app.navigationBars["Plants"].waitForExistence(timeout: 20) { note("The app did not open on Plants") }
        _ = StubServer.wait(20) {
            app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'plant-'")).firstMatch.exists
        }
    }

    @MainActor
    private func openGardenTab(_ app: XCUIApplication) {
        let tabBar = app.tabBars.firstMatch
        if !tabBar.waitForExistence(timeout: 20) { note("No tab bar after launch") }
        tap(tabBar.buttons["Garden"], "the Garden tab")
        if !app.navigationBars["My garden"].waitForExistence(timeout: 10) { note("Garden did not open") }
    }

    // MARK: - the walk

    /// Butterfly Weed is first in Jefferson County's list. July is when its flowers and most of the list are in bloom.
    private let summer = ["-uiTestNow", "2027-07-12T09:00"]
    private let plantId = "asclepias-tuberosa"

    @MainActor
    func testStorePictures() throws {
        // 01 and 02 to 04: the list, then a plant from it.
        var app = launch(["-uiTestZip", "40202"] + summer)
        waitForPlants(app)
        shot(app, "01-plants")

        let row = element(app, "plant-\(plantId)")
        if tap(row, "the row for \(plantId)") {
            if !StubServer.wait(10, until: { app.buttons["whereToBuy"].exists || self.element(app, "noPhoto").exists
                || app.staticTexts["photoCounter"].exists }) {
                note("The plant page for \(plantId) did not open")
            }
            shot(app, "02-plant")

            let chart = element(app, "seasonChart")
            reveal(app, chart)
            if !chart.exists { note("MISSING the month chart") }
            shot(app, "03-plant-months")

            let buy = app.buttons["whereToBuy"]
            for _ in 0..<8 where !(buy.exists && buy.isHittable) { app.swipeDown() }
            if tap(buy, "the Where to buy button") {
                _ = app.buttons["whereToBuyDone"].waitForExistence(timeout: 10)
                _ = element(app, "seller").waitForExistence(timeout: 10)
                shot(app, "04-where-to-buy")
            }
        }

        // 05: the Garden tab with three saved plants and this week's jobs. November has the most jobs.
        app.terminate()
        let saved = ["asclepias-tuberosa", "echinacea-purpurea", "lindera-benzoin"].joined(separator: ",")
        app = launch(["-uiTestLocation", "21111", "-uiTestNow", "2027-11-18T09:00", "-uiTestSaved", saved])
        openGardenTab(app)
        let anyJob = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'job-'")).firstMatch
        if !anyJob.waitForExistence(timeout: 10) { note("No job showed on the Garden tab") }
        shot(app, "05-garden")

        // 06: the first screen, where a ZIP code is typed.
        app.terminate()
        app = launch(["-uiTestFirstOpen"])
        if !app.textFields["zipField"].waitForExistence(timeout: 20) { note("No ZIP field on the first-open screen") }
        shot(app, "06-first-open")
        app.terminate()
    }
}
