// Sends the app's events to the website's analytics, the way the website does: only the events and properties on the
// website's own list (Analytics.clean), each once per launch where the website sends it once per visit, and every
// event marked platform "android". Events wait in a file on the phone until there is signal, at most 500 of them (the
// oldest go first). They are posted in PostHog's batch form to the website's /ingest/batch/, which passes them on to
// PostHog, with the website's public key, a random id made once on this phone, and no person profile. The same as
// the iPhone app's EventSender.
package garden.hereabouts.app.services

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import garden.hereabouts.core.Analytics
import garden.hereabouts.core.EventValue
import garden.hereabouts.core.EventValueSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * `baseUrl` null sends nothing; events still wait in `queueFile`. The work runs in `scope`, off the main thread, and
 * reads `isOnline` before each post.
 */
class EventSender(
    private val scope: CoroutineScope,
    baseUrl: String?,
    private val apiKey: String,
    private val distinctId: String,
    private val queueFile: File,
    private val isOnline: () -> Boolean,
) {
    /** One event as PostHog's batch endpoint takes it, already written as JSON. `uuid` lets PostHog drop a copy. */
    class QueuedEvent(val uuid: String, val event: String, val json: JsonObject)

    private val endpoint = baseUrl?.let { Http.address(it, "/ingest/batch/") }

    private val lock = Any()
    private val events = ArrayList(readQueue(queueFile))

    /** The once-per-launch events already queued this launch, by event, plant and seller. */
    private val sentThisLaunch = HashSet<String>()

    /** Counts changes to `events`, so a slow save never writes over a newer one. */
    private var changes = 0L
    private var savedChanges = 0L
    private val saving = Mutex()

    /** Set by each send and flush; the next pass of the poster clears it and posts. */
    private val pending = AtomicBoolean(false)
    private val posting = Mutex()

    /** The events waiting to be sent, oldest first. */
    val queued: List<QueuedEvent> get() = synchronized(lock) { events.toList() }

    /**
     * Queues the event, if the website's list allows it, and sends the queue when there is signal. Never waits for
     * the network. The Job is the saving and the try to send, so a test can wait for it; null when nothing was
     * queued: the event is not on the list, was already sent this launch, or holds a number JSON cannot hold (NaN or
     * an infinity), which drops that one event and nothing else.
     */
    fun send(event: String, props: Map<String, EventValue>): Job? {
        val cleaned = Analytics.clean(event, props) ?: return null
        val onceKey = if (event in Analytics.onceKeys) {
            listOf(event, text(cleaned["plant"]), text(cleaned["vendor"])).joinToString(".")
        } else {
            null
        }
        val properties = cleaned + mapOf(
            "platform" to EventValue.Text("android"),
            "distinct_id" to EventValue.Text(distinctId),
            "\$process_person_profile" to EventValue.Bool(false),
        )
        val item = try {
            encode(event, properties)
        } catch (e: SerializationException) {
            return null
        }
        synchronized(lock) {
            if (onceKey != null && !sentThisLaunch.add(onceKey)) return null
            events.add(item)
            if (events.size > MAX_QUEUED) events.subList(0, events.size - MAX_QUEUED).clear()
            changes++
        }
        return scope.launch(Dispatchers.IO) {
            save()
            pending.set(true)
            pass()
        }
    }

    /**
     * Posts the waiting events when there is signal. When the Job ends, a post has been tried after this call (or
     * there was no signal). Events the server took, or refused as malformed, leave the queue; on no answer or a
     * server error they wait for the next try.
     */
    fun flush(): Job = scope.launch(Dispatchers.IO) {
        pending.set(true)
        pass()
    }

    /** One pass of the poster. Passes run one at a time; a pass with nothing new asked of it does nothing. */
    private suspend fun pass() {
        posting.withLock {
            if (pending.getAndSet(false)) postAll()
        }
    }

    private suspend fun postAll() {
        val endpoint = endpoint ?: return
        // Without the website's key PostHog would refuse every event, so they wait for a build that has it.
        if (apiKey.isEmpty()) return
        while (isOnline()) {
            val batch = queued
            if (batch.isEmpty()) return
            val body = buildJsonObject {
                put("api_key", apiKey)
                put("batch", JsonArray(batch.map { it.json }))
            }.toString().toByteArray(Charsets.UTF_8)
            val status = Http.postJson(endpoint, body) ?: return
            // 408 and 429 mean "try later". Any other 4xx means PostHog will never take these events, so retrying
            // them would only block the ones behind.
            val refused = status in 400..499 && status != 408 && status != 429
            if (status !in 200..299 && !refused) return
            // Events queued while the batch was on its way stay; only the ones in the batch leave.
            val sent = batch.mapTo(HashSet()) { it.uuid }
            synchronized(lock) {
                events.removeAll { it.uuid in sent }
                changes++
            }
            save()
        }
    }

    private fun encode(event: String, properties: Map<String, EventValue>): QueuedEvent {
        val uuid = UUID.randomUUID().toString()
        val json = buildJsonObject {
            put("uuid", uuid)
            put("event", event)
            put("properties", JsonObject(properties.mapValues { Json.encodeToJsonElement(EventValueSerializer, it.value) }))
            put("timestamp", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString())
        }
        return QueuedEvent(uuid, event, json)
    }

    /**
     * Writes the queue as it is now, written to a new file first and then moved into place, so the file always holds
     * a whole queue. If writing fails, the events still go out from memory this launch.
     */
    private suspend fun save() {
        saving.withLock {
            val (snapshot, version) = synchronized(lock) { events.toList() to changes }
            if (version <= savedChanges) return
            try {
                queueFile.parentFile?.mkdirs()
                val fresh = File(queueFile.parentFile, "${queueFile.name}.new")
                fresh.writeText(JsonArray(snapshot.map { it.json }).toString(), Charsets.UTF_8)
                if (!fresh.renameTo(queueFile)) throw IOException("could not move the queue into place")
                savedChanges = version
            } catch (e: IOException) {
                // Tried again at the next change.
            }
        }
    }

    companion object {
        /** The most events kept waiting. When more arrive, the oldest are dropped. */
        const val MAX_QUEUED = 500

        /** Where the random analytics id is kept. */
        val distinctIdKey = stringPreferencesKey("hereabouts.analyticsId")

        /** The random analytics id for this phone, made the first time it is needed and kept in `store`. */
        suspend fun distinctId(store: DataStore<Preferences>): String {
            store.data.first()[distinctIdKey]?.takeIf { it.isNotEmpty() }?.let { return it }
            var id = UUID.randomUUID().toString()
            try {
                store.edit { kept ->
                    val existing = kept[distinctIdKey]
                    if (existing.isNullOrEmpty()) kept[distinctIdKey] = id else id = existing
                }
            } catch (e: IOException) {
                // A full phone simply does not save the id; a new one is made next time.
            }
            return id
        }

        /** The events an earlier launch left waiting, newest 500 at most. Empty when there are none or they do not read. */
        private fun readQueue(file: File): List<QueuedEvent> = try {
            val array = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonArray
            array.orEmpty().mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val uuid = (o["uuid"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                val event = (o["event"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                QueuedEvent(uuid, event, o)
            }.takeLast(MAX_QUEUED)
        } catch (e: Exception) {
            emptyList()
        }

        /** A property as the website's once-per-visit key writes it: the text, or nothing when it is missing. */
        private fun text(value: EventValue?): String = when (value) {
            is EventValue.Text -> value.value
            is EventValue.Number -> {
                val n = value.value
                if (n == Math.rint(n) && abs(n) < 1e15) n.toLong().toString() else n.toString()
            }
            is EventValue.Bool -> value.value.toString()
            null -> ""
        }
    }
}
