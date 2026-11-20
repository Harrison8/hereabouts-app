// The saved plants on the Garden tab, as d1-my-garden.png draws them: one row per plant, in the order they were saved,
// with its photo, name, Latin name, how many of this week's jobs name it, and the photo credit. Tapping a row opens
// the plant's page. Swiping a row to the left shows a red button with a bin behind it, as Mail and Reminders do;
// tapping the button, or swiping on past half the row, unsaves the plant. VoiceOver offers the same as the action
// "Remove". A plant that is not native to the county chosen now stays here until it is removed.
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
    /// The plant whose row is swiped open, if any. Opening one row closes the others.
    @State private var openId: String?

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
                    .accessibilityIdentifier("savedCount")
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 12)

            VStack(spacing: 0) {
                ForEach(Array(plants.enumerated()), id: \.element.id) { i, plant in
                    if i > 0 { Theme.rule.frame(height: 1) }
                    SwipeToRemove(id: plant.id, openId: $openId, remove: { remove(plant.id) }) {
                        row(plant)
                    }
                }
            }
            .background(Color.white)
            .clipShape(RoundedRectangle(cornerRadius: Theme.brandCorner))
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
            // With a row swiped open, a tap only closes it, as in Mail.
            if openId != nil { openId = nil } else { open(plant.id) }
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

/// One saved plant's row that slides to the left over the red Remove button. A short swipe leaves the button showing,
/// 80 points wide; a swipe past half the row removes the plant at once; a swipe that goes mostly up or down is left to
/// the page's scrolling. The button exists only while the row is slid open, so VoiceOver and a closed row never meet
/// it.
private struct SwipeToRemove<Row: View>: View {
    let id: String
    @Binding var openId: String?
    let remove: () -> Void
    @ViewBuilder let row: () -> Row

    /// How far the row has slid to the left, as a negative number of points.
    @State private var offset: CGFloat = 0
    /// Where the row sat when the current sideways swipe began; nil when no sideways swipe is under way.
    @State private var start: CGFloat?
    @State private var width: CGFloat = 0

    private static let buttonWidth: CGFloat = 80

    var body: some View {
        row()
            .background(Color.white)
            .offset(x: offset)
            .simultaneousGesture(swipe)
            .frame(maxWidth: .infinity)
            .background(alignment: .trailing) {
                if offset < 0 { removeButton }
            }
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
            .onChange(of: openId) { _, now in
                if now != id, start == nil, offset != 0 { withAnimation(.snappy) { offset = 0 } }
            }
    }

    private var removeButton: some View {
        Button(action: removeNow) {
            Image(systemName: "trash")
                .font(.system(size: 20, weight: .regular))
                .foregroundStyle(Color.white)
                .frame(width: max(Self.buttonWidth, -offset))
                .frame(maxHeight: .infinity)
                .background(Color(.systemRed))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Remove")
        .accessibilityIdentifier("remove-\(id)")
    }

    private var swipe: some Gesture {
        DragGesture(minimumDistance: 16)
            .onChanged { value in
                let dx = value.translation.width
                if start == nil {
                    guard abs(dx) > abs(value.translation.height) else { return }
                    start = offset
                    if openId != id { openId = id }
                }
                offset = min(0, (start ?? 0) + dx)
            }
            .onEnded { value in
                guard let from = start else { return }
                start = nil
                let end = min(0, from + value.translation.width)
                if width > 0, -end > width * 0.55 {
                    removeNow()
                } else if -end > 40 {
                    withAnimation(.snappy) { offset = -Self.buttonWidth }
                    openId = id
                } else {
                    withAnimation(.snappy) { offset = 0 }
                    if openId == id { openId = nil }
                }
            }
    }

    private func removeNow() {
        withAnimation(.snappy) { offset = -max(width, Self.buttonWidth) }
        if openId == id { openId = nil }
        remove()
    }
}
