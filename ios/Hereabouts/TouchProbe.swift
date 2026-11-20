// Temporary, for finding why the reminders switch in Settings sometimes ignores a tap after the app is left and
// reopened. In a Debug build launched by a screen test with `-uiTestTouchLog`, the app writes to the phone's log, each
// line starting "HBDIAG":
// - each time it comes to the front or goes to the back;
// - each finger that touches the screen: where, which view it landed on, and every gesture recognizer from that view
//   up to the window, with whether it is switched on and its state, then the same states 0.4 seconds later;
// - each change of the reminders switch's setting.
// The watcher it adds to the window refuses every touch, so it never takes part in one. In every other build and
// launch this does nothing.
import SwiftUI
import UIKit

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
                    if phase == .active { TouchProbe.shared.install() }
                }
                .onChange(of: model.remindersOn) { _, on in
                    NSLog("%@", "HBDIAG remindersOn=\(on)")
                }
        } else {
            content
        }
    }
}

@MainActor
private final class TouchProbe: NSObject, UIGestureRecognizerDelegate {
    static let shared = TouchProbe()
    private var watched = Set<ObjectIdentifier>()

    func install() {
        for case let scene as UIWindowScene in UIApplication.shared.connectedScenes {
            for window in scene.windows where !watched.contains(ObjectIdentifier(window)) {
                watched.insert(ObjectIdentifier(window))
                let watcher = UIGestureRecognizer(target: nil, action: nil)
                watcher.cancelsTouchesInView = false
                watcher.delaysTouchesBegan = false
                watcher.delaysTouchesEnded = false
                watcher.delegate = self
                window.addGestureRecognizer(watcher)
                NSLog("%@", "HBDIAG watching window \(type(of: window)) \(window.frame)")
            }
        }
    }

    /// Writes the touch and the recognizers it meets, then refuses it.
    func gestureRecognizer(_ watcher: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        var recognizers: [UIGestureRecognizer] = []
        var view = touch.view
        while let current = view {
            recognizers += (current.gestureRecognizers ?? []).filter { $0 !== watcher }
            view = current.superview
        }
        let at = touch.location(in: nil)
        let landed = touch.view.map { "\(type(of: $0)) \($0.frame)" } ?? "none"
        NSLog("%@", "HBDIAG touch at=\(at) view=\(landed) app=\(UIApplication.shared.applicationState.rawValue)"
              + " recognizers=\(recognizers.count)")
        Self.write(recognizers, at: at, when: "now")
        let met = recognizers
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(400))
            Self.write(met, at: at, when: "0.4s")
        }
        return false
    }

    /// One log line per recognizer, since the phone's log cuts a long line short.
    private static func write(_ recognizers: [UIGestureRecognizer], at: CGPoint, when: String) {
        for (i, g) in recognizers.enumerated() {
            let on = g.view.map { "\(type(of: $0)) \($0.frame)" } ?? "none"
            NSLog("%@", "HBDIAG touch at=\(at) \(when) #\(i) \(type(of: g)) name=\(g.name ?? "") on \(on)"
                  + " enabled=\(g.isEnabled) state=\(g.state.rawValue)")
        }
    }
}
