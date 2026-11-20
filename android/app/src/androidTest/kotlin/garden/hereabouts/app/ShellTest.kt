// The app's frame: with a county already chosen, the three tabs are there and each opens its own screen. A screenshot
// of each tab, and a picture of the launcher icon as this phone shapes it, are saved on the device, where the workflow
// collects them for review.
package garden.hereabouts.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import garden.hereabouts.core.LocationLookup
import garden.hereabouts.core.LocationResult
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ShellTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun threeTabs() {
        // Fairfax County, Virginia, kept the way the app keeps it, so the app skips the first-open screen.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (_, tables) = AppModel.loadShipped(context)
        val fairfax = (LocationLookup.resolve("51059", null, null, tables) as LocationResult.Found).location
        runBlocking {
            context.keptStore.edit {
                it.clear()
                LocationStore.save(it, fairfax)
            }
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("tabs")).fetchSemanticsNodes().isNotEmpty() }

            for (name in listOf("Plants", "Garden", "Settings")) {
                compose.onNodeWithTag("tab-$name").assertExists("The tab bar has no $name tab").performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodes(hasTestTag("title") and hasText(name)).fetchSemanticsNodes().isNotEmpty()
                }
                compose.waitForIdle()
                screenshot(name)
            }
        }
        launcherIcon(context)
    }

    /** The whole screen, status bar and gesture bar included, saved as SCREENSHOTS/<name>.png on the device. */
    private fun screenshot(name: String) {
        shell("mkdir -p $SCREENSHOTS")
        shell("screencap -p $SCREENSHOTS/$name.png")
    }

    /**
     * The app's icon as this phone's launcher shape cuts it, 432 pixels square, saved as SCREENSHOTS/launcher-icon.png.
     * Only for looking at: the app may write only to its own folders, so the shell copies the picture across.
     */
    private fun launcherIcon(context: Context) {
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 432, 432)
        icon.draw(Canvas(bitmap))
        val file = File(context.getExternalFilesDir(null) ?: return, "launcher-icon.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shell("mkdir -p $SCREENSHOTS")
        shell("cp ${file.absolutePath} $SCREENSHOTS/launcher-icon.png")
    }

    /** Runs a shell command on the device. Reading the output to its end waits for the command to finish. */
    private fun shell(command: String) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { it.readBytes() }
    }

    companion object {
        /** A folder the shell can write to and the workflow can read from. */
        const val SCREENSHOTS = "/data/local/tmp/hereabouts-screenshots"
    }
}
