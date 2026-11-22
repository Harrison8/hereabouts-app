// The one screen holder. It draws edge to edge (behind the status bar and the gesture bar, with the screens keeping
// their content clear of both) and shows the app once its data has loaded. Whenever the app comes to the front, and
// whenever signal comes back, it sends waiting events and checks for newer plant data if a day has passed since the
// last check. A tapped reminder brings it forward on the Garden tab.
// On a fresh launch the opening plays over the first screen (see the opening folder). Whether it plays is decided
// here, when the screen is made, before anything is drawn. The phone's loading screen (kraft, with the tag in the
// middle) stays up until the first frame, which is the opening's, so the tag does not move.
package garden.hereabouts.app

import android.animation.ValueAnimator
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.util.Consumer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import garden.hereabouts.app.opening.Opening
import garden.hereabouts.app.opening.OpeningLayer
import garden.hereabouts.app.plant.MapShapesStore
import garden.hereabouts.app.reminders.NotificationAsk
import garden.hereabouts.app.reminders.OPEN_GARDEN
import garden.hereabouts.app.services.Connectivity
import garden.hereabouts.app.services.animationsUnderTest
import garden.hereabouts.app.services.firstFrameDrawnForTests
import garden.hereabouts.app.services.firstFrameHeldForTests
import garden.hereabouts.app.services.openingHeldForTests
import garden.hereabouts.app.services.openingSkippedForTests
import garden.hereabouts.core.OpeningRule
import garden.hereabouts.core.OpeningStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The screen's way to what lasts as long as the app's process (see AppProcess): the model, which is null until the
 * data and the kept values have been read, the phone's signal and the data check.
 */
class Launch(app: Application) : AndroidViewModel(app) {
    private val process = AppProcess.get(app)

    /** Whether the phone has signal. */
    val connectivity: Connectivity get() = process.connectivity

    val model: StateFlow<AppModel?> get() = process.model

    /** The data check running now, or the last one, so a test can wait for it to finish. */
    val dataCheck: Job? get() = process.dataCheck

    /** The app came to the front: send waiting events and check for newer data if a check is due. */
    fun cameToFront() = process.cameToFront()

    /** A reminder was tapped. */
    fun openGarden() = process.openGarden()

    /** True for the first screen made in this process, and false for every later one. */
    fun firstScreen(): Boolean = process.firstScreen()
}

/**
 * Whether `intent` is a tapped reminder's. Reopening the app from the recent apps list repeats the intent that last
 * started the screen, which may be a reminder's; that is not a reminder tap.
 */
internal fun isReminderTap(intent: Intent?): Boolean =
    intent?.action == OPEN_GARDEN && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0

class MainActivity : ComponentActivity() {
    private val launch: Launch by viewModels()

    /** The phone's question about notifications, which the reminders ask the first time a plant is saved. */
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        NotificationAsk.answered(it)
    }

    /** This screen's opening, decided when the screen is made. */
    private lateinit var opening: Opening

    private val newIntents = Consumer<Intent> { intent ->
        setIntent(intent)
        if (isReminderTap(intent)) {
            opening.stop()
            launch.openGarden()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val created = SystemClock.elapsedRealtime()
        // The manifest gives the screen the loading screen's theme, so the phone shows kraft with the tag while the
        // app starts. The screen itself uses the app's theme.
        setTheme(R.style.Theme_Hereabouts)
        // Dark status bar icons on the white ground. On older Android versions that draw a gesture bar or button bar
        // with no icons of their own, it gets a nearly white scrim so its buttons stay readable.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.argb(0xE6, 0xFF, 0xFF, 0xFF), Color.argb(0x80, 0x1B, 0x1B, 0x1B)),
        )
        super.onCreate(savedInstanceState)
        // On Android 12 and later the phone's own loading screen goes the moment the first frame is drawn, with no
        // fade, so the opening's first frame takes its place exactly.
        if (Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { it.remove() }
        NotificationAsk.attach(askNotifications)
        val fromReminder = isReminderTap(intent)
        // Decided once a launch: a screen made again, or a later screen in the same process, never shows it.
        val firstScreen = launch.firstScreen()
        opening = Opening(
            OpeningRule.start(
                reduceMotion = !(animationsUnderTest() ?: ValueAnimator.areAnimatorsEnabled()),
                fromReminder = fromReminder,
                shownBefore = !firstScreen || savedInstanceState != null,
                skipForTests = openingSkippedForTests(intent),
                holdAtMs = openingHeldForTests(intent),
            ),
        )
        if (opening.start != OpeningStart.Off) {
            // The map's outlines are read from now, alongside the model, so once the model has loaded the first frame
            // waits only for the short map build.
            val shapes = lifecycleScope.async { MapShapesStore.get(applicationContext) }
            lifecycleScope.launch { opening.buildMap(shapes, launch.model.filterNotNull().first()) }
        }
        // A screen made again after the phone was turned has already acted on the reminder that opened it.
        if (savedInstanceState == null && fromReminder) launch.openGarden()
        addOnNewIntentListener(newIntents)
        setContent {
            val model by launch.model.collectAsStateWithLifecycle()
            val online by launch.connectivity.isOnline.collectAsStateWithLifecycle()
            HereaboutsTheme {
                Box(Modifier.fillMaxSize(), propagateMinConstraints = true) {
                    model?.let { HereaboutsApp(it, online) }
                    OpeningLayer(opening)
                }
            }
        }
        holdFirstFrameUntilReady(created)
    }

    override fun onStart() {
        super.onStart()
        NotificationAsk.attach(askNotifications)
        launch.cameToFront()
    }

    override fun onDestroy() {
        removeOnNewIntentListener(newIntents)
        NotificationAsk.detach(askNotifications)
        super.onDestroy()
    }

    /**
     * Keeps Android's starting window up until the model has loaded, by not drawing the first frame before then. So
     * the first thing drawn already knows whether a county was chosen, and someone who chose one never sees the
     * first-open screen. When the opening plays, it also waits for the opening's map, so the map starts from empty
     * on the first frame rather than appearing part filled.
     */
    private fun holdFirstFrameUntilReady(created: Long) {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (launch.model.value == null) return false
                if (opening.start != OpeningStart.Off && !opening.mapSettled) return false
                if (firstFrameHeldForTests()) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                firstFrameDrawnForTests(SystemClock.elapsedRealtime() - created)
                return true
            }
        })
    }
}
