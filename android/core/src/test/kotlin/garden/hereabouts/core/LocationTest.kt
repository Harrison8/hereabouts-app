// Checks LocationLookup against expected/location.json: every case there is the website's own answer (location.js
// run by scripts/app/expected-answers.mjs), and the Kotlin code must give exactly the same one.
package garden.hereabouts.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class LocationTest {
    // Turning answers into JSON shaped like the website's

    private fun text(s: String) = JsonPrimitive(s)

    private fun json(loc: Location): JsonElement = JsonObject(
        mapOf(
            "countyFips" to text(loc.countyFips), "countyName" to text(loc.countyName), "stateFips" to text(loc.stateFips),
            "stateName" to text(loc.stateName), "stateUsps" to text(loc.stateUsps), "ecoregion" to text(loc.ecoregion),
            "lat" to (loc.lat?.let { JsonPrimitive(it) } ?: JsonNull), "lon" to (loc.lon?.let { JsonPrimitive(it) } ?: JsonNull),
        ),
    )

    private fun json(kind: InputKind): JsonElement = JsonObject(
        when (kind) {
            InputKind.Empty -> mapOf("kind" to text("empty"))
            is InputKind.Zip -> mapOf("kind" to text("zip"), "zip" to text(kind.zip))
            is InputKind.Address -> mapOf("kind" to text("address"), "text" to text(kind.text))
            is InputKind.State -> mapOf("kind" to text("state"), "code" to text(kind.code), "name" to text(kind.name))
            is InputKind.Place -> mapOf("kind" to text("place"), "text" to text(kind.text))
        },
    )

    /** A ZIP or coordinates answer: the Location, `{ error }`, or null when there is no ZIP to use. */
    private fun json(result: LocationResult): JsonElement = when (result) {
        is LocationResult.Found -> json(result.location)
        LocationResult.NotFound -> JsonNull
        LocationResult.OutsideUs48 -> JsonObject(mapOf("error" to text(result.error ?: "")))
    }

    private fun json(result: PlaceResult): JsonElement = when (result) {
        is PlaceResult.Found -> json(result.location)
        is PlaceResult.Ambiguous ->
            JsonObject(mapOf("error" to text(result.error ?: ""), "states" to JsonArray(result.states.map { text(it) })))
        else -> JsonObject(mapOf("error" to text(result.error ?: "")))
    }

    // The expected answers

    @Test
    fun everyExpectedCase() {
        val tables = Expected.tables
        val cases = Expected.cases("location.json")
        val counts = sortedMapOf<String, Int>()
        val bad = ArrayList<String>()
        for (c in cases) {
            val kind = c.kind ?: fail("location.json case ${c.index} has no kind")
            counts[kind] = (counts[kind] ?: 0) + 1
            val actual: JsonElement = when (kind) {
                "classify" -> json(LocationLookup.classify(c.string("text")))
                // An unknown ZIP is NotFound, which json() writes as null, the website's answer.
                "zip" -> json(LocationLookup.zip(c.string("zip"), tables))
                "place" -> json(LocationLookup.place(c.string("text"), tables))
                "placeKind" -> LocationLookup.place(c.string("text"), tables).kind?.let { text(it.word) } ?: JsonNull
                "coords" -> json(LocationLookup.nearest(c.double("lat"), c.double("lon"), tables))
                // A message case is either a state code alone, or a place name with its list of states. A case with
                // both, or with neither, is a mistake in location.json.
                "message" -> when {
                    c.has("code") && !c.has("text") && !c.has("states") ->
                        text(LocationLookup.stateMessage(c.string("code")))
                    c.has("text") && !c.has("code") ->
                        text(LocationLookup.ambiguousMessage(c.string("text"), c.strings("states")))
                    else -> fail("location.json case ${c.index}: a message case needs either code alone or text with states")
                }
                "header" -> text(LocationLookup.headerText(c.objOrNull("location")?.let { Expected.location(it, "case ${c.index} location") }))
                "coverage" -> {
                    val n = c.double("n")
                    assertEquals(n, Math.floor(n), "case ${c.index}: n should be a whole number")
                    text(LocationLookup.coverageText(n.toInt(), c.bool("served")))
                }
                else -> fail("case ${c.index}: unknown kind $kind")
            }
            val expected = c.element("result")
            if (!jsonEquals(actual, expected)) {
                bad.add("location.json case ${c.index} ($kind) ${c.input()}: expected $expected, got $actual")
            }
        }
        println("location.json: ${cases.size} cases (${counts.entries.joinToString { "${it.key} ${it.value}" }}), ${bad.size} failed")
        if (bad.isNotEmpty()) fail("${bad.size} of ${cases.size} location cases differ; first:\n" + bad.take(10).joinToString("\n"))
        // No quiet pass: every kind the file is made of must be there.
        for (kind in listOf("classify", "zip", "place", "placeKind", "coords", "message", "header", "coverage")) {
            assertTrue((counts[kind] ?: 0) > 0, "location.json has no $kind cases")
        }
    }

    // Parts the expected file does not cover

    @Test
    fun roundPosition() {
        // Math.round(x * 100) / 100: halves go up (toward +infinity), so -0.125 gives -0.12, not -0.13.
        assertEquals(38.85, LocationLookup.roundPosition(38.84629))
        assertEquals(-77.3, LocationLookup.roundPosition(-77.30449))
        assertEquals(-0.12, LocationLookup.roundPosition(-0.125))
        assertEquals(0.13, LocationLookup.roundPosition(0.125))
        assertEquals(1.0, LocationLookup.roundPosition(1.005))
        assertEquals(-85.75, LocationLookup.roundPosition(-85.755))
    }

    @Test
    fun nativeHere() {
        val louisville = Location("21111", "Jefferson County", "21", "Kentucky", "KY", "71", 38.25, -85.75)
        val geo = mapOf("p" to PlantGeo(eco = listOf("71"), intro = listOf("18")))
        assertTrue(LocationLookup.nativeHere("p", louisville, geo))
        assertFalse(LocationLookup.nativeHere("no-such-plant", louisville, geo))
        val coast = Location("41007", "Clatsop County", "41", "Oregon", "OR", "1", null, null)
        assertFalse(LocationLookup.nativeHere("p", coast, geo))
        // With the real data: every plant is native in its own ecoregions, unless introduced in that state.
        val data = Expected.data
        assertTrue(data.geo.isNotEmpty())
        for ((id, g) in data.geo) {
            for (eco in g.eco) {
                val here = Location("21111", "", "21", "", "", eco, null, null)
                assertEquals("21" !in g.intro, LocationLookup.nativeHere(id, here, data.geo), id)
            }
        }
        // Native to the ecoregion but flagged as introduced in the state.
        assertFalse(LocationLookup.nativeHere("p", louisville, mapOf("p" to PlantGeo(listOf("71"), listOf("21")))))
    }

    @Test
    fun errorWordsAreTheWebsites() {
        assertEquals("ambiguous", PlaceResult.Ambiguous(listOf("MO")).error)
        assertEquals("outside_us48", PlaceResult.OutsideUs48(null).error)
        assertEquals("not_found", PlaceResult.NotFound.error)
        assertEquals(null, PlaceResult.Found(Location("21111", "", "21", "", "KY", "71"), PlaceKind.City).error)
        assertEquals("outside_us48", LocationResult.OutsideUs48.error)
        assertEquals("not_found", LocationResult.NotFound.error)
        assertEquals("city", PlaceKind.City.word)
        assertEquals("county", PlaceKind.County.word)
    }

    @Test
    fun placeNamesInOneForm() {
        assertEquals("st louis, mo", LocationLookup.normalizePlaceName("  Saint  Louis ,  MO, "))
        assertEquals("coeur dalene", LocationLookup.normalizePlaceName("Coeur d’Alene"))
        assertEquals("canon city", LocationLookup.normalizePlaceName("Cañon City"))
        assertEquals("winston salem ft worth", LocationLookup.normalizePlaceName("Winston-Salem/Fort Worth"))
        assertEquals("mountain view", LocationLookup.normalizePlaceName("Mountain View"))
        assertEquals("Winston-Salem Nc", LocationLookup.displayName("  winston-salem   nc "))
        assertEquals("O'fallon", LocationLookup.displayName("o'fallon"))
    }
}
