// The one screen holder. It draws edge to edge (behind the status bar and the gesture bar, with the screens keeping
// their content clear of both) and shows the app once its data has loaded.
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loads the app's model in the background, once for the activity (it lasts when the phone is turned). The model is
 * null until the data and the kept values have been read.
 */
class Launch(app: Application) : AndroidViewModel(app) {
    private val _model = MutableStateFlow<AppModel?>(null)
    val model: StateFlow<AppModel?> = _model.asStateFlow()

    init {
        viewModelScope.launch {
            _model.value = withContext(Dispatchers.IO) { AppModel.load(app) }
        }
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
