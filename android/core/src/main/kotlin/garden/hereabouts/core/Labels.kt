// The words on a plant card and a plant page, ported line by line from the website's index.html: bloomRange,
// ftRange, rangeLabel, sunLabel, moistureLabel, growthLabel and categoryLabel, PHOTO_ORDER and mainPhoto,
// growingFacts and WILDLIFE_LABEL, SEASON_ROWS and seasonRows, and plantTitle. The same as the iPhone app's
// Labels.swift. Each JavaScript corner case the port keeps is said where it happens. The tests check every plant
// against expected/labels.json, which the website's own functions wrote.
//
// The website's regular expressions have no u flag, so they look at UTF-16 units; so does this file (Kotlin's Char).
package garden.hereabouts.core

/** One row of the season chart: what the plant does (`label`), the look it shows (`state`) and the months it does it. */
data class SeasonRow(
    val label: String,
    val state: String,
    /** [first month, last month] for each stretch of months, both included; 0 is January. */
    val runs: List<List<Int>>,
)

/**
 * growingFacts' answer, each row [label, value]. quick: Height, Width, Sun and Soil (the ruled box). more: Growth and
 * Feeds. flowers: Flowers and In fall (the plant page only).
 */
data class GrowingFacts(val quick: List<List<String>>, val more: List<List<String>>, val flowers: List<List<String>>)

/** A plant's photo for one phase ("bloom", "fruit" and so on). */
data class PhasePhoto(val season: String, val photo: Photo)

object Labels {
    /** MONTHS in index.html. */
    private val monthAbbreviations = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** PHOTO_ORDER in index.html: the phases tried, in order, for the photo a card shows. */
    val photoOrder: List<String> = listOf("bloom", "fruit", "green", "fall", "winter", "dormant")

    /** SITE_NAME in index.html. */
    const val SITE_NAME = "Hereabouts"

    /** WILDLIFE_LABEL in index.html: what a plant feeds, in a visitor's words. "host" means caterpillars eat its leaves. */
    private val wildlifeLabels = mapOf(
        "bees" to "bees", "butterflies" to "butterflies", "hummingbirds" to "hummingbirds", "birds" to "birds",
        "host" to "caterpillars",
    )

    private val categoryLabels = mapOf(
        "tree" to "Tree", "shrub" to "Shrub", "perennial" to "Perennial", "grass" to "Grass / Sedge", "fern" to "Fern",
        "vine" to "Vine", "groundcover" to "Groundcover",
    )

    /** MONTHS[m - 1] inside a string: a month outside 1-12 reads "undefined", as `+` writes it on the website. */
    private fun month(m: Int): String = if (m in 1..12) monthAbbreviations[m - 1] else "undefined"

    /** MONTHS[m - 1] as a list item: a month outside 1-12 is undefined, which `join` writes as an empty string. */
    private fun monthItem(m: Int): String = if (m in 1..12) monthAbbreviations[m - 1] else ""

    /**
     * bloomRange: the bloom months as "May", "Jun–Aug" or "Mar–May, Jul". null when there are none. Months are
     * sorted as numbers, and a repeated month starts a new stretch, as on the website: [5, 5, 6] gives "May, May–Jun".
     */
    fun bloomRange(months: List<Int>): String? {
        if (months.isEmpty()) return null
        val sorted = months.sorted()
        val runs = mutableListOf(mutableListOf(sorted[0]))
        for (i in 1 until sorted.size) {
            if (sorted[i] == sorted[i - 1] + 1) runs.last().add(sorted[i]) else runs.add(mutableListOf(sorted[i]))
        }
        // A one-month stretch is the bare MONTHS entry, so join drops an unknown one: [13] gives "" and [0, 5] gives
        // ", May". A longer stretch is built with +, which writes "undefined": [13, 14] gives "undefined–undefined".
        return runs.joinToString(", ") { r -> if (r.size == 1) monthItem(r[0]) else month(r[0]) + "–" + month(r.last()) }
    }

    /** Number.isInteger. */
    private fun isInteger(n: Double): Boolean = n.isFinite() && Math.floor(n) == n

    /**
     * ftRange: "3 ft" when both ends are the same, else "1–1.5 ft". Whole numbers stay whole; others get one decimal
     * through JavaScript's toFixed(1), so 0.25 gives "0.3" (see JsText.toFixed). The website reads two numbers and
     * fails on fewer; this gives an empty string then.
     */
    fun ftRange(range: List<Double>): String {
        if (range.size < 2) return ""
        val a = range[0]
        val b = range[1]
        // a === b: the same number, written as String(a) would write it.
        if (a == b) return JsText.numberString(a) + " ft"
        fun fmt(n: Double) = if (isInteger(n)) JsText.numberString(n) else JsText.toFixed(n, 1)
        return fmt(a) + "–" + fmt(b) + " ft"
    }

