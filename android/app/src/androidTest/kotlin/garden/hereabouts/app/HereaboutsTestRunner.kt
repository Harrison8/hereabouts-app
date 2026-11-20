// Runs the instrumented tests with the app's network services pointed away from the website. With no address given
// they reach nothing at all; the instrumentation argument hereaboutsBaseURL names one for every test, and a test that
// starts its own stand-in server on 127.0.0.1 names that server instead.
package garden.hereabouts.app

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import garden.hereabouts.app.services.TestSwitches

class HereaboutsTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        TestSwitches.underTest = true
        TestSwitches.site = arguments?.getString(BASE_URL_ARGUMENT)
        super.onCreate(arguments)
    }

    companion object {
        /** The instrumentation argument that names the services' address for the whole run. */
        const val BASE_URL_ARGUMENT = "hereaboutsBaseURL"
    }
}
