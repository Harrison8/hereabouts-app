// The street-address lookup. Not built yet: the tests that describe it come first.
package garden.hereabouts.app.firstopen

import garden.hereabouts.core.LocationResult
import garden.hereabouts.core.Tables
import kotlinx.serialization.json.JsonElement
import java.net.URL

data class GeocoderPlace(val countyFips: String, val countyName: String, val lat: Double, val lon: Double)

class AddressLookup(private val tables: Tables, private val endpoint: String = GEOCODER) {
    class NoAnswer : Exception()

    fun requestUrl(text: String): URL = url(text, endpoint)

    suspend fun county(text: String): LocationResult = LocationResult.NotFound

    companion object {
        const val GEOCODER = "https://geocoding.geo.census.gov/geocoder/geographies/onelineaddress"

        fun url(text: String, endpoint: String = GEOCODER): URL = URL(endpoint)

        fun countyFromGeocoderReply(reply: JsonElement?): GeocoderPlace? = null
    }
}
