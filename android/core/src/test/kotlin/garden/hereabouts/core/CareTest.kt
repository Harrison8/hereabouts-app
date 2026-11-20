// Checks Care, the port of the website's care-timing logic, on made-up rules and timing dates. The answers were
// worked out by running that logic itself in Node. The whole path through the real rules and every county's
// timing is checked against the bundle's expected/care.json in RemindersTest.
package garden.hereabouts.core

import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CareTest {
    private val anchors = Anchors(lastSpring = 100, firstFall = 300, hardSpring = 90, hardFall = 310, highs50 = null)

    private fun rule(
        id: String,
        order: Double,
        pair: String? = null,
        from: Pair<String, Int> = "lastSpring" to 0,
        to: Pair<String, Int> = "lastSpring" to 6,
        categories: List<String> = listOf("perennial"),
    ) = CareRule(
        id = id, heading = "H $id", categories = categories, from = CareRule.Bound(from.first, from.second),
        to = CareRule.Bound(to.first, to.second), facts = listOf("f $id"), pair = pair, order = order,
    )

    private val plants: List<Plant> = json.decodeFromString(
        ListSerializer(Plant.serializer()),
        """
        [{"id":"b-plant","common":"Bee Balm","sci":"B b","category":"perennial"},
         {"id":"a-plant","common":"Aster","sci":"A a","category":"perennial"},
         {"id":"oak","common":"Oak","sci":"Q q","category":"tree"},
         {"id":"Z-plant","common":"Zed","sci":"Z z","category":"perennial"}]
        """,
    )

    private val rules = listOf(
        rule("r5", 5.0), rule("r1", 1.0), rule("r1b", 1.0),
        rule("p2", 2.0, pair = "pp"), rule("p9", 9.0, pair = "pp", from = "firstFall" to 0, to = "firstFall" to 6),
        rule("r3", 3.0), rule("t0", 0.0, categories = listOf("tree")), rule("r4", 4.0),
    )

    @Test
    fun ruleWindow() {
        assertEquals(Care.Window(300, 100), Care.ruleWindow(rule("a", 1.0, from = "firstFall" to 0, to = "lastSpring" to 0), anchors))
        assertEquals(Care.Window(1, 365), Care.ruleWindow(rule("b", 1.0, from = "lastSpring" to -120, to = "lastSpring" to 250), anchors))
        assertNull(Care.ruleWindow(rule("c", 1.0, from = "highs50" to 0, to = "highs50" to 27), anchors))
        assertNull(Care.ruleWindow(rule("d", 1.0, from = "unknown" to 0, to = "lastSpring" to 0), anchors))
        assertNull(Care.ruleWindow(rule("e", 1.0), null))
        assertEquals(Care.Window(355, 5), Care.ruleWindow(rule("f", 1.0, from = "lastSpring" to -110, to = "lastSpring" to -95), anchors))
    }

    @Test
    fun isDueWeek() {
        assertTrue(Care.isDueWeek(Care.Window(300, 100), 365))
        assertFalse(Care.isDueWeek(Care.Window(5, 10), 362))
        assertFalse(Care.isDueWeek(Care.Window(5, 10), 361))
        assertFalse(Care.isDueWeek(Care.Window(5, 10), 363))
        assertTrue(Care.isDueWeek(Care.Window(5, 10), 364))
        assertFalse(Care.isDueWeek(Care.Window(100, 120), 93))
        assertTrue(Care.isDueWeek(Care.Window(100, 120), 94))
        assertTrue(Care.isDueWeek(Care.Window(360, 365), 400))
        assertFalse(Care.isDueWeek(null, 100))
    }

    @Test
    fun sendDay() {
        assertEquals(100, Care.sendDay("2027-04-10"))
        assertEquals(59, Care.sendDay("2028-02-29"))
        assertEquals(60, Care.sendDay("2028-03-01"))
        assertEquals(130, Care.sendDay("2027-04-40"))
        assertNull(Care.sendDay("2027-13-01"))
        assertNull(Care.sendDay("2027-4-10"))
        assertNull(Care.sendDay("2027-04-10T08:00"))
        // Only ASCII digits count, as in the website's regular expression.
        assertNull(Care.sendDay("2027-0٤-10"))
    }

    private fun summary(jobs: List<Job>) = jobs.map { "${it.ruleId} ${it.examplePlantIds} ${it.pair ?: "null"}" }

    @Test
    fun dueJobsSlotsPairsAndExamples() {
        val jobs = Care.dueJobs(rules, anchors, listOf("b-plant", "a-plant", "not", "Z-plant", "a-plant"), "2027-04-10", plants)
        val examples = "[Z-plant, a-plant, a-plant]"
        assertEquals(
            listOf("r1 $examples null", "r1b $examples null", "p2 $examples pp", "p9 $examples pp", "r3 $examples null"),
            summary(jobs),
        )
        assertEquals("H r1", jobs.first().heading)
        assertEquals(listOf("f r1"), jobs.first().facts)

        val all = Care.dueJobs(rules, anchors, listOf("oak", "b-plant"), "2027-04-10", plants, maxExamples = Int.MAX_VALUE)
        assertEquals(listOf("t0", "r1", "r1b", "p2", "p9"), all.map { it.ruleId })
        assertEquals(
            listOf(listOf("oak"), listOf("b-plant"), listOf("b-plant"), listOf("b-plant"), listOf("b-plant")),
            all.map { it.examplePlantIds },
        )

        val fall = Care.dueJobs(rules, anchors, listOf("b-plant"), "2027-10-27", plants)
        assertEquals(listOf("p2", "p9"), fall.map { it.ruleId })

        assertEquals(emptyList<Job>(), Care.dueJobs(rules, anchors, listOf("b-plant"), "bad", plants))
        assertEquals(emptyList<Job>(), Care.dueJobs(rules, null, listOf("b-plant"), "2027-04-10", plants))
        val lastDropped = Care.dueJobs(rules, anchors, listOf("a-plant", "b-plant"), "2027-04-10", plants, maxExamples = -1)
        assertEquals(listOf("a-plant"), lastDropped.first().examplePlantIds)
    }

    @Test
    fun bundleRulesAndAnchorsLoad() {
        val tables = Expected.bundleDir.resolve("tables")
        val rules = Care.rules(tables.resolve("care-rules.json").readText(Charsets.UTF_8))
        assertEquals(14, rules.size)
        assertEquals(listOf("stems-tidy", "stems-pollinator"), rules.filter { it.pair == "stems" }.map { it.id })
        val anchors = Care.anchorsByCounty(tables.resolve("care-anchors.json").readText(Charsets.UTF_8))
        assertEquals(3109, anchors.size)
        assertNotNull(anchors["21111"]?.lastSpring)
    }
}
