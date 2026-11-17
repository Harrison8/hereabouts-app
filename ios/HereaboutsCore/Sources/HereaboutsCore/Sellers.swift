// Which sellers can sell a plant to a visitor, ported line by line from the website's sellers.js. A seller shows
// only if it has a shop within 75 miles of the visitor, or it ships that kind of plant to the visitor's state.
//
// Task 5 (the plant list) added `group`, which the buy filter and the two seller sorts need. Task 6 added the rest
// of sellers.js (shipsSummary, distanceMi) and the "Where to buy" rows, ported from buyRowsView in index.html.
// The links in those rows come from Links.swift.
import Foundation

/// One seller's listing for a plant, as the visitor sees it. `miles` is the straight-line distance to the seller's
/// shop, or nil when there is no location, the location has no point, or the seller has no coordinates.
public struct SellerRow: Hashable, Sendable {
    public let entry: VendorEntry
    public let vendor: Vendor
    public var miles: Double?

    public init(entry: VendorEntry, vendor: Vendor, miles: Double?) {
        self.entry = entry
        self.vendor = vendor
        self.miles = miles
    }
}

public enum Sellers {
    /// LOCAL_RADIUS_MI: a shop this close counts as nearby.
    public static let localRadiusMi = 75.0

    /// contiguousUsps(): the postal codes of the lower 48 states and DC. sellers.js reads them from STATE_NAMES in
    /// plant-geo.js (the same 49 codes as the bundle's tables/states.json); here they are location.js's states
    /// without the ones outside the lower 48.
    static let contiguousUsps: Set<String> =
        Set(LocationLookup.usStates.map { $0.code }).subtracting(LocationLookup.outsideUS48States)

    /// shipsToState: true if the seller ships this kind of plant to this state. Unknown shipping counts as no.
    /// When the plant's own listing has a list of states, that list decides instead of the seller's area.
    public static func shipsToState(_ vendor: Vendor, _ stateUsps: String, category: String, entry: VendorEntry?) -> Bool {
        if stateUsps.isEmpty { return false }
        if let except = vendor.shipsExcept, except.contains(category) { return false }
        if let list = entry?.shipsTo { return list.contains(stateUsps) }
        switch vendor.shipsTo {
        case .contiguous48?: return contiguousUsps.contains(stateUsps)
        case .states(let list)?: return list.contains(stateUsps)
        case .allExcept(let list)?: return contiguousUsps.contains(stateUsps) && !list.contains(stateUsps)
        case nil: return false
        }
    }

    /// groupSellers: splits a plant's sellers into the groups a visitor sees. `nearby` is shops within 75 miles,
    /// closest first; `ships` is the sellers that ship here, the ones that pay us first; `all` is nearby then ships.
    /// With no location, every seller is in `all`, in data order, and the two groups are empty.
    public static func group(_ p: Plant, _ loc: Location?, vendors: [String: Vendor]) -> (nearby: [SellerRow], ships: [SellerRow], all: [SellerRow]) {
        var rows = p.vendors.map { SellerRow(entry: $0, vendor: vendors[$0.name] ?? Vendor(name: $0.name), miles: nil) }
        guard let loc else { return (nearby: [], ships: [], all: rows) }
        // Number.isFinite(loc.lat) && Number.isFinite(loc.lon)
        let point: (lat: Double, lon: Double)? = {
            guard let lat = loc.lat, let lon = loc.lon, lat.isFinite, lon.isFinite else { return nil }
            return (lat: lat, lon: lon)
        }()
        var nearby: [SellerRow] = []
        var rest: [SellerRow] = []
        for i in rows.indices {
            if let point, let c = rows[i].vendor.coords, c.count == 2 {
                let d = LocationLookup.distanceMi(point, (lat: c[0], lon: c[1]))
                if d.isFinite { rows[i].miles = d }
            }
            if rows[i].vendor.storefront == true, let miles = rows[i].miles, miles <= localRadiusMi {
                nearby.append(rows[i])
            } else {
                rest.append(rows[i])
            }
        }
        // nearby.sort((a, b) => a.miles - b.miles): a stable sort, so sellers the same distance away keep data order.
        nearby = nearby.enumerated().sorted { x, y in
            let a = x.element.miles ?? 0
            let b = y.element.miles ?? 0
            return a != b ? a < b : x.offset < y.offset
        }.map { $0.element }
        let ships = rest.filter { shipsToState($0.vendor, loc.stateUsps, category: p.category, entry: $0.entry) }
        let affiliateFirst = ships.filter { $0.vendor.affiliate == true } + ships.filter { $0.vendor.affiliate != true }
        return (nearby: nearby, ships: affiliateFirst, all: nearby + affiliateFirst)
    }

