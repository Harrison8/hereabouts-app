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
    /// Whether the rows follow a sideways swipe. Off while another tab is showing or a plant page is open over the
    /// list, so the swipe's pan is not attached anywhere while the person works on another screen.
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
    /// Where the row is on the screen, so the swipe answers only a finger that starts on it.
    @State private var frame = RowFrame()

    // A generic view cannot hold a stored static value, so the width is worked out each time it is asked for.
    private static var buttonWidth: CGFloat { 80 }

    var body: some View {
        row()
            .background(Color.white)
            .offset(x: offset)
            .modifier(SidewaysSwipe(on: swipeable, frame: frame, changed: changed, ended: ended))
            .frame(maxWidth: .infinity)
            .background(alignment: .trailing) {
                if offset < 0 { removeButton }
            }
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frame.rect = $0 }
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
                .background(Theme.error)
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

    /// The swipe stopped `dx` points from where it began: the finger lifted (`lifted`), or the phone called it off.
    private func ended(_ dx: CGFloat, lifted: Bool) {
        guard let from = start else { return }
        swiped()
        start = nil
        switch SwipeEnd.settle(from: from, dx: dx, width: width, lifted: lifted) {
        case .remove:
            removeNow()
        case .open:
            withAnimation(.snappy) { offset = -Self.buttonWidth }
            openId = id
        case .shut:
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

/// Where a saved plant's row comes to rest when a sideways swipe stops.
enum SwipeEnd: Equatable {
    /// The plant is unsaved.
    case remove
    /// The row rests slid open, with the Remove button showing.
    case open
    /// The row slides shut.
    case shut

    /// `from` is where the row sat when the swipe began and `dx` how far the finger moved since, in points, negative
    /// to the left; `width` is the row's width. A swipe past 55% of the row removes the plant, and one past 40 points
    /// leaves the button showing. When the phone calls the swipe off (`lifted` is false: a call, an alert, a swipe
    /// from the screen's edge), the row goes back to where it was and the plant is never removed.
    static func settle(from: CGFloat, dx: CGFloat, width: CGFloat, lifted: Bool) -> SwipeEnd {
        guard lifted else { return from < 0 ? .open : .shut }
        let end = min(0, from + dx)
        if width > 0, -end > width * 0.55 { return .remove }
        return -end > 40 ? .open : .shut
    }
}

/// Where a row is on the screen, in the window's points. A class, so updating it as the page scrolls does not redraw
/// the row.
private final class RowFrame {
    var rect = CGRect.null
}

/// Follows a sideways swipe and leaves an up-or-down one to the page's scrolling. From iOS 18 a SwiftUI drag inside a
/// scroll view keeps the page from scrolling, so there the swipe is UIKit's pan, which only starts when the finger
/// moves more sideways than up or down. iOS 17 keeps SwiftUI's drag, which scrolls alongside.
private struct SidewaysSwipe: ViewModifier {
    /// When false, the iOS 18 pan is switched off and takes no touch at all. The iOS 17 drag belongs to the row alone,
    /// so it stays.
    let on: Bool
    /// Where the row is on the screen. The iOS 18 pan ignores a finger that starts anywhere else.
    let frame: RowFrame
    let changed: (CGFloat) -> Void
    /// The swipe stopped this many points from where it began; true when the finger lifted, false when the phone
    /// called the swipe off.
    let ended: (CGFloat, Bool) -> Void

    /// The current iOS 17 drag has gone sideways.
    @State private var sideways = false
    /// True while any iOS 17 drag is under way. SwiftUI puts it back to false however the drag stops, including when
    /// the phone calls it off, which onEnded never hears about.
    @GestureState private var dragging = false

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            // Always attached and switched on or off, rather than added and taken away: SwiftUI may attach the pan to a
            // view shared with the other tabs, and a switched-off pan is sure to take no touch there.
            content.gesture(SidewaysPan(on: on, frame: frame, changed: changed, ended: ended))
        } else {
            content
                .simultaneousGesture(
                    DragGesture(minimumDistance: 16)
                        .updating($dragging) { _, state, _ in state = true }
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
                            ended(value.translation.width, true)
                        }
                )
                .onChange(of: dragging) { _, now in
                    // A drag that stopped while still marked sideways was called off: onEnded clears the mark first
                    // when the finger lifts.
                    guard !now, sideways else { return }
                    sideways = false
                    ended(0, false)
                }
        }
    }
}

@available(iOS 18.0, *)
private struct SidewaysPan: UIGestureRecognizerRepresentable {
    let on: Bool
    let frame: RowFrame
    let changed: (CGFloat) -> Void
    let ended: (CGFloat, Bool) -> Void

    func makeCoordinator(converter: CoordinateSpaceConverter) -> Coordinator { Coordinator(frame: frame) }

    func makeUIGestureRecognizer(context: Context) -> UIPanGestureRecognizer {
        let pan = UIPanGestureRecognizer()
        pan.delegate = context.coordinator
        context.coordinator.on = on
        pan.isEnabled = on
        return pan
    }

    func updateUIGestureRecognizer(_ recognizer: UIPanGestureRecognizer, context: Context) {
        context.coordinator.on = on
        recognizer.isEnabled = on
    }

    func handleUIGestureRecognizerAction(_ recognizer: UIPanGestureRecognizer, context: Context) {
        let dx = recognizer.translation(in: recognizer.view).x
        switch recognizer.state {
        case .began, .changed: changed(dx)
        case .ended: ended(dx, true)
        // The phone called the swipe off: the row goes back and the plant is never removed.
        case .cancelled, .failed: ended(dx, false)
        default: break
        }
    }

    @MainActor
    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        let frame: RowFrame
        /// Whether the pan is switched on: the Garden tab is showing with no plant page open over it.
        var on = false

        init(frame: RowFrame) { self.frame = frame }

        /// Takes only a finger that starts on its own row, and only while switched on. SwiftUI may attach the pan to a
        /// view that holds more than the row, so without this a touch elsewhere on the screen could reach it. Before
        /// the row has a place on the screen, it takes nothing.
        func gestureRecognizer(_ recognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            let rect = frame.rect
            return on && !rect.isEmpty && rect.contains(touch.location(in: nil))
        }

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
