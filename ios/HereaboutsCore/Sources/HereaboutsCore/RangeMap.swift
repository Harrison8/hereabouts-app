// The plant page's "Native range" map: the lower 48 states' ecoregions as outlines, and the visitor's place as a
// dot. A port of the website's range-shapes.js, read from the bundle's tables/range-shapes.json, which
// scripts/gen-range-shapes.mjs writes from the same numbers. The tests check it against expected/map.json.
import Foundation

/// The map's size, its projection and the outline of each ecoregion.
public struct RangeShapes: Decodable, Sendable {
    /// The numbers of the map's projection (an Albers equal-area conic projection centred on longitude `lon0`).
    /// `x0` and `y0` already include the map's 4-point margin, so `point` must not add it again.
    public struct Projection: Decodable, Hashable, Sendable {
        public let n: Double
        public let C: Double
        public let r0: Double
        public let x0: Double
        public let y0: Double
        public let scale: Double
        public let lon0: Double

        public init(n: Double, C: Double, r0: Double, x0: Double, y0: Double, scale: Double, lon0: Double) {
            self.n = n
            self.C = C
            self.r0 = r0
            self.x0 = x0
            self.y0 = y0
            self.scale = scale
            self.lon0 = lon0
        }
    }

    /// A region's outline used a drawing command other than M (move), L (line) and Z (close), or was cut short.
    public struct PathError: Error, Equatable, Sendable {
        public let message: String
    }

    public let width: Double
    public let height: Double
    public let projection: Projection
    /// An SVG path for each ecoregion code, like "M14 173L13 180Z".
    public let regions: [String: String]

    public init(width: Double, height: Double, projection: Projection, regions: [String: String]) {
        self.width = width
        self.height = height
        self.projection = projection
        self.regions = regions
    }

    /// Reads range-shapes.json.
    public static func load(from url: URL) throws -> RangeShapes {
        try JSONDecoder().decode(RangeShapes.self, from: Data(contentsOf: url))
    }

    /// A longitude and latitude as (x, y) on the map, as RANGE_SHAPES.point does.
    public func point(lon: Double, lat: Double) -> (Double, Double) {
        let p = projection
        let rad = Double.pi / 180
        let r = (p.C - 2 * p.n * sin(lat * rad)).squareRoot() / p.n
        let t = p.n * (lon - p.lon0) * rad
        return ((r * sin(t) - p.x0) * p.scale, (p.y0 - (p.r0 - r * cos(t))) * p.scale)
    }

    /// The outline of one ecoregion as a list of closed shapes, each a list of (x, y) points. An ecoregion the map
    /// does not have gives an empty list. Throws when the outline uses any command but M, L and Z.
    public func paths(region: String) throws -> [[(Double, Double)]] {
        guard let d = regions[region] else { return [] }
        return try RangeShapes.parse(d)
    }

    // MARK: - path parsing

    private enum Token {
        case command(Unicode.Scalar)
        case number(Double)
    }

    /// Splits a path into commands and numbers. Numbers are written like "12", "-3.5" or "1e-3"; spaces and commas
    /// separate them.
    private static func tokens(_ d: String) throws -> [Token] {
        let s = Array(d.unicodeScalars)
        var out: [Token] = []
        var i = 0
        func isDigit(_ i: Int) -> Bool { i < s.count && s[i].value >= 0x30 && s[i].value <= 0x39 }
        while i < s.count {
            let u = s[i]
            if u == " " || u == "," || u == "\n" || u == "\t" || u == "\r" {
                i += 1
            } else if (u.value >= 0x41 && u.value <= 0x5A) || (u.value >= 0x61 && u.value <= 0x7A) {
                out.append(.command(u))
                i += 1
            } else if u == "-" || u == "+" || u == "." || isDigit(i) {
                let start = i
                if u == "-" || u == "+" { i += 1 }
                while isDigit(i) { i += 1 }
                if i < s.count && s[i] == "." {
                    i += 1
                    while isDigit(i) { i += 1 }
                }
                if i < s.count && (s[i] == "e" || s[i] == "E") {
                    var j = i + 1
                    if j < s.count && (s[j] == "-" || s[j] == "+") { j += 1 }
                    if isDigit(j) {
                        i = j
                        while isDigit(i) { i += 1 }
                    }
                }
                let text = JSText.string(s[start..<i])
                guard let n = Double(text) else { throw PathError(message: "bad number \"\(text)\"") }
                out.append(.number(n))
            } else {
                throw PathError(message: "unexpected character \"\(u)\"")
            }
        }
        return out
    }

    /// Reads M, L and Z commands into closed shapes. M starts a shape, L adds points to it, and further number pairs
    /// after either add points too. Z closes the shape; only M may follow it.
    static func parse(_ d: String) throws -> [[(Double, Double)]] {
        let list = try tokens(d)
        var shapes: [[(Double, Double)]] = []
        var current: [(Double, Double)]? = nil
        var command: Unicode.Scalar? = nil
        var i = 0
        while i < list.count {
            switch list[i] {
            case .command(let c):
                i += 1
                switch c {
                case "M":
                    if let open = current { shapes.append(open) }
                    current = []
                    command = c
                case "L":
                    guard current != nil else { throw PathError(message: "L with no shape started") }
                    command = c
                case "Z":
                    guard let open = current else { throw PathError(message: "Z with no shape started") }
                    shapes.append(open)
                    current = nil
                    command = nil
                default:
                    throw PathError(message: "unsupported command \"\(c)\"")
                }
            case .number(let x):
                guard command != nil, current != nil else { throw PathError(message: "a number with no command") }
                guard i + 1 < list.count, case .number(let y) = list[i + 1] else {
                    throw PathError(message: "a point with no y")
                }
                current!.append((x, y))
                i += 2
            }
        }
        if let open = current { shapes.append(open) }
        return shapes
    }
}
