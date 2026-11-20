// Where the app's network services talk to and where they keep their files, decided once at launch.
import Foundation

/// The folders the app keeps its own files in. Nothing here is backed up: it can all be downloaded again.
struct AppFolders: Sendable {
    /// Application Support: the downloaded plant data and the events waiting to be sent.
    let support: URL
    /// Caches: photos of plants the app did not ship with. The phone may clear these when space runs low.
    let caches: URL

    /// The newest accepted plant data: version.json, plants.json, vendors.json and plant-regions.json.
    var data: URL { support.appendingPathComponent("data", isDirectory: true) }
    /// Events not yet sent.
    var eventQueue: URL { support.appendingPathComponent("events.json") }
    /// Downloaded photos, by the same path as on the website (images/<name>).
    var photos: URL { caches.appendingPathComponent("photos", isDirectory: true) }

    /// Makes both folders and keeps them out of the phone's backups.
    func prepare() {
        for var folder in [support, caches] {
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? folder.setResourceValues(values)
        }
    }

    /// Deletes both folders and everything in them.
    func removeAll() {
        for folder in [support, caches] { try? FileManager.default.removeItem(at: folder) }
    }
}

struct ServiceSettings: Sendable {
    /// The website: data from /app-data/v1/, events to /ingest/batch/, photos from /images/.
    static let website = URL(string: "https://hereabouts.garden")!

    /// The address the services talk to, or nil for no network at all.
    let site: URL?
    let folders: AppFolders
    /// The website's public PostHog key, which can only send events (bundle/tables/site.json).
    let apiKey: String

    /// The settings for this launch: the website, and the app's own folders.
    ///
    /// In a Debug build only, tests change this. Under a screen test (`-uiTestLocation`) or the in-app unit tests, the
    /// services reach no address at all unless `-hereaboutsBaseURL <address>` names one, such as a stand-in server on
    /// 127.0.0.1, and they keep their files in folders of their own. Those folders are emptied at launch unless
    /// `-uiTestKeepFiles` is given, which a test uses to check what a relaunch finds. A Release build reads none of this.
    @MainActor
    static func forLaunch(arguments: [String] = ProcessInfo.processInfo.arguments) -> ServiceSettings {
        let files = FileManager.default
        let support = files.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Hereabouts", isDirectory: true)
        let caches = files.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Hereabouts", isDirectory: true)
        let apiKey = (try? JSONDecoder().decode(SiteFile.self, from: Data(contentsOf: AppModel.bundleURL
            .appendingPathComponent("tables/site.json"))).posthogKey) ?? ""

        #if DEBUG
        if underTest(arguments: arguments) {
            let folders = AppFolders(support: support.appendingPathComponent("test", isDirectory: true),
                                     caches: caches.appendingPathComponent("test", isDirectory: true))
            if !arguments.contains("-uiTestKeepFiles") { folders.removeAll() }
            folders.prepare()
            return ServiceSettings(site: testSite(in: arguments), folders: folders, apiKey: apiKey)
        }
        #endif
        let folders = AppFolders(support: support, caches: caches)
        folders.prepare()
        return ServiceSettings(site: website, folders: folders, apiKey: apiKey)
    }

    /// tables/site.json in the bundle.
    private struct SiteFile: Decodable {
        let posthogKey: String
    }

    #if DEBUG
    /// Whether a test is running the app: a screen test launched it with a county, or the unit tests run inside it.
    private static func underTest(arguments: [String]) -> Bool {
        arguments.contains("-uiTestLocation") || NSClassFromString("XCTestCase") != nil
            || ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
    }

    /// The address after `-hereaboutsBaseURL`, when it is an http or https address.
    static func testSite(in arguments: [String]) -> URL? {
        guard let i = arguments.firstIndex(of: "-hereaboutsBaseURL"), i + 1 < arguments.count,
              let url = URL(string: arguments[i + 1]), url.scheme == "http" || url.scheme == "https" else { return nil }
        return url
    }
    #endif
}
