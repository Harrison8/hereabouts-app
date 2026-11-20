// Plant data sets for the tests: a small newer set the app should take, and a broken one it must ignore. Each set has
// a correct version.json (schema 1, a later date, the SHA-256 of its three files), so the broken one gets past the
// date and fingerprint checks and fails only when its plants are read. The same sets as the iPhone app's tests.
package garden.hereabouts.app

import java.security.MessageDigest

class StubData(val version: ByteArray, val plants: ByteArray, val vendors: ByteArray, val regions: ByteArray) {
    /** The four files by the path the app asks for. */
    val files: List<Pair<String, ByteArray>>
        get() = listOf(
            "/app-data/v1/version.json" to version,
            "/app-data/v1/plants.json" to plants,
            "/app-data/v1/vendors.json" to vendors,
            "/app-data/v1/plant-regions.json" to regions,
        )

    /** The four files by their names, as the updater keeps them. */
    val byName: Map<String, ByteArray>
        get() = files.associate { (path, body) -> path.substringAfterLast('/') to body }

    /** Serves the set's four files from `server`. */
    fun serve(server: StubServer) {
        for ((path, body) in files) server.serve(path, body)
    }

    companion object {
        /** A set dated far after the data the app ships with, so it always counts as newer. */
        const val LATER_DATE = "2099-01-01T00:00:00Z"

        /** Two plants the app does not ship with, named so a screen can show which set is in use. */
        val good: StubData = make(
            """
            [{"id":"test-plant-one","common":"Test Plant One","sci":"Testus unus","category":"perennial",
              "vendors":[{"name":"Test Seller","shipsTo":["VA"]}]},
             {"id":"test-plant-two","common":"Test Plant Two","sci":"Testus duo","category":"shrub",
              "phasePhotos":{"green":{"url":"images/test-plant-two-green.jpg","alt":"Test","credit":"Test"}}}]
            """.trimIndent(),
        )

        /**
         * One plant whose seller entry gives `shipsTo` as text instead of a list. The app cannot read that plants.json,
         * so the whole set must be refused and the app must stay on the data it had.
         */
        val unreadable: StubData = make(
            """
            [{"id":"bad-data-plant","common":"Bad Data Plant","sci":"Malus datus","category":"perennial",
              "vendors":[{"name":"Test Seller","shipsTo":"VA"}]}]
            """.trimIndent(),
        )

        fun make(plants: String): StubData {
            val plantsData = plants.toByteArray(Charsets.UTF_8)
            val vendors = """{"Test Seller":{"name":"Test Seller","shipsTo":"contiguous-48"}}""".toByteArray(Charsets.UTF_8)
            val regions = (
                """{"schema":1,"plants":{"test-plant-one":{"eco":["45"],"intro":[]},"test-plant-two":{"eco":["45"],"intro":[]},""" +
                    """"bad-data-plant":{"eco":["45"],"intro":[]}},"served":["45"]}"""
                ).toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(plantsData + vendors + regions)
            val hash = digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            val version = """{"schema":1,"dataDate":"$LATER_DATE","dataHash":"$hash"}""".toByteArray(Charsets.UTF_8)
            return StubData(version, plantsData, vendors, regions)
        }
    }
}
