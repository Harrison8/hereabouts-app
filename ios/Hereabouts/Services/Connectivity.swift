// Whether the phone has signal, from the system's own network path. Screens read `isOnline` to say when seller links
// need a connection; the app uses it to send waiting events and check for newer data as soon as signal comes back.
//
// In a Debug build only, the launch argument `-uiTestOffline` makes it say there is no signal, without asking the
// system, so a screen test can show the no-signal banner. A Release build never reads it.
import Foundation
import Network
import Observation

@MainActor
@Observable
final class Connectivity {
    /// False until the system first reports a usable network path.
    private(set) var isOnline = false
    /// True only once the system has reported that there is no usable network path. Before its first report this is
    /// false too, so the no-signal banner never flashes at launch.
    private(set) var isOffline = false

    @ObservationIgnored private let monitor = NWPathMonitor()

    init(arguments: [String] = ProcessInfo.processInfo.arguments) {
        #if DEBUG
        if arguments.contains("-uiTestOffline") {
            isOffline = true
            return
        }
        #endif
        monitor.pathUpdateHandler = { [self] path in
            let online = path.status == .satisfied
            MainActor.assumeIsolated {
                if isOnline != online { isOnline = online }
                if isOffline == online { isOffline = !online }
            }
        }
        // Updates arrive on the main queue, where the screens read them.
        monitor.start(queue: .main)
    }
}