    /// distanceMi: straight-line miles between two (lat, lon) points. The haversine itself is LocationLookup's.
    public static func distanceMi(_ a: (Double, Double), _ b: (Double, Double)) -> Double {
        LocationLookup.distanceMi((lat: a.0, lon: a.1), (lat: b.0, lon: b.1))
    }

    /// joinStates: "KY, OH and TN", a short list joined the way a person writes it.
    static func joinStates(_ codes: [String]) -> String {
        // [...codes].sort(): JavaScript sorts by UTF-16 code unit; for two-letter postal codes that is plain byte order.
        let list = codes.sorted { Array($0.utf16).lexicographicallyPrecedes(Array($1.utf16)) }
        if list.count < 2 { return list.joined() }
        return list.dropLast().joined(separator: ", ") + " and " + list[list.count - 1]
    }

    /// shipsSummary: where a seller sends this kind of plant, for a visitor who has not given a location yet:
    /// "Ships to the lower 48 states", "Ships to the lower 48 except CA", "Ships to KY, OH and TN", "Ships to 35 states".
    /// A seller with a shop adds " · Shop in Goshen, KY"; one that does not ship this kind of plant but has a shop says
    /// "Pick up only · Goshen, KY". Empty when nothing is known. When the plant's own listing has a list of states,
    /// that list is used instead of the seller's area (an empty list too).
    public static func shipsSummary(_ v: Vendor, category: String, entry: VendorEntry?) -> String {
        // `vendor.storefront && vendor.location ? vendor.location : ''`: an empty place counts as none.
        let shop = v.storefront == true ? (v.location ?? "") : ""
        let excluded = v.shipsExcept?.contains(category) ?? false
        // `entry && Array.isArray(entry.shipsTo) ? entry.shipsTo : vendor.shipsTo`
        let to: ShipsTo?
        if let list = entry?.shipsTo {
            to = .states(list)
        } else {
            to = v.shipsTo
        }
        var ships = ""
        if !excluded {
            switch to {
            case .contiguous48?:
                ships = "Ships to the lower 48 states"
            case .allExcept(let list)?:
                ships = list.isEmpty ? "Ships to the lower 48 states" : "Ships to the lower 48 except \(joinStates(list))"
            case .states(let list)? where !list.isEmpty:
                ships = list.count <= 10 ? "Ships to \(joinStates(list))" : "Ships to \(list.count) states"
            default:
                // An empty list, an unknown shape or no shipping area: nothing to say about shipping.
                break
            }
        }
        if ships.isEmpty { return shop.isEmpty ? "" : "Pick up only \u{B7} \(shop)" }
        return shop.isEmpty ? ships : "\(ships) \u{B7} Shop in \(shop)"
    }

