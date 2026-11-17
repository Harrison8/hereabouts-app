// The website sorts names with JavaScript's localeCompare, which uses ICU's root collation order (the order
// English uses). This file gives the same order in plain Swift, with no Locale and no Foundation
// compare(options:), so an iPhone and the Linux test machine sort the same way.
//
// How ICU orders two strings, in three passes; a later pass only counts when every earlier one ties:
//   1. Primary: the letters with case and accents ignored. Spaces and punctuation are not skipped: they count,
//      and they come before digits, which come before letters. So " apple" < "1 apple" < "10 apples" <
//      "2 apples" < "a1" < "apple" (digits compare one at a time, not as numbers).
//   2. Secondary: accents. An unaccented letter comes before an accented one, and accents have a fixed order
//      among themselves, so "cote" < "coté" < "côte" < "côté".
//   3. Tertiary: case. Lower case comes before upper case, so "apple" < "Apple".
//
// The weights below were read off Node's ICU (Intl.Collator("en"), ICU 78.2) one character at a time, then
// checked against it on every pair of plant names, the strings in collation.json and over a million random
// strings built from these characters. The table covers the characters the data uses and the ones around them:
// ASCII, the no-break space, the dashes and curly quotes, a few Latin-1 signs, and any Latin letter that splits
// into a plain letter plus accents (é, ä, ñ, ç, å and so on). A character outside the table sorts after the
// letters, by its code point, which is not where the website puts it. So the table is also kept in
// scripts/app/collation-table.mjs: building the bundle fails when a plant name uses a character missing there, a
// Node test checks every weight there against ICU, and CollationTests checks this table equals it. A new character
// goes in both places (and in the Kotlin copy).
import Foundation

/// Compares two strings the way the website's `a.localeCompare(b)` does.
public func hereaboutsCompare(_ a: String, _ b: String) -> ComparisonResult {
    Collation.compare(a, b)
}

enum Collation {
    /// One character's weights for the three passes. A primary of 0 means "no primary weight": an accent mark,
    /// which only counts in the secondary pass.
    struct Weights: Sendable {
        let primary: UInt32
        let secondary: UInt32
        let tertiary: UInt32
    }

    /// The weight every plain character has in the secondary and tertiary passes. Accents, curly quotes, upper
    /// case and the no-break forms all weigh more.
    static let common: UInt32 = 5

    /// The spaces and punctuation, in ICU's order, lowest first. Characters in one group have the same primary
    /// weight and differ only in a later pass. Digits and letters follow these.
    private static let punctuation: [[UInt32]] = [
        [0x0009], [0x000A], [0x000B], [0x000C], [0x000D], [0x0020, 0x00A0], [0x005F], [0x002D], [0x2010, 0x2011],
        [0x2012], [0x2013], [0x2014], [0x2015], [0x002C], [0x003B], [0x003A], [0x0021], [0x00A1], [0x003F], [0x00BF],
        [0x002E], [0x00B7], [0x0027, 0x2018, 0x2019], [0x0022, 0x201C, 0x201D], [0x00AB], [0x00BB], [0x0028],
        [0x0029], [0x005B], [0x005D], [0x007B], [0x007D], [0x00A7], [0x00B6], [0x0040], [0x002A], [0x002F],
        [0x005C], [0x0026], [0x0023], [0x0025], [0x0060], [0x005E], [0x00B0], [0x00A9], [0x00AE], [0x002B],
        [0x00D7], [0x003C], [0x003D], [0x003E], [0x007C], [0x007E], [0x0024],
    ]

    /// Curly quotes sort with the straight quote in the first pass and after it in the second:
    /// ' < ‘ < ’ and " < “ < ”.
    private static let secondaryOf: [UInt32: UInt32] = [0x2018: 6, 0x2019: 7, 0x201C: 6, 0x201D: 7]

    /// The no-break space and the no-break hyphen sort with the space and the hyphen (U+2010) until the third pass.
    private static let tertiaryOf: [UInt32: UInt32] = [0x00A0: 6, 0x2011: 6]

    /// The accent marks (combining characters) in ICU's secondary order: acute, grave, breve, circumflex, caron,
    /// ring, diaeresis, double acute, tilde, dot above, cedilla, ogonek, macron.
    private static let accents: [UInt32] = [0x0301, 0x0300, 0x0306, 0x0302, 0x030C, 0x030A, 0x0308, 0x030B, 0x0303, 0x0307, 0x0327, 0x0328, 0x0304]

    static let table: [UInt32: Weights] = {
        var t: [UInt32: Weights] = [:]
        var groups = punctuation
        for d in UInt32(0x30)...UInt32(0x39) { groups.append([d]) }
        for lower in UInt32(0x61)...UInt32(0x7A) { groups.append([lower, lower - 0x20]) } // a then A
        for (i, group) in groups.enumerated() {
            for v in group {
                let upper = v >= 0x41 && v <= 0x5A
                t[v] = Weights(primary: UInt32(i + 1), secondary: secondaryOf[v] ?? common, tertiary: upper ? 6 : tertiaryOf[v] ?? common)
            }
        }
        for (i, v) in accents.enumerated() {
            t[v] = Weights(primary: 0, secondary: 10 + UInt32(i), tertiary: common)
        }
        return t
    }()

    /// The weights of each character of `s`, after splitting accented letters into letter plus accent marks (NFD).
    static func weights(_ s: String) -> [Weights] {
        s.decomposedStringWithCanonicalMapping.unicodeScalars.map { (u: Unicode.Scalar) -> Weights in
            let v = u.value
            if let w = table[v] { return w }
            // Another accent mark: after the known ones. Anything else: after the letters, by code point.
            if v >= 0x300 && v <= 0x36F { return Weights(primary: 0, secondary: 100 + v, tertiary: common) }
            return Weights(primary: 1000 + v, secondary: common, tertiary: common)
        }
    }

    /// Compares two lists of weights item by item; a list that runs out first comes first.
    private static func compareLists(_ a: [UInt32], _ b: [UInt32]) -> ComparisonResult {
        for (x, y) in zip(a, b) where x != y {
            return x < y ? .orderedAscending : .orderedDescending
        }
        if a.count == b.count { return .orderedSame }
        return a.count < b.count ? .orderedAscending : .orderedDescending
    }

    /// The three passes. Weights of 0 (accent marks in the first pass) are left out of a pass.
    static func compare(_ a: String, _ b: String) -> ComparisonResult {
        let wa = weights(a)
        let wb = weights(b)
        let passes: [(Weights) -> UInt32] = [{ $0.primary }, { $0.secondary }, { $0.tertiary }]
        for pass in passes {
            let r = compareLists(wa.map(pass).filter { $0 != 0 }, wb.map(pass).filter { $0 != 0 })
            if r != .orderedSame { return r }
        }
        return .orderedSame
    }

    /// The comparison as a number, like a JavaScript sort callback: negative, 0 or positive.
    static func order(_ a: String, _ b: String) -> Int {
        switch hereaboutsCompare(a, b) {
        case .orderedAscending: return -1
        case .orderedSame: return 0
        case .orderedDescending: return 1
        }
    }
}
