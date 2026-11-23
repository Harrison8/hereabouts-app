// What the whole app shares: the plant and seller data, the location tables, the care rules, the four things kept on
// the phone (the county, the saved plants, this week's ticked jobs and whether the Saturday reminders are on), what the
// phone says about the app's notifications, and the services that send events, fetch photos and arm the reminders.
// Screens read it through its StateFlows.
package garden.hereabouts.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import garden.hereabouts.app.plants.PlantsChoices
import garden.hereabouts.app.reminders.NotificationPermission
import garden.hereabouts.app.reminders.ReminderPlan
import garden.hereabouts.app.reminders.ReminderScheduler
import garden.hereabouts.app.services.DataUpdater
import garden.hereabouts.app.services.EventSender
import garden.hereabouts.app.services.PhotoCache
import garden.hereabouts.app.services.ServiceSettings
import garden.hereabouts.app.services.clockUnderTest
import garden.hereabouts.core.Anchors
import garden.hereabouts.core.AppData
import garden.hereabouts.core.Care
import garden.hereabouts.core.CareRule
import garden.hereabouts.core.DataVersion
import garden.hereabouts.core.EventValue
import garden.hereabouts.core.Location
import garden.hereabouts.core.Tables
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The shared model. `kept` is what the phone had kept when the app started; changes are shown at once and written
 * to `store` in the background, in `writes`. Each change returns the write's Job, so a test can wait for it.
 *
 * It is built once the data has loaded (see [load]), so every screen gets the data, the tables and the kept values
 * ready to use. AppProcess holds it for as long as the app's process lasts.
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
    /** The care rules, each county's timing dates and the rules' sources, for This week and the reminders. */
    val care: CareTables = CareTables.empty,
    /** Arms the Saturday reminders and asks the phone about notifications. Null arms nothing and asks nothing. */
    private val scheduler: ReminderScheduler? = null,
    /** What the phone said about the app's notifications when the app started. */
    startPermission: NotificationPermission? = null,
    /** Where the questions to the phone and the arming that follows them run. */
    private val work: CoroutineScope = keptWrites,
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

    private val _remindersOn = MutableStateFlow(RemindersStore.load(kept))
    /** Whether the Saturday reminders are on: Settings' switch. On until turned off. */
    val remindersOn: StateFlow<Boolean> = _remindersOn.asStateFlow()

    private val _permission = MutableStateFlow(startPermission)
    /** What the phone says about the app's notifications, read again each time the app comes to the front. */
    val permission: StateFlow<NotificationPermission?> = _permission.asStateFlow()

    private val _today = MutableStateFlow(now())
    /** The time This week is worked out from: the clock when the app last came to the front. */
    val today: StateFlow<ZonedDateTime> = _today.asStateFlow()

    private val _gardenRequest = MutableStateFlow(false)
    /** True after a reminder was tapped, until the Garden tab has shown This week. */
    val gardenRequest: StateFlow<Boolean> = _gardenRequest.asStateFlow()

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
        writes.launch {
            try {
                store.edit { change(it) }
            } catch (e: IOException) {
                // A full phone simply does not save.
            }
        }

    // Data

    /** Switches to newer plant and seller data, downloaded and checked while the app was open. */
    fun useData(newData: AppData) {
        _data.value = newData
        reschedule()
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
        reschedule()
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
        val written = write { SavedStore.save(it, _saved.value) }
        reschedule()
        return written
    }

    /**
     * What every save button does, in the list, on the plant page and in the Garden tab's Remove: saves or unsaves
     * the plant and sends plant_saved or plant_unsaved, as the website does. The first time a plant is saved while a
     * county is set, the phone asks whether the app may send notifications, and the answer is sent as
     * notifications_answer.
     */
    fun pressSave(plantId: String) {
        val wasSaved = isSaved(plantId)
        toggleSaved(plantId)
        track(if (wasSaved) "plant_unsaved" else "plant_saved")
        val scheduler = scheduler ?: return
        if (wasSaved || _location.value == null) return
        work.launch {
            val granted = scheduler.askIfNotAsked() ?: return@launch
            track("notifications_answer", mapOf("granted" to EventValue.Bool(granted)))
            readPermission()
            reschedule()
        }
    }

    /**
     * The Garden tab's Remove: unsaves the plant as the save button does, and does nothing when it is not saved, so
     * a swipe and the screen reader's Remove both firing, or a Remove that fires twice, never saves it again.
     */
    fun removeSaved(plantId: String) {
        if (!isSaved(plantId)) return
        pressSave(plantId)
    }

    // The Plants tab

    /** The Plants tab's filters, sort and search, kept here so they last when another tab is open. */
    val plantsChoices = PlantsChoices()

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

    // Reminders

    /** Whether a Saturday reminder will come: the switch is on and the phone has not refused the app's notifications. */
    val remindersWillCome: Boolean get() = willCome(_remindersOn.value, _permission.value)

    /**
     * Turns the Saturday reminders on or off. Turning them on after the phone refused the app's notifications asks
     * the phone once more, since the switch is the only way back.
     */
    fun setRemindersOn(on: Boolean): Job {
        _remindersOn.value = on
        val written = write { RemindersStore.save(it, _remindersOn.value) }
        reschedule()
        val scheduler = scheduler
        if (on && scheduler != null && _permission.value == NotificationPermission.Denied) {
            work.launch {
                scheduler.askAgain() ?: return@launch
                readPermission()
                reschedule()
            }
        }
        return written
    }

    /** What the reminders are planned from now. */
    fun reminderPlan(): ReminderPlan = ReminderPlan(
        care = care,
        plants = _data.value.plants,
        countyFips = _location.value?.countyFips,
        saved = _saved.value,
        on = _remindersOn.value,
        permission = _permission.value,
    )

    /** Arms the next reminder from what is saved now, or cancels the one waiting when none is due. */
    fun reschedule() {
        val scheduler = scheduler ?: return
        scheduler.arm(reminderPlan().weekly(now()).firstOrNull())
    }

    private suspend fun readPermission() {
        val scheduler = scheduler ?: return
        _permission.value = scheduler.permission()
    }

    /**
     * The app came to the front (and at launch): take the phone's time again, so This week moves on to a new week,
     * read what the phone says about notifications again (the person may have changed it in the phone's settings),
     * and arm the next reminder again.
     */
    fun cameToFront(): Job {
        _today.value = now()
        return work.launch {
            readPermission()
            reschedule()
        }
    }

    /** A reminder was tapped: show This week at the top of the Garden tab, and send this_week_opened. */
    fun openedFromNotification() {
        _gardenRequest.value = true
        track("this_week_opened", mapOf("from" to EventValue.Text("notification")))
    }

    /** The Garden tab has shown This week for the tapped reminder. */
    fun gardenShown() {
        _gardenRequest.value = false
    }

    companion object {
        /** Whether a Saturday reminder will come, for the switch `on` and the phone's answer `permission`. */
        fun willCome(on: Boolean, permission: NotificationPermission?): Boolean =
            on && (permission == NotificationPermission.Allowed || permission == NotificationPermission.NotAsked)

        /** Reads the plant data and the tables the app ships with, from its assets. Throws if they do not load. */
        fun loadShipped(context: Context): Pair<AppData, Tables> =
            AppData.load(assetReader(context, "data")) to Tables.load(assetReader(context, "tables"))

        internal fun assetReader(context: Context, folder: String): (String) -> String =
            { name -> context.assets.open("$folder/$name").bufferedReader(Charsets.UTF_8).use { it.readText() } }

        /** The version of the data the app ships with, or null if its version.json does not read. */
        private fun shippedVersion(context: Context): DataVersion? = try {
            DataUpdater.readVersion(context.assets.open("data/version.json").use { it.readBytes() })
        } catch (e: IOException) {
            null
        }

        /** The set downloaded into `dataFolder`, when one was kept and is still newer than the shipped one. */
        private fun downloaded(context: Context, dataFolder: File): AppData? =
            shippedVersion(context)?.let { DataUpdater.saved(dataFolder, newerThan = it) }

        /**
         * The newest plant data the phone holds: a downloaded set when one was kept and is still newer than the
         * shipped one, otherwise the shipped one. It reads a few megabytes, so the caller runs it off the main thread.
         */
        fun newestData(context: Context, dataFolder: File): AppData {
            val app = context.applicationContext
            return downloaded(app, dataFolder) ?: AppData.load(assetReader(app, "data"))
        }

        /**
         * The model for this launch: the newest data the phone holds, the shipped tables and care rules, what the
         * phone has kept, and the services `settings` describes, working in `scope`. It reads a few megabytes, so the
         * caller runs it off the main thread.
         */
        suspend fun load(
            context: Context,
            settings: ServiceSettings,
            scope: CoroutineScope,
            isOnline: () -> Boolean,
        ): AppModel {
            val app = context.applicationContext
            // A download cut short, or an earlier set, may have left folders behind.
            DataUpdater.cleanUp(settings.dataFolder)
            val downloaded = downloaded(app, settings.dataFolder)
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
            val scheduler = ReminderScheduler(app)
            return AppModel(
                data,
                tables,
                store,
                store.data.first(),
                now = { clockUnderTest() ?: ZonedDateTime.now() },
                events = events,
                photos = photos,
                care = CareTables.load(app),
                scheduler = scheduler,
                startPermission = scheduler.permission(),
                work = scope,
            )
        }
    }
}

