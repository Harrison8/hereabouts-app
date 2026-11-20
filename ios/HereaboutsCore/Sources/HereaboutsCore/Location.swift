// Location finding, ported line by line from the website's location.js: turns what a visitor types (a ZIP, a
// city or county name, a state) or where the phone is into a county, then into an EPA ecoregion, and answers
// "is this plant native here". The answers must match the website's exactly; the tests check every case in
// apps/shared/bundle/expected/location.json.
//
// The website's regular expressions are written out here as plain code over Unicode scalars (code points),
// because Swift's Regex reads \d, \s and \b differently from JavaScript (Unicode digits, other spaces, Unicode
// word boundaries). Each function says which JavaScript lines it follows.
import Foundation

/// What a visitor typed, as classifyInput in location.js sorts it.
public enum InputKind: Equatable, Hashable, Sendable {
    case empty
    /// Five digits, from a ZIP, a ZIP+4, or a ZIP at the end of "Dayton, OH 45402".
    case zip(String)
    /// Starts with a house number. The text is trimmed.
    case address(String)
    /// Only a state, like "Ohio" or "state of ohio". Never DC, which is looked up like a city.
    case state(code: String, name: String)
    /// Anything else: a city or county name. The text is trimmed.
    case place(String)
}

/// A county found from a ZIP, a position or a county code.
public enum LocationResult: Equatable, Hashable, Sendable {
    case found(Location)
    /// The county is outside the lower 48 states and DC, or has no ecoregion (the website's "outside_us48").
    case outsideUS48
    /// The ZIP is not in the ZIP table (the website returns null and says "We couldn't find ZIP ..."), or, for
    /// `nearest`, the ZIP table is empty.
    case notFound

    /// The website's word for the problem, or nil when a county was found.
    public var error: String? {
        switch self {
        case .found: return nil
        case .outsideUS48: return "outside_us48"
        case .notFound: return "not_found"
        }
    }
}

/// Whether a typed name matched a city or a county (lookupPlace's `kind`). The website sends it as the `method`
/// of its location_set and geocode_failed events.
public enum PlaceKind: String, Equatable, Hashable, Sendable {
    case city
    case county
}

/// A county found from a typed city or county name.
public enum PlaceResult: Equatable, Hashable, Sendable {
    case found(Location, kind: PlaceKind)
    /// Several states have a place with this name; `states` lists them, biggest place first (the website's "ambiguous").
    case ambiguous(states: [String])
    /// The website's "outside_us48". `kind` is nil when the name was only found in a state outside the lower 48
    /// (lookupPlace's own error, which the website reports as method "city"); it is set when lookupPlace found
    /// one place but its county has no ecoregion or is outside the lower 48 (the website reports that place's kind).
    case outsideUS48(kind: PlaceKind?)
    /// No place has this name (the website's "not_found").
    case notFound

    /// lookupPlace's kind, when it found a single place (even one outside the lower 48), else nil.
    public var kind: PlaceKind? {
        switch self {
        case .found(_, let kind): return kind
        case .outsideUS48(let kind): return kind
        case .ambiguous, .notFound: return nil
        }
    }

    /// The website's word for the problem, or nil when a county was found.
    public var error: String? {
        switch self {
        case .found: return nil
        case .ambiguous: return "ambiguous"
        case .outsideUS48: return "outside_us48"
        case .notFound: return "not_found"
        }
    }
}

/// A US state or territory, as US_STATES in location.js lists them.
public struct USState: Hashable, Sendable {
    public let code: String
    public let name: String
}

// MARK: - Text helpers: more of the JavaScript string operations location.js uses (the shared ones are in JSText.swift)

/// The characters JavaScript's `.` does not match.
private let jsLineTerminatorValues: Set<UInt32> = [0x0A, 0x0D, 0x2028, 0x2029]

private func isLineTerminator(_ u: Unicode.Scalar) -> Bool { jsLineTerminatorValues.contains(u.value) }
/// JavaScript's \d (without the u flag): ASCII digits only.
private func isDigit(_ u: Unicode.Scalar) -> Bool { u.value >= 0x30 && u.value <= 0x39 }
/// JavaScript's \w, which \b is built on: ASCII letters, digits and underscore.
private func isWordChar(_ u: Unicode.Scalar) -> Bool {
    let v = u.value
    return (v >= 0x30 && v <= 0x39) || (v >= 0x41 && v <= 0x5A) || (v >= 0x61 && v <= 0x7A) || v == 0x5F
}

