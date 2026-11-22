// What the whole app shares: the plant and seller data, the location tables, the care rules and each county's timing
// dates, the three things kept on the phone (the county, the saved plants, this week's ticked jobs), the open tab,
// and the event sender, photo store and reminder planner. Screens read it from the environment.
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
    /// Whether the person wants the Saturday reminder. On until they turn it off in Settings.
    var remindersOn: Bool {
        didSet {
            defaults.set(remindersOn, forKey: Self.remindersKey)
            rescheduleReminders()
        }
    }
    nonisolated static let remindersKey = "hereabouts.reminders"

    /// Where the three things above are kept.
    let defaults: UserDefaults
    /// Sends events to the website's analytics. nil sends nothing.
    let events: EventSender?
    /// Photos, shipped or downloaded. nil when only shipped photos are shown.
    let photos: PhotoCache?
    /// The care rules and each county's timing dates, for This week and the reminders.
    let care: CareTables
    /// Plans the Saturday reminders with the phone. nil plans none.
    let reminders: ReminderScheduler?
    /// The tab on screen. A tapped reminder opens the Garden tab.
    var selectedTab: RootTabs.TabID = .plants
    /// The plant pages opened inside the Garden tab, by plant id. A tapped reminder clears them.
    var gardenPath: [String] = []
    /// The job whose sheet is open in the Garden tab. A tapped reminder closes it.
    var openJob: WeekRow?
    /// Goes up by one each time every open sheet should close (a tapped reminder). Screens that show a sheet watch it.
    private(set) var closeSheetsCount = 0
    /// What the phone said about the app's notifications when last asked: read when the app comes to the front and
    /// after the person answers the question. nil until first read, or when no reminders are planned.
    private(set) var notificationPermission: NotificationPermission?
    /// The time the screen tests set. nil everywhere else, so `now` is the phone's clock.
    private let fixedNow: Date?
    /// The phone's time when the app last came to the front. Kept here, rather than read each time, so screens that
    /// show This week are drawn again when it changes, and a new week shows after the app comes back on a Saturday.
    private(set) var today: Date = .now

    /// The time This week, the ticks and the reminders go by.
    var now: Date { fixedNow ?? today }

    /// True when a reminder will come on Saturday: the switch in Settings is on and the phone has not refused the
    /// app's notifications.
    var remindersWillCome: Bool {
        remindersOn && (notificationPermission == .allowed || notificationPermission == .notAsked)
    }

    private let locationStore: LocationStore
    private let savedStore: SavedStore
    private let ticksStore: TicksStore

    init(data: AppData, tables: Tables, defaults: UserDefaults, now: Date = .now, events: EventSender? = nil,
         photos: PhotoCache? = nil, care: CareTables = .empty, reminders: ReminderScheduler? = nil, fixedNow: Date? = nil) {
        self.data = data
        self.tables = tables
        self.defaults = defaults
        self.events = events
        self.photos = photos
        self.care = care
        self.reminders = reminders
        self.fixedNow = fixedNow
        locationStore = LocationStore(defaults: defaults)
        savedStore = SavedStore(defaults: defaults)
        ticksStore = TicksStore(defaults: defaults)
        location = locationStore.load(tables: tables)
        saved = savedStore.load()
        ticks = ticksStore.load(now: fixedNow ?? now)
        remindersOn = defaults.object(forKey: Self.remindersKey) as? Bool ?? true
    }

    // MARK: Data

    /// Switches to newer plant and seller data, downloaded and checked while the app was open.
    func useData(_ newData: AppData) {
        data = newData
        rescheduleReminders()
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
    /// `method` is how the person chose it: the website's "zip", "city", "county" or "address", or "device" (the
    /// phone's location). When given, the choice is sent as a location_set event with the county name, ecoregion and state,
    /// never the ZIP code, the address or the position. A county loaded from the phone at launch passes no method and sends nothing, as on the website.
    func setLocation(_ newLocation: Location?, method: String? = nil) {
        location = newLocation.map(LocationStore.rounded)
        locationStore.save(newLocation)
        rescheduleReminders()
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
        rescheduleReminders()
    }

    /// What every save button does, in the list, on the plant page and in the Garden tab's Remove: saves or unsaves
    /// the plant and sends plant_saved or plant_unsaved. The first time a plant is saved while a county is set, the
    /// phone asks whether the app may send notifications, and the answer is sent as notifications_answer.
    func pressSave(_ plantId: String) {
        let wasSaved = isSaved(plantId)
        toggleSaved(plantId)
        track(wasSaved ? "plant_unsaved" : "plant_saved")
        if !wasSaved, location != nil, let reminders {
            Task {
                guard let granted = await reminders.askIfNotAsked() else { return }
                track("notifications_answer", ["granted": .bool(granted)])
                await readNotificationPermission()
                rescheduleReminders()
            }
        }
    }

    // MARK: Reminders

    /// The Saturday reminders for the saved plants and the county's timing dates: none when reminders are off or no
    /// county is chosen. Saved plants that are not native to the county still count.
    var plannedReminders: [PlannedReminder] {
        guard remindersOn, let location else { return [] }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        return Reminders.weekly(rules: care.rules, anchors: care.anchors[location.countyFips], savedIds: saved,
                                plants: data.plants, now: now, calendar: calendar).map { reminder in
            var at = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: reminder.at)
            at.calendar = calendar
            return PlannedReminder(id: "\(ReminderScheduler.idPrefix)\(reminder.id)", title: reminder.title,
                                   body: reminder.body, at: at)
        }
    }

    /// Plans the reminders again from what is saved now.
    func rescheduleReminders() {
        reminders?.reschedule(plannedReminders)
    }

    /// Asks the phone again whether the app may send notifications. The person can change it in the phone's Settings
    /// while the app is in the background.
    func readNotificationPermission() async {
        notificationPermission = await reminders?.centre.permission()
    }

    /// The app came to the front (and at launch): take the phone's time again, so This week moves on to a new week,
    /// read the notification permission again, and plan the reminders again.
    func cameToFront() {
        today = .now
        Task {
            await readNotificationPermission()
            rescheduleReminders()
        }
    }

    /// A reminder was tapped: close any open sheet, show This week at the top of the Garden tab, and send
    /// this_week_opened.
    func openedFromNotification() {
        openJob = nil
        gardenPath = []
        closeSheetsCount += 1
        selectedTab = .garden
        track("this_week_opened", ["from": .string("notification")])
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
    nonisolated static var bundleURL: URL {
        Bundle.main.bundleURL.appendingPathComponent("bundle", isDirectory: true)
    }

    /// The model for this launch: the newest plant data on the phone (downloaded by an earlier check, or else the data
    /// the app ships with), what the phone has kept, and, when `services` is given, the event sender and photo store.
    ///
    /// In a Debug build only, the launch argument `-uiTestLocation <county code>` (used by the screen tests) makes
    /// the app keep nothing between launches and start with that county already chosen, found through the same
    /// county lookup the rest of the app uses, as if it had been typed as a ZIP code. `-uiTestFirstOpen` makes it keep
    /// nothing between launches and start on the first-open screen. `-uiTestZip <ZIP code>` works like `-uiTestLocation`
    /// but finds the county and its position from the ZIP code, as typing it would, so the position used for nearby
    /// shops is set too. The Garden tests add three more: `-uiTestNow <YYYY-MM-DDTHH:mm>` fixes the phone time,
    /// `-uiTestSaved <id,id>` starts with those plants saved, and `-uiTestNotifications <allowed|willAllow|willDeny>`
    /// sets what the stand-in notification settings answer (with none, the person said no before). Their fourth,
    /// `-uiTestNotificationTap`, is read by the window's delegate, where a real reminder tap is first seen. A Release
    /// build reads none of these.
    static func forLaunch(arguments: [String] = ProcessInfo.processInfo.arguments, services: ServiceSettings? = nil,
                          isOnline: @escaping @Sendable () async -> Bool = { false }) -> AppModel {
        let shippedData = bundleURL.appendingPathComponent("data", isDirectory: true)
        let data: AppData
        let tables: Tables
        let care: CareTables
        do {
            let shipped = try JSONDecoder().decode(DataVersion.self,
                                                   from: Data(contentsOf: shippedData.appendingPathComponent("version.json")))
            if let services, let downloaded = DataUpdater.saved(in: services.folders.data, newerThan: shipped) {
                data = downloaded
            } else {
                data = try AppData.load(from: shippedData)
            }
            tables = try Tables.load(from: bundleURL.appendingPathComponent("tables", isDirectory: true))
            care = try CareTables.load(from: bundleURL.appendingPathComponent("tables", isDirectory: true))
        } catch {
            fatalError("The plant data the app ships with did not load: \(error)")
        }

        var defaults = UserDefaults.standard
        var fixedNow: Date?
        var centre: any NotificationCentre = SystemNotifications()
        #if DEBUG
        let county = testCounty(in: arguments)
        let zip = testValue(after: "-uiTestZip", in: arguments)
        let underTest = county != nil || zip != nil || arguments.contains("-uiTestFirstOpen")
        if underTest {
            defaults = testDefaults()
            centre = TestNotifications(setting: testValue(after: "-uiTestNotifications", in: arguments))
        }
        fixedNow = testValue(after: "-uiTestNow", in: arguments).flatMap(testDate)
        #endif

        var events: EventSender?
        var photos: PhotoCache?
        if let services {
            events = EventSender(site: services.site, apiKey: services.apiKey,
                                 distinctId: EventSender.distinctId(in: defaults),
                                 queueFile: services.folders.eventQueue, isOnline: isOnline)
            photos = PhotoCache(site: services.site, bundle: bundleURL, folder: services.folders.photos)
        }
        var reminders: ReminderScheduler?
        if services != nil {
            reminders = ReminderScheduler(centre: centre)
            #if DEBUG
            if underTest { reminders?.reportTo = ServiceSettings.testSite(in: arguments) }
            #endif
        }
        let model = AppModel(data: data, tables: tables, defaults: defaults, events: events, photos: photos, care: care,
                             reminders: reminders, fixedNow: fixedNow)

        #if DEBUG
        if let county,
           case .found(let location) = LocationLookup.resolve(countyFips: county, lat: nil, lon: nil, tables: tables) {
            model.setLocation(location, method: "zip")
        } else if let zip, case .found(let location) = LocationLookup.zip(zip, tables: tables) {
            model.setLocation(location, method: "zip")
        }
        if underTest, let ids = testValue(after: "-uiTestSaved", in: arguments) {
            for id in ids.split(separator: ",").map(String.init) where !model.isSaved(id) { model.toggleSaved(id) }
        }
        #endif
        // The waiting reminders are planned again at every launch, so a new week's always follow the newest data.
        model.rescheduleReminders()
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
        testValue(after: "-uiTestLocation", in: arguments)
    }

    /// "2027-11-18T09:00" as that time on the phone's clock, in the phone's time zone.
    private static func testDate(_ text: String) -> Date? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = .current
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm"
        return formatter.date(from: text)
    }

    /// The text after a launch argument, such as the ZIP code after `-uiTestZip`.
    nonisolated static func testValue(after name: String, in arguments: [String]) -> String? {
        guard let i = arguments.firstIndex(of: name), i + 1 < arguments.count else { return nil }
        return arguments[i + 1]
    }
    #endif
}

