// How the app opens anything outside itself: a plain "view this" handed to Android, with no app named, so the
// phone's default browser opens a web page and the person's own maps app opens a place. Never a browser inside the
// app. A phone with nothing that can open the link does nothing.
package garden.hereabouts.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import garden.hereabouts.app.services.openedUnderTest

/**
 * Opens a web address in the phone's default browser. False when nothing on the phone can open it. The scheme may be
 * written in capitals ("HTTPS://"), as a seller link can be; it is handed on in small letters, as browsers expect.
 */
fun openInBrowser(context: Context, url: String): Boolean {
    val lower = url.lowercase()
    if (!lower.startsWith("https://") && !lower.startsWith("http://")) return false
    return open(context, Uri.parse(url).normalizeScheme())
}

/**
 * Directions to a shop nearby. The website's Google Maps link names the place as "destination=lat,lon"; the phone's
 * maps app is asked for that place first, and with no maps app the Google Maps link opens in the browser instead.
 */
fun openDirections(context: Context, mapsUrl: String): Boolean {
    val place = Uri.parse(mapsUrl).getQueryParameter("destination").orEmpty()
    if (PLACE.matches(place) && open(context, Uri.parse("geo:$place?q=$place"))) return true
    return openInBrowser(context, mapsUrl)
}

/** "38.400143,-85.548903": a latitude and a longitude, the only kind of place handed to a maps app. */
private val PLACE = Regex("""-?\d+(\.\d+)?,-?\d+(\.\d+)?""")

private fun open(context: Context, uri: Uri): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, uri)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    openedUnderTest(intent)?.let { return it }
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
