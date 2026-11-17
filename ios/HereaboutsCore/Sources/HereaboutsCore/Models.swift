// The plant and seller records the app reads from the bundle's data/ folder (plants.json, vendors.json,
// plant-regions.json, version.json). Each type holds the fields the website reads; any other field in the
// files is ignored, so new fields in the data never break an older app.
import Foundation

/// One photo of a plant in one phase of its year. `url` is a path inside the bundle, like "images/quercus-alba-green.jpg".
public struct Photo: Decodable, Hashable, Sendable {
    public let url: String
    public let alt: String
    public let credit: String

    public init(url: String, alt: String, credit: String) {
        self.url = url
        self.alt = alt
        self.credit = credit
    }
}

/// How long a plant takes to reach full size, and what to know while it does.
public struct Maturity: Decodable, Hashable, Sendable {
    public let years: String?
    public let notes: String?

    public init(years: String?, notes: String?) {
        self.years = years
        self.notes = notes
    }
}

/// One seller's listing for one plant (an entry of a plant's `vendors` list).
/// `shipsTo`, when present, replaces the seller's own shipping area for this plant.
/// `stock` is part of the agreed shape but the data has no such field today (it has `inStock`), so it is always nil.
public struct VendorEntry: Decodable, Hashable, Sendable {
    public let name: String
    public let url: String?
    public let price: String?
    public let size: String?
    public let stock: String?
    public let shipsTo: [String]?

    public init(name: String, url: String? = nil, price: String? = nil, size: String? = nil, stock: String? = nil, shipsTo: [String]? = nil) {
        self.name = name
        self.url = url
        self.price = price
        self.size = size
        self.stock = stock
        self.shipsTo = shipsTo
    }
}

/// A plant, as plants.json describes it.
public struct Plant: Decodable, Identifiable, Hashable, Sendable {
    public let id: String
    public let common: String
    public let sci: String
    public let category: String
    public let usda: String?
    /// Feet: [smallest, largest].
    public let height: [Double]
    public let spread: [Double]
    public let sun: [String]
    public let moisture: [String]
    public let bloomColor: [String]
    public let wildlife: [String]
    public let zones: [String]
    /// The plant's look in each month, January first: "dormant", "green", "bloom", "fall" and so on.
    public let months: [String]
    /// 1 is January.
    public let bloomMonths: [Int]
    public let fallColor: String?
    public let evergreen: Bool?
    public let notes: String?
    /// Photo for each phase name. Empty when the plant has no photos yet (the file leaves the field out).
    public let phasePhotos: [String: Photo]
    public let maturity: Maturity?
    public let vendors: [VendorEntry]

    enum CodingKeys: String, CodingKey {
        case id, common, sci, category, usda, height, spread, sun, moisture, bloomColor, wildlife, zones, months
        case bloomMonths, fallColor, evergreen, notes, phasePhotos, maturity, vendors
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        common = try c.decode(String.self, forKey: .common)
        sci = try c.decode(String.self, forKey: .sci)
        category = try c.decode(String.self, forKey: .category)
        usda = try c.decodeIfPresent(String.self, forKey: .usda)
        height = try c.decodeIfPresent([Double].self, forKey: .height) ?? []
        spread = try c.decodeIfPresent([Double].self, forKey: .spread) ?? []
        sun = try c.decodeIfPresent([String].self, forKey: .sun) ?? []
        moisture = try c.decodeIfPresent([String].self, forKey: .moisture) ?? []
        bloomColor = try c.decodeIfPresent([String].self, forKey: .bloomColor) ?? []
        wildlife = try c.decodeIfPresent([String].self, forKey: .wildlife) ?? []
        zones = try c.decodeIfPresent([String].self, forKey: .zones) ?? []
        months = try c.decodeIfPresent([String].self, forKey: .months) ?? []
        bloomMonths = try c.decodeIfPresent([Int].self, forKey: .bloomMonths) ?? []
        fallColor = try c.decodeIfPresent(String.self, forKey: .fallColor)
        evergreen = try c.decodeIfPresent(Bool.self, forKey: .evergreen)
        notes = try c.decodeIfPresent(String.self, forKey: .notes)
        phasePhotos = try c.decodeIfPresent([String: Photo].self, forKey: .phasePhotos) ?? [:]
        maturity = try c.decodeIfPresent(Maturity.self, forKey: .maturity)
        vendors = try c.decodeIfPresent([VendorEntry].self, forKey: .vendors) ?? []
    }
}

