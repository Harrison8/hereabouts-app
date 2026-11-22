// The Garden tab's model without the screen: planning the Saturday reminders again leaves the app's other
// notifications alone; coming to the front reads the notification permission again and moves the clock on (but not a
// screen test's fixed clock); a tapped reminder closes what is open and shows the Garden tab; and where a saved
// plant's row comes to rest when a swipe stops, including a swipe the phone calls off.
import HereaboutsCore
import XCTest
@testable import Hereabouts

final class GardenUnitTests: XCTestCase {
    /// The shipped data and care tables, a throwaway store, and the stand-in notification settings.
    @MainActor
    private func makeModel(fixedNow: Date? = nil, notifications: String?) throws -> (AppModel, TestNotifications) {
        let name = "hereabouts.unit-test-garden-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        addTeardownBlock { UserDefaults().removePersistentDomain(forName: name) }
        let centre = TestNotifications(setting: notifications)
        let shipped = AppModel.forLaunch(arguments: [])
        let model = AppModel(data: shipped.data, tables: shipped.tables, defaults: defaults, care: shipped.care,
                             reminders: ReminderScheduler(centre: centre), fixedNow: fixedNow)
        return (model, centre)
    }

    /// Checks `condition` every 10 milliseconds for up to two seconds.
    @MainActor
    private func waitUntil(_ condition: () -> Bool) async -> Bool {
        for _ in 0..<200 {
            if condition() { return true }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return condition()
    }

    /// Planning again removes the old Saturday reminders and adds the new ones, and leaves a notification whose
    /// identifier does not start hereabouts.week. waiting.
    @MainActor
    func testPlanningAgainRemovesOnlyTheSaturdayReminders() async {
        let centre = TestNotifications(setting: "allowed")
        let at = DateComponents(year: 2027, month: 10, day: 9, hour: 8, minute: 0)
        await centre.add(PlannedReminder(id: "other.1", title: "Other", body: "", at: at))
        await centre.add(PlannedReminder(id: "hereabouts.week.20271002", title: "Old", body: "", at: at))
        let scheduler = ReminderScheduler(centre: centre)
        scheduler.reschedule([PlannedReminder(id: "hereabouts.week.20271009", title: "New", body: "", at: at)])
        await scheduler.settled()
        let ids = await centre.waiting().map(\.id)
        XCTAssertEqual(ids, ["hereabouts.week.20271009", "other.1"])
    }

    /// The person refuses notifications in the phone's Settings while the app is in the background: once the app is
    /// back in front it knows, and no longer says a reminder will come. Turning the switch off says the same.
    @MainActor
    func testComingToTheFrontReadsThePermissionAgain() async throws {
        let (model, centre) = try makeModel(notifications: "willDeny")
        model.cameToFront()
        let readFirst = await waitUntil { model.notificationPermission == .notAsked }
        XCTAssertTrue(readFirst, "The permission was not read when the app came to the front")
        XCTAssertTrue(model.remindersWillCome, "Not asked yet, with the switch on, should still say a reminder will come")
        model.remindersOn = false
        XCTAssertFalse(model.remindersWillCome, "With the switch off a reminder should not be promised")
        model.remindersOn = true

        _ = await centre.ask()
        XCTAssertEqual(model.notificationPermission, .notAsked, "The permission changed before the app came back")
        model.cameToFront()
        let readAgain = await waitUntil { model.notificationPermission == .denied }
        XCTAssertTrue(readAgain, "The refusal was not read when the app came back to the front")
        XCTAssertFalse(model.remindersWillCome, "After a refusal a reminder should not be promised")
    }

    /// Coming to the front takes the phone's time again, so This week can move on to a new week. A screen test's fixed
    /// time stays as it was.
    @MainActor
    func testComingToTheFrontMovesTheClockButNotATestsFixedClock() async throws {
        let (live, _) = try makeModel(notifications: nil)
        let before = live.now
        try await Task.sleep(for: .milliseconds(20))
        live.cameToFront()
        XCTAssertGreaterThan(live.now, before, "Coming to the front did not take the phone's time again")

        let fixed = Date(timeIntervalSince1970: 1_826_000_000)
        let (test, _) = try makeModel(fixedNow: fixed, notifications: nil)
        test.cameToFront()
        XCTAssertEqual(test.now, fixed, "Coming to the front moved a screen test's fixed time")
    }

    /// A tapped reminder closes the open job sheet and any plant page opened from the Garden tab, tells every other
    /// sheet to close, and shows the Garden tab.
    @MainActor
    func testATappedReminderClosesWhatIsOpenAndShowsTheGarden() throws {
        let (model, _) = try makeModel(notifications: nil)
        model.gardenPath = ["echinacea-purpurea"]
        model.openJob = WeekRow(jobs: [Job(ruleId: "stems-tidy", heading: "Cut back dead stems for a tidy garden",
                                           facts: [], examplePlantIds: ["echinacea-purpurea"], pair: "stems")])
        let before = model.closeSheetsCount
        model.openedFromNotification()
        XCTAssertEqual(model.selectedTab, .garden)
        XCTAssertTrue(model.gardenPath.isEmpty, "The plant page opened from the Garden tab is still open")
        XCTAssertNil(model.openJob, "The job sheet is still open")
        XCTAssertEqual(model.closeSheetsCount, before + 1, "The other sheets were not told to close")
    }

    // MARK: - where a swiped row comes to rest

    /// A row 393 points wide, as on an iPhone 17. The finger lifts.
    func testALiftedSwipeRemovesOpensOrShutsByHowFarItWent() {
        XCTAssertEqual(SwipeEnd.settle(from: 0, dx: -300, width: 393, lifted: true), .remove)
        XCTAssertEqual(SwipeEnd.settle(from: 0, dx: -120, width: 393, lifted: true), .open)
        XCTAssertEqual(SwipeEnd.settle(from: 0, dx: -20, width: 393, lifted: true), .shut)
        XCTAssertEqual(SwipeEnd.settle(from: -80, dx: 70, width: 393, lifted: true), .shut)
        XCTAssertEqual(SwipeEnd.settle(from: -80, dx: -160, width: 393, lifted: true), .remove)
        XCTAssertEqual(SwipeEnd.settle(from: 0, dx: 50, width: 393, lifted: true), .shut)
    }

    /// The phone calls the swipe off (a call, an alert, a swipe from the screen's edge): however far it went, the row
    /// goes back to where it began and the plant stays saved.
    func testASwipeThePhoneCallsOffNeverRemoves() {
        XCTAssertEqual(SwipeEnd.settle(from: 0, dx: -300, width: 393, lifted: false), .shut)
        XCTAssertEqual(SwipeEnd.settle(from: 0, dx: -120, width: 393, lifted: false), .shut)
        XCTAssertEqual(SwipeEnd.settle(from: -80, dx: -300, width: 393, lifted: false), .open)
        XCTAssertEqual(SwipeEnd.settle(from: -80, dx: 80, width: 393, lifted: false), .open)
    }
}
