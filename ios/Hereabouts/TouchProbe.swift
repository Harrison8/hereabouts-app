// Temporary, for finding why the reminders switch in Settings sometimes ignores a tap after the app is left and
// reopened. In a Debug build launched by a screen test with `-uiTestTouchLog`, the app writes to the phone's log, each
// line starting "HBDIAG":
// - each time it comes to the front or goes to the back;
// - each finger that touches the screen, as it goes down and as it lifts: where, which view it landed on, the time the
//   phone stamped on the touch and the time the app was handed it, both in seconds since the phone started;
// - each time the app's main thread was busy for more than 50 milliseconds, with when it started and how long it took;
// - each change of the reminders switch's setting.
// The touch clock it adds to the window never recognizes anything, never cancels or delays a touch, and fails as each
// finger lifts, so it takes no part in a touch. In every other build and launch this does nothing.
import SwiftUI
import UIKit
import UIKit.UIGestureRecognizerSubclass

extension View {
    func diagnosingTouchesForTests() -> some View {
        modifier(TouchDiagnosis())
    }
}

private struct TouchDiagnosis: ViewModifier {
    @Environment(\.scenePhase) private var scenePhase
    @Environment(AppModel.self) private var model

    func body(content: Content) -> some View {
        if SwipeDiagnosis.log {
            content
                .onChange(of: scenePhase, initial: true) { _, phase in
                    NSLog("%@", "HBDIAG scene \(phase) app=\(UIApplication.shared.applicationState.rawValue)")
                    if phase == .active {
                        TouchClock.install()
                        MainThreadWatch.start()
                    }
                }
                .onChange(of: model.remindersOn) { _, on in
                    NSLog("%@", "HBDIAG remindersOn=\(on)")
                }
        } else {
            content
        }
    }
}

/// Writes when each touch was stamped by the phone and when the app was handed it.
private final class TouchClock: UIGestureRecognizer {
    @MainActor private static var watched = Set<ObjectIdentifier>()

    @MainActor static func install() {
        for case let scene as UIWindowScene in UIApplication.shared.connectedScenes {
            for window in scene.windows where !watched.contains(ObjectIdentifier(window)) {
                watched.insert(ObjectIdentifier(window))
                let clock = TouchClock(target: nil, action: nil)
                clock.cancelsTouchesInView = false
                clock.delaysTouchesBegan = false
                clock.delaysTouchesEnded = false
                window.addGestureRecognizer(clock)
                NSLog("%@", "HBDIAG watching window \(type(of: window)) \(window.frame)")
            }
        }
    }

    private func write(_ phase: String, _ touches: Set<UITouch>) {
        let handed = ProcessInfo.processInfo.systemUptime
        for touch in touches {
            let at = touch.location(in: nil)
            let landed = touch.view.map { "\(type(of: $0)) \($0.frame)" } ?? "none"
            NSLog("%@", "HBDIAG \(phase) at=\(at) stamped=\(String(format: "%.4f", touch.timestamp))"
                  + " handed=\(String(format: "%.4f", handed)) late=\(Int((handed - touch.timestamp) * 1000))ms"
                  + " view=\(landed)")
        }
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        write("down", touches)
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        write("up", touches)
        state = .failed
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        write("cancelled", touches)
        state = .failed
    }
}

/// Every 20 milliseconds a background queue asks the main thread to note the time; when the main thread answers more
/// than 50 milliseconds late, it was busy, and that is written down.
private enum MainThreadWatch {
    @MainActor private static var started = false

    @MainActor static func start() {
        guard !started else { return }
        started = true
        ping()
    }

    private static func ping() {
        DispatchQueue.global(qos: .userInteractive).asyncAfter(deadline: .now() + .milliseconds(20)) {
            let asked = ProcessInfo.processInfo.systemUptime
            DispatchQueue.main.async {
                let answered = ProcessInfo.processInfo.systemUptime
                let late = Int((answered - asked) * 1000)
                if late > 50 {
                    NSLog("%@", "HBDIAG busy from=\(String(format: "%.4f", asked)) for=\(late)ms")
                }
                ping()
            }
        }
    }
}
