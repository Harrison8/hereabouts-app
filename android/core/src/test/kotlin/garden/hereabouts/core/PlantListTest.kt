// Checks the plant list against the website's: every case in apps/shared/bundle/expected/list.json gives the same
// plants in the same order, and the same "Also matching" lists. Each case sets the page's state, filters the plants
// with passes, sorts with sortPlants, and asks hiddenNameMatches only when the search box is not empty.
package garden.hereabouts.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PlantListTest {
    /** The page's state for one case. Every one of the website's 11 state fields must be there, and nothing else. */
    private fun state(c: Case): FilterState {
        val s = c.obj("state")
        val known = setOf("sun", "moisture", "category", "wildlife", "zones", "bloomMonth", "bloomColor", "maxHeight", "buy", "search", "sort")
        assertEquals(known, s.keys, "list.json case ${c.index} state fields")
        val st = Case("list.json state", c.index, s)
        val sortText = st.string("sort")
        val sort = SortKey.fromKey(sortText) ?: fail("case ${c.index}: unknown sort $sortText")
        val buy = st.strings("buy").map { BuyMode.fromKey(it) ?: fail("case ${c.index}: unknown buy mode $it") }
        val months = (st.element("bloomMonth") as? JsonArray ?: fail("case ${c.index}: bloomMonth is not a list")).map { m ->
            val d = (m as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: fail("case ${c.index}: month $m is not a number")
            if (d != Math.floor(d)) fail("case ${c.index}: month $d is not a whole number")
            d.toInt()
        }
        return FilterState(
            sun = st.strings("sun").toSet(), moisture = st.strings("moisture").toSet(), category = st.strings("category").toSet(),
            wildlife = st.strings("wildlife").toSet(), zones = st.strings("zones").toSet(), bloomMonth = months.toSet(),
            bloomColor = st.strings("bloomColor").toSet(), maxHeight = st.double("maxHeight"),
            buy = buy.toSet(), search = st.string("search"), sort = sort,
            location = c.objOrNull("location")?.let { Expected.location(it, "list.json case ${c.index} location") },
        )
    }

    private class Rendered(val ids: List<String>, val county: List<String>, val filters: List<String>)

    /** The list as the page's render() builds it: ids in order, and the two "Also matching" lists. */
    private fun render(st: FilterState, data: AppData): Rendered {
        val shown = PlantList.sorted(data.plants.filter { PlantList.passes(it, st, data) }, st, data)
        if (st.search.isEmpty()) return Rendered(shown.map { it.id }, emptyList(), emptyList())
        val hidden = PlantList.hidden(shown, st, data)
        return Rendered(shown.map { it.id }, hidden.county.map { it.id }, hidden.filters.map { it.id })
    }

    /** Where two id lists first differ, for a readable failure; null when they are the same. */
    private fun difference(what: String, got: List<String>, want: List<String>): String? {
        if (got == want) return null
        val i = got.zip(want).takeWhile { it.first == it.second }.size
        val g = got.getOrElse(i) { "(end)" }
        val w = want.getOrElse(i) { "(end)" }
        return "$what differ at $i of ${want.size}: expected $w, got $g (${got.size} plants)"
    }

    private fun describe(st: FilterState): String {
        val parts = mutableListOf("sort ${st.sort.key}", "county ${st.location?.countyFips ?: "none"}")
        if (st.search.isNotEmpty()) parts.add("search \"${st.search}\"")
        if (st.sun.isNotEmpty()) parts.add("sun ${st.sun.sorted()}")
        if (st.moisture.isNotEmpty()) parts.add("moisture ${st.moisture.sorted()}")
        if (st.category.isNotEmpty()) parts.add("category ${st.category.sorted()}")
        if (st.wildlife.isNotEmpty()) parts.add("wildlife ${st.wildlife.sorted()}")
        if (st.zones.isNotEmpty()) parts.add("zones ${st.zones.sorted()}")
        if (st.bloomMonth.isNotEmpty()) parts.add("bloomMonth ${st.bloomMonth.sorted()}")
        if (st.bloomColor.isNotEmpty()) parts.add("bloomColor ${st.bloomColor.sorted()}")
        if (st.maxHeight != 100.0) parts.add("maxHeight ${st.maxHeight}")
        if (st.buy.isNotEmpty()) parts.add("buy ${st.buy.map { it.key }.sorted()}")
        return parts.joinToString(", ")
    }

    @Test
    fun everyListCase() {
        val data = Expected.data
        val cases = Expected.cases("list.json")
        assertTrue(cases.size > 100, "only ${cases.size} list cases")
        val bad = ArrayList<String>()
        for (c in cases) {
            val st = state(c)
            val got = render(st, data)
            val also = c.obj("also")
            assertEquals(setOf("county", "filters"), also.keys, "list.json case ${c.index} also fields")
            val alsoCase = Case("list.json also", c.index, also)
            val found = listOfNotNull(
                difference("ids", got.ids, c.strings("ids")),
                difference("also.county", got.county, alsoCase.strings("county")),
                difference("also.filters", got.filters, alsoCase.strings("filters")),
            )
            if (found.isNotEmpty()) bad.add("case ${c.index} (${describe(st)}): " + found.joinToString("; "))
        }
        println("list.json: ${cases.size} cases, ${bad.size} failed")
        if (bad.isNotEmpty()) fail("${bad.size} of ${cases.size} list cases differ; first:\n" + bad.take(5).joinToString("\n"))
    }

    @Test
    fun sortIsStable() {
        val data = Expected.data
        val byId = data.plants.associateBy { it.id }
        // "Height (low to high)" in Jefferson County, Kentucky, with no other choices.
        val sets = listOf("sun", "moisture", "category", "wildlife", "zones", "bloomMonth", "bloomColor", "buy")
        val c = Expected.cases("list.json").firstOrNull { x ->
            val s = x.obj("state")
            val loc = x.objOrNull("location")
            sets.all { (s[it] as? JsonArray)?.isEmpty() == true } &&
                (s["sort"] as? JsonPrimitive)?.content == "height-asc" &&
                (s["search"] as? JsonPrimitive)?.let { it.isString && it.content.isEmpty() } == true &&
                (s["maxHeight"] as? JsonPrimitive)?.doubleOrNull == 100.0 &&
                (loc?.get("countyFips") as? JsonPrimitive)?.content == "21111"
        } ?: fail("list.json has no plain height-asc case for county 21111")
        val want = c.strings("ids")
        val heights = want.mapNotNull { byId[it]?.height?.lastOrNull() }
        assertEquals(want.size, heights.size)
        val ties = heights.zipWithNext().count { it.first == it.second }
        assertTrue(ties > 0, "the case needs plants of equal height to test stability")
        assertNull(difference("ids", render(state(c), data).ids, want))

        // The same sort on the plants in reverse: plants of equal height now come out in reverse data order.
        val reversed = data.plants.reversed()
        val sorted = PlantList.sorted(reversed, FilterState(sort = SortKey.HeightAsc), data)
        val position = reversed.withIndex().associate { it.value.id to it.index }
        for ((a, b) in sorted.zipWithNext()) {
            if (a.height.lastOrNull() == b.height.lastOrNull()) {
                assertTrue(position.getValue(a.id) < position.getValue(b.id), "${a.id} and ${b.id} are the same height and keep their order")
            }
        }
        assertEquals(reversed.size, sorted.size)
    }

    @Test
    fun heightSortsPutPlantsWithoutTwoHeightsLast() {
        // 40 plants: p05, p13, p22 and p31 have no heights, p09 and p27 have only one. The rest have two, with many
        // ties. The website's order for a plant without two heights is not defined; this rule is the apps' own, and
        // the iPhone app's PlantListTests checks the same 40 plants and the same two orders.
        val none = setOf(5, 13, 22, 31)
        val single = setOf(9, 27)
        val plants = (0 until 40).map { i ->
            val height = when (i) {
                in none -> emptyList()
                in single -> listOf(4.0)
                else -> listOf(1.0, ((i * 7) % 11) + 1.0)
            }
            Plant(id = "p%02d".format(i), common = "P", sci = "P p", category = "tree", height = height)
        }
        val asc = "p00 p11 p33 p08 p19 p30 p16 p38 p02 p24 p35 p10 p21 p32 p07 p18 p29 p04 p15 p26 p37 p01 p12 p23 " +
            "p34 p20 p06 p17 p28 p39 p03 p14 p25 p36 p05 p09 p13 p22 p27 p31"
        val desc = "p03 p14 p25 p36 p06 p17 p28 p39 p20 p01 p12 p23 p34 p04 p15 p26 p37 p07 p18 p29 p10 p21 p32 p02 " +
            "p24 p35 p16 p38 p08 p19 p30 p00 p11 p33 p05 p09 p13 p22 p27 p31"
        val data = Expected.data
        assertEquals(asc.split(" "), PlantList.sorted(plants, FilterState(sort = SortKey.HeightAsc), data).map { it.id })
        assertEquals(desc.split(" "), PlantList.sorted(plants, FilterState(sort = SortKey.HeightDesc), data).map { it.id })
    }

    @Test
    fun sortMenuIsTheWebsites() {
        // list.json's sortMenu is index.html's sort menu, read when the bundle was built: the same sorts, in the same
        // order, with the same labels.
        val menu = Expected.field("list.json", "sortMenu") as? JsonArray ?: fail("list.json's sortMenu is not a list")
        val options = menu.mapIndexed { i, o ->
            val obj = o as? JsonObject ?: fail("sortMenu item $i is not an object")
            assertEquals(setOf("label", "value"), obj.keys, "sortMenu item $i fields")
            Case("list.json sortMenu", i, obj)
        }
        assertEquals(SortKey.entries.map { it.key }, options.map { it.string("value") })
        assertEquals(SortKey.entries.map { it.label }, options.map { it.string("label") })

        // The list cases run the sorts in the menu's order.
        val seen = ArrayList<String>()
        for (c in Expected.cases("list.json")) {
            val sort = Case("list.json state", c.index, c.obj("state")).string("sort")
            if (sort !in seen) seen.add(sort)
        }
        assertEquals(SortKey.entries.map { it.key }, seen)
    }

    @Test
    fun typeCounts() {
        val data = Expected.data
        val counts = PlantList.typeCounts(data.plants)
        assertEquals(PlantList.categoryOrder.filter { cat -> data.plants.any { it.category == cat } }, counts.map { it.category })
        assertEquals(data.plants.size, counts.sumOf { it.count })
        assertTrue(counts.all { it.count > 0 })
        assertEquals(listOf("fern"), PlantList.typeCounts(data.plants.filter { it.category == "fern" }).map { it.category })
    }

    @Test
    fun normalizeName() {
        assertEquals("culvers root", PlantList.normalizeName("  Culver’s  Root "))
        assertEquals("jack in the pulpit", PlantList.normalizeName("Jack–in–the–Pulpit"))
        assertEquals("echinacea", PlantList.normalizeName("Échinacea"))
        assertEquals("black eyed susan", PlantList.normalizeName("black-eyed `susan`"))
        assertEquals("", PlantList.normalizeName("   "))
    }
}
