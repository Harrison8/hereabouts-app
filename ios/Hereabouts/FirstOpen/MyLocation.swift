// "Use my location": asks the phone for its position once. Permission is asked only when the person taps the button,
// and only "while using the app". The position stays on the phone: the first-open screen turns it into the county of
// the nearest ZIP code, and only that ZIP code's own point, rounded, is ever kept. Nothing here sends anything.
import CoreLocation

@MainActor
final class MyLocation: NSObject, CLLocationManagerDelegate {
    enum Outcome: Equatable, Sendable {
        case position(lat: Double, lon: Double)
        /// The person said no, now or earlier, or the phone does not allow it.
        case denied
        /// The phone could not find where it is.
        case unavailable
    }

    private let manager = CLLocationManager()
    /// Everyone waiting for the one position under way.
    private var waiting: [CheckedContinuation<Outcome, Never>] = []
    private var askedPermission = false

    override init() {
        super.init()
        manager.delegate = self
        // A county needs no better than about a kilometre, which also comes back fastest.
        manager.desiredAccuracy = kCLLocationAccuracyKilometer
    }

    /// One position, or why there is none. A second call while one is under way waits for the same answer.
    func position() async -> Outcome {
        await withCheckedContinuation { continuation in
            waiting.append(continuation)
            guard waiting.count == 1 else { return }
            switch manager.authorizationStatus {
            case .notDetermined:
                askedPermission = true
                manager.requestWhenInUseAuthorization()
            case .authorizedWhenInUse, .authorizedAlways:
                manager.requestLocation()
            default:
                finish(.denied)
            }
        }
    }

    private func finish(_ outcome: Outcome) {
        askedPermission = false
        let continuations = waiting
        waiting = []
        for continuation in continuations { continuation.resume(returning: outcome) }
    }

    private func permissionChanged(_ status: CLAuthorizationStatus) {
        // The phone also reports the permission once when the manager starts; only an answer to our question counts.
        guard !waiting.isEmpty, askedPermission, status != .notDetermined else { return }
        askedPermission = false
        switch status {
        case .authorizedWhenInUse, .authorizedAlways:
            manager.requestLocation()
        default:
            finish(.denied)
        }
    }

    // MARK: CLLocationManagerDelegate. The phone calls these on the main thread, where the manager was made.

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        MainActor.assumeIsolated { permissionChanged(status) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let coordinate = locations.last?.coordinate else { return }
        let lat = coordinate.latitude
        let lon = coordinate.longitude
        MainActor.assumeIsolated { finish(.position(lat: lat, lon: lon)) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let denied = (error as? CLError)?.code == .denied
        MainActor.assumeIsolated { finish(denied ? .denied : .unavailable) }
    }
}
