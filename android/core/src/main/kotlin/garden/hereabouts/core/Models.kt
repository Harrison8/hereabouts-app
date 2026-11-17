// The plant and seller records the app reads from the bundle's data/ folder (plants.json, vendors.json,
// plant-regions.json, version.json). Each type holds the fields the website reads; any other field in the files is
// ignored, so new fields in the data never break an older app. The same types as the iPhone app's Models.swift.
package garden.hereabouts.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One photo of a plant in one phase of its year. `url` is a path inside the bundle, like "images/quercus-alba-green.jpg".
 * All three fields are required, as in the iPhone app: a photo missing one makes the whole file fail to load.
 */
@Serializable
data class Photo(
    val url: String,
    val alt: String,
    val credit: String,
)

/** How long a plant takes to reach full size, and what to know while it does. */
@Serializable
data class Maturity(
    val years: String? = null,
    val notes: String? = null,
)

/**
 * One seller's listing for one plant (an entry of a plant's `vendors` list). `shipsTo`, when present, replaces the
 * seller's own shipping area for this plant. `stock` is part of the agreed shape, but the data has no such field
 * today (it has `inStock`), so it is always null.
 */
@Serializable
data class VendorEntry(
    val name: String,
    val url: String? = null,
    val price: String? = null,
    val size: String? = null,
    val stock: String? = null,
    val shipsTo: List<String>? = null,
)

/** A plant, as plants.json describes it. A list or map the file leaves out (or sets to null) is empty. */
@Serializable
data class Plant(
    val id: String,
    val common: String,
    val sci: String,
    val category: String,
    val usda: String? = null,
    /** Feet: [smallest, largest]. */
    val height: List<Double> = emptyList(),
    val spread: List<Double> = emptyList(),
    val sun: List<String> = emptyList(),
    val moisture: List<String> = emptyList(),
    val bloomColor: List<String> = emptyList(),
    val wildlife: List<String> = emptyList(),
    val zones: List<String> = emptyList(),
    /** The plant's look in each month, January first: "dormant", "green", "bloom", "fall" and so on. */
    val months: List<String> = emptyList(),
    /** 1 is January. */
    val bloomMonths: List<Int> = emptyList(),
    val fallColor: String? = null,
    val evergreen: Boolean? = null,
    val notes: String? = null,
    /** Photo for each phase name. Empty when the plant has no photos yet. */
    val phasePhotos: Map<String, Photo> = emptyMap(),
    val maturity: Maturity? = null,
    val vendors: List<VendorEntry> = emptyList(),
)

/**
 * Where a seller ships: the lower 48 states (the file says "contiguous-48"), a list of states, or the lower 48
 * except some states (the file says { "allExcept": [...] }). Any other shape counts as shipping nowhere, as on the
 * website, where unknown shipping counts as no.
 */
sealed interface ShipsTo {
    data object Contiguous48 : ShipsTo
    data class States(val states: List<String>) : ShipsTo
    data class AllExcept(val states: List<String>) : ShipsTo

    companion object {
        /** The website's reading of a shipping area from its JSON value. */
        fun fromJson(e: JsonElement): ShipsTo = when {
            e is JsonPrimitive && e.isString -> if (e.content == "contiguous-48") Contiguous48 else States(emptyList())
            e is JsonArray -> States(e.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content })
            e is JsonObject -> {
                val list = e["allExcept"] as? JsonArray
                if (list != null) AllExcept(list.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content })
                else States(emptyList())
            }
            else -> States(emptyList())
        }
    }
}

/** Reads ShipsTo from any JSON shape; a null field never reaches it (the field is then null). */
object ShipsToSerializer : KSerializer<ShipsTo> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("garden.hereabouts.core.ShipsTo")

    override fun deserialize(decoder: Decoder): ShipsTo {
        val input = decoder as? JsonDecoder ?: error("ShipsTo can only be read from JSON")
        return ShipsTo.fromJson(input.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: ShipsTo) {
        val output = encoder as? JsonEncoder ?: error("ShipsTo can only be written as JSON")
        val e: JsonElement = when (value) {
            ShipsTo.Contiguous48 -> JsonPrimitive("contiguous-48")
            is ShipsTo.States -> JsonArray(value.states.map { JsonPrimitive(it) })
            is ShipsTo.AllExcept -> buildJsonObject { put("allExcept", JsonArray(value.states.map { JsonPrimitive(it) })) }
        }
        output.encodeJsonElement(e)
    }
}

/** A seller, as vendors.json describes it (keyed by `name`). */
@Serializable
data class Vendor(
    val name: String,
    val short: String? = null,
    val location: String? = null,
    /** [lat, lon] of the storefront, when it has one. */
    val coords: List<Double>? = null,
    val storefront: Boolean? = null,
    val affiliate: Boolean? = null,
    @Serializable(with = ShipsToSerializer::class)
    val shipsTo: ShipsTo? = null,
    /** Plant categories the seller never ships, like "tree". */
    val shipsExcept: List<String>? = null,
)

/**
 * Where a plant is native: the EPA level III ecoregion codes it is native to (`eco`), and the two-digit state codes
 * (state FIPS) where it is flagged as introduced (`intro`). Both are required, as in the iPhone app.
 */
@Serializable
data class PlantGeo(
    val eco: List<String>,
    val intro: List<String>,
)

/** version.json: which data the bundle holds. */
@Serializable
data class DataVersion(
    val schema: Int,
    val dataDate: String,
    val dataHash: String,
)

/** A county the visitor is in, with its ecoregion. The same fields as the website's Location object. */
@Serializable
data class Location(
    /** Five-digit county FIPS code, like "21111". */
    val countyFips: String,
    val countyName: String,
    /** Two-digit state FIPS code, like "21". */
    val stateFips: String,
    val stateName: String,
    /** Two-letter postal code, like "KY". */
    val stateUsps: String,
    /** EPA level III ecoregion code, like "71". */
    val ecoregion: String,
    val lat: Double? = null,
    val lon: Double? = null,
)

