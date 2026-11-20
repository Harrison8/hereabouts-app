// A small web server for the tests, standing in for the website. It listens on 127.0.0.1 only, on a port the system
// picks, answers each path with what the test set (404 for anything else) and records every request, so a test can
// read the events the app posted. It runs inside the test process, which is the app's process, so the tests never
// reach any outside host.
package garden.hereabouts.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class StubServer {
    class Request(val method: String, val path: String, val body: ByteArray)

    private class Route(val status: Int, val body: ByteArray)

    private val lock = Any()
    private val routes = mutableMapOf<String, Route>()
    private val requests = mutableListOf<Request>()
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool()

    /** The address to hand the app, like "http://127.0.0.1:52811". */
    val baseUrl: String = "http://127.0.0.1:${socket.localPort}"

    init {
        Thread({ acceptLoop() }, "stub-server").apply { isDaemon = true }.start()
    }

    /** Stops listening. Requests already being answered finish. */
    fun stop() {
        runCatching { socket.close() }
        workers.shutdown()
    }

    /** Answers `path` with `body` and `status` from now on. */
    fun serve(path: String, body: ByteArray, status: Int = 200) {
        synchronized(lock) { routes[path] = Route(status, body) }
    }

    /** Answers every path with 404 from now on. */
    fun serveNothing() {
        synchronized(lock) { routes.clear() }
    }

    /** Every request so far, oldest first. */
    val received: List<Request> get() = synchronized(lock) { requests.toList() }

    /** The paths asked for so far, for failure messages. */
    val receivedPaths: List<String> get() = received.map { "${it.method} ${it.path}" }

    /** One event from a posted batch, with the batch's api_key. */
    class PostedEvent(val apiKey: String, val event: JsonObject) {
        val name: String get() = (event["event"] as? JsonPrimitive)?.content ?: "?"
        val properties: JsonObject get() = event["properties"] as? JsonObject ?: JsonObject(emptyMap())
    }

    /** Every event in every batch posted to /ingest/batch/ so far, oldest first. */
    val postedEvents: List<PostedEvent>
        get() = received.filter { it.method == "POST" && it.path == "/ingest/batch/" }.flatMap { request ->
            val o = runCatching { Json.parseToJsonElement(request.body.decodeToString()) as? JsonObject }.getOrNull()
                ?: return@flatMap emptyList()
            val key = (o["api_key"] as? JsonPrimitive)?.content.orEmpty()
            (o["batch"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { e -> PostedEvent(key, e) } }
        }

    private fun acceptLoop() {
        while (true) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                return
            }
            runCatching { workers.execute { handle(client) } }.onFailure { runCatching { client.close() } }
        }
    }

    private fun handle(client: Socket) {
        client.use {
            client.soTimeout = 10_000
            val input = client.getInputStream()
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val parts = lines[0].split(" ")
            if (parts.size < 2) return
            var length = 0
            for (line in lines.drop(1)) {
                val i = line.indexOf(':')
                if (i > 0 && line.substring(0, i).trim().equals("content-length", ignoreCase = true)) {
                    length = line.substring(i + 1).trim().toIntOrNull() ?: 0
                }
            }
            val body = readBody(input, length)
            val request = Request(parts[0], parts[1], body)
            val route = synchronized(lock) {
                requests.add(request)
                routes[request.path] ?: Route(404, "Not found".toByteArray())
            }
            val reason = if (route.status == 200) "OK" else "Other"
            val header = "HTTP/1.1 ${route.status} $reason\r\nContent-Length: ${route.body.size}\r\n" +
                "Connection: close\r\nContent-Type: application/octet-stream\r\n\r\n"
            val output = client.getOutputStream()
            output.write(header.toByteArray(Charsets.US_ASCII))
            output.write(route.body)
            output.flush()
        }
    }

    /** The request line and headers, up to the blank line, or null if the connection ended first. */
    private fun readHead(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            bytes.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        val text = bytes.toByteArray().decodeToString()
        return text.substring(0, text.length - 4)
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return if (read == length) body else body.copyOf(read)
    }

    companion object {
        /** Waits up to `seconds` for `condition`, checking every 200 ms. Returns whether it held. */
        fun waitFor(seconds: Int, condition: () -> Boolean): Boolean {
            val deadline = System.currentTimeMillis() + seconds * 1000L
            while (System.currentTimeMillis() < deadline) {
                if (condition()) return true
                Thread.sleep(200)
            }
            return condition()
        }
    }
}
