// What the whole app shares: the plant and seller data, the location tables, and the three things kept on the phone
// (the county, the saved plants, this week's ticked jobs). Screens read it through its StateFlows.
package garden.hereabouts.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import garden.hereabouts.core.AppData
import garden.hereabouts.core.Location
import garden.hereabouts.core.Tables
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * The shared model. `kept` is what the phone had kept when the app started; changes are shown at once and written
 * to `store` in the background, in `writes`. Each change returns the write's Job, so a test can wait for it.
 *
 * It is built once the data has loaded (see [load]), so every screen gets the data, the tables and the kept values
 * ready to use. The activity's ViewModel, Launch, holds it.
 */
class AppModel(
    val data: AppData,
    val tables: Tables,
    private val store: DataStore<Preferences>,
    kept: Preferences,
    /** The clock, which a test can set. */
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val writes: CoroutineScope = keptWrites,
) {

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

    // Location

    /**
     * Sets the county. The position is rounded to two decimal places (about 1 km) here as well as where it is kept,
     * so what the app shows now matches what it will load next time.
     */
    fun setLocation(newLocation: Location?): Job {
        _location.value = newLocation?.let(LocationStore::rounded)
        return write { LocationStore.save(it, _location.value) }
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
        fun loadShipped(context: Context): Pair<AppData, Tables> {
            val assets = context.assets
            fun reader(folder: String): (String) -> String =
                { name -> assets.open("$folder/$name").bufferedReader(Charsets.UTF_8).use { it.readText() } }
            return AppData.load(reader("data")) to Tables.load(reader("tables"))
        }

        /**
         * The model for this launch, from the shipped data and what the phone has kept. It reads a few megabytes,
         * so the caller runs it off the main thread.
         */
        suspend fun load(context: Context): AppModel {
            val app = context.applicationContext
            val (data, tables) = loadShipped(app)
            val store = app.keptStore
            return AppModel(data, tables, store, store.data.first())
        }
    }
}
