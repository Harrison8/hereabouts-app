// The app's network services, end to end, against a stand-in for the website run by the test itself on 127.0.0.1:
// the events it sends, a newer data set it takes and keeps, and a broken data set it ignores. The same three checks
// as the iPhone app's. Closing the screen and opening it again counts as a relaunch: everything the app holds is
// built again from what it kept on the phone.
package garden.hereabouts.app

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
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
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val launch = loaded(scenario)
            assertTrue(
                "The newer data set was not used. Online: ${launch.connectivity.isOnline.value}. " +
                    "Requests: ${server.receivedPaths}",
                waitForTag("plant-test-plant-one", 30_000),
            )
        }

        // With the website gone, the relaunched app must still have the newer set, read from what it kept.
        server.serveNothing()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            loaded(scenario)
            assertTrue("After a relaunch the app went back to the data it ships with", waitForTag("plant-test-plant-one", 20_000))
            assertTrue(plantRows().contains("plant-test-plant-two"))
            assertEquals("The list should hold only the newer set's two plants", 2, plantRows().size)
        }
    }

    @Test
    fun aBrokenDataSetIsIgnoredAndTheAppStillListsPlants() {
        StubData.unreadable.serve(server)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val launch = loaded(scenario)
            assertTrue(
                "The app did not download all four files. Online: ${launch.connectivity.isOnline.value}. " +
                    "Requests: ${server.receivedPaths}",
                StubServer.waitFor(30) { server.received.any { it.path == "/app-data/v1/plant-regions.json" } },
            )
            // Wait for the set to be read and refused.
            runBlocking { launch.dataCheck?.join() }
            compose.waitForIdle()
            assertFalse("The broken data set was used", plantRows().contains("plant-bad-data-plant"))
            assertTrue("The app no longer lists the plants it ships with", plantRows().size > 2)
            assertFalse("The broken data set was kept on the phone", settings.dataFolder.exists())
        }

        // Nothing of the broken set was kept for the next launch.
        server.serveNothing()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            loaded(scenario)
            assertTrue("No plants listed after a relaunch", waitFor(20_000) { plantRows().isNotEmpty() })
            assertFalse("The broken data set was kept", plantRows().contains("plant-bad-data-plant"))
            assertTrue("The app no longer lists the plants it ships with", plantRows().size > 2)
        }
    }

    /** The screen's ViewModel, once the model has loaded and the tabs are drawn. */
    private fun loaded(scenario: ActivityScenario<MainActivity>): Launch {
        lateinit var launch: Launch
        scenario.onActivity { launch = ViewModelProvider(it)[Launch::class.java] }
        assertTrue("The app did not load", waitFor(30_000) { launch.model.value != null })
        assertTrue("No tab bar after launch", waitForTag("tabs", 30_000))
        return launch
    }

    /** The tags of every plant row on the Plants tab, each marked "plant-<id>". */
    private fun plantRows(): List<String> =
        compose.onAllNodes(hasTestTagStarting("plant-")).fetchSemanticsNodes().mapNotNull { node ->
            node.config.getOrElseNullable(SemanticsProperties.TestTag) { null }
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
