// "Where it grows wild": the brand kit's range map, drawn with Canvas from HereaboutsCore's RangeShapes, as the
// website's renderRangeMap draws it. Every EPA natural region of the lower 48 states in kraft, the ones where the
// plant grows wild in moss, edged in packet paper; the visitor's county marked with a dot when it falls on the map.
// The dot is placed from the position the app already has. Nothing is sent anywhere.
import HereaboutsCore
import SwiftUI

struct RangeMapView: View {
    let plant: Plant
    /// The ecoregion codes where the plant grows wild (PLANT_GEO's eco).
    let native: [String]
    let location: Location?

    var body: some View {
        if let map = RangeMapShapes.shared {
            let point = Self.point(location, map: map.shapes)
            let nativeSet = Set(native)
            let paths = map.paths
            let mapWidth = map.shapes.width
            Canvas { context, size in
                // The website's lines keep their width at any map size (non-scaling strokes), so the widths here are
                // in points, divided by the scale.
                let scale = size.width / mapWidth
                context.scaleBy(x: scale, y: scale)
                for (code, path) in paths {
                    context.fill(path, with: .color(nativeSet.contains(code) ? Theme.moss : Theme.kraft))
                }
                for (_, path) in paths {
                    context.stroke(path, with: .color(Theme.packet),
                                   style: StrokeStyle(lineWidth: 0.75 / scale, lineJoin: .round))
                }
                if let point {
                    let dot = Path(ellipseIn: CGRect(x: point.x - 15, y: point.y - 15, width: 30, height: 30))
                    context.fill(dot, with: .color(Theme.packet))
                    context.stroke(dot, with: .color(Theme.ink), lineWidth: 2 / scale)
                }
            }
            .aspectRatio(map.shapes.width / map.shapes.height, contentMode: .fit)
            .frame(maxWidth: .infinity)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Self.label(plant, location: location, marked: point != nil))
            .accessibilityAddTraits(.isImage)
            .accessibilityIdentifier("rangeMap")
        }
    }

    /// The visitor's county on the map, or nil when there is no position or it falls off the lower 48 states, as on
    /// the website.
    static func point(_ location: Location?, map: RangeShapes) -> CGPoint? {
        guard let lat = location?.lat, let lon = location?.lon, lat.isFinite, lon.isFinite else { return nil }
        let (x, y) = map.point(lon: lon, lat: lat)
        guard x >= 0, x <= map.width, y >= 0, y <= map.height else { return nil }
        return CGPoint(x: x, y: y)
    }

    /// The website's words for the map.
    static func label(_ plant: Plant, location: Location?, marked: Bool) -> String {
        var text = "The shaded parts of this map of the lower 48 states are where \(plant.common) grows wild."
        if marked, let location { text += " \(location.countyName), \(location.stateName) is marked with a dot." }
        return text
    }
}

/// The map's outlines, read once from the bundle and turned into paths, so scrolling never re-reads them.
@MainActor
final class RangeMapShapes {
    let shapes: RangeShapes
    let paths: [(String, Path)]

    private init(shapes: RangeShapes) {
        self.shapes = shapes
        paths = shapes.regions.keys.sorted().map { code in
            var path = Path()
            for shape in (try? shapes.paths(region: code)) ?? [] {
                guard let first = shape.first else { continue }
                path.move(to: CGPoint(x: first.0, y: first.1))
                for p in shape.dropFirst() { path.addLine(to: CGPoint(x: p.0, y: p.1)) }
                path.closeSubpath()
            }
            return (code, path)
        }
    }

    /// nil only if the shipped map file is missing or broken; the page then leaves the map out.
    static let shared: RangeMapShapes? = {
        let url = AppModel.bundleURL.appendingPathComponent("tables/range-shapes.json")
        guard let shapes = try? RangeShapes.load(from: url) else { return nil }
        return RangeMapShapes(shapes: shapes)
    }()
}
