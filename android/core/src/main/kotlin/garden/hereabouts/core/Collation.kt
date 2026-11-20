// The website sorts names with JavaScript's localeCompare, which uses ICU's root collation order (the order English
// uses). This file gives the same order in plain Kotlin, with no java.text.Collator and no Locale, so every phone
// sorts the same way. It is the iPhone app's Collation.swift in Kotlin; both read their weights from the same list.
//
// How ICU orders two strings, in three passes; a later pass only counts when every earlier one ties:
//   1. Primary: the letters with case and accents ignored. Spaces and punctuation are not skipped: they count, and
//      they come before digits, which come before letters. So " apple" < "1 apple" < "10 apples" < "2 apples" <
//      "a1" < "apple" (digits compare one at a time, not as numbers).
//   2. Secondary: accents. An unaccented letter comes before an accented one, and accents have a fixed order among
//      themselves, so "cote" < "coté" < "côte" < "côté".
//   3. Tertiary: case. Lower case comes before upper case, so "apple" < "Apple".
//
// The table covers the characters the data uses and the ones around them: ASCII, the no-break space, the dashes and
// curly quotes, a few Latin-1 signs, and any Latin letter that splits into a plain letter plus accents. A character
// outside the table sorts after the letters, by its code point, which is not where the website puts it. So the table
// is also kept in the website's bundle-building logic: building the bundle fails when a plant name uses a character
// missing there, and CollationTest checks this table equals it (collation.json's `table`). A new character goes in
// that list, in Collation.swift and here.
package garden.hereabouts.core

import java.text.Normalizer

/** Compares two strings the way the website's `a.localeCompare(b)` does: negative, 0 or positive. */
fun hereaboutsCompare(a: String, b: String): Int = Collation.compare(a, b)

internal object Collation {
    /**
     * One character's weights for the three passes. A primary of 0 means "no primary weight": an accent mark, which
     * only counts in the secondary pass.
     */
    data class Weights(val primary: Int, val secondary: Int, val tertiary: Int)

    /** The weight every plain character has in the secondary and tertiary passes. */
    const val COMMON = 5

    /**
     * The spaces and punctuation, in ICU's order, lowest first. Characters in one group have the same primary weight
     * and differ only in a later pass. Digits and letters follow these.
     */
    private val punctuation: List<List<Int>> = listOf(
        listOf(0x0009), listOf(0x000A), listOf(0x000B), listOf(0x000C), listOf(0x000D), listOf(0x0020, 0x00A0),
        listOf(0x005F), listOf(0x002D), listOf(0x2010, 0x2011), listOf(0x2012), listOf(0x2013), listOf(0x2014),
        listOf(0x2015), listOf(0x002C), listOf(0x003B), listOf(0x003A), listOf(0x0021), listOf(0x00A1), listOf(0x003F),
        listOf(0x00BF), listOf(0x002E), listOf(0x00B7), listOf(0x0027, 0x2018, 0x2019), listOf(0x0022, 0x201C, 0x201D),
        listOf(0x00AB), listOf(0x00BB), listOf(0x0028), listOf(0x0029), listOf(0x005B), listOf(0x005D), listOf(0x007B),
        listOf(0x007D), listOf(0x00A7), listOf(0x00B6), listOf(0x0040), listOf(0x002A), listOf(0x002F), listOf(0x005C),
        listOf(0x0026), listOf(0x0023), listOf(0x0025), listOf(0x0060), listOf(0x005E), listOf(0x00B0), listOf(0x00A9),
        listOf(0x00AE), listOf(0x002B), listOf(0x00D7), listOf(0x003C), listOf(0x003D), listOf(0x003E), listOf(0x007C),
        listOf(0x007E), listOf(0x0024),
    )

    /** Curly quotes sort with the straight quote in the first pass and after it in the second: ' < ‘ < ’ and " < “ < ”. */
    private val secondaryOf: Map<Int, Int> = mapOf(0x2018 to 6, 0x2019 to 7, 0x201C to 6, 0x201D to 7)

    /** The no-break space and the no-break hyphen sort with the space and the hyphen (U+2010) until the third pass. */
    private val tertiaryOf: Map<Int, Int> = mapOf(0x00A0 to 6, 0x2011 to 6)

    /**
     * The accent marks (combining characters) in ICU's secondary order: acute, grave, breve, circumflex, caron, ring,
     * diaeresis, double acute, tilde, dot above, cedilla, ogonek, macron.
     */
    private val accents: List<Int> =
        listOf(0x0301, 0x0300, 0x0306, 0x0302, 0x030C, 0x030A, 0x0308, 0x030B, 0x0303, 0x0307, 0x0327, 0x0328, 0x0304)

    /** Every character's weights, by code point. */
    val table: Map<Int, Weights> = buildMap<Int, Weights> {
        val groups = ArrayList(punctuation)
        for (d in 0x30..0x39) groups.add(listOf(d))
        for (lower in 0x61..0x7A) groups.add(listOf(lower, lower - 0x20)) // a then A
        groups.forEachIndexed { i, group ->
            for (v in group) {
                val upper = v in 0x41..0x5A
                put(v, Weights(i + 1, secondaryOf[v] ?: COMMON, if (upper) 6 else tertiaryOf[v] ?: COMMON))
            }
        }
        accents.forEachIndexed { i, v -> put(v, Weights(0, 10 + i, COMMON)) }
    }

    /** The weights of each character of `s`, after splitting accented letters into letter plus accent marks (NFD). */
    fun weights(s: String): List<Weights> {
        val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
        val out = ArrayList<Weights>(nfd.length)
        var i = 0
        while (i < nfd.length) {
            val v = nfd.codePointAt(i)
            i += Character.charCount(v)
            out.add(
                table[v]
                    // Another accent mark: after the known ones. Anything else: after the letters, by code point.
                    ?: if (v in 0x300..0x36F) Weights(0, 100 + v, COMMON) else Weights(1000 + v, COMMON, COMMON),
            )
        }
        return out
    }

    /** Compares two lists of weights item by item; a list that runs out first comes first. */
    private fun compareLists(a: List<Int>, b: List<Int>): Int {
        for (k in 0 until minOf(a.size, b.size)) {
            if (a[k] != b[k]) return if (a[k] < b[k]) -1 else 1
        }
        return a.size.compareTo(b.size).coerceIn(-1, 1)
    }

    /** The three passes. Weights of 0 (accent marks in the first pass) are left out of a pass. */
    fun compare(a: String, b: String): Int {
        val wa = weights(a)
        val wb = weights(b)
        val passes: List<(Weights) -> Int> = listOf({ it.primary }, { it.secondary }, { it.tertiary })
        for (pass in passes) {
            val r = compareLists(wa.map(pass).filter { it != 0 }, wb.map(pass).filter { it != 0 })
            if (r != 0) return r
        }
        return 0
    }
}
