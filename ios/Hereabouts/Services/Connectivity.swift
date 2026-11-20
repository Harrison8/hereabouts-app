// Whether the phone has signal, from the system's own network path. Screens read `isOnline` to say when seller links
// need a connection; the app uses it to send waiting events and check for newer data as soon as signal comes back.
import Network
import Observation

@MainActor
@Observable
final class Connectivity {
    /// False until the system first reports a usable network path.
    private(set) var isOnline = false

    @ObservationIgnored private let monitor = NWPathMonitor()

    init() {
        monitor.pathUpdateHandler = { [self] path in
            let online = path.status == .satisfied
            MainActor.assumeIsolated {
                if isOnline != online { isOnline = online }
            }
        }
        // Updates arrive on the main queue, where the screens read them.
        monitor.start(queue: .main)
    }
}
