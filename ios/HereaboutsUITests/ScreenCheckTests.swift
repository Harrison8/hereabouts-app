// The screen check: every screen and sheet a person can reach, photographed at the normal text size and at the
// largest accessibility size, with the phone's own accessibility audit run on each main screen. Each picture is named
// "<size>-<screen>" and each audit's findings are attached as text named "a11y-<size>-<screen>".
//
// Nothing here fails the run for what a screen looks like. A control that cannot be found is written down in the
// "notes" attachment and the flow goes on, so one missing control does not cost the pictures after it. The audit's
// findings are collected, never counted as failures.
// Events and links go to a stand-in server on 127.0.0.1 run by the test; nothing reaches an outside address.
import CoreLocation
import XCTest

final class ScreenCheckTests: XCTestCase {
    private var server: StubServer!
    private var size = "normal"
    private var notes: [String] = []

    override func setUpWithError() throws {
        continueAfterFailure = true
        server = try StubServer()
    }

    override func tearDown() {
        if !notes.isEmpty {
            let attachment = XCTAttachment(string: notes.joined(separator: "\n"))
            attachment.name = "notes-\(size)-\(name.replacingOccurrences(of: "[^A-Za-z0-9]+", with: "-", options: .regularExpression))"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        server.stop()
        server = nil
    }

    // MARK: - launching and reading the screen

    /// The app with `extra` launch switches, at the largest text size when asked.
    @MainActor
    private func launch(_ extra: [String], largest: Bool, skipOpening: Bool = true) -> XCUIApplication {
        size = largest ? "largest" : "normal"
        let app = XCUIApplication()
        var arguments = skipOpening ? ["-uiTestSkipOpening"] : []
        arguments += ["-hereaboutsBaseURL", server.baseURL, "-uiTestCaptureLinks"] + extra
        if largest { arguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }
        app.launchArguments = arguments
        app.launch()
        return app
    }

    private func note(_ line: String) {
        notes.append("[\(size)] \(line)")
    }

    @MainActor
    private func shot(_ app: XCUIApplication, _ screen: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "\(size)-\(screen)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// The phone's accessibility audit on what is on screen. Findings are attached as text and never fail the test.
    @MainActor
    private func audit(_ app: XCUIApplication, _ screen: String) {
        var lines: [String] = []
        do {
            try app.performAccessibilityAudit(for: .all) { issue in
                let element = issue.element
                let what = [element?.label, element?.identifier].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " / ")
                lines.append("type \(issue.auditType.rawValue): \(issue.compactDescription) | element: \(what.isEmpty ? "unnamed" : what) | \(issue.detailedDescription)")
                return true
            }
        } catch {
            lines.append("The audit could not finish: \(error)")
        }
        let text = lines.isEmpty ? "No findings." : lines.joined(separator: "\n")
        let attachment = XCTAttachment(string: text)
        attachment.name = "a11y-\(size)-\(screen)"
        attachment.lifetime = .keepAlways
        add(attachment)
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

    /// Like `tap`, but when the element is there and inside the window yet reported as not tappable, taps its centre
    /// by coordinate and says so in the notes. At the largest text some controls report this although they show.
    @MainActor
    @discardableResult
    private func tapOrCentre(_ app: XCUIApplication, _ element: XCUIElement, _ what: String) -> Bool {
        guard element.waitForExistence(timeout: 10) else {
            note("MISSING \(what)")
            return false
        }
        if element.isHittable {
            element.tap()
            return true
        }
        let frame = element.frame
        if frame.width > 0, frame.height > 0, app.windows.firstMatch.frame.contains(CGPoint(x: frame.midX, y: frame.midY)) {
            note("\(what): not reported tappable, tapped its centre instead")
            element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            return true
        }
        note("NOT TAPPABLE \(what): it is there but cannot be tapped (covered or off screen)")
        return false
    }

    /// Closes the keyboard with the search key, so the list below the field can be tapped.
    @MainActor
    private func closeKeyboard(_ app: XCUIApplication) {
        let key = app.keyboards.buttons["Search"]
        if key.exists { key.tap() } else if app.keyboards.buttons["search"].exists { app.keyboards.buttons["search"].tap() }
        if !StubServer.wait(5, until: { !app.keyboards.firstMatch.exists }) { note("The keyboard did not close after Search") }
    }

    /// Scrolls up to eight times until the element can be tapped.
    @MainActor
    private func reveal(_ app: XCUIApplication, _ target: XCUIElement) {
        for _ in 0..<8 where !(target.exists && target.isHittable) { app.swipeUp() }
    }

    /// Scrolls back down (towards the top) until the element can be tapped.
    @MainActor
    private func revealFromAbove(_ app: XCUIApplication, _ target: XCUIElement) {
        for _ in 0..<8 where !(target.exists && target.isHittable) { app.swipeDown() }
    }

    @MainActor
    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier]
    }

