// The JavaScript string operations the website's scripts use (location.js, the plant list and the buy links),
// done over Unicode scalars so they give the same answer as JavaScript does on the same text. Location.swift,
// PlantList.swift, Sellers.swift and Links.swift share them.

/// A string as a list of Unicode scalars, the unit these operations work on.
typealias Scalars = [Unicode.Scalar]

enum JSText {
    /// JavaScript's \s and the characters String.trim() removes: WhiteSpace and LineTerminator in the ECMAScript spec.
    static let spaceValues: Set<UInt32> = [
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006,
        0x2007, 0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF,
    ]

    /// Whether JavaScript's \s matches this character.
    static func isSpace(_ u: Unicode.Scalar) -> Bool { spaceValues.contains(u.value) }

    /// The string's Unicode scalars.
    static func scalars(_ s: String) -> Scalars { Array(s.unicodeScalars) }

    /// A string made from Unicode scalars.
    static func string<C: Collection>(_ a: C) -> String where C.Element == Unicode.Scalar {
        var view = String.UnicodeScalarView()
        view.append(contentsOf: a)
        return String(view)
    }

    /// String.prototype.trim().
    static func trimmed<C: Collection>(_ a: C) -> Scalars where C.Element == Unicode.Scalar {
        let all = Array(a)
        var start = 0
        var end = all.count
        while start < end && isSpace(all[start]) { start += 1 }
        while end > start && isSpace(all[end - 1]) { end -= 1 }
        return Array(all[start..<end])
    }

    /// Math.round(x): the nearest whole number, with halves going up (toward +infinity), so -2.5 gives -2.
    static func mathRound(_ x: Double) -> Double {
        let down = x.rounded(.down)
        let r = x - down >= 0.5 ? down + 1 : down
        return r == 0 && x < 0 ? -0.0 : r
    }

    /// x.toFixed(digits) for 0 to 3 digits: the exact value of the number rounded to `digits` decimals, an exact half
    /// going to the larger number. So 0.25 gives "0.3" and 1.25 gives "1.3" (both are exact halves in binary), but
    /// 0.35 gives "0.3", because the number stored for 0.35 is a little below it. Swift's own formatting rounds halves
    /// to even, so it would give "0.2" and "1.2". A negative number keeps its "-" even when it rounds to zero ("-0.0").
    static func toFixed(_ x: Double, _ digits: Int) -> String {
        precondition((0...3).contains(digits), "toFixed is written for 0 to 3 digits")
        if !x.isFinite || abs(x) >= 1e21 { return numberString(x) }
        // x < 0 is false for -0, so (-0).toFixed(1) is "0.0", as in JavaScript.
        let sign = x < 0 ? "-" : ""
        let a = abs(x)
        // a = m * 2^e exactly, with m a whole number below 2^53.
        let m: UInt64
        let e: Int
        if a == 0 {
            m = 0
            e = 0
        } else if a.exponentBitPattern == 0 {
            m = a.significandBitPattern
            e = -1074
        } else {
            m = a.significandBitPattern | (1 << 52)
            e = Int(a.exponentBitPattern) - 1075
        }
        if e >= 0 {
            // A whole number of at least 2^52. JavaScript writes its exact digits; the shortest form used here is the
            // same up to 2^53 and can differ in the last digits above that (no plant is that tall).
            return numberString(x) + (digits > 0 ? "." + String(repeating: "0", count: digits) : "")
        }
        let scale: UInt64 = [1, 10, 100, 1000][digits]
        var n: UInt64
        let k = -e
        if k >= 64 {
            // a is below 2^-11, so a * 1000 is below one half: it rounds to 0.
            n = 0
        } else {
            // n = round(m * scale / 2^k), an exact half going up. m * scale is below 2^63.
            let p = m * scale
            let mask: UInt64 = (1 << UInt64(k)) - 1
            n = p >> UInt64(k)
            if p & mask >= (1 << UInt64(k - 1)) { n += 1 }
        }
        var text = String(n)
        if digits > 0 {
            if text.count <= digits { text = String(repeating: "0", count: digits + 1 - text.count) + text }
            text.insert(".", at: text.index(text.endIndex, offsetBy: -digits))
        }
        return sign + text
    }