    /// buyRowsView in index.html: the sellers a visitor can use for one plant, ready to draw. `channel` is .app in the
    /// phone app, which tags affiliate links with "__app"; the site's name in the links is always hereabouts.garden.
    public static func buyView(_ p: Plant, _ loc: Location?, channel: Channel, data: AppData) -> BuyView {
        let groups = group(p, loc, vendors: data.vendors)
        // `ctx.ecoregion = loc ? loc.ecoregion : null`
        let ecoregion = loc?.ecoregion
        var rows: [BuyRow] = []
        for (i, r) in groups.all.enumerated() {
            // nearbySet.has(r): `all` is nearby then ships, so the first nearby.count rows are the nearby ones.
            let near = i < groups.nearby.count
            // Math.round(r.miles): halves round up. A nearby row always has miles.
            let miles: Int? = near ? Int(JSText.mathRound(r.miles ?? 0)) : nil
            let label: String?
            if loc == nil {
                label = nil
            } else if let miles {
                label = "LOCAL \u{B7} \(miles) mi"
            } else {
                label = "Ships to you"
            }
            // `shipsSummary(...) || null`: an empty summary is null.
            let summary = loc == nil ? shipsSummary(r.vendor, category: p.category, entry: r.entry) : ""
            let href = Links.safeHref(Links.buyUrl(entry: r.entry, vendor: r.vendor, ecoregion: ecoregion, plantId: p.id,
                                                   channel: channel, siteDomain: Links.siteDomain))
            rows.append(BuyRow(
                name: r.entry.name,
                short: nonEmpty(r.vendor.short) ?? r.entry.name, // `r.vendor.short || r.entry.name`
                place: nonEmpty(r.vendor.location), // `|| null`
                href: href,
                label: label,
                ships: nonEmpty(summary),
                local: near,
                miles: miles,
                kind: near ? "directions" : "shop",
                directions: near ? Links.directionsURL(r.vendor) : nil,
                affiliate: r.vendor.affiliate == true, // `!!r.vendor.affiliate`
                price: nonEmpty(r.entry.price),
                size: nonEmpty(r.entry.size)
            ))
        }
        // `loc && rows.length === 0 ? ... : null`
        var noSellerText: String? = nil
        if let loc, rows.isEmpty { noSellerText = "No seller in our list sells this to \(loc.stateName) yet." }
        return BuyView(rows: rows, noSellerText: noSellerText, hasAffiliate: rows.contains { $0.affiliate })
    }

    /// JavaScript's `x || null` for text: empty text and nil are both nil.
    private static func nonEmpty(_ s: String?) -> String? {
        guard let s, !s.isEmpty else { return nil }
        return s
    }
}

/// One row of the "Where to buy" list, with the fields of the website's buyRowsView rows.
/// `label` is "LOCAL · 14 mi" or "Ships to you" (nil with no location); `ships` is where the seller ships, shown only
/// with no location; `kind` is "directions" for a shop nearby (its button opens `directions`) and "shop" otherwise
/// (its button opens `href`); `miles` is whole miles, for nearby shops only.
public struct BuyRow: Equatable, Hashable, Sendable {
    public let name: String
    public let short: String
    public let place: String?
    public let href: String
    public let label: String?
    public let ships: String?
    public let local: Bool
    public let miles: Int?
    public let kind: String
    public let directions: String?
    public let affiliate: Bool
    public let price: String?
    public let size: String?

    public init(name: String, short: String, place: String?, href: String, label: String?, ships: String?, local: Bool,
                miles: Int?, kind: String, directions: String?, affiliate: Bool, price: String?, size: String?) {
        self.name = name
        self.short = short
        self.place = place
        self.href = href
        self.label = label
        self.ships = ships
        self.local = local
        self.miles = miles
        self.kind = kind
        self.directions = directions
        self.affiliate = affiliate
        self.price = price
        self.size = size
    }
}

/// The "Where to buy" list for one plant. `noSellerText` shows when the visitor has a location and no seller can
/// sell them the plant; `hasAffiliate` says whether the list needs the line about commissions.
public struct BuyView: Equatable, Hashable, Sendable {
    public let rows: [BuyRow]
    public let noSellerText: String?
    public let hasAffiliate: Bool

    public init(rows: [BuyRow], noSellerText: String?, hasAffiliate: Bool) {
        self.rows = rows
        self.noSellerText = noSellerText
        self.hasAffiliate = hasAffiliate
    }
}
