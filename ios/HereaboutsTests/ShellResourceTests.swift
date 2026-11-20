// Checks run inside the app itself: the brand fonts are registered, the shipped data is where the app reads it (and
// the test-only expected answers are not shipped), and the three kept things come back as they were kept. The location
// keeps only its county and a rounded position, and the county's details are looked up again on load.
import HereaboutsCore
import UIKit
import XCTest
@testable import Hereabouts

final class ShellResourceTests: XCTestCase {
    @MainActor
    func testBrandFontsAreRegistered() {
        let figtree = UIFont.fontNames(forFamilyName: "Figtree")
        let young = UIFont.fontNames(forFamilyName: "Young Serif")
        for name in [Theme.FontName.display, Theme.FontName.body, Theme.FontName.bodyMedium,
                     Theme.FontName.bodySemiBold] {
            XCTAssertNotNil(UIFont(name: name, size: 17),
                            "\(name) is not registered. Figtree names: \(figtree). Young Serif names: \(young)")
        }
    }

    @MainActor
    func testDataShipsWithoutTheExpectedAnswers() {
        let files = FileManager.default
        let bundle = AppModel.bundleURL
        for path in ["data/plants.json", "data/version.json", "tables/zip-county.json", "tables/care-rules.json",
                     "fonts/YoungSerif-Regular.ttf", "fonts/Figtree[wght].ttf"] {
            XCTAssertTrue(files.fileExists(atPath: bundle.appendingPathComponent(path).path), "bundle/\(path) is missing")
        }
        let images = (try? files.contentsOfDirectory(atPath: bundle.appendingPathComponent("images").path)) ?? []
        XCTAssertFalse(images.isEmpty, "bundle/images is empty")
        XCTAssertFalse(files.fileExists(atPath: bundle.appendingPathComponent("expected").path),
                       "bundle/expected is in the app, but only the tests need it")
    }

    @MainActor
    func testTheModelLoadsAndKeepsWhatItShould() throws {
        let suite = "hereabouts.unit-test"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        defer { defaults.removePersistentDomain(forName: suite) }

        let first = AppModel.forLaunch(arguments: [])
        XCTAssertFalse(first.data.plants.isEmpty)
        let model = AppModel(data: first.data, tables: first.tables, defaults: defaults)
        XCTAssertNil(model.location)

        guard case .found(let fairfax) = LocationLookup.resolve(countyFips: "51059", lat: nil, lon: nil,
                                                                tables: model.tables) else {
            return XCTFail("51059 did not resolve")
        }
        model.setLocation(fairfax)
        model.toggleSaved("echinacea-purpurea")
        model.toggleSaved("asclepias-tuberosa")
        model.toggleSaved("rudbeckia-hirta")
        model.toggleSaved("asclepias-tuberosa")
        model.toggleTick("cut-back")

        let again = AppModel(data: first.data, tables: first.tables, defaults: defaults)
        XCTAssertEqual(again.location, fairfax)
        XCTAssertEqual(again.location?.countyName, "Fairfax County")
        XCTAssertEqual(again.saved, ["echinacea-purpurea", "rudbeckia-hirta"])
        XCTAssertTrue(again.isTicked("cut-back"))
    }

    @MainActor
    func testTheLocationKeepsOnlyTheCountyAndARoundedPosition() throws {
        let suite = "hereabouts.unit-test-location"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        defer { defaults.removePersistentDomain(forName: suite) }

        let first = AppModel.forLaunch(arguments: [])
        let model = AppModel(data: first.data, tables: first.tables, defaults: defaults)
        guard case .found(let fairfax) = LocationLookup.resolve(countyFips: "51059", lat: 38.84567, lon: -77.30612,
                                                                tables: model.tables) else {
            return XCTFail("51059 did not resolve")
        }
        model.setLocation(fairfax)
        XCTAssertEqual(model.location?.lat, 38.85)
        XCTAssertEqual(model.location?.lon, -77.31)

        // Only the county code and the rounded position are written to the phone.
        let written = try XCTUnwrap(defaults.data(forKey: LocationStore.key))
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: written) as? [String: Any])
        XCTAssertEqual(Set(object.keys), ["countyFips", "lat", "lon"])
        XCTAssertEqual(object["countyFips"] as? String, "51059")
        XCTAssertEqual(object["lat"] as? Double, 38.85)
        XCTAssertEqual(object["lon"] as? Double, -77.31)

        let again = AppModel(data: first.data, tables: first.tables, defaults: defaults)
        XCTAssertEqual(again.location?.lat, 38.85)
        XCTAssertEqual(again.location?.lon, -77.31)

        // The county's name, state and ecoregion come from the tables on load, not from what was written.
        defaults.set(Data(#"{"countyFips":"51059","countyName":"Old name","stateName":"Old state","stateUsps":"XX","ecoregion":"00","lat":38.85,"lon":-77.31}"#.utf8),
                     forKey: LocationStore.key)
        let reloaded = try XCTUnwrap(AppModel(data: first.data, tables: first.tables, defaults: defaults).location)
        XCTAssertEqual(reloaded.countyName, "Fairfax County")
        XCTAssertEqual(reloaded.stateUsps, "VA")
        XCTAssertEqual(reloaded.stateName, fairfax.stateName)
        XCTAssertEqual(reloaded.ecoregion, model.tables.countyEcoregion["51059"])
        XCTAssertEqual(reloaded.lat, 38.85)

        // A kept county the tables no longer serve, or one that does not read, loads as no county.
        defaults.set(Data(#"{"countyFips":"99999","lat":null,"lon":null}"#.utf8), forKey: LocationStore.key)
        XCTAssertNil(AppModel(data: first.data, tables: first.tables, defaults: defaults).location)
        defaults.set(Data("not json".utf8), forKey: LocationStore.key)
        XCTAssertNil(AppModel(data: first.data, tables: first.tables, defaults: defaults).location)
    }

    func testTheWeekIsNamedByItsSaturday() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/New_York")!
        func day(_ y: Int, _ m: Int, _ d: Int, _ h: Int = 12) -> Date {
            calendar.date(from: DateComponents(year: y, month: m, day: d, hour: h))!
        }
        XCTAssertEqual(TicksStore.weekId(for: day(2026, 11, 14), calendar: calendar), "20261114") // Saturday
        XCTAssertEqual(TicksStore.weekId(for: day(2026, 11, 19), calendar: calendar), "20261114") // Thursday
        XCTAssertEqual(TicksStore.weekId(for: day(2026, 11, 20, 23), calendar: calendar), "20261114") // Friday night
        XCTAssertEqual(TicksStore.weekId(for: day(2026, 11, 21, 0), calendar: calendar), "20261121") // Saturday
        XCTAssertEqual(TicksStore.weekId(for: day(2027, 1, 1), calendar: calendar), "20261226") // across the year
    }

    @MainActor
    func testTicksFromEarlierWeeksAreDropped() throws {
        let suite = "hereabouts.unit-test-ticks"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        defer { defaults.removePersistentDomain(forName: suite) }

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let thursday = calendar.date(from: DateComponents(year: 2026, month: 11, day: 19, hour: 12))!
        defaults.set(["20261107": ["water"], "20261114": ["cut-back"], "junk": ["x"]], forKey: TicksStore.key)

        let store = TicksStore(defaults: defaults)
        XCTAssertEqual(store.load(now: thursday, calendar: calendar), ["20261114": ["cut-back"]])
        // What was dropped is forgotten, not only hidden.
        XCTAssertEqual(defaults.dictionary(forKey: TicksStore.key) as? [String: [String]], ["20261114": ["cut-back"]])
    }
}
