// The six lookup tables location finding uses, read from the bundle's tables/ folder. They are the website's own
// files, copied into the bundle as they are. Their rows are JSON lists, not objects, so they are read by hand.
package garden.hereabouts.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import java.io.File

/** A row of zip-county.json: the ZIP's main county and a point inside the ZIP area. In the file: [countyFips, lat, lon]. */
data class ZipRow(val countyFips: String, val lat: Double, val lon: Double)

/**
 * A row of place-county.json: one city or county with this name. In the file: [state, countyFips, lat, lon] or
 * [state, countyFips, lat, lon, 1]. The trailing 1 (`isDefault`) on a name's first row marks the clear choice when
 * the visitor gives no state.
 */
data class PlaceRow(
    /** Two-letter postal code, like "OH". */
    val state: String,
    val countyFips: String,
    val lat: Double,
    val lon: Double,
    val isDefault: Boolean = false,
)

/** A row of states.json (the website's STATE_NAMES): a state the site covers. In the file: [name, postal code]. */
data class StateName(val name: String, val usps: String)

class Tables(
    /** zip-county.json, by five-digit ZIP. */
    val zipCounty: Map<String, ZipRow>,
    /** place-county.json, by place name in the form `LocationLookup.normalizePlaceName` gives. Biggest place first. */
    val placeCounty: Map<String, List<PlaceRow>>,
    /** county-names.json: county FIPS to name, like "Jefferson County". */
    val countyNames: Map<String, String>,
    /** county-ecoregion.json: county FIPS to its EPA level III ecoregion code. */
    val countyEcoregion: Map<String, String>,
    /** states.json: the lower 48 states and DC, by state FIPS code. */
    val states: Map<String, StateName>,
    /** ecoregion-names.json: ecoregion code to name. */
    val ecoregionNames: Map<String, String>,
) {
    /**
     * Every ZIP in `zipCounty`, in ascending order, so `LocationLookup.nearest` breaks ties the website's way
     * (Object.keys(zipTable).sort(), which compares UTF-16 code units, as Kotlin's String order does).
     */
    val sortedZips: List<String> = zipCounty.keys.sorted()

    /** Postal codes of the states in `states`, plus DC. */
    val coveredStates: Set<String> = states.values.map { it.usps }.toSet() + "DC"

    companion object {
        /** Reads the six tables from `tablesDir` (the bundle's tables/ folder). Throws if a file is missing or has another shape. */
        fun load(tablesDir: File): Tables = load { name -> tablesDir.resolve(name).readText(Charsets.UTF_8) }

        /**
         * Reads the six tables through `read`, which gives a file's text by its name in the bundle's tables/ folder.
         * The Android app reads them this way from its assets, which are not files. Throws if a file is missing or
         * has another shape.
         */
        fun load(read: (name: String) -> String): Tables {
            fun obj(name: String): JsonObject =
                json.parseToJsonElement(read(name)) as? JsonObject
                    ?: throw IllegalArgumentException("$name is not a JSON object")
            return Tables(
                zipCounty = obj("zip-county.json").mapValues { (zip, v) -> zipRow(zip, v) },
                placeCounty = obj("place-county.json").mapValues { (name, v) ->
                    (v as? JsonArray ?: throw IllegalArgumentException("place $name is not a list")).map { placeRow(name, it) }
                },
                countyNames = textTable(obj("county-names.json"), "county-names.json"),
                countyEcoregion = textTable(obj("county-ecoregion.json"), "county-ecoregion.json"),
                states = obj("states.json").mapValues { (fips, v) ->
                    val r = v as? JsonArray ?: throw IllegalArgumentException("state $fips is not a list")
                    require(r.size >= 2) { "state $fips has ${r.size} fields" }
                    StateName(text(r[0], "state $fips name"), text(r[1], "state $fips code"))
                },
                ecoregionNames = textTable(obj("ecoregion-names.json"), "ecoregion-names.json"),
            )
        }

        private fun text(e: JsonElement, what: String): String {
            val p = e as? JsonPrimitive
            require(p != null && p.isString) { "$what is not text" }
            return p.content
        }

        private fun number(e: JsonElement, what: String): Double {
            val p = e as? JsonPrimitive
            require(p != null && !p.isString && p.doubleOrNull != null) { "$what is not a number" }
            return p.double
        }

        private fun zipRow(zip: String, v: JsonElement): ZipRow {
            val r = v as? JsonArray ?: throw IllegalArgumentException("zip $zip is not a list")
            require(r.size >= 3) { "zip $zip has ${r.size} fields" }
            return ZipRow(text(r[0], "zip $zip county"), number(r[1], "zip $zip lat"), number(r[2], "zip $zip lon"))
        }

        private fun placeRow(name: String, v: JsonElement): PlaceRow {
            val r = v as? JsonArray ?: throw IllegalArgumentException("a row of place $name is not a list")
            require(r.size >= 4) { "a row of place $name has ${r.size} fields" }
            // The website tests `row[4] === 1`, so only the number 1 counts.
            val fifth = r.getOrNull(4) as? JsonPrimitive
            val isDefault = fifth != null && !fifth.isString && fifth.doubleOrNull == 1.0
            return PlaceRow(
                state = text(r[0], "place $name state"),
                countyFips = text(r[1], "place $name county"),
                lat = number(r[2], "place $name lat"),
                lon = number(r[3], "place $name lon"),
                isDefault = isDefault,
            )
        }

        private fun textTable(o: JsonObject, name: String): Map<String, String> =
            o.mapValues { (k, v) -> text(v, "$name $k") }
    }
}
