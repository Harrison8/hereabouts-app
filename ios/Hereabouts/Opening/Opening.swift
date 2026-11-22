// The opening, "Your map fills in". For half a second after a fresh launch, the website's home map fills in around
// the tag the loading screen showed, nearest areas first, and then the first screen opens out of the tag's point as
// a growing circle. Its first frame is the loading screen exactly: kraft, with the moss tag 160 points square in the
// middle of the whole screen. Every frame is worked out from the time since the opening started, with
// HereaboutsCore's OpeningMotion, not with SwiftUI's own animations, so the timing is the approved preview's.
//
// Whether it plays is decided once, when the app is made, before any window exists. It is not shown with Reduce
// Motion on, or when a tapped reminder opened the app: the window's delegate learns that as the window connects,
// before the first frame, and takes the opening away. Coming back from the background never shows it again. Touches
// reach the screen underneath from 250 ms, when the circle starts. VoiceOver never sees it.
//
// In a Debug build only, a screen test can turn it off with `-uiTestSkipOpening` (every screen test but the
// opening's own does) or hold it still at one moment with `-uiTestOpeningAt <ms>`, and finds it under the name
// "opening". Once it has been on screen it also leaves a mark named "openingShown", which stays after it goes. A
// Release build reads none of this.
import HereaboutsCore
import SwiftUI
import UIKit

/// Whether the opening is showing, and the map it draws.
@MainActor
@Observable
final class Opening {
    /// Set when the phone opened the app because a Saturday reminder was tapped.
    static var launchedFromReminder = false
    /// The opening the app made at launch.
    private static weak var current: Opening?

    /// A tapped reminder opened the app: the opening already made goes, and one made later does not start.
    static func reminderOpenedTheApp() {
        launchedFromReminder = true
        current?.stop()
    }

    /// How the opening starts: playing, held still for a screen test, or not at all.
    let start: OpeningStart
    /// The map, once it has been built away from the main thread. Until then a frame shows the tag on kraft.
    private(set) var map: OpeningMap?
    /// True once the opening has gone, for good.
    private(set) var ended = false
    /// How the opening was on screen, once it has been.
    private(set) var shown: OpeningStart?
    /// When its first frame appeared. Until then every frame is the first, 0 ms.
    @ObservationIgnored private(set) var startedAt: Date?

    /// Decides how the opening starts and begins building the map straight away, away from the main thread, so the
    /// first screen's own loading is not held up and the map is ready by the first frame.
    init(geo: [String: PlantGeo], location: Location?, arguments: [String] = ProcessInfo.processInfo.arguments,
         reduceMotion: Bool = UIAccessibility.isReduceMotionEnabled) {
        var skipForTests = false
        var holdAtMs: Double?
        #if DEBUG
        skipForTests = arguments.contains("-uiTestSkipOpening")
        holdAtMs = AppModel.testValue(after: "-uiTestOpeningAt", in: arguments).flatMap { Double($0) }
        #endif
        // The app makes one Opening per launch, so it has never been shown before.
        start = OpeningRule.start(reduceMotion: reduceMotion, fromReminder: Self.launchedFromReminder,
                                  shownBefore: false, skipForTests: skipForTests, holdAtMs: holdAtMs)
        Self.current = self
        if start == .off { return }
        Task { [weak self] in
            let built = await Task.detached(priority: .userInitiated) {
                OpeningMap.build(geo: geo, location: location)
            }.value
            self?.map = built
        }
    }

    /// The opening's first frame is on screen: its clock starts.
    func appeared() {
        if shown == nil { shown = start }
        if startedAt == nil { startedAt = .now }
    }

    /// Milliseconds since the first frame appeared, at `date`.
    func elapsedMs(at date: Date) -> Double {
        startedAt.map { date.timeIntervalSince($0) * 1000 } ?? 0
    }

    /// Waits out the rest of the opening, then takes it away.
    func playToEnd() async {
        let left = OpeningMotion.lengthMs - elapsedMs(at: .now)
        if left > 0 { try? await Task.sleep(for: .milliseconds(left)) }
        ended = true
    }

    /// A tapped reminder takes the opening away at once.
    func stop() {
        ended = true
    }
}

/// The home map as the opening draws it: each area's outline, how strong its moss ends up (the website's home map
/// shade), and how long it waits before it starts to fill.
struct OpeningMap: @unchecked Sendable {
    struct Area {
        let path: Path
        let strength: Double
        let delayMs: Double
    }

    let areas: [Area]
    /// The visitor's county, or the map's middle when there is none, in map units.
    let county: OpeningPoint
    /// Points per map unit.
    let scale: Double

