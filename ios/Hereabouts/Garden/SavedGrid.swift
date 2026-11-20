// The saved plants on the Garden tab, as d1-my-garden.png draws them: one row per plant, in the order they were saved,
// with its photo, name, Latin name, how many of this week's jobs name it, and the photo credit. Tapping a row opens
// the plant's page. Swiping a row to the left shows a red button with a bin behind it, as Mail and Reminders do;
// tapping the button, or swiping on past half the row, unsaves the plant. VoiceOver offers the same as the action
// "Remove". A plant that is not native to the county chosen now stays here until it is removed.
import HereaboutsCore
import SwiftUI
import UIKit

struct SavedGrid: View {
    let plants: [Plant]
    let photos: PhotoCache?
    /// How many of this week's jobs name the plant.
    let jobCount: (String) -> Int
    let open: (String) -> Void
    let remove: (String) -> Void
    /// Whether the rows follow a sideways swipe. Off while another tab is showing, so the swipe's pan is not attached
    /// anywhere while the user works on the Plants or Settings tab.
    let swipeable: Bool

    @Environment(\.dynamicTypeSize) private var typeSize
    /// The plant whose row is swiped open, if any. Opening one row closes the others.
    @State private var openId: String?
    /// When a sideways swipe last moved a row.
    @State private var swipes = SwipeClock()

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
                    SwipeToRemove(id: plant.id, openId: $openId, swiped: { swipes.mark() },
                                  remove: { remove(plant.id) }, swipeable: swipeable) {
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
            // The phone also reports a tap as the finger lifts at the end of a sideways swipe. That tap is ignored, or
            // it would close the row the swipe just opened, or open the page of a plant the swipe just removed.
            guard !swipes.justSwiped else { return }
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

/// When a sideways swipe last moved a saved plant's row. A class, so marking it does not redraw the rows.
private final class SwipeClock {
    private var last = Date.distantPast

    func mark() { last = Date() }

    /// A swipe moved a row, or ended, in the last half second.
    var justSwiped: Bool { Date().timeIntervalSince(last) < 0.5 }
}

/// One saved plant's row that slides to the left over the red Remove button. A short swipe leaves the button showing,
/// 80 points wide; a swipe past half the row removes the plant at once; a swipe that goes mostly up or down is left to
/// the page's scrolling. The button exists only while the row is slid open, so VoiceOver and a closed row never meet
/// it.
private struct SwipeToRemove<Row: View>: View {
    let id: String
    @Binding var openId: String?
    /// Called each time a sideways swipe moves the row, and when it ends.
    let swiped: () -> Void
    let remove: () -> Void
    /// Whether the row follows a sideways swipe at all.
    let swipeable: Bool
    @ViewBuilder let row: () -> Row

    /// How far the row has slid to the left, as a negative number of points.
    @State private var offset: CGFloat = 0
    /// Where the row sat when the current sideways swipe began; nil when no sideways swipe is under way.
    @State private var start: CGFloat?
    @State private var width: CGFloat = 0

    // A generic view cannot hold a stored static value, so the width is worked out each time it is asked for.
    private static var buttonWidth: CGFloat { 80 }

    var body: some View {
        row()
            .background(Color.white)
            .offset(x: offset)
            .modifier(SidewaysSwipe(on: swipeable, changed: changed, ended: ended))
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

    /// The finger has moved `dx` points sideways since the swipe began.
    private func changed(_ dx: CGFloat) {
        swiped()
        if start == nil {
            start = offset
            if openId != id { openId = id }
        }
        offset = min(0, (start ?? 0) + dx)
    }

    /// The finger lifted, or the phone called the swipe off, `dx` points from where it began.
    private func ended(_ dx: CGFloat) {
        guard let from = start else { return }
        swiped()
        start = nil
        let end = min(0, from + dx)
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

    private func removeNow() {
        withAnimation(.snappy) { offset = -max(width, Self.buttonWidth) }
        if openId == id { openId = nil }
        remove()
    }
}

/// Follows a sideways swipe and leaves an up-or-down one to the page's scrolling. From iOS 18 a SwiftUI drag inside a
/// scroll view keeps the page from scrolling, so there the swipe is UIKit's pan, which only starts when the finger
/// moves more sideways than up or down. iOS 17 keeps SwiftUI's drag, which scrolls alongside.
private struct SidewaysSwipe: ViewModifier {
    /// When false, the iOS 18 pan is left off entirely. The iOS 17 drag runs alongside other gestures, so it stays.
    let on: Bool
    let changed: (CGFloat) -> Void
    let ended: (CGFloat) -> Void

    @State private var sideways = false

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            if on {
                content.gesture(SidewaysPan(changed: changed, ended: ended))
            } else {
                content
            }
        } else {
            content.simultaneousGesture(
                DragGesture(minimumDistance: 16)
                    .onChanged { value in
                        if !sideways {
                            guard abs(value.translation.width) > abs(value.translation.height) else { return }
                            sideways = true
                        }
                        changed(value.translation.width)
                    }
                    .onEnded { value in
                        guard sideways else { return }
                        sideways = false
                        ended(value.translation.width)
                    }
            )
        }
    }
}

@available(iOS 18.0, *)
private struct SidewaysPan: UIGestureRecognizerRepresentable {
    let changed: (CGFloat) -> Void
    let ended: (CGFloat) -> Void

    func makeCoordinator(converter: CoordinateSpaceConverter) -> Coordinator { Coordinator() }

    func makeUIGestureRecognizer(context: Context) -> UIPanGestureRecognizer {
        let pan = UIPanGestureRecognizer()
        pan.delegate = context.coordinator
        return pan
    }

    func handleUIGestureRecognizerAction(_ recognizer: UIPanGestureRecognizer, context: Context) {
        let dx = recognizer.translation(in: recognizer.view).x
        switch recognizer.state {
        case .began, .changed: changed(dx)
        case .ended, .cancelled, .failed: ended(dx)
        default: break
        }
    }

    @MainActor
    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        /// Starts only when the finger moves more sideways than up or down.
        func gestureRecognizerShouldBegin(_ recognizer: UIGestureRecognizer) -> Bool {
            guard let pan = recognizer as? UIPanGestureRecognizer else { return true }
            let speed = pan.velocity(in: pan.view)
            return abs(speed.x) > abs(speed.y)
        }

        /// The page's scrolling may run alongside, so neither cancels the other.
        func gestureRecognizer(_ recognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            true
        }
    }
}
