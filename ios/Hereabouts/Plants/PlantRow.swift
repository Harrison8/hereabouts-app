// One plant in the Plants list, as the approved picture draws it: the photo in its frame, the name in Young Serif, the
// Latin name, "Type · height", the photo credit, and the bookmark on the right. Tapping the row opens the plant;
// the bookmark only saves or unsaves it. At the largest text sizes the photo sits above the words, so nothing is cut.
import HereaboutsCore
import ImageIO
import SwiftUI
import UIKit

struct PlantRow: View {
    let plant: Plant
    let isSaved: Bool
    let photos: PhotoCache?
    let open: () -> Void
    let toggleSave: () -> Void

    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        if typeSize.isAccessibilitySize {
            rowButton {
                VStack(alignment: .leading, spacing: 8) {
                    PlantThumbnail(plant: plant, photos: photos, side: 120)
                    words
                }
            }
            .overlay(alignment: .topTrailing) { saveButton.padding(.top, 12).padding(.trailing, 4) }
        } else {
            HStack(alignment: .top, spacing: 0) {
                rowButton {
                    HStack(alignment: .top, spacing: 12) {
                        PlantThumbnail(plant: plant, photos: photos, side: 84)
                        words
                    }
                }
                saveButton.padding(.top, 8)
            }
            .padding(.trailing, 4)
        }
    }

    /// The part of the row that opens the plant: everything but the bookmark.
    private func rowButton<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        Button(action: open) {
            content()
                .padding(.leading, 16)
                .padding(.trailing, typeSize.isAccessibilitySize ? 16 : 4)
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(plant.common), \(plant.sci), \(meta)")
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("plant-\(plant.id)")
    }

    /// "Perennial · 2–3 ft", or the type alone when the plant has no heights.
    private var meta: String {
        [Labels.category(plant.category), Labels.ftRange(plant.height)].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    private var words: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(plant.common)
                .font(.display(.title3))
                .foregroundStyle(Theme.ink)
            Text(plant.sci)
                .font(.bodySlanted(.subheadline, typeSize: typeSize))
                .foregroundStyle(Theme.inkSoft)
            Text(meta)
                .font(.body(14, .regular, relativeTo: .footnote))
                .foregroundStyle(Theme.inkSoft)
            if let credit = Labels.mainPhoto(plant)?.photo.credit, !credit.isEmpty {
                Text(credit)
                    .font(.body(10, .regular, relativeTo: .caption2))
                    .foregroundStyle(Theme.creditGrey)
                    .padding(.top, 2)
            }
        }
        .multilineTextAlignment(.leading)
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var saveButton: some View {
        Button(action: toggleSave) {
            Image(systemName: isSaved ? "bookmark.fill" : "bookmark")
                .font(.system(size: 22))
                .foregroundStyle(isSaved ? Theme.moss : Theme.inkSoft)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .accessibilityLabel("Save \(plant.common)")
        .accessibilityValue(isSaved ? "Saved" : "Not saved")
        .accessibilityIdentifier("save-\(plant.id)")
    }
}

/// The plant's main photo in the brand's frame: a thin darker rule and 4-point corners on the pale ground. A photo not
/// on the phone yet is fetched through the photo store; until it arrives, or if it cannot be had, the frame holds the
/// website's "No photograph yet" plate.
struct PlantThumbnail: View {
    let plant: Plant
    let photos: PhotoCache?
    let side: CGFloat

    @State private var image: UIImage?
    @State private var looked = false
    @Environment(\.displayScale) private var displayScale

    var body: some View {
        ZStack {
            Theme.surface
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: side, height: side)
                    .clipped()
            } else if looked {
                NoPhotoPlate(plant: plant)
            }
        }
        .frame(width: side, height: side)
        .clipShape(RoundedRectangle(cornerRadius: Theme.brandCorner))
        .overlay(RoundedRectangle(cornerRadius: Theme.brandCorner).strokeBorder(Theme.ruleStrong, lineWidth: 1))
        .accessibilityHidden(true)
        .task(id: Labels.mainPhoto(plant)?.photo.url) {
            image = nil
            looked = false
            if let photo = Labels.mainPhoto(plant)?.photo, let file = await Self.file(for: photo, photos: photos) {
                let pixels = side * displayScale
                image = await Task.detached(priority: .userInitiated) { Decoded(image: Self.downsample(file, maxPixels: pixels)) }.value.image
            }
            looked = true
        }
    }

    /// The photo's file on the phone: from the photo store (which downloads a photo the app did not ship with), or,
    /// without one, from the bundle the app ships with. The plant page's photos come through here too.
    static func file(for photo: Photo, photos: PhotoCache?) async -> URL? {
        if let photos { return await photos.load(photo) }
        guard PhotoCache.isPhotoPath(photo.url) else { return nil }
        let file = AppModel.bundleURL.appendingPathComponent(photo.url)
        return FileManager.default.fileExists(atPath: file.path) ? file : nil
    }

    /// The photo read at the size it is shown, not at full size, so a long list stays light on memory.
    nonisolated static func downsample(_ file: URL, maxPixels: CGFloat) -> UIImage? {
        guard let source = CGImageSourceCreateWithURL(file as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary)
        else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, Int(maxPixels.rounded(.up))),
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// A decoded photo handed from the background task that read it to the screen. A UIImage is not changed after it is
/// made, so passing it between threads is safe.
struct Decoded: @unchecked Sendable {
    let image: UIImage?
}

/// The website's plate for a plant with no photograph on the phone: "No photograph yet", centred in the frame. The
/// list's small frame takes 12-point words; the plant page's full-width frame takes the brand kit's 15.
struct NoPhotoPlate: View {
    let plant: Plant
    var size: CGFloat = 12

    var body: some View {
        Text("No photograph yet")
            .font(.body(size, .regular, relativeTo: size < 15 ? .caption : .subheadline))
            .foregroundStyle(Theme.inkSoft)
            .multilineTextAlignment(.center)
            .minimumScaleFactor(0.5)
            .padding(4)
            .accessibilityLabel("\(plant.common): no photograph yet")
    }
}
