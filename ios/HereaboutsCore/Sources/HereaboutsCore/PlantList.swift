// The plant list: search, filters and the ten sorts, ported line by line from the website's index.html
// (setOverlap, normalizeName, nameMatches, passes, CAT_ORDER and sortPlants at lines 892-966, and
// hiddenNameMatches at lines 1486-1495). The answers must match the website's exactly; the tests check every
// case in apps/shared/bundle/expected/list.json.
//
// The page builds its list as render() does: plants.filter { passes } -> sorted -> and, only when the search box
// is not empty (spaces count as not empty), hidden() for the "Also matching" lines.
import Foundation

/// The sort menu, in the website's order. The raw values are the website's option values.
public enum SortKey: String, CaseIterable, Sendable {
    case suggested
    case sellersDesc = "sellers-desc"
    case commonAsc = "common-asc"
    case commonDesc = "common-desc"
    case sciAsc = "sci-asc"
    case heightAsc = "height-asc"
    case heightDesc = "height-desc"
    case bloomAsc = "bloom-asc"
    case category
    case wildlifeDesc = "wildlife-desc"

    /// The words in the website's sort menu.
    public var label: String {
        switch self {
        case .suggested: return "Suggested"
        case .sellersDesc: return "Most sellers"
        case .commonAsc: return "Common name (A to Z)"
        case .commonDesc: return "Common name (Z to A)"
        case .sciAsc: return "Scientific name (A to Z)"
        case .heightAsc: return "Height (low to high)"
        case .heightDesc: return "Height (high to low)"
        case .bloomAsc: return "Bloom (earliest first)"
        case .category: return "Plant type"
        case .wildlifeDesc: return "Wildlife value"
        }
    }
}

/// The "Where to buy" filter: a shop within 75 miles, or a seller that ships here.
public enum BuyMode: String, CaseIterable, Sendable {
    case pickup
    case ship
}

/// Everything the visitor has chosen: the website's `state` object. An empty set means "no limit".
public struct FilterState: Equatable, Sendable {
    public var sun: Set<String>
    public var moisture: Set<String>
    public var category: Set<String>
    public var wildlife: Set<String>
    public var zones: Set<String>
    public var bloomMonth: Set<Int>
    public var bloomColor: Set<String>
    /// Feet. A plant shows when its smallest height is at most this.
    public var maxHeight: Double = 100
    public var buy: Set<BuyMode>
    /// What is typed in the search box, untrimmed. Only the empty string counts as no search.
    public var search: String
    public var sort: SortKey = .suggested
    public var location: Location?

    public init(sun: Set<String> = [], moisture: Set<String> = [], category: Set<String> = [], wildlife: Set<String> = [],
                zones: Set<String> = [], bloomMonth: Set<Int> = [], bloomColor: Set<String> = [], maxHeight: Double = 100,
                buy: Set<BuyMode> = [], search: String = "", sort: SortKey = .suggested, location: Location? = nil) {
        self.sun = sun
        self.moisture = moisture
        self.category = category
        self.wildlife = wildlife
        self.zones = zones
        self.bloomMonth = bloomMonth
        self.bloomColor = bloomColor
        self.maxHeight = maxHeight
        self.buy = buy
        self.search = search
        self.sort = sort
        self.location = location
    }
}

public enum PlantList {
    /// CAT_ORDER: the plant types in the website's order.
    public static let categoryOrder = ["tree", "shrub", "perennial", "grass", "fern", "vine", "groundcover"]

    /// setOverlap: true when nothing is chosen, or the plant has at least one chosen value.
    static func overlaps<T: Hashable>(_ values: [T], _ chosen: Set<T>) -> Bool {
        chosen.isEmpty || values.contains { chosen.contains($0) }
    }

    /// normalizeName: lower case, accents removed, apostrophes dropped, hyphens and dashes read as spaces, runs of
    /// spaces collapsed, trimmed. So "Culver’s" is "culvers" and "Jack–in–the–Pulpit" is "jack in the pulpit".
    public static func normalizeName(_ s: String) -> String {
        JSText.string(normalizedScalars(s))
    }

