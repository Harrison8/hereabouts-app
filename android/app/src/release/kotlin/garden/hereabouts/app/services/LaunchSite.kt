// A Release build always talks to the website. The Debug build's twin of this file lets the tests choose instead.
package garden.hereabouts.app.services

/** The address the services talk to in this build. */
internal fun launchSite(): String? = WEBSITE
