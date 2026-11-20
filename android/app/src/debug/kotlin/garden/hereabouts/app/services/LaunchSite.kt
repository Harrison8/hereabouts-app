// Debug builds only. The tests run inside the app's own process, so they choose the services' address here before
// they open the app. The test runner sets underTest, and with it the services reach no address at all unless a test
// names one, such as a stand-in server on 127.0.0.1. So no test ever fetches the real data or sends a real event.
// A Release build has a twin of this file that always gives the website and has no switches.
package garden.hereabouts.app.services

object TestSwitches {
    /** True while the instrumented tests run. */
    @Volatile
    var underTest: Boolean = false

    /** Under test, the address the services talk to (an http or https address), or null for none. */
    @Volatile
    var site: String? = null
}

/** The address the services talk to in this build: the website, or under test what the tests chose. */
internal fun launchSite(): String? {
    if (!TestSwitches.underTest) return WEBSITE
    val site = TestSwitches.site ?: return null
    return site.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}
