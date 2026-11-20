// The network services inside the app, against a stand-in for the website on 127.0.0.1: what an event carries and
// what is never sent, the limits on waiting events, the daily data check, and photos for plants added later.
import HereaboutsCore
import XCTest
@testable import Hereabouts

final class ServicesUnitTests: XCTestCase {
    private var server: StubServer!
    private var folder: URL!

    override func setUpWithError() throws {
        server = try StubServer()
        folder = FileManager.default.temporaryDirectory.appendingPathComponent("services-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    override func tearDown() {
        server.stop()
        try? FileManager.default.removeItem(at: folder)
    }

    private var site: URL { URL(string: server.baseURL)! }

    private func sender(online: Bool) -> EventSender {
        EventSender(site: site, apiKey: "phc_test", distinctId: "test-id", queueFile: folder.appendingPathComponent("events.json"),
                    isOnline: { online })
    }

    private func names(_ events: [(apiKey: String, event: [String: Any])]) -> [String] {
        events.map { $0.event["event"] as? String ?? "?" }
    }

    // MARK: Events

    func testEventsKeepOnlyTheAllowedPropertiesAndAllSayPlatformIos() async throws {
        server.serve("/ingest/batch/", Data("{}".utf8))
        let events = sender(online: true)
        await events.send("location_set", ["method": .string("zip"), "county": .string("Fairfax County, VA"),
                                           "state": .string("VA"), "ecoregion": .string("45"), "zip": .string("22030"),
                                           "lat": .number(38.85), "platform": .string("web")])
        await events.send("results_shown", ["count": .number(58), "ecoregion": .string("45"), "query": .string("oak")])
        await events.send("not_an_event", ["plant": .string("x")])
        // Once per launch for each plant and seller.
        await events.send("buy_panel_open", ["plant": .string("lindera-benzoin")])
        await events.send("buy_panel_open", ["plant": .string("lindera-benzoin")])
        await events.send("buy_panel_open", ["plant": .string("asclepias-tuberosa")])
        await events.send("affiliate_click", ["plant": .string("lindera-benzoin"), "vendor": .string("A")])
        await events.send("affiliate_click", ["plant": .string("lindera-benzoin"), "vendor": .string("B")])
        await events.send("affiliate_click", ["plant": .string("lindera-benzoin"), "vendor": .string("A")])
        await events.flush()

        let posted = server.postedEvents
        XCTAssertEqual(names(posted), ["location_set", "results_shown", "buy_panel_open", "buy_panel_open",
                                       "affiliate_click", "affiliate_click"])
        for (apiKey, event) in posted {
            let name = event["event"] as? String ?? "?"
            let properties = try XCTUnwrap(event["properties"] as? [String: Any])
            XCTAssertEqual(apiKey, "phc_test", name)
            XCTAssertEqual(properties["platform"] as? String, "ios", name)
            XCTAssertEqual(properties["distinct_id"] as? String, "test-id", name)
            XCTAssertEqual(properties["$process_person_profile"] as? Bool, false, name)
            XCTAssertNotNil(event["timestamp"] as? String, name)
            XCTAssertNotNil(event["uuid"] as? String, name)
        }
        let location = try XCTUnwrap(posted[0].event["properties"] as? [String: Any])
        XCTAssertEqual(Set(location.keys), ["method", "county", "state", "ecoregion", "platform", "distinct_id",
                                            "$process_person_profile"])
        let results = try XCTUnwrap(posted[1].event["properties"] as? [String: Any])
        XCTAssertNil(results["query"])
        XCTAssertEqual(results["count"] as? Double, 58)
        let queued = await events.queued
        XCTAssertTrue(queued.isEmpty, "Sent events stay queued")
    }

    func testANumberThatIsNotFiniteDropsOnlyItsOwnEvent() async throws {
        server.serve("/ingest/batch/", Data("{}".utf8))
        let events = sender(online: false)
        await events.send("results_shown", ["count": .number(3)])
        await events.send("results_shown", ["count": .number(.nan)])
        await events.send("plant_search", ["count": .number(.infinity), "hidden_count": .number(1)])
        await events.send("plant_search", ["count": .number(4), "hidden_count": .number(1)])
        let queued = await events.queued
        XCTAssertEqual(queued.map(\.event), ["results_shown", "plant_search"])

        let online = sender(online: true)
        await online.flush()
        XCTAssertEqual(names(server.postedEvents), ["results_shown", "plant_search"])
    }

    func testAtMost500EventsWaitAndTheyOutliveARestart() async throws {
        server.serve("/ingest/batch/", Data("{}".utf8))
        let offline = sender(online: false)
        for i in 0..<505 { await offline.send("results_shown", ["count": .number(Double(i))]) }
        let waiting = await offline.queued
        XCTAssertEqual(waiting.count, 500)
        XCTAssertTrue(server.received.isEmpty, "Something was sent with no signal")

        // A new launch reads the waiting events back and sends them once there is signal. The oldest five were dropped.
        let restarted = sender(online: true)
        await restarted.flush()
        let counts = server.postedEvents.compactMap { ($0.event["properties"] as? [String: Any])?["count"] as? Double }
        XCTAssertEqual(counts, (5..<505).map(Double.init))
        let left = await restarted.queued
        XCTAssertTrue(left.isEmpty)
    }

    func testEventsWaitWhenTheServerFails() async throws {
        server.serve("/ingest/batch/", Data("busy".utf8), status: 503)
        let events = sender(online: true)
        await events.send("browse_all_opened", [:])
        let waiting = await events.queued
        XCTAssertEqual(waiting.count, 1, "An event the server did not take was dropped")
    }

    // MARK: Data check

    @MainActor
    private func shippedModel(defaults: UserDefaults) -> AppModel {
        let shipped = AppModel.forLaunch(arguments: [])
        return AppModel(data: shipped.data, tables: shipped.tables, defaults: defaults)
    }

    @MainActor
    private func freshDefaults(_ name: String) throws -> UserDefaults {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        defaults.removePersistentDomain(forName: name)
        addTeardownBlock { UserDefaults().removePersistentDomain(forName: name) }
        return defaults
    }

    @MainActor
    func testANewerDataSetIsUsedAtOnceKeptAndNotCheckedAgainForADay() async throws {
        StubData.good.serve(from: server)
        let defaults = try freshDefaults("hereabouts.unit-test-update-good")
        let model = shippedModel(defaults: defaults)
        let shipped = model.data.version
        let dataFolder = folder.appendingPathComponent("data", isDirectory: true)
        let start = Date()
        var now = start
        let updater = DataUpdater(model: model, site: site, dataFolder: dataFolder, defaults: defaults,
                                  isOnline: { true }, now: { now })
        await updater.checkIfDue()

        XCTAssertEqual(model.data.plants.map(\.id), ["test-plant-one", "test-plant-two"])
        let saved = try XCTUnwrap(DataUpdater.saved(in: dataFolder, newerThan: shipped), "The newer set was not kept")
        XCTAssertEqual(saved.plants.map(\.id), ["test-plant-one", "test-plant-two"])

        // Within a day: no request at all. A day later: checked again.
        let asked = server.received.count
        now = start.addingTimeInterval(23 * 3600)
        await updater.checkIfDue()
        XCTAssertEqual(server.received.count, asked)
        now = start.addingTimeInterval(25 * 3600)
        await updater.checkIfDue()
        XCTAssertGreaterThan(server.received.count, asked)
    }

    @MainActor
    func testADataSetThatDoesNotReadKeepsTheOldData() async throws {
        StubData.unreadable.serve(from: server)
        let defaults = try freshDefaults("hereabouts.unit-test-update-bad")
        let model = shippedModel(defaults: defaults)
        let before = model.data.plants.map(\.id)
        let dataFolder = folder.appendingPathComponent("data", isDirectory: true)
        let updater = DataUpdater(model: model, site: site, dataFolder: dataFolder, defaults: defaults, isOnline: { true })
        await updater.checkIfDue()

        XCTAssertTrue(server.received.contains { $0.path == "/app-data/v1/plants.json" }, "The broken set was never downloaded")
        XCTAssertEqual(model.data.plants.map(\.id), before)
        XCTAssertFalse(FileManager.default.fileExists(atPath: dataFolder.path), "The broken set was kept")
    }

    @MainActor
    func testNoCheckWithoutSignal() async throws {
        StubData.good.serve(from: server)
        let defaults = try freshDefaults("hereabouts.unit-test-update-offline")
        let model = shippedModel(defaults: defaults)
        let updater = DataUpdater(model: model, site: site, dataFolder: folder.appendingPathComponent("data"),
                                  defaults: defaults, isOnline: { false })
        await updater.checkIfDue()
        XCTAssertTrue(server.received.isEmpty)
        XCTAssertNil(defaults.object(forKey: DataUpdater.lastCheckKey), "A check that never ran was recorded")
    }

    // MARK: Photos

    @MainActor
    func testPhotosOfPlantsAddedLaterAreDownloadedOnceAndKept() async throws {
        server.serve("/images/test-plant-two-green.jpg", Data("jpeg bytes".utf8))
        let cache = PhotoCache(site: site, bundle: AppModel.bundleURL, folder: folder.appendingPathComponent("photos"))

        // A photo the app ships with needs no download.
        let shipped = Photo(url: "images/quercus-alba-green.jpg", alt: "", credit: "")
        XCTAssertNotNil(cache.file(for: shipped))
        let shippedFile = await cache.load(shipped)
        XCTAssertNotNil(shippedFile)

        let added = Photo(url: "images/test-plant-two-green.jpg", alt: "", credit: "")
        XCTAssertNil(cache.file(for: added), "A photo nobody downloaded is already there")
        let loaded = await cache.load(added)
        let downloaded = try XCTUnwrap(loaded)
        XCTAssertEqual(try Data(contentsOf: downloaded), Data("jpeg bytes".utf8))
        XCTAssertEqual(cache.file(for: added), downloaded)
        _ = await cache.load(added)
        XCTAssertEqual(server.received.map(\.path), ["/images/test-plant-two-green.jpg"])

        // An address outside images/ is never fetched.
        let outside = await cache.load(Photo(url: "../secret.jpg", alt: "", credit: ""))
        XCTAssertNil(outside)
        let nested = await cache.load(Photo(url: "images/../../secret.jpg", alt: "", credit: ""))
        XCTAssertNil(nested)
        XCTAssertEqual(server.received.count, 1)
    }
}
