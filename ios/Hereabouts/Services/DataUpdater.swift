// The daily check for newer plant and seller data. At launch and whenever the app comes back to the front, if there is
// signal and the last check was a day ago or more, it asks the website for version.json; when that names data the app
// does not have, it downloads plants.json, vendors.json and plant-regions.json too. The set is used only if
// DataUpdate.accept passes it (right schema, not older, the fingerprint matches, every file reads). Then the app
// switches to it at once and keeps it in Application Support for the next launch. Anything else keeps the data the
// app has. Only data is downloaded, never code.
import Foundation
import HereaboutsCore

@MainActor
final class DataUpdater {
    /// Where the time of the last finished check is kept (UserDefaults).
    nonisolated static let lastCheckKey = "hereabouts.dataCheck"
    /// The four files, in the order they are downloaded.
    nonisolated static let fileNames = ["version.json", "plants.json", "vendors.json", "plant-regions.json"]

    private let model: AppModel
    private let site: URL?
    private let dataFolder: URL
    private let defaults: UserDefaults
    private let session: URLSession
    private let isOnline: @MainActor () -> Bool
    private let now: () -> Date
    private var checking = false

    /// `site` nil never checks. `dataFolder` is where an accepted set is kept.
    init(model: AppModel, site: URL?, dataFolder: URL, defaults: UserDefaults, session: URLSession = .shared,
         isOnline: @escaping @MainActor () -> Bool, now: @escaping () -> Date = { .now }) {
        self.model = model
        self.site = site
        self.dataFolder = dataFolder
        self.defaults = defaults
        self.session = session
        self.isOnline = isOnline
        self.now = now
    }

    /// Checks for newer data if a check is due and there is signal. A check that gets no answer (no signal on the
    /// way, a timeout) is not recorded, so the next launch or return to the app tries again.
    func checkIfDue() async {
        guard let site, !checking, isOnline(),
              DataUpdate.shouldCheck(last: defaults.object(forKey: Self.lastCheckKey) as? Date, now: now()) else { return }
        checking = true
        defer { checking = false }
        guard let folder = URL(string: "app-data/v1/", relativeTo: site)?.absoluteURL else { return }

        var files: [String: Data] = [:]
        for name in Self.fileNames {
            guard let answer = await fetch(folder.appendingPathComponent(name)) else { return }
            guard answer.status == 200 else { return finished() }
            let body = answer.body
            files[name] = body
            // Stop after version.json when it names the data the app already has or a schema it cannot read.
            if name == "version.json" {
                guard let next = try? JSONDecoder().decode(DataVersion.self, from: body), next.schema == 1,
                      next.dataHash != model.data.version.dataHash else { return finished() }
            }
        }
        finished()

        let downloaded = files
        let current = model.data.version
        let folderOnPhone = dataFolder
        let accepted = await Task.detached(priority: .utility) { () -> AppData? in
            guard let version = downloaded["version.json"], let plants = downloaded["plants.json"],
                  let vendors = downloaded["vendors.json"], let regions = downloaded["plant-regions.json"],
                  let data = DataUpdate.accept(version: version, plants: plants, vendors: vendors, regions: regions,
                                               current: current) else { return nil }
            // Kept for the next launch. If writing fails, the set is still used until then.
            try? DataUpdater.keep(downloaded, in: folderOnPhone)
            return data
        }.value
        if let accepted { model.useData(accepted) }
    }

    private func finished() {
        defaults.set(now(), forKey: Self.lastCheckKey)
    }

    /// The status and body, or nil when no answer came.
    private func fetch(_ url: URL) async -> (status: Int, body: Data)? {
        let request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        guard let answer = try? await session.data(for: request),
              let status = (answer.1 as? HTTPURLResponse)?.statusCode else { return nil }
        return (status, answer.0)
    }

    /// Writes the four files into `folder`, replacing what was there. They are written to a new folder first and then
    /// moved into place, so `folder` never holds a mix of two sets.
    nonisolated static func keep(_ files: [String: Data], in folder: URL) throws {
        let manager = FileManager.default
        let parent = folder.deletingLastPathComponent()
        try manager.createDirectory(at: parent, withIntermediateDirectories: true)
        let fresh = parent.appendingPathComponent("data-\(UUID().uuidString)", isDirectory: true)
        try manager.createDirectory(at: fresh, withIntermediateDirectories: false)
        do {
            for name in fileNames {
                guard let body = files[name] else { throw CocoaError(.fileNoSuchFile) }
                try body.write(to: fresh.appendingPathComponent(name))
            }
            try? manager.removeItem(at: folder)
            try manager.moveItem(at: fresh, to: folder)
        } catch {
            try? manager.removeItem(at: fresh)
            throw error
        }
    }

    /// The data kept by an earlier update, when it is still newer than `shipped` (the data the app ships with) and
    /// passes the same checks as a download. nil otherwise, for example after an app update that ships newer data.
    nonisolated static func saved(in folder: URL, newerThan shipped: DataVersion) -> AppData? {
        func read(_ name: String) -> Data? { try? Data(contentsOf: folder.appendingPathComponent(name)) }
        guard let version = read("version.json"), let plants = read("plants.json"), let vendors = read("vendors.json"),
              let regions = read("plant-regions.json") else { return nil }
        return DataUpdate.accept(version: version, plants: plants, vendors: vendors, regions: regions, current: shipped)
    }
}
