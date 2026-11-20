// Plant data sets for the tests: a small newer set the app should take, and a broken one it must ignore. Each set has
// a correct version.json (schema 1, a later date, the SHA-256 of its three files), so the broken one gets past the
// date and fingerprint checks and fails only when its plants are read.
import CryptoKit
import Foundation

struct StubData {
    let version: Data
    let plants: Data
    let vendors: Data
    let regions: Data

    /// The four files by the path the app asks for.
    var files: [(path: String, body: Data)] {
        [("/app-data/v1/version.json", version), ("/app-data/v1/plants.json", plants),
         ("/app-data/v1/vendors.json", vendors), ("/app-data/v1/plant-regions.json", regions)]
    }

    /// A set dated far after the data the app ships with, so it always counts as newer.
    static let laterDate = "2099-01-01T00:00:00Z"

    /// Two plants the app does not ship with, named so a screen can show which set is in use.
    static let good = make(plants: """
        [{"id":"test-plant-one","common":"Test Plant One","sci":"Testus unus","category":"perennial",
          "vendors":[{"name":"Test Seller","shipsTo":["VA"]}]},
         {"id":"test-plant-two","common":"Test Plant Two","sci":"Testus duo","category":"shrub",
          "phasePhotos":{"green":{"url":"images/test-plant-two-green.jpg","alt":"Test","credit":"Test"}}}]
        """)

    /// One plant whose seller entry gives `shipsTo` as text instead of a list. The app cannot read that plants.json,
    /// so the whole set must be refused and the app must stay on the data it had.
    static let unreadable = make(plants: """
        [{"id":"bad-data-plant","common":"Bad Data Plant","sci":"Malus datus","category":"perennial",
          "vendors":[{"name":"Test Seller","shipsTo":"VA"}]}]
        """)

    static func make(plants: String) -> StubData {
        let plantsData = Data(plants.utf8)
        let vendors = Data(#"{"Test Seller":{"name":"Test Seller","shipsTo":"contiguous-48"}}"#.utf8)
        let regions = Data("""
            {"schema":1,"plants":{"test-plant-one":{"eco":["45"],"intro":[]},"test-plant-two":{"eco":["45"],"intro":[]},
            "bad-data-plant":{"eco":["45"],"intro":[]}},"served":["45"]}
            """.utf8)
        var joined = Data()
        joined.append(plantsData)
        joined.append(vendors)
        joined.append(regions)
        let hash = SHA256.hash(data: joined).map { String(format: "%02x", $0) }.joined()
        let version = Data(#"{"schema":1,"dataDate":"\#(laterDate)","dataHash":"\#(hash)"}"#.utf8)
        return StubData(version: version, plants: plantsData, vendors: vendors, regions: regions)
    }

    /// Serves the set's four files from `server`.
    func serve(from server: StubServer) {
        for file in files { server.serve(file.path, file.body) }
    }
}
