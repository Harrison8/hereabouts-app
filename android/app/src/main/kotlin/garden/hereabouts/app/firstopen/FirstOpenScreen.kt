// The first-open screen. Not built yet: the tests that describe it come first.
package garden.hereabouts.app.firstopen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import garden.hereabouts.app.AppModel
import garden.hereabouts.core.Location
import garden.hereabouts.core.Tables

object FirstOpenLookup {
    sealed interface Outcome {
        data class Found(val location: Location, val method: String) : Outcome
        data class Message(val text: String) : Outcome
    }

    fun zip(digits: String, tables: Tables): Outcome? = null

    fun device(lat: Double, lon: Double, tables: Tables): Outcome = Outcome.Message("")

    suspend fun text(text: String, tables: Tables, address: AddressLookup): Outcome = Outcome.Message("")

    fun zipDigits(typed: String): String = typed
}

class LatestLookup {
    suspend fun <T> run(work: suspend () -> T): T? = null

    fun supersede() {}
}

@Composable
fun FirstOpenScreen(model: AppModel, modifier: Modifier = Modifier, inSheet: Boolean = false, onChosen: () -> Unit = {}) {
    Box(modifier.fillMaxSize())
}
