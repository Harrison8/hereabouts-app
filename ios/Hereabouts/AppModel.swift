// What the whole app shares: the plant and seller data, the location tables, the three things kept on the phone
// (the county, the saved plants, this week's ticked jobs), and the event sender and photo store. Screens read it from
// the environment.
import Foundation
import HereaboutsCore
import Observation

@MainActor
@Observable
final class AppModel {
    private(set) var data: AppData
    let tables: Tables
    /// The chosen county, or nil until one is set. The app shows the first-open screen until then.
    private(set) var location: Location?
    /// Saved plant ids, in the order they were saved.
    private(set) var saved: [String]
    /// Ticked jobs: week id (yyyymmdd of the week's Saturday) to rule ids.
    private(set) var ticks: [String: Set<String>]

    /// Where the three things above are kept.
    let defaults: UserDefaults
    /// Sends events to the website's analytics. nil sends nothing.
    let events: EventSender?
    /// Photos, shipped or downloaded. nil when only shipped photos are shown.
    let photos: PhotoCache?

    private let locationStore: LocationStore
    private let savedStore: SavedStore
    private let ticksStore: TicksStore

    init(data: AppData, tables: Tables, defaults: UserDefaults, now: Date = .now, events: EventSender? = nil,
         photos: PhotoCache? = nil) {
        self.data = data
        self.tables = tables
        self.defaults = defaults
        self.events = events
        self.photos = photos
        locationStore = LocationStore(defaults: defaults)
        savedStore = SavedStore(defaults: defaults)
        ticksStore = TicksStore(defaults: defaults)
        location = locationStore.load(tables: tables)
        saved = savedStore.load()
        ticks = ticksStore.load(now: now)
    }

    // MARK: Data

    /// Switches to newer plant and seller data, downloaded and checked while the app was open.
    func useData(_ newData: AppData) {
        data = newData
    }

    // MARK: Events

    /// Sends an event to the website's analytics, if it is on the website's list. It never waits for the network.
    func track(_ event: String, _ props: [String: EventValue] = [:]) {
        guard let events else { return }
        Task { await events.send(event, props) }
    }

    // MARK: Location

    /// Sets the county. The position is rounded to two decimal places (about 1 km) here as well as where it is kept,
    /// so what the app shows now matches what it will load next time.
    ///
    /// `method` is how the person chose it ("zip", "place" or "gps"), as the website reports it. When given, the
    /// choice is sent as a location_set event with the county name, ecoregion and state, never the ZIP code or the
    /// position. A county loaded from the phone at launch passes no method and sends nothing, as on the website.
    func setLocation(_ newLocation: Location?, method: String? = nil) {
        location = newLocation.map(LocationStore.rounded)
        locationStore.save(newLocation)
        if let method, let newLocation {
            track("location_set", [
                "method": .string(method),
                "county": .string("\(newLocation.countyName), \(newLocation.stateUsps)"),
                "ecoregion": .string(newLocation.ecoregion),
                "state": .string(newLocation.stateUsps),
            ])
        }
    }

    // MARK: Saved plants

    func isSaved(_ plantId: String) -> Bool { saved.contains(plantId) }

    /// Saves the plant at the end of the list, or removes it if it is already saved.
    func toggleSaved(_ plantId: String) {
        if let i = saved.firstIndex(of: plantId) {
            saved.remove(at: i)
        } else {
            saved.append(plantId)
        }
        savedStore.save(saved)
    }

    // MARK: Ticked jobs

    func isTicked(_ ruleId: String, now: Date = .now) -> Bool {
        ticks[TicksStore.weekId(for: now)]?.contains(ruleId) ?? false
    }

    /// Ticks the job off for the week `now` falls in, or unticks it.
    func toggleTick(_ ruleId: String, now: Date = .now) {
        let week = TicksStore.weekId(for: now)
        var rules = ticks[week] ?? []
        if rules.contains(ruleId) { rules.remove(ruleId) } else { rules.insert(ruleId) }
        ticks[week] = rules.isEmpty ? nil : rules
        ticksStore.save(ticks)
    }
}

extension AppModel {
    /// The data folder the app ships with: bundle/ inside the app, holding data/, tables/, images/ and fonts/.
    static var bundleURL: URL {
        Bundle.main.bundleURL.appendingPathComponent("bundle", isDirectory: true)
    }

    /// The model for this launch: the newest plant data on the phone (downloaded by an earlier check, or else the data
    /// the app ships with), what the phone has kept, and, when `services` is given, the event sender and photo store.
    ///
    /// In a Debug build only, the launch argument `-uiTestLocation <county code>` (used by the screen tests) makes
    /// the app keep nothing between launches and start with that county already chosen, found through the same
    /// county lookup the rest of the app uses, as if it had been typed as a ZIP code. A Release build never reads it.
    static func forLaunch(arguments: [String] = ProcessInfo.processInfo.arguments, services: ServiceSettings? = nil,
                          isOnline: @escaping @Sendable () async -> Bool = { false }) -> AppModel {
        let shippedData = bundleURL.appendingPathComponent("data", isDirectory: true)
        let data: AppData
        let tables: Tables
        do {
            let shipped = try JSONDecoder().decode(DataVersion.self,
                                                   from: Data(contentsOf: shippedData.appendingPathComponent("version.json")))
            if let services, let downloaded = DataUpdater.saved(in: services.folders.data, newerThan: shipped) {
                data = downloaded
            } else {
                data = try AppData.load(from: shippedData)
            }
            tables = try Tables.load(from: bundleURL.appendingPathComponent("tables", isDirectory: true))
        } catch {
            fatalError("The plant data the app ships with did not load: \(error)")
        }

        var defaults = UserDefaults.standard
        #if DEBUG
        let county = testCounty(in: arguments)
        if county != nil { defaults = testDefaults() }
        #endif

        var events: EventSender?
        var photos: PhotoCache?
        if let services {
            events = EventSender(site: services.site, apiKey: services.apiKey,
                                 distinctId: EventSender.distinctId(in: defaults),
                                 queueFile: services.folders.eventQueue, isOnline: isOnline)
            photos = PhotoCache(site: services.site, bundle: bundleURL, folder: services.folders.photos)
        }
        let model = AppModel(data: data, tables: tables, defaults: defaults, events: events, photos: photos)

        #if DEBUG
        if let county,
           case .found(let location) = LocationLookup.resolve(countyFips: county, lat: nil, lon: nil, tables: tables) {
            model.setLocation(location, method: "zip")
        }
        #endif
        return model
    }

    #if DEBUG
    /// The screen tests' store: a throwaway one, cleared at launch.
    private static func testDefaults() -> UserDefaults {
        let suite = "hereabouts.uitest"
        let defaults = UserDefaults(suiteName: suite) ?? .standard
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }

    /// The county code after `-uiTestLocation`, kept as text so a leading zero stays.
    nonisolated static func testCounty(in arguments: [String]) -> String? {
        guard let i = arguments.firstIndex(of: "-uiTestLocation"), i + 1 < arguments.count else { return nil }
        return arguments[i + 1]
    }
    #endif
}