    /// nil only if the shipped map file is missing or broken; the opening then shows the tag on kraft.
    nonisolated static func build(geo: [String: PlantGeo], location: Location?) -> OpeningMap? {
        guard let map = RangeMapShapes.shared else { return nil }
        let shapes = map.shapes
        let scale = OpeningMotion.scale(mapWidth: shapes.width)
        let dot = RangeMapView.point(location, map: shapes).map { OpeningPoint(x: Double($0.x), y: Double($0.y)) }
        let county = OpeningMotion.county(dot, mapWidth: shapes.width, mapHeight: shapes.height)
        let steps = HomeMap.steps(geo: geo, regions: map.paths.map { $0.0 })
        let areas = map.paths.map { entry -> Area in
            let box = entry.1.boundingRect
            let centre = OpeningPoint(x: Double(box.midX), y: Double(box.midY))
            return Area(path: entry.1, strength: HomeMap.strengths[steps[entry.0] ?? 0],
                        delayMs: OpeningMotion.delay(from: county, to: centre, scale: scale))
        }
        return OpeningMap(areas: areas, county: county, scale: scale)
    }
}

/// The opening above the first screen, while it shows. Its body only reads; the clock starts when the first frame
/// appears.
struct OpeningLayer: View {
    let opening: Opening

    var body: some View {
        ZStack {
            if !opening.ended {
                let map = opening.map
                switch opening.start {
                case .off:
                    EmptyView()
                case .hold(let ms):
                    // A held frame is shown to the screen test only once its map is drawn, so its photograph has it.
                    OpeningFrame(map: map, ms: ms, named: map != nil)
                        .onAppear { opening.appeared() }
                case .play:
                    TimelineView(.animation) { timeline in
                        OpeningFrame(map: map, ms: opening.elapsedMs(at: timeline.date), named: true)
                    }
                    .onAppear { opening.appeared() }
                    .task { await opening.playToEnd() }
                }
            }
            OpeningShownForTests(shown: opening.shown)
        }
    }
}

/// In a Debug build launched by a screen test, a mark that the opening has been on screen, "Opening played" or
/// "Opening held". It stays after the opening goes, so a test can tell an opening that played and ended from one that
/// never came up. Two points square, almost clear, and never touched.
private struct OpeningShownForTests: View {
    let shown: OpeningStart?

    var body: some View {
        #if DEBUG
        if let shown, shown != .off, ProcessInfo.processInfo.arguments.contains(where: { $0.hasPrefix("-uiTest") }) {
            Color.white.opacity(0.01)
                .frame(width: 2, height: 2)
                .accessibilityElement()
                .accessibilityLabel(shown == .play ? "Opening played" : "Opening held")
                .accessibilityIdentifier("openingShown")
                .allowsHitTesting(false)
        }
        #else
        EmptyView()
        #endif
    }
}

/// One frame of the opening, `ms` after it started, over the whole screen.
struct OpeningFrame: View {
    let map: OpeningMap?
    let ms: Double
    /// Whether a screen test may find it.
    let named: Bool

    var body: some View {
        let kraft = Theme.kraft
        let moss = Theme.moss
        let tag = Image("LaunchTag")
        let map = self.map
        let ms = self.ms
        Canvas { context, size in
            let layout = OpeningLayout(screenWidth: size.width, screenHeight: size.height)
            // The first screen shows through a circle centred on the tag's point.
            let radius = OpeningMotion.radius(ms, maxRadius: layout.maxRadius)
            if radius > 0 {
                let p = layout.point
                let circle = CGRect(x: p.x - radius, y: p.y - radius, width: 2 * radius, height: 2 * radius)
                context.clip(to: Path(ellipseIn: circle), options: .inverse)
            }
            context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(kraft))
            if let map {
                // The map as the brand kit draws it: moss areas edged with kraft lines 0.75 points wide at any scale.
                var mapContext = context
                let origin = layout.mapOrigin(county: map.county, scale: map.scale)
                mapContext.translateBy(x: origin.x, y: origin.y)
                mapContext.scaleBy(x: map.scale, y: map.scale)
                let edge = StrokeStyle(lineWidth: 0.75 / map.scale, lineJoin: .round)
                for area in map.areas {
                    let strength = area.strength * OpeningMotion.fill(ms - area.delayMs)
                    mapContext.fill(area.path, with: .color(moss.opacity(strength)))
                    mapContext.stroke(area.path, with: .color(kraft), style: edge)
                }
            }
            let b = layout.backing
            context.fill(Path(CGRect(x: b.x, y: b.y, width: b.width, height: b.height)), with: .color(kraft))
            let t = layout.tag
            context.draw(tag, in: CGRect(x: t.x, y: t.y, width: t.width, height: t.height))
        }
        .ignoresSafeArea()
        .contentShape(Rectangle())
        .allowsHitTesting(ms < OpeningMotion.openAtMs)
        .openingForTests(named)
    }
}

private extension View {
    /// Hidden from VoiceOver. In a Debug build launched by a screen test, shown to the test under the name "opening"
    /// instead, so it can tell whether the opening is up.
    func openingForTests(_ named: Bool) -> some View {
        #if DEBUG
        if named, ProcessInfo.processInfo.arguments.contains(where: { $0.hasPrefix("-uiTest") }) {
            return AnyView(accessibilityElement(children: .ignore)
                .accessibilityLabel("Opening")
                .accessibilityIdentifier("opening"))
        }
        return AnyView(accessibilityHidden(true))
        #else
        return accessibilityHidden(true)
        #endif
    }
}