private func matches(_ a: Scalars, at i: Int, _ word: Scalars) -> Bool {
    i >= 0 && i + word.count <= a.count && a[i..<(i + word.count)].elementsEqual(word)
}

private func hasPrefix(_ a: Scalars, _ p: Scalars) -> Bool { matches(a, at: 0, p) }

private func hasSuffix(_ a: Scalars, _ p: Scalars) -> Bool { matches(a, at: a.count - p.count, p) }

/// /^\d{5}(?:-?\d{4})?$/: five digits, nine digits, or five digits, a dash and four digits.
private func isZipText<C: Collection>(_ text: C) -> Bool where C.Element == Unicode.Scalar {
    let d = Array(text)
    if d.count == 5 || d.count == 9 { return d.allSatisfy(isDigit) }
    if d.count == 10 { return d[0..<5].allSatisfy(isDigit) && d[5] == "-" && d[6...].allSatisfy(isDigit) }
    return false
}

// MARK: - Location lookup

public enum LocationLookup {
    /// US_STATES in location.js, in its order.
    private static let statePairs: [(String, String)] = [
        ("AL", "Alabama"), ("AK", "Alaska"), ("AZ", "Arizona"), ("AR", "Arkansas"), ("CA", "California"), ("CO", "Colorado"),
        ("CT", "Connecticut"), ("DE", "Delaware"), ("DC", "District of Columbia"), ("FL", "Florida"), ("GA", "Georgia"),
        ("HI", "Hawaii"), ("ID", "Idaho"), ("IL", "Illinois"), ("IN", "Indiana"), ("IA", "Iowa"), ("KS", "Kansas"),
        ("KY", "Kentucky"), ("LA", "Louisiana"), ("ME", "Maine"), ("MD", "Maryland"), ("MA", "Massachusetts"),
        ("MI", "Michigan"), ("MN", "Minnesota"), ("MS", "Mississippi"), ("MO", "Missouri"), ("MT", "Montana"),
        ("NE", "Nebraska"), ("NV", "Nevada"), ("NH", "New Hampshire"), ("NJ", "New Jersey"), ("NM", "New Mexico"),
        ("NY", "New York"), ("NC", "North Carolina"), ("ND", "North Dakota"), ("OH", "Ohio"), ("OK", "Oklahoma"),
        ("OR", "Oregon"), ("PA", "Pennsylvania"), ("RI", "Rhode Island"), ("SC", "South Carolina"), ("SD", "South Dakota"),
        ("TN", "Tennessee"), ("TX", "Texas"), ("UT", "Utah"), ("VT", "Vermont"), ("VA", "Virginia"), ("WA", "Washington"),
        ("WV", "West Virginia"), ("WI", "Wisconsin"), ("WY", "Wyoming"), ("PR", "Puerto Rico"), ("GU", "Guam"),
        ("VI", "US Virgin Islands"), ("AS", "American Samoa"), ("MP", "Northern Mariana Islands"),
    ]
    /// Every state and territory, in location.js's order, so a typed state is recognized even where there are no plants.
    public static let usStates: [USState] = statePairs.map { USState(code: $0.0, name: $0.1) }

    /// States that sit in a single ecoregion (ONE_ECOREGION_STATES).
    static let oneEcoregionStates: Set<String> = ["DE", "RI"]
    /// States and territories outside the lower 48 (OUTSIDE_US48_STATES).
    static let outsideUS48States: Set<String> = ["AK", "HI", "PR", "GU", "VI", "AS", "MP"]

    /// Each state's code in lower case and its name through normalizePlaceName, worked out once.
    private struct StateKey: Sendable {
        let code: String
        let lower: Scalars
        let name: Scalars
    }

    private static let stateKeys: [StateKey] = usStates.map {
        StateKey(code: $0.code, lower: JSText.scalars($0.code.lowercased()), name: normalized($0.name))
    }

    private struct Abbreviation: Sendable {
        let long: Scalars
        let short: Scalars
    }

