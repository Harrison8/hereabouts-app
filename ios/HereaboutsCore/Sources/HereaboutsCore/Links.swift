// The links a visitor follows to a seller, ported line by line from the website's affiliates.js (buyUrl, subId and
// the Garden for Wildlife program) and from index.html (safeHref, the step that guards the buy button, and
// directionsHref, the Google Maps link for a shop nearby).
//
// buyUrl uses JavaScript's `new URL(...)` and `searchParams.set`, which re-write the whole address the WHATWG URL
// way. Foundation's URLComponents encodes differently, so WebURL below does that work in plain Swift, the same on
// Linux and on an iPhone. It covers what the sellers' addresses need, and the tests in links.json check every one.
// An http or https address of a shape it does not cover (a user name, an IP address, a host outside ASCII) keeps a
// working button: it goes out as written, without the utm tags.

/// Where a click comes from: the website, or the phone app (which tags affiliate links with "__app").
public enum Channel: String, Sendable, Hashable {
    case web
    case app
}

public enum Links {
    /// The site's own name, which the app always reports as the place a click came from (siteDomain() in index.html
    /// with HEREABOUTS_APP set).
    public static let siteDomain = "hereabouts.garden"

    /// Garden for Wildlife's code for us (GFW_REF).
    static let gardenForWildlifeRef = "12722502.jRlMYFbpTI8"

    /// subId: the tag that tells an affiliate program which ecoregion and plant a click came from, like
    /// "71__asclepias-tuberosa", with "__app" added for a click from the phone app.
    public static func subId(ecoregion: String?, plantId: String, channel: Channel) -> String {
        // `ecoregion || 'none'`: an empty code counts as none.
        let eco = (ecoregion?.isEmpty ?? true) ? "none" : ecoregion!
        return "\(eco)__\(plantId)\(channel == .app ? "__app" : "")"
    }

    /// buyUrl: the link for one seller row. A seller with an affiliate program gets that program's link; every other
    /// http or https link gets utm tags saying the visit came from this site. Any other link, or one that cannot be
    /// read, comes back unchanged, and an entry with no address gives nil (JavaScript's undefined).
    public static func buyUrl(entry: VendorEntry, vendor: Vendor, ecoregion: String?, plantId: String, channel: Channel,
                              siteDomain: String) -> String? {
        // `vendor.affiliate && entry.url ? programs[vendor.name] : null`: an empty address gets no program link.
        if vendor.affiliate == true, let url = entry.url, !url.isEmpty {
            // The AFFILIATES table. A program that throws falls through to the tracked link.
            switch vendor.name {
            case "Garden for Wildlife":
                if let link = gardenForWildlifeLink(url, subId(ecoregion: ecoregion, plantId: plantId, channel: channel)) {
                    return link
                }
            default:
                break
            }
        }
        // new URL(undefined) throws, and the catch returns entry.url: undefined.
        guard let url = entry.url else { return nil }
        // A link that does not parse, or is not http or https, comes back as written, as on the website. So does an
        // http or https link of a shape WebURL does not cover; the website would add the utm tags to that one.
        guard case .web(var link) = WebURL.read(url) else { return url }
        // `ctx.siteDomain || 'none'` and `ctx.ecoregion || 'none'`: empty text counts as none.
        link.setQueryParameter("utm_source", siteDomain.isEmpty ? "none" : siteDomain)
        link.setQueryParameter("utm_medium", "referral")
        link.setQueryParameter("utm_campaign", (ecoregion?.isEmpty ?? true) ? "none" : ecoregion!)
        return link.serialized
    }

