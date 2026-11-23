// The network services on their own, against a stand-in for the website on 127.0.0.1: what an event carries and what
// is never sent, the limits on waiting events, the daily data check and how a newer set is swapped in, photos for
// plants added later, and which links open. The same checks as the iPhone app's.
package garden.hereabouts.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import garden.hereabouts.app.services.DataUpdater
import garden.hereabouts.app.services.EventSender
import garden.hereabouts.app.services.PhotoCache
import garden.hereabouts.app.services.TestSwitches
import garden.hereabouts.core.EventValue
import garden.hereabouts.core.Photo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ServicesPartsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: StubServer
    private lateinit var folder: File
    private lateinit var scope: CoroutineScope

    @Before
    fun start() {
        server = StubServer()
        folder = File(context.cacheDir, "services-${UUID.randomUUID()}").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun stop() {
        server.stop()
        scope.cancel()
        folder.deleteRecursively()
    }

    private fun sender(online: Boolean) = EventSender(
        scope = scope,
        baseUrl = server.baseUrl,
        apiKey = "phc_test",
        distinctId = "test-id",
        queueFile = File(folder, "events.json"),
        isOnline = { online },
    )

    private fun send(events: EventSender, name: String, props: Map<String, EventValue> = emptyMap()) {
        runBlocking { events.send(name, props)?.join() }
    }

    private fun flush(events: EventSender) {
        runBlocking { events.flush().join() }
    }

    private fun text(value: String) = EventValue.Text(value)
    private fun number(value: Double) = EventValue.Number(value)

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    // Events

    @Test
    fun eventsKeepOnlyTheAllowedPropertiesAndAllSayPlatformAndroid() {
        server.serve("/ingest/batch/", "{}".toByteArray())
        val events = sender(online = true)
        send(
            events,
            "location_set",
            mapOf(
                "method" to text("zip"), "county" to text("Fairfax County, VA"), "state" to text("VA"),
                "ecoregion" to text("45"), "zip" to text("22030"), "lat" to number(38.85), "platform" to text("web"),
            ),
        )
        send(events, "results_shown", mapOf("count" to number(58.0), "ecoregion" to text("45"), "query" to text("oak")))
        send(events, "not_an_event", mapOf("plant" to text("x")))
        // Once per launch for each plant and seller.
        send(events, "buy_panel_open", mapOf("plant" to text("lindera-benzoin")))
        send(events, "buy_panel_open", mapOf("plant" to text("lindera-benzoin")))
        send(events, "buy_panel_open", mapOf("plant" to text("asclepias-tuberosa")))
        send(events, "affiliate_click", mapOf("plant" to text("lindera-benzoin"), "vendor" to text("A")))
        send(events, "affiliate_click", mapOf("plant" to text("lindera-benzoin"), "vendor" to text("B")))
        send(events, "affiliate_click", mapOf("plant" to text("lindera-benzoin"), "vendor" to text("A")))
        flush(events)

        val posted = server.postedEvents
        assertEquals(
            listOf("location_set", "results_shown", "buy_panel_open", "buy_panel_open", "affiliate_click", "affiliate_click"),
            posted.map { it.name },
        )
        for (event in posted) {
            val properties = event.properties
            assertEquals(event.name, "phc_test", event.apiKey)
            assertEquals(event.name, "android", properties.text("platform"))
            assertEquals(event.name, "test-id", properties.text("distinct_id"))
            assertEquals(event.name, false, (properties["\$process_person_profile"] as? JsonPrimitive)?.booleanOrNull)
            assertNotNull(event.name, event.event.text("timestamp"))
            assertNotNull(event.name, event.event.text("uuid"))
        }
        assertEquals(
            setOf("method", "county", "state", "ecoregion", "platform", "distinct_id", "\$process_person_profile"),
            posted[0].properties.keys,
        )
        val results = posted[1].properties
        assertNull(results["query"])
        assertEquals(58.0, (results["count"] as? JsonPrimitive)?.doubleOrNull)
        assertTrue("Sent events stay queued", events.queued.isEmpty())
    }

    @Test
    fun aNumberThatIsNotFiniteDropsOnlyItsOwnEvent() {
        server.serve("/ingest/batch/", "{}".toByteArray())
        val events = sender(online = false)
        send(events, "results_shown", mapOf("count" to number(3.0)))
        send(events, "results_shown", mapOf("count" to number(Double.NaN)))
        send(events, "plant_search", mapOf("count" to number(Double.POSITIVE_INFINITY), "hidden_count" to number(1.0)))
        send(events, "plant_search", mapOf("count" to number(4.0), "hidden_count" to number(1.0)))
        assertEquals(listOf("results_shown", "plant_search"), events.queued.map { it.event })

        val online = sender(online = true)
        flush(online)
        assertEquals(listOf("results_shown", "plant_search"), server.postedEvents.map { it.name })
    }

    @Test
    fun atMost500EventsWaitAndTheyOutliveARestart() {
        server.serve("/ingest/batch/", "{}".toByteArray())
        val offline = sender(online = false)
        for (i in 0 until 505) send(offline, "results_shown", mapOf("count" to number(i.toDouble())))
        assertEquals(500, offline.queued.size)
        assertTrue("Something was sent with no signal", server.received.isEmpty())

        // A new launch reads the waiting events back and sends them once there is signal. The oldest five were dropped.
        val restarted = sender(online = true)
        flush(restarted)
        val counts = server.postedEvents.mapNotNull { (it.properties["count"] as? JsonPrimitive)?.doubleOrNull }
        assertEquals((5 until 505).map { it.toDouble() }, counts)
        assertTrue(restarted.queued.isEmpty())
    }

    @Test
    fun eventsWaitWhenTheServerFails() {
        server.serve("/ingest/batch/", "busy".toByteArray(), status = 503)
        val events = sender(online = true)
        send(events, "browse_all_opened")
        assertTrue("Nothing was posted", server.received.any { it.path == "/ingest/batch/" })
        assertEquals("An event the server did not take was dropped", 1, events.queued.size)
    }

    // Data check

    private fun freshStore(name: String): DataStore<Preferences> {
        val file = File(folder, "$name.preferences_pb")
        return PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    private fun shippedModel(store: DataStore<Preferences>): AppModel {
        val (data, tables) = AppModel.loadShipped(context)
        return AppModel(data, tables, store, runBlocking { store.data.first() })
    }

    @Test
    fun aNewerDataSetIsUsedAtOnceKeptAndNotCheckedAgainForADay() {
        StubData.good.serve(server)
        val store = freshStore("update-good")
        val model = shippedModel(store)
        val shipped = model.data.value.version
        val dataFolder = File(folder, "data")
        val start = Instant.now()
        var now = start
        val updater = DataUpdater(model, server.baseUrl, dataFolder, store, isOnline = { true }, now = { now })
        runBlocking { updater.checkIfDue() }

        assertEquals(listOf("test-plant-one", "test-plant-two"), model.data.value.plants.map { it.id })
        val saved = DataUpdater.saved(dataFolder, newerThan = shipped)
        assertNotNull("The newer set was not kept", saved)
        assertEquals(listOf("test-plant-one", "test-plant-two"), saved!!.plants.map { it.id })

        // Within a day: no request at all. A day later: checked again.
        val asked = server.received.size
        now = start + Duration.ofHours(23)
        runBlocking { updater.checkIfDue() }
        assertEquals(asked, server.received.size)
        now = start + Duration.ofHours(25)
        runBlocking { updater.checkIfDue() }
        assertTrue(server.received.size > asked)
    }

    /** A store that cannot be written to, as on a phone with no free space. */
    private class FullStore(private val real: DataStore<Preferences>) : DataStore<Preferences> {
        override val data = real.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("No space left on device")
    }

    @Test
    fun aFullPhoneDoesNotCrashTheCheckOrTheAnalyticsId() {
        StubData.good.serve(server)
        val real = freshStore("full")
        val model = shippedModel(real)
        val full = FullStore(real)
        val updater = DataUpdater(model, server.baseUrl, File(folder, "data"), full, isOnline = { true })
        runBlocking { updater.checkIfDue() }
        assertEquals(listOf("test-plant-one", "test-plant-two"), model.data.value.plants.map { it.id })
        assertTrue(runBlocking { EventSender.distinctId(full) }.isNotEmpty())
    }

    @Test
    fun aDataSetThatDoesNotReadKeepsTheOldData() {
        StubData.unreadable.serve(server)
        val store = freshStore("update-bad")
        val model = shippedModel(store)
        val before = model.data.value.plants.map { it.id }
        val dataFolder = File(folder, "data")
        val updater = DataUpdater(model, server.baseUrl, dataFolder, store, isOnline = { true })
        runBlocking { updater.checkIfDue() }

        assertTrue("The broken set was never downloaded", server.received.any { it.path == "/app-data/v1/plants.json" })
        assertEquals(before, model.data.value.plants.map { it.id })
        assertFalse("The broken set was kept", dataFolder.exists())
    }

    /** A newer set that cannot be moved into place leaves the set kept before it, never no data at all. */
    @Test
    fun aFailedSwapKeepsTheSetAlreadyKept() {
        val shipped = shippedModel(freshStore("swap")).data.value.version
        val dataFolder = File(folder, "data")
        DataUpdater.keep(StubData.good.byName, dataFolder)
        val onePlant = StubData.make(
            """[{"id":"test-plant-one","common":"Test Plant One","sci":"Testus unus","category":"perennial"}]""",
        )
        try {
            DataUpdater.keep(onePlant.byName, dataFolder, rename = { _, _ -> false })
            fail("A set that could not be moved into place was reported as kept")
        } catch (e: IOException) {
            // Expected.
        }
        val after = DataUpdater.saved(dataFolder, newerThan = shipped)
        assertNotNull("A failed swap left the app with no kept data", after)
        assertEquals(listOf("test-plant-one", "test-plant-two"), after!!.plants.map { it.id })

        // The next swap that works replaces it.
        DataUpdater.keep(onePlant.byName, dataFolder)
        assertEquals(listOf("test-plant-one"), DataUpdater.saved(dataFolder, newerThan = shipped)!!.plants.map { it.id })
    }

    /** What an update cut short left behind is removed at launch, and the kept set still reads. */
    @Test
    fun leftoverFoldersAreRemoved() {
        val shipped = shippedModel(freshStore("leftovers")).data.value.version
        val dataFolder = File(folder, "data")
        DataUpdater.keep(StubData.good.byName, dataFolder)
        val strays = listOf(
            File(folder, "data-${UUID.randomUUID()}"),
            File(dataFolder, "set-stray"),
        )
        for (stray in strays) {
            stray.mkdirs()
            File(stray, "plants.json").writeText("[]")
        }
        val half = File(dataFolder, "current.tmp").apply { writeText("set-stray") }

        DataUpdater.cleanUp(dataFolder)

        for (stray in strays) assertFalse("$stray was left behind", stray.exists())
        assertFalse("$half was left behind", half.exists())
        val kept = DataUpdater.saved(dataFolder, newerThan = shipped)
        assertNotNull("Cleaning up removed the kept set", kept)
        assertEquals(listOf("test-plant-one", "test-plant-two"), kept!!.plants.map { it.id })
    }

    // Links

    /** A seller link written "HTTPS://" opens like any other, and only web links open at all. */
    @Test
    fun aCapitalisedHttpsLinkOpens() {
        TestSwitches.opened.clear()
        assertTrue(openInBrowser(context, "HTTPS://hereabouts.garden/x"))
        assertEquals("https://hereabouts.garden/x", TestSwitches.opened.last().dataString)
        assertTrue(openInBrowser(context, "Http://hereabouts.garden/y"))
        assertEquals("http://hereabouts.garden/y", TestSwitches.opened.last().dataString)
        assertFalse(openInBrowser(context, "javascript:alert(1)"))
        assertFalse(openInBrowser(context, "HTTPSX://hereabouts.garden/x"))
        assertEquals(2, TestSwitches.opened.size)
        TestSwitches.opened.clear()
    }

    @Test
    fun noCheckWithoutSignal() {
        StubData.good.serve(server)
        val store = freshStore("update-offline")
        val model = shippedModel(store)
        val updater = DataUpdater(model, server.baseUrl, File(folder, "data"), store, isOnline = { false })
        runBlocking { updater.checkIfDue() }
        assertTrue(server.received.isEmpty())
        assertNull("A check that never ran was recorded", runBlocking { store.data.first()[DataUpdater.lastCheckKey] })
    }

    // Photos

    @Test
    fun photosOfPlantsAddedLaterAreDownloadedOnceAndKept() {
        server.serve("/images/test-plant-two-green.jpg", "jpeg bytes".toByteArray())
        val cache = PhotoCache(server.baseUrl, context.assets, File(folder, "photos"))

        // A photo the app ships with needs no download.
        val shipped = Photo("images/quercus-alba-green.jpg", "", "")
        assertTrue(cache.isShipped(shipped))
        assertTrue(runBlocking { cache.load(shipped) })
        assertNotNull(cache.open(shipped)?.use { it.read() })

        val added = Photo("images/test-plant-two-green.jpg", "", "")
        assertNull("A photo nobody downloaded is already there", cache.cachedFile(added))
        assertTrue(runBlocking { cache.load(added) })
        val downloaded = cache.cachedFile(added)
        assertNotNull(downloaded)
        assertArrayEquals("jpeg bytes".toByteArray(), downloaded!!.readBytes())
        assertArrayEquals("jpeg bytes".toByteArray(), cache.open(added)?.use { it.readBytes() })
        runBlocking { cache.load(added) }
        assertEquals(listOf("/images/test-plant-two-green.jpg"), server.received.map { it.path })

        // An address outside images/ is never fetched.
        assertFalse(runBlocking { cache.load(Photo("../secret.jpg", "", "")) })
        assertFalse(runBlocking { cache.load(Photo("images/../../secret.jpg", "", "")) })
        assertNull(cache.open(Photo("images/../../secret.jpg", "", "")))
        assertEquals(1, server.received.size)
    }
}
