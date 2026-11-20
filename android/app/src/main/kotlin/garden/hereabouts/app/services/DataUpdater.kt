// The daily check for newer plant and seller data. Not built yet: it never checks.
package garden.hereabouts.app.services

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import garden.hereabouts.app.AppModel
import garden.hereabouts.core.AppData
import garden.hereabouts.core.DataVersion
import java.io.File
import java.time.Instant

class DataUpdater(
    private val model: AppModel,
    private val site: String?,
    private val dataFolder: File,
    private val store: DataStore<Preferences>,
    private val isOnline: () -> Boolean,
    private val now: () -> Instant = { Instant.now() },
) {
    suspend fun checkIfDue() {}

    companion object {
        /** Where the time of the last finished check is kept, as milliseconds since 1970. */
        val lastCheckKey = longPreferencesKey("hereabouts.dataCheck")

        /** The four files, in the order they are downloaded. */
        val FILE_NAMES = listOf("version.json", "plants.json", "vendors.json", "plant-regions.json")

        fun saved(folder: File, newerThan: DataVersion): AppData? = null
    }
}
