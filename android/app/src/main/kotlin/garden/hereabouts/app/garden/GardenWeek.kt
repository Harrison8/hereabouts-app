// This week on the Garden tab: the jobs due for the saved plants in the week that runs from Saturday to Friday, and
// the words built from them. The jobs are the website's: the core's Care.dueJobs for the county's timing dates and the
// saved plants, with the week's Saturday as the send date, every saved plant named. Saved plants that are not native
// to the county still count, as they do in the reminders. The same as the iPhone app's GardenWeek.
package garden.hereabouts.app.garden

import garden.hereabouts.app.CareTables
import garden.hereabouts.core.Anchors
import garden.hereabouts.core.Care
import garden.hereabouts.core.CareRule
import garden.hereabouts.core.Job
import garden.hereabouts.core.Plant
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * One row of This week: a job, or jobs that share a pair, shown together under the first. Its id is the first job's
 * rule id, which is also what a tick is kept under.
 */
class WeekRow(val jobs: List<Job>) {
    val id: String get() = jobs[0].ruleId
    val heading: String get() = jobs[0].heading

    /** The saved plants any of its jobs applies to, in the order the jobs name them. */
    val plantIds: List<String> get() = jobs.flatMap { it.examplePlantIds }.distinct()
}

/** This week for a county, the saved plants and a time: the rows, the dates, and the words built from them. */
class GardenWeek(
    private val care: CareTables,
    val plants: List<Plant>,
    countyFips: String?,
    private val countyName: String,
    saved: List<String>,
    now: ZonedDateTime,
) {
    private val anchors: Anchors? = countyFips?.let { care.anchors[it] }

    /** The Saturday the week starts on, at the time of day `now` has. */
    val saturday: ZonedDateTime = now.minusDays(((now.dayOfWeek.value + 1) % 7).toLong())

    private val byId: Map<String, Plant> = plants.associateBy { it.id }

    /** The saved plants the data knows, in the order they were saved. */
    val savedPlants: List<Plant> = saved.mapNotNull { byId[it] }

    val rows: List<WeekRow>

    init {
        val jobs = Care.dueJobs(care.rules, anchors, saved, saturday.toLocalDate().toString(), plants, Int.MAX_VALUE)
        // A job that shares a pair joins the row of the pair's first job.
        val pairRow = HashMap<String, Int>()
        val grouped = ArrayList<MutableList<Job>>()
        for (job in jobs) {
            val pair = job.pair?.takeIf { it.isNotEmpty() }
            val i = pair?.let { pairRow[it] }
            if (i != null) {
                grouped[i].add(job)
                continue
            }
            if (pair != null) pairRow[pair] = grouped.size
            grouped.add(mutableListOf(job))
        }
        rows = grouped.map { WeekRow(it) }
    }

    /** "Nov 13 to 19", or "Oct 30 to Nov 5" when the week crosses into the next month. */
    val datesLabel: String
        get() {
            val friday = saturday.plusDays(6)
            val aMonth = shortMonths[saturday.monthValue - 1]
            val bMonth = shortMonths[friday.monthValue - 1]
            return if (saturday.monthValue == friday.monthValue) {
                "$aMonth ${saturday.dayOfMonth} to ${friday.dayOfMonth}"
            } else {
                "$aMonth ${saturday.dayOfMonth} to $bMonth ${friday.dayOfMonth}"
            }
        }

    /** The week's Saturday as a day of the year, as the care rules count days (February 29 counts as February 28). */
    val sendDay: Int
        get() {
            val month = saturday.monthValue
            val day = saturday.dayOfMonth
            if (month == 2 && day == 29) return 59
            return monthStart[month - 1] + day
        }

    fun rule(id: String): CareRule? = care.rules.firstOrNull { it.id == id }

    fun window(job: Job): Care.Window? = rule(job.ruleId)?.let { Care.ruleWindow(it, anchors) }

    fun plant(id: String): Plant? = byId[id]

    /** How many rows name the plant. */
    fun jobCount(plantId: String): Int = rows.count { plantId in it.plantIds }

    /** "Butterfly Weed and Little Bluestem · until Dec 27". */
    fun subline(row: WeekRow): String {
        val names = joined(row.plantIds.mapNotNull { plant(it)?.common }.filter { it.isNotEmpty() })
        val parts = ArrayList<String>()
        if (names.isNotEmpty()) parts.add(names)
        val w = window(row.jobs[0])
        if (w != null && !(w.start == 1 && w.end == 365)) parts.add("until ${monthDay(w.end, short = true)}")
        return parts.joinToString(" · ")
    }

    /**
     * The line under This week: where the dates come from, and, while a reminder will come (the switch is on and the
     * phone has not refused the app's notifications), when it comes.
     */
    fun footer(remindersWillCome: Boolean): String {
        if (anchors == null) return "Hereabouts has no frost dates for $countyName yet. Without them it shows no jobs."
        return if (remindersWillCome) {
            "From $countyName’s frost dates. A reminder comes every Saturday at 8:00."
        } else {
            "From $countyName’s frost dates."
        }
    }

    /**
     * "Now until about April 14." when the job is due this week, "From about March 10 to about April 7." when not,
     * "Any time of year." when it runs all year.
     */
    fun whenText(job: Job): String {
        val w = window(job) ?: return ""
        if (w.start == 1 && w.end == 365) return "Any time of year."
        if (Care.isDueWeek(w, sendDay)) return "Now until about ${monthDay(w.end)}."
        return "From about ${monthDay(w.start)} to about ${monthDay(w.end)}."
    }

    /**
     * "Fairfax County’s first fall frost is about October 29 and its first hard freeze about November 8. This job runs
     * from the first fall frost to four weeks after the first hard freeze."
     */
    fun whenExplained(job: Job): String {
        val rule = rule(job.ruleId) ?: return ""
        val anchors = anchors ?: return ""
        val names = listOf(rule.from.anchor, rule.to.anchor).distinct()
        val dates = ArrayList<String>()
        for ((i, anchor) in names.withIndex()) {
            val day = anchors[anchor] ?: return ""
            val name = anchorNames[anchor] ?: anchor
            dates.add(if (i == 0) "$countyName’s $name is about ${monthDay(day)}" else "its $name about ${monthDay(day)}")
        }
        val from = phrase(rule.from, sameAsBefore = false)
        val to = phrase(rule.to, sameAsBefore = rule.to.anchor == rule.from.anchor)
        return "${dates.joinToString(" and ")}. This job runs from $from to $to."
    }

    companion object {
        private val shortMonths = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        private val months = listOf(
            "January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
            "November", "December",
        )

        /** The day before each month starts, in a 365-day year, as the care rules count days. */
        private val monthStart = listOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)

        /** A day of the year as "April 14", or "Apr 14" when `short`. */
        fun monthDay(dayOfYear: Int, short: Boolean = false): String {
            val month = monthStart.indexOfLast { it < dayOfYear }.coerceAtLeast(0)
            val name = if (short) shortMonths[month] else months[month]
            return "$name ${dayOfYear - monthStart[month]}"
        }

        /** "a", "a and b", "a, b, and c". */
        fun joined(names: List<String>): String {
            if (names.size < 3) return names.joinToString(" and ")
            return names.dropLast(1).joinToString(", ") + ", and " + names.last()
        }

        private val anchorNames = mapOf(
            "lastSpring" to "last spring frost",
            "firstFall" to "first fall frost",
            "hardSpring" to "last hard freeze",
            "hardFall" to "first hard freeze",
            "highs50" to "first week with highs of 50°F",
        )

        /** "the first fall frost", "four weeks after the first hard freeze", "two weeks before it". */
        private fun phrase(bound: CareRule.Bound, sameAsBefore: Boolean): String {
            val target = if (sameAsBefore) "it" else "the ${anchorNames[bound.anchor] ?: bound.anchor}"
            if (bound.days == 0) return target
            val words = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
            val days = abs(bound.days)
            val amount = if (days % 7 == 0) {
                val weeks = days / 7
                val count = if (weeks <= 9) words[weeks - 1] else "$weeks"
                if (weeks == 1) "one week" else "$count weeks"
            } else {
                if (days == 1) "one day" else "$days days"
            }
            return if (bound.days < 0) "$amount before $target" else "$amount after $target"
        }
    }
}
