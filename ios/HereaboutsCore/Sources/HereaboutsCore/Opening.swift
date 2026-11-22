// The opening's sums, with no drawing: where the tag, its point and the map sit on a given screen, how far each
// area's moss has filled in at a given moment, how far the first screen has opened out of the tag's point, and
// whether the opening plays at all. Also the home map's shades, worked out as the website's home map works them out.
//
// Every number is the approved preview's. The opening lasts 500 ms: each area fills on a spring that settles in
// 200 ms, starting up to 360 ms late the farther it is from the visitor's county, and from 250 ms the first screen
// opens as a growing circle centred on the tag's point.
import Foundation

/// A point in screen points or in map units.
public struct OpeningPoint: Equatable, Sendable {
    public let x: Double
    public let y: Double

    public init(x: Double, y: Double) {
        self.x = x
        self.y = y
    }
}

/// A rectangle in screen points.
public struct OpeningRect: Equatable, Sendable {
    public let x: Double
    public let y: Double
    public let width: Double
    public let height: Double

    public init(x: Double, y: Double, width: Double, height: Double) {
        self.x = x
        self.y = y
        self.width = width
        self.height = height
    }
}

/// Where the opening's parts sit on a screen of the given size, in points.
public struct OpeningLayout: Equatable, Sendable {
    /// The tag is 160 points square; its drawing is 256 units square, so one unit is 0.625 points.
    public static let tagSize = 160.0
    public static let unit = tagSize / 256

    /// The tag, centred on the whole screen (the loading screen puts it in the same place).
    public let tag: OpeningRect
    /// The tag's point: units (128, 232) of the tag's drawing. The county sits under it, and the first screen opens
    /// out of it.
    public let point: OpeningPoint
    /// The kraft patch behind the tag's label, so the cut-out H stays kraft while the map fills in around it.
    public let backing: OpeningRect
    /// The circle's last radius: from the tag's point to the farthest corner of the screen, plus 2 points.
    public let maxRadius: Double

    public init(screenWidth: Double, screenHeight: Double) {
        let u = Self.unit
        tag = OpeningRect(x: (screenWidth - Self.tagSize) / 2, y: (screenHeight - Self.tagSize) / 2,
                          width: Self.tagSize, height: Self.tagSize)
        point = OpeningPoint(x: tag.x + 128 * u, y: tag.y + 232 * u)
        backing = OpeningRect(x: tag.x + 46 * u, y: tag.y + 26 * u, width: 164 * u, height: 116 * u)
        maxRadius = hypot(max(point.x, screenWidth - point.x), max(point.y, screenHeight - point.y)) + 2
    }

    /// Where the map's top-left corner goes, in points, so that `county` (in map units) lands on the tag's point.
    public func mapOrigin(county: OpeningPoint, scale: Double) -> OpeningPoint {
        OpeningPoint(x: point.x - county.x * scale, y: point.y - county.y * scale)
    }
}

/// The opening's timing.
public enum OpeningMotion {
    /// The whole opening, and the moment the first screen starts to open, in milliseconds.
    public static let lengthMs = 500.0
    public static let openAtMs = 250.0
    /// The map is drawn 1000 points wide.
    public static let mapWidthPoints = 1000.0
    /// Each area's fill settles in 200 ms.
    public static let settleMs = 200.0
    /// No area starts later than 360 ms, which it does when it is 620 points or more from the county.
    public static let longestDelayMs = 360.0
    public static let farthestPoints = 620.0
    /// The circle grows as (R / 0.6)(1 - e^(-kt)), so it reaches its last radius R exactly 250 ms after it starts.
    public static let circleK = -log(0.4) / 0.25

    /// Points per map unit.
    public static func scale(mapWidth: Double) -> Double {
        mapWidthPoints / mapWidth
    }

    /// The county's point in map units, or, with no county (or one off the map), the map's middle.
    public static func county(_ point: OpeningPoint?, mapWidth: Double, mapHeight: Double) -> OpeningPoint {
        point ?? OpeningPoint(x: mapWidth / 2, y: mapHeight / 2)
    }

    /// How far an area's moss has filled in, from 0 to nearly 1, `ms` after that area started: a critically damped
    /// spring that settles in 200 ms.
    public static func fill(_ ms: Double) -> Double {
        guard ms > 0 else { return 0 }
        let omega = 7.43 / (settleMs / 1000)
        let t = ms / 1000
        return 1 - (1 + omega * t) * exp(-omega * t)
    }

    /// How long an area waits before it starts to fill, from its distance to the county in points.
    public static func delay(distance: Double) -> Double {
        min(distance / farthestPoints, 1) * longestDelayMs
    }

    /// The same, from the county and the middle of the area's bounding box, both in map units.
    public static func delay(from county: OpeningPoint, to centre: OpeningPoint, scale: Double) -> Double {
        delay(distance: hypot(centre.x - county.x, centre.y - county.y) * scale)
    }

    /// The radius of the circle the first screen shows through, in points, `ms` after the opening started.
    public static func radius(_ ms: Double, maxRadius: Double) -> Double {
        guard ms > openAtMs else { return 0 }
        guard ms < lengthMs else { return maxRadius }
        let t = (ms - openAtMs) / 1000
        return min(maxRadius, (maxRadius / 0.6) * (1 - exp(-circleK * t)))
    }
}

/// How the opening starts on this launch.
public enum OpeningStart: Equatable, Sendable {
    /// It plays once and goes.
    case play
    /// It is not shown: the first screen shows straight away.
    case off
    /// A screen test holds it still at this moment, in milliseconds, so the frame can be photographed.
    case hold(ms: Double)
}

/// When the opening plays.
public enum OpeningRule {
    /// It plays on a fresh launch, and not with Reduce Motion on, not when a tapped reminder opened the app, not
    /// when it has already been shown in this launch (coming back from the background), and not when a screen test
    /// turns it off. A screen test may hold it at one moment instead, which never overrides a reason not to play.
    public static func start(reduceMotion: Bool, fromReminder: Bool, shownBefore: Bool, skipForTests: Bool,
                             holdAtMs: Double?) -> OpeningStart {
        if reduceMotion || fromReminder || shownBefore || skipForTests { return .off }
        if let holdAtMs { return .hold(ms: min(max(holdAtMs, 0), OpeningMotion.lengthMs)) }
        return .play
    }
}

/// The website's home map: each area shaded by how many of our plants are native there.
public enum HomeMap {
    /// The most plants an area can have and still take shades 1, 2 and 3; any more takes shade 4. No plants is
    /// shade 0.
    public static let stepMaxima = [20, 75, 125]
    /// How strong the moss is for shades 0 to 4.
    public static let strengths: [Double] = [0.07, 0.2, 0.4, 0.66, 0.92]

    public static func step(plantCount n: Int) -> Int {
        guard n > 0 else { return 0 }
        return 1 + (stepMaxima.firstIndex { n <= $0 } ?? stepMaxima.count)
    }

    /// How many plants are native to each area: a plant counts once for every area in its native list.
    public static func counts(geo: [String: PlantGeo]) -> [String: Int] {
        var out: [String: Int] = [:]
        for g in geo.values {
            for e in g.eco { out[e, default: 0] += 1 }
        }
        return out
    }

    /// Each of `regions`' shade, 0 to 4.
    public static func steps(geo: [String: PlantGeo], regions: [String]) -> [String: Int] {
        let n = counts(geo: geo)
        var out: [String: Int] = [:]
        for r in regions { out[r] = step(plantCount: n[r] ?? 0) }
        return out
    }
}
