// The JavaScript string operations the website's scripts use (location.js and the plant list), so they give the
// same answer as JavaScript does on the same text. Kotlin strings are UTF-16, like JavaScript's, so these work on
// UTF-16 units (Char) exactly as a JavaScript regular expression without the u flag does. Kotlin's own trim(),
// isWhitespace() and the \s and \b of its Regex read spaces and words differently, so they are not used.
package garden.hereabouts.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.Normalizer

internal object JsText {
    /** JavaScript's \s and the characters String.trim() removes: WhiteSpace and LineTerminator in the ECMAScript spec. */
    private val spaceValues: Set<Int> = setOf(
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006,
        0x2007, 0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF,
    )

    /** The characters JavaScript's `.` does not match. */
    private val lineTerminatorValues: Set<Int> = setOf(0x0A, 0x0D, 0x2028, 0x2029)

    /** Whether JavaScript's \s matches this character. */
    fun isSpace(c: Char): Boolean = c.code in spaceValues

    /** Whether this is a character JavaScript's `.` does not match. */
    fun isLineTerminator(c: Char): Boolean = c.code in lineTerminatorValues

    /** JavaScript's \d (without the u flag): ASCII digits only. */
    fun isDigit(c: Char): Boolean = c in '0'..'9'

    /** JavaScript's \w, which \b is built on: ASCII letters, digits and underscore. */
    fun isWordChar(c: Char): Boolean = c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z' || c == '_'

    /** String.prototype.trim(). */
    fun trimmed(s: CharSequence): String {
        var start = 0
        var end = s.length
        while (start < end && isSpace(s[start])) start++
        while (end > start && isSpace(s[end - 1])) end--
        return s.subSequence(start, end).toString()
    }

    /** .replace(/\s+/g, " "). */
    fun collapsedSpaces(s: CharSequence): String {
        val out = StringBuilder(s.length)
        var inSpace = false
        for (c in s) {
            if (isSpace(c)) {
                if (!inSpace) out.append(' ')
                inSpace = true
            } else {
                out.append(c)
                inSpace = false
            }
        }
        return out.toString()
    }

    /** .normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase(): no accents, lower case. */
    fun unaccentedLower(s: String): String {
        val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
        val out = StringBuilder(nfd.length)
        for (c in nfd) if (c.code !in 0x300..0x36F) out.append(c)
        // lowercase() is toLowerCase(Locale.ROOT): the Unicode default mapping, as JavaScript's toLowerCase.
        return out.toString().lowercase()
    }

    /** The sign of a JavaScript number difference, a - b as a sort callback. NaN counts as equal, as in JavaScript. */
    fun sign(d: Double): Int = if (d < 0) -1 else if (d > 0) 1 else 0

    /** Math.round(x): the nearest whole number, with halves going up (toward +infinity), so -2.5 gives -2. */
    fun mathRound(x: Double): Double {
        if (x.isNaN() || x.isInfinite()) return x
        val down = Math.floor(x)
        val r = if (x - down >= 0.5) down + 1 else down
        // Math.round(-0.4) is -0.
        return if (r == 0.0 && (x < 0 || (x == 0.0 && 1.0 / x < 0))) -0.0 else r
    }

