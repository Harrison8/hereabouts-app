// Reads the bundle's data/ folder: plants.json, vendors.json, plant-regions.json and version.json.
package garden.hereabouts.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The JSON reader every bundle file goes through. Unknown fields are ignored, so new fields in the data never break
 * an older app; a null where the type has a default (a list, say) takes the default.
 */
internal val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** Everything the app knows about plants and sellers, read once from the bundle. */
data class AppData(
    val plants: List<Plant>,
    /** Sellers by name. vendors.json is an object keyed by seller name, not a list. */
    val vendors: Map<String, Vendor>,
    /** Where each plant is native, by plant id. */
    val geo: Map<String, PlantGeo>,
    /** The ecoregion codes the site serves fully (SERVED_ECOREGIONS on the website). */
    val served: List<String>,
    val version: DataVersion,
) {
    /** plant-regions.json: { schema, plants: { id: { eco, intro } }, served: [code] }. */
    @Serializable
    private data class Regions(
        val plants: Map<String, PlantGeo>,
        val served: List<String>,
    )

    companion object {
        /** Reads the four files in `dataDir` (the bundle's data/ folder). Throws if a file is missing or does not decode. */
        fun load(dataDir: File): AppData = load { name -> dataDir.resolve(name).readText(Charsets.UTF_8) }

        /**
         * Reads the four files through `read`, which gives a file's text by its name in the bundle's data/ folder.
         * The Android app reads them this way from its assets, which are not files. Throws if a file is missing or
         * does not decode.
         */
        fun load(read: (name: String) -> String): AppData {
            val version = json.decodeFromString(DataVersion.serializer(), read("version.json"))
            return decode(read("plants.json"), read("vendors.json"), read("plant-regions.json"), version)
        }

        /** Decodes plants.json, vendors.json and plant-regions.json. Throws if a file does not decode. */
        internal fun decode(plants: String, vendors: String, regions: String, version: DataVersion): AppData {
            val r = json.decodeFromString(Regions.serializer(), regions)
            return AppData(
                plants = json.decodeFromString(ListSerializer(Plant.serializer()), plants),
                vendors = json.decodeFromString(MapSerializer(String.serializer(), Vendor.serializer()), vendors),
                geo = r.plants,
                served = r.served,
                version = version,
            )
        }
    }
}