/**
 * The care rules from tables/care-rules.json, each county's timing dates from tables/care-anchors.json, and the
 * sources each rule's facts come from, by rule id.
 */
class CareTables(
    val rules: List<CareRule>,
    val anchors: Map<String, Anchors>,
    val sources: Map<String, List<Source>>,
) {
    /** Where a rule's facts come from: a title and the address it opens. */
    data class Source(val title: String, val url: String)

    companion object {
        val empty = CareTables(emptyList(), emptyMap(), emptyMap())

        /** Reads the care tables the app ships with, from its assets. Throws if they do not load. */
        fun load(context: Context): CareTables {
            val read = AppModel.assetReader(context.applicationContext, "tables")
            val rulesText = read("care-rules.json")
            return CareTables(Care.rules(rulesText), Care.anchorsByCounty(read("care-anchors.json")), sources(rulesText))
        }

        /** Each rule's sources, in the file's order. A source without a title or an address is left out. */
        private fun sources(rulesText: String): Map<String, List<Source>> {
            val rules = (Json.parseToJsonElement(rulesText) as? JsonObject)?.get("rules") as? JsonArray ?: return emptyMap()
            fun text(o: JsonObject, name: String): String? =
                (o[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
            val result = LinkedHashMap<String, List<Source>>()
            for (rule in rules) {
                val o = rule as? JsonObject ?: continue
                val id = text(o, "id") ?: continue
                result[id] = (o["sources"] as? JsonArray).orEmpty().mapNotNull { s ->
                    val so = s as? JsonObject ?: return@mapNotNull null
                    val title = text(so, "title") ?: return@mapNotNull null
                    val url = text(so, "url") ?: return@mapNotNull null
                    Source(title, url)
                }
            }
            return result
        }
    }
}
