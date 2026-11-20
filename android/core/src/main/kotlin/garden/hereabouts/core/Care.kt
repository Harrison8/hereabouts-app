// Which garden jobs are due in a given week, ported line by line from the website's care-timing logic, which the
// newsletter and the website's reminders share. The same as the iPhone app's Care.swift. The rules are the bundle's
// tables/care-rules.json; the timing of each county (its frost dates and first warm week) is
// tables/care-anchors.json.
//
// Each rule covers a stretch of the year that runs from one of a county's timing dates plus some days to another
// plus some days. A stretch whose start is later than its end runs past New Year. Days of the year run 1 to 365.
package garden.hereabouts.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** One garden job from care-rules.json: what to do, for which kinds of plant, and in which stretch of the year. */
@Serializable
data class CareRule(
    val id: String,
    val heading: String,
    val categories: List<String>,
    val from: Bound,
    val to: Bound,
    val facts: List<String>,
    /** Rules with the same pair name are shown together, or not at all. */
    val pair: String? = null,
    val order: Double,
) {
    /** One end of a rule's stretch: one of the county's timing dates (`anchor`) plus `days`. */
    @Serializable
    data class Bound(val anchor: String, val days: Int)
}

/** One county's timing dates from care-anchors.json, each a day of the year (1 to 365), or null when unknown. */
@Serializable
data class Anchors(
    val lastSpring: Int? = null,
    val firstFall: Int? = null,
    val hardSpring: Int? = null,
    val hardFall: Int? = null,
    val highs50: Int? = null,
) {
    /** anchors[name], as care-core.mjs reads it: an unknown name gives null. */
    operator fun get(name: String): Int? = when (name) {
        "lastSpring" -> lastSpring
        "firstFall" -> firstFall
        "hardSpring" -> hardSpring
        "hardFall" -> hardFall
        "highs50" -> highs50
        else -> null
    }
}

/** A job that is due, with up to a few of the visitor's own plants it applies to. */
data class Job(
    val ruleId: String,
    val heading: String,
    val facts: List<String>,
    val examplePlantIds: List<String>,
    val pair: String?,
)

object Care {
    /** MAX_SLOTS: at most this many jobs a week (a pair of rules counts as one). */
    const val MAX_SLOTS = 4

    /** A stretch of the year, both days included. When start is later than end, it runs past New Year. */
    data class Window(val start: Int, val end: Int)

