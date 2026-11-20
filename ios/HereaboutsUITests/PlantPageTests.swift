// The plant page and its "Where to buy" panel, tapped through like a person and compared with the website's own
// answers in expected/sellers.json (made by running the website's seller code with the app's link marker):
// Spicebush in Fairfax County swipes through its three photos with the season and "1 of 3"; the panel lists the
// website's sellers in the website's order; tapping Garden for Wildlife hands the phone's browser the website's link,
// ending in the app's "__app" marker; a nearby shop's Directions button hands Apple Maps the same place as the
// website's directions link; and a plant no seller sends to Virginia shows the website's no-seller line.
// Links are not really opened: the Debug-only `-uiTestCaptureLinks` switch makes the app post each link it would open
// to the stand-in server on 127.0.0.1 instead, where the test reads it. Nothing reaches an outside address.
import XCTest

final class PlantPageTests: XCTestCase {
    private var server: StubServer!

    override func setUpWithError() throws {
        continueAfterFailure = false
        server = try StubServer()
    }

    override func tearDown() {
        server.stop()
        server = nil
    }

    // MARK: - launching and opening a plant

    /// The app with the county of `zip` chosen, as if the ZIP code had been typed, with links captured.
    @MainActor
    private func launch(zip: String = "22030", textSize: String? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestZip", zip, "-hereaboutsBaseURL", server.baseURL, "-uiTestCaptureLinks"]
        if let textSize { app.launchArguments += ["-UIPreferredContentSizeCategoryName", textSize] }
        app.launch()
        XCTAssertTrue(app.navigationBars["Plants"].waitForExistence(timeout: 20), "The app did not open on Plants")
        return app
    }

