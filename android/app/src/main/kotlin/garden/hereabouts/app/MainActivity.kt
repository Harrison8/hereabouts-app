// The one screen holder. It draws edge to edge (behind the status bar and the gesture bar, with the screens keeping
// their content clear of both) and shows the app once its data has loaded. Whenever the app comes to the front, and
// whenever signal comes back, it sends waiting events and checks for newer plant data if a day has passed since the
// last check.
package garden.hereabouts.app

import android.app.Application
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import garden.hereabouts.app.services.Connectivity
import garden.hereabouts.app.services.DataUpdater
import garden.hereabouts.app.services.ServiceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loads the app's model and its network services in the background, once for the activity (they last when the phone
 * is turned). The model is null until the data and the kept values have been read.
 */
class Launch(app: Application) : AndroidViewModel(app) {
    /** Whether the phone has signal. */
    val connectivity = Connectivity(app)

    private val _model = MutableStateFlow<AppModel?>(null)
    val model: StateFlow<AppModel?> = _model.asStateFlow()

    private var updater: DataUpdater? = null

    /** The data check running now, or the last one, so a test can wait for it to finish. */
    @Volatile
    var dataCheck: Job? = null
        private set

    init {
        viewModelScope.launch {
            val online = { connectivity.isOnline.value }
            val settings = withContext(Dispatchers.IO) { ServiceSettings.forLaunch(app) }
            val loaded = withContext(Dispatchers.IO) { AppModel.load(app, settings, viewModelScope, online) }
            updater = DataUpdater(loaded, settings.site, settings.dataFolder, app.keptStore, online)
            _model.value = loaded
            // At launch, and each time signal comes back.
            connectivity.isOnline.collect { if (it) refresh() }
        }
    }

    /** The app came to the front: send waiting events and check for newer data if a check is due. */
    fun cameToFront() {
        if (_model.value != null) refresh()
    }

    /** Both do nothing without signal; the data check also does nothing until a day has passed since the last one. */
    private fun refresh() {
        val updater = updater ?: return
        if (dataCheck?.isActive != true) dataCheck = viewModelScope.launch { updater.checkIfDue() }
        _model.value?.events?.flush()
    }

    override fun onCleared() {
        connectivity.close()
    }
}

class MainActivity : ComponentActivity() {
    private val launch: Launch by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark status bar icons on the white ground. On older Android versions that draw a gesture bar or button bar
        // with no icons of their own, it gets a nearly white scrim so its buttons stay readable.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.argb(0xE6, 0xFF, 0xFF, 0xFF), Color.argb(0x80, 0x1B, 0x1B, 0x1B)),
        )
        super.onCreate(savedInstanceState)
        setContent {
            val model by launch.model.collectAsStateWithLifecycle()
            HereaboutsTheme {
                model?.let { HereaboutsApp(it) }
            }
        }
        holdFirstFrameUntilLoaded()
    }

    override fun onStart() {
        super.onStart()
        launch.cameToFront()
    }

    /**
     * Keeps Android's starting window up until the model has loaded, by not drawing the first frame before then. So
     * the first thing drawn already knows whether a county was chosen, and someone who chose one never sees the
     * first-open screen.
     */
    private fun holdFirstFrameUntilLoaded() {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (launch.model.value == null) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
    }
}
