// The app's entry point. It loads the shared model and the network services once, and shows the first-open screen
// until a county is chosen, then the three tabs. Whenever the app comes to the front, and whenever signal comes back,
// it sends waiting events and checks for newer plant data if a day has passed since the last check.
import SwiftUI

@main
struct HereaboutsApp: App {
    @State private var model: AppModel
    @State private var connectivity: Connectivity
    private let updater: DataUpdater

    init() {
        Theme.styleNavigationTitles()
        let settings = ServiceSettings.forLaunch()
        let connectivity = Connectivity()
        let model = AppModel.forLaunch(services: settings, isOnline: { await connectivity.isOnline })
        _connectivity = State(initialValue: connectivity)
        _model = State(initialValue: model)
        updater = DataUpdater(model: model, site: settings.site, dataFolder: settings.folders.data,
                              defaults: model.defaults, isOnline: { connectivity.isOnline })
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .modifier(RefreshWhenUseful(updater: updater, events: model.events))
                .environment(model)
                .environment(connectivity)
                .tint(Theme.moss)
        }
    }
}

/// Sends waiting events and checks for newer data when the app comes to the front and when signal comes back. Both
/// do nothing without signal, and the data check also does nothing until a day has passed since the last one.
private struct RefreshWhenUseful: ViewModifier {
    let updater: DataUpdater
    let events: EventSender?

    @Environment(\.scenePhase) private var scenePhase
    @Environment(Connectivity.self) private var connectivity

    func body(content: Content) -> some View {
        content
            .onChange(of: scenePhase, initial: true) { _, phase in
                if phase == .active { refresh() }
            }
            .onChange(of: connectivity.isOnline) { _, online in
                if online { refresh() }
            }
    }

    private func refresh() {
        Task { await updater.checkIfDue() }
        Task { await events?.flush() }
    }
}

/// The first-open screen until a county is chosen, then the tabs.
struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if model.location == nil {
            FirstOpenPlaceholder()
        } else {
            RootTabs()
        }
    }
}

/// Stands in for the first-open screen, where the person gives their ZIP code, until that screen is built.
struct FirstOpenPlaceholder: View {
    var body: some View {
        Text("Hereabouts")
            .font(.display(.largeTitle))
            .foregroundStyle(Theme.moss)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.white)
    }
}