    /// gardenForWildlifeLink: the shop's product address with our code in sca_ref and the tag in sca_source. The tags
    /// are added to the text as written, so the shop's own query and fragment stay exactly as they were. nil where
    /// the website throws (not https://gardenforwildlife.com), and buyUrl falls back to the tracked link.
    static func gardenForWildlifeLink(_ productUrl: String, _ subid: String) -> String? {
        guard let link = WebURL(productUrl), link.scheme == "https", link.host == "gardenforwildlife.com" else { return nil }
        // productUrl.indexOf('#'), then slice: the first "#" in the text as written. JavaScript looks at single code
        // units, so this looks at code points, not at whole characters: a "#" followed by an accent mark is one
        // Character in Swift, and searching Characters would miss it.
        let scalars = productUrl.unicodeScalars
        let base: String
        let fragment: String
        if let hash = scalars.firstIndex(of: "#") {
            base = String(String.UnicodeScalarView(scalars[..<hash]))
            fragment = String(String.UnicodeScalarView(scalars[hash...]))
        } else {
            base = productUrl
            fragment = ""
        }
        let sep = base.unicodeScalars.contains("?") ? "&" : "?"
        return "\(base)\(sep)sca_ref=\(gardenForWildlifeRef)&sca_source=\(JSText.encodeURIComponent(subid))\(fragment)"
    }

    /// directionsHref: a Google Maps directions link to a seller's shop, or nil when the seller has no point.
    /// The visitor's own location is not in it.
    public static func directionsURL(_ v: Vendor) -> String? {
        // `!Array.isArray(c) || c.length !== 2`
        guard let c = v.coords, c.count == 2 else { return nil }
        // c[0] + ',' + c[1] writes each number the way JavaScript does (38, not 38.0).
        let point = JSText.numberString(c[0]) + "," + JSText.numberString(c[1])
        return "https://www.google.com/maps/dir/?api=1&destination=\(JSText.encodeURIComponent(point))"
    }

    /// safeHref: the link itself when it reads as an http or https address, "#" for anything else (a mailto: link,
    /// javascript:, plain text, nothing). The link is passed through as written, not tidied. An http or https link
    /// of a shape WebURL does not cover is kept, so its button still works.
    public static func safeHref(_ url: String?) -> String {
        // new URL(undefined) reads the text "undefined", which does not parse.
        guard let url else { return "#" }
        if case .notWeb = WebURL.read(url) { return "#" }
        return url
    }
}

// MARK: - The WHATWG URL steps buyUrl needs

/// An http or https address read the way a browser's `new URL(...)` reads it, and written back the way
/// `url.toString()` writes it. Only the parts the sellers' addresses use are supported. `read` tells three results
/// apart: an address it can rewrite (`.web`); an http or https address of a shape it does not cover (`.unsupported`):
/// a user name or password ("user@host"), a host with letters outside ASCII (which would need IDNA), or a host that is
/// an IP address; and anything else (`.notWeb`): another scheme, or text that does not parse.
///
/// No seller address has an unsupported shape today. Seller data reaches installed phones every day without an app
/// update, so the tests on links.json only cover the addresses in the data when the app was built. A seller address
/// of an unsupported shape added later still gets a working button in the app: it goes out as written, without the
/// utm tags the website would add.
struct WebURL: Sendable {
    /// What `read` made of an address.
    enum Reading: Sendable {
        case web(WebURL)
        case unsupported
        case notWeb
    }

    private enum ReadError: Error {
        case unsupported
        case notWeb
    }

    /// Reads an address. See the type's comment for the three results.
    static func read(_ input: String) -> Reading {
        do {
            return .web(try WebURL(parsing: input))
        } catch ReadError.unsupported {
            return .unsupported
        } catch {
            return .notWeb
        }
    }

    /// An address `read` can rewrite, or nil.
    init?(_ input: String) {
        guard case .web(let url) = Self.read(input) else { return nil }
        self = url
    }

    /// "http" or "https", in lower case.
    let scheme: String
    /// The host in lower case, like "example.com".
    let host: String
    /// The port as written back, or nil for none or the scheme's default (80 for http, 443 for https).
    let port: String?
    /// The path's segments, percent-encoded. "/" is [""].
    let path: [String]
    /// The text after "?", as written (tabs and line breaks removed), or nil when there is no "?".
    var query: String?
    /// The text after the first "#", percent-encoded, or nil when there is no "#".
    let fragment: String?

