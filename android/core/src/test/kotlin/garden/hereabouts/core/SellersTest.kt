// Checks the "Where to buy" list against the website's: every case in apps/shared/bundle/expected/sellers.json.
// A case with no kind is buyRowsView(plant, location, "hereabouts.garden") from index.html, run as the phone app
// (channel app) or as the website (channel web). A case with kind "summary" is shipsSummary from sellers.js.
// Then the parts of Sellers the plant list needs that list.json does not pin down on its own.
package garden.hereabouts.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SellersTest {
    private val rowFields = setOf(
        "name", "short", "place", "href", "label", "ships", "local", "miles", "kind", "directions", "affiliate", "price", "size",
    )
    private val viewFields = setOf("rows", "noSellerText", "hasAffiliate")

    /** A row as the website's row object. */
    private fun rowJson(r: BuyRow): JsonObject = buildJsonObject {
        put("name", r.name)
        put("short", r.short)
        put("place", r.place)
        put("href", r.href)
        put("label", r.label)
        put("ships", r.ships)
        put("local", r.local)
        put("miles", r.miles)
        put("kind", r.kind)
        put("directions", r.directions)
        put("affiliate", r.affiliate)
        put("price", r.price)
        put("size", r.size)
    }

    /** The first difference between a view and the expected one, or null when they are the same. */
    private fun difference(got: BuyView, want: JsonObject): String? {
        if (want.keys != viewFields) return "view has fields ${want.keys.sorted()}"
        val wantRows = want.getValue("rows") as? JsonArray ?: return "rows is not a list"
        for ((i, r) in wantRows.withIndex()) {
            val o = r as? JsonObject ?: return "row $i is not an object"
            if (o.keys != rowFields) return "row $i has fields ${o.keys.sorted()}"
        }
        if (got.rows.size != wantRows.size) return "${got.rows.size} rows, expected ${wantRows.size}"
        for (i in got.rows.indices) {
            val g = rowJson(got.rows[i])
            if (!jsonEquals(g, wantRows[i])) return "row $i: got $g, expected ${wantRows[i]}"
        }
        if (!jsonEquals(JsonPrimitive(got.noSellerText), want.getValue("noSellerText"))) {
            return "noSellerText ${got.noSellerText}, expected ${want.getValue("noSellerText")}"
        }
        if (!jsonEquals(JsonPrimitive(got.hasAffiliate), want.getValue("hasAffiliate"))) return "hasAffiliate ${got.hasAffiliate}"
        return null
    }

    @Test
    fun everySellerCase() {
        val data = Expected.data
        val byId = data.plants.associateBy { it.id }
        val bad = ArrayList<String>()
        var count = 0
        val channels = HashSet<String>()
        for (c in Expected.cases("sellers.json")) {
            if (c.kind != null) {
                if (c.kind != "summary") bad.add("case ${c.index}: unknown kind ${c.kind}")
                continue
            }
            count++
            val plantId = c.string("plant")
            val channelText = c.string("channel")
            val plant = byId[plantId]
            val channel = Channel.fromKey(channelText)
            if (plant == null || channel == null) {
                bad.add("case ${c.index}: plant $plantId or channel $channelText not known")
                continue
            }
            channels.add(channelText)
            val loc = c.objOrNull("location")?.let { Expected.location(it, "sellers.json case ${c.index} location") }
            val got = Sellers.buyView(plant, loc, channel, data)
            val d = difference(got, c.obj("view"))
            if (d != null) bad.add("case ${c.index} ($plantId, ${loc?.countyFips ?: "no location"}, $channelText): $d")
        }
        println("sellers.json: $count seller cases")
        assertTrue(count > 1000, "only $count seller cases")
        assertEquals(setOf("app", "web"), channels)
        if (bad.isNotEmpty()) fail("${bad.size} of $count seller cases differ; first:\n" + bad.take(5).joinToString("\n"))
    }

    @Test
    fun everySummaryCase() {
        val data = Expected.data
        val bad = ArrayList<String>()
        var count = 0
        for (c in Expected.cases("sellers.json")) {
            if (c.kind != "summary") continue // everySellerCase fails on other kinds
            count++
            val name = c.string("vendor")
            val category = c.string("category")
            val want = c.string("result")
            val vendor = data.vendors[name]
            if (vendor == null) {
                bad.add("case ${c.index}: vendor $name not in vendors.json")
                continue
            }
            // entry = { shipsTo: entryShipsTo }, or no entry when entryShipsTo is null.
            val e = c.element("entryShipsTo")
            val entry = if (e is JsonNull) null else VendorEntry(name = name, shipsTo = c.strings("entryShipsTo"))
            val got = Sellers.shipsSummary(vendor, category, entry)
            if (got != want) bad.add("case ${c.index} ($name, $category, $e): got \"$got\", expected \"$want\"")
        }
        println("sellers.json: $count summary cases")
        assertTrue(count > 100, "only $count summary cases")
        if (bad.isNotEmpty()) fail("${bad.size} of $count summary cases differ; first:\n" + bad.take(5).joinToString("\n"))
    }

    @Test
    fun contiguousUspsIsTheStatesTable() {
        // sellers.js reads these from STATE_NAMES in plant-geo.js, the same codes as the bundle's tables/states.json.
        val tables = Expected.tables
        assertEquals(tables.states.values.map { it.usps }.toSet(), Sellers.contiguousUsps)
    }

    @Test
    fun shippingAreas() {
        val shop = Vendor(name = "S", shipsTo = ShipsTo.Contiguous48)
        assertTrue(Sellers.shipsToState(shop, "KY", "tree", null))
        assertFalse(Sellers.shipsToState(shop, "AK", "tree", null))
        assertFalse(Sellers.shipsToState(shop, "", "tree", null))
        assertFalse(Sellers.shipsToState(shop.copy(shipsExcept = listOf("tree")), "KY", "tree", null))
        assertTrue(Sellers.shipsToState(Vendor(name = "S", shipsTo = ShipsTo.AllExcept(listOf("CA"))), "KY", "tree", null))
        assertFalse(Sellers.shipsToState(Vendor(name = "S", shipsTo = ShipsTo.AllExcept(listOf("CA"))), "CA", "tree", null))
        assertFalse(Sellers.shipsToState(Vendor(name = "S", shipsTo = ShipsTo.AllExcept(listOf("CA"))), "HI", "tree", null))
        assertTrue(Sellers.shipsToState(Vendor(name = "S", shipsTo = ShipsTo.States(listOf("KY"))), "KY", "tree", null))
        assertFalse(Sellers.shipsToState(Vendor(name = "S"), "KY", "tree", null))
        // The plant's own list of states decides over the seller's area.
        assertFalse(Sellers.shipsToState(shop, "KY", "tree", VendorEntry(name = "S", shipsTo = listOf("IN"))))
        assertTrue(Sellers.shipsToState(Vendor(name = "S"), "IN", "tree", VendorEntry(name = "S", shipsTo = listOf("IN"))))
    }

    @Test
    fun shipsSummaryWording() {
        val v = Vendor(name = "S", location = "Goshen, KY", storefront = true, shipsTo = ShipsTo.States(listOf("TN", "KY", "OH")))
        assertEquals("Ships to KY, OH, and TN · Shop in Goshen, KY", Sellers.shipsSummary(v, "tree", null))
        assertEquals("Pick up only · Goshen, KY", Sellers.shipsSummary(v.copy(shipsExcept = listOf("tree")), "tree", null))
        assertEquals("", Sellers.shipsSummary(Vendor(name = "S"), "tree", null))
        assertEquals("Ships to the lower 48 except CA", Sellers.shipsSummary(Vendor(name = "S", shipsTo = ShipsTo.AllExcept(listOf("CA"))), "tree", null))
        val many = (1..11).map { "S$it" }
        assertEquals("Ships to 11 states", Sellers.shipsSummary(Vendor(name = "S", shipsTo = ShipsTo.States(many)), "tree", null))
        // The plant's own list (even an empty one) replaces the seller's area.
        assertEquals("", Sellers.shipsSummary(Vendor(name = "S", shipsTo = ShipsTo.Contiguous48), "tree", VendorEntry(name = "S", shipsTo = emptyList())))
    }

    @Test
    fun distanceIsTheLocationLookups() {
        val d = Sellers.distanceMi(38.25, -85.75, 34.518252, -89.030319)
        assertEquals(LocationLookup.distanceMi(38.25, -85.75, 34.518252, -89.030319), d)
        assertTrue(d > 300, "$d miles")
        assertEquals(0.0, Sellers.distanceMi(38.25, -85.75, 38.25, -85.75))
    }

    @Test
    fun milesRoundTheWayJavaScriptDoes() {
        // "LOCAL · 25 mi" uses Math.round, which sends halves up (toward +infinity), unlike Kotlin's roundToInt.
        assertEquals(25.0, JsText.mathRound(24.5))
        assertEquals(24.0, JsText.mathRound(24.4999))
        assertEquals(1.0, JsText.mathRound(0.5))
        assertEquals(-2.0, JsText.mathRound(-2.5))
        assertTrue(1.0 / JsText.mathRound(-0.4) < 0, "Math.round(-0.4) is -0")
        assertEquals(75.0, JsText.mathRound(75.0))
    }

    @Test
    fun noLocationListsEverySellerWithWhereItShips() {
        val data = Expected.data
        val plant = assertNotNull(data.plants.firstOrNull { it.vendors.size > 1 }, "no plant has two sellers")
        val view = Sellers.buyView(plant, null, Channel.App, data)
        assertEquals(plant.vendors.map { it.name }, view.rows.map { it.name })
        assertTrue(view.rows.all { it.label == null && it.kind == "shop" && !it.local })
        assertNull(view.noSellerText)
    }
}
