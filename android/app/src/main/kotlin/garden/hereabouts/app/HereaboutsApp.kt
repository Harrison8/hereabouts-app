// The app's root: the first-open screen until a county is chosen, then the three tabs.
package garden.hereabouts.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import garden.hereabouts.app.firstopen.FirstOpenScreen

@Composable
fun HereaboutsApp(model: AppModel) {
    val location by model.location.collectAsStateWithLifecycle()
    if (location == null) {
        FirstOpenScreen(model)
    } else {
        RootTabs(model)
    }
}
