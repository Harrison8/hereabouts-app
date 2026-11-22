// How the screen tests open the app: as the phone's launcher opens it, with the opening turned off, so a test that is
// not about the opening does not wait for it.
package garden.hereabouts.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import garden.hereabouts.app.services.SKIP_OPENING

/** The launcher's intent for the app's screen, with the opening turned off. */
fun appIntent(): Intent {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return Intent.makeMainActivity(ComponentName(context, MainActivity::class.java)).putExtra(SKIP_OPENING, true)
}

/** Opens the app's screen with the opening turned off. */
fun launchApp(): ActivityScenario<MainActivity> = ActivityScenario.launch(appIntent())
