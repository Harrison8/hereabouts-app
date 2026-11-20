// Sends the app's events to the website's analytics. Not built yet: nothing is queued or sent.
package garden.hereabouts.app.services

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import garden.hereabouts.core.EventValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID

class EventSender(
    private val scope: CoroutineScope,
    baseUrl: String?,
    private val apiKey: String,
    private val distinctId: String,
    private val queueFile: File,
    private val isOnline: () -> Boolean,
) {
    /** One event as PostHog's batch endpoint takes it, already written as JSON. */
    class QueuedEvent(val uuid: String, val event: String, val json: JsonObject)

    /** The events waiting to be sent, oldest first. */
    val queued: List<QueuedEvent> get() = emptyList()

    fun send(event: String, props: Map<String, EventValue>): Job? = null

    fun flush(): Job = scope.launch { }

    companion object {
        /** The most events kept waiting. When more arrive, the oldest are dropped. */
        const val MAX_QUEUED = 500

        /** Where the random analytics id is kept. */
        val distinctIdKey = stringPreferencesKey("hereabouts.analyticsId")

        /** The random analytics id for this phone, made the first time it is needed and kept in `store`. */
        suspend fun distinctId(store: DataStore<Preferences>): String {
            store.data.first()[distinctIdKey]?.takeIf { it.isNotEmpty() }?.let { return it }
            var id = UUID.randomUUID().toString()
            store.edit { kept ->
                val existing = kept[distinctIdKey]
                if (existing.isNullOrEmpty()) kept[distinctIdKey] = id else id = existing
            }
            return id
        }
    }
}
