// The app's root: the first-open screen until a county is chosen, then the three tabs.
package garden.hereabouts.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import garden.hereabouts.app.firstopen.FirstOpenScreen

/** `isOnline` is whether the phone has signal; the Plants tab says so when it has none. */
@Composable
fun HereaboutsApp(model: AppModel, isOnline: Boolean) {
    val location by model.location.collectAsStateWithLifecycle()
    if (location == null) {
        FirstOpenScreen(model)
    } else {
        RootTabs(model, isOnline)
    }
}