    /**
     * Number::toString(x), what JavaScript writes for `"" + x`: the shortest digits that read back as the same
     * number, laid out as JavaScript does (38, not 38.0; 1e+21; 5e-324).
     */
    fun numberString(x: Double): String {
        if (x.isNaN()) return "NaN"
        if (x == Double.POSITIVE_INFINITY) return "Infinity"
        if (x == Double.NEGATIVE_INFINITY) return "-Infinity"
        if (x == 0.0) return "0"
        val (digits, n) = shortestDigits(Math.abs(x))
        val k = digits.length
        val body = when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val e = n - 1
                val exp = (if (e >= 0) "+" else "-") + Math.abs(e)
                if (k == 1) digits + "e" + exp else digits.substring(0, 1) + "." + digits.substring(1) + "e" + exp
            }
        }
        return if (x < 0) "-$body" else body
    }

    /**
     * The shortest decimal digits that read back as x (x finite and above 0), and n, where the value is
     * 0.digits times 10 to the n. When two candidates of that length both read back, the closer one wins, then the
     * one ending in an even digit.
     */
    private fun shortestDigits(x: Double): Pair<String, Int> {
        val exact = BigDecimal(x)
        for (p in 1..17) {
            val down = exact.round(MathContext(p, RoundingMode.FLOOR))
            val up = exact.round(MathContext(p, RoundingMode.CEILING))
            val fits = listOf(down, up).distinct().filter { it.toString().toDouble() == x }
            if (fits.isEmpty()) continue
            val best = if (fits.size == 1) {
                fits[0]
            } else {
                val c = exact.subtract(down).abs().compareTo(up.subtract(exact).abs())
                when {
                    c < 0 -> down
                    c > 0 -> up
                    down.unscaledValue().testBit(0) -> up
                    else -> down
                }
            }
            return layout(best)
        }
        // Seventeen significant digits always read back as the same double, so this is not reached.
        return layout(exact.round(MathContext(17, RoundingMode.HALF_EVEN)))
    }

    private fun layout(d: BigDecimal): Pair<String, Int> {
        val s = d.stripTrailingZeros()
        val digits = s.unscaledValue().toString()
        return digits to (digits.length - s.scale())
    }

    /**
     * Number.prototype.toFixed(digits): the number's exact binary value rounded to `digits` places, halves going up
     * (so 1.005, which is stored as 1.00499999..., gives "1.00"). From 1e21 up it writes the number as numberString.
     */
    fun toFixed(x: Double, digits: Int): String {
        if (x.isNaN() || x.isInfinite() || Math.abs(x) >= 1e21) return numberString(x)
        val body = BigDecimal(Math.abs(x)).setScale(digits, RoundingMode.HALF_UP).toPlainString()
        // (-0).toFixed(1) is "0.0": the minus sign is only written below zero.
        return if (x < 0) "-$body" else body
    }

    /** s.charAt(0).toUpperCase() + s.slice(1). charAt(0) is one UTF-16 unit, as Kotlin's substring(0, 1) is. */
    fun upperFirst(s: String): String = if (s.isEmpty()) "" else s.substring(0, 1).uppercase() + s.substring(1)

    private const val HEX = "0123456789ABCDEF"

    /** The UTF-8 bytes of one code point (not a surrogate), added to `out` as Ints 0-255. */
    fun appendUtf8(out: MutableList<Int>, c: Int) {
        when {
            c < 0x80 -> out.add(c)
            c < 0x800 -> {
                out.add(0xC0 or (c shr 6))
                out.add(0x80 or (c and 0x3F))
            }
            c < 0x10000 -> {
                out.add(0xE0 or (c shr 12))
                out.add(0x80 or ((c shr 6) and 0x3F))
                out.add(0x80 or (c and 0x3F))
            }
            else -> {
                out.add(0xF0 or (c shr 18))
                out.add(0x80 or ((c shr 12) and 0x3F))
                out.add(0x80 or ((c shr 6) and 0x3F))
                out.add(0x80 or (c and 0x3F))
            }
        }
    }

    /**
     * UTF-8 bytes read as text the way the WHATWG decoder does: each broken sequence becomes one U+FFFD (one per
     * maximal subpart, as in the Encoding Standard).
     */
    fun decodeUtf8(bytes: List<Int>): String {
        val sb = StringBuilder(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            if (b < 0x80) {
                sb.append(b.toChar())
                i++
                continue
            }
            val need: Int
            var cp: Int
            var lower = 0x80
            var upper = 0xBF
            when (b) {
                in 0xC2..0xDF -> {
                    need = 1
                    cp = b and 0x1F
                }
                in 0xE0..0xEF -> {
                    need = 2
                    cp = b and 0x0F
                    if (b == 0xE0) lower = 0xA0
                    if (b == 0xED) upper = 0x9F
                }
                in 0xF0..0xF4 -> {
                    need = 3
                    cp = b and 0x07
                    if (b == 0xF0) lower = 0x90
                    if (b == 0xF4) upper = 0x8F
                }
                else -> {
                    sb.append('�')
                    i++
                    continue
                }
            }
            var j = i + 1
            var seen = 0
            var ok = true
            while (seen < need) {
                if (j >= bytes.size || bytes[j] !in lower..upper) {
                    ok = false
                    break
                }
                cp = (cp shl 6) or (bytes[j] and 0x3F)
                lower = 0x80
                upper = 0xBF
                j++
                seen++
            }
            if (ok) sb.appendCodePoint(cp) else sb.append('�')
            // A broken sequence ends before the byte that broke it, which is read again on its own.
            i = j
        }
        return sb.toString()
    }

    /**
     * encodeURIComponent(s): letters, digits and - _ . ! ~ * ' ( ) stay; every other character's UTF-8 bytes are
     * written as %XX in upper case. null where JavaScript throws: a lone half of a surrogate pair.
     */
    fun encodeURIComponent(s: String): String? {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val cp: Int
            if (c.isHighSurrogate()) {
                if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return null
                cp = Character.toCodePoint(c, s[i + 1])
                i += 2
            } else if (c.isLowSurrogate()) {
                return null
            } else {
                cp = c.code
                i++
            }
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_.!~*'()") {
                sb.append(c)
            } else {
                val bytes = ArrayList<Int>(4)
                appendUtf8(bytes, cp)
                for (b in bytes) sb.append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
            }
        }
        return sb.toString()
    }
}
