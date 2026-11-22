// Arms the Saturday reminder again after the phone restarts or its clock or time zone changes. The phone forgets every
// alarm when it restarts, and a reminder armed for 08:00 in one time zone would come at the wrong hour in another.
// It reads only what the reminders need, so the whole app is not loaded.
package garden.hereabouts.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import garden.hereabouts.app.services.clockUnderTest
import java.time.ZonedDateTime
import kotlinx.coroutines.launch

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val app = context.applicationContext
        // Keeps the phone from ending the work when this method returns. Null when called directly.
        val pending: PendingResult? = goAsync()
        reminderWork.launch {
            try {
                // Read after the change, so the new time zone is the one used.
                val now = clockUnderTest() ?: ZonedDateTime.now()
                ReminderScheduler.arm(app, readPlan(app).weekly(now).firstOrNull())
            } finally {
                pending?.finish()
            }
        }
    }

    private companion object {
        /** The phone restarted, its clock was set, or its time zone changed. */
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
    }
}