    static func normalizedScalars(_ s: String) -> Scalars {
        // .normalize('NFD'), drop the accent marks U+0300 to U+036F, .toLowerCase()
        let unaccented = JSText.scalars(s.decomposedStringWithCanonicalMapping).filter { !(0x300...0x36F).contains($0.value) }
        let lower = JSText.scalars(JSText.string(unaccented).lowercased())
        // Drop ' U+2019 U+2018 and the backtick; the hyphen and U+2010 to U+2015 become spaces.
        let cleaned = lower
            .filter { !($0.value == 0x27 || $0.value == 0x2019 || $0.value == 0x2018 || $0.value == 0x60) }
            .map { (u: Unicode.Scalar) -> Unicode.Scalar in
                (u.value == 0x2D || (0x2010...0x2015).contains(u.value)) ? " " : u
            }
        // .replace(/\s+/g, ' ').trim()
        return JSText.trimmed(JSText.collapsedSpaces(cleaned))
    }

    /// String.prototype.includes over Unicode scalars (the text has no lone surrogates, so this is the same as
    /// JavaScript's search over UTF-16 code units). Not String.contains, which compares whole characters.
    static func includes(_ hay: Scalars, _ needle: Scalars) -> Bool {
        if needle.isEmpty { return true }
        if needle.count > hay.count { return false }
        for start in 0...(hay.count - needle.count) where hay[start..<(start + needle.count)].elementsEqual(needle) {
            return true
        }
        return false
    }

    /// nameMatches: true when the whole search, or every word of it, appears in the common or Latin name.
    public static func nameMatches(_ p: Plant, _ query: String) -> Bool {
        let q = normalizedScalars(query)
        if q.isEmpty { return true }
        let names = [normalizedScalars(p.common), normalizedScalars(p.sci)]
        if names.contains(where: { includes($0, q) }) { return true }
        // q.split(' '): q is trimmed with single spaces, so no word is empty.
        let words = q.split(separator: " ", omittingEmptySubsequences: false).map { Array($0) }
        return words.allSatisfy { w in names.contains { includes($0, w) } }
    }

    /// passes: true when the plant shows in the list for these choices.
    public static func passes(_ p: Plant, _ st: FilterState, data: AppData) -> Bool {
        if !st.search.isEmpty && !nameMatches(p, st.search) { return false }
        if !overlaps(p.sun, st.sun) { return false }
        if !overlaps(p.moisture, st.moisture) { return false }
        if !st.category.isEmpty && !st.category.contains(p.category) { return false }
        if !overlaps(p.wildlife, st.wildlife) { return false }
        if !overlaps(p.zones, st.zones) { return false }
        if !overlaps(p.bloomMonths, st.bloomMonth) { return false }
        if !overlaps(p.bloomColor, st.bloomColor) { return false }
        // p.height[0] > st.maxHeight (a missing height compares as false in JavaScript, so it passes)
        if let smallest = p.height.first, smallest > st.maxHeight { return false }
        if !st.buy.isEmpty, let loc = st.location {
            let g = Sellers.group(p, loc, vendors: data.vendors)
            let ok = (st.buy.contains(.pickup) && !g.nearby.isEmpty) || (st.buy.contains(.ship) && !g.ships.isEmpty)
            if !ok { return false }
        }
        if let loc = st.location, !LocationLookup.nativeHere(p.id, loc, geo: data.geo) { return false }
        return true
    }

    /// Sorts the items stably: `order` is a JavaScript sort callback (negative, 0 or positive), and items it calls
    /// equal keep their order, as in JavaScript's Array.prototype.sort.
    static func stableSorted<T>(_ items: [T], _ order: (Int, Int) -> Int) -> [T] {
        let indices = Array(items.indices).sorted { i, j in
            let c = order(i, j)
            return c != 0 ? c < 0 : i < j
        }
        return indices.map { items[$0] }
    }

    /// The sign of a JavaScript number difference: a - b as a sort callback. NaN counts as equal, as in JavaScript.
    private static func sign(_ d: Double) -> Int {
        d < 0 ? -1 : (d > 0 ? 1 : 0)
    }

    /// The larger height (a.height[1] on the website), or nil when the plant does not have two finite heights.
    ///
    /// For such a plant the website's order is not defined: a.height[1] is undefined, the subtraction gives NaN, and
    /// the sort treats the plant as equal to every other, which is not a consistent order. So the app uses its own
    /// rule, which is ours and not the website's: plants without two heights go after all the others, in data order,
    /// in both height sorts. The Android app does the same.
    private static func tallest(_ p: Plant) -> Double? {
        p.height.count > 1 && p.height[0].isFinite && p.height[1].isFinite ? p.height[1] : nil
    }

