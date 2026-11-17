// The links a visitor follows to a seller, ported line by line from the website's affiliates.js (buyUrl, subId and
// the Garden for Wildlife program) and from index.html (safeHref, the step that guards the buy button, and
// directionsHref, the Google Maps link for a shop nearby). The same as the iPhone app's Links.swift.
//
// buyUrl uses JavaScript's `new URL(...)` and `searchParams.set`, which re-write the whole address the WHATWG URL
// way. java.net.URI and URLEncoder encode differently, so WebUrl below does that work by hand. It covers what the
// sellers' addresses need, and the tests in links.json check every one. An http or https address of a shape it does
// not cover (a user name, an IP address, a host outside ASCII) keeps a working button: it goes out as written,
// without the utm tags.
package garden.hereabouts.core

/** Where a click comes from: the website, or the phone app (which tags affiliate links with "__app"). */
enum class Channel(val key: String) {
    Web("web"),
    App("app"),
    ;

    companion object {
        fun fromKey(key: String): Channel? = entries.firstOrNull { it.key == key }
    }
}

object Links {
    /**
     * The site's own name, which the app always reports as the place a click came from (siteDomain() in index.html
     * with HEREABOUTS_APP set).
     */
    const val SITE_DOMAIN = "hereabouts.garden"

    /** Garden for Wildlife's code for us (GFW_REF). */
    internal const val GARDEN_FOR_WILDLIFE_REF = "12722502.jRlMYFbpTI8"

    /**
     * subId: the tag that tells an affiliate program which ecoregion and plant a click came from, like
     * "71__asclepias-tuberosa", with "__app" added for a click from the phone app.
     */
    fun subId(ecoregion: String?, plantId: String, channel: Channel): String {
        // `ecoregion || 'none'`: an empty code counts as none.
        val eco = if (ecoregion.isNullOrEmpty()) "none" else ecoregion
        return "${eco}__$plantId${if (channel == Channel.App) "__app" else ""}"
    }

    /**
     * buyUrl: the link for one seller row. A seller with an affiliate program gets that program's link; every other
     * http or https link gets utm tags saying the visit came from this site. Any other link, or one that cannot be
     * read, comes back unchanged, and an entry with no address gives null (JavaScript's undefined).
     */
    fun buyUrl(entry: VendorEntry, vendor: Vendor, ecoregion: String?, plantId: String, channel: Channel, siteDomain: String): String? {
        // `vendor.affiliate && entry.url ? programs[vendor.name] : null`: an empty address gets no program link.
        val programUrl = entry.url
        if (vendor.affiliate == true && !programUrl.isNullOrEmpty()) {
            // The AFFILIATES table. A program that throws falls through to the tracked link.
            if (vendor.name == "Garden for Wildlife") {
                gardenForWildlifeLink(programUrl, subId(ecoregion, plantId, channel))?.let { return it }
            }
        }
        // new URL(undefined) throws, and the catch returns entry.url: undefined.
        val url = entry.url ?: return null
        // A link that does not parse, or is not http or https, comes back as written, as on the website. So does an
        // http or https link of a shape WebUrl does not cover; the website would add the utm tags to that one.
        val link = (WebUrl.read(url) as? WebUrl.Reading.Web)?.url ?: return url
        // `ctx.siteDomain || 'none'` and `ctx.ecoregion || 'none'`: empty text counts as none.
        link.setQueryParameter("utm_source", siteDomain.ifEmpty { "none" })
        link.setQueryParameter("utm_medium", "referral")
        link.setQueryParameter("utm_campaign", if (ecoregion.isNullOrEmpty()) "none" else ecoregion)
        return link.serialized
    }

