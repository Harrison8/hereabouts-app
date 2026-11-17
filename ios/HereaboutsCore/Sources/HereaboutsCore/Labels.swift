// The words on a plant card and a plant page, ported line by line from the website's index.html: bloomRange,
// ftRange, rangeLabel, sunLabel, moistureLabel, growthLabel and categoryLabel (lines 968-1020), PHOTO_ORDER and
// mainPhoto (1034-1039), growingFacts and WILDLIFE_LABEL (1301-1330), SEASON_ROWS and seasonRows (1339-1360), and
// plantTitle (503). Each JavaScript corner case the port keeps is said where it happens. The tests check every
// plant against expected/labels.json, which the website's own functions wrote.
import Foundation

/// One row of the season chart: what the plant does (`label`), the look it shows (`state`) and the months it does it.
public struct SeasonRow: Hashable, Sendable {
    public let label: String
    public let state: String
    /// [first month, last month] for each stretch of months, both included; 0 is January.
    public let runs: [[Int]]

    public init(label: String, state: String, runs: [[Int]]) {
        self.label = label
        self.state = state
        self.runs = runs
    }
}

public enum Labels {
    /// MONTHS in index.html.
    static let monthAbbreviations = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]

    /// PHOTO_ORDER in index.html: the phases tried, in order, for the photo a card shows.
    public static let photoOrder = ["bloom", "fruit", "green", "fall", "winter", "dormant"]

    /// SITE_NAME in index.html.
    public static let siteName = "Hereabouts"

    /// WILDLIFE_LABEL in index.html: what a plant feeds, in a visitor's words. "host" means caterpillars eat its leaves.
    static let wildlifeLabels = ["bees": "bees", "butterflies": "butterflies", "hummingbirds": "hummingbirds", "birds": "birds",
                                 "host": "caterpillars"]

    static let categoryLabels = ["tree": "Tree", "shrub": "Shrub", "perennial": "Perennial", "grass": "Grass / Sedge", "fern": "Fern",
                                 "vine": "Vine", "groundcover": "Groundcover"]

    /// MONTHS[m - 1] inside a string: a month outside 1-12 reads "undefined", as `+` writes it on the website.
    private static func month(_ m: Int) -> String {
        (1...12).contains(m) ? monthAbbreviations[m - 1] : "undefined"
    }

    /// MONTHS[m - 1] as a list item: a month outside 1-12 is undefined, which `join` writes as an empty string.
    private static func monthItem(_ m: Int) -> String {
        (1...12).contains(m) ? monthAbbreviations[m - 1] : ""
    }

    /// bloomRange: the bloom months as "May", "Jun–Aug" or "Mar–May, Jul". nil when there are none.
    /// Months are sorted as numbers, and a repeated month starts a new stretch, as on the website: [5, 5, 6] gives
    /// "May, May–Jun".
    public static func bloomRange(_ months: [Int]) -> String? {
        if months.isEmpty { return nil }
        let sorted = months.sorted()
        var runs: [[Int]] = [[sorted[0]]]
        for i in 1..<sorted.count {
            if sorted[i] == sorted[i - 1] + 1 {
                runs[runs.count - 1].append(sorted[i])
            } else {
                runs.append([sorted[i]])
            }
        }
        return runs.map { r in
            // A one-month stretch is the bare MONTHS entry, so join drops an unknown one: [13] gives "" and [0, 5]
            // gives ", May". A longer stretch is built with +, which writes "undefined": [13, 14] gives
            // "undefined–undefined".
            r.count == 1 ? monthItem(r[0]) : month(r[0]) + "–" + month(r[r.count - 1])
        }.joined(separator: ", ")
    }

    /// Number.isInteger.
    private static func isInteger(_ n: Double) -> Bool {
        n.isFinite && n.rounded(.towardZero) == n
    }

    /// ftRange: "3 ft" when both ends are the same, else "1–1.5 ft". Whole numbers stay whole; others get one decimal
    /// through JavaScript's toFixed(1), so 0.25 gives "0.3" (see JSText.toFixed). The website reads two numbers and
    /// fails on fewer; this gives an empty string then.
    public static func ftRange(_ range: [Double]) -> String {
        guard range.count >= 2 else { return "" }
        let a = range[0]
        let b = range[1]
        // a === b: the same number, written as String(a) would write it.
        if a == b { return JSText.numberString(a) + " ft" }
        func fmt(_ n: Double) -> String { isInteger(n) ? JSText.numberString(n) : JSText.toFixed(n, 1) }
        return fmt(a) + "–" + fmt(b) + " ft"
    }

    /// rangeLabel: the values in `order`'s order. One value is its name; neighbours read "first to last"; a gap reads
    /// "or". The first letter is upper case. An empty string when no value is in `order`.
    private static func rangeLabel(_ values: [String], _ order: [String], _ names: [String: String]) -> String {
        let picked = order.filter { values.contains($0) }
        if picked.isEmpty { return "" }
        let idx = picked.compactMap { order.firstIndex(of: $0) }
        let run = idx.indices.allSatisfy { i in i == 0 || idx[i] == idx[i - 1] + 1 }
        let name = { (k: String) in names[k] ?? "" }
        let text: String
        if picked.count == 1 {
            text = name(picked[0])
        } else if run {
            text = "\(name(picked[0])) to \(name(picked[picked.count - 1]))"
        } else {
            text = picked.map(name).joined(separator: " or ")
        }
        return JSText.upperFirst(text)
    }

    /// sunLabel: "Full sun to part shade" and the like.
    public static func sun(_ values: [String]) -> String {
        rangeLabel(values, ["full", "part", "shade"], ["full": "full sun", "part": "part shade", "shade": "full shade"])
    }

    /// moistureLabel: "Dry to medium" and the like.
    public static func moisture(_ values: [String]) -> String {
        rangeLabel(values, ["dry", "medium", "wet"], ["dry": "dry", "medium": "medium", "wet": "wet"])
    }

    // MARK: - growthLabel

    /// [0-9]: JavaScript's \d, which is ASCII digits only.
    private static func isDigit(_ u: Unicode.Scalar?) -> Bool {
        guard let u else { return false }
        return u.value >= 0x30 && u.value <= 0x39
    }

    /// [A-Za-z0-9_]: a word character for JavaScript's \b (without the u flag).
    private static func isWord(_ u: Unicode.Scalar?) -> Bool {
        guard let u else { return false }
        let v = u.value
        return (v >= 0x30 && v <= 0x39) || (v >= 0x41 && v <= 0x5A) || (v >= 0x61 && v <= 0x7A) || v == 0x5F
    }

    /// /^(\d+)\s*[-–]\s*(\d+)$/ on the trimmed text: the two numbers, as written (leading zeros kept).
    private static func yearsRange(_ s: Scalars) -> (String, String)? {
        var i = 0
        func run(_ test: (Unicode.Scalar) -> Bool) -> Scalars {
            let start = i
            while i < s.count && test(s[i]) { i += 1 }
            return Array(s[start..<i])
        }
        let first = run { isDigit($0) }
        if first.isEmpty { return nil }
        _ = run(JSText.isSpace)
        guard i < s.count, s[i] == "-" || s[i] == "\u{2013}" else { return nil }
        i += 1
        _ = run(JSText.isSpace)
        let second = run { isDigit($0) }
        if second.isEmpty || i != s.count { return nil }
        return (JSText.string(first), JSText.string(second))
    }

    /// Replaces each `word` that starts at a word boundary and passes `after` (given the character after the word, or
    /// nil at the end), left to right, without overlaps, as a global JavaScript regular expression replace does.
    /// Every word here starts with a word character, so \b before it means the character before is not one.
    private static func replacing(_ s: Scalars, _ word: String, _ with: String, after: (Unicode.Scalar?) -> Bool) -> Scalars {
        let w = JSText.scalars(word)
        let r = JSText.scalars(with)
        var out: Scalars = []
        var i = 0
        while i < s.count {
            let fits = i + w.count <= s.count && Array(s[i..<(i + w.count)]) == w
            let boundary = i == 0 || !isWord(s[i - 1])
            if fits && boundary && after(i + w.count < s.count ? s[i + w.count] : nil) {
                out += r
                i += w.count
            } else {
                out.append(s[i])
                i += 1
            }
        }
        return out
    }

    /// (?=\s|$|;|,|\)): what may follow "in." for it to be read as inches.
    private static func endsInchNote(_ u: Unicode.Scalar?) -> Bool {
        guard let u else { return true }
        return JSText.isSpace(u) || u == ";" || u == "," || u == ")"
    }

    /// growthLabel: "2-3" in the data means years to full size and reads "2–3 years to full size"; anything else is a
    /// note written in shorthand, spelled out: "1-2" gets an en dash, "~" (and the spaces after it) reads "about ",
    /// "ft/yr" reads "ft a year", "yrs" "years", "yr" "year", and "in." before a space, the end, ";", "," or ")" reads
    /// "in". The first letter is upper case.
    /// nil when there are no years. (The website, given a maturity with no years, writes "Undefined"; no plant has
    /// one, and the app leaves the line out instead.)
    public static func growth(_ years: String?) -> String? {
        guard let years else { return nil }
        let text = JSText.scalars(years)
        if let r = yearsRange(JSText.trimmed(text)) { return "\(r.0)–\(r.1) years to full size" }
        // .replace(/(\d)-(\d)/g, '$1–$2'): matches do not overlap, so "1-2-3" gives "1–2-3".
        var s: Scalars = []
        var i = 0
        while i < text.count {
            if i + 2 < text.count && isDigit(text[i]) && text[i + 1] == "-" && isDigit(text[i + 2]) {
                s += [text[i], "\u{2013}", text[i + 2]]
                i += 3
            } else {
                s.append(text[i])
                i += 1
            }
        }
        // .replace(/~\s*/g, 'about ')
        var t: Scalars = []
        i = 0
        while i < s.count {
            if s[i] == "~" {
                t += JSText.scalars("about ")
                i += 1
                while i < s.count && JSText.isSpace(s[i]) { i += 1 }
            } else {
                t.append(s[i])
                i += 1
            }
        }
        let wordEnd = { (u: Unicode.Scalar?) in !isWord(u) }
        t = replacing(t, "ft/yr", "ft a year", after: wordEnd)
        t = replacing(t, "yrs", "years", after: wordEnd)
        t = replacing(t, "yr", "year", after: wordEnd)
        // /\bin\.(?=\s|$|;|,|\))/g
        t = replacing(t, "in.", "in", after: endsInchNote)
        return JSText.upperFirst(JSText.string(t))
    }

    /// categoryLabel: "Tree", "Grass / Sedge" and so on. The website gives undefined for a type it does not know; this
    /// gives an empty string (no plant has such a type).
    public static func category(_ c: String) -> String {
        categoryLabels[c] ?? ""
    }

    // MARK: - growingFacts

    /// Whether a growingFacts row is set in the mono font: the website marks the Height and Width rows of `quick` with a
    /// third element, true. The rows here hold only the label and the value, and this says which rows have the mark.
    public static func isMono(_ label: String) -> Bool {
        label == "Height" || label == "Width"
    }

    /// A number's truthiness in JavaScript: false for 0, NaN, and a missing value.
    private static func truthy(_ list: [Double], _ i: Int) -> Bool {
        guard i < list.count else { return false }
        return list[i] != 0 && !list[i].isNaN
    }

    /// growingFacts: the facts on a card and a plant page, each row [label, value].
    /// quick: Height, Width, Sun and Soil (the ruled box). more: Growth and Feeds. flowers: Flowers and In fall
    /// (the plant page only).
    public static func growingFacts(_ p: Plant) -> (quick: [[String]], more: [[String]], flowers: [[String]]) {
        let bloom = bloomRange(p.bloomMonths)
        // p.bloomColor.length ? join : null, then tested for truthiness: an empty joined string counts as none.
        let bloomColors = p.bloomColor.joined(separator: ", ")
        var quick: [[String]] = []
        // p.height[1] ? ...: the larger height must be a number other than 0.
        if truthy(p.height, 1) { quick.append(["Height", ftRange(p.height)]) }
        if truthy(p.spread, 1) { quick.append(["Width", ftRange(p.spread)]) }
        if !p.sun.isEmpty { quick.append(["Sun", sun(p.sun)]) }
        if !p.moisture.isEmpty { quick.append(["Soil", moisture(p.moisture)]) }
        // A wildlife value with no label is undefined in the map, and join writes undefined as an empty string.
        let feeds = p.wildlife.map { wildlifeLabels[$0] ?? "" }.joined(separator: ", ")
        var more: [[String]] = []
        if let g = growth(p.maturity?.years) { more.append(["Growth", g]) }
        if !feeds.isEmpty { more.append(["Feeds", JSText.upperFirst(feeds)]) }
        var flowers: [[String]] = []
        if let bloom, !bloom.isEmpty { flowers.append(["Flowers", bloom + (bloomColors.isEmpty ? "" : ", " + bloomColors)]) }
        if let fall = p.fallColor, !fall.isEmpty, fall != "none" { flowers.append(["In fall", JSText.upperFirst(fall)]) }
        return (quick: quick, more: more, flowers: flowers)
    }

    // MARK: - seasonRows

    /// SEASON_ROWS: label and state of each row, in order. "In leaf" covers every month the plant is green, in bloom,
    /// in fruit or in fall color; each other row covers the months of its own state.
    private static let seasonRowNames: [(label: String, state: String)] = [
        (label: "In leaf", state: "green"), (label: "Bloom", state: "bloom"), (label: "Fruit", state: "fruit"),
        (label: "Fall color", state: "fall"),
    ]

    private static func inRow(_ state: String, _ s: String) -> Bool {
        switch state {
        case "green": return s == "green" || s == "bloom" || s == "fruit" || s == "fall"
        default: return s == state
        }
    }

    /// seasonRows: one row per thing the plant does, with the stretches of months it does it. A row with no months is
    /// left out. As on the website, a stretch still open at month index 11 closes there, and one still open when a
    /// list shorter than 12 ends is dropped.
    public static func seasonRows(_ p: Plant) -> [SeasonRow] {
        seasonRowNames.compactMap { row -> SeasonRow? in
            var runs: [[Int]] = []
            var start: Int? = nil
            for (i, s) in p.months.enumerated() {
                let on = inRow(row.state, s)
                if on && start == nil { start = i }
                if let st = start, !on || i == 11 {
                    runs.append([st, on ? i : i - 1])
                    start = nil
                }
            }
            return runs.isEmpty ? nil : SeasonRow(label: row.label, state: row.state, runs: runs)
        }
    }

    // MARK: - photos and title

    /// Every photo the plant has, in PHOTO_ORDER. A phase not in PHOTO_ORDER is not shown.
    public static func photos(_ p: Plant) -> [(season: String, photo: Photo)] {
        photoOrder.compactMap { phase in p.phasePhotos[phase].map { (season: phase, photo: $0) } }
    }

    /// mainPhoto: the first phase in PHOTO_ORDER that has a photo, or nil.
    public static func mainPhoto(_ p: Plant) -> (season: String, photo: Photo)? {
        photos(p).first
    }

    /// plantTitle: a plant page's title.
    public static func plantTitle(_ p: Plant) -> String {
        "\(p.common) (\(p.sci)): native range and where to buy \u{00B7} \(siteName)"
    }
}
