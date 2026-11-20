// What the whole app shares: the plant and seller data, the location tables, the three things kept on the phone
// (the county, the saved plants, this week's ticked jobs), and the services that send events and fetch photos.
// Screens read it through its StateFlows.
package garden.hereabouts.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import garden.hereabouts.app.services.DataUpdater
import garden.hereabouts.app.services.EventSender
import garden.hereabouts.app.services.PhotoCache
import garden.hereabouts.app.services.ServiceSettings
import garden.hereabouts.core.AppData
import garden.hereabouts.core.DataVersion
import garden.hereabouts.core.EventValue
import garden.hereabouts.core.Location
import garden.hereabouts.core.Tables
import java.io.IOException
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The shared model. `kept` is what the phone had kept when the app started; changes are shown at once and written
 * to `store` in the background, in `writes`. Each change returns the write's Job, so a test can wait for it.
 *
 * It is built once the data has loaded (see [load]), so every screen gets the data, the tables and the kept values
 * ready to use. The activity's ViewModel, Launch, holds it.
 */
class AppModel(
    data: AppData,
    val tables: Tables,
    private val store: DataStore<Preferences>,
    kept: Preferences,
    /** The clock, which a test can set. */
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val writes: CoroutineScope = keptWrites,
    /** Sends events to the website's analytics. Null sends nothing. */
    val events: EventSender? = null,
    /** Photos of plants the app did not ship with. Null downloads nothing. */
    val photos: PhotoCache? = null,
) {

    private val _data = MutableStateFlow(data)
    /** The plant and seller data in use: the shipped set, or a newer one downloaded and checked. */
    val data: StateFlow<AppData> = _data.asStateFlow()

    private val _location = MutableStateFlow(LocationStore.load(kept, tables))
    /** The chosen county, or null until one is set. The app shows the first-open screen until then. */
    val location: StateFlow<Location?> = _location.asStateFlow()

    private val _saved = MutableStateFlow(SavedStore.load(kept))
    /** Saved plant ids, in the order they were saved. */
    val saved: StateFlow<List<String>> = _saved.asStateFlow()

    private val _ticks: MutableStateFlow<Map<String, Set<String>>>
    /** Ticked jobs: week id (yyyymmdd of the week's Saturday) to rule ids. */
    val ticks: StateFlow<Map<String, Set<String>>>

    /** The write that forgets ticks from earlier weeks, when there were any. */
    val cleanup: Job?

    init {
        val (current, dropped) = TicksStore.load(kept, now())
        _ticks = MutableStateFlow(current)
        ticks = _ticks.asStateFlow()
        cleanup = if (dropped) write { TicksStore.save(it, _ticks.value) } else null
    }

    /**
     * Writes in the background. Each write keeps the value the model holds when the write runs, not the one it held
     * when the write was asked for. So after two quick changes the later one is kept, whichever write runs last.
     */
    private fun write(change: (MutablePreferences) -> Unit): Job =
        writes.launch { store.edit { change(it) } }

    // Data

    /** Switches to newer plant and seller data, downloaded and checked while the app was open. */
    fun useData(newData: AppData) {
        _data.value = newData
    }

    // Events

    /**
     * Sends an event to the website's analytics, if it is on the website's list. It never waits for the network.
     * The Job is the queueing and the try to send, so a test can wait for it; null when nothing was queued.
     */
    fun track(event: String, props: Map<String, EventValue> = emptyMap()): Job? = events?.send(event, props)

    // Location

    /**
     * Sets the county. The position is rounded to two decimal places (about 1 km) here as well as where it is kept,
     * so what the app shows now matches what it will load next time.
     *
     * `method` is how the person chose it: the website's "zip", "city", "county" or "address", or "device" (the
     * phone's location). When given, the choice is sent as a location_set event with the county name, ecoregion and
     * state, never the ZIP code, the address or the position. A county loaded from the phone at launch passes no
     * method and sends nothing, as on the website.
     */
    fun setLocation(newLocation: Location?, method: String? = null): Job {
        _location.value = newLocation?.let(LocationStore::rounded)
        val written = write { LocationStore.save(it, _location.value) }
        if (method != null && newLocation != null) {
            track(
                "location_set",
                mapOf(
                    "method" to EventValue.Text(method),
                    "county" to EventValue.Text("${newLocation.countyName}, ${newLocation.stateUsps}"),
                    "ecoregion" to EventValue.Text(newLocation.ecoregion),
                    "state" to EventValue.Text(newLocation.stateUsps),
                ),
            )
        }
        return written
    }

    // Saved plants

    fun isSaved(plantId: String): Boolean = plantId in _saved.value

    /** Saves the plant at the end of the list, or removes it if it is already saved. */
    fun toggleSaved(plantId: String): Job {
        val list = _saved.value
        _saved.value = if (plantId in list) list - plantId else list + plantId
        return write { SavedStore.save(it, _saved.value) }
    }

    // Ticked jobs

    fun isTicked(ruleId: String, at: ZonedDateTime = now()): Boolean =
        _ticks.value[TicksStore.weekId(at)]?.contains(ruleId) == true

    /** Ticks the job off for the week `at` falls in, or unticks it. */
    fun toggleTick(ruleId: String, at: ZonedDateTime = now()): Job {
        val week = TicksStore.weekId(at)
        val rules = _ticks.value[week].orEmpty()
        val nextRules = if (ruleId in rules) rules - ruleId else rules + ruleId
        _ticks.value = if (nextRules.isEmpty()) _ticks.value - week else _ticks.value + (week to nextRules)
        return write { TicksStore.save(it, _ticks.value) }
    }

    companion object {
        /** Reads the plant data and the tables the app ships with, from its assets. Throws if they do not load. */
        fun loadShipped(context: Context): Pair<AppData, Tables> =
            AppData.load(assetReader(context, "data")) to Tables.load(assetReader(context, "tables"))

        private fun assetReader(context: Context, folder: String): (String) -> String =
            { name -> context.assets.open("$folder/$name").bufferedReader(Charsets.UTF_8).use { it.readText() } }

        /** The version of the data the app ships with, or null if its version.json does not read. */
        private fun shippedVersion(context: Context): DataVersion? = try {
            DataUpdater.readVersion(context.assets.open("data/version.json").use { it.readBytes() })
        } catch (e: IOException) {
            null
        }

        /**
         * The model for this launch: the newest data the phone holds (a downloaded set when one was kept and is still
         * newer than the shipped one, otherwise the shipped one), the shipped tables, what the phone has kept, and
         * the services `settings` describes, working in `scope`. It reads a few megabytes, so the caller runs it off
         * the main thread.
         */
        suspend fun load(
            context: Context,
            settings: ServiceSettings,
            scope: CoroutineScope,
            isOnline: () -> Boolean,
        ): AppModel {
            val app = context.applicationContext
            val downloaded = shippedVersion(app)?.let { DataUpdater.saved(settings.dataFolder, newerThan = it) }
            if (downloaded == null) {
                // A kept set that is no longer newer (the app was updated with newer data) or no longer reads is of
                // no further use.
                settings.dataFolder.deleteRecursively()
            }
            val data = downloaded ?: AppData.load(assetReader(app, "data"))
            val tables = Tables.load(assetReader(app, "tables"))
            val store = app.keptStore
            val events = EventSender(
                scope = scope,
                baseUrl = settings.site,
                apiKey = settings.apiKey,
                distinctId = EventSender.distinctId(store),
                queueFile = settings.queueFile,
                isOnline = isOnline,
            )
            val photos = PhotoCache(settings.site, app.assets, settings.photoFolder)
            return AppModel(data, tables, store, store.data.first(), events = events, photos = photos)
        }
    }
}
