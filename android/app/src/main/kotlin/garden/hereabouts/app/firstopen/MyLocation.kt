// "Use my location". Not built yet: the tests that describe it come first.
package garden.hereabouts.app.firstopen

import android.content.Context

class MyLocation(private val context: Context, private val askPermission: suspend () -> Boolean) {
    sealed interface Outcome {
        data class Position(val lat: Double, val lon: Double) : Outcome
        data object Denied : Outcome
        data object Unavailable : Outcome
    }

    suspend fun position(): Outcome = Outcome.Unavailable
}
