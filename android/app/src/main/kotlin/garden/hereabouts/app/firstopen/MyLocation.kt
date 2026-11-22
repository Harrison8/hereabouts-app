// "Use my location": asks the phone for its position once. The permission is asked only when the person taps the
// link, and only for the rough location (about a kilometre), never the exact one and never in the background. The
// position stays on the phone: the first-open screen turns it into the county of the nearest ZIP code, and only that ZIP
// code's own point, rounded, is ever kept. Nothing here sends anything.
package garden.hereabouts.app.firstopen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Finds the phone's position once. `askPermission` shows the phone's own question for the rough location and gives
 * the answer; it is called only when the permission is not yet given.
 */
class MyLocation(private val context: Context, private val askPermission: suspend () -> Boolean) {
    sealed interface Outcome {
        data class Position(val lat: Double, val lon: Double) : Outcome

        /** The person said no, now or earlier, or the phone does not allow it. */
        data object Denied : Outcome

        /** The phone could not find where it is, or its location is turned off. */
        data object Unavailable : Outcome
    }

    /** The answer under way, so a second tap waits for the same one. Used on the main thread only. */
    private var pending: CompletableDeferred<Outcome>? = null

    /** One position, or why there is none. A second call while one is under way waits for the same answer. */
    suspend fun position(): Outcome {
        pending?.let { return it.await() }
        val mine = CompletableDeferred<Outcome>()
        pending = mine
        try {
            val outcome = find()
            mine.complete(outcome)
            return outcome
        } catch (e: CancellationException) {
            mine.complete(Outcome.Unavailable)
            throw e
        } finally {
            if (pending === mine) pending = null
        }
    }

    private fun allowed(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private suspend fun find(): Outcome {
        if (!allowed() && !askPermission()) return Outcome.Denied
        if (!allowed()) return Outcome.Denied
        val manager = context.getSystemService(LocationManager::class.java) ?: return Outcome.Unavailable
        if (!LocationManagerCompat.isLocationEnabled(manager)) return Outcome.Unavailable
        // The providers this app may use with the rough location, best first. Google's combined provider exists from
        // Android 12; before that the network provider gives the rough position.
        val enabled = manager.getProviders(true)
        val provider = listOf(FUSED, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in enabled } ?: return Outcome.Unavailable
        val fix = withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation -> requestOneFix(manager, provider, continuation) }
        }
        return fix?.let { Outcome.Position(it.latitude, it.longitude) } ?: Outcome.Unavailable
    }

    /**
     * Asks `provider` for one position and resumes `continuation` with it, or with null when there is none. Android 11
     * and later have a call for exactly one position; Android 8 to 10 ask for a single update.
     */
    private fun requestOneFix(
        manager: LocationManager,
        provider: String,
        continuation: CancellableContinuation<android.location.Location?>,
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            continuation.resume(null)
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: android.location.Location) {
                        if (continuation.isActive) continuation.resume(location)
                    }

                    // Android 8 to 10 call these and have no default for them.
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

                    override fun onProviderEnabled(provider: String) {}

                    override fun onProviderDisabled(provider: String) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            if (continuation.isActive) continuation.resume(null)
        } catch (e: IllegalArgumentException) {
            if (continuation.isActive) continuation.resume(null)
        }
    }

    private companion object {
        /** Google's combined provider, named here because its constant needs Android 12. */
        const val FUSED = "fused"

        /** How long to wait for a position before saying none was found. */
        const val FIX_TIMEOUT_MS = 20_000L
    }
}

/**
 * The screen's [MyLocation]. It asks for the permission through the phone's own question, which the activity shows
 * and answers; nothing is asked until the person taps "Use my location".
 */
@Composable
fun rememberMyLocation(): MyLocation {
    val context = LocalContext.current
    val waiting = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        waiting[0]?.complete(granted)
        waiting[0] = null
    }
    return remember(context) {
        MyLocation(context.applicationContext) {
            val answer = CompletableDeferred<Boolean>()
            waiting[0] = answer
            launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            answer.await()
        }
    }
}
