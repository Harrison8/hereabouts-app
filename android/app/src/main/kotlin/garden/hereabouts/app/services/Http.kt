// The app's two kinds of request, with Java's own HttpURLConnection (no networking library): a GET for data and
// photos, and a POST of JSON for events. Each waits at most 30 seconds and never uses a cached answer. They block, so
// callers run them on Dispatchers.IO.
package garden.hereabouts.app.services

import java.net.HttpURLConnection
import java.net.URL

internal object Http {
    /** How long a request waits to connect, and then for each part of the answer. */
    const val TIMEOUT_MS = 30_000

    /** An answer: its HTTP status, and its body when the status is 200. */
    class Answer(val status: Int, val body: ByteArray)

    /** The address `path` names on `site` ("app-data/v1/version.json" on "https://hereabouts.garden"). */
    fun address(site: String, path: String): URL? = try {
        URL(URL(site), path)
    } catch (e: Exception) {
        null
    }

    /** GETs `url`. Null when no answer came: no signal on the way, a timeout, or an address that does not work. */
    fun get(url: URL): Answer? {
        val connection = open(url) ?: return null
        return try {
            connection.requestMethod = "GET"
            val status = connection.responseCode
            val body = if (status == 200) connection.inputStream.use { it.readBytes() } else ByteArray(0)
            Answer(status, body)
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /** POSTs `json` to `url`. The answer's HTTP status, or null when no answer came. */
    fun postJson(url: URL, json: ByteArray): Int? {
        val connection = open(url) ?: return null
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setFixedLengthStreamingMode(json.size)
            connection.outputStream.use { it.write(json) }
            val status = connection.responseCode
            // Read the answer to its end, so the connection is left clean.
            try {
                val answer = if (status < 400) connection.inputStream else connection.errorStream
                answer?.use { it.readBytes() }
            } catch (e: Exception) {
                // The status is all that matters.
            }
            status
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: URL): HttpURLConnection? = try {
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
        }
    } catch (e: Exception) {
        null
    }
}
