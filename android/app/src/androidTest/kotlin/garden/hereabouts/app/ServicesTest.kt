// The app's network services, end to end, against a stand-in for the website run by the test itself on 127.0.0.1:
// the events it sends, a newer data set it takes and keeps, and a broken data set it ignores. The same three checks
// as the iPhone app's, and a second screen that shares the first one's event sender. A relaunch forgets what the
// process holds and builds everything again from what the app kept on the phone; a second screen without it is a
// second screen in the same process.
package garden.hereabouts.app

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import garden.hereabouts.app.services.ServiceSettings
import garden.hereabouts.app.services.TestSwitches
import garden.hereabouts.core.Location
import garden.hereabouts.core.LocationLookup
import garden.hereabouts.core.LocationResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServicesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: StubServer
    private lateinit var settings: ServiceSettings
    private lateinit var fairfax: Location

    @Before
    fun start() {
        server = StubServer()
        TestSwitches.site = server.baseUrl
        settings = ServiceSettings.forLaunch(context)
        clearFiles()
        // Fairfax County, Virginia, kept the way the app keeps it, so the app skips the first-open screen. Clearing
        // the store also forgets the last data check, so the app checks at once.
        val (_, tables) = AppModel.loadShipped(context)
        fairfax = (LocationLookup.resolve("51059", 38.8462, -77.3064, tables) as LocationResult.Found).location
        runBlocking {
            context.keptStore.edit {
                it.clear()
                LocationStore.save(it, fairfax)
            }
        }
    }

    @After
    fun stop() {
        server.stop()
        TestSwitches.site = InstrumentationRegistry.getArguments().getString(HereaboutsTestRunner.BASE_URL_ARGUMENT)
        clearFiles()
    }

    private fun clearFiles() {
        settings.dataFolder.deleteRecursively()
        settings.queueFile.delete()
        settings.photoFolder.deleteRecursively()
    }

    @Test
    fun locationSetArrivesWithThePlatformAndNoZip() {
        launchApp().use { scenario ->
            val launch = loaded(scenario)
            val model = launch.model.value!!
            // As the first-open screen will do when the person gives a ZIP code.
            scenario.onActivity { model.setLocation(fairfax, "zip") }

            assertTrue(
                "No location_set event reached the server. Online: ${launch.connectivity.isOnline.value}. " +
                    "Requests: ${server.receivedPaths}",
                StubServer.waitFor(30) { server.postedEvents.any { it.name == "location_set" } },
            )
            val posted = server.postedEvents
            for (event in posted) {
                val properties = event.properties
                assertEquals("${event.name} does not say platform android", "android", text(properties["platform"]))
                assertTrue("${event.name} was sent without the site's public key", event.apiKey.startsWith("phc_"))
                assertFalse("${event.name} has no distinct_id", text(properties["distinct_id"]).isNullOrEmpty())
                assertEquals(
                    "${event.name} would make a person profile",
                    false,
                    (properties["\$process_person_profile"] as? JsonPrimitive)?.booleanOrNull,
                )
            }
            val properties = posted.first { it.name == "location_set" }.properties
            assertNull("location_set carries a ZIP code", properties["zip"])
            assertNull("location_set carries a position", properties["lat"])
            assertNull("location_set carries a position", properties["lon"])
            assertEquals("Fairfax County, VA", text(properties["county"]))
            assertEquals("VA", text(properties["state"]))
            assertEquals("zip", text(properties["method"]))
        }
    }

    @Test
    fun aNewerDataSetIsUsedAndKeptAfterRelaunch() {
        StubData.good.serve(server)
        launchApp().use { scenario ->
            val launch = loaded(scenario)
            assertTrue(
                "The newer data set was not used. Online: ${launch.connectivity.isOnline.value}. " +
                    "Requests: ${server.receivedPaths}",
                waitForTag("plant-test-plant-one", 30_000),
            )
        }

        // With the website gone, the relaunched app must still have the newer set, read from what it kept.
        server.serveNothing()
        AppProcess.forget()
        launchApp().use { scenario ->
            loaded(scenario)
            assertTrue("After a relaunch the app went back to the data it ships with", waitForTag("plant-test-plant-one", 20_000))
            assertTrue(plantRows().contains("plant-test-plant-two"))
            assertEquals("The list should hold only the newer set's two plants", 2, plantRows().size)
        }
    }

    @Test
    fun aBrokenDataSetIsIgnoredAndTheAppStillListsPlants() {
        StubData.unreadable.serve(server)
        launchApp().use { scenario ->
            val launch = loaded(scenario)
            assertTrue(
                "The app did not download all four files. Online: ${launch.connectivity.isOnline.value}. " +
                    "Requests: ${server.receivedPaths}",
                StubServer.waitFor(30) { server.received.any { it.path == "/app-data/v1/plant-regions.json" } },
            )
            // Wait for the set to be read and refused.
            runBlocking { launch.dataCheck?.join() }
            compose.waitForIdle()
            // The model's own list, not the rows on the screen: a small phone builds only the one or two rows it shows.
            assertFalse("The broken data set was used", "bad-data-plant" in plantIds(launch))
            assertTrue("The app no longer lists the plants it ships with", rowsDownTheList(3).size >= 3)
            assertFalse("The broken data set was kept on the phone", settings.dataFolder.exists())
        }

        // Nothing of the broken set was kept for the next launch.
        server.serveNothing()
        AppProcess.forget()
        launchApp().use { scenario ->
            val launch = loaded(scenario)
            assertTrue("No plants listed after a relaunch", waitFor(20_000) { plantRows().isNotEmpty() })
            assertFalse("The broken data set was kept", "bad-data-plant" in plantIds(launch))
            assertTrue("The app no longer lists the plants it ships with", rowsDownTheList(3).size >= 3)
        }
    }

    /**
     * A second screen in the same process (as a tapped reminder could open while the app is running), made again as
     * when the phone is turned, shares the first one's event sender: an event sent once per launch is not sent again
     * when the same plant page opens on it.
     */
    @Test
    fun aSecondScreenDoesNotSendOncePerLaunchEventsAgain() {
        launchApp().use { scenario ->
            loaded(scenario)
            openSpicebush()
            assertTrue(
                "No buy_panel_open when the plant page opened. Requests: ${server.receivedPaths}",
                StubServer.waitFor(30) { server.postedEvents.any { it.name == "buy_panel_open" } },
            )
        }

        launchApp().use { scenario ->
            loaded(scenario)
            // The phone makes the screen again, as it does when the phone is turned.
            scenario.recreate()
            loaded(scenario)
            openSpicebush()
            // Time for a second buy_panel_open to arrive, if one were sent.
            StubServer.waitFor(8) { server.postedEvents.count { it.name == "buy_panel_open" } > 1 }
            assertEquals(
                "buy_panel_open was sent again by the second screen",
                1,
                server.postedEvents.count { it.name == "buy_panel_open" },
            )
        }
    }

    /** Searches the Plants tab for Spicebush, as a person would, and opens its page. */
    private fun openSpicebush() {
        assertTrue("The Plants tab has no search field", waitForTag("search", 20_000))
        // The search lasts as long as the process, so the second screen may already hold it.
        compose.onNodeWithTag("search").performTextClearance()
        compose.onNodeWithTag("search").performTextInput("Spicebush")
        // The Search key closes the keyboard, so it does not cover the plant page.
        compose.onNodeWithTag("search").performImeAction()
        compose.waitForIdle()
        compose.onNodeWithTag("plantList").performScrollToKey("lindera-benzoin")
        assertTrue("No row for Spicebush", waitForTag("plant-lindera-benzoin", 10_000))
        compose.onNodeWithTag("plant-lindera-benzoin").performClick()
        assertTrue("The plant page did not open", waitForTag("plantPage", 10_000))
    }

    /** The screen's ViewModel, once the model has loaded and the tabs are drawn. */
    private fun loaded(scenario: ActivityScenario<MainActivity>): Launch {
        lateinit var launch: Launch
        scenario.onActivity { launch = ViewModelProvider(it)[Launch::class.java] }
        assertTrue("The app did not load", waitFor(30_000) { launch.model.value != null })
        assertTrue("No tab bar after launch", waitForTag("tabs", 30_000))
        return launch
    }

    /** The ids of every plant in the data the app is using. */
    private fun plantIds(launch: Launch): List<String> = launch.model.value!!.data.value.plants.map { it.id }

    /** The tags of every plant row on the Plants tab, each marked "plant-<id>". */
    private fun plantRows(): List<String> =
        compose.onAllNodes(hasTestTagStarting("plant-")).fetchSemanticsNodes().mapNotNull { node ->
            node.config.getOrElseNullable(SemanticsProperties.TestTag) { null }
        }

    /**
     * The plant rows met while moving down the list, until there are `enough` or the list ends, then back to the top.
     * The list only builds the rows on the screen, and on a small phone that can be one or two.
     */
    private fun rowsDownTheList(enough: Int): Set<String> {
        val seen = linkedSetOf<String>()
        val list = compose.onNodeWithTag("plantList")
        var index = 0
        while (index < 40) {
            seen += plantRows()
            if (seen.size >= enough) break
            index += 2
            try {
                list.performScrollToIndex(index)
            } catch (e: Throwable) {
                break
            }
            compose.waitForIdle()
        }
        list.performScrollToIndex(0)
        compose.waitForIdle()
        return seen
    }

    private fun hasTestTagStarting(prefix: String) = SemanticsMatcher("test tag starts with $prefix") {
        it.config.getOrElseNullable(SemanticsProperties.TestTag) { null }?.startsWith(prefix) == true
    }

    private fun waitForTag(tag: String, millis: Long): Boolean =
        waitFor(millis) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    /** True once `condition` holds, false if it still does not after `millis`. */
    private fun waitFor(millis: Long, condition: () -> Boolean): Boolean = try {
        compose.waitUntil(millis, condition)
        true
    } catch (e: ComposeTimeoutException) {
        false
    }

    private fun text(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
}
