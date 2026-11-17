// Checks Reminders against apps/shared/bundle/expected/care.json: for each case (a time zone, a moment, a county and
// some saved plants) the Saturday reminders the website's scripts/app/reminders.mjs makes, with the real care rules
// and the county's real timing dates. Then the Saturday and message rules on their own.
package garden.hereabouts.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class RemindersTest {
    private val tablesDir get() = Expected.bundleDir.resolve("tables")
    private val rules by lazy { Care.rules(tablesDir.resolve("care-rules.json").readText(Charsets.UTF_8)) }
    private val anchors by lazy { Care.anchorsByCounty(tablesDir.resolve("care-anchors.json").readText(Charsets.UTF_8)) }

    /** "YYYY-MM-DDTHH:mm" as local time in the zone. */
    private fun date(text: String, zone: String): ZonedDateTime = LocalDateTime.parse(text).atZone(ZoneId.of(zone))

    /** A moment as "YYYY-MM-DDTHH:mm" in its own zone, written by hand so no locale can change it. */
    private fun text(d: ZonedDateTime): String {
        fun pad(n: Int) = n.toString().padStart(2, '0')
        return "${d.year}-${pad(d.monthValue)}-${pad(d.dayOfMonth)}T${pad(d.hour)}:${pad(d.minute)}"
    }

    private fun resultJson(list: List<Reminder>): JsonArray = JsonArray(list.map { r ->
        JsonObject(mapOf(
            "id" to JsonPrimitive(r.id),
            "at" to JsonPrimitive(text(r.at)),
            "title" to JsonPrimitive(r.title),
            "body" to JsonPrimitive(r.body),
            "ruleIds" to JsonArray(r.jobs.map { JsonPrimitive(it.ruleId) }),
        ))
    })

    @Test
    fun everyExpectedCase() {
        val data = Expected.data
        val cases = Expected.cases("care.json")
        val fields = setOf("tz", "now", "county", "savedIds", "result")
        val bad = ArrayList<String>()
        var withReminders = 0
        for (c in cases) {
            if (c.fields.keys != fields) {
                bad.add("case ${c.index}: fields ${c.fields.keys.sorted()}")
                continue
            }
            val tz = c.string("tz")
            val now = date(c.string("now"), tz)
            val saved = c.strings("savedIds")
            val county = c.string("county")
            val got = Reminders.weekly(rules, anchors[county], saved, data.plants, now)
            got.forEach { assertEquals(now.zone, it.at.zone, "case ${c.index}: a reminder in another zone") }
            if (got.isNotEmpty()) withReminders++
            val gotJson = resultJson(got)
            val want = c.element("result")
            if (!jsonEquals(gotJson, want)) bad.add("case ${c.index} ($tz ${c.string("now")} county $county $saved): got $gotJson, expected $want")
        }
        assertTrue(withReminders > 0)
        println("care.json: ${cases.size} cases checked, $withReminders with reminders")
        if (bad.isNotEmpty()) fail("${bad.size} of ${cases.size} mismatches in care.json; first:\n" + bad.take(5).joinToString("\n"))
    }

    @Test
    fun phoneLocaleDoesNotChangeTheReminders() {
        // A phone set to the Japanese or Thai calendar, or Thai digits, gets the same reminders: the website's dates
        // are always Gregorian with ASCII digits.
        val data = Expected.data
        val c = assertNotNull(
            Expected.cases("care.json").firstOrNull {
                it.string("tz") == "America/New_York" && (it.element("result") as? JsonArray)?.isNotEmpty() == true
            },
        )
        val now = date(c.string("now"), "America/New_York")
        val saved = c.strings("savedIds")
        val county = c.string("county")
        val want = Reminders.weekly(rules, anchors[county], saved, data.plants, now)
        assertFalse(want.isEmpty())
        val before = Locale.getDefault()
        try {
            for (tag in listOf("ja-JP-u-ca-japanese", "th-TH-u-ca-buddhist-nu-thai", "ar-SA-u-nu-arab", "tr-TR")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(want, Reminders.weekly(rules, anchors[county], saved, data.plants, now), tag)
            }
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun saturdaysAhead() {
        val ny = "America/New_York"
        // Saturday 2027-01-02 at 07:59: that morning is the first.
        val list = Reminders.saturdaysAhead(date("2027-01-02T07:59", ny))
        assertEquals(8, list.size)
        assertEquals("2027-01-02T08:00", text(list.first()))
        assertEquals("2027-02-20T08:00", text(list.last()))
        // At 08:00 exactly it is not later than now, so the next Saturday is the first.
        assertEquals(listOf("2027-01-09T08:00", "2027-01-16T08:00"), Reminders.saturdaysAhead(date("2027-01-02T08:00", ny), 2).map { text(it) })
        // Across the March clock change the time stays 08:00.
        assertEquals(listOf("2027-03-13T08:00", "2027-03-20T08:00"), Reminders.saturdaysAhead(date("2027-03-10T12:00", ny), 2).map { text(it) })
        // And across the November one.
        assertEquals(listOf("2027-11-06T08:00", "2027-11-13T08:00"), Reminders.saturdaysAhead(date("2027-11-01T12:00", ny), 2).map { text(it) })
        assertEquals(20270102, Reminders.id(list[0]))
        assertEquals(0, Reminders.saturdaysAhead(date("2027-01-02T07:59", ny), 0).size)
    }

    @Test
    fun body() {
        val plants = json.decodeFromString(
            ListSerializer(Plant.serializer()),
            """
            [{"id":"b-plant","common":"Bee Balm","sci":"B b","category":"perennial"},
             {"id":"a-plant","common":"Aster","sci":"A a","category":"perennial"},
             {"id":"oak","common":"Oak","sci":"Q q","category":"tree"},
             {"id":"Z-plant","common":"Zed","sci":"Z z","category":"perennial"}]
            """,
        )
        fun job(heading: String, pair: String?, ids: List<String>) = Job(heading, heading, emptyList(), ids, pair)
        val jobs = listOf(
            job("One", "x", listOf("a-plant")), job("Two", "x", listOf("b-plant")),
            job("Three", null, listOf("a-plant", "b-plant", "Z-plant")), job("Four", "", listOf("nope")),
            job("Five", "", listOf("oak", "a-plant")),
        )
        assertEquals("One: your aster.\nThree: your aster, bee balm and zed.\nFive: your oak and aster.", Reminders.body(jobs, plants))
        assertEquals("This week in your garden", Reminders.TITLE)
    }
}