    @MainActor
    private func type(_ app: XCUIApplication, _ field: XCUIElement, _ text: String) {
        guard field.waitForExistence(timeout: 10) else {
            note("MISSING a field to type \(text) into")
            return
        }
        field.typeReliably(text)
    }

    @MainActor
    private func search(_ app: XCUIApplication, _ text: String) {
        type(app, app.searchFields.firstMatch, text)
    }

    @MainActor
    private func waitForPlants(_ app: XCUIApplication) {
        if !app.navigationBars["Plants"].waitForExistence(timeout: 20) { note("The app did not open on Plants") }
        _ = StubServer.wait(20) {
            app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'plant-'")).firstMatch.exists
                || app.buttons["sortMenu"].exists || app.staticTexts["emptyResults"].exists
        }
    }

    @MainActor
    private func openPlant(_ app: XCUIApplication, search query: String, id: String) {
        search(app, query)
        closeKeyboard(app)
        let row = element(app, "plant-\(id)")
        if tapOrCentre(app, row, "the row for \(id) after searching \(query)") {
            if !StubServer.wait(10, until: { app.buttons["whereToBuy"].exists || self.element(app, "noPhoto").exists
                || app.staticTexts["photoCounter"].exists }) {
                note("The plant page for \(id) did not open")
            }
        }
    }

    @MainActor
    private func openGardenTab(_ app: XCUIApplication) {
        let tabBar = app.tabBars.firstMatch
        if !tabBar.waitForExistence(timeout: 20) { note("No tab bar after launch") }
        tap(tabBar.buttons["Garden"], "the Garden tab")
        if !app.navigationBars["My garden"].waitForExistence(timeout: 10) { note("Garden did not open") }
    }

