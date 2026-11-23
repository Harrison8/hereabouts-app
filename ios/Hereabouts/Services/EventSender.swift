// Sends the app's events to the website's analytics, the way the website does: only the events and properties on the
// website's own list (Analytics.clean), each once per launch where the website sends it once per visit, and every
// event marked platform "ios". Events wait in a file on the phone until there is signal, at most 500 of them (the
// oldest go first). They are posted in PostHog's batch form to the website's /ingest/batch/, which passes them on to
// PostHog, with the website's public key, a random id made once on this phone, and no person profile.
import Foundation
import HereaboutsCore

actor EventSender {
    /// The most events kept waiting. When more arrive, the oldest are dropped.
    static let maxQueued = 500
    /// Where the random analytics id is kept (UserDefaults).
    static let distinctIdKey = "hereabouts.analyticsId"

    /// One event as PostHog's batch endpoint takes it. `uuid` lets PostHog drop a copy if a batch is sent twice.
    struct QueuedEvent: Codable, Equatable, Sendable {
        let uuid: String
        let event: String
        let properties: [String: EventValue]
        let timestamp: String
    }

    private struct Batch: Encodable {
        let apiKey: String
        let batch: [QueuedEvent]

        enum CodingKeys: String, CodingKey {
            case apiKey = "api_key"
            case batch
        }
    }

    private let endpoint: URL?
    private let apiKey: String
    private let distinctId: String
    private let queueFile: URL
    private let session: URLSession
    private let isOnline: @Sendable () async -> Bool
    private var queue: [QueuedEvent]
    /// The once-per-launch events already sent this launch, by event, plant and seller.
    private var sentThisLaunch: Set<String> = []
    private var posting = false

    /// `site` nil sends nothing; events still wait in `queueFile`.
    init(site: URL?, apiKey: String, distinctId: String, queueFile: URL, session: URLSession = .shared,
         isOnline: @escaping @Sendable () async -> Bool) {
        endpoint = site.flatMap { URL(string: "ingest/batch/", relativeTo: $0)?.absoluteURL }
        self.apiKey = apiKey
        self.distinctId = distinctId
        self.queueFile = queueFile
        self.session = session
        self.isOnline = isOnline
        queue = (try? JSONDecoder().decode([QueuedEvent].self, from: Data(contentsOf: queueFile))) ?? []
    }

    /// The events waiting to be sent, oldest first.
    var queued: [QueuedEvent] { queue }

    /// Queues the event, if the website's list allows it, and sends the queue when there is signal.
    func send(_ event: String, _ props: [String: EventValue]) async {
        guard enqueue(event, props) else { return }
        await flush()
    }

    /// Posts the waiting events, when there is signal and nothing is being posted already. Events the server took,
    /// or refused as malformed, leave the queue; on no answer or a server error they wait for the next try.
    func flush() async {
        guard let endpoint, !posting else { return }
        // Without the website's key PostHog would refuse every event, so they wait for a build that has it.
        if apiKey.isEmpty { return }
        posting = true
        defer { posting = false }
        while !queue.isEmpty, await isOnline() {
            let batch = queue
            guard let body = try? JSONEncoder().encode(Batch(apiKey: apiKey, batch: batch)) else { return }
            var request = URLRequest(url: endpoint, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = body
            guard let answer = try? await session.data(for: request),
                  let status = (answer.1 as? HTTPURLResponse)?.statusCode else { return }
            // 408 and 429 mean "try later". Any other 4xx means PostHog will never take these events, so retrying
            // them would only block the ones behind.
            let refused = (400..<500).contains(status) && status != 408 && status != 429
            guard (200..<300).contains(status) || refused else { return }
            // Events queued while the batch was on its way stay; only the ones in the batch leave.
            let sent = Set(batch.map(\.uuid))
            queue.removeAll { sent.contains($0.uuid) }
            save()
        }
    }

    /// Adds the cleaned event to the queue. False when it is not to be sent: not on the list, already sent this
    /// launch, or holding a number JSON cannot hold (NaN or infinity), which drops that one event and nothing else.
    private func enqueue(_ event: String, _ props: [String: EventValue]) -> Bool {
        guard var properties = Analytics.clean(event: event, props: props) else { return false }
        let onceKey = Analytics.onceKeys.contains(event)
            ? [event, Self.text(properties["plant"]), Self.text(properties["vendor"])].joined(separator: ".") : nil
        if let onceKey, sentThisLaunch.contains(onceKey) { return false }
        properties["platform"] = .string("ios")
        properties["distinct_id"] = .string(distinctId)
        properties["$process_person_profile"] = .bool(false)
        let item = QueuedEvent(uuid: UUID().uuidString.lowercased(), event: event, properties: properties,
                               timestamp: Date.now.formatted(.iso8601))
        guard (try? JSONEncoder().encode(item)) != nil else { return false }
        if let onceKey { sentThisLaunch.insert(onceKey) }
        queue.append(item)
        if queue.count > Self.maxQueued { queue.removeFirst(queue.count - Self.maxQueued) }
        save()
        return true
    }

    private func save() {
        do {
            try FileManager.default.createDirectory(at: queueFile.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(queue).write(to: queueFile, options: .atomic)
        } catch {
            // The events still go out from memory this launch; only a restart before then loses them.
        }
    }

    /// A property as the website's once-per-visit key writes it: the text, or nothing when it is missing.
    private static func text(_ value: EventValue?) -> String {
        switch value {
        case .string(let s)?: s
        case .number(let n)?: n == n.rounded() && abs(n) < 1e15 ? String(Int64(n)) : String(n)
        case .bool(let b)?: String(b)
        case nil: ""
        }
    }

    /// The random analytics id for this phone, made the first time it is needed and kept in `defaults`.
    static func distinctId(in defaults: UserDefaults) -> String {
        if let id = defaults.string(forKey: distinctIdKey), !id.isEmpty { return id }
        let id = UUID().uuidString.lowercased()
        defaults.set(id, forKey: distinctIdKey)
        return id
    }
}
