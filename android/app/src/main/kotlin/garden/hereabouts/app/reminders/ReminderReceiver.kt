// Posts the Saturday reminder when its alarm goes off, then arms the next one. The reminder is the website's: the
// title "This week in your garden" and one line per job naming the saved plants. Tapping it brings the app forward on
// the Garden tab, in the screen already open if there is one.
//
// The work runs off the main thread and reads only what the reminders need (the kept values, the care tables and the
// plant data), so an alarm that goes off while the app is closed does not load the whole app.
package garden.hereabouts.app.reminders

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import garden.hereabouts.app.AppModel
import garden.hereabouts.app.AppProcess
import garden.hereabouts.app.CareTables
import garden.hereabouts.app.LocationStore
import garden.hereabouts.app.MainActivity
import garden.hereabouts.app.R
import garden.hereabouts.app.RemindersStore
import garden.hereabouts.app.SavedStore
import garden.hereabouts.app.keptStore
import garden.hereabouts.app.services.ServiceSettings
import garden.hereabouts.app.services.clockUnderTest
import garden.hereabouts.core.Reminder
import java.time.Duration
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Where the receivers do their work. It lasts as long as the app's process. */
internal val reminderWork: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** The action of the screen a tapped reminder opens: the Garden tab. */
const val OPEN_GARDEN = "garden.hereabouts.app.OPEN_GARDEN"

/** The notification channel the reminders are posted in, which the person can turn off in the phone's settings. */
private const val CHANNEL = "saturday"

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.REMIND) return
        val app = context.applicationContext
        // Keeps the phone from ending the work when this method returns. Null when called directly.
        val pending: PendingResult? = goAsync()
        reminderWork.launch {
            try {
                remind(app)
            } finally {
                pending?.finish()
            }
        }
    }

    private suspend fun remind(context: Context) {
        val now = clockUnderTest() ?: ZonedDateTime.now()
        val plan = readPlan(context)
        // The reminder whose time has come: the latest at or before now, unless the phone was off for over a day.
        val due = plan.weekly(now.minusDays(7)).lastOrNull { !it.at.isAfter(now) }
        if (due != null && Duration.between(due.at, now) <= Duration.ofDays(1)) post(context, due)
        ReminderScheduler.arm(context, plan.weekly(now).firstOrNull())
    }
}

/**
 * What the reminders are planned from: the open app's own, or, with the app closed, read from what the phone keeps,
 * without loading the whole app.
 */
internal suspend fun readPlan(context: Context): ReminderPlan {
    AppProcess.loaded()?.let { return it.reminderPlan() }
    val app = context.applicationContext
    val kept = app.keptStore.data.first()
    val settings = ServiceSettings.forLaunch(app)
    return ReminderPlan(
        care = CareTables.load(app),
        plants = AppModel.newestData(app, settings.dataFolder).plants,
        countyFips = LocationStore.countyFips(kept),
        saved = SavedStore.load(kept),
        on = RemindersStore.load(kept),
        permission = ReminderScheduler.permission(app),
    )
}

/** Posts `reminder`, if the phone lets the app post notifications. */
private fun post(context: Context, reminder: Reminder) {
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    val manager = context.getSystemService(NotificationManager::class.java)
    if (!manager.areNotificationsEnabled()) return
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL, "Saturday reminders", NotificationManager.IMPORTANCE_DEFAULT),
    )
    // Brings the screen already open forward instead of opening a second one over it.
    val open = Intent(context, MainActivity::class.java)
        .setAction(OPEN_GARDEN)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val tap = PendingIntent.getActivity(
        context,
        0,
        open,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val notification = Notification.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher_foreground_inset)
        .setContentTitle(reminder.title)
        .setContentText(reminder.body)
        .setStyle(Notification.BigTextStyle().bigText(reminder.body))
        .setContentIntent(tap)
        .setAutoCancel(true)
        .build()
    manager.notify(reminder.id, notification)
}
