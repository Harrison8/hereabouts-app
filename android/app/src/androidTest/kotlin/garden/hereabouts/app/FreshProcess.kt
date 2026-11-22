// Before each test: the app forgets what its process holds (the loaded data, the event sender, the data check), the
// clock, the phone's answer about notifications and the opening's switches go back to their defaults, and no reminder
// is left armed. So each test opens the app as if it had just been started, whatever the test before it left behind.
package garden.hereabouts.app

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import garden.hereabouts.app.reminders.ReminderScheduler
import garden.hereabouts.app.services.TestSwitches
import org.junit.runner.Description
import org.junit.runner.notification.RunListener

class FreshProcess : RunListener() {
    override fun testStarted(description: Description) {
        AppProcess.forget()
        TestSwitches.now = null
        TestSwitches.notifications = null
        TestSwitches.asks.set(0)
        val context = ApplicationProvider.getApplicationContext<Context>()
        ReminderScheduler.pendingAlarm(context, create = false)?.let { alarm ->
            context.getSystemService(AlarmManager::class.java).cancel(alarm)
            alarm.cancel()
        }
        TestSwitches.armed.clear()
        TestSwitches.animations = null
        TestSwitches.holdFirstFrame = false
        TestSwitches.firstFrameAfterMs = null
    }
}