    // The parser works on code points as UInt32 numbers, so these are numbers too.
    private static let slash: UInt32 = 0x2F
    private static let backslash: UInt32 = 0x5C
    private static let questionMark: UInt32 = 0x3F
    private static let hashMark: UInt32 = 0x23

    /// The path percent-encode set, beyond C0 controls and everything above "~": space " # < > ? ^ ` { }.
    private static let pathSet: Set<UInt32> = [0x20, 0x22, 0x23, 0x3C, 0x3E, 0x3F, 0x5E, 0x60, 0x7B, 0x7D]
    /// The fragment percent-encode set, beyond C0 controls and everything above "~": space " < > `.
    private static let fragmentSet: Set<UInt32> = [0x20, 0x22, 0x3C, 0x3E, 0x60]
    /// Forbidden domain code points, beyond C0 controls: NUL, tab, LF, CR, space # / : < > ? @ [ \ ] ^ | % and DEL.
    private static let forbiddenHost: Set<UInt8> = [
        0x00, 0x09, 0x0A, 0x0D, 0x20, 0x23, 0x2F, 0x3A, 0x3C, 0x3E, 0x3F, 0x40, 0x5B, 0x5C, 0x5D, 0x5E, 0x7C, 0x25, 0x7F,
    ]

    private static func isSlash(_ c: UInt32) -> Bool { c == slash || c == backslash }