    /**
     * rangeLabel: the values in `order`'s order. One value is its name; neighbours read "first to last"; a gap reads
     * "or". The first letter is upper case. An empty string when no value is in `order`.
     */
    private fun rangeLabel(values: List<String>, order: List<String>, names: Map<String, String>): String {
        val picked = order.filter { it in values }
        if (picked.isEmpty()) return ""
        val idx = picked.map { order.indexOf(it) }
        val run = idx.indices.all { i -> i == 0 || idx[i] == idx[i - 1] + 1 }
        fun name(k: String) = names[k] ?: ""
        val text = when {
            picked.size == 1 -> name(picked[0])
            run -> "${name(picked[0])} to ${name(picked.last())}"
            else -> picked.joinToString(" or ") { name(it) }
        }
        return JsText.upperFirst(text)
    }

    /** sunLabel: "Full sun to part shade" and the like. */
    fun sun(values: List<String>): String =
        rangeLabel(values, listOf("full", "part", "shade"), mapOf("full" to "full sun", "part" to "part shade", "shade" to "full shade"))

    /** moistureLabel: "Dry to medium" and the like. */
    fun moisture(values: List<String>): String =
        rangeLabel(values, listOf("dry", "medium", "wet"), mapOf("dry" to "dry", "medium" to "medium", "wet" to "wet"))

    // growthLabel

    /** /^(\d+)\s*[-–]\s*(\d+)$/ on the trimmed text: the two numbers, as written (leading zeros kept). */
    private fun yearsRange(s: String): Pair<String, String>? {
        var i = 0
        fun span(test: (Char) -> Boolean): String {
            val start = i
            while (i < s.length && test(s[i])) i++
            return s.substring(start, i)
        }
        val first = span(JsText::isDigit)
        if (first.isEmpty()) return null
        span(JsText::isSpace)
        if (i >= s.length || (s[i] != '-' && s[i] != '–')) return null
        i++
        span(JsText::isSpace)
        val second = span(JsText::isDigit)
        if (second.isEmpty() || i != s.length) return null
        return first to second
    }