    /// \b(saint|sainte|mount|fort)\b, in the order the alternation tries them.
    private static let abbreviationPairs: [(String, String)] = [("saint", "st"), ("sainte", "ste"), ("mount", "mt"), ("fort", "ft")]
    private static let abbreviations: [Abbreviation] = abbreviationPairs
        .map { Abbreviation(long: JSText.scalars($0.0), short: JSText.scalars($0.1)) }
    private static let countries: [Scalars] = ["usa", "us", "united states", "united states of america"].map(JSText.scalars)
    private static let theStateOf = JSText.scalars("the state of ")
    private static let stateOf = JSText.scalars("state of ")
    private static let spaceState = JSText.scalars(" state")
    private static let dcForms: [Scalars] = ["dc", "washington dc", "washington, dc"].map(JSText.scalars)
    private static let wholeDCForms: [Scalars] = ["dc", "washington dc", "district of columbia"].map(JSText.scalars)
    private static let wholeNYCForms: [Scalars] = ["nyc", "new york city", "nyc ny", "new york city ny"].map(JSText.scalars)
    private static let washington = JSText.scalars("washington")
    private static let newYork = JSText.scalars("new york")
    private static let spaceCo = JSText.scalars(" co")

    /// US_STATES[code], or nil for a code that is not a state or territory.
    public static func stateName(_ code: String) -> String? {
        usStates.first { $0.code == code }?.name
    }

    // MARK: Names

    /// normalizePlaceName: puts a place name in one standard form, so "St. Louis", "saint louis" and "St Louis" all
    /// match. Drops accents, case, periods and apostrophes; hyphens and slashes become spaces; commas are kept.
    public static func normalizePlaceName(_ text: String) -> String {
        JSText.string(normalized(text))
    }

    static func normalized(_ input: String) -> Scalars {
        // .normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase()
        let unaccented = JSText.scalars(input.decomposedStringWithCanonicalMapping).filter { !(0x300...0x36F).contains($0.value) }
        var a = JSText.scalars(JSText.string(unaccented).lowercased())
        // .replace(/[.'’]/g, "").replace(/[-/]/g, " ")
        a = a.filter { $0 != "." && $0 != "'" && $0 != "\u{2019}" }
            .map { (u: Unicode.Scalar) -> Unicode.Scalar in (u == "-" || u == "/") ? " " : u }
        // .replace(/\s*,\s*/g, ", "): each comma, with the spaces on both sides of it, becomes ", ".
        var out: Scalars = []
        var i = 0
        while i < a.count {
            if JSText.isSpace(a[i]) {
                var j = i
                while j < a.count && JSText.isSpace(a[j]) { j += 1 }
                if j < a.count && a[j] == "," {
                    i = j
                    continue
                }
                out.append(contentsOf: a[i..<j])
                i = j
            } else if a[i] == "," {
                out.append(",")
                out.append(" ")
                i += 1
                while i < a.count && JSText.isSpace(a[i]) { i += 1 }
            } else {
                out.append(a[i])
                i += 1
            }
        }
        // .replace(/\s+/g, " ").trim(), then .replace(/,$/, "").trim()
        a = JSText.trimmed(JSText.collapsedSpaces(out))
        if a.last == "," { a = JSText.trimmed(a.dropLast()) }
        // .replace(/\b(saint|sainte|mount|fort)\b/g, ...)
        let s = a
        func isWordAt(_ k: Int) -> Bool { k >= 0 && k < s.count && isWordChar(s[k]) }
        var result: Scalars = []
        var k = 0
        scan: while k < s.count {
            if isWordAt(k) && !isWordAt(k - 1) {
                for w in abbreviations where matches(s, at: k, w.long) && !isWordAt(k + w.long.count) {
                    result.append(contentsOf: w.short)
                    k += w.long.count
                    continue scan
                }
            }
            result.append(s[k])
            k += 1
        }
        return result
    }

    /// dropCountry: drops a trailing "USA", "US" or "United States" from normalized text.
    /// .replace(/,? (usa|us|united states|united states of america)$/, "").trim()
    static func dropCountry(_ a: Scalars) -> Scalars {
        for p in 0..<a.count {
            var rest: ArraySlice<Unicode.Scalar>? = nil
            if a[p] == ",", p + 1 < a.count, a[p + 1] == " " {
                rest = a[(p + 2)...]
            } else if a[p] == " " {
                rest = a[(p + 1)...]
            }
            if let rest, countries.contains(where: { $0.elementsEqual(rest) }) {
                return JSText.trimmed(a[..<p])
            }
        }
        return JSText.trimmed(a)
    }

