// The Saturday-morning reminders: which Saturdays, which jobs, and which saved plants each job names. Ported line by
// line from the website's reminder logic, the version its app build uses. The same as the iPhone app's
// Reminders.swift. The app turns each Reminder into a local notification. The tests check it against
// expected/care.json.
//
// java.time's LocalDate and ZonedDateTime are always Gregorian and never read the phone's language or region, so a
// phone set to the Japanese or Buddhist calendar, or to Arabic or Thai digits, gets the same dates and text.
package garden.hereabouts.core

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime

/** One Saturday's reminder. `id` is the date as yyyymmdd, so the same Saturday always gets the same id. */
data class Reminder(val id: Int, val at: ZonedDateTime, val title: String, val body: String, val jobs: List<Job>)

object Reminders {
    /** REMINDER_TITLE: every reminder's title. */
    const val TITLE = "This week in your garden"

    /** 08:00, the time each reminder is sent. */
    private val eight: LocalTime = LocalTime.of(8, 0)

    /**
     * saturdaysAhead: the next `weeks` Saturdays at 08:00 in the time zone of `now`, each later than `now`. On a
     * Saturday before 08:00 that same morning is the first. Looks at most 7 × weeks + 14 days ahead, as the website
     * does.
     */
    fun saturdaysAhead(now: ZonedDateTime, weeks: Int = 8): List<ZonedDateTime> {
        val out = ArrayList<ZonedDateTime>()
        val today = now.toLocalDate()
        var i = 0
        while (out.size < weeks && i < 7 * weeks + 14) {
            val date = today.plusDays(i.toLong())
            i++
            // new Date(y, m, d, 8, 0, 0): 08:00 local time on that date. If 08:00 is skipped by a clock change, both
            // move it forward by the length of the gap.
            val at = ZonedDateTime.of(date, eight, now.zone)
            if (date.dayOfWeek == DayOfWeek.SATURDAY && at.isAfter(now)) out.add(at)
        }
        return out
    }

    /** reminderId: the date as yyyymmdd, from its local year, month and day. */
    fun id(at: ZonedDateTime): Int = at.year * 10000 + at.monthValue * 100 + at.dayOfMonth

    /** The date as "YYYY-MM-DD" from its local fields (localDate in reminders.mjs). */
    private fun localDate(at: ZonedDateTime): String =
        at.year.toString() + "-" + at.monthValue.toString().padStart(2, '0') + "-" + at.dayOfMonth.toString().padStart(2, '0')

    /** joinNames: "a", "a and b", "a, b, and c". */
    private fun joinNames(names: List<String>): String {
        if (names.size < 3) return names.joinToString(" and ")
        return names.dropLast(1).joinToString(", ") + ", and " + names.last()
    }

    /**
     * reminderBody: one line per job, "<heading>: your <plants>.", with the plants' common names in lower case. A
     * pair shows once, under its first member. A job whose plants are all unknown (or have no name) has no line.
     */
    fun body(jobs: List<Job>, plants: List<Plant>): String {
        // new Map(plants.map(...)): a repeated id keeps the last plant.
        val byId = HashMap<String, Plant>()
        for (p in plants) byId[p.id] = p
        val seenPairs = HashSet<String>()
        val lines = ArrayList<String>()
        for (job in jobs) {
            // if (job.pair): an empty pair name counts as none.
            val pair = job.pair
            if (!pair.isNullOrEmpty()) {
                if (pair in seenPairs) continue
                seenPairs.add(pair)
            }
            // .filter(Boolean) drops a missing plant and an empty name. lowercase() is the Unicode default mapping,
            // as JavaScript's toLowerCase, whatever the phone's language.
            val names = job.examplePlantIds.mapNotNull { byId[it]?.common }.filter { it.isNotEmpty() }.map { it.lowercase() }
            if (names.isEmpty()) continue
            lines.add("${job.heading}: your ${joinNames(names)}.")
        }
        return lines.joinToString("\n")
    }

    /**
     * weeklyReminders: a reminder for each of the next eight Saturdays that has at least one job, naming every saved
     * plant each job applies to. None when the county has no timing dates or no saved plant is known.
     */
    fun weekly(rules: List<CareRule>, anchors: Anchors?, savedIds: List<String>, plants: List<Plant>, now: ZonedDateTime): List<Reminder> {
        if (anchors == null) return emptyList()
        val known = plants.map { it.id }.toSet()
        val nativePlantIds = savedIds.filter { it in known }
        if (nativePlantIds.isEmpty()) return emptyList()
        val out = ArrayList<Reminder>()
        for (at in saturdaysAhead(now)) {
            // maxExamples: Infinity on the website: every saved plant the job applies to.
            val jobs = Care.dueJobs(rules, anchors, nativePlantIds, localDate(at), plants, Int.MAX_VALUE)
            if (jobs.isEmpty()) continue
            out.add(Reminder(id(at), at, TITLE, body(jobs, plants), jobs))
        }
        return out
    }
}