    /**
     * Replaces each `word` that starts at a word boundary and passes `after` (given the character after the word, or
     * null at the end), left to right, without overlaps, as a global JavaScript regular expression replace does.
     * Every word here starts with a word character, so \b before it means the character before is not one.
     */
    private fun replacing(s: String, word: String, with: String, after: (Char?) -> Boolean): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val fits = s.startsWith(word, i)
            val boundary = i == 0 || !JsText.isWordChar(s[i - 1])
            if (fits && boundary && after(s.getOrNull(i + word.length))) {
                out.append(with)
                i += word.length
            } else {
                out.append(s[i])
                i++
            }
        }
        return out.toString()
    }

    /** (?=\s|$|;|,|\)): what may follow "in." for it to be read as inches. */
    private fun endsInchNote(c: Char?): Boolean = c == null || JsText.isSpace(c) || c == ';' || c == ',' || c == ')'

    /**
     * growthLabel: "2-3" in the data means years to full size and reads "2–3 years to full size"; anything else is a
     * note written in shorthand, spelled out: "1-2" gets an en dash, "~" (and the spaces after it) reads "about ",
     * "ft/yr" reads "ft a year", "yrs" "years", "yr" "year", and "in." before a space, the end, ";", "," or ")" reads
     * "in". The first letter is upper case. null when there are no years. (The website, given a maturity with no
     * years, writes "Undefined"; no plant has one, and the app leaves the line out instead.)
     */
    fun growth(years: String?): String? {
        if (years == null) return null
        yearsRange(JsText.trimmed(years))?.let { return "${it.first}–${it.second} years to full size" }
        // .replace(/(\d)-(\d)/g, '$1–$2'): matches do not overlap, so "1-2-3" gives "1–2-3".
        val s = StringBuilder(years.length)
        var i = 0
        while (i < years.length) {
            if (i + 2 < years.length && JsText.isDigit(years[i]) && years[i + 1] == '-' && JsText.isDigit(years[i + 2])) {
                s.append(years[i]).append('–').append(years[i + 2])
                i += 3
            } else {
                s.append(years[i])
                i++
            }
        }
        // .replace(/~\s*/g, 'about ')
        val t = StringBuilder(s.length)
        i = 0
        while (i < s.length) {
            if (s[i] == '~') {
                t.append("about ")
                i++
                while (i < s.length && JsText.isSpace(s[i])) i++
            } else {
                t.append(s[i])
                i++
            }
        }
        val wordEnd = { c: Char? -> c == null || !JsText.isWordChar(c) }
        var out = t.toString()
        out = replacing(out, "ft/yr", "ft a year", wordEnd)
        out = replacing(out, "yrs", "years", wordEnd)
        out = replacing(out, "yr", "year", wordEnd)
        // /\bin\.(?=\s|$|;|,|\))/g
        out = replacing(out, "in.", "in", ::endsInchNote)
        return JsText.upperFirst(out)
    }

    /**
     * categoryLabel: "Tree", "Grass / Sedge" and so on. The website gives undefined for a type it does not know; this
     * gives an empty string (no plant has such a type).
     */
    fun category(c: String): String = categoryLabels[c] ?: ""

    // growingFacts

    /**
     * Whether a growingFacts row is set in the mono font: the website marks the Height and Width rows of `quick` with
     * a third element, true. The rows here hold only the label and the value, and this says which rows have the mark.
     */
    fun isMono(label: String): Boolean = label == "Height" || label == "Width"

    /** A number's truthiness in JavaScript: false for 0, NaN, and a missing value. */
    private fun truthy(list: List<Double>, i: Int): Boolean = i < list.size && list[i] != 0.0 && !list[i].isNaN()

    /** growingFacts: the facts on a card and a plant page. See GrowingFacts for the three lists. */
    fun growingFacts(p: Plant): GrowingFacts {
        val bloom = bloomRange(p.bloomMonths)
        // p.bloomColor.length ? join : null, then tested for truthiness: an empty joined string counts as none.
        val bloomColors = p.bloomColor.joinToString(", ")
        val quick = ArrayList<List<String>>()
        // p.height[1] ? ...: the larger height must be a number other than 0.
        if (truthy(p.height, 1)) quick.add(listOf("Height", ftRange(p.height)))
        if (truthy(p.spread, 1)) quick.add(listOf("Width", ftRange(p.spread)))
        if (p.sun.isNotEmpty()) quick.add(listOf("Sun", sun(p.sun)))
        if (p.moisture.isNotEmpty()) quick.add(listOf("Soil", moisture(p.moisture)))
        // A wildlife value with no label is undefined in the map, and join writes undefined as an empty string.
        val feeds = p.wildlife.joinToString(", ") { wildlifeLabels[it] ?: "" }
        val more = ArrayList<List<String>>()
        growth(p.maturity?.years)?.let { more.add(listOf("Growth", it)) }
        if (feeds.isNotEmpty()) more.add(listOf("Feeds", JsText.upperFirst(feeds)))
        val flowers = ArrayList<List<String>>()
        if (!bloom.isNullOrEmpty()) flowers.add(listOf("Flowers", bloom + if (bloomColors.isEmpty()) "" else ", $bloomColors"))
        val fall = p.fallColor
        if (!fall.isNullOrEmpty() && fall != "none") flowers.add(listOf("In fall", JsText.upperFirst(fall)))
        return GrowingFacts(quick, more, flowers)
    }

    // seasonRows

    /**
     * SEASON_ROWS: label and state of each row, in order. "In leaf" covers every month the plant is green, in bloom,
     * in fruit or in fall color; each other row covers the months of its own state.
     */
    private val seasonRowNames = listOf("In leaf" to "green", "Bloom" to "bloom", "Fruit" to "fruit", "Fall color" to "fall")

    private fun inRow(state: String, s: String): Boolean =
        if (state == "green") s == "green" || s == "bloom" || s == "fruit" || s == "fall" else s == state

    /**
     * seasonRows: one row per thing the plant does, with the stretches of months it does it. A row with no months is
     * left out. As on the website, a stretch still open at month index 11 closes there, and one still open when a
     * list shorter than 12 ends is dropped.
     */
    fun seasonRows(p: Plant): List<SeasonRow> = seasonRowNames.mapNotNull { (label, state) ->
        val runs = ArrayList<List<Int>>()
        var start: Int? = null
        for ((i, s) in p.months.withIndex()) {
            val on = inRow(state, s)
            if (on && start == null) start = i
            val st = start
            if (st != null && (!on || i == 11)) {
                runs.add(listOf(st, if (on) i else i - 1))
                start = null
            }
        }
        if (runs.isEmpty()) null else SeasonRow(label, state, runs)
    }

    // photos and title

    /** Every photo the plant has, in PHOTO_ORDER. A phase not in PHOTO_ORDER is not shown. */
    fun photos(p: Plant): List<PhasePhoto> = photoOrder.mapNotNull { phase -> p.phasePhotos[phase]?.let { PhasePhoto(phase, it) } }

    /** mainPhoto: the first phase in PHOTO_ORDER that has a photo, or null. */
    fun mainPhoto(p: Plant): PhasePhoto? = photos(p).firstOrNull()

    /** plantTitle: a plant page's title. */
    fun plantTitle(p: Plant): String = "${p.common} (${p.sci}): native range and where to buy · $SITE_NAME"
}
