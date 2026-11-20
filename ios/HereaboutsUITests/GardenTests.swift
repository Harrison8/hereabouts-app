// The Garden tab, tapped through like a person: this week's jobs for the saved plants with their tick circles and the
// "N of M done" line, the job sheet (when, what to do, the sources, which open in the phone's browser), the map pin
// before the county, the saved plants with the red Remove button a swipe to the left shows (a long swipe removes the
// plant at once, pressing and holding opens no menu, and swiping on a plant page opened from here removes nothing),
// the empty state, and the Saturday reminders the app plans.
//
// The clock is set with -uiTestNow (local phone time), the saved plants with -uiTestSaved, and the phone's answer to
// the notification question with -uiTestNotifications, so no system alert appears. The app reports the reminders it
// has waiting to the stand-in website at /uitest/reminders after every change, and the test compares them with
// expected/care.json, the website's own answers. Events and links go to a stand-in server on 127.0.0.1 run by the
// test; nothing reaches an outside address.
import XCTest

final class GardenTests: XCTestCase {
    private var server: StubServer!

    override func setUpWithError() throws {
        continueAfterFailure = false
        server = try StubServer()
    }

    override func tearDown() {
        server.stop()
        server = nil
    }

    // MARK: - launching and reading the screen

    /// The app with Fairfax County chosen, the clock at `now` (local time, "YYYY-MM-DDTHH:mm") and `saved` already
    /// saved, then on the Garden tab.
    @MainActor
    private func launch(now: String? = nil, saved: [String] = [], notifications: String? = nil, textSize: String? = nil,
                        extra: [String] = [], openGarden: Bool = true) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestLocation", "51059", "-hereaboutsBaseURL", server.baseURL, "-uiTestCaptureLinks"]
        if let now { app.launchArguments += ["-uiTestNow", now] }
        if !saved.isEmpty { app.launchArguments += ["-uiTestSaved", saved.joined(separator: ",")] }
        if let notifications { app.launchArguments += ["-uiTestNotifications", notifications] }
        if let textSize { app.launchArguments += ["-UIPreferredContentSizeCategoryName", textSize] }
        app.launchArguments += extra
        app.launch()
        let tabBar = app.tabBars.firstMatch
        XCTAssertTrue(tabBar.waitForExistence(timeout: 20), "No tab bar after launch")
        if openGarden {
            tabBar.buttons["Garden"].tap()
            XCTAssertTrue(app.navigationBars["My garden"].waitForExistence(timeout: 10), "The Garden tab is not titled My garden")
        }
        return app
    }

    /// Any element with this identifier, whatever kind it is.
    @MainActor
    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier]
    }

    /// Scrolls up to eight times until the element can be tapped. At the largest text sizes it may start off screen.
    @MainActor
    private func reveal(_ app: XCUIApplication, _ target: XCUIElement) {
        for _ in 0..<8 where !(target.exists && target.isHittable) { app.swipeUp() }
    }

    /// Drags a saved plant's row to the left by `points`, from near its right edge, slowly, and holds a moment before
    /// letting go, so the row stops where the finger stopped instead of being flung.
    @MainActor
    private func dragLeft(_ row: XCUIElement, by points: CGFloat) {
        let start = row.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5))
        start.press(forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: -points, dy: 0)),
                    withVelocity: .slow, thenHoldForDuration: 0.2)
    }

    /// Drags a plant page's photo sideways, to the next photo, on the photo's own height (the top third of the screen).
    @MainActor
    private func swipePhotoLeft(_ app: XCUIApplication) {
        let window = app.windows.firstMatch
        window.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.3))
            .press(forDuration: 0.05, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.3)))
    }

    @MainActor
    private func attachScreenshot(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// The events of one name that reached the stand-in server, as their properties.
    private func posted(_ event: String) -> [[String: Any]] {
        server.postedEvents.compactMap { $0.event["event"] as? String == event ? $0.event["properties"] as? [String: Any] : nil }
    }

    /// The links the app would have opened in the browser, in order.
    private var openedLinks: [String] {
        server.received.filter { $0.method == "POST" && $0.path == "/uitest/opened" }.map { String(decoding: $0.body, as: UTF8.self) }
    }

    /// One waiting reminder as the app reports it: its identifier, title, body and time ("YYYY-MM-DDTHH:mm").
    private struct Waiting: Equatable, CustomStringConvertible {
        let id: String
        let title: String
        let body: String
        let at: String
        var description: String { "\(id) \(at) \(title): \(body.replacingOccurrences(of: "\n", with: " | "))" }
    }

    /// The reminders waiting on the phone after the app's latest change, or nil before its first report.
    private var latestWaiting: [Waiting]? {
        guard let last = server.received.last(where: { $0.method == "POST" && $0.path == "/uitest/reminders" }),
              let list = try? JSONSerialization.jsonObject(with: last.body) as? [[String: Any]] else { return nil }
        return list.map {
            Waiting(id: $0["id"] as? String ?? "", title: $0["title"] as? String ?? "", body: $0["body"] as? String ?? "",
                    at: $0["at"] as? String ?? "")
        }.sorted { $0.id < $1.id }
    }

    /// How many times the app has reported its waiting reminders: once after every time it planned them.
    private var reminderReports: Int {
        server.received.filter { $0.method == "POST" && $0.path == "/uitest/reminders" }.count
    }

    // MARK: - the website's answers

    /// The care.json case for a county, a time and a list of saved plants.
    private func careCase(county: String, now: String, saved: [String]) throws -> [Waiting] {
        guard let url = Bundle(for: GardenTests.self).url(forResource: "care", withExtension: "json") else {
            XCTFail("care.json could not be read: it is not in the test bundle")
            throw CocoaError(.fileNoSuchFile)
        }
        let file = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any],
                                 "care.json could not be read: not a JSON object")
        let cases = try XCTUnwrap(file["cases"] as? [[String: Any]], "care.json could not be read: no cases")
        let found = try XCTUnwrap(cases.first {
            $0["county"] as? String == county && $0["now"] as? String == now && $0["savedIds"] as? [String] == saved
        }, "care.json has no case for county \(county) at \(now) with \(saved)")
        let result = try XCTUnwrap(found["result"] as? [[String: Any]])
        return result.map {
            Waiting(id: "hereabouts.week.\(($0["id"] as? Int) ?? Int(($0["id"] as? Double) ?? 0))",
                    title: $0["title"] as? String ?? "", body: $0["body"] as? String ?? "", at: $0["at"] as? String ?? "")
        }.sorted { $0.id < $1.id }
    }

    // MARK: - tests

    /// With nothing saved, the Garden tab says so, and its button opens the Plants tab.
    @MainActor
    func testTheEmptyStateShowsWithNothingSavedAndOpensPlants() throws {
        let app = launch(notifications: "willAllow")
        let empty = app.staticTexts["gardenEmpty"]
        XCTAssertTrue(empty.waitForExistence(timeout: 10), "No empty state with nothing saved")
        XCTAssertEqual(empty.label, "No plants saved yet")
        let browse = app.buttons["browsePlants"]
        XCTAssertTrue(browse.exists, "The empty state has no Browse plants button")
        XCTAssertEqual(browse.label, "Browse plants")
        XCTAssertGreaterThanOrEqual(browse.frame.height, 44, "Browse plants is less than 44 points tall")
        // The phone has not asked about notifications yet, so the note says it will.
        XCTAssertTrue(app.staticTexts["The phone asks if Hereabouts may send reminders when you save your first plant."]
            .waitForExistence(timeout: 5), "The empty state does not say the phone will ask")
        // Not asked yet and the switch is on, so the empty state says when the reminder comes.
        let note = element(app, "gardenEmptyNote")
        XCTAssertTrue(StubServer.wait(5) { note.exists && note.label.contains("Each Saturday at 8:00") },
                      "The empty state does not say when the reminder comes: \(note.label)")
        attachScreenshot(app, "Garden, nothing saved")
        browse.tap()
        XCTAssertTrue(app.navigationBars["Plants"].waitForExistence(timeout: 10), "Browse plants did not open the Plants tab")
    }

    /// Purple coneflower saved in Fairfax County on a Thursday in November: This week lists the tidy-garden stems job
    /// (shown once with its pair) and the cold-winter seed job, so ticking one shows "1 of 2 done". The job's sheet
    /// says when to do it, what to do, and opens its sources in the browser.
    @MainActor
    func testThisWeekListsTheStemsJobAndTickingItCountsOneDone() throws {
        let app = launch(now: "2027-11-18T09:00", saved: ["echinacea-purpurea"])
        let row = element(app, "job-stems-tidy")
        XCTAssertTrue(row.waitForExistence(timeout: 10), "This week does not list the stems job")
        XCTAssertTrue(row.label.contains("Cut back dead stems for a tidy garden"), "The stems row reads \(row.label)")
        XCTAssertTrue(element(app, "job-sow-cold-seed").exists, "This week does not list the cold-winter seed job")
        XCTAssertFalse(element(app, "job-stems-pollinator").exists, "The pair shows twice")
        let count = app.staticTexts["doneCount"]
        XCTAssertTrue(count.exists, "No done count")
        XCTAssertEqual(count.label, "0 of 2 done")
        XCTAssertEqual(app.staticTexts["thisWeekDates"].label, "Nov 13 to 19")
        // This launch's stand-in says the person refused notifications before, so no reminder is promised.
        XCTAssertEqual(app.staticTexts["thisWeekFooter"].label, "From Fairfax County’s frost dates.")

        let tick = app.buttons["tick-stems-tidy"]
        XCTAssertTrue(tick.exists, "The stems job has no tick circle")
        XCTAssertGreaterThanOrEqual(tick.frame.width, 44, "The tick circle is less than 44 points wide")
        XCTAssertGreaterThanOrEqual(tick.frame.height, 44, "The tick circle is less than 44 points tall")
        XCTAssertEqual(tick.value as? String, "Not done")
        tick.tap()
        XCTAssertTrue(StubServer.wait(5) { count.label == "1 of 2 done" }, "Ticking the job shows \(count.label)")
        XCTAssertEqual(tick.value as? String, "Done")
        attachScreenshot(app, "Garden, one job done")

        // The job sheet.
        row.tap()
        let when = app.staticTexts["jobWhen-stems-tidy"]
        XCTAssertTrue(when.waitForExistence(timeout: 10), "The job sheet did not open")
        XCTAssertEqual(when.label, "Now until about April 14.")
        // The sheet opens at full height: its Done button sits in the top quarter of the screen, not halfway down.
        XCTAssertLessThan(app.buttons["jobSheetDone"].frame.minY, app.frame.height / 4,
                          "The job sheet did not open at full height")
        XCTAssertTrue(app.staticTexts["Last season's stems are still empty. Cutting them does not harm nesting bees."].exists,
                      "The sheet does not list what to do")
        XCTAssertTrue(app.staticTexts["Your Purple Coneflower"].exists, "The sheet does not name the saved plant")
        let markDone = app.buttons["markDone"]
        XCTAssertTrue(markDone.exists, "The sheet has no Mark as done button")
        XCTAssertGreaterThanOrEqual(markDone.frame.height, 48, "Mark as done is less than 48 points tall in the sheet")
        // The job was ticked above, so the sheet's button unticks it.
        XCTAssertEqual(markDone.label, "Mark as not done")
        markDone.tap()
        XCTAssertTrue(StubServer.wait(5) { markDone.label == "Mark as done" }, "Mark as not done reads \(markDone.label)")
        attachScreenshot(app, "Job sheet")
        // The pair's other job shows below the first, since either way will do.
        let other = app.staticTexts["Or: Cut stems later for a pollinator-friendly garden"]
        reveal(app, other)
        XCTAssertTrue(other.exists, "The sheet does not show the pair's other job")
        attachScreenshot(app, "Job sheet, the other way")
        let source = app.buttons["source-0"]
        reveal(app, source)
        XCTAssertTrue(source.exists, "The sheet lists no sources")
        attachScreenshot(app, "Job sheet, sources")
        source.tap()
        XCTAssertTrue(StubServer.wait(10) { !self.openedLinks.isEmpty }, "The source did not open in the browser")
        XCTAssertEqual(openedLinks.last,
                       "https://content.ces.ncsu.edu/garden-cleanup-for-pollinators-trim-perennial-stems-in-their-first-winter")
    }

    /// At the largest text size the jobs, their tick circles and the job sheet can all be reached.
    @MainActor
    func testAtTheLargestTextTheJobsAndTheSheetCanBeReached() throws {
        let app = launch(now: "2027-11-18T09:00", saved: ["echinacea-purpurea", "asclepias-tuberosa", "lindera-benzoin"],
                         textSize: "UICTContentSizeCategoryAccessibilityXXXL")
        let tick = app.buttons["tick-stems-tidy"]
        XCTAssertTrue(tick.waitForExistence(timeout: 10), "No stems job at the largest text")
        attachScreenshot(app, "Garden, largest text")
        reveal(app, tick)
        tick.tap()
        XCTAssertTrue(StubServer.wait(5) { tick.value as? String == "Done" }, "The tick circle cannot be ticked at the largest text")
        let saved = element(app, "saved-lindera-benzoin")
        reveal(app, saved)
        XCTAssertTrue(saved.exists, "The saved plants cannot be reached at the largest text")
        attachScreenshot(app, "Garden saved plants, largest text")
        for _ in 0..<8 where !element(app, "job-stems-tidy").isHittable { app.swipeDown() }
        element(app, "job-stems-tidy").tap()
        let markDone = app.buttons["markDone"]
        XCTAssertTrue(markDone.waitForExistence(timeout: 10), "The job sheet did not open at the largest text")
        attachScreenshot(app, "Job sheet, largest text")
    }

    /// With four plants saved in Fairfax County on Saturday October 2, 2027 at noon, the reminders waiting on the phone
    /// have the same ids, times, titles and bodies as the website's. Turning Saturday reminders off in Settings leaves
    /// none waiting.
    @MainActor
    func testTheRemindersMatchTheWebsitesAndTurningThemOffLeavesNone() throws {
        let saved = ["asclepias-tuberosa", "schizachyrium-scoparium", "quercus-alba", "lindera-benzoin"]
        let expected = try careCase(county: "51059", now: "2027-10-02T12:00", saved: saved)
        XCTAssertFalse(expected.isEmpty, "The care.json case has no reminders to compare")
        let app = launch(now: "2027-10-02T12:00", saved: saved, notifications: "allowed")
        XCTAssertTrue(StubServer.wait(20) { self.latestWaiting == expected },
                      "Waiting reminders differ from care.json.\nApp: \(latestWaiting ?? [])\nWebsite: \(expected)")
        XCTAssertTrue(element(app, "saved-quercus-alba").waitForExistence(timeout: 5), "The saved plants are not listed")
        // The map pin sits before the county under the title, as d1-my-garden.png draws it.
        let pin = element(app, "county-pin")
        XCTAssertTrue(pin.exists, "No map pin before the county")
        XCTAssertLessThanOrEqual(pin.frame.maxX, app.staticTexts["gardenCounty"].frame.minX + 1, "The map pin is not before the county")
        XCTAssertEqual(app.staticTexts["gardenCounty"].label, "Fairfax County, Virginia")
        // The phone allows notifications and the switch is on, so the line under This week says when they come.
        let footer = app.staticTexts["thisWeekFooter"]
        XCTAssertTrue(StubServer.wait(10) { footer.exists && footer.label.contains("A reminder comes every Saturday at 8:00.") },
                      "The line under This week does not say when the reminder comes: \(footer.label)")
        attachScreenshot(app, "Garden, four plants saved")

        // Leaving the app and coming back plans the reminders again, and they still match the website's.
        // Two seconds first, so the reports from launch have all arrived before counting.
        _ = StubServer.wait(2) { false }
        let reportsBefore = reminderReports
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(app.navigationBars["My garden"].waitForExistence(timeout: 20), "The app did not come back")
        XCTAssertTrue(StubServer.wait(15) { self.reminderReports > reportsBefore && self.latestWaiting == expected },
                      "Coming back to the app did not plan the reminders again")

        app.tabBars.firstMatch.buttons["Settings"].tap()
        let toggle = app.switches["Saturday reminders"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 10), "No Saturday reminders switch")
        XCTAssertEqual(toggle.value as? String, "1", "Saturday reminders start off")
        // The switch itself at the row's right end, not the middle of the row's label.
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        XCTAssertTrue(StubServer.wait(5) { toggle.value as? String == "0" }, "The switch did not turn off")
        XCTAssertTrue(StubServer.wait(15) { self.latestWaiting == [] },
                      "Turning reminders off left \(latestWaiting ?? []) waiting")
    }

    /// With plants saved, the reminders switch in Settings answers every tap, however often the person goes back to
    /// the Garden tab between taps.
    @MainActor
    func testTheRemindersSwitchAnswersEachTimeAfterTheGardenTab() throws {
        let app = launch(now: "2027-10-02T12:00", saved: ["asclepias-tuberosa", "lindera-benzoin"], notifications: "allowed")
        XCTAssertTrue(element(app, "saved-lindera-benzoin").waitForExistence(timeout: 10), "The saved plants are not listed")
        let tabs = app.tabBars.firstMatch
        for (round, want) in ["0", "1", "0"].enumerated() {
            tabs.buttons["Settings"].tap()
            let toggle = app.switches["Saturday reminders"]
            XCTAssertTrue(toggle.waitForExistence(timeout: 10), "No Saturday reminders switch")
            toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
            XCTAssertTrue(StubServer.wait(5) { toggle.value as? String == want },
                          "Tap \(round + 1) on the switch did nothing: it still reads \(toggle.value ?? "")")
            tabs.buttons["Garden"].tap()
            XCTAssertTrue(app.navigationBars["My garden"].waitForExistence(timeout: 10), "Could not go back to the Garden tab")
        }
    }

    /// Swiping a saved plant to the left shows a red Remove button and leaves the Garden tab open; tapping the button
    /// unsaves the plant, and the count of saved plants drops by one.
    @MainActor
    func testSwipingASavedPlantLeftShowsRemoveAndTappingItRemovesIt() throws {
        let app = launch(now: "2027-11-18T09:00", saved: ["lindera-benzoin", "asclepias-tuberosa"])
        let row = element(app, "saved-lindera-benzoin")
        XCTAssertTrue(row.waitForExistence(timeout: 10), "Spicebush is not listed")
        let count = element(app, "savedCount")
        XCTAssertTrue(count.exists, "The saved plants have no count")
        XCTAssertEqual(count.label, "2 plants")
        XCTAssertFalse(app.buttons["Remove"].exists, "A Remove button shows before any swipe")
        reveal(app, row)

        dragLeft(row, by: 120)
        let remove = app.buttons["remove-lindera-benzoin"]
        XCTAssertTrue(remove.waitForExistence(timeout: 5), "Swiping left showed no Remove button")
        XCTAssertEqual(remove.label, "Remove")
        XCTAssertTrue(remove.isHittable, "The Remove button is covered")
        XCTAssertGreaterThanOrEqual(remove.frame.width, 44, "The Remove button is narrower than 44 points")
        XCTAssertTrue(app.navigationBars["My garden"].exists, "Swiping opened the plant's page")
        attachScreenshot(app, "Saved plant swiped")

        remove.tap()
        XCTAssertTrue(StubServer.wait(10) { !row.exists }, "Remove did not unsave Spicebush")
        XCTAssertTrue(StubServer.wait(5) { count.label == "1 plant" }, "The count reads \(count.label) after Remove")
        XCTAssertTrue(element(app, "saved-asclepias-tuberosa").exists, "Remove took Butterfly Weed too")
    }

    /// Swiping through a plant's photos on its page, opened from the Garden tab, moves only the photos: both saved
    /// plants are still saved when the page closes, and no Remove button shows.
    @MainActor
    func testSwipingThePhotosOfAPlantOpenedFromTheGardenKeepsEveryPlantSaved() throws {
        let app = launch(now: "2027-11-18T09:00", saved: ["lindera-benzoin", "asclepias-tuberosa"])
        let row = element(app, "saved-lindera-benzoin")
        XCTAssertTrue(row.waitForExistence(timeout: 10), "Spicebush is not listed")
        let count = element(app, "savedCount")
        XCTAssertEqual(count.label, "2 plants")
        reveal(app, row)
        row.tap()
        XCTAssertTrue(app.navigationBars["Spicebush"].waitForExistence(timeout: 10), "Tapping Spicebush did not open its page")

        let counter = app.staticTexts["photoCounter"]
        XCTAssertTrue(counter.waitForExistence(timeout: 10), "Spicebush's page has no photo counter")
        XCTAssertEqual(counter.label, "In bloom \u{00B7} 1 of 3")
        swipePhotoLeft(app)
        XCTAssertTrue(StubServer.wait(5) { counter.label == "In fruit \u{00B7} 2 of 3" },
                      "Swiping did not show the fruit photo: \(counter.label)")
        swipePhotoLeft(app)
        XCTAssertTrue(StubServer.wait(5) { counter.label == "In fall \u{00B7} 3 of 3" },
                      "Swiping did not show the fall photo: \(counter.label)")

        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.navigationBars["My garden"].waitForExistence(timeout: 10), "Could not go back to the Garden tab")
        XCTAssertTrue(StubServer.wait(5) { count.label == "2 plants" }, "The count reads \(count.label) after swiping the photos")
        XCTAssertTrue(element(app, "saved-lindera-benzoin").exists, "Swiping the photos unsaved Spicebush")
        XCTAssertTrue(element(app, "saved-asclepias-tuberosa").exists, "Swiping the photos unsaved Butterfly Weed")
        XCTAssertFalse(app.buttons["Remove"].exists, "A Remove button shows after swiping the photos")
    }

    /// A saved plant that is not native to a newly chosen county stays saved and stays listed: purple coneflower grows
    /// wild in Fairfax County but not in Portland, Oregon. Pressing and holding it opens no menu; a long swipe to the
    /// left removes it.
    @MainActor
    func testChangingTheCountyKeepsASavedPlantThatIsNotNativeThere() throws {
        let app = launch(now: "2027-11-18T09:00", saved: ["echinacea-purpurea"])
        let row = element(app, "saved-echinacea-purpurea")
        XCTAssertTrue(row.waitForExistence(timeout: 10), "Purple coneflower is not listed in Fairfax County")

        app.tabBars.firstMatch.buttons["Settings"].tap()
        let change = app.buttons["changeLocation"]
        XCTAssertTrue(change.waitForExistence(timeout: 10), "No Change button in Settings")
        change.tap()
        let field = app.textFields["zipField"]
        XCTAssertTrue(field.waitForExistence(timeout: 10), "Change did not open the location screen")
        field.tap()
        field.typeText("97201")
        XCTAssertTrue(app.staticTexts["Multnomah County, Oregon"].waitForExistence(timeout: 10))
        app.buttons["continue"].tap()
        XCTAssertTrue(app.staticTexts["Multnomah County, Oregon"].waitForExistence(timeout: 10), "The county did not change")

        app.tabBars.firstMatch.buttons["Garden"].tap()
        XCTAssertTrue(app.navigationBars["My garden"].waitForExistence(timeout: 10))
        XCTAssertTrue(row.waitForExistence(timeout: 10), "Purple coneflower left the saved plants after the county changed")
        XCTAssertTrue(app.staticTexts["gardenCounty"].label.contains("Multnomah County, Oregon"),
                      "The Garden tab does not show the new county")
        attachScreenshot(app, "Garden after moving to Portland")

        // Pressing and holding opens no menu. A press this long may count as a tap and open the plant's page; if so,
        // go back.
        row.press(forDuration: 1.2)
        _ = StubServer.wait(1.5) { false }
        XCTAssertFalse(app.buttons["Remove"].exists, "Pressing and holding opened a menu with Remove")
        if !app.navigationBars["My garden"].exists {
            app.navigationBars.buttons.element(boundBy: 0).tap()
            XCTAssertTrue(app.navigationBars["My garden"].waitForExistence(timeout: 10), "Could not go back to the Garden tab")
        }

        dragLeft(row, by: row.frame.width * 0.85)
        XCTAssertTrue(app.staticTexts["gardenEmpty"].waitForExistence(timeout: 10), "A long swipe did not unsave the plant")
    }

    /// The first save with a county set asks the phone whether reminders may be sent, once, and sends the answer.
    @MainActor
    func testTheFirstSaveAsksForNotificationsOnceAndSendsTheAnswer() throws {
        let app = launch(notifications: "willAllow", openGarden: false)
        let saves = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'save-'"))
        XCTAssertTrue(saves.firstMatch.waitForExistence(timeout: 20), "No save button on the Plants tab")
        XCTAssertTrue(posted("notifications_answer").isEmpty, "The question was asked before anything was saved")
        saves.element(boundBy: 0).tap()
        XCTAssertTrue(StubServer.wait(20) { !self.posted("notifications_answer").isEmpty }, "No notifications_answer event")
        let answer = try XCTUnwrap(posted("notifications_answer").first)
        XCTAssertEqual(answer["granted"] as? Bool, true)
        saves.element(boundBy: 1).tap()
        // Time for a second answer to arrive, if one were sent.
        _ = StubServer.wait(5) { self.posted("notifications_answer").count > 1 }
        XCTAssertEqual(posted("notifications_answer").count, 1, "The question was asked twice")
    }

    /// When the person refuses notifications at the first save, the Garden tab promises no Saturday reminder: not
    /// under This week, and not in the empty state once the plant is removed again.
    @MainActor
    func testWhenThePhoneRefusesNotificationsNoReminderIsPromised() throws {
        let app = launch(now: "2027-11-18T09:00", notifications: "willDeny", openGarden: false)
        let first = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'save-'")).firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 20), "No save button on the Plants tab")
        let plantId = String(first.identifier.dropFirst("save-".count))
        first.tap()
        XCTAssertTrue(StubServer.wait(20) { !self.posted("notifications_answer").isEmpty }, "No notifications_answer event")
        XCTAssertEqual(posted("notifications_answer").first?["granted"] as? Bool, false)

        app.tabBars.firstMatch.buttons["Garden"].tap()
        let footer = app.staticTexts["thisWeekFooter"]
        XCTAssertTrue(footer.waitForExistence(timeout: 10), "No line under This week after saving \(plantId)")
        XCTAssertTrue(StubServer.wait(5) { footer.label == "From Fairfax County’s frost dates." },
                      "With notifications refused the line under This week reads: \(footer.label)")
        attachScreenshot(app, "Garden, notifications refused")

        let row = element(app, "saved-\(plantId)")
        reveal(app, row)
        dragLeft(row, by: 120)
        let remove = app.buttons["remove-\(plantId)"]
        XCTAssertTrue(remove.waitForExistence(timeout: 5), "Swiping left showed no Remove button")
        remove.tap()
        let note = element(app, "gardenEmptyNote")
        XCTAssertTrue(note.waitForExistence(timeout: 10), "Remove did not unsave the plant")
        XCTAssertTrue(StubServer.wait(5) { note.label == "Tap the save button on any plant to save it here." },
                      "With notifications refused the empty state reads: \(note.label)")
        XCTAssertFalse(app.staticTexts["The phone asks if Hereabouts may send reminders when you save your first plant."].exists,
                       "The empty state says the phone will ask, after it already did")
        attachScreenshot(app, "Garden, nothing saved, notifications refused")
    }

    /// Opening the app from a reminder shows the Garden tab and sends this_week_opened from the notification.
    @MainActor
    func testOpeningAReminderShowsTheGardenAndSendsTheEvent() throws {
        let app = launch(saved: ["echinacea-purpurea"], extra: ["-uiTestNotificationTap"], openGarden: false)
        XCTAssertTrue(app.navigationBars["My garden"].waitForExistence(timeout: 20), "A reminder did not open the Garden tab")
        XCTAssertTrue(StubServer.wait(20) { !self.posted("this_week_opened").isEmpty }, "No this_week_opened event")
        XCTAssertEqual(posted("this_week_opened").first?["from"] as? String, "notification")
    }
}