    /// stateCode: the two-letter code for normalized text that is only a state ("ohio", "oh", "state of ohio"), else nil.
    static func stateCode(_ s: Scalars) -> String? {
        var t = s
        // .replace(/^(the )?state of /, "").replace(/ state$/, "").trim()
        if hasPrefix(t, theStateOf) {
            t = Array(t.dropFirst(theStateOf.count))
        } else if hasPrefix(t, stateOf) {
            t = Array(t.dropFirst(stateOf.count))
        }
        if hasSuffix(t, spaceState) { t = Array(t.dropLast(spaceState.count)) }
        t = JSText.trimmed(t)
        for key in stateKeys where t == key.lower || t == key.name { return key.code }
        if dcForms.contains(t) { return "DC" }
        return nil
    }

    /// displayName: capitalizes each word of what the visitor typed, for messages: "springfield" -> "Springfield".
    /// .trim().replace(/\s+/g, " ").replace(/(^|[\s-])(\p{Ll})/gu, upper case)
    public static func displayName(_ input: String) -> String {
        let a = JSText.collapsedSpaces(JSText.trimmed(JSText.scalars(input)))
        var out = String.UnicodeScalarView()
        for (i, u) in a.enumerated() {
            if u.properties.generalCategory == .lowercaseLetter && (i == 0 || JSText.isSpace(a[i - 1]) || a[i - 1] == "-") {
                out.append(contentsOf: u.properties.uppercaseMapping.unicodeScalars)
            } else {
                out.append(u)
            }
        }
        return String(out)
    }

    // MARK: What was typed

    /// classifyInput.
    public static func classify(_ text: String) -> InputKind {
        let a = JSText.trimmed(JSText.scalars(text))
        if a.isEmpty { return .empty }
        let trimmedText = JSText.string(a)
        // normalizeZip
        if isZipText(a) { return .zip(JSText.string(a[0..<5])) }
        // A street address starts with a house number. (/^\d/)
        if isDigit(a[0]) { return .address(trimmedText) }
        // "Dayton, OH 45402": the ZIP at the end is enough. /(?:^|[\s,])(\d{5})(?:-?\d{4})?$/
        for q in -1..<a.count {
            if q >= 0 && !(JSText.isSpace(a[q]) || a[q] == ",") { continue }
            let rest = a[(q + 1)...]
            if isZipText(rest) { return .zip(JSText.string(rest.prefix(5))) }
        }
        // Washington, DC is one county, so it is looked up like a city.
        if let code = stateCode(dropCountry(normalized(trimmedText))), code != "DC" {
            return .state(code: code, name: stateName(code) ?? "")
        }
        return .place(trimmedText)
    }

    // MARK: Finding the county

    /// resolveLocation: the full Location for a county code, or `.outsideUS48` when the county has no ecoregion or
    /// its state is not one of the lower 48 states (plus DC) in `tables.states`.
    public static func resolve(countyFips: String, lat: Double?, lon: Double?, tables: Tables) -> LocationResult {
        let ecoregion = tables.countyEcoregion[countyFips] ?? ""
        let stateFips = JSText.string(countyFips.unicodeScalars.prefix(2))
        guard !ecoregion.isEmpty, let state = tables.states[stateFips] else { return .outsideUS48 }
        return .found(Location(
            countyFips: countyFips,
            countyName: tables.countyNames[countyFips] ?? "",
            stateFips: stateFips,
            stateName: state.name,
            stateUsps: state.usps,
            ecoregion: ecoregion,
            lat: lat,
            lon: lon
        ))
    }

    /// lookupZip then resolveLocation: `.notFound` for a ZIP the table does not have (the website's "We couldn't
    /// find ZIP ..."), `.outsideUS48` for a ZIP whose county is outside the lower 48 or has no ecoregion.
    public static func zip(_ zip: String, tables: Tables) -> LocationResult {
        guard let row = tables.zipCounty[zip] else { return .notFound }
        return resolve(countyFips: row.countyFips, lat: row.lat, lon: row.lon, tables: tables)
    }

    private struct Reading {
        let name: Scalars
        let code: String?
    }

