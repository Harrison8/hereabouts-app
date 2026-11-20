// What the app keeps on the phone, in one DataStore file, under the same keys and with the same meanings as the iPhone
// app: the chosen county, the saved plants and this week's ticked garden jobs. Each is kept as JSON text. None of it
// leaves the phone as kept: events carry only the county's name, never the position, and the manifest keeps it out of
// Android's backup and out of a copy to a new phone. The same file also holds the analytics id and the time of the
// last data check.
package garden.hereabouts.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import garden.hereabouts.core.Location
import garden.hereabouts.core.LocationLookup
import garden.hereabouts.core.LocationResult
import garden.hereabouts.core.Reminders
import garden.hereabouts.core.Tables
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * The app's one DataStore file on the phone. A file that no longer reads (cut short by a crash, say) is replaced with
 * an empty one, so the app starts as on its first open instead of failing at every launch.
 */
val Context.keptStore: DataStore<Preferences> by preferencesDataStore(
    name = "hereabouts",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Where the writes to the kept file run. It lasts as long as the app's process, so a change made just before the
 * screen closes is still written.
 */
val keptWrites: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Reads JSON that may not be JSON: null when it does not parse. */
private fun parse(text: String?): JsonElement? = text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }

/**
 * Keeps the chosen county the way the website does: only the county code and the position, rounded to two decimal
 * places (about 1 km) so it cannot point at a house. The county's name, state and ecoregion are looked up again from
 * the shipped tables on every load, so a data update reaches a county chosen earlier.
 */
object LocationStore {
    val key = stringPreferencesKey("hereabouts.location")

    /**
     * The kept location, looked up again in `tables`. Null when none is set, when what is kept no longer reads, or
     * when the county is no longer one the tables serve.
     */
    fun load(kept: Preferences, tables: Tables): Location? {
        val o = parse(kept[key]) as? JsonObject ?: return null
        val fips = (o["countyFips"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (fips.length != 5 || !fips.all { it in '0'..'9' }) return null
        fun number(name: String): Double? = (o[name] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        val found = LocationLookup.resolve(fips, number("lat"), number("lon"), tables)
        return (found as? LocationResult.Found)?.location
    }

    /** Keeps the county code and the rounded position of `location`, or forgets it when null. */
    fun save(into: MutablePreferences, location: Location?) {
        if (location == null) {
            into.remove(key)
            return
        }
        val r = rounded(location)
        into[key] = buildJsonObject {
            put("countyFips", r.countyFips)
            // Left out when there is no position, as the iPhone app does.
            r.lat?.let { put("lat", it) }
            r.lon?.let { put("lon", it) }
        }.toString()
    }

    /** `location` with its position rounded the same way it is kept. */
    fun rounded(location: Location): Location = location.copy(
        lat = location.lat?.let(LocationLookup::roundPosition),
        lon = location.lon?.let(LocationLookup::roundPosition),
    )
}

/** Keeps the saved plants, as plant ids in the order they were saved. */
object SavedStore {
    val key = stringPreferencesKey("hereabouts.saved")

    fun load(kept: Preferences): List<String> {
        val list = parse(kept[key]) as? JsonArray ?: return emptyList()
        return list.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }

    fun save(into: MutablePreferences, ids: List<String>) {
        into[key] = buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }.toString()
    }
}

/** Keeps whether the Saturday reminders are on, under the iPhone app's key. They are on until turned off. */
object RemindersStore {
    val key = booleanPreferencesKey("hereabouts.reminders")

    fun load(kept: Preferences): Boolean = kept[key] ?: true

    fun save(into: MutablePreferences, on: Boolean) {
        into[key] = on
    }
}

/**
 * Keeps which garden jobs were ticked off, week by week. A week runs Saturday to Friday and is named by its Saturday
 * as yyyymmdd (the same form the reminders use for their ids). Ticks from weeks before the current one are dropped
 * when they are read, so each week starts with nothing ticked.
 */
object TicksStore {
    val key = stringPreferencesKey("hereabouts.ticks")

    /** The week `now` falls in, named by the Saturday on or before it, in the phone's own time zone. */
    fun weekId(now: ZonedDateTime): String =
        Reminders.id(now.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY))).toString()

    /**
     * The ticks by week id, without any week before the one `now` falls in, and whether anything was dropped (the
     * caller then keeps the cleaned set, so what was dropped is forgotten, not only hidden). What does not read at all
     * counts as no ticks and is left as it is, as the iPhone app does.
     */
    fun load(kept: Preferences, now: ZonedDateTime): Pair<Map<String, Set<String>>, Boolean> {
        val stored = parse(kept[key]) as? JsonObject ?: return emptyMap<String, Set<String>>() to false
        val current = weekId(now)
        val result = mutableMapOf<String, Set<String>>()
        for ((week, value) in stored) {
            val rules = (value as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.toSet()
                .orEmpty()
            // Eight digits compare as text in date order.
            if (week.length == 8 && week.all { it in '0'..'9' } && week >= current && rules.isNotEmpty()) {
                result[week] = rules
            }
        }
        return result to (result.size != stored.size)
    }

    fun save(into: MutablePreferences, ticks: Map<String, Set<String>>) {
        into[key] = buildJsonObject {
            for ((week, rules) in ticks.toSortedMap()) {
                if (rules.isEmpty()) continue
                put(week, buildJsonArray { rules.sorted().forEach { add(JsonPrimitive(it)) } })
            }
        }.toString()
    }
}
