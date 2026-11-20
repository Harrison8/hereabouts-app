// The saved plants on the Garden tab, as d1-my-garden.png draws them: one row per plant, in the order they were saved,
// with its photo, name, Latin name, how many of this week's jobs name it, and the photo credit. Tapping a row opens
// the plant's page; pressing and holding it opens a menu with Remove, which unsaves it. A plant that is not native to
// the county chosen now stays here until it is removed.
import HereaboutsCore
import SwiftUI

struct SavedGrid: View {
    let plants: [Plant]
    let photos: PhotoCache?
    /// How many of this week's jobs name the plant.
    let jobCount: (String) -> Int
    let open: (String) -> Void
    let remove: (String) -> Void

    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text("Saved plants")
                    .font(.display(.title2))
                    .foregroundStyle(Theme.ink)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 0)
                Text("\(plants.count)")
                    .font(.body(17, .regular, relativeTo: .body))
                    .foregroundStyle(Theme.inkSoft)
                    .accessibilityLabel(plants.count == 1 ? "1 plant" : "\(plants.count) plants")
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 12)

            VStack(spacing: 0) {
                ForEach(Array(plants.enumerated()), id: \.element.id) { i, plant in
                    if i > 0 { Theme.rule.frame(height: 1) }
                    row(plant)
                }
            }
            .background(Color.white)
            .overlay(RoundedRectangle(cornerRadius: Theme.brandCorner).strokeBorder(Theme.rule, lineWidth: 1))
            .padding(.horizontal, 16)
        }
    }

    private func jobsLine(_ plant: Plant) -> String {
        switch jobCount(plant.id) {
        case 0: "Nothing to do this week"
        case 1: "1 job this week"
        case let n: "\(n) jobs this week"
        }
    }

    private func row(_ plant: Plant) -> some View {
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
            : AnyLayout(HStackLayout(alignment: .center, spacing: 12))
        return Button {
            open(plant.id)
        } label: {
            HStack(spacing: 8) {
                layout {
                    PlantThumbnail(plant: plant, photos: photos, side: typeSize.isAccessibilitySize ? 120 : 64)
                    words(plant)
                }
                Image(systemName: "chevron.right")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Theme.ruleStrong)
                    .accessibilityHidden(true)
            }
            .padding(.vertical, 12)
            .padding(.leading, 16)
            .padding(.trailing, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button("Remove", role: .destructive) { remove(plant.id) }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(plant.common), \(plant.sci), \(jobsLine(plant))")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction(named: "Remove") { remove(plant.id) }
        .accessibilityIdentifier("saved-\(plant.id)")
    }

    private func words(_ plant: Plant) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(plant.common)
                .font(.display(.title3))
                .foregroundStyle(Theme.ink)
            Text(plant.sci)
                .font(.bodySlanted(.subheadline, typeSize: typeSize))
                .foregroundStyle(Theme.inkSoft)
            Text(jobsLine(plant))
                .font(.body(15, .medium, relativeTo: .subheadline))
                .foregroundStyle(jobCount(plant.id) == 0 ? Theme.inkSoft : Theme.ink)
                .padding(.top, 2)
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
}
