// The app's entry point. It loads the shared model and the network services once, and shows the first-open screen
// until a county is chosen, then the three tabs. Whenever the app comes to the front, and whenever signal comes back,
// it sends waiting events and checks for newer plant data if a day has passed since the last check. A tapped
// Saturday reminder opens the Garden tab.
import SwiftUI
@preconcurrency import UserNotifications

@main
struct HereaboutsApp: App {
    @State private var model: AppModel
    @State private var connectivity: Connectivity
    private let updater: DataUpdater
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        Theme.styleNavigationTitles()
        let settings = ServiceSettings.forLaunch()
        let connectivity = Connectivity()
        let model = AppModel.forLaunch(services: settings, isOnline: { await connectivity.isOnline })
        _connectivity = State(initialValue: connectivity)
        _model = State(initialValue: model)
        updater = DataUpdater(model: model, site: settings.site, dataFolder: settings.folders.data,
                              defaults: model.defaults, isOnline: { connectivity.isOnline })
        NotificationTaps.handler = { [model] in model.openedFromNotification() }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .capturingLinksForTests()
                .modifier(RefreshWhenUseful(updater: updater, model: model))
                .environment(model)
                .environment(connectivity)
                .tint(Theme.moss)
        }
    }
}

/// Hears about taps on the Saturday reminders, which the phone reports to the notification centre's delegate.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // Set before launch ends, so a tap that opened the app is not missed.
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    /// A reminder was tapped, with the app open or closed. Only the identifier is read here; the tap is passed to the
    /// main thread, and the phone is told straight away that it was handled.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        let id = response.notification.request.identifier
        if id.hasPrefix(ReminderScheduler.idPrefix) {
            Task { @MainActor in NotificationTaps.received() }
        }
        completionHandler()
    }

    /// A reminder came due while the app was open: show it as usual.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler:
                                            @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }
}

/// Passes a reminder tap to the model. A tap that arrives before the model is ready waits until it is.
@MainActor
enum NotificationTaps {
    static var handler: (@MainActor () -> Void)? {
        didSet {
            if waiting, let handler {
                waiting = false
                handler()
            }
        }
    }
    private static var waiting = false

    static func received() {
        if let handler { handler() } else { waiting = true }
    }
}

/// Sends waiting events and checks for newer data when the app comes to the front and when signal comes back. Both
/// do nothing without signal, and the data check also does nothing until a day has passed since the last one. Coming
/// to the front also moves This week on to the phone's time, reads the notification permission again and plans the
/// reminders again (AppModel.cameToFront).
private struct RefreshWhenUseful: ViewModifier {
    let updater: DataUpdater
    let model: AppModel

    @Environment(\.scenePhase) private var scenePhase
    @Environment(Connectivity.self) private var connectivity

    func body(content: Content) -> some View {
        content
            .onChange(of: scenePhase, initial: true) { _, phase in
                if phase == .active {
                    model.cameToFront()
                    refresh()
                }
            }
            .onChange(of: connectivity.isOnline) { _, online in
                if online { refresh() }
            }
    }

    private func refresh() {
        Task { await updater.checkIfDue() }
        Task { await model.events?.flush() }
    }
}

/// The first-open screen until a county is chosen, then the tabs.
struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if model.location == nil {
            FirstOpenView()
        } else {
            RootTabs()
        }
    }
}

extension View {
    /// In a Debug build launched by a screen test with `-uiTestCaptureLinks`, every link the app would open in the
    /// browser or in Maps is posted to the test's stand-in website at /uitest/opened instead, so the test can check
    /// the exact address. In every other build and launch it does nothing. A sheet gets its own environment, so the
    /// plant page applies this to Where to buy as well.
    func capturingLinksForTests() -> some View {
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("-uiTestCaptureLinks"), let site = ServiceSettings.testSite(in: arguments) {
            return AnyView(environment(\.openURL, OpenURLAction { url in
                var request = URLRequest(url: site.appendingPathComponent("uitest/opened"))
                request.httpMethod = "POST"
                request.httpBody = Data(url.absoluteString.utf8)
                URLSession.shared.dataTask(with: request).resume()
                return .handled
            }))
        }
        return AnyView(self)
        #else
        return self
        #endif
    }
}
