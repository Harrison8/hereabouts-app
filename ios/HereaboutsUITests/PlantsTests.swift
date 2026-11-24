// The Plants tab, tapped through like a person, compared with the website's own answers in expected/list.json (made by
// running the website's code, and copied into this test bundle with data/plants.json for the plant names):
// Fairfax County lists the same plants in the same order, a search for "oak" shows the same plants and the same
// "Also matching" lines, the height sort and the shade filter give the website's order, the save button saves without
// opening the plant, the county has its map pin, heights read "1.5 to 2.5 ft", and with no signal the banner shows
// with its cloud. Each launch is set up as if the ZIP code had been typed, so
// the position used for nearby shops matches the expected answers.
// Events go to a stand-in server on 127.0.0.1 run by the test; nothing reaches an outside address.
import XCTest

final class PlantsTests: XCTestCase {
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

    /// The app with the county of `zip` chosen, as if the ZIP code had been typed on the first-open screen.
    @MainActor
    private func launch(zip: String = "22030", offline: Bool = false, textSize: String? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestSkipOpening", "-uiTestZip", zip, "-hereaboutsBaseURL", server.baseURL]
        if offline { app.launchArguments.append("-uiTestOffline") }
        if let textSize { app.launchArguments += ["-UIPreferredContentSizeCategoryName", textSize] }
        app.launch()
        XCTAssertTrue(app.navigationBars["Plants"].waitForExistence(timeout: 20), "The app did not open on Plants")
        // At the largest text sizes the heading, search and chips can fill the screen, and the list only builds the rows
        // near it, so the sort menu just above the rows also counts as the list being there.
        XCTAssertTrue(StubServer.wait(20) {
            self.plantRows(app).firstMatch.exists || app.buttons["sortMenu"].exists
                || app.staticTexts["alsoCounty"].exists || app.staticTexts["emptyResults"].exists
        }, "The Plants tab shows no plant rows")
        return app
    }

    /// Every plant row on the screen. Each row is marked "plant-<id>", whatever kind of element it is.
    @MainActor
    private func plantRows(_ app: XCUIApplication) -> XCUIElementQuery {
        app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'plant-'"))
    }

    /// The plant ids of the first `count` rows, top to bottom. The list only builds the rows near the screen, so this
    /// scrolls down until it has seen enough, or the end of the list.
    @MainActor
    private func shownIds(_ app: XCUIApplication, count: Int) -> [String] {
        var ids: [String] = []
        var unchanged = 0
        for _ in 0..<15 {
            let before = ids.count
            for identifier in plantRows(app).allElementsBoundByIndex.map(\.identifier) {
                let id = String(identifier.dropFirst("plant-".count))
                if !ids.contains(id) { ids.append(id) }
            }
            if ids.count >= count { break }
            unchanged = ids.count == before ? unchanged + 1 : 0
            if unchanged >= 2 { break }
            app.swipeUp()
        }
        return Array(ids.prefix(count))
    }

    /// Back to the top of the list after `shownIds` scrolled it.
    @MainActor
    private func scrollToTop(_ app: XCUIApplication) {
        for _ in 0..<8 where !app.buttons["sortMenu"].isHittable { app.swipeDown() }
    }

