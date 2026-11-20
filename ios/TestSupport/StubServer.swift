// A small web server for the tests, standing in for the website. It listens on 127.0.0.1 only, on a port the system
// picks, answers each path with what the test set (404 for anything else) and records every request, so a test can
// read the events the app posted. It runs inside the test process, so the tests never reach any outside host.
import Foundation

final class StubServer: @unchecked Sendable {
    struct Request: Sendable {
        let method: String
        let path: String
        let body: Data
    }

    private let lock = NSLock()
    private var routes: [String: (status: Int, body: Data)] = [:]
    private var requests: [Request] = []
    private let socketFD: Int32
    let port: UInt16

    /// The address to hand the app, like "http://127.0.0.1:52811".
    var baseURL: String { "http://127.0.0.1:\(port)" }

    init() throws {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { throw StubError.socket("socket", errno) }
        var yes: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = 0
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard bound == 0, listen(fd, 32) == 0 else {
            close(fd)
            throw StubError.socket("bind or listen", errno)
        }
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &length) }
        }
        guard named == 0 else {
            close(fd)
            throw StubError.socket("getsockname", errno)
        }
        socketFD = fd
        port = UInt16(bigEndian: address.sin_port)
        let thread = Thread { [self] in acceptLoop() }
        thread.start()
    }

    /// Stops listening. Requests already being answered finish.
    func stop() {
        shutdown(socketFD, SHUT_RDWR)
        close(socketFD)
    }

    /// Answers `path` with `body` and `status` from now on.
    func serve(_ path: String, _ body: Data, status: Int = 200) {
        lock.withLock { routes[path] = (status, body) }
    }

    /// Answers every path with 404 from now on.
    func serveNothing() {
        lock.withLock { routes = [:] }
    }

    /// Every request so far, oldest first.
    var received: [Request] { lock.withLock { requests } }

    /// Every event in every batch posted to /ingest/batch/ so far, as JSON objects, with the batch's api_key.
    var postedEvents: [(apiKey: String, event: [String: Any])] {
        received.filter { $0.method == "POST" && $0.path == "/ingest/batch/" }.flatMap { request in
            guard let object = try? JSONSerialization.jsonObject(with: request.body) as? [String: Any],
                  let batch = object["batch"] as? [[String: Any]] else { return [(apiKey: String, event: [String: Any])]() }
            let key = object["api_key"] as? String ?? ""
            return batch.map { (apiKey: key, event: $0) }
        }
    }

    // MARK: Serving

    private func acceptLoop() {
        while true {
            let client = accept(socketFD, nil, nil)
            if client < 0 {
                if errno == EINTR { continue }
                return
            }
            DispatchQueue.global().async { [self] in handle(client) }
        }
    }

    private func handle(_ client: Int32) {
        defer { close(client) }
        var yes: Int32 = 1
        setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, &yes, socklen_t(MemoryLayout<Int32>.size))
        var timeout = timeval(tv_sec: 10, tv_usec: 0)
        setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))

        var buffer = Data()
        let headerEnd = Data("\r\n\r\n".utf8)
        var chunk = [UInt8](repeating: 0, count: 65536)
        func readMore() -> Bool {
            let n = recv(client, &chunk, chunk.count, 0)
            guard n > 0 else { return false }
            buffer.append(contentsOf: chunk[0..<n])
            return true
        }
        while buffer.range(of: headerEnd) == nil {
            guard readMore() else { return }
        }
        guard let end = buffer.range(of: headerEnd),
              let head = String(data: buffer[buffer.startIndex..<end.lowerBound], encoding: .utf8) else { return }
        let lines = head.components(separatedBy: "\r\n")
        let parts = lines[0].split(separator: " ")
        guard parts.count >= 2 else { return }
        var contentLength = 0
        for line in lines.dropFirst() {
            let pair = line.split(separator: ":", maxSplits: 1)
            if pair.count == 2, pair[0].trimmingCharacters(in: .whitespaces).lowercased() == "content-length" {
                contentLength = Int(pair[1].trimmingCharacters(in: .whitespaces)) ?? 0
            }
        }
        while buffer.count - (end.upperBound - buffer.startIndex) < contentLength {
            guard readMore() else { break }
        }
        let bodyStart = end.upperBound
        let body = Data(buffer[bodyStart..<min(buffer.endIndex, bodyStart + contentLength)])
        let path = String(parts[1])
        let request = Request(method: String(parts[0]), path: path, body: body)
        let answer = lock.withLock { () -> (status: Int, body: Data) in
            requests.append(request)
            return routes[path] ?? (404, Data("Not found".utf8))
        }

        var response = Data("HTTP/1.1 \(answer.status) \(answer.status == 200 ? "OK" : "Other")\r\n".utf8)
        response.append(Data("Content-Length: \(answer.body.count)\r\nConnection: close\r\nContent-Type: application/octet-stream\r\n\r\n".utf8))
        response.append(answer.body)
        response.withUnsafeBytes { raw in
            var sent = 0
            while sent < raw.count {
                let n = send(client, raw.baseAddress!.advanced(by: sent), raw.count - sent, 0)
                if n <= 0 { return }
                sent += n
            }
        }
    }

    enum StubError: Error {
        case socket(String, Int32)
    }
}

extension StubServer {
    /// Waits up to `timeout` seconds for `condition`, letting the run loop turn meanwhile. Returns whether it held.
    static func wait(_ timeout: TimeInterval, until condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }
        return condition()
    }
}