    /**
     * gardenForWildlifeLink: the shop's product address with our code in sca_ref and the tag in sca_source. The tags
     * are added to the text as written, so the shop's own query and fragment stay exactly as they were. null where
     * the website throws (not https://gardenforwildlife.com, or a tag encodeURIComponent cannot write), and buyUrl
     * falls back to the tracked link.
     */
    internal fun gardenForWildlifeLink(productUrl: String, subid: String): String? {
        val link = WebUrl.of(productUrl) ?: return null
        if (link.scheme != "https" || link.host != "gardenforwildlife.com") return null
        // productUrl.indexOf('#'), then slice: JavaScript looks at single UTF-16 units, as Kotlin's indexOf does.
        val hash = productUrl.indexOf('#')
        val base = if (hash >= 0) productUrl.substring(0, hash) else productUrl
        val fragment = if (hash >= 0) productUrl.substring(hash) else ""
        val sep = if (base.contains('?')) "&" else "?"
        val tag = JsText.encodeURIComponent(subid) ?: return null
        return "$base${sep}sca_ref=$GARDEN_FOR_WILDLIFE_REF&sca_source=$tag$fragment"
    }

    /**
     * directionsHref: a Google Maps directions link to a seller's shop, or null when the seller has no point.
     * The visitor's own location is not in it.
     */
    fun directionsUrl(v: Vendor): String? {
        // `!Array.isArray(c) || c.length !== 2`
        val c = v.coords
        if (c == null || c.size != 2) return null
        // c[0] + ',' + c[1] writes each number the way JavaScript does (38, not 38.0). The text is ASCII, so
        // encodeURIComponent cannot fail on it.
        val point = JsText.numberString(c[0]) + "," + JsText.numberString(c[1])
        return "https://www.google.com/maps/dir/?api=1&destination=" + (JsText.encodeURIComponent(point) ?: return null)
    }

    /**
     * safeHref: the link itself when it reads as an http or https address, "#" for anything else (a mailto: link,
     * javascript:, plain text, nothing). The link is passed through as written, not tidied. An http or https link of
     * a shape WebUrl does not cover is kept, so its button still works.
     */
    fun safeHref(url: String?): String {
        // new URL(undefined) reads the text "undefined", which does not parse.
        if (url == null) return "#"
        if (WebUrl.read(url) is WebUrl.Reading.NotWeb) return "#"
        return url
    }
}

/**
 * An http or https address read the way a browser's `new URL(...)` reads it, and written back the way
 * `url.toString()` writes it. Only the parts the sellers' addresses use are supported. `read` tells three results
 * apart: an address it can rewrite (Web); an http or https address of a shape it does not cover (Unsupported): a
 * user name or password ("user@host"), a host with letters outside ASCII (which would need IDNA), or a host that is
 * an IP address; and anything else (NotWeb): another scheme, or text that does not parse.
 *
 * No seller address has an unsupported shape today. Seller data reaches installed phones every day without an app
 * update, so the tests on links.json only cover the addresses in the data when the app was built. A seller address
 * of an unsupported shape added later still gets a working button in the app: it goes out as written, without the
 * utm tags the website would add.
 */
