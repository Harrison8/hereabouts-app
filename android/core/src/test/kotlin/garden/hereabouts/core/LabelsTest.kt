// Checks Labels against apps/shared/bundle/expected/labels.json: for every plant, the words the website's index.html
// writes on its card and page. Then a few made-up values that the data does not have, with the website's answers
// worked out by running its functions in Node.
package garden.hereabouts.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class LabelsTest {
    /** A plant read from JSON text with the given fields added to a plain one. */
    private fun plant(fields: String): Plant {
        val text = """{"id":"x","common":"Ex","sci":"Ex ample","category":"perennial"""" + (if (fields.isEmpty()) "" else ",$fields") + "}"
        return json.decodeFromString(Plant.serializer(), text)
    }

    private fun text(s: String?): JsonElement = if (s == null) JsonNull else JsonPrimitive(s)

    private fun rows(list: List<List<String>>, mono: Boolean): JsonArray = JsonArray(list.map { row ->
        val a = ArrayList<JsonElement>(row.map { JsonPrimitive(it) })
        if (mono && row.isNotEmpty() && Labels.isMono(row[0])) a.add(JsonPrimitive(true))
        JsonArray(a)
    })

    private fun answers(p: Plant): Map<String, JsonElement> {
        val facts = Labels.growingFacts(p)
        val main = Labels.mainPhoto(p)
        return mapOf(
            "bloomRange" to text(Labels.bloomRange(p.bloomMonths)),
            "height" to text(Labels.ftRange(p.height)),
            "spread" to text(Labels.ftRange(p.spread)),
            "sun" to text(Labels.sun(p.sun)),
            "moisture" to text(Labels.moisture(p.moisture)),
            "growth" to text(Labels.growth(p.maturity?.years)),
            "category" to text(Labels.category(p.category)),
            "seasonRows" to JsonArray(Labels.seasonRows(p).map { r ->
                JsonObject(mapOf(
                    "label" to JsonPrimitive(r.label),
                    "state" to JsonPrimitive(r.state),
                    "runs" to JsonArray(r.runs.map { run -> JsonArray(run.map { JsonPrimitive(it) }) }),
                ))
            }),
            "growingFacts" to JsonObject(mapOf(
                "quick" to rows(facts.quick, mono = true),
                "more" to rows(facts.more, mono = false),
                "flowers" to rows(facts.flowers, mono = false),
            )),
            "mainPhoto" to if (main == null) JsonNull else JsonObject(mapOf(
                "phase" to JsonPrimitive(main.season),
                "url" to JsonPrimitive(main.photo.url),
                "alt" to JsonPrimitive(main.photo.alt),
                "credit" to JsonPrimitive(main.photo.credit),
            )),
            "plantTitle" to text(Labels.plantTitle(p)),
        )
    }

    @Test
    fun everyExpectedCase() {
        val data = Expected.data
        val byId = data.plants.associateBy { it.id }
        val cases = Expected.cases("labels.json")
        assertEquals(data.plants.size, cases.size, "labels.json should have one case per plant")
        val fields = setOf(
            "plant", "bloomRange", "height", "spread", "sun", "moisture", "growth", "category", "seasonRows",
            "growingFacts", "mainPhoto", "plantTitle",
        )
        val bad = ArrayList<String>()
        for (c in cases) {
            val id = c.string("plant")
            if (c.fields.keys != fields) {
                bad.add("$id: fields ${c.fields.keys.sorted()}")
                continue
            }
            val p = byId[id]
            if (p == null) {
                bad.add("$id: no such plant")
                continue
            }
            val mine = answers(p)
            for (key in (fields - "plant").sorted()) {
                val want = c.element(key)
                val got = mine[key] ?: JsonNull
                if (!jsonEquals(got, want)) bad.add("$id $key: got $got, expected $want")
            }
        }
        println("labels.json: ${cases.size} cases checked")
        if (bad.isNotEmpty()) fail("${bad.size} mismatches in labels.json; first:\n" + bad.take(5).joinToString("\n"))
    }

    @Test
    fun photoOrderMatchesWebsite() {
        assertEquals(Expected.strings(Expected.field("labels.json", "photoOrder"), "labels.json photoOrder"), Labels.photoOrder)
    }

    @Test
    fun photosFollowPhotoOrderAndStartWithMainPhoto() {
        val data = Expected.data
        var checked = 0
        for (p in data.plants) {
            val list = Labels.photos(p)
            val rank = list.map { Labels.photoOrder.indexOf(it.season) }
            assertEquals(rank.sorted(), rank, "${p.id}: photos out of order")
            assertEquals(p.phasePhotos.keys.count { it in Labels.photoOrder }, list.size, p.id)
            assertEquals(Labels.mainPhoto(p), list.firstOrNull(), p.id)
            checked++
        }
        assertTrue(checked > 0)
        val made = plant(
            """"phasePhotos":{"dormant":{"url":"d","alt":"","credit":""},"green":{"url":"g","alt":"","credit":""},"odd":{"url":"o","alt":"","credit":""}}""",
        )
        assertEquals(listOf("green", "dormant"), Labels.photos(made).map { it.season })
        assertEquals("g", Labels.mainPhoto(made)?.photo?.url)
        assertNull(Labels.mainPhoto(plant("")))
    }

    // Values the data does not have (answers from the website's functions in Node).

    @Test
    fun growthShorthand() {
        val cases = listOf(
            "2-3" to "2–3 years to full size", " 2 – 3 " to "2–3 years to full size",
            "02-10" to "02–10 years to full size", "~2 yrs; 1-2 ft/yr" to "About 2 years; 1–2 ft a year",
            "~ 3 yr" to "About 3 year", "6 in. tall" to "6 in tall", "6 in." to "6 in", "6 in.;" to "6 in;",
            "in.x" to "In.x", "twin. 1" to "Twin. 1", "1-2-3" to "1–2-3", "pyrs and yrs2 yrs" to "Pyrs and yrs2 years",
            "shrub-like 2-3" to "Shrub-like 2–3", "ft/yrs" to "Ft/years", "é 1-2 yr" to "É 1–2 year",
            "2-3 years to fill in." to "2–3 years to fill in",
        )
        for ((input, want) in cases) assertEquals(want, Labels.growth(input), input)
        assertNull(Labels.growth(null))
    }

    @Test
    fun ftRangeRoundsHalvesUpLikeToFixed() {
        assertEquals("0.3–0.8 ft", Labels.ftRange(listOf(0.25, 0.75)))
        assertEquals("1.3–2 ft", Labels.ftRange(listOf(1.25, 2.0)))
        assertEquals("0.3–1 ft", Labels.ftRange(listOf(0.35, 1.0)))
        assertEquals("3 ft", Labels.ftRange(listOf(3.0, 3.0)))
        assertEquals("0.5 ft", Labels.ftRange(listOf(0.5, 0.5)))
        assertEquals("1–1.5 ft", Labels.ftRange(listOf(1.0, 1.5)))
        assertEquals("0.1–2.5 ft", Labels.ftRange(listOf(0.05, 2.45)))
        assertEquals("", Labels.ftRange(listOf(1.0)))
        assertEquals("-0.0", JsText.toFixed(-0.04, 1))
        assertEquals("2.00", JsText.toFixed(2.0, 2))
        // toFixed works on the exact binary value: 1.005 is just below 1.005, so it rounds down.
        assertEquals("1.00", JsText.toFixed(1.005, 2))
    }

    @Test
    fun bloomRangeSunAndMoisture() {
        assertEquals("May, May–Jun", Labels.bloomRange(listOf(5, 5, 6)))
        assertEquals("Jan, Dec", Labels.bloomRange(listOf(12, 1)))
        assertEquals("Mar–May, Jul, Sep–Oct", Labels.bloomRange(listOf(3, 4, 5, 7, 9, 10)))
        assertNull(Labels.bloomRange(emptyList()))
        // Months outside 1-12, as the website writes them.
        assertEquals("", Labels.bloomRange(listOf(13)))
        assertEquals(", May", Labels.bloomRange(listOf(0, 5)))
        assertEquals("undefined–undefined", Labels.bloomRange(listOf(13, 14)))
        assertEquals("Full sun or full shade", Labels.sun(listOf("shade", "full")))
        assertEquals("Part shade", Labels.sun(listOf("part")))
        assertEquals("Full sun to full shade", Labels.sun(listOf("full", "part", "shade")))
        assertEquals("", Labels.sun(listOf("x")))
        assertEquals("Dry or wet", Labels.moisture(listOf("wet", "dry")))
        assertEquals("Medium to wet", Labels.moisture(listOf("medium", "wet")))
        assertEquals("Grass / Sedge", Labels.category("grass"))
        assertEquals("", Labels.category("cactus"))
    }

    @Test
    fun monoRowsAreHeightAndWidth() {
        assertTrue(Labels.isMono("Height"))
        assertTrue(Labels.isMono("Width"))
        assertFalse(Labels.isMono("Sun"))
        assertFalse(Labels.isMono("Soil"))
    }

    @Test
    fun creditTextKeepsTheLicenceNameTogether() {
        val nb = "\u00A0"
        val hy = "\u2011"
        assertEquals("Jane Doe, CC${nb}BY${hy}SA${nb}4.0", Labels.creditText("Jane Doe, CC BY-SA 4.0"))
        assertEquals("A, B, Public${nb}domain", Labels.creditText("A, B, Public domain"))
        assertEquals("No licence here", Labels.creditText("No licence here"))
        assertEquals("Name, Some Place", Labels.creditText("Name, Some Place"))
        assertEquals("", Labels.creditText(""))
        // A source in brackets stays breakable: only the licence name is held.
        assertEquals("NPS, Public${nb}Domain (NPS)", Labels.creditText("NPS, Public Domain (NPS)"))
        assertEquals("Jane Doe, CC${nb}BY (iNaturalist)", Labels.creditText("Jane Doe, CC BY (iNaturalist)"))
    }
}