    /// placeReadings: the ways to read normalized text as a name plus an optional state, most likely first.
    private static func placeReadings(_ s: Scalars) -> [Reading] {
        var out: [Reading] = []
        let whole = JSText.trimmed(s.filter { $0 != "," })
        if let comma = s.lastIndex(of: ","), comma > 0 {
            if let code = stateCode(JSText.trimmed(s[(comma + 1)...])) {
                out.append(Reading(name: JSText.trimmed(s[..<comma].filter { $0 != "," }), code: code))
            }
            out.append(Reading(name: whole, code: nil))
        } else {
            // Every state name or code the text ends with, longest first (a stable sort, as in JavaScript).
            var tails: [(reading: Reading, length: Int, order: Int)] = []
            for key in stateKeys {
                for tail in [key.name, key.lower] {
                    let spaced: Scalars = [" "] + tail
                    if hasSuffix(s, spaced) {
                        tails.append((reading: Reading(name: JSText.trimmed(s.dropLast(spaced.count)), code: key.code), length: tail.count, order: tails.count))
                    }
                }
            }
            tails.sort { $0.length != $1.length ? $0.length > $1.length : $0.order < $1.order }
            out.append(Reading(name: whole, code: nil))
            out.append(contentsOf: tails.map { $0.reading })
        }
        if wholeDCForms.contains(whole) { out.insert(Reading(name: washington, code: "DC"), at: 0) }
        if wholeNYCForms.contains(whole) { out.insert(Reading(name: newYork, code: "NY"), at: 0) }
        return out
    }

    private enum PlaceHit {
        case place(countyFips: String, lat: Double, lon: Double, kind: PlaceKind)
        case ambiguous([String])
        case outside
        case notFound
    }

    /// lookupPlace. Like the website, it does not classify the text first, so "Ohio" is looked up as a place name.
    private static func lookupPlace(_ text: String, tables: Tables) -> PlaceHit {
        var outside = false
        for reading in placeReadings(dropCountry(normalized(text))) {
            if reading.name.isEmpty { continue }
            if let code = reading.code, !tables.coveredStates.contains(code) {
                outside = true
                continue
            }
            // "Hamilton Co" is Hamilton County.
            let name = JSText.string(reading.name)
            let keys = hasSuffix(reading.name, spaceCo) ? [name, JSText.string(reading.name.dropLast(3)) + " county"] : [name]
            for key in keys {
                let rows = (tables.placeCounty[key] ?? []).filter { reading.code == nil || $0.state == reading.code }
                if rows.isEmpty { continue }
                if rows.count > 1 && !rows[0].isDefault { return .ambiguous(rows.map { $0.state }) }
                return .place(countyFips: rows[0].countyFips, lat: rows[0].lat, lon: rows[0].lon, kind: placeKind(key))
            }
        }
        return outside ? .outside : .notFound
    }

    private static let countyWords: [Scalars] = [" county", " parish", " borough", " census area", " municipality"].map(JSText.scalars)

    /// lookupPlace's kind: / (county|parish|borough|census area|municipality)$/ on the table name that matched.
    private static func placeKind(_ key: String) -> PlaceKind {
        let k = JSText.scalars(key)
        return countyWords.contains { hasSuffix(k, $0) } ? .county : .city
    }

    /// lookupPlace then resolveLocation, or lookupPlace's error.
    public static func place(_ text: String, tables: Tables) -> PlaceResult {
        switch lookupPlace(text, tables: tables) {
        case .place(let countyFips, let lat, let lon, let kind):
            if case .found(let loc) = resolve(countyFips: countyFips, lat: lat, lon: lon, tables: tables) { return .found(loc, kind: kind) }
            return .outsideUS48(kind: kind)
        case .ambiguous(let states):
            return .ambiguous(states: states)
        case .outside:
            return .outsideUS48(kind: nil)
        case .notFound:
            return .notFound
        }
    }

    /// EARTH_RADIUS_MI in sellers.js.
    public static let earthRadiusMi = 3958.8

    /// distanceMi in sellers.js: straight-line miles between two points (haversine), with the arithmetic in the
    /// same order so the results match the website's.
    public static func distanceMi(_ a: (lat: Double, lon: Double), _ b: (lat: Double, lon: Double)) -> Double {
        func rad(_ deg: Double) -> Double { (deg * Double.pi) / 180 }
        let dLat = rad(b.lat - a.lat)
        let dLon = rad(b.lon - a.lon)
        // x ** 2 in JavaScript is exactly x * x.
        let sinLat = sin(dLat / 2)
        let sinLon = sin(dLon / 2)
        let h = sinLat * sinLat + cos(rad(a.lat)) * cos(rad(b.lat)) * (sinLon * sinLon)
        return 2 * earthRadiusMi * asin(min(1, h.squareRoot()))
    }

