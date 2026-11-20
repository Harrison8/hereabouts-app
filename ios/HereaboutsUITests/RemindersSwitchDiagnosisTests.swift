// Temporary: the Garden test that turns Saturday reminders off in Settings, run in two ways to find why a tap on the
// switch sometimes does nothing after the app is left and reopened. The build machine runs this class on its own,
// many times over, after the other screen tests. Every launch adds -uiTestTouchLog, so the app writes each touch, when
// the phone stamped it and when the app was handed it, and each time its main thread was busy, to the phone's log,
// which the build machine saves. Each test prints lines starting "DIAG" with its outcome.
// - a: the saved plants listed with no swipe at all (-uiTestNoSwipe);
// - d: two plants saved, with the leave-and-reopen.
import XCTest

final class RemindersSwitchDiagnosisTests: XCTestCase {
    private var server: StubServer!

    private static let four = ["asclepias-tuberosa", "schizachyrium-scoparium", "quercus-alba", "lindera-benzoin"]
    private static let two = ["asclepias-tuberosa", "lindera-benzoin"]

    override func setUpWithError() throws {
        continueAfterFailure = false
        server = try StubServer()
    }

    override func tearDown() {
        server.stop()
        server = nil
    }

    @MainActor func testA_NoSwipe() throws { try check("a", saved: Self.four, extra: ["-uiTestNoSwipe"]) }
    @MainActor func testD_TwoPlants() throws { try check("d", saved: Self.two) }

    /// How many times the app has reported its waiting reminders.
    private var reports: Int {
        server.received.filter { $0.method == "POST" && $0.path == "/uitest/reminders" }.count
    }

    /// How many reminders the app's latest report lists, or nil before its first report.
    private var latestCount: Int? {
        guard let last = server.received.last(where: { $0.method == "POST" && $0.path == "/uitest/reminders" }),
              let list = try? JSONSerialization.jsonObject(with: last.body) as? [Any] else { return nil }
        return list.count
    }

    private func stamp() -> String { ISO8601DateFormatter.string(from: Date(), timeZone: .gmt,
                                                                  formatOptions: [.withInternetDateTime, .withFractionalSeconds]) }

    @MainActor
    private func shot(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    @MainActor
    private func check(_ variant: String, saved: [String], extra: [String] = [], leave: Bool = true,
                       settle: TimeInterval = 0, secondTap: Bool = false) throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestLocation", "51059", "-hereaboutsBaseURL", server.baseURL, "-uiTestCaptureLinks",
                               "-uiTestNow", "2027-10-02T12:00", "-uiTestSaved", saved.joined(separator: ","),
                               "-uiTestNotifications", "allowed", "-uiTestTouchLog"] + extra
        app.launch()
        print("DIAG \(variant) launched \(stamp())")
        let tabBar = app.tabBars.firstMatch
        XCTAssertTrue(tabBar.waitForExistence(timeout: 20), "No tab bar after launch")
        tabBar.buttons["Garden"].tap()
        XCTAssertTrue(app.navigationBars["My garden"].waitForExistence(timeout: 10), "The Garden tab did not open")
        // As the Garden test does: wait for the reminders to be planned and the line under This week to show. Neither
        // is required here, since the two-plant case has no website answer to wait for.
        let planned = StubServer.wait(20) { (self.latestCount ?? 0) > 0 }
        XCTAssertTrue(app.descendants(matching: .any)["saved-lindera-benzoin"].waitForExistence(timeout: 5),
                      "The saved plants are not listed")
        let footer = app.staticTexts["thisWeekFooter"]
        let promised = StubServer.wait(10) { footer.exists && footer.label.contains("A reminder comes every Saturday at 8:00.") }
        print("DIAG \(variant) planned=\(planned) count=\(latestCount ?? -1) footer=\(promised) \(stamp())")
        shot(app, "\(variant) Garden")

        if leave {
            _ = StubServer.wait(2) { false }
            let before = reports
            print("DIAG \(variant) home \(stamp())")
            XCUIDevice.shared.press(.home)
            app.activate()
            XCTAssertTrue(app.navigationBars["My garden"].waitForExistence(timeout: 20), "The app did not come back")
            let replanned = StubServer.wait(15) { self.reports > before }
            print("DIAG \(variant) back replanned=\(replanned) count=\(latestCount ?? -1) \(stamp())")
        }
        if settle > 0 { _ = StubServer.wait(settle) { false } }

        app.tabBars.firstMatch.buttons["Settings"].tap()
        let toggle = app.switches["Saturday reminders"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 10), "No Saturday reminders switch")
        XCTAssertEqual(toggle.value as? String, "1", "Saturday reminders start off")
        print("DIAG \(variant) tap 1 at \(stamp()) switch frame \(toggle.frame)")
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        let first = StubServer.wait(5) { toggle.value as? String == "0" }
        print("DIAG \(variant) tap 1 \(first ? "worked" : "did nothing")")
        shot(app, "\(variant) after tap 1")
        if secondTap && !first {
            print("DIAG \(variant) tap 2 at \(stamp())")
            toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
            let second = StubServer.wait(5) { toggle.value as? String == "0" }
            print("DIAG \(variant) tap 2 \(second ? "worked" : "did nothing")")
            shot(app, "\(variant) after tap 2")
        }
        XCTAssertEqual(toggle.value as? String, "0", "Variant \(variant): the switch did not turn off")
    }
}
