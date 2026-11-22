// Where the app's network services talk to and where they keep their files, decided once for each launch of the
// screen. Only data comes over the network, never code.
package garden.hereabouts.app.services

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** The website: data from /app-data/v1/, events to /ingest/batch/, photos from /images/. */
const val WEBSITE = "https://hereabouts.garden"

class ServiceSettings(
    /** The address the services talk to, or null for no network at all. */
    val site: String?,
    /** The website's public PostHog key, which can only send events (tables/site.json in the assets). */
    val apiKey: String,
    /** The newest accepted plant data: version.json, plants.json, vendors.json and plant-regions.json. */
    val dataFolder: File,
    /** Events not yet sent. */
    val queueFile: File,
    /** Downloaded photos, by the same path as on the website (images/<name>). Android may clear these when space runs low. */
    val photoFolder: File,
) {
    companion object {
        /**
         * The settings for this launch: the website (or, in a Debug build under test, what the tests chose; see
         * [launchSite]), and the app's own folders. The manifest keeps all of them out of backups.
         */
        fun forLaunch(context: Context): ServiceSettings {
            val app = context.applicationContext
            return ServiceSettings(
                site = launchSite(),
                apiKey = readApiKey(app),
                dataFolder = File(app.filesDir, "data"),
                queueFile = File(app.filesDir, "events.json"),
                photoFolder = File(app.cacheDir, "photos"),
            )
        }

        /** posthogKey from tables/site.json, or "" when it cannot be read (events then wait and are never posted). */
        private fun readApiKey(context: Context): String = try {
            val text = context.assets.open("tables/site.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val key = (Json.parseToJsonElement(text) as? JsonObject)?.get("posthogKey") as? JsonPrimitive
            key?.takeIf { it.isString }?.content.orEmpty()
        } catch (e: Exception) {
            ""
        }
    }
}