    private val monthStart = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)

    /** dayOfYear: the day of a 365-day year. 29 February counts as 28 February; a month outside 1-12 gives null. */
    private fun dayOfYear(month: Int, day: Int): Int? {
        if (month == 2 && day == 29) return 59
        if (month !in 1..12) return null
        return monthStart[month - 1] + day
    }

    /** wrap: any day number brought into 1 to 365. */
    private fun wrap(d: Int): Int = (((d - 1) % 365) + 365) % 365 + 1

    /**
     * sendDay: the day of the year of a "YYYY-MM-DD" date, or null when the text is not that shape (ASCII digits
     * only, as in the website's regular expression). The day is not checked against the month: "04-40" gives 130.
     */
    internal fun sendDay(sendDate: String): Int? {
        val s = sendDate
        if (s.length != 10 || s[4] != '-' || s[7] != '-') return null
        fun number(from: Int, to: Int): Int? {
            var n = 0
            for (i in from until to) {
                if (s[i] !in '0'..'9') return null
                n = n * 10 + (s[i] - '0')
            }
            return n
        }
        if (number(0, 4) == null) return null
        val month = number(5, 7) ?: return null
        val day = number(8, 10) ?: return null
        return dayOfYear(month, day)
    }

    /**
     * ruleWindow: the stretch of the year a rule covers in one county, or null when the county lacks one of the two
     * timing dates. A stretch of 364 days or more is the whole year.
     */
    fun ruleWindow(rule: CareRule, anchors: Anchors?): Window? {
        val a = anchors?.get(rule.from.anchor) ?: return null
        val b = anchors[rule.to.anchor] ?: return null
        val start = a + rule.from.days
        val end = b + rule.to.days
        if (end - start >= 364) return Window(1, 365)
        return Window(wrap(start), wrap(end))
    }

    private fun inWindow(w: Window, d: Int): Boolean =
        if (w.start <= w.end) d >= w.start && d <= w.end else d >= w.start || d <= w.end

    /** isDueWeek: whether any of the seven days from sendDay falls in the window. */
    fun isDueWeek(window: Window?, sendDay: Int): Boolean {
        if (window == null) return false
        val first = minOf(sendDay, 365)
        return (0 until 7).any { inWindow(window, wrap(first + it)) }
    }

    private class Candidate(val rule: CareRule, val examples: List<String>, val due: Boolean)

    private class Slot(val order: Double, val id: String, val members: List<Candidate>)

    private fun byOrder(xOrder: Double, xId: String, yOrder: Double, yId: String): Int {
        // x.order - y.order || ...: a difference of 0 (or NaN, which no rule has) falls through to the ids.
        val d = xOrder - yOrder
        if (d != 0.0 && !d.isNaN()) return if (d < 0) -1 else 1
        // localeCompare is not used: the ids are compared by UTF-16 unit, as Kotlin's compareTo does.
        return xId.compareTo(yId).let { if (it < 0) -1 else if (it > 0) 1 else 0 }
    }

    /**
     * dueJobs: the jobs due in the week starting `sendDate`, for the visitor's plants. Rules are taken in `order`;
     * paired rules are shown together when either is due; at most MAX_SLOTS jobs (a pair counts as one). Each job
     * names up to `maxExamples` of the visitor's plants it applies to (a negative number drops that many from the end,
     * as JavaScript's slice does).
     */
    fun dueJobs(
        rules: List<CareRule>,
        anchors: Anchors?,
        nativePlantIds: List<String>,
        sendDate: String,
        plants: List<Plant>,
        maxExamples: Int = 3,
    ): List<Job> {
        val day = sendDay(sendDate) ?: return emptyList()
        // new Map(plants.map(...)): a repeated id keeps the last plant's category.
        val category = HashMap<String, String>()
        for (p in plants) category[p.id] = p.category
        // [...ids].filter(...).sort(): the default sort compares UTF-16 units, as Kotlin's sorted() does.
        val natives = nativePlantIds.filter { it in category }.sorted()

        val candidates = ArrayList<Candidate>()
        for (rule in rules) {
            val window = ruleWindow(rule, anchors) ?: continue
            val examples = natives.filter { id -> category[id]?.let { it in rule.categories } == true }
            if (examples.isEmpty()) continue
            candidates.add(Candidate(rule, examples, isDueWeek(window, day)))
        }

        val units = ArrayList<Slot>()
        // A JavaScript Map keeps its keys in the order they were first added; so does LinkedHashMap.
        val pairs = LinkedHashMap<String, MutableList<Candidate>>()
        for (c in candidates) {
            val pair = c.rule.pair
            // !c.rule.pair: an empty pair name counts as none.
            if (pair.isNullOrEmpty()) {
                if (c.due) units.add(Slot(c.rule.order, c.rule.id, listOf(c)))
                continue
            }
            pairs.getOrPut(pair) { ArrayList() }.add(c)
        }
        for (all in pairs.values) {
            if (all.none { it.due }) continue
            // sortedWith is stable, as JavaScript's sort is.
            val members = all.sortedWith { x, y -> byOrder(x.rule.order, x.rule.id, y.rule.order, y.rule.id) }
            units.add(Slot(members[0].rule.order, members[0].rule.id, members))
        }

        val sorted = units.sortedWith { x, y -> byOrder(x.order, x.id, y.order, y.id) }
        return sorted.take(MAX_SLOTS).flatMap { it.members }.map { c ->
            Job(c.rule.id, c.rule.heading, c.rule.facts, slice(c.examples, maxExamples), c.rule.pair)
        }
    }

    /** list.slice(0, end), with JavaScript's reading of a negative end. */
    private fun slice(list: List<String>, end: Int): List<String> {
        val n = if (end >= 0) minOf(end, list.size) else maxOf(0, list.size + end)
        return list.take(n)
    }

    // loading

    @Serializable
    private class RulesFile(val rules: List<CareRule>)

    /** Reads care-rules.json. */
    fun rules(text: String): List<CareRule> = json.decodeFromString(RulesFile.serializer(), text).rules

    /** Reads care-anchors.json: each county's timing, by county code. */
    fun anchorsByCounty(text: String): Map<String, Anchors> =
        json.decodeFromString(MapSerializer(String.serializer(), Anchors.serializer()), text)
}
