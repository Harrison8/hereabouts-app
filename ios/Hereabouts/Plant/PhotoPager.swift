// The plant page's photos, as plant-page-top.png draws them: the full width of the screen, one photo per season the
// website has (bloom, fruit, green, fall, winter, dormant, in that order), swiped sideways. Under the photo: its credit,
// the dots, and the season with "1 of 3". A plant with no photo shows the brand kit's "No photograph yet" plate in
// the same 4:3 frame, with no credit and no counter.
import HereaboutsCore
import SwiftUI
import UIKit

struct PhotoPager: View {
    let plant: Plant
    let photos: PhotoCache?

    @State private var page = 0
    @Environment(\.dynamicTypeSize) private var typeSize

    /// The word for each season under the photo, as the approved picture writes them.
    static let seasonWords = ["bloom": "In bloom", "fruit": "In fruit", "green": "In leaf", "fall": "In fall",
                              "winter": "In winter", "dormant": "Dormant"]

    var body: some View {
        let shots = Labels.photos(plant)
        VStack(alignment: .leading, spacing: 8) {
            if shots.isEmpty {
                ZStack {
                    Theme.surface
                    NoPhotoPlate(plant: plant, size: 15)
                }
                .aspectRatio(4 / 3, contentMode: .fit)
                .frame(maxWidth: .infinity)
                .overlay(alignment: .bottom) { Theme.ruleStrong.frame(height: 1) }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(plant.common): no photograph yet")
                .accessibilityIdentifier("noPhoto")
            } else {
                TabView(selection: $page) {
                    ForEach(shots.indices, id: \.self) { i in
                        PagerPhoto(plant: plant, season: shots[i].season, photo: shots[i].photo, photos: photos)
                            .tag(i)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .aspectRatio(4 / 3, contentMode: .fit)
                .frame(maxWidth: .infinity)
                caption(shots: shots)
                    .padding(.horizontal, 16)
            }
        }
        .onChange(of: plant.id) { _, _ in page = 0 }
    }

    /// The credit at left, the dots in the middle, the season and count at right. At the largest text sizes the three
    /// stack, so neither the credit nor the count is cut.
    @ViewBuilder
    private func caption(shots: [(season: String, photo: Photo)]) -> some View {
        let shown = min(max(page, 0), shots.count - 1)
        let counter = Text("\(Self.seasonWords[shots[shown].season] ?? "") \u{00B7} \(shown + 1) of \(shots.count)")
            .font(.body(15, .regular, relativeTo: .subheadline))
            .foregroundStyle(Theme.inkSoft)
            .accessibilityIdentifier("photoCounter")
        let credit = Text(Labels.creditText(shots[shown].photo.credit))
            .font(.body(12, .regular, relativeTo: .caption))
            .foregroundStyle(Theme.creditGrey)
        if typeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 4) {
                counter
                credit
            }
            .fixedSize(horizontal: false, vertical: true)
        } else {
            HStack(alignment: .center, spacing: 8) {
                credit
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                dots(count: shots.count, current: shown)
                counter
                    .fixedSize(horizontal: false, vertical: true)
                    .multilineTextAlignment(.trailing)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
    }

    /// One dot per photo, the shown one in ink. The counter already says the same, so the dots are not read aloud.
    private func dots(count: Int, current: Int) -> some View {
        HStack(spacing: 8) {
            ForEach(0..<count, id: \.self) { i in
                Circle()
                    .fill(i == current ? Theme.ink : Theme.ruleStrong)
                    .frame(width: 8, height: 8)
            }
        }
        .opacity(count > 1 ? 1 : 0)
        .accessibilityHidden(true)
    }
}

/// One photo of the pager, read at the size it is shown. It comes from the photo store like the list's photos; until it
/// arrives, or if it cannot be had, the frame holds the "No photograph yet" plate.
private struct PagerPhoto: View {
    let plant: Plant
    let season: String
    let photo: Photo
    let photos: PhotoCache?

    @State private var image: UIImage?
    @State private var looked = false
    @Environment(\.displayScale) private var displayScale

    var body: some View {
        GeometryReader { geo in
            let longest = max(geo.size.width, geo.size.height)
            ZStack {
                Theme.surface
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: geo.size.width, height: geo.size.height)
                        .clipped()
                } else if looked {
                    NoPhotoPlate(plant: plant, size: 15)
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .task(id: photo.url) {
                image = nil
                looked = false
                if let file = await PlantThumbnail.file(for: photo, photos: photos) {
                    let pixels = longest * displayScale
                    image = await Task.detached(priority: .userInitiated) {
                        Decoded(image: PlantThumbnail.downsample(file, maxPixels: pixels))
                    }.value.image
                }
                looked = true
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(photo.alt.isEmpty ? "\(plant.common), \(season)" : photo.alt)
        .accessibilityAddTraits(.isImage)
    }
}