    /// The height sorts as a sort callback: plants with two heights by their larger height (low to high, or high to
    /// low when `descending`), then the plants without, which it calls equal, so the stable sort keeps their order.
    private static func byHeight(_ a: Plant, _ b: Plant, descending: Bool) -> Int {
        guard let ta = tallest(a), let tb = tallest(b) else {
            // A plant without heights goes after one with heights; two without are equal.
            return (tallest(a) == nil ? 1 : 0) - (tallest(b) == nil ? 1 : 0)
        }
        return descending ? sign(tb - ta) : sign(ta - tb)
    }

    /// a.bloomMonths[0] || 99: the first bloom month, or 99 when there is none.
    private static func firstBloom(_ p: Plant) -> Int {
        let m = p.bloomMonths.first ?? 0
        return m == 0 ? 99 : m
    }

    /// CAT_ORDER.indexOf(category): -1 for a type not in the list.
    private static func categoryIndex(_ p: Plant) -> Int {
        categoryOrder.firstIndex(of: p.category) ?? -1
    }

    /// sortPlants, with the sort and location from `st`. Stable: plants the sort calls equal keep their order.
    public static func sorted(_ plants: [Plant], _ st: FilterState, data: AppData) -> [Plant] {
        let a = plants
        let byCommon = { (i: Int, j: Int) -> Int in Collation.order(a[i].common, a[j].common) }
        switch st.sort {
        case .suggested:
            // Plants a seller that pays us can sell to this visitor come first, then the "Most sellers" order
            // inside each group, then by common name.
            let rows = a.map { Sellers.group($0, st.location, vendors: data.vendors).all }
            let aff = rows.map { r in r.contains { $0.vendor.affiliate == true } ? 1 : 0 }
            return stableSorted(a) { i, j in
                if aff[j] != aff[i] { return aff[j] - aff[i] }
                if rows[j].count != rows[i].count { return rows[j].count - rows[i].count }
                return byCommon(i, j)
            }
        case .sellersDesc:
            let n = a.map { Sellers.group($0, st.location, vendors: data.vendors).all.count }
            return stableSorted(a) { i, j in n[j] != n[i] ? n[j] - n[i] : byCommon(i, j) }
        case .commonAsc:
            return stableSorted(a) { i, j in byCommon(i, j) }
        case .commonDesc:
            return stableSorted(a) { i, j in byCommon(j, i) }
        case .sciAsc:
            return stableSorted(a) { i, j in Collation.order(a[i].sci, a[j].sci) }
        case .heightAsc:
            return stableSorted(a) { i, j in byHeight(a[i], a[j], descending: false) }
        case .heightDesc:
            return stableSorted(a) { i, j in byHeight(a[i], a[j], descending: true) }
        case .bloomAsc:
            return stableSorted(a) { i, j in firstBloom(a[i]) - firstBloom(a[j]) }
        case .category:
            return stableSorted(a) { i, j in
                let d = categoryIndex(a[i]) - categoryIndex(a[j])
                return d != 0 ? d : byCommon(i, j)
            }
        case .wildlifeDesc:
            return stableSorted(a) { i, j in a[j].wildlife.count - a[i].wildlife.count }
        }
    }

    /// hiddenNameMatches: plants whose name matches the search but are not in the list, and why. A plant that
    /// fails only the county check goes under `county` ("Also matching, but not native to ..."); one that fails
    /// any other filter goes under `filters` ("Also matching, but hidden by your filters"). Each is sorted by
    /// common name. The website asks for these only when the search box is not empty.
    public static func hidden(_ shown: [Plant], _ st: FilterState, data: AppData) -> (county: [Plant], filters: [Plant]) {
        let shownIds = Set(shown.map { $0.id })
        var county: [Plant] = []
        var filters: [Plant] = []
        var withoutLocation = st
        withoutLocation.location = nil
        for p in data.plants {
            if shownIds.contains(p.id) || !nameMatches(p, st.search) { continue }
            if st.location != nil && passes(p, withoutLocation, data: data) { county.append(p) } else { filters.append(p) }
        }
        let byName = { (list: [Plant]) -> [Plant] in PlantList.stableSorted(list) { i, j in Collation.order(list[i].common, list[j].common) } }
        return (county: byName(county), filters: byName(filters))
    }

    /// How many of the plants are of each type, in CAT_ORDER, leaving out types with none: the type chips.
    public static func typeCounts(_ plants: [Plant]) -> [(category: String, count: Int)] {
        var counts: [String: Int] = [:]
        for p in plants { counts[p.category, default: 0] += 1 }
        return categoryOrder.compactMap { (c: String) -> (category: String, count: Int)? in
            guard let n = counts[c], n > 0 else { return nil }
            return (category: c, count: n)
        }
    }
}
