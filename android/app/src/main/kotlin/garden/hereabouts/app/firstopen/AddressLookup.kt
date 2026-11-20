// The street-address lookup: asks the US Census Bureau's address finder which county a typed address is in. It sends
// the same question the website and the iPhone app send (onelineaddress, current address ranges, the Counties layer),
// asking for JSON, and reads the reply with the website's rules (countyFromGeocoderReply). Only the typed text goes to
// the Census; the answer is turned into a county on the phone, and the position it returns is rounded before anything
// keeps it.
package garden.hereabouts.app.firstopen

import garden.hereabouts.app.services.Http
import garden.hereabouts.core.LocationLookup
import garden.hereabouts.core.LocationResult
import garden.hereabouts.core.Tables
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.net.URL

/** What countyFromGeocoderReply takes from a Census reply: the county code and name, and the matched point. */
data class GeocoderPlace(val countyFips: String, val countyName: String, val lat: Double, val lon: Double)

class AddressLookup(private val tables: Tables, private val endpoint: String = GEOCODER) {
    /** No usable answer: none at all, none in time, an error answer, or an answer that is not the Census's JSON. */
    class NoAnswer : Exception("The address lookup gave no usable answer")

    /** The question for the typed text, sent to this lookup's address. */
    fun requestUrl(text: String): URL = url(text, endpoint)

    /**
     * The county of a typed address: Found with the matched point rounded to two decimal places, NotFound when the
     * Census has no match, OutsideUs48 when the county is outside the lower 48 states. Throws [NoAnswer] when no usable
     * answer comes back.
     */
    suspend fun county(text: String): LocationResult {
        val answer = withContext(Dispatchers.IO) { Http.get(requestUrl(text), TIMEOUT_MS) }
        if (answer == null || answer.status !in 200..299) throw NoAnswer()
        val reply = runCatching { Json.parseToJsonElement(answer.body.decodeToString()) }.getOrNull()
        if (reply !is JsonObject) throw NoAnswer()
        val place = countyFromGeocoderReply(reply) ?: return LocationResult.NotFound
        return LocationLookup.resolve(
            place.countyFips,
            LocationLookup.roundPosition(place.lat),
            LocationLookup.roundPosition(place.lon),
            tables,
        )
    }

    companion object {
        /** The Census Bureau's address finder, the only outside address the app asks besides the website. */
        const val GEOCODER = "https://geocoding.geo.census.gov/geocoder/geographies/onelineaddress"

        /** How long the lookup waits, as on the website: 8 seconds to connect, then 8 for each part of the answer. */
        const val TIMEOUT_MS = 8_000

        /** The website's question for the typed text, asking for JSON instead of a script. */
        fun url(text: String, endpoint: String = GEOCODER): URL = URL(
            "$endpoint?address=${encodeUriComponent(text)}&benchmark=Public_AR_Current&vintage=Current_Current" +
                "&layers=Counties&format=json",
        )

        /**
         * countyFromGeocoderReply: the first match's first county and its point, or null when the reply has no county
         * or no point.
         */
        fun countyFromGeocoderReply(reply: JsonElement?): GeocoderPlace? {
            val result = (reply as? JsonObject)?.get("result") as? JsonObject ?: return null
            val match = (result["addressMatches"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            val geographies = match["geographies"] as? JsonObject ?: return null
            val county = (geographies["Counties"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            val fips = (county["GEOID"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val coordinates = match["coordinates"] as? JsonObject ?: return null
            val lat = (coordinates["y"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
            val lon = (coordinates["x"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
            val name = (county["NAME"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
            return GeocoderPlace(fips, name, lat, lon)
        }

        /** The characters JavaScript's encodeURIComponent leaves as they are. */
        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

        private const val HEX = "0123456789ABCDEF"

        /**
         * JavaScript's encodeURIComponent, so the question matches the website's: every other character as its UTF-8
         * bytes in %XX form. Text with half of a surrogate pair cannot be encoded and gives "".
         */
        internal fun encodeUriComponent(text: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c in UNRESERVED) {
                    out.append(c)
                    i++
                    continue
                }
                val codePoint = text.codePointAt(i)
                if (Character.isSurrogate(c) && Character.charCount(codePoint) == 1) return ""
                val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
                for (b in bytes) {
                    val v = b.toInt() and 0xFF
                    out.append('%').append(HEX[v shr 4]).append(HEX[v and 0xF])
                }
                i += Character.charCount(codePoint)
            }
            return out.toString()
        }
    }
}
