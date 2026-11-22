// The app's network services, end to end, against a stand-in for the website run by the test itself on 127.0.0.1:
// the events it sends, a newer data set it takes and keeps, and a broken data set it ignores.
import XCTest

final class ServicesTests: XCTestCase {
    private var server: StubServer!

    override func setUpWithError() throws {
        continueAfterFailure = false
        server = try StubServer()
    }

    override func tearDown() {
        server.stop()
        server = nil
    }

    /// The app with Fairfax County chosen, talking only to the stub server. `freshStart` clears the files the app kept
    /// on earlier launches (downloaded data, waiting events); without it they stay, as on a real relaunch.
    @MainActor
    private func launchApp(freshStart: Bool) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestSkipOpening", "-uiTestLocation", "51059", "-hereaboutsBaseURL", server.baseURL]
        if !freshStart { app.launchArguments.append("-uiTestKeepFiles") }
        app.launch()
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 20), "No tab bar after launch")
        return app
    }

    @MainActor
    func testLocationSetArrivesWithThePlatformAndNoZip() throws {
        let app = launchApp(freshStart: true)
        XCTAssertTrue(StubServer.wait(30) { self.server.postedEvents.contains { $0.event["event"] as? String == "location_set" } },
                      "No location_set event reached the server. Requests: \(server.received.map { "\($0.method) \($0.path)" })")

        let posted = server.postedEvents
        for (apiKey, event) in posted {
            let name = event["event"] as? String ?? "?"
            let properties = try XCTUnwrap(event["properties"] as? [String: Any], "\(name) has no properties")
            XCTAssertEqual(properties["platform"] as? String, "ios", "\(name) does not say platform ios")
            XCTAssertTrue(apiKey.hasPrefix("phc_"), "\(name) was sent without the site's public key")
            XCTAssertFalse((properties["distinct_id"] as? String ?? "").isEmpty, "\(name) has no distinct_id")
            XCTAssertEqual(properties["$process_person_profile"] as? Bool, false, "\(name) would make a person profile")
        }
        let locationSet = try XCTUnwrap(posted.first { $0.event["event"] as? String == "location_set" }?.event)
        let properties = try XCTUnwrap(locationSet["properties"] as? [String: Any])
        XCTAssertNil(properties["zip"], "location_set carries a ZIP code")
        XCTAssertNil(properties["lat"], "location_set carries a position")
        XCTAssertNil(properties["lon"], "location_set carries a position")
        XCTAssertEqual(properties["county"] as? String, "Fairfax County, VA")
        XCTAssertEqual(properties["state"] as? String, "VA")
        app.terminate()
    }

    @MainActor
    func testANewerDataSetIsUsedAndKeptAfterRelaunch() throws {
        StubData.good.serve(from: server)
        let app = launchApp(freshStart: true)
        XCTAssertTrue(plantRow(app, "test-plant-one").waitForExistence(timeout: 30),
                      "The newer data set was not used. Requests: \(server.received.map(\.path))")
        attachScreenshot(app, "Newer data")

        // With the website gone, the relaunched app must still have the newer set, read from what it kept.
        app.terminate()
        server.serveNothing()
        let again = launchApp(freshStart: false)
        XCTAssertTrue(plantRow(again, "test-plant-one").waitForExistence(timeout: 20),
                      "After a relaunch the app went back to the data it ships with")
        XCTAssertTrue(plantRow(again, "test-plant-two").exists)
        XCTAssertEqual(plantRows(again).count, 2, "The list should hold only the newer set's two plants")
        again.terminate()
    }

    @MainActor
    func testABrokenDataSetIsIgnoredAndTheAppStillListsPlants() throws {
        StubData.unreadable.serve(from: server)
        let app = launchApp(freshStart: true)
        XCTAssertTrue(StubServer.wait(30) { self.server.received.contains { $0.path == "/app-data/v1/plant-regions.json" } },
                      "The app did not download all four files. Requests: \(server.received.map(\.path))")
        // Time to read and refuse the set.
        RunLoop.current.run(until: Date().addingTimeInterval(3))
        XCTAssertFalse(plantRow(app, "bad-data-plant").exists, "The broken data set was used")
        XCTAssertGreaterThan(plantRows(app).count, 2, "The app no longer lists the plants it ships with")
        attachScreenshot(app, "Broken data ignored")

        // Nothing of the broken set was kept for the next launch.
        app.terminate()
        server.serveNothing()
        let again = launchApp(freshStart: false)
        XCTAssertTrue(plantRows(again).firstMatch.waitForExistence(timeout: 20), "No plants listed after a relaunch")
        XCTAssertFalse(plantRow(again, "bad-data-plant").exists, "The broken data set was kept")
        XCTAssertGreaterThan(plantRows(again).count, 2, "The app no longer lists the plants it ships with")
        again.terminate()
    }

    /// Every plant row on the Plants tab. Each row is marked "plant-<id>", whatever kind of element it is.
    @MainActor
    private func plantRows(_ app: XCUIApplication) -> XCUIElementQuery {
        app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'plant-'"))
    }

    /// The row of one plant, by its id.
    @MainActor
    private func plantRow(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any)["plant-\(id)"]
    }

    @MainActor
    private func attachScreenshot(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }
}
