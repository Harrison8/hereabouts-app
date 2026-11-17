// Which events, and which of their properties, the app may send to analytics. A copy of EVENT_PROPS and
// ONCE_PER_SESSION in the website's analytics.js, with its cleanEvent. The same as the iPhone app's Analytics.swift.
// What a visitor types (an address, ZIP, city or county name) is never on the list, so it cannot reach analytics.
// The tests check both lists and every case against expected/analytics.json, which records the website's own lists,
// so a change on the website fails the tests here.
package garden.hereabouts.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** One property value of an event: text, a number or true/false, as the website sends. */
sealed interface EventValue {
    data class Text(val value: String) : EventValue
    data class Number(val value: Double) : EventValue
    data class Bool(val value: Boolean) : EventValue

    companion object {
        /**
         * The value a JSON text, number or true/false holds; null for JSON null or a number Kotlin cannot read. Text in
         * quotes stays text, so "true" in quotes is Text("true"), not Bool(true).
         */
        fun fromJson(p: JsonPrimitive): EventValue? = when {
            p is JsonNull -> null
            p.isString -> Text(p.content)
            p.booleanOrNull != null -> Bool(p.booleanOrNull!!)
            else -> p.doubleOrNull?.let { Number(it) }
        }
    }
}

/**
 * Reads and writes an EventValue as a bare JSON value ("x", 12 or true), so a saved event reads back as the same kind
 * of value. Only JSON is supported. Numbers are written as the website's JSON.stringify writes them: 12, not 12.0;
 * 0.5; 1e+21. A number that is not finite (NaN or an infinity) cannot be written as JSON, so writing one throws a
 * SerializationException, the same as the iPhone app's JSONEncoder, which throws for it too. (The website would
 * write null.) Nothing is written in that case, so a broken file or request body never goes out.
 */
object EventValueSerializer : KSerializer<EventValue> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("garden.hereabouts.core.EventValue", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): EventValue {
        val input = decoder as? JsonDecoder ?: throw SerializationException("EventValue can only be read from JSON")
        val element = input.decodeJsonElement()
        val p = element as? JsonPrimitive ?: throw SerializationException("an event value must be text, a number or true/false")
        return EventValue.fromJson(p) ?: throw SerializationException("an event value must be text, a number or true/false")
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: EventValue) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("EventValue can only be written as JSON")
        output.encodeJsonElement(
            when (value) {
                is EventValue.Text -> JsonPrimitive(value.value)
                is EventValue.Number -> {
                    if (value.value.isNaN() || value.value.isInfinite()) {
                        throw SerializationException("an event number must be finite, not ${value.value}")
                    }
                    // numberString writes what JavaScript writes, and for a finite number that is valid JSON.
                    JsonUnquotedLiteral(JsText.numberString(value.value))
                }
                is EventValue.Bool -> JsonPrimitive(value.value)
            },
        )
    }
}

object Analytics {
    /**
     * EVENT_PROPS: each event the app may send, with the only properties it may carry. The tests check the events and
     * each event's property list, in order, against analytics.json; they cannot check the order of the events, because
     * that file sorts its keys.
     */
    val allowed: Map<String, List<String>> = linkedMapOf(
        "location_set" to listOf("method", "county", "ecoregion", "state"),
        "results_shown" to listOf("count", "ecoregion"),
        "buy_panel_open" to listOf("plant"),
        "affiliate_click" to listOf("plant", "vendor", "local", "affiliate"),
        "local_vendor_click" to listOf("plant", "vendor"),
        "no_seller_shown" to listOf("plant", "state"),
        "newsletter_signup" to listOf("ecoregion"),
        "newsletter_waitlist" to listOf("state"),
        "geocode_failed" to listOf("method", "reason"),
        "zip_fallback_used" to emptyList(),
        "empty_results" to listOf("ecoregion", "state"),
        "browse_all_opened" to emptyList(),
        "plant_search" to listOf("count", "hidden_count"),
        "home_pick_opened" to listOf("plant"),
        "plant_saved" to emptyList(),
        "plant_unsaved" to emptyList(),
        "notifications_answer" to listOf("granted"),
        "this_week_opened" to listOf("from"),
    )

    /** ONCE_PER_SESSION: events sent at most once per plant in a visit. */
    val onceKeys: List<String> = listOf("buy_panel_open", "affiliate_click", "local_vendor_click", "no_seller_shown")

    /**
     * cleanEvent: the properties the event may carry, in the list's order; any other property is dropped. null for an
     * event not on the list, which is then not sent.
     */
    fun clean(event: String, props: Map<String, EventValue>): Map<String, EventValue>? {
        val keys = allowed[event] ?: return null
        val out = LinkedHashMap<String, EventValue>()
        for (key in keys) props[key]?.let { out[key] = it }
        return out
    }
}
