// The app's root: the first-open screen until a county is chosen, then the three tabs.
package garden.hereabouts.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun HereaboutsApp(model: AppModel) {
    val location by model.location.collectAsStateWithLifecycle()
    if (location == null) {
        FirstOpenPlaceholder()
    } else {
        RootTabs()
    }
}

/** Stands in for the first-open screen, where the person gives their ZIP code, until that screen is built. */
@Composable
private fun FirstOpenPlaceholder() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brand.ground)
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Text("Hereabouts", style = MaterialTheme.typography.displaySmall, color = Brand.moss)
    }
}
