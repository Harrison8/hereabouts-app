// Checks Analytics against apps/shared/bundle/expected/analytics.json: the app's copies of the website's event list and
// once-per-session list, and what the website's cleanEvent keeps for every case.
package garden.hereabouts.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class AnalyticsTest {
    private fun jsonOf(v: EventValue): JsonElement = when (v) {
        is EventValue.Text -> JsonPrimitive(v.value)
        is EventValue.Number -> JsonPrimitive(v.value)
        is EventValue.Bool -> JsonPrimitive(v.value)
    }

    @Test
    fun listsMatchWebsite() {
        val allowed = Expected.field("analytics.json", "allowed") as? JsonObject ?: fail("allowed is not an object")
        val want = LinkedHashMap<String, List<String>>()
        for ((k, v) in allowed) want[k] = Expected.strings(v, "analytics.json allowed.$k")
        assertTrue(want.isNotEmpty())
        // Map equality ignores order: the expected files sort every object's keys, so analytics.json cannot say the
        // website's order. Only the properties within an event keep their order, and those lists are compared in full.
        assertEquals(want, Analytics.allowed)
        val once = Expected.strings(Expected.field("analytics.json", "onceKeys"), "analytics.json onceKeys")
        assertTrue(once.isNotEmpty())
        assertEquals(once, Analytics.onceKeys)
    }

    @Test
    fun everyExpectedCase() {
        val cases = Expected.cases("analytics.json")
        val bad = ArrayList<String>()
        for (c in cases) {
            if (c.fields.keys != setOf("event", "props", "result")) {
                bad.add("case ${c.index}: fields ${c.fields.keys.sorted()}")
                continue
            }
            val event = c.string("event")
            val props = LinkedHashMap<String, EventValue>()
            for ((k, v) in c.obj("props")) {
                val ev = (v as? JsonPrimitive)?.let { EventValue.fromJson(it) }
                if (ev == null) bad.add("case ${c.index}: property $k is not text, a number or true/false") else props[k] = ev
            }
            val cleaned = Analytics.clean(event, props)
            val got: JsonElement = if (cleaned == null) JsonNull else JsonObject(cleaned.mapValues { jsonOf(it.value) })
            val want = c.element("result")
            if (!jsonEquals(got, want)) bad.add("case ${c.index} $event: got $got, expected $want")
        }
        assertTrue(cases.isNotEmpty())
        println("analytics.json: ${cases.size} cases checked")
        if (bad.isNotEmpty()) fail("${bad.size} mismatches in analytics.json; first:\n" + bad.take(5).joinToString("\n"))
    }

    @Test
    fun cleanKeepsOnlyListedKeysInListOrder() {
        val got = Analytics.clean(
            "affiliate_click",
            mapOf("address" to EventValue.Text("1 Main St"), "local" to EventValue.Bool(true), "plant" to EventValue.Text("x")),
        )
        assertEquals(listOf("plant", "local"), got?.keys?.toList())
        assertNull(Analytics.clean("toString", emptyMap()))
        assertEquals(emptyMap<String, EventValue>(), Analytics.clean("plant_saved", mapOf("plant" to EventValue.Text("x"))))
    }

    @Test
    fun eventValueRoundTrip() {
        val serializer = MapSerializer(String.serializer(), EventValueSerializer)
        val props = mapOf("a" to EventValue.Text("x"), "b" to EventValue.Number(12.0), "c" to EventValue.Bool(true), "d" to EventValue.Text("true"))
        val text = json.encodeToString(serializer, props)
        assertEquals("""{"a":"x","b":12,"c":true,"d":"true"}""", text)
        assertEquals(props, json.decodeFromString(serializer, text))
    }

    @Test
    fun numbersAreWrittenLikeTheWebsite() {
        val serializer = MapSerializer(String.serializer(), EventValueSerializer)
        fun written(x: Double) = json.encodeToString(serializer, mapOf("n" to EventValue.Number(x)))
        // What JSON.stringify({n: x}) gives on the website.
        assertEquals("""{"n":0}""", written(0.0))
        assertEquals("""{"n":0}""", written(-0.0))
        assertEquals("""{"n":-3}""", written(-3.0))
        assertEquals("""{"n":0.1}""", written(0.1))
        assertEquals("""{"n":1e+21}""", written(1e21))
        assertEquals("""{"n":1.5e-7}""", written(1.5e-7))
        for (x in listOf(1e21, 1.5e-7, 0.1, 123456789012.0)) {
            assertEquals(mapOf("n" to EventValue.Number(x)), json.decodeFromString(serializer, written(x)))
        }
    }

    @Test
    fun numbersThatAreNotFiniteAreNeverWritten() {
        val serializer = MapSerializer(String.serializer(), EventValueSerializer)
        for (x in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            // The same as the iPhone app's JSONEncoder: an error, never text that is not JSON.
            assertFailsWith<SerializationException>("$x") {
                json.encodeToString(serializer, mapOf("a" to EventValue.Text("x"), "n" to EventValue.Number(x)))
            }
        }
    }
}