    @MainActor
    private func dragLeft(_ row: XCUIElement, by points: CGFloat) {
        let start = row.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5))
        start.press(forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: -points, dy: 0)),
                    withVelocity: .slow, thenHoldForDuration: 0.2)
    }

    // MARK: - the flows

    @MainActor
    private func firstOpenFlow(largest: Bool) {
        var app = launch(["-uiTestFirstOpen"], largest: largest)
        if !app.textFields["zipField"].waitForExistence(timeout: 20) { note("No ZIP field on the first-open screen") }
        shot(app, "first-open")
        audit(app, "first-open")
        type(app, app.textFields["zipField"], "22030")
        _ = app.staticTexts["Fairfax County, Virginia"].waitForExistence(timeout: 10)
        shot(app, "first-open-zip-found")

        app.terminate()
        app = launch(["-uiTestFirstOpen"], largest: largest)
        type(app, app.textFields["zipField"], "99501")
        _ = app.staticTexts["We only cover the lower 48 states for now."].waitForExistence(timeout: 10)
        shot(app, "first-open-outside")

        app.terminate()
        app = launch(["-uiTestFirstOpen"], largest: largest)
        if app.textFields["zipField"].waitForExistence(timeout: 20) {
            let link = app.buttons["Use a town or street address instead"]
            reveal(app, link)
            if tap(link, "the town link") {
                type(app, app.textFields["placeField"], "Dayton, OH\n")
                _ = app.staticTexts["Montgomery County, Ohio"].waitForExistence(timeout: 10)
                shot(app, "first-open-town")
            }
        }

        app.terminate()
        XCUIDevice.shared.location = XCUILocation(location: CLLocation(latitude: 38.94, longitude: -77.16))
        app = launch(["-uiTestFirstOpen"], largest: largest)
        if app.textFields["zipField"].waitForExistence(timeout: 20) {
            let link = app.buttons["Use my location"]
            reveal(app, link)
            if tap(link, "the use my location link") {
                if !app.staticTexts["Fairfax County, Virginia"].waitForExistence(timeout: 30) {
                    note("Use my location did not find Fairfax County")
                }
                shot(app, "first-open-my-location")
            }
        }
    }

    @MainActor
    private func plantsFlow(largest: Bool) {
        var app = launch(["-uiTestZip", "22030"], largest: largest)
        waitForPlants(app)
        shot(app, "plants-top")
        audit(app, "plants")
        app.swipeUp()
        app.swipeUp()
        shot(app, "plants-scrolled")
        for _ in 0..<8 where !app.buttons["sortMenu"].isHittable { app.swipeDown() }

        if tapOrCentre(app, app.buttons["sortMenu"], "the sort menu") {
            let tallest = app.buttons["Height (high to low)"]
            if !tallest.waitForExistence(timeout: 5) {
                // At the largest text the menu is taller than the screen; it scrolls.
                for _ in 0..<4 where !tallest.exists { app.swipeUp() }
            }
            shot(app, "plants-sort-menu")
            if tapOrCentre(app, tallest, "Height (high to low)") {
                shot(app, "plants-tallest")
            }
        }
        for _ in 0..<8 where !app.buttons["filtersChip"].isHittable { app.swipeDown() }
        if tapOrCentre(app, app.buttons["filtersChip"], "the Filters chip") {
            _ = app.buttons["showPlants"].waitForExistence(timeout: 5)
            shot(app, "plants-filters")
            if tapOrCentre(app, app.buttons["Full shade"], "Full shade") { shot(app, "plants-filters-shade") }
            if tapOrCentre(app, app.buttons["showPlants"], "the show plants button") { shot(app, "plants-filtered") }
        }

        app.terminate()
        app = launch(["-uiTestZip", "22030"], largest: largest)
        waitForPlants(app)
        search(app, "oak")
        shot(app, "plants-search")
        search(app, "zzzz")
        _ = app.staticTexts["emptyResults"].waitForExistence(timeout: 5)
        shot(app, "plants-no-results")

        app.terminate()
        app = launch(["-uiTestZip", "22030", "-uiTestOffline"], largest: largest)
        waitForPlants(app)
        _ = app.staticTexts["offlineBanner"].waitForExistence(timeout: 10)
        shot(app, "plants-no-signal")
    }

    @MainActor
    private func plantPageFlow(largest: Bool) {
        var app = launch(["-uiTestZip", "22030"], largest: largest)
        waitForPlants(app)
        openPlant(app, search: "Spicebush", id: "lindera-benzoin")
        _ = app.staticTexts["photoCounter"].waitForExistence(timeout: 10)
        shot(app, "plant-top")
        audit(app, "plant-page")
        let window = app.windows.firstMatch
        window.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.3))
            .press(forDuration: 0.05, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.3)))
        shot(app, "plant-photo-pager")
        app.swipeUp()
        app.swipeUp()
        shot(app, "plant-scrolled")
        for _ in 0..<4 { app.swipeUp() }
        shot(app, "plant-map")
        let buy = app.buttons["whereToBuy"]
        revealFromAbove(app, buy)
        if tap(buy, "the Where to buy button") {
            _ = app.buttons["whereToBuyDone"].waitForExistence(timeout: 10)
            shot(app, "where-to-buy-sellers")
        }

        app.terminate()
        app = launch(["-uiTestZip", "22030"], largest: largest)
        waitForPlants(app)
        openPlant(app, search: "Staghorn", id: "rhus-typhina")
        let none = app.buttons["whereToBuy"]
        reveal(app, none)
        if tap(none, "the Where to buy button on Staghorn Sumac") {
            _ = app.staticTexts["noSeller"].waitForExistence(timeout: 10)
            shot(app, "where-to-buy-none")
        }

        app.terminate()
        app = launch(["-uiTestZip", "22030"], largest: largest)
        waitForPlants(app)
        openPlant(app, search: "Lovegrass", id: "eragrostis-spectabilis")
        _ = element(app, "noPhoto").waitForExistence(timeout: 10)
        shot(app, "plant-no-photo")
    }

    @MainActor
    private func gardenFlow(largest: Bool) {
        let four = ["echinacea-purpurea", "asclepias-tuberosa", "lindera-benzoin", "quercus-alba"].joined(separator: ",")
        let now = ["-uiTestLocation", "51059", "-uiTestNow", "2027-11-18T09:00"]
        var app = launch(now + ["-uiTestSaved", four], largest: largest)
        openGardenTab(app)
        _ = element(app, "job-stems-tidy").waitForExistence(timeout: 10)
        shot(app, "garden-top")
        audit(app, "garden")
        let spicebush = element(app, "saved-lindera-benzoin")
        reveal(app, spicebush)
        shot(app, "garden-saved-spicebush")
        if spicebush.exists && spicebush.isHittable {
            dragLeft(spicebush, by: 120)
            _ = app.buttons["remove-lindera-benzoin"].waitForExistence(timeout: 5)
            shot(app, "garden-saved-swiped")
        } else {
            note("Spicebush's saved row could not be reached to swipe")
        }

        app.terminate()
        app = launch(now + ["-uiTestSaved", four], largest: largest)
        openGardenTab(app)
        let job = element(app, "job-stems-tidy")
        _ = job.waitForExistence(timeout: 10)
        revealFromAbove(app, job)
        if tap(job, "the stems job") {
            _ = app.buttons["markDone"].waitForExistence(timeout: 10)
            shot(app, "job-sheet")
            let source = app.buttons["source-0"]
            reveal(app, source)
            shot(app, "job-sheet-sources")
        }

        app.terminate()
        app = launch(now + ["-uiTestSaved", "echinacea-purpurea"], largest: largest)
        openGardenTab(app)
        for id in ["stems-tidy", "sow-cold-seed"] {
            let tick = app.buttons["tick-\(id)"]
            _ = tick.waitForExistence(timeout: 10)
            reveal(app, tick)
            if tick.exists && tick.isHittable { tick.tap() } else { note("The tick circle for \(id) could not be reached") }
        }
        revealFromAbove(app, app.buttons["tick-stems-tidy"])
        shot(app, "garden-done")

        app.terminate()
        app = launch(now, largest: largest)
        openGardenTab(app)
        _ = element(app, "gardenEmpty").waitForExistence(timeout: 10)
        shot(app, "garden-empty")
    }

    @MainActor
    private func settingsFlow(largest: Bool) {
        let app = launch(["-uiTestLocation", "51059"], largest: largest)
        let tabBar = app.tabBars.firstMatch
        if !tabBar.waitForExistence(timeout: 20) { note("No tab bar after launch") }
        tap(tabBar.buttons["Settings"], "the Settings tab")
        _ = app.navigationBars["Settings"].waitForExistence(timeout: 10)
        shot(app, "settings")
        audit(app, "settings")
        let credits = app.buttons["Photo credits and licenses"]
        reveal(app, credits)
        if tap(credits, "Photo credits and licenses") {
            _ = app.navigationBars["Photo credits and licenses"].waitForExistence(timeout: 10)
            shot(app, "photo-credits")
            for n in 2...4 {
                app.swipeUp()
                shot(app, "photo-credits-\(n)")
            }
            app.navigationBars.buttons.firstMatch.tap()
        }
        let change = app.buttons["changeLocation"]
        revealFromAbove(app, change)
        reveal(app, change)
        if tap(change, "the Change location button") {
            type(app, app.textFields["zipField"], "44101")
            _ = app.staticTexts["Cuyahoga County, Ohio"].waitForExistence(timeout: 10)
            shot(app, "change-location")
        }
    }

    /// The opening's first frame: held still at 0 ms.
    @MainActor
    private func openingFlow(largest: Bool) {
        let app = launch(["-uiTestZip", "22030", "-uiTestOpeningAt", "0"], largest: largest, skipOpening: false)
        if !app.descendants(matching: .any).matching(identifier: "opening").firstMatch.waitForExistence(timeout: 20) {
            note("The opening held at 0 ms is not on screen")
        }
        shot(app, "opening-first-frame")
    }

    // MARK: - the tests, one per area and size

    @MainActor func testFirstOpenNormal() { firstOpenFlow(largest: false) }
    @MainActor func testFirstOpenLargest() { firstOpenFlow(largest: true) }
    @MainActor func testPlantsNormal() { plantsFlow(largest: false) }
    @MainActor func testPlantsLargest() { plantsFlow(largest: true) }
    @MainActor func testPlantPageNormal() { plantPageFlow(largest: false) }
    @MainActor func testPlantPageLargest() { plantPageFlow(largest: true) }
    @MainActor func testGardenNormal() { gardenFlow(largest: false) }
    @MainActor func testGardenLargest() { gardenFlow(largest: true) }
    @MainActor func testSettingsNormal() { settingsFlow(largest: false) }
    @MainActor func testSettingsLargest() { settingsFlow(largest: true) }
    @MainActor func testOpeningNormal() { openingFlow(largest: false) }
    @MainActor func testOpeningLargest() { openingFlow(largest: true) }
}
