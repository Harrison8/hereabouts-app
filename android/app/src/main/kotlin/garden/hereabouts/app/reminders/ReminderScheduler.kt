// The Saturday reminders, armed one at a time with the phone's alarm service: only the next Saturday that has jobs is
// ever waiting. The alarm is inexact (the phone may move it a few minutes to save battery), so the app needs no right
// to set exact alarms. When it goes off, ReminderReceiver posts the reminder and arms the next one; RescheduleReceiver
// arms it again after a restart or a change of clock or time zone; the app arms it again whenever what it depends on
// changes (the saved plants, the county, the switch in Settings, the data, the phone's answer about notifications) and
// each time it comes to the front.
//
// On Android 13 and later the phone asks the person whether the app may post notifications. The app asks once, the
// first time a plant is saved while a county is set, never at first open. A refusal is final for saving; turning the
// switch in Settings on again is the way back, and asks once more. Below Android 13 notifications are on unless the
// person turned them off in the phone's settings, and there is nothing to ask.
package garden.hereabouts.app.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import garden.hereabouts.app.CareTables
import garden.hereabouts.app.keptStore
import garden.hereabouts.app.services.armedUnderTest
import garden.hereabouts.app.services.askedUnderTest
import garden.hereabouts.app.services.phoneAllowsUnderTest
import garden.hereabouts.core.Plant
import garden.hereabouts.core.Reminder
import garden.hereabouts.core.Reminders
import java.time.ZonedDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Whether the phone lets the app send notifications. */
enum class NotificationPermission { NotAsked, Allowed, Denied }

/** What the reminders are planned from. */
class ReminderPlan(
    val care: CareTables,
    val plants: List<Plant>,
    /** The chosen county's five-digit code, or null when none is chosen. */
    val countyFips: String?,
    val saved: List<String>,
    /** Settings' switch. */
    val on: Boolean,
    val permission: NotificationPermission?,
) {
    /**
     * The reminders of the next eight Saturdays after `now` that have jobs, the website's own. None when the switch
     * is off, no county is chosen, or the phone does not let the app post notifications.
     */
    fun weekly(now: ZonedDateTime): List<Reminder> {
        if (!on || permission != NotificationPermission.Allowed) return emptyList()
        val county = countyFips ?: return emptyList()
        return Reminders.weekly(care.rules, care.anchors[county], saved, plants, now)
    }
}

/**
 * The phone's question about notifications. MainActivity registers the question when it is created and passes the
 * person's answer here; the reminders wait for that answer.
 */
object NotificationAsk {
    @Volatile
    private var launcher: ActivityResultLauncher<String>? = null

    @Volatile
    private var answer: CompletableDeferred<Boolean>? = null

    /** The screen that can show the question now. */
    fun attach(launcher: ActivityResultLauncher<String>) {
        this.launcher = launcher
    }

    /** Forgets `launcher` if it is the one attached, when its screen goes away. */
    fun detach(launcher: ActivityResultLauncher<String>) {
        if (this.launcher === launcher) this.launcher = null
    }

    /** The person answered. */
    fun answered(granted: Boolean) {
        answer?.complete(granted)
    }

    /** Shows the phone's question and waits for the answer. Null when no screen can show it. */
    internal suspend fun ask(): Boolean? {
        val deferred = CompletableDeferred<Boolean>()
        val shown = withContext(Dispatchers.Main) {
            val launcher = launcher ?: return@withContext false
            answer = deferred
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            true
        }
        return if (shown) deferred.await() else null
    }
}

class ReminderScheduler(context: Context) {
    private val context = context.applicationContext
    private val asking = Mutex()

    /** What the phone says now about the app's notifications. */
    suspend fun permission(): NotificationPermission = permission(context)

    /**
     * Asks the phone whether the app may post notifications, if it has never asked, and returns the answer. Null when
     * it asked before, the phone already allows them, or there is nothing to ask.
     */
    suspend fun askIfNotAsked(): Boolean? = asking.withLock {
        if (permission() != NotificationPermission.NotAsked) null else ask()
    }

    /** Asks again after a refusal, when the switch in Settings is turned on. Null when there is nothing to ask. */
    suspend fun askAgain(): Boolean? = asking.withLock {
        if (permission() != NotificationPermission.Denied) null else ask()
    }

    private suspend fun ask(): Boolean? {
        if (Build.VERSION.SDK_INT < 33) return null
        // Under test the answer comes from the test instead of the phone's question, so nothing covers the screen.
        val granted = askedUnderTest() ?: NotificationAsk.ask() ?: return null
        context.keptStore.edit { it[askedKey] = true }
        return granted
    }

    /** Arms `reminder`, replacing the one waiting, or cancels the one waiting when null. */
    fun arm(reminder: Reminder?) = arm(context, reminder)

    companion object {
        /** The alarm's action, which only ReminderReceiver takes. */
        const val REMIND = "garden.hereabouts.app.REMIND"

        /** Whether the app has shown the phone's question about notifications. Kept with the app's other values. */
        val askedKey = booleanPreferencesKey("hereabouts.notificationsAsked")

        /** The alarm's PendingIntent: made when `create` is true, otherwise the one waiting, or null when none is. */
        fun pendingAlarm(context: Context, create: Boolean): PendingIntent? {
            val intent = Intent(context, ReminderReceiver::class.java).setAction(REMIND)
            val flags = PendingIntent.FLAG_IMMUTABLE or
                if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
            return PendingIntent.getBroadcast(context, 0, intent, flags)
        }

        /** Arms `reminder` with the phone's alarm service, or cancels the one waiting when null. */
        fun arm(context: Context, reminder: Reminder?) {
            val alarms = context.getSystemService(AlarmManager::class.java)
            if (reminder == null) {
                pendingAlarm(context, create = false)?.let { waiting ->
                    alarms.cancel(waiting)
                    waiting.cancel()
                }
            } else {
                val alarm = pendingAlarm(context, create = true) ?: return
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.at.toInstant().toEpochMilli(), alarm)
            }
            armedUnderTest(reminder)
        }

        /** What the phone says now about the app's notifications. */
        suspend fun permission(context: Context): NotificationPermission {
            // Under test the phone's setting comes from the test; whether the app asked before is still read below.
            val underTest = phoneAllowsUnderTest()
            if (Build.VERSION.SDK_INT < 33) {
                val on = underTest ?: context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
                return if (on) NotificationPermission.Allowed else NotificationPermission.Denied
            }
            val granted = underTest
                ?: (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            if (granted) return NotificationPermission.Allowed
            val asked = context.keptStore.data.first()[askedKey] == true
            return if (asked) NotificationPermission.Denied else NotificationPermission.NotAsked
        }
    }
}