    private init(parsing input: String) throws(ReadError) {
        // Remove leading and trailing C0 controls and spaces, then every tab and line break.
        var a = Array(input.unicodeScalars).map { $0.value }
        while let first = a.first, first <= 0x20 { a.removeFirst() }
        while let last = a.last, last <= 0x20 { a.removeLast() }
        a.removeAll { $0 == 0x09 || $0 == 0x0A || $0 == 0x0D }

        // The scheme: the text before the first ":". Only http and https are read (case does not matter); any other
        // scheme, or text that is not a valid scheme, is not a web address. This check comes first, so a mailto: link
        // or plain text is never taken for an unsupported web address.
        guard let colon = a.firstIndex(of: 0x3A) else { throw ReadError.notWeb }
        let scheme = String(decoding: a[..<colon].map { UInt8(truncatingIfNeeded: $0) }, as: UTF8.self).lowercased()
        guard a[..<colon].allSatisfy({ $0 < 0x80 }), scheme == "http" || scheme == "https" else { throw ReadError.notWeb }
        self.scheme = scheme
        var i = colon + 1

        // http and https skip any run of slashes or backslashes before the host ("https:example.com" works too).
        while i < a.count && Self.isSlash(a[i]) { i += 1 }

        // The authority: up to the first / \ ? or #.
        var j = i
        while j < a.count && !Self.isSlash(a[j]) && a[j] != Self.questionMark && a[j] != Self.hashMark { j += 1 }
        let authority = Array(a[i..<j])
        // From here on, a shape WebURL does not cover sets `unsupported`; the checks after it still run, so an
        // address the browser rejects (a bad port, an empty host) is still not a web address.
        var unsupported = false
        // "@": a user name or password, not supported. The host is what follows the last "@".
        var hostAndPort = authority
        if let at = authority.lastIndex(of: 0x40) {
            unsupported = true
            hostAndPort = Array(authority[(at + 1)...])
        }
        // "[": an IPv6 address, not supported.
        if hostAndPort.first == 0x5B { throw ReadError.unsupported }
        let portColon = hostAndPort.firstIndex(of: 0x3A)
        let hostPart = portColon.map { Array(hostAndPort[..<$0]) } ?? hostAndPort

        // The port: digits only; empty means none; above 65535 fails; the scheme's default is dropped.
        var port: String? = nil
        if let portColon {
            let digits = hostAndPort[(portColon + 1)...]
            guard digits.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }) else { throw ReadError.notWeb }
            if !digits.isEmpty {
                var n = 0
                for d in digits {
                    n = n * 10 + Int(d - 0x30)
                    if n > 65535 { throw ReadError.notWeb }
                }
                if n != (scheme == "http" ? 80 : 443) { port = String(n) }
            }
        }
        self.port = port

        // The host: percent-decoded, then lower-cased. Bytes outside ASCII would need IDNA (not supported); a
        // forbidden code point or an empty host fails, as in the browser.
        let hostBytes = Self.percentDecoded(Self.utf8(hostPart))
        guard !hostBytes.isEmpty else { throw ReadError.notWeb }
        for b in hostBytes {
            if b >= 0x80 {
                unsupported = true
            } else if b < 0x20 || Self.forbiddenHost.contains(b) {
                throw ReadError.notWeb
            }
        }
        let host = String(decoding: hostBytes, as: UTF8.self).lowercased()
        // A host whose last label is a number ("1.2.3.4", "0x7f") is an IP address: not supported.
        var labels = host.split(separator: ".", omittingEmptySubsequences: false)
        if labels.count > 1 && labels.last == "" { labels.removeLast() }
        let last = labels.last ?? ""
        if !last.isEmpty && last.allSatisfy({ $0.isASCII && $0.isNumber }) { unsupported = true }
        if last.hasPrefix("0x") && last.dropFirst(2).allSatisfy({ $0.isHexDigit && $0.isASCII }) { unsupported = true }
        if unsupported { throw ReadError.unsupported }
        self.host = host

        // The path: up to "?" or "#". "/" and "\" both separate segments; "." and ".." segments (also written %2e)
        // are resolved the way the browser does.
        var k = j
        while k < a.count && a[k] != Self.questionMark && a[k] != Self.hashMark { k += 1 }
        var rawPath = a[j..<k]
        if let first = rawPath.first, Self.isSlash(first) { rawPath = rawPath.dropFirst() }
        var buffers: [[UInt32]] = [[]]
        for c in rawPath {
            if Self.isSlash(c) { buffers.append([]) } else { buffers[buffers.count - 1].append(c) }
        }
        var path: [String] = []
        for (n, buffer) in buffers.enumerated() {
            let endsInSlash = n < buffers.count - 1
            let segment = Self.percentEncoded(buffer, Self.pathSet)
            let lower = segment.lowercased()
            if lower == ".." || lower == ".%2e" || lower == "%2e." || lower == "%2e%2e" {
                if !path.isEmpty { path.removeLast() }
                if !endsInSlash { path.append("") }
            } else if lower == "." || lower == "%2e" {
                if !endsInSlash { path.append("") }
            } else {
                path.append(segment)
            }
        }
        self.path = path

        // The query (kept as written; setQueryParameter re-reads it) and the fragment (after the first "#").
        var m = k
        var query: String? = nil
        if m < a.count && a[m] == Self.questionMark {
            var q = m + 1
            while q < a.count && a[q] != Self.hashMark { q += 1 }
            query = Self.string(a[(m + 1)..<q])
            m = q
        }
        self.query = query
        self.fragment = m < a.count && a[m] == Self.hashMark ? Self.percentEncoded(a[(m + 1)...], Self.fragmentSet) : nil
    }

    /// url.toString(): scheme://host[:port]/path[?query][#fragment].
    var serialized: String {
        var out = "\(scheme)://\(host)"
        if let port { out += ":\(port)" }
        out += "/" + path.joined(separator: "/")
        if let query { out += "?\(query)" }
        if let fragment { out += "#\(fragment)" }
        return out
    }

    /// url.searchParams.set(name, value): the first pair with this name gets the value and any later ones are
    /// removed; with none, the pair goes on the end. The whole query is then written back as
    /// application/x-www-form-urlencoded, so "flag" becomes "flag=", spaces become "+", and so on.
    mutating func setQueryParameter(_ name: String, _ value: String) {
        var pairs = Self.formParsed(query ?? "")
        if let first = pairs.firstIndex(where: { $0.name == name }) {
            pairs[first].value = value
            var kept: [(name: String, value: String)] = []
            for (n, pair) in pairs.enumerated() where n <= first || pair.name != name { kept.append(pair) }
            pairs = kept
        } else {
            pairs.append((name: name, value: value))
        }
        query = pairs.map { Self.formEncoded($0.name) + "=" + Self.formEncoded($0.value) }.joined(separator: "&")
    }

    // MARK: Helpers

    private static func string<C: Collection>(_ a: C) -> String where C.Element == UInt32 {
        JSText.string(a.compactMap { Unicode.Scalar($0) })
    }

    /// The UTF-8 bytes of a list of code points.
    private static func utf8<C: Collection>(_ a: C) -> [UInt8] where C.Element == UInt32 {
        Array(string(a).utf8)
    }

    private static let hexDigits = Array("0123456789ABCDEF".utf8)

    private static func percent(_ b: UInt8) -> String {
        String(decoding: [0x25, hexDigits[Int(b >> 4)], hexDigits[Int(b & 0x0F)]], as: UTF8.self)
    }

    /// Percent-encodes C0 controls, everything above "~", and `set`, as UTF-8 bytes. A "%" already there stays.
    private static func percentEncoded<C: Collection>(_ a: C, _ set: Set<UInt32>) -> String where C.Element == UInt32 {
        var out = ""
        for c in a {
            guard let scalar = Unicode.Scalar(c) else { continue }
            if c < 0x20 || c > 0x7E || set.contains(c) {
                for b in String(Character(scalar)).utf8 { out += percent(b) }
            } else {
                out.unicodeScalars.append(scalar)
            }
        }
        return out
    }

    private static func hexValue(_ b: UInt8) -> UInt8? {
        switch b {
        case 0x30...0x39: return b - 0x30
        case 0x41...0x46: return b - 0x41 + 10
        case 0x61...0x66: return b - 0x61 + 10
        default: return nil
        }
    }

    /// Percent-decoding: "%" and two hex digits become that byte; any other "%" stays as it is.
    private static func percentDecoded(_ bytes: [UInt8]) -> [UInt8] {
        var out: [UInt8] = []
        var i = 0
        while i < bytes.count {
            if bytes[i] == 0x25, i + 2 < bytes.count, let hi = hexValue(bytes[i + 1]), let lo = hexValue(bytes[i + 2]) {
                out.append(hi * 16 + lo)
                i += 3
            } else {
                out.append(bytes[i])
                i += 1
            }
        }
        return out
    }

    /// application/x-www-form-urlencoded parsing: split on "&" (skipping empty parts), name and value at the first
    /// "=", "+" read as a space, then percent-decoded and read as UTF-8 (a broken sequence becomes U+FFFD).
    private static func formParsed(_ query: String) -> [(name: String, value: String)] {
        var out: [(name: String, value: String)] = []
        for part in Array(query.utf8).split(separator: 0x26, omittingEmptySubsequences: true) {
            let bytes = Array(part)
            let eq = bytes.firstIndex(of: 0x3D)
            let name = eq.map { Array(bytes[..<$0]) } ?? bytes
            let value = eq.map { Array(bytes[($0 + 1)...]) } ?? []
            func decode(_ b: [UInt8]) -> String {
                String(decoding: percentDecoded(b.map { $0 == 0x2B ? 0x20 : $0 }), as: UTF8.self)
            }
            out.append((name: decode(name), value: decode(value)))
        }
        return out
    }

    /// The application/x-www-form-urlencoded byte serializer: space becomes "+"; letters, digits and * - . _ stay;
    /// every other UTF-8 byte is percent-encoded in upper case.
    private static func formEncoded(_ s: String) -> String {
        var out = ""
        for b in s.utf8 {
            switch b {
            case 0x20: out += "+"
            case 0x30...0x39, 0x41...0x5A, 0x61...0x7A, 0x2A, 0x2D, 0x2E, 0x5F: out.unicodeScalars.append(Unicode.Scalar(b))
            default: out += percent(b)
            }
        }
        return out
    }
}
