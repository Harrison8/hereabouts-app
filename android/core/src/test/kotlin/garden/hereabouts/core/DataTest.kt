// Checks that the bundle's data/ and tables/ folders decode into the Kotlin types, with nothing lost. Every expected
// value is read from the bundle's own JSON, never written here, so routine edits to prices, shipping areas or the
// tables do not break these tests; only a change in the files' shape does.
package garden.hereabouts.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

// Reading expected values out of the raw JSON

private fun str(j: JsonElement?): String? = (j as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun bool(j: JsonElement?): Boolean? = (j as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun num(j: JsonElement?): Double? = (j as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

private fun strings(j: JsonElement?): List<String>? = (j as? JsonArray)?.mapNotNull { str(it) }

private fun numbers(j: JsonElement?): List<Double>? = (j as? JsonArray)?.mapNotNull { num(it) }

private fun obj(j: JsonElement?): JsonObject? = j as? JsonObject

/**
 * The website's reading of a shipping area: "contiguous-48", a list of states, { allExcept: [...] }, and any other
 * value as shipping nowhere. A missing or null field is null.
 */
private fun shipsTo(j: JsonElement?): ShipsTo? = when (j) {
    null, is JsonNull -> null
    is JsonPrimitive -> if (j.isString && j.content == "contiguous-48") ShipsTo.Contiguous48 else ShipsTo.States(emptyList())
    is JsonArray -> ShipsTo.States(strings(j) ?: emptyList())
    is JsonObject -> strings(j["allExcept"])?.let { ShipsTo.AllExcept(it) } ?: ShipsTo.States(emptyList())
}

/** Collects mismatches and reports the count plus the first few, so a broken table gives one readable failure. */
private class Mismatches {
    val lines = ArrayList<String>()

    fun <T> check(actual: T, expected: T, what: () -> String) {
        if (actual != expected) lines.add("${what()}: expected $expected, got $actual")
    }

    fun report(name: String) {
        if (lines.isNotEmpty()) fail("$name: ${lines.size} mismatches, first: ${lines.take(5).joinToString("; ")}")
    }
}

class DataTest {
    @Test
    fun vendorsIsAnObject() {
        // vendors.json is an object keyed by seller name, and every seller decodes with each field the website reads.
        val raw = Expected.bundleJSON("data/vendors.json") as? JsonObject ?: fail("vendors.json is not an object")
        val data = Expected.data
        assertTrue(raw.isNotEmpty())
        assertEquals(raw.keys, data.vendors.keys)
        val m = Mismatches()
        for ((key, value) in raw) {
            val o = obj(value) ?: fail("vendors.json: $key is not an object")
            val v = data.vendors[key] ?: fail("vendors.json: $key did not decode")
            m.check(v.name, key) { "$key name is the key" }
            val expected = Vendor(
                name = str(o["name"]) ?: "", short = str(o["short"]), location = str(o["location"]), coords = numbers(o["coords"]),
                storefront = bool(o["storefront"]), affiliate = bool(o["affiliate"]), shipsTo = shipsTo(o["shipsTo"]),
                shipsExcept = strings(o["shipsExcept"]),
            )
            m.check(v, expected) { key }
        }
        m.report("vendors.json")
    }

    @Test
    fun shipsToShapes() {
        // Each shape of shipping area, written out here so the test does not depend on which sellers use which.
        fun decode(text: String): ShipsTo? = json.decodeFromString(Vendor.serializer(), """{"name":"S","shipsTo":$text}""").shipsTo
        assertEquals(ShipsTo.Contiguous48, decode("\"contiguous-48\""))
        assertEquals(ShipsTo.States(listOf("KY", "IN")), decode("""["KY","IN"]"""))
        assertEquals(ShipsTo.States(emptyList()), decode("[]"))
        assertEquals(ShipsTo.AllExcept(listOf("CA")), decode("""{"allExcept":["CA"]}"""))
        assertEquals(ShipsTo.States(emptyList()), decode("\"somewhere\""))
        assertEquals(ShipsTo.States(emptyList()), decode("12"))
        assertNull(decode("null"))
    }

    @Test
    fun everyPlantDecodes() {
        val raw = Expected.bundleJSON("data/plants.json") as? JsonArray ?: fail("plants.json is not a list")
        val data = Expected.data
        assertTrue(raw.isNotEmpty())
        assertEquals(raw.size, data.plants.size)
        assertEquals(data.plants.size, data.plants.map { it.id }.toSet().size, "plant ids repeat")
        val m = Mismatches()
        for ((plant, value) in data.plants.zip(raw)) {
            val o = obj(value)
            if (o == null) {
                m.lines.add("a plant is not an object")
                continue
            }
            val id = plant.id
            m.check(id, str(o["id"]) ?: "") { "id" }
            m.check(plant.common, str(o["common"]) ?: "") { "$id common" }
            m.check(plant.sci, str(o["sci"]) ?: "") { "$id sci" }
            m.check(plant.category, str(o["category"]) ?: "") { "$id category" }
            m.check(plant.usda, str(o["usda"])) { "$id usda" }
            m.check(plant.height, numbers(o["height"]) ?: emptyList()) { "$id height" }
            m.check(plant.spread, numbers(o["spread"]) ?: emptyList()) { "$id spread" }
            m.check(plant.sun, strings(o["sun"]) ?: emptyList()) { "$id sun" }
            m.check(plant.moisture, strings(o["moisture"]) ?: emptyList()) { "$id moisture" }
            m.check(plant.bloomColor, strings(o["bloomColor"]) ?: emptyList()) { "$id bloomColor" }
            m.check(plant.wildlife, strings(o["wildlife"]) ?: emptyList()) { "$id wildlife" }
            m.check(plant.zones, strings(o["zones"]) ?: emptyList()) { "$id zones" }
            m.check(plant.months, strings(o["months"]) ?: emptyList()) { "$id months" }
            m.check(plant.bloomMonths, (numbers(o["bloomMonths"]) ?: emptyList()).map { it.toInt() }) { "$id bloomMonths" }
            m.check(plant.fallColor, str(o["fallColor"])) { "$id fallColor" }
            m.check(plant.evergreen, bool(o["evergreen"])) { "$id evergreen" }
            m.check(plant.notes, str(o["notes"])) { "$id notes" }
            // Plants with photos keep every phase; plants without get an empty map.
            val photos = (obj(o["phasePhotos"]) ?: JsonObject(emptyMap())).mapValues { (_, p) ->
                val po = obj(p) ?: JsonObject(emptyMap())
                Photo(str(po["url"]) ?: "", str(po["alt"]) ?: "", str(po["credit"]) ?: "")
            }
            m.check(plant.phasePhotos, photos) { "$id phasePhotos" }
            val maturity = obj(o["maturity"])?.let { Maturity(str(it["years"]), str(it["notes"])) }
            m.check(plant.maturity, maturity) { "$id maturity" }
            val vendors = ((o["vendors"] as? JsonArray) ?: JsonArray(emptyList())).map { e ->
                val eo = obj(e) ?: JsonObject(emptyMap())
                VendorEntry(
                    name = str(eo["name"]) ?: "", url = str(eo["url"]), price = str(eo["price"]), size = str(eo["size"]),
                    stock = str(eo["stock"]), shipsTo = strings(eo["shipsTo"]),
                )
            }
            m.check(plant.vendors, vendors) { "$id vendors" }
        }
        m.report("plants.json")
        assertTrue(data.plants.any { it.phasePhotos.isNotEmpty() }, "no plant has photos")
        assertTrue(data.plants.any { it.vendors.isNotEmpty() }, "no plant has sellers")
    }

    @Test
    fun regionsAndVersion() {
        val data = Expected.data
        val regions = obj(Expected.bundleJSON("data/plant-regions.json")) ?: fail("plant-regions.json is not an object")
        val version = obj(Expected.bundleJSON("data/version.json")) ?: fail("version.json is not an object")
        val geo = (obj(regions["plants"]) ?: JsonObject(emptyMap())).mapValues { (_, g) ->
            PlantGeo(strings(obj(g)?.get("eco")) ?: emptyList(), strings(obj(g)?.get("intro")) ?: emptyList())
        }
        assertEquals(geo, data.geo)
        assertEquals(data.plants.size, data.geo.size)
        assertEquals(strings(regions["served"]) ?: emptyList(), data.served)
        assertTrue(data.served.isNotEmpty())
        val want = DataVersion((num(version["schema"]) ?: -1.0).toInt(), str(version["dataDate"]) ?: "", str(version["dataHash"]) ?: "")
        assertEquals(want, data.version)
        assertEquals(64, data.version.dataHash.length)
    }

    @Test
    fun unknownFieldsIgnored() {
        val plant = """
            {"id":"x","common":"X","sci":"X x","category":"tree","height":[1,2.5],"spread":[1],"sun":["full"],"moisture":[],
             "bloomColor":[],"wildlife":[],"zones":[],"months":[],"bloomMonths":[],"nativeKY":true,"someNewField":{"a":[1,"b"]},
             "vendors":[{"name":"S","inStock":true,"form":"plant","variantId":"1","shipsTo":["KY"],"another":null}]}
        """.trimIndent()
        val p = json.decodeFromString(Plant.serializer(), plant)
        assertEquals("x", p.id)
        assertEquals(listOf(1.0, 2.5), p.height)
        assertNull(p.usda)
        assertNull(p.maturity)
        assertTrue(p.phasePhotos.isEmpty())
        assertEquals(listOf(VendorEntry(name = "S", shipsTo = listOf("KY"))), p.vendors)

        // A list left out or set to null is empty.
        val bare = json.decodeFromString(Plant.serializer(), """{"id":"y","common":"Y","sci":"Y y","category":"fern","sun":null}""")
        assertEquals(emptyList<String>(), bare.sun)
        assertEquals(emptyList<Double>(), bare.height)
        assertEquals(emptyList<VendorEntry>(), bare.vendors)

        val vendor = """{"name":"S","coords":null,"storefront":false,"shipsTo":{"allExcept":["ID"]},"confirmed":true,"carriesCount":3,"kind":"x"}"""
        val v = json.decodeFromString(Vendor.serializer(), vendor)
        assertEquals(ShipsTo.AllExcept(listOf("ID")), v.shipsTo)
        assertNull(v.coords)
        assertEquals(false, v.storefront)

        val geo = """{"eco":["1"],"intro":[],"extra":1}"""
        assertEquals(PlantGeo(listOf("1"), emptyList()), json.decodeFromString(PlantGeo.serializer(), geo))
    }

    @Test
    fun requiredFieldsRefused() {
        // The iPhone app refuses a photo without url, alt or credit, and a plant-regions entry without eco or intro.
        // The Android app must refuse the same files, so both phones load the same data or neither does.
        assertFailsWith<SerializationException> { json.decodeFromString(PlantGeo.serializer(), """{"eco":["1"]}""") }
        assertFailsWith<SerializationException> { json.decodeFromString(PlantGeo.serializer(), """{"intro":[]}""") }
        assertFailsWith<SerializationException> { json.decodeFromString(Photo.serializer(), """{"url":"u","alt":"a"}""") }
        assertFailsWith<SerializationException> { json.decodeFromString(Photo.serializer(), """{"alt":"a","credit":"c"}""") }
        assertEquals(Photo("u", "a", "c"), json.decodeFromString(Photo.serializer(), """{"url":"u","alt":"a","credit":"c"}"""))
    }

    @Test
    fun tablesLoad() {
        val tables = Expected.tables
        fun raw(name: String): JsonObject = obj(Expected.bundleJSON("tables/$name")) ?: fail("$name is not an object")
        val m = Mismatches()

        // zip-county.json: { zip: [countyFips, lat, lon] }
        val zips = raw("zip-county.json")
        assertTrue(zips.isNotEmpty())
        assertEquals(zips.size, tables.zipCounty.size)
        assertEquals(zips.keys.sorted(), tables.sortedZips)
        for ((zip, row) in zips) {
            val r = row as? JsonArray
            val fips = str(r?.getOrNull(0))
            val lat = num(r?.getOrNull(1))
            val lon = num(r?.getOrNull(2))
            if (fips == null || lat == null || lon == null) {
                m.lines.add("zip $zip row has an unexpected shape")
                continue
            }
            m.check(tables.zipCounty[zip], ZipRow(fips, lat, lon)) { "zip $zip" }
        }

        // place-county.json: { name: [[stateCode, countyFips, lat, lon, default?], ...] }; a fifth element of 1 is the clear choice.
        val places = raw("place-county.json")
        assertTrue(places.isNotEmpty())
        assertEquals(places.size, tables.placeCounty.size)
        var defaults = 0
        for ((name, rows) in places) {
            val list = rows as? JsonArray
            if (list == null) {
                m.lines.add("place $name is not a list")
                continue
            }
            val expected = list.mapNotNull { row ->
                val r = row as? JsonArray
                if (r == null || r.size < 4) {
                    null
                } else {
                    PlaceRow(
                        str(r[0]) ?: "", str(r[1]) ?: "", num(r[2]) ?: Double.NaN, num(r[3]) ?: Double.NaN,
                        isDefault = r.size > 4 && num(r[4]) == 1.0,
                    )
                }
            }
            defaults += expected.count { it.isDefault }
            m.check(tables.placeCounty[name] ?: emptyList(), expected) { "place $name" }
        }
        assertTrue(defaults > 0, "no place row is marked as the clear choice")

        // states.json: { stateFips: [name, usps] }
        val states = raw("states.json")
        assertEquals(states.size, tables.states.size)
        for ((fips, row) in states) {
            val r = strings(row) ?: emptyList()
            m.check(tables.states[fips], if (r.size == 2) StateName(r[0], r[1]) else null) { "state $fips" }
        }
        assertEquals(tables.states.values.map { it.usps }.toSet() + "DC", tables.coveredStates)

        // The three { key: text } tables.
        for ((name, table) in listOf(
            "county-names.json" to tables.countyNames,
            "county-ecoregion.json" to tables.countyEcoregion,
            "ecoregion-names.json" to tables.ecoregionNames,
        )) {
            val r = raw(name)
            assertTrue(r.isNotEmpty(), name)
            m.check(table, r.mapValues { str(it.value) }.filterValues { it != null }) { name }
        }
        m.report("tables")
    }
}