/// The care rules, each county's timing dates and each rule's sources, from tables/care-rules.json and
/// tables/care-anchors.json.
struct CareTables: Sendable {
    /// One source a rule's facts come from, shown under the job and opened in Safari.
    struct Source: Decodable, Hashable, Sendable {
        let title: String
        let url: String
    }

    let rules: [CareRule]
    /// Timing dates by five-digit county code.
    let anchors: [String: Anchors]
    /// Sources by rule id.
    let sources: [String: [Source]]

    /// No rules and no dates, so there are no jobs and no reminders.
    static let empty = CareTables(rules: [], anchors: [:], sources: [:])

    private struct SourcesFile: Decodable {
        struct Rule: Decodable {
            let id: String
            let sources: [Source]?
        }
        let rules: [Rule]
    }

    static func load(from tables: URL) throws -> CareTables {
        let rulesData = try Data(contentsOf: tables.appendingPathComponent("care-rules.json"))
        let anchorsData = try Data(contentsOf: tables.appendingPathComponent("care-anchors.json"))
        let file = try JSONDecoder().decode(SourcesFile.self, from: rulesData)
        var sources: [String: [Source]] = [:]
        for rule in file.rules { sources[rule.id] = rule.sources ?? [] }
        return CareTables(rules: try Care.rules(from: rulesData), anchors: try Care.anchorsByCounty(from: anchorsData),
                          sources: sources)
    }
}
