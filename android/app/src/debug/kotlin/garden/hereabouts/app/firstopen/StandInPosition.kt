// Debug builds only. Under test, "Use my location" gives the position the test chose instead of asking the phone's
// location service, once the app's own permission check has passed. A Release build has a twin of this file that never
// gives one.
package garden.hereabouts.app.firstopen

import garden.hereabouts.app.services.TestSwitches

/** The test's stand-in for the phone's position, as latitude and longitude, or null to ask the phone. */
internal fun standInPosition(): Pair<Double, Double>? = if (TestSwitches.underTest) TestSwitches.position else null