    /// s.charAt(0).toUpperCase() + s.slice(1). charAt works on UTF-16 units, so a first character outside the Basic
    /// Multilingual Plane is half a surrogate pair there, which toUpperCase leaves alone: such a string is unchanged.
    static func upperFirst(_ s: String) -> String {
        let a = scalars(s)
        guard let first = a.first else { return "" }
        if first.value > 0xFFFF { return s }
        return String(Character(first)).uppercased() + string(a.dropFirst())
    }

    /// a < b for JavaScript strings: a comparison of UTF-16 code units, which is what the default Array.sort and the
    /// `<` operator use. It differs from Swift's String `<` for some characters outside the Basic Multilingual Plane.
    static func utf16Less(_ a: String, _ b: String) -> Bool {
        a.utf16.lexicographicallyPrecedes(b.utf16)
    }

    /// .replace(/\s+/g, " ").
    static func collapsedSpaces(_ a: Scalars) -> Scalars {
        var out: Scalars = []
        var inSpace = false
        for u in a {
            if isSpace(u) {
                if !inSpace { out.append(" ") }
                inSpace = true
            } else {
                out.append(u)
                inSpace = false
            }
        }
        return out
    }

    /// encodeURIComponent: every UTF-8 byte percent-encoded in upper case, except letters, digits and - _ . ! ~ * ' ( ).
    static func encodeURIComponent(_ s: String) -> String {
        let kept = Set("-_.!~*'()".utf8)
        var out = ""
        for b in s.utf8 {
            let isAlnum = (b >= 0x30 && b <= 0x39) || (b >= 0x41 && b <= 0x5A) || (b >= 0x61 && b <= 0x7A)
            if isAlnum || kept.contains(b) {
                out.unicodeScalars.append(Unicode.Scalar(b))
            } else {
                let hex = Array("0123456789ABCDEF")
                out += "%\(hex[Int(b >> 4)])\(hex[Int(b & 0x0F)])"
            }
        }
        return out
    }

    /// A number as JavaScript's String(x) writes it (Number::toString): the shortest digits that read back as the
    /// same number, as Swift's own description gives them, laid out JavaScript's way: "38" not "38.0", "-0" as "0",
    /// plain decimals from 1e-6 up to 1e21 and an exponent like "1e-7" or "1.25e+22" outside that.
    static func numberString(_ x: Double) -> String {
        if x.isNaN { return "NaN" }
        if x.isInfinite { return x < 0 ? "-Infinity" : "Infinity" }
        if x == 0 { return "0" }
        // Swift writes "38.0", "0.001", "1e-05", "1.5e-07" or "1e+16": split it into digits and where the point goes.
        var text = Substring(abs(x).description)
        var exponent = 0
        if let e = text.firstIndex(where: { $0 == "e" || $0 == "E" }) {
            exponent = Int(String(text[text.index(after: e)...].filter { $0 != "+" })) ?? 0
            text = text[..<e]
        }
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        let whole = parts.first.map(String.init) ?? ""
        let fraction = parts.count > 1 ? String(parts[1]) : ""
        var digits = Array(whole + fraction)
        // n: where the decimal point goes, counted in digits from the left (zero or less when the number is below 1).
        var n = whole.count + exponent
        while let first = digits.first, first == "0" {
            digits.removeFirst()
            n -= 1
        }
        while let last = digits.last, last == "0" { digits.removeLast() }
        let k = digits.count
        let s = String(digits)
        let out: String
        if k <= n && n <= 21 {
            out = s + String(repeating: "0", count: n - k)
        } else if 0 < n && n <= 21 {
            out = String(digits[..<n]) + "." + String(digits[n...])
        } else if -6 < n && n <= 0 {
            out = "0." + String(repeating: "0", count: -n) + s
        } else {
            let e = n - 1
            let sign = e >= 0 ? "+" : "-"
            out = (k == 1 ? s : String(digits[0]) + "." + String(digits[1...])) + "e" + sign + String(abs(e))
        }
        return x < 0 ? "-" + out : out
    }
}
