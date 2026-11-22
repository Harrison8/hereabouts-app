// Ranges as the app shows them: "6 to 12 ft", "Mar to Apr", "$3.25 to $6.50", "Slow to moderate". The data and the
// website write a range with an en dash or a hyphen; the app reads it with "to", which a screen reader says clearly and
// which reads the same at any text size. Only what is shown changes: the data keeps its dashes.
//
// The rules, the same as the iPhone app's:
// - a hyphen or en dash between numbers, prices or month names becomes " to ";
// - a hyphen or en dash between two growth rates (slow, medium, moderate, fast, rapid; any case) becomes " to ";
// - any other dash between words stays as written ("Jack–in–the–Pulpit", "Mon–Sat", "3-gallon", "fast-growing");
// - a value with no range is unchanged.
package garden.hereabouts.core

object RangeText {
    /** `text` with each range written with "to". */
    fun display(text: String): String {
        if (!text.contains('–') && !text.contains('-')) return text
        var out = text
        for ((rule, template) in rules) out = rule.replace(out, template)
        return out
    }

    /** Month names, the full ones first so "April" is not read as "Apr" followed by "il". */
    private const val MONTHS = "January|February|March|April|May|June|July|August|September|October|November|December" +
        "|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sept|Sep|Oct|Nov|Dec"

    private val rules: List<Pair<Regex, String>> = listOf(
        // Two growth rates joined by either dash: "Slow-moderate", "Moderate–rapid".
        Regex("""(?i)\b(slow|medium|moderate|fast|rapid)[-–](slow|medium|moderate|fast|rapid)\b""") to "\$1 to \$2",
        // An en dash after a number, price or month that starts a word, before a number, price, "~" or month:
        // "6–12 ft", "$16.95–$35.95", "2nd–3rd", "Mar–Apr". A dash between other words is left alone.
        Regex("""(?<![^\s(~])(\$?\d[A-Za-z0-9.]*|(?:$MONTHS))–(?=\$?\d|~|(?:$MONTHS)\b)""") to "\$1 to ",
        // A hyphen between two plain numbers or prices: "1.5-2.5 ft", "$3.25-$6.50". Not a date or a catalogue number
        // (another hyphen and digit follow), not a fraction or a negative number (something stands before it).
        Regex("""(?<![A-Za-z0-9.\-/])(\$?\d+(?:\.\d+)?)-(\$?\d+(?:\.\d+)?)(?![\d.]*-\d)(?!\d)""") to "\$1 to \$2",
        // A hyphen between two month names: "Oct-Nov", "April-May".
        Regex("""\b($MONTHS)-($MONTHS)\b""") to "\$1 to \$2",
    )
}
