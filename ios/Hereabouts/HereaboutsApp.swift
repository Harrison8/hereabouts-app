// The app's entry point. It loads the shared model and the network services once, and shows the first-open screen
// until a county is chosen, then the three tabs. Whenever the app comes to the front, and whenever signal comes back,
// it sends waiting events and checks for newer plant data if a day has passed since the last check. A tapped
// Saturday reminder opens the Garden tab. On a fresh launch the opening plays above the first screen (Opening.swift).
import SwiftUI
@preconcurrency import UserNotifications

@main
struct HereaboutsApp: App {
    @State private var model: AppModel
    @State private var connectivity: Connectivity
    @State private var opening: Opening
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
        let opening = Opening(geo: model.data.geo, location: model.location)
        _opening = State(initialValue: opening)
        // A tapped reminder goes straight to This week, so it also takes the opening away.
        NotificationTaps.handler = { [model, opening] in
            opening.stop()
            model.openedFromNotification()
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .capturingLinksForTests()
                .modifier(RefreshWhenUseful(updater: updater, model: model))
                .environment(model)
                .environment(connectivity)
                .tint(Theme.moss)
                .overlay { OpeningLayer(opening: opening) }
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

    /// A new window is being made, the first time the app runs or after its window was thrown away. It names the
    /// window's delegate below, which the phone keeps with the window from then on. The window itself is made as it
    /// would be anyway.
    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        if WindowSceneDelegate.opensFromReminder(options) { Opening.reminderOpenedTheApp() }
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        configuration.delegateClass = WindowSceneDelegate.self
        return configuration
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

/// The window's delegate, named in the window's settings above. SwiftUI makes and runs the window as it would without
/// it; it only looks at why the window is connecting.
@MainActor
final class WindowSceneDelegate: NSObject, UIWindowSceneDelegate {
    /// The window is connecting, new or brought back after the phone ended the app, before its first frame. When a
    /// tapped Saturday reminder is why, the opening is taken away, so This week comes straight up.
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession,
               options connectionOptions: UIScene.ConnectionOptions) {
        if Self.opensFromReminder(connectionOptions) { Opening.reminderOpenedTheApp() }
        #if DEBUG
        // A screen test's stand-in for a tapped reminder goes the same way. The tap itself is passed on a second
        // later, so an opening that came up anyway would have been on screen before the tap took it away.
        if ProcessInfo.processInfo.arguments.contains("-uiTestNotificationTap") {
            Opening.reminderOpenedTheApp()
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(1))
                NotificationTaps.received()
            }
        }
        #endif
    }

    /// True when a tapped Saturday reminder is why the window is connecting.
    static func opensFromReminder(_ options: UIScene.ConnectionOptions) -> Bool {
        options.notificationResponse?.notification.request.identifier.hasPrefix(ReminderScheduler.idPrefix) ?? false
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