    /// nearestZip (scripts/app/app-logic.mjs) then as a ZIP: the county of the ZIP whose table point is closest to
    /// the position. Ties go to the lower ZIP.
    public static func nearest(lat: Double, lon: Double, tables: Tables) -> LocationResult {
        var best: ZipRow? = nil
        var bestDistance = Double.infinity
        for code in tables.sortedZips {
            guard let row = tables.zipCounty[code] else { continue }
            let d = distanceMi((lat: lat, lon: lon), (lat: row.lat, lon: row.lon))
            if d < bestDistance {
                bestDistance = d
                best = row
            }
        }
        guard let best else { return .notFound }
        return resolve(countyFips: best.countyFips, lat: best.lat, lon: best.lon, tables: tables)
    }

    // MARK: Words on screen

    /// stateMessage: the message for a typed state, given its two-letter code.
    public static func stateMessage(_ code: String) -> String {
        let name = stateName(code) ?? "undefined"
        if outsideUS48States.contains(code) { return "We only cover the lower 48 states for now." }
        if oneEcoregionStates.contains(code) { return "Type a city, county or ZIP code in \(name)." }
        return "\(name) contains several different ecoregions. Type a city, county or ZIP code."
    }

    /// ambiguousMessage: the message for a city name that several states share.
    public static func ambiguousMessage(_ text: String, states: [String]) -> String {
        // text.replace(/,.*$/, ""): from the first comma with no line break after it, to the end.
        let a = JSText.scalars(text)
        var cut = a.count
        for c in a.indices where a[c] == "," && !a[(c + 1)...].contains(where: isLineTerminator) {
            cut = c
            break
        }
        let name = displayName(JSText.string(a[..<cut]))
        return "There's a \(name) in more than one state. Add the state, like \(name), \(states.first ?? "undefined")."
    }

    /// headerText.
    public static func headerText(_ loc: Location?) -> String {
        guard let loc else { return "Native plants for your spot" }
        return "Native plants for \(loc.countyName), \(loc.stateName)"
    }

    /// coverageText: shows only for an ecoregion the site does not serve yet.
    public static func coverageText(_ n: Int, served: Bool) -> String {
        if served || n <= 0 { return "" }
        return "We have \(n) plants for your area so far."
    }

    /// isServed: whether the site serves the county's ecoregion (plant-regions.json `served`).
    public static func isServed(_ loc: Location, served: [String]) -> Bool {
        served.contains(loc.ecoregion)
    }

    /// The line above the plant list once a county is chosen, as showLocation in index.html picks it: the "None of
    /// our plants" line when no plant is native there, else coverageText. Empty when no line shows.
    /// `nativeCount` counts the plants native to the county, with no filter.
    public static func coverageLine(_ loc: Location, nativeCount: Int, served: [String], ecoregionNames: [String: String]) -> String {
        if nativeCount == 0 {
            // A missing name reads "undefined" on the website; no ecoregion the tables hold lacks one.
            return "None of our plants are recorded as native to \(ecoregionNames[loc.ecoregion] ?? "undefined") yet."
        }
        return coverageText(nativeCount, served: isServed(loc, served: served))
    }

    /// roundTo2 in location.js (roundPosition in app-logic.mjs): Math.round(x * 100) / 100, two decimals (about 1 km),
    /// so a saved position cannot point at a house. Math.round sends halves up, toward +infinity.
    public static func roundPosition(_ x: Double) -> Double {
        let v = x * 100
        let down = v.rounded(.down)
        var r = v - down >= 0.5 ? down + 1 : down
        if r == 0 && v < 0 { r = -0.0 } // Math.round(-0.4) is -0
        return r / 100
    }

    /// nativeHere: native to the visitor's ecoregion, and not flagged as introduced in the visitor's state.
    public static func nativeHere(_ plantId: String, _ loc: Location, geo: [String: PlantGeo]) -> Bool {
        guard let g = geo[plantId] else { return false }
        return g.eco.contains(loc.ecoregion) && !g.intro.contains(loc.stateFips)
    }
}