    /// Searches for `search` and opens the plant `id` from the list; its page has the plant's name as its title.
    @MainActor
    private func openPlant(_ app: XCUIApplication, search: String, id: String, name: String) {
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), "No search field")
        field.tap()
        field.typeText(search)
        let row = app.descendants(matching: .any)["plant-\(id)"]
        XCTAssertTrue(row.waitForExistence(timeout: 10), "No row for \(name) after searching \"\(search)\"")
        row.tap()
        XCTAssertTrue(app.navigationBars[name].waitForExistence(timeout: 10), "Tapping the row did not open \(name)")
    }

    /// Opens the "Where to buy" panel from the button under the plant's name.
    @MainActor
    private func openWhereToBuy(_ app: XCUIApplication) {
        let button = app.buttons["whereToBuy"]
        XCTAssertTrue(button.waitForExistence(timeout: 10), "The plant page has no Where to buy button")
        button.tap()
        XCTAssertTrue(app.buttons["whereToBuyDone"].waitForExistence(timeout: 10), "The Where to buy panel did not open")
    }

    /// Every seller row in the panel, nearby shops and shipping sellers alike, each marked "seller".
    @MainActor
    private func sellerRows(_ app: XCUIApplication) -> XCUIElementQuery {
        app.descendants(matching: .any).matching(identifier: "seller")
    }

    /// The seller names in the panel, top to bottom, scrolling the panel if it holds more than fit.
    @MainActor
    private func shownSellers(_ app: XCUIApplication, count: Int) -> [String] {
        var names: [String] = []
        for _ in 0..<6 {
            for label in sellerRows(app).allElementsBoundByIndex.map(\.label) where !names.contains(label) {
                names.append(label)
            }
            if names.count >= count { break }
            app.buttons["whereToBuyDone"].swipeUp()
        }
        return names
    }

    /// Drags the photo sideways, to the next photo, on the photo's own height (the top third of the screen).
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

    /// The links the app would have opened, oldest first.
    private var openedLinks: [String] {
        server.received.filter { $0.method == "POST" && $0.path == "/uitest/opened" }
            .map { String(data: $0.body, encoding: .utf8) ?? "" }
    }

    // MARK: - the website's answers

    /// The sellers.json view for one plant in one county, as the app's channel makes it.
    private func sellersCase(plant: String, county: String) throws -> [String: Any] {
        guard let url = Bundle(for: PlantPageTests.self).url(forResource: "sellers", withExtension: "json") else {
            XCTFail("sellers.json could not be read: it is not in the test bundle")
            throw CocoaError(.fileNoSuchFile)
        }
        let file = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any],
                                 "sellers.json could not be read: not a JSON object")
        let cases = try XCTUnwrap(file["cases"] as? [[String: Any]], "sellers.json could not be read: no cases")
        let found = cases.first { c in
            c["channel"] as? String == "app" && c["plant"] as? String == plant
                && (c["location"] as? [String: Any])?["countyFips"] as? String == county
        }
        let view = try XCTUnwrap(found?["view"] as? [String: Any], "sellers.json has no app case for \(plant) in \(county)")
        return view
    }

    private func rows(_ view: [String: Any]) -> [[String: Any]] { view["rows"] as? [[String: Any]] ?? [] }

    // MARK: - the tests

    /// Spicebush has three photos, bloom, fruit and fall; the counter names the season and counts them, and swiping
    /// moves through them. The page shows the chart, the map and the USDA link further down.
    @MainActor
    func testSpicebushShowsThreePhotosAndTheCounter() throws {
        let app = launch()
        openPlant(app, search: "Spicebush", id: "lindera-benzoin", name: "Spicebush")
        let counter = app.staticTexts["photoCounter"]
        XCTAssertTrue(counter.waitForExistence(timeout: 10), "The photo has no counter")
        XCTAssertEqual(counter.label, "In bloom \u{00B7} 1 of 3")
        XCTAssertFalse(app.descendants(matching: .any)["noPhoto"].exists, "Spicebush shows the no-photograph plate")
        let height = app.descendants(matching: .any)["fact-Height"]
        XCTAssertTrue(height.waitForExistence(timeout: 5), "The plant page has no Height fact")
        XCTAssertTrue(height.label.contains("6 to 12 ft"), "Spicebush's height reads: \(height.label)")
        attachScreenshot(app, "plant-page-top")

        swipePhotoLeft(app)
        XCTAssertTrue(StubServer.wait(5) { counter.label == "In fruit \u{00B7} 2 of 3" }, "Swiping did not show the fruit photo: \(counter.label)")
        swipePhotoLeft(app)
        XCTAssertTrue(StubServer.wait(5) { counter.label == "In fall \u{00B7} 3 of 3" }, "Swiping did not show the fall photo: \(counter.label)")

        XCTAssertTrue(app.staticTexts["Shrub \u{00B7} Native to Fairfax County, Virginia"].exists, "No type and native line")
        let save = app.buttons["pageSave"]
        XCTAssertTrue(save.exists, "The plant page has no Save button")
        XCTAssertGreaterThanOrEqual(save.frame.height, 44, "The Save button is smaller than 44 points")

        let chart = app.descendants(matching: .any)["seasonChart"]
        for _ in 0..<4 where !chart.isHittable { app.swipeUp() }
        XCTAssertTrue(chart.exists, "The plant page has no season chart")
        XCTAssertTrue(chart.label.hasPrefix("Through the year. In leaf: March to October; Bloom: March to April"),
                      "The chart says \(chart.label)")
        // Every range under the chart reads with "to", as plant-page-scrolled.png writes them.
        let flowers = app.descendants(matching: .any)["fact-Flowers"]
        XCTAssertTrue(flowers.exists, "The plant page has no Flowers fact")
        XCTAssertTrue(flowers.label.contains("Mar to Apr"), "Spicebush's flowers read: \(flowers.label)")
        let growth = app.descendants(matching: .any)["fact-Growth"]
        XCTAssertTrue(growth.exists, "The plant page has no Growth fact")
        XCTAssertTrue(growth.label.contains("Slow-moderate (about 1 to 1.5 ft a year); 6 to 12 ft in about 6 to 8 years; fruit in 3 to 6 years"),
                      "Spicebush's growth reads: \(growth.label)")
        for fact in [height, flowers, growth] {
            XCTAssertFalse(fact.label.contains("\u{2013}"), "A fact shows an en dash: \(fact.label)")
        }
        // Up a little at a time, until the Where to buy row is on screen above the tab bar, as the picture shows it.
        let card = app.buttons["whereToBuyCard"]
        let window = app.windows.firstMatch
        for _ in 0..<10 where !(card.isHittable && card.frame.maxY < app.tabBars.firstMatch.frame.minY) {
            window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.6))
                .press(forDuration: 0.05, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)),
                       withVelocity: .slow, thenHoldForDuration: 0.3)
        }
        XCTAssertTrue(card.isHittable, "The Where to buy row cannot be reached")
        attachScreenshot(app, "plant-page-scrolled")

        let usda = app.buttons["usdaLink"]
        for _ in 0..<6 where !usda.isHittable { app.swipeUp() }
        XCTAssertTrue(app.descendants(matching: .any)["rangeMap"].exists, "The plant page has no range map")
        XCTAssertTrue(usda.isHittable, "The USDA link cannot be reached")
        attachScreenshot(app, "plant-page-map")
        usda.tap()
        XCTAssertTrue(StubServer.wait(10) { self.openedLinks.last == "https://plants.sc.egov.usda.gov/plant-profile/LIBE3" },
                      "The USDA link opened \(openedLinks)")
    }

    /// Opening Spicebush sends buy_panel_open, as the website does when a plant page opens, before Where to buy is
    /// tapped. The panel lists the website's sellers for Spicebush in Fairfax in the website's order, and Garden for
    /// Wildlife hands the browser the website's link, ending in "__app", with the website's events.
    @MainActor
    func testWhereToBuyListsTheWebsitesSellersAndOpensTheAppLink() throws {
        let view = try sellersCase(plant: "lindera-benzoin", county: "51059")
        let want = rows(view).map { $0["name"] as? String ?? "" }
        XCTAssertEqual(want.first, "Garden for Wildlife", "sellers.json no longer starts with Garden for Wildlife")
        let app = launch()
        openPlant(app, search: "Spicebush", id: "lindera-benzoin", name: "Spicebush")
        XCTAssertTrue(StubServer.wait(20) { !self.posted("buy_panel_open").isEmpty },
                      "No buy_panel_open event when the plant page opened")
        XCTAssertFalse(app.buttons["whereToBuyDone"].exists, "The Where to buy panel is open before it was tapped")
        XCTAssertEqual(posted("buy_panel_open").first?["plant"] as? String, "lindera-benzoin")
        openWhereToBuy(app)
        attachScreenshot(app, "where-to-buy-sheet")
        XCTAssertTrue(app.staticTexts["7 sellers ship Spicebush to Virginia. None has a shop near you."].exists,
                      "No summary line")
        XCTAssertTrue(app.staticTexts["commissionNote"].exists, "No commission note with an affiliate seller listed")
        XCTAssertEqual(shownSellers(app, count: want.count), want)
        XCTAssertEqual(posted("buy_panel_open").count, 1, "Opening the panel sent buy_panel_open again")

        let gfw = sellerRows(app).matching(NSPredicate(format: "label == 'Garden for Wildlife'")).firstMatch
        for _ in 0..<4 where !gfw.isHittable { app.scrollViews["whereToBuyRows"].swipeDown() }
        XCTAssertTrue(gfw.isHittable, "The Garden for Wildlife row cannot be tapped")
        gfw.tap()
        let href = try XCTUnwrap(rows(view).first?["href"] as? String)
        XCTAssertTrue(StubServer.wait(10) { !self.openedLinks.isEmpty }, "Tapping Garden for Wildlife opened nothing")
        XCTAssertEqual(openedLinks.last, href, "The link handed to the browser is not the website's")
        XCTAssertTrue(openedLinks.last?.hasSuffix("__app") == true, "The link does not end in __app: \(openedLinks)")

        XCTAssertTrue(StubServer.wait(20) { !self.posted("affiliate_click").isEmpty }, "No affiliate_click event")
        let click = try XCTUnwrap(posted("affiliate_click").first)
        XCTAssertEqual(click["plant"] as? String, "lindera-benzoin")
        XCTAssertEqual(click["vendor"] as? String, "Garden for Wildlife")
        XCTAssertEqual(click["local"] as? Bool, false)
        XCTAssertEqual(click["affiliate"] as? Bool, true)
        XCTAssertTrue(posted("local_vendor_click").isEmpty, "local_vendor_click was sent for a seller that only ships")
    }

    /// Louisville has a nursery 15 miles away that sells Bur Oak: its row says so, and Directions hands Apple Maps
    /// the same place as the website's Google Maps link.
    @MainActor
    func testANearbyShopHasDirectionsToTheSamePlace() throws {
        let view = try sellersCase(plant: "quercus-macrocarpa", county: "21111")
        let first = try XCTUnwrap(rows(view).first)
        XCTAssertEqual(first["local"] as? Bool, true, "sellers.json no longer has a nearby shop first for Bur Oak in Louisville")
        let directions = try XCTUnwrap(first["directions"] as? String)
        let place = try XCTUnwrap(URLComponents(string: directions)?.queryItems?.first { $0.name == "destination" }?.value)
        let app = launch(zip: "40202")
        openPlant(app, search: "Bur Oak", id: "quercus-macrocarpa", name: "Bur Oak")
        openWhereToBuy(app)
        XCTAssertEqual(shownSellers(app, count: rows(view).count), rows(view).map { $0["name"] as? String ?? "" })
        let row = sellerRows(app).firstMatch
        XCTAssertEqual(row.label, first["name"] as? String)
        XCTAssertTrue((row.value as? String ?? "").contains("Pick up \u{00B7} 15 mi \u{00B7} Goshen, KY"),
                      "The nearby row says \(row.value as? String ?? "")")
        attachScreenshot(app, "Where to buy, a shop nearby")

        let button = app.buttons["directions"].firstMatch
        XCTAssertTrue(button.isHittable, "The nearby shop has no Directions button")
        XCTAssertGreaterThanOrEqual(button.frame.height, 44, "The Directions button is smaller than 44 points")
        button.tap()
        XCTAssertTrue(StubServer.wait(10) { !self.openedLinks.isEmpty }, "Directions opened nothing")
        let opened = try XCTUnwrap(URLComponents(string: openedLinks.last ?? ""))
        XCTAssertEqual(opened.host, "maps.apple.com", "Directions did not open Apple Maps: \(openedLinks)")
        XCTAssertEqual(opened.queryItems?.first { $0.name == "daddr" }?.value, place, "Apple Maps was given another place")

        XCTAssertTrue(StubServer.wait(20) { !self.posted("local_vendor_click").isEmpty }, "No local_vendor_click event")
        let click = try XCTUnwrap(posted("local_vendor_click").first)
        XCTAssertEqual(click["plant"] as? String, "quercus-macrocarpa")
        XCTAssertEqual(click["vendor"] as? String, first["name"] as? String)
        XCTAssertTrue(posted("affiliate_click").isEmpty, "affiliate_click was sent for Directions")
    }

    /// No seller sends Staghorn Sumac to Virginia: opening the page sends no_seller_shown with the state, as the
    /// website does, and the panel shows the website's line.
    @MainActor
    func testAPlantWithNoSellerShowsTheNoSellerLine() throws {
        let view = try sellersCase(plant: "rhus-typhina", county: "51059")
        XCTAssertTrue(rows(view).isEmpty, "sellers.json now has sellers for Staghorn Sumac in Fairfax")
        let line = try XCTUnwrap(view["noSellerText"] as? String)
        let app = launch()
        openPlant(app, search: "Staghorn", id: "rhus-typhina", name: "Staghorn Sumac")
        XCTAssertTrue(StubServer.wait(20) { !self.posted("no_seller_shown").isEmpty },
                      "No no_seller_shown event when the plant page opened")
        XCTAssertFalse(app.buttons["whereToBuyDone"].exists, "The Where to buy panel is open before it was tapped")
        let event = try XCTUnwrap(posted("no_seller_shown").first)
        XCTAssertEqual(event["plant"] as? String, "rhus-typhina")
        XCTAssertEqual(event["state"] as? String, "VA")
        openWhereToBuy(app)
        let shown = app.staticTexts["noSeller"]
        XCTAssertTrue(shown.waitForExistence(timeout: 5), "The panel has no no-seller line")
        XCTAssertEqual(shown.label, line)
        XCTAssertEqual(sellerRows(app).count, 0, "The panel lists sellers")
        XCTAssertFalse(app.staticTexts["commissionNote"].exists, "The commission note shows with no affiliate seller")
        attachScreenshot(app, "Where to buy, no seller")
        XCTAssertEqual(posted("no_seller_shown").count, 1, "Opening the panel sent no_seller_shown again")
    }

    /// A plant with no photograph shows the brand kit's plate, and no counter.
    @MainActor
    func testAPlantWithNoPhotoShowsThePlate() throws {
        let app = launch()
        openPlant(app, search: "Lovegrass", id: "eragrostis-spectabilis", name: "Purple Lovegrass")
        let plate = app.descendants(matching: .any)["noPhoto"]
        XCTAssertTrue(plate.waitForExistence(timeout: 10), "No \"No photograph yet\" plate")
        XCTAssertEqual(plate.label, "Purple Lovegrass: no photograph yet")
        XCTAssertFalse(app.staticTexts["photoCounter"].exists, "A plant with no photo has a counter")
        attachScreenshot(app, "Plant page, no photograph")
    }

    /// At the largest text size the page and the panel still fit their words, and every button can be reached.
    @MainActor
    func testTheLargestTextSize() throws {
        let app = launch(textSize: "UICTContentSizeCategoryAccessibilityXXXL")
        openPlant(app, search: "Spicebush", id: "lindera-benzoin", name: "Spicebush")
        XCTAssertTrue(app.staticTexts["photoCounter"].waitForExistence(timeout: 10), "The photo has no counter")
        attachScreenshot(app, "plant-page-top, largest text")
        let buy = app.buttons["whereToBuy"]
        for _ in 0..<4 where !buy.isHittable { app.swipeUp() }
        XCTAssertTrue(buy.isHittable, "The Where to buy button cannot be reached at the largest text size")
        attachScreenshot(app, "Save and Where to buy, largest text")
        for _ in 0..<3 { app.swipeUp() }
        attachScreenshot(app, "plant-page-scrolled, largest text")
        for _ in 0..<8 where !buy.isHittable { app.swipeDown() }
        openWhereToBuy(app)
        attachScreenshot(app, "where-to-buy-sheet, largest text")
        XCTAssertEqual(sellerRows(app).firstMatch.label, "Garden for Wildlife")
    }
}
