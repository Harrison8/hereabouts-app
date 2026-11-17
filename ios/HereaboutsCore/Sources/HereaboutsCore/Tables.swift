// The six lookup tables location finding uses, read from the bundle's tables/ folder. They are the website's own
// files, copied as they are by `npm run app:bundle`.
import Foundation

/// A row of zip-county.json: the ZIP's main county and a point inside the ZIP area. In the file: [countyFips, lat, lon].
public struct ZipRow: Decodable, Hashable, Sendable {
    public let countyFips: String
    public let lat: Double
    public let lon: Double

    public init(countyFips: String, lat: Double, lon: Double) {
        self.countyFips = countyFips
        self.lat = lat
        self.lon = lon
    }

    public init(from decoder: any Decoder) throws {
        var c = try decoder.unkeyedContainer()
        countyFips = try c.decode(String.self)
        lat = try c.decode(Double.self)
        lon = try c.decode(Double.self)
    }
}

/// A row of place-county.json: one city or county with this name. In the file: [state, countyFips, lat, lon] or
/// [state, countyFips, lat, lon, 1]. The trailing 1 (`isDefault`) on a name's first row marks the clear choice when
/// the visitor gives no state.
public struct PlaceRow: Decodable, Hashable, Sendable {
    /// Two-letter postal code, like "OH".
    public let state: String
    public let countyFips: String
    public let lat: Double
    public let lon: Double
    public let isDefault: Bool

    public init(state: String, countyFips: String, lat: Double, lon: Double, isDefault: Bool = false) {
        self.state = state
        self.countyFips = countyFips
        self.lat = lat
        self.lon = lon
        self.isDefault = isDefault
    }

    public init(from decoder: any Decoder) throws {
        var c = try decoder.unkeyedContainer()
        state = try c.decode(String.self)
        countyFips = try c.decode(String.self)
        lat = try c.decode(Double.self)
        lon = try c.decode(Double.self)
        // The website tests `row[4] === 1`, so only the number 1 counts.
        isDefault = c.isAtEnd ? false : (try? c.decode(Double.self)) == 1
    }
}

/// A row of states.json (the website's STATE_NAMES): the states the site covers, by two-digit state FIPS code.
/// In the file: [name, postal code].
public struct StateName: Decodable, Hashable, Sendable {
    public let name: String
    public let usps: String

    public init(name: String, usps: String) {
        self.name = name
        self.usps = usps
    }

    public init(from decoder: any Decoder) throws {
        var c = try decoder.unkeyedContainer()
        name = try c.decode(String.self)
        usps = try c.decode(String.self)
    }
}

public struct Tables: Sendable {
    /// zip-county.json, by five-digit ZIP.
    public let zipCounty: [String: ZipRow]
    /// place-county.json, by place name in the form `LocationLookup.normalizePlaceName` gives. Biggest place first.
    public let placeCounty: [String: [PlaceRow]]
    /// county-names.json: county FIPS to name, like "Jefferson County".
    public let countyNames: [String: String]
    /// county-ecoregion.json: county FIPS to its EPA level III ecoregion code.
    public let countyEcoregion: [String: String]
    /// states.json: the lower 48 states and DC, by state FIPS code.
    public let states: [String: StateName]
    /// ecoregion-names.json: ecoregion code to name.
    public let ecoregionNames: [String: String]
    /// Every ZIP in `zipCounty`, in ascending order, so `LocationLookup.nearest` breaks ties the website's way.
    public let sortedZips: [String]
    /// Postal codes of the states in `states`, plus DC.
    public let coveredStates: Set<String>

    public init(zipCounty: [String: ZipRow], placeCounty: [String: [PlaceRow]], countyNames: [String: String],
                countyEcoregion: [String: String], states: [String: StateName], ecoregionNames: [String: String]) {
        self.zipCounty = zipCounty
        self.placeCounty = placeCounty
        self.countyNames = countyNames
        self.countyEcoregion = countyEcoregion
        self.states = states
        self.ecoregionNames = ecoregionNames
        self.sortedZips = zipCounty.keys.sorted()
        self.coveredStates = Set(states.values.map { $0.usps }).union(["DC"])
    }

    /// Reads the six tables from `tablesDir` (the bundle's tables/ folder).
    public static func load(from tablesDir: URL) throws -> Tables {
        let decoder = JSONDecoder()
        func read<T: Decodable>(_ type: T.Type, _ name: String) throws -> T {
            try decoder.decode(type, from: Data(contentsOf: tablesDir.appendingPathComponent(name)))
        }
        return Tables(
            zipCounty: try read([String: ZipRow].self, "zip-county.json"),
            placeCounty: try read([String: [PlaceRow]].self, "place-county.json"),
            countyNames: try read([String: String].self, "county-names.json"),
            countyEcoregion: try read([String: String].self, "county-ecoregion.json"),
            states: try read([String: StateName].self, "states.json"),
            ecoregionNames: try read([String: String].self, "ecoregion-names.json")
        )
    }
}