    /// Types into the search field once it has the keyboard. A tap that lands while the list is still settling can
    /// leave the field without it, so a second tap follows if the first did not take.
    @MainActor
    private func search(_ app: XCUIApplication, _ text: String) {
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), "No search field")
        field.typeReliably(text)
    }

    @MainActor
    private func attachScreenshot(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// The events of one name that reached the stand-in server, as their properties.
    private func posted(_ event: String, since start: Int = 0) -> [[String: Any]] {
        server.postedEvents.dropFirst(start).compactMap { $0.event["event"] as? String == event ? $0.event["properties"] as? [String: Any] : nil }
    }

    // MARK: - the website's answers

    /// A file this test bundle carries, parsed. Fails with "could not be read" (not a wrong-order message) when the
    /// file is missing, so a packaging problem is not mistaken for a wrong answer.
    private func bundled(_ name: String) throws -> Any {
        guard let url = Bundle(for: PlantsTests.self).url(forResource: name, withExtension: "json") else {
            XCTFail("\(name).json could not be read: it is not in the test bundle")
            throw CocoaError(.fileNoSuchFile)
        }
        do {
            return try JSONSerialization.jsonObject(with: Data(contentsOf: url))
        } catch {
            XCTFail("\(name).json could not be read: \(error)")
            throw error
        }
    }

    /// The list.json case for a county with no filter: the given sort and search, every set empty, any height.
    private func listCase(county: String, sort: String, search: String = "", extra: [String: [Any]] = [:]) throws -> [String: Any] {
        let file = try XCTUnwrap(try bundled("list") as? [String: Any], "list.json could not be read: not a JSON object")
        let cases = try XCTUnwrap(file["cases"] as? [[String: Any]], "list.json could not be read: no cases")
        let found = cases.first { c in
            guard let loc = c["location"] as? [String: Any], loc["countyFips"] as? String == county,
                  let st = c["state"] as? [String: Any], st["sort"] as? String == sort, st["search"] as? String == search,
                  (st["maxHeight"] as? Double ?? 0) == 100 else { return false }
            for field in ["sun", "moisture", "category", "wildlife", "zones", "bloomMonth", "bloomColor", "buy"] {
                let have = (st[field] as? [Any]) ?? []
                let want = extra[field] ?? []
                if have.map({ "\($0)" }) != want.map({ "\($0)" }) { return false }
            }
            return true
        }
        return try XCTUnwrap(found, "list.json has no case for county \(county), sort \(sort), search \"\(search)\", \(extra)")
    }

    private func ids(_ c: [String: Any]) -> [String] { c["ids"] as? [String] ?? [] }

    /// Plant id to common name, from data/plants.json.
    private func names() throws -> [String: String] {
        let plants = try XCTUnwrap(try bundled("plants") as? [[String: Any]], "plants.json could not be read")
        var out: [String: String] = [:]
        for p in plants { if let id = p["id"] as? String { out[id] = p["common"] as? String ?? "" } }
        return out
    }

    // MARK: - the tests

    /// Fairfax County shows the website's first ten plants in its order, and results_shown is sent once with the count.
    @MainActor
    func testFairfaxListsTheWebsitesPlantsInItsOrder() throws {
        let expected = try listCase(county: "51059", sort: "suggested")
        let app = launch()
        attachScreenshot(app, "Plants, Fairfax County")
        XCTAssertTrue(app.buttons["plantsCounty"].exists, "The county under the title is not there")
        XCTAssertEqual(app.buttons["plantsCounty"].label, "Fairfax County, Virginia")
        let pin = app.descendants(matching: .any)["county-pin"]
        XCTAssertTrue(pin.exists, "No map pin before the county")
        XCTAssertLessThanOrEqual(pin.frame.maxX, app.buttons["plantsCounty"].frame.minX + 1, "The map pin is not before the county")
        XCTAssertFalse(app.staticTexts["offlineBanner"].exists, "The no-signal banner shows with signal")
        XCTAssertEqual(shownIds(app, count: 10), Array(ids(expected).prefix(10)))

        XCTAssertTrue(StubServer.wait(30) { !self.posted("results_shown").isEmpty }, "No results_shown event reached the server")
        RunLoop.current.run(until: Date().addingTimeInterval(2))
        let shown = posted("results_shown")
        XCTAssertEqual(shown.count, 1, "results_shown should be sent once for the county")
        XCTAssertEqual(shown.first?["count"] as? Double, Double(ids(expected).count))
        XCTAssertEqual(shown.first?["ecoregion"] as? String, "45")
        XCTAssertTrue(posted("empty_results").isEmpty, "empty_results was sent for a county with plants")
    }

    /// A search for "oak" shows the website's plants, and its "Also matching" lines when the website has them:
    /// Louisville has three oaks, Portland, Oregon has none native but three in its "not native here" line.
    @MainActor
    func testSearchingOakShowsTheWebsitesOaksAndAlsoMatching() throws {
        let names = try names()
        for (zip, county) in [("40202", "21111"), ("97201", "41051")] {
            let expected = try listCase(county: county, sort: "suggested", search: "oak")
            // Only this launch's events count: the server keeps the ones the last ZIP code's launch sent.
            let start = server.postedEvents.count
            let app = launch(zip: zip)
            search(app, "oak")
            let want = ids(expected)
            let settled = StubServer.wait(10) { self.plantRows(app).count == want.count }
            XCTAssertTrue(settled, "\(zip): \(plantRows(app).count) rows after searching oak, expected \(want.count)")
            XCTAssertEqual(shownIds(app, count: 50), want, "\(zip): the oak results differ from the website's")

            let also = try XCTUnwrap(expected["also"] as? [String: Any])
            let countyAlso = (also["county"] as? [String]) ?? []
            let filtersAlso = (also["filters"] as? [String]) ?? []
            let loc = try XCTUnwrap(expected["location"] as? [String: Any])
            let place = "\(loc["countyName"] as? String ?? ""), \(loc["stateName"] as? String ?? "")"
            if countyAlso.isEmpty {
                XCTAssertFalse(app.staticTexts["alsoCounty"].exists, "\(zip): an \"Also matching\" line the website does not have")
            } else {
                let line = app.staticTexts["alsoCounty"]
                XCTAssertTrue(line.waitForExistence(timeout: 5), "\(zip): no \"Also matching, but not native\" line")
                XCTAssertEqual(line.label, "Also matching, but not native to \(place): "
                               + countyAlso.prefix(12).map { names[$0] ?? $0 }.joined(separator: ", "))
            }
            XCTAssertEqual(app.staticTexts["alsoFilters"].exists, !filtersAlso.isEmpty, "\(zip): the hidden-by-filters line")
            // The website hides its empty-results text when the search has matches elsewhere.
            XCTAssertEqual(app.staticTexts["emptyResults"].exists, want.isEmpty && countyAlso.isEmpty && filtersAlso.isEmpty)
            attachScreenshot(app, "Search oak, \(zip)")

            // plant_search: the two counts only, never the typed text.
            XCTAssertTrue(StubServer.wait(15) { !self.posted("plant_search", since: start).isEmpty },
                          "\(zip): no plant_search event")
            let search = try XCTUnwrap(posted("plant_search", since: start).last)
            XCTAssertEqual(search["count"] as? Double, Double(want.count))
            XCTAssertEqual(search["hidden_count"] as? Double, Double(countyAlso.count + filtersAlso.count))
            let leftovers = Set(search.keys).subtracting(["count", "hidden_count", "platform", "distinct_id",
                                                          "$process_person_profile"])
            XCTAssertTrue(leftovers.isEmpty, "plant_search carries \(leftovers)")
            for (_, event) in server.postedEvents {
                let text = String(data: try JSONSerialization.data(withJSONObject: event), encoding: .utf8) ?? ""
                XCTAssertFalse(text.lowercased().contains("\"oak\""), "An event carries the search text: \(text)")
            }
            app.terminate()
            server.serveNothing()
        }
    }

    /// Choosing "Height (high to low)" (the website's tallest-first sort) puts the plants in the website's order for it.
    @MainActor
    func testTallestFirstReordersAsTheWebsiteDoes() throws {
        let suggested = try listCase(county: "51059", sort: "suggested")
        let tallest = try listCase(county: "51059", sort: "height-desc")
        XCTAssertNotEqual(Array(ids(suggested).prefix(10)), Array(ids(tallest).prefix(10)), "The two sorts start the same")
        let app = launch()
        let menu = app.buttons["sortMenu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 10), "No sort menu")
        menu.tap()
        let choice = app.buttons["Height (high to low)"]
        XCTAssertTrue(choice.waitForExistence(timeout: 5), "The sort menu has no \"Height (high to low)\"")
        attachScreenshot(app, "Sort menu")
        choice.tap()
        XCTAssertTrue(StubServer.wait(5) { "\(menu.value as? String ?? "") \(menu.label)".contains("Height (high to low)") },
                      "The sort menu does not show the chosen sort")
        XCTAssertEqual(shownIds(app, count: 10), Array(ids(tallest).prefix(10)))
        attachScreenshot(app, "Tallest first")
    }

    /// The save button saves and unsaves without opening the plant, and sends plant_saved and plant_unsaved. Tapping
    /// the rest of the row does open the plant.
    @MainActor
    func testTheSaveButtonTogglesWithoutOpeningThePlant() throws {
        let expected = try listCase(county: "51059", sort: "suggested")
        let first = try XCTUnwrap(ids(expected).first)
        let name = try XCTUnwrap(try names()[first])
        let app = launch()
        let save = app.buttons["save-\(first)"]
        XCTAssertTrue(save.waitForExistence(timeout: 10), "The first row has no save button")
        XCTAssertEqual(save.value as? String, "Not saved")
        XCTAssertEqual(save.label, "Save \(name)")
        XCTAssertGreaterThanOrEqual(save.frame.width, 44, "The save button is smaller than 44 points")
        XCTAssertGreaterThanOrEqual(save.frame.height, 44, "The save button is smaller than 44 points")

        save.tap()
        XCTAssertTrue(StubServer.wait(5) { save.value as? String == "Saved" }, "The save button did not save")
        XCTAssertTrue(app.navigationBars["Plants"].exists, "Saving opened the plant")
        attachScreenshot(app, "Saved")
        XCTAssertTrue(StubServer.wait(20) { !self.posted("plant_saved").isEmpty }, "No plant_saved event")

        save.tap()
        XCTAssertTrue(StubServer.wait(5) { save.value as? String == "Not saved" }, "The save button did not unsave")
        XCTAssertTrue(app.navigationBars["Plants"].exists, "Unsaving opened the plant")
        XCTAssertTrue(StubServer.wait(20) { !self.posted("plant_unsaved").isEmpty }, "No plant_unsaved event")

        // The rest of the row opens the plant.
        app.descendants(matching: .any)["plant-\(first)"].tap()
        XCTAssertTrue(app.navigationBars[name].waitForExistence(timeout: 5), "Tapping the row did not open the plant")
    }

    /// The Filters chip opens the panel; choosing "Full shade" makes its button count the website's shade list, and the
    /// list then shows the website's shade plants.
    @MainActor
    func testTheFiltersPanelCountsAndFiltersAsTheWebsiteDoes() throws {
        let shade = try listCase(county: "51059", sort: "suggested", extra: ["sun": ["shade"]])
        let app = launch()
        app.buttons["filtersChip"].tap()
        let show = app.buttons["showPlants"]
        XCTAssertTrue(show.waitForExistence(timeout: 5), "The filters panel did not open")
        attachScreenshot(app, "Filters panel")
        app.buttons["Full shade"].tap()
        let count = ids(shade).count
        XCTAssertTrue(StubServer.wait(5) { show.label == "Show \(count) plant\(count == 1 ? "" : "s")" },
                      "The button says \(show.label), expected Show \(count) plants")
        show.tap()
        XCTAssertTrue(StubServer.wait(5) { !show.exists }, "The panel did not close")
        XCTAssertEqual(shownIds(app, count: 10), Array(ids(shade).prefix(10)))
        attachScreenshot(app, "Full shade")
    }

    /// A chosen type chip stays on screen and chosen when a search leaves none of that type, so there is still a chip
    /// to turn off; and the Filters chip shows as chosen, with how many choices are on, once a panel filter is on.
    @MainActor
    func testChosenChipsStayVisibleAndFiltersShowsWhenOn() throws {
        let app = launch()
        // The Filters chip: plain with nothing chosen in the panel, chosen with its count once "Full shade" is on.
        let filters = app.buttons["filtersChip"]
        XCTAssertTrue(filters.waitForExistence(timeout: 10), "No Filters chip")
        XCTAssertFalse(filters.isSelected, "The Filters chip shows as chosen with nothing chosen in the panel")
        filters.tap()
        let show = app.buttons["showPlants"]
        XCTAssertTrue(show.waitForExistence(timeout: 5), "The filters panel did not open")
        app.buttons["Full shade"].tap()
        show.tap()
        XCTAssertTrue(StubServer.wait(5) { !show.exists }, "The panel did not close")
        XCTAssertTrue(StubServer.wait(5) { filters.isSelected }, "The Filters chip is not shown as chosen")
        XCTAssertEqual(filters.value as? String, "1 chosen")
        // 0.01 point tolerance: iOS 17.5 gave 43.99999999999997.
        XCTAssertGreaterThanOrEqual(filters.frame.height, 44 - 0.01, "The Filters chip is smaller than 44 points")
        attachScreenshot(app, "Filters chip on")

        // Fairfax has three full-shade trees. Choose Trees, then search a name no tree has: the Trees chip stays, chosen,
        // counting 0. (Turned off at 0 it would go, which is right, so the test ends here.)
        let trees = app.buttons["chip-tree"]
        XCTAssertTrue(trees.waitForExistence(timeout: 10), "No Trees chip")
        trees.tap()
        XCTAssertTrue(StubServer.wait(5) { trees.isSelected }, "The Trees chip is not shown as chosen")
        search(app, "milkweed")
        XCTAssertTrue(StubServer.wait(10) { self.plantRows(app).count == 0 }, "Trees and \"milkweed\" still show plants")
        XCTAssertTrue(trees.exists, "The chosen Trees chip vanished when no trees matched")
        XCTAssertTrue(trees.isSelected, "The Trees chip is no longer shown as chosen")
        XCTAssertEqual(trees.value as? String, "0", "The Trees chip should count 0 trees")
        XCTAssertTrue(filters.isSelected, "The Filters chip lost its chosen look")
        attachScreenshot(app, "Trees chosen, search milkweed")
    }

    /// With no signal the banner shows above the chips, and the list still works.
    @MainActor
    func testNoSignalShowsTheBanner() throws {
        let app = launch(offline: true)
        let banner = app.staticTexts["offlineBanner"]
        XCTAssertTrue(banner.waitForExistence(timeout: 10), "No banner with no signal")
        XCTAssertEqual(banner.label, "No signal. Seller links open once you have signal again.")
        let icon = app.descendants(matching: .any)["no-signal-icon"]
        XCTAssertTrue(icon.exists, "No no-signal icon at the start of the banner")
        XCTAssertLessThanOrEqual(icon.frame.maxX, banner.frame.minX + 1, "The no-signal icon is not before the words")
        XCTAssertGreaterThan(plantRows(app).count, 2, "The list is empty with no signal")
        attachScreenshot(app, "Plants, no signal")
    }

    /// A height in the list reads with "to", not a dash: Butterfly Weed is "1.5 to 2.5 ft", and no row on screen
    /// shows an en dash.
    @MainActor
    func testHeightsInTheListReadTo() throws {
        let app = launch()
        XCTAssertGreaterThan(plantRows(app).count, 2, "The list is empty")
        for label in plantRows(app).allElementsBoundByIndex.map(\.label) {
            XCTAssertFalse(label.contains("\u{2013}"), "A row shows an en dash: \(label)")
        }
        search(app, "Butterfly Weed")
        let row = app.descendants(matching: .any)["plant-asclepias-tuberosa"]
        XCTAssertTrue(row.waitForExistence(timeout: 10), "No row for Butterfly Weed")
        XCTAssertTrue(row.label.hasSuffix("1.5 to 2.5 ft"), "Butterfly Weed's row reads: \(row.label)")
    }

    /// At the largest text size every row still has its save button in reach, and the panel opens.
    @MainActor
    func testTheLargestTextSize() throws {
        let expected = try listCase(county: "51059", sort: "suggested")
        let app = launch(textSize: "UICTContentSizeCategoryAccessibilityXXXL")
        attachScreenshot(app, "Plants, largest text")
        let first = try XCTUnwrap(ids(expected).first)
        let save = app.buttons["save-\(first)"]
        for _ in 0..<4 where !save.isHittable { app.swipeUp() }
        XCTAssertTrue(save.isHittable, "The first row's save button cannot be reached at the largest text size")
        attachScreenshot(app, "Plants, largest text, first row")
        // Drag up a little further, so the first row's name, Latin name, type and height are on screen above the tab bar.
        let window = app.windows.firstMatch
        window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.7))
            .press(forDuration: 0.05, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)))
        attachScreenshot(app, "Plants, largest text, first row words")
        scrollToTop(app)
        let filters = app.buttons["filtersChip"]
        for _ in 0..<4 where !filters.isHittable { app.swipeDown() }
        filters.tap()
        XCTAssertTrue(app.buttons["showPlants"].waitForExistence(timeout: 5), "The filters panel did not open")
        attachScreenshot(app, "Filters panel, largest text")
    }
}
