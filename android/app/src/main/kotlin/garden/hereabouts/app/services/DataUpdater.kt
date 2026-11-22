// The daily check for newer plant and seller data. At launch, whenever the app comes back to the front and whenever
// signal comes back, if there is signal and the last check was a day ago or more, it asks the website for
// version.json; when that names data the app does not have, it downloads plants.json, vendors.json and
// plant-regions.json too. The set is used only if DataUpdate.accept passes it (right schema, not older, the
// fingerprint matches, every file reads). Then the app switches to it at once and keeps it in its own files for the
// next launch. Anything else keeps the data the app has. Only data is downloaded, never code. The same as the iPhone
// app's DataUpdater.
package garden.hereabouts.app.services

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import garden.hereabouts.app.AppModel
import garden.hereabouts.core.AppData
import garden.hereabouts.core.DataUpdate
import garden.hereabouts.core.DataVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.URL
import java.time.Instant
import java.util.UUID

/** `site` null never checks. `dataFolder` is where an accepted set is kept; `store` keeps the time of the last check. */
class DataUpdater(
    private val model: AppModel,
    private val site: String?,
    private val dataFolder: File,
    private val store: DataStore<Preferences>,
    private val isOnline: () -> Boolean,
    private val now: () -> Instant = { Instant.now() },
) {
    private val checking = Mutex()

    /**
     * Checks for newer data if a check is due and there is signal. A check that gets no answer (no signal on the way,
     * a timeout) is not recorded, so the next launch or return to the app tries again. A check already running makes
     * this return at once.
     */
    suspend fun checkIfDue() {
        val site = site ?: return
        if (!isOnline()) return
        if (!checking.tryLock()) return
        try {
            val last = store.data.first()[lastCheckKey]?.let { Instant.ofEpochMilli(it) }
            if (!DataUpdate.shouldCheck(last, now())) return
            val folder = Http.address(site, "/app-data/v1/") ?: return
            withContext(Dispatchers.IO) { check(folder) }
        } finally {
            checking.unlock()
        }
    }

    private suspend fun check(folder: URL) {
        val files = LinkedHashMap<String, ByteArray>()
        for (name in FILE_NAMES) {
            val answer = Http.get(URL(folder, name)) ?: return
            if (answer.status != 200) return finished()
            files[name] = answer.body
            // Stop after version.json when it names the data the app already has or a schema it cannot read.
            if (name == "version.json") {
                val next = readVersion(answer.body)
                if (next == null || next.schema != 1 || next.dataHash == model.data.value.version.dataHash) return finished()
            }
        }
        finished()
        val accepted = DataUpdate.accept(
            version = files.getValue("version.json"),
            plants = files.getValue("plants.json"),
            vendors = files.getValue("vendors.json"),
            regions = files.getValue("plant-regions.json"),
            current = model.data.value.version,
        ) ?: return
        // Kept for the next launch. If writing fails, the set is still used until then.
        try {
            keep(files, dataFolder)
        } catch (e: IOException) {
            // Used this launch only.
        }
        model.useData(accepted)
    }

    private suspend fun finished() {
        store.edit { it[lastCheckKey] = now().toEpochMilli() }
    }

    companion object {
        /** Where the time of the last finished check is kept, as milliseconds since 1970. */
        val lastCheckKey = longPreferencesKey("hereabouts.dataCheck")

        /** The four files, in the order they are downloaded. */
        val FILE_NAMES = listOf("version.json", "plants.json", "vendors.json", "plant-regions.json")

        /** version.json's schema, date and fingerprint, or null when it does not read as one. */
        fun readVersion(bytes: ByteArray): DataVersion? = try {
            val o = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            DataVersion(
                schema = o.getValue("schema").jsonPrimitive.int,
                dataDate = o.getValue("dataDate").jsonPrimitive.content,
                dataHash = o.getValue("dataHash").jsonPrimitive.content,
            )
        } catch (e: Exception) {
            null
        }

        /** The file in the data folder that names the set in use. */
        private const val POINTER = "current"

        /**
         * Keeps the four files in `folder`. Each set gets its own new folder inside it, and a file named "current"
         * names the set in use. The new set is written whole, then "current" is replaced in one step by renaming a
         * new pointer file over it, and only then are older sets deleted. So whenever the app stops, "current" names
         * a whole set: the new one or the one before it, never a mix and never nothing.
         */
        fun keep(
            files: Map<String, ByteArray>,
            folder: File,
            rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
        ) {
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("could not make $folder")
            val name = "set-${UUID.randomUUID()}"
            val fresh = File(folder, name)
            val pointer = File(folder, "$POINTER.tmp")
            if (!fresh.mkdir()) throw IOException("could not make $fresh")
            try {
                for (file in FILE_NAMES) {
                    val body = files[file] ?: throw IOException("no $file")
                    File(fresh, file).writeBytes(body)
                }
                pointer.writeText(name, Charsets.UTF_8)
                if (!rename(pointer, File(folder, POINTER))) throw IOException("could not point at the new set")
            } catch (e: IOException) {
                fresh.deleteRecursively()
                pointer.delete()
                throw e
            }
            folder.listFiles()?.filter { it.name.startsWith("set-") && it.name != name }?.forEach { it.deleteRecursively() }
        }

        /** The folder of the set "current" names in `folder`, or null when there is none. */
        private fun currentSet(folder: File): File? {
            val name = try {
                File(folder, POINTER).readText(Charsets.UTF_8).trim()
            } catch (e: IOException) {
                return null
            }
            if (!name.startsWith("set-") || File(name).name != name) return null
            return File(folder, name).takeIf { it.isDirectory }
        }

        /**
         * Removes what an update cut short left behind: in `folder`, everything but "current" and the set it names;
         * next to `folder`, any "data-" folder an earlier version of the app was writing when it stopped.
         */
        fun cleanUp(folder: File) {
            val current = currentSet(folder)?.name
            folder.listFiles()?.filter { it.name != POINTER && it.name != current }?.forEach { it.deleteRecursively() }
            folder.absoluteFile.parentFile?.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("${folder.name}-") }
                ?.forEach { it.deleteRecursively() }
        }

        /**
         * The data kept by an earlier update, when it is still newer than `newerThan` (the data the app ships with)
         * and passes the same checks as a download. Null otherwise, for example after an app update that ships newer
         * data.
         */
        fun saved(folder: File, newerThan: DataVersion): AppData? {
            val set = currentSet(folder) ?: return null
            fun read(name: String): ByteArray? = try {
                File(set, name).readBytes()
            } catch (e: IOException) {
                null
            }
            return DataUpdate.accept(
                version = read("version.json") ?: return null,
                plants = read("plants.json") ?: return null,
                vendors = read("vendors.json") ?: return null,
                regions = read("plant-regions.json") ?: return null,
                current = newerThan,
            )
        }
    }
}