/// Where a seller ships: the lower 48 states (the file says "contiguous-48"), a list of states, or the lower 48
/// except some states (the file says { "allExcept": [...] }). Any other shape counts as shipping nowhere, as on
/// the website, where unknown shipping counts as no.
public enum ShipsTo: Decodable, Hashable, Sendable {
    case contiguous48
    case states([String])
    case allExcept([String])

    private struct AllExcept: Decodable {
        let allExcept: [String]
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.singleValueContainer()
        if let text = try? c.decode(String.self) {
            self = text == "contiguous-48" ? .contiguous48 : .states([])
        } else if let list = try? c.decode([String].self) {
            self = .states(list)
        } else if let object = try? c.decode(AllExcept.self) {
            self = .allExcept(object.allExcept)
        } else {
            self = .states([])
        }
    }
}

/// A seller, as vendors.json describes it (keyed by `name`).
public struct Vendor: Decodable, Hashable, Sendable {
    public let name: String
    public let short: String?
    public let location: String?
    /// [lat, lon] of the storefront, when it has one.
    public let coords: [Double]?
    public let storefront: Bool?
    public let affiliate: Bool?
    public let shipsTo: ShipsTo?
    /// Plant categories the seller never ships, like "tree".
    public let shipsExcept: [String]?

    public init(name: String, short: String? = nil, location: String? = nil, coords: [Double]? = nil, storefront: Bool? = nil,
                affiliate: Bool? = nil, shipsTo: ShipsTo? = nil, shipsExcept: [String]? = nil) {
        self.name = name
        self.short = short
        self.location = location
        self.coords = coords
        self.storefront = storefront
        self.affiliate = affiliate
        self.shipsTo = shipsTo
        self.shipsExcept = shipsExcept
    }
}

/// Where a plant is native: the EPA level III ecoregion codes it is native to (`eco`), and the two-digit state
/// codes (state FIPS) where it is flagged as introduced (`intro`).
public struct PlantGeo: Decodable, Hashable, Sendable {
    public let eco: [String]
    public let intro: [String]

    public init(eco: [String], intro: [String]) {
        self.eco = eco
        self.intro = intro
    }
}

/// version.json: which data the bundle holds.
public struct DataVersion: Codable, Hashable, Sendable {
    public let schema: Int
    public let dataDate: String
    public let dataHash: String

    public init(schema: Int, dataDate: String, dataHash: String) {
        self.schema = schema
        self.dataDate = dataDate
        self.dataHash = dataHash
    }
}

/// A county the visitor is in, with its ecoregion. The same fields as the website's Location object.
public struct Location: Codable, Equatable, Hashable, Sendable {
    /// Five-digit county FIPS code, like "21111".
    public let countyFips: String
    public let countyName: String
    /// Two-digit state FIPS code, like "21".
    public let stateFips: String
    public let stateName: String
    /// Two-letter postal code, like "KY".
    public let stateUsps: String
    /// EPA level III ecoregion code, like "71".
    public let ecoregion: String
    public let lat: Double?
    public let lon: Double?

    public init(countyFips: String, countyName: String, stateFips: String, stateName: String, stateUsps: String,
                ecoregion: String, lat: Double?, lon: Double?) {
        self.countyFips = countyFips
        self.countyName = countyName
        self.stateFips = stateFips
        self.stateName = stateName
        self.stateUsps = stateUsps
        self.ecoregion = ecoregion
        self.lat = lat
        self.lon = lon
    }
}