internal class WebUrl private constructor(
    /** "http" or "https", in lower case. */
    val scheme: String,
    /** The host in lower case, like "example.com". */
    val host: String,
    /** The port as written back, or null for none or the scheme's default (80 for http, 443 for https). */
    val port: String?,
    /** The path's segments, percent-encoded. "/" is [""]. */
    val path: List<String>,
    /** The text after "?", as written (tabs and line breaks removed), or null when there is no "?". */
    var query: String?,
    /** The text after the first "#", percent-encoded, or null when there is no "#". */
    val fragment: String?,
) {
    /** What `read` made of an address. */
    sealed interface Reading {
        class Web(val url: WebUrl) : Reading
        data object Unsupported : Reading
        data object NotWeb : Reading
    }

    private class ReadError(val unsupported: Boolean) : Exception()

    /** url.toString(): scheme://host[:port]/path[?query][#fragment]. */
    val serialized: String
        get() {
            val out = StringBuilder("$scheme://$host")
            if (port != null) out.append(':').append(port)
            out.append('/').append(path.joinToString("/"))
            query?.let { out.append('?').append(it) }
            fragment?.let { out.append('#').append(it) }
            return out.toString()
        }

    /**
     * url.searchParams.set(name, value): the first pair with this name gets the value and any later ones are
     * removed; with none, the pair goes on the end. The whole query is then written back as
     * application/x-www-form-urlencoded, so "flag" becomes "flag=", spaces become "+", and so on.
     */
    fun setQueryParameter(name: String, value: String) {
        val pairs = formParsed(query ?: "")
        val first = pairs.indexOfFirst { it.first == name }
        val next: List<Pair<String, String>> = if (first >= 0) {
            // Everything up to and including the first match keeps its place; later pairs with this name go.
            pairs.filterIndexed { n, pair -> n <= first || pair.first != name }.toMutableList().also { it[first] = name to value }
        } else {
            pairs + (name to value)
        }
        query = next.joinToString("&") { formEncoded(it.first) + "=" + formEncoded(it.second) }
    }

    companion object {
        private const val SLASH = 0x2F
        private const val BACKSLASH = 0x5C
        private const val QUESTION_MARK = 0x3F
        private const val HASH_MARK = 0x23

        /** The path percent-encode set, beyond C0 controls and everything above "~": space " # < > ? ^ ` { }. */
        private val pathSet = setOf(0x20, 0x22, 0x23, 0x3C, 0x3E, 0x3F, 0x5E, 0x60, 0x7B, 0x7D)

        /** The fragment percent-encode set, beyond C0 controls and everything above "~": space " < > `. */
        private val fragmentSet = setOf(0x20, 0x22, 0x3C, 0x3E, 0x60)

        /** Forbidden domain code points, beyond C0 controls: NUL, tab, LF, CR, space # / : < > ? @ [ \ ] ^ | % and DEL. */
        private val forbiddenHost = setOf(
            0x00, 0x09, 0x0A, 0x0D, 0x20, 0x23, 0x2F, 0x3A, 0x3C, 0x3E, 0x3F, 0x40, 0x5B, 0x5C, 0x5D, 0x5E, 0x7C, 0x25, 0x7F,
        )

        private const val HEX = "0123456789ABCDEF"

        private fun isSlash(c: Int) = c == SLASH || c == BACKSLASH

        /** Reads an address. See the type's comment for the three results. */
        fun read(input: String): Reading = try {
            Reading.Web(parse(input))
        } catch (e: ReadError) {
            if (e.unsupported) Reading.Unsupported else Reading.NotWeb
        }

        /** An address `read` can rewrite, or null. */
        fun of(input: String): WebUrl? = (read(input) as? Reading.Web)?.url

        /**
         * The text's code points, as new URL() reads it: JavaScript passes a USVString, so a lone half of a
         * surrogate pair becomes U+FFFD.
         */
        private fun codePoints(s: String): IntArray {
            val out = IntArray(s.length)
            var n = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                    out[n++] = Character.toCodePoint(c, s[i + 1])
                    i += 2
                } else {
                    out[n++] = if (c.isSurrogate()) 0xFFFD else c.code
                    i++
                }
            }
            return out.copyOf(n)
        }

        /** Code points back to a string. */
        private fun string(a: List<Int>): String {
            val sb = StringBuilder(a.size)
            for (c in a) sb.appendCodePoint(c)
            return sb.toString()
        }

        /** The UTF-8 bytes of a list of code points (each 0 to 0x10FFFF and not a surrogate), as Ints 0-255. */
        private fun utf8(a: List<Int>): List<Int> {
            val out = ArrayList<Int>(a.size)
            for (c in a) JsText.appendUtf8(out, c)
            return out
        }

        private fun percent(sb: StringBuilder, b: Int) {
            sb.append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
        }

        /** Percent-encodes C0 controls, everything above "~", and `set`, as UTF-8 bytes. A "%" already there stays. */
        private fun percentEncoded(a: List<Int>, set: Set<Int>): String {
            val sb = StringBuilder()
            for (c in a) {
                if (c < 0x20 || c > 0x7E || c in set) {
                    for (b in utf8(listOf(c))) percent(sb, b)
                } else {
                    sb.append(c.toChar())
                }
            }
            return sb.toString()
        }

        private fun hexValue(b: Int): Int? = when (b) {
            in 0x30..0x39 -> b - 0x30
            in 0x41..0x46 -> b - 0x41 + 10
            in 0x61..0x66 -> b - 0x61 + 10
            else -> null
        }

        /** Percent-decoding: "%" and two hex digits become that byte; any other "%" stays as it is. */
        private fun percentDecoded(bytes: List<Int>): List<Int> {
            val out = ArrayList<Int>(bytes.size)
            var i = 0
            while (i < bytes.size) {
                val hi = if (bytes[i] == 0x25 && i + 2 < bytes.size) hexValue(bytes[i + 1]) else null
                val lo = if (hi != null) hexValue(bytes[i + 2]) else null
                if (hi != null && lo != null) {
                    out.add(hi * 16 + lo)
                    i += 3
                } else {
                    out.add(bytes[i])
                    i++
                }
            }
            return out
        }

        /**
         * application/x-www-form-urlencoded parsing: split on "&" (skipping empty parts), name and value at the first
         * "=", "+" read as a space, then percent-decoded and read as UTF-8 (a broken sequence becomes U+FFFD).
         */
        private fun formParsed(query: String): List<Pair<String, String>> {
            val bytes = utf8(codePoints(query).toList())
            val out = ArrayList<Pair<String, String>>()
            fun decode(b: List<Int>): String = JsText.decodeUtf8(percentDecoded(b.map { if (it == 0x2B) 0x20 else it }))
            var start = 0
            while (start <= bytes.size) {
                var end = start
                while (end < bytes.size && bytes[end] != 0x26) end++
                if (end > start) {
                    val part = bytes.subList(start, end)
                    val eq = part.indexOf(0x3D)
                    val name = if (eq >= 0) part.subList(0, eq) else part
                    val value = if (eq >= 0) part.subList(eq + 1, part.size) else emptyList()
                    out.add(decode(name) to decode(value))
                }
                start = end + 1
            }
            return out
        }

        /**
         * The application/x-www-form-urlencoded byte serializer: space becomes "+"; letters, digits and * - . _ stay;
         * every other UTF-8 byte is percent-encoded in upper case. A lone half of a surrogate pair is written as U+FFFD,
         * as JavaScript's URLSearchParams does.
         */
        private fun formEncoded(s: String): String {
            val sb = StringBuilder()
            for (b in utf8(codePoints(s).toList())) {
                when (b) {
                    0x20 -> sb.append('+')
                    in 0x30..0x39, in 0x41..0x5A, in 0x61..0x7A, 0x2A, 0x2D, 0x2E, 0x5F -> sb.append(b.toChar())
                    else -> percent(sb, b)
                }
            }
            return sb.toString()
        }

        private fun asciiLower(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) sb.append(if (c in 'A'..'Z') c + 32 else c)
            return sb.toString()
        }

        private fun parse(input: String): WebUrl {
            // Remove leading and trailing C0 controls and spaces, then every tab and line break.
            val all = codePoints(input)
            var from = 0
            var to = all.size
            while (from < to && all[from] <= 0x20) from++
            while (to > from && all[to - 1] <= 0x20) to--
            val a = all.slice(from until to).filter { it != 0x09 && it != 0x0A && it != 0x0D }

            // The scheme: the text before the first ":". Only http and https are read (case does not matter); any
            // other scheme, or text that is not a valid scheme, is not a web address. This check comes first, so a
            // mailto: link or plain text is never taken for an unsupported web address.
            val colon = a.indexOf(0x3A)
            if (colon < 0) throw ReadError(false)
            val schemePart = a.subList(0, colon)
            if (schemePart.any { it >= 0x80 }) throw ReadError(false)
            val scheme = asciiLower(string(schemePart))
            if (scheme != "http" && scheme != "https") throw ReadError(false)
            var i = colon + 1

            // http and https skip any run of slashes or backslashes before the host ("https:example.com" works too).
            while (i < a.size && isSlash(a[i])) i++

            // The authority: up to the first / \ ? or #.
            var j = i
            while (j < a.size && !isSlash(a[j]) && a[j] != QUESTION_MARK && a[j] != HASH_MARK) j++
            val authority = a.subList(i, j)
            // From here on, a shape WebUrl does not cover sets `unsupported`; the checks after it still run, so an
            // address the browser rejects (a bad port, an empty host) is still not a web address.
            var unsupported = false
            // "@": a user name or password, not supported. The host is what follows the last "@".
            var hostAndPort = authority
            val at = authority.lastIndexOf(0x40)
            if (at >= 0) {
                unsupported = true
                hostAndPort = authority.subList(at + 1, authority.size)
            }
            // "[": an IPv6 address, not supported.
            if (hostAndPort.firstOrNull() == 0x5B) throw ReadError(true)
            val portColon = hostAndPort.indexOf(0x3A)
            val hostPart = if (portColon >= 0) hostAndPort.subList(0, portColon) else hostAndPort

            // The port: digits only; empty means none; above 65535 fails; the scheme's default is dropped.
            var port: String? = null
            if (portColon >= 0) {
                val digits = hostAndPort.subList(portColon + 1, hostAndPort.size)
                if (!digits.all { it in 0x30..0x39 }) throw ReadError(false)
                if (digits.isNotEmpty()) {
                    var n = 0
                    for (d in digits) {
                        n = n * 10 + (d - 0x30)
                        if (n > 65535) throw ReadError(false)
                    }
                    if (n != (if (scheme == "http") 80 else 443)) port = n.toString()
                }
            }

            // The host: percent-decoded, then lower-cased. Bytes outside ASCII would need IDNA (not supported); a
            // forbidden code point or an empty host fails, as in the browser.
            val hostBytes = percentDecoded(utf8(hostPart))
            if (hostBytes.isEmpty()) throw ReadError(false)
            for (b in hostBytes) {
                if (b >= 0x80) {
                    unsupported = true
                } else if (b < 0x20 || b in forbiddenHost) {
                    throw ReadError(false)
                }
            }
            // Every byte is ASCII by the time the host is used: a byte outside ASCII throws just below.
            val host = asciiLower(String(CharArray(hostBytes.size) { hostBytes[it].toChar() }))
            // A host whose last label is a number ("1.2.3.4", "0x7f") is an IP address: not supported.
            val labels = host.split('.').toMutableList()
            if (labels.size > 1 && labels.last() == "") labels.removeAt(labels.size - 1)
            val last = labels.last()
            if (last.isNotEmpty() && last.all { it in '0'..'9' }) unsupported = true
            if (last.startsWith("0x") && last.substring(2).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) unsupported = true
            if (unsupported) throw ReadError(true)

            // The path: up to "?" or "#". "/" and "\" both separate segments; "." and ".." segments (also written
            // %2e) are resolved the way the browser does.
            var k = j
            while (k < a.size && a[k] != QUESTION_MARK && a[k] != HASH_MARK) k++
            var rawPath = a.subList(j, k)
            if (rawPath.isNotEmpty() && isSlash(rawPath[0])) rawPath = rawPath.subList(1, rawPath.size)
            val buffers = ArrayList<ArrayList<Int>>()
            buffers.add(ArrayList())
            for (c in rawPath) {
                if (isSlash(c)) buffers.add(ArrayList()) else buffers.last().add(c)
            }
            val path = ArrayList<String>()
            for ((n, buffer) in buffers.withIndex()) {
                val endsInSlash = n < buffers.size - 1
                val segment = percentEncoded(buffer, pathSet)
                val lower = asciiLower(segment)
                if (lower == ".." || lower == ".%2e" || lower == "%2e." || lower == "%2e%2e") {
                    if (path.isNotEmpty()) path.removeAt(path.size - 1)
                    if (!endsInSlash) path.add("")
                } else if (lower == "." || lower == "%2e") {
                    if (!endsInSlash) path.add("")
                } else {
                    path.add(segment)
                }
            }

            // The query (kept as written; setQueryParameter re-reads it) and the fragment (after the first "#").
            var m = k
            var query: String? = null
            if (m < a.size && a[m] == QUESTION_MARK) {
                var q = m + 1
                while (q < a.size && a[q] != HASH_MARK) q++
                query = string(a.subList(m + 1, q))
                m = q
            }
            val fragment = if (m < a.size && a[m] == HASH_MARK) percentEncoded(a.subList(m + 1, a.size), fragmentSet) else null
            return WebUrl(scheme, host, port, path, query, fragment)
        }
    }
}
