// The first-open screen, as the approved picture draws it: the wordmark, "Native plants for your county", a ZIP code
// field with the number pad up, the county found shown under it as the ZIP is typed, and the button pinned above the
// keyboard. The two links for a town or street address, or the phone's own location, are pinned under the button, so
// both can be tapped while the number pad is up. Everything above the button scrolls, and the field and the county
// found are scrolled into view as the keyboard comes up. At the largest text sizes the button and links would leave no
// room for the field, so they follow the county in the scroll area instead. Text follows the phone's text size at every
// size. The line that sat under the title was cut on 11-23: the title, the field's label and the button say it.
// Settings opens the same screen in a sheet titled "Change location", without the wordmark or title.
//
// Everything is looked up on the phone from the tables the app ships with, apart from a street address, which goes to
// the Census Bureau's address finder as on the website. The county is set only when the button is tapped. The
// location_set event says how it was found, with the website's values (zip, city, county or address), or device for the
// phone's location, and never carries what was typed or a position.
import HereaboutsCore
import SwiftUI

/// The website's lookup (index.html, lookup()), in the same order and with the same messages.
enum FirstOpenLookup {
    enum Outcome: Equatable, Sendable {
        /// A county, and how it was found: the website's "zip", "city", "county" or "address", or "device".
        case found(Location, method: String)
        /// The website's message for why no county was found.
        case message(String)
    }

    static let emptyMessage = "Type your city, county or ZIP code first."
    static let outsideMessage = "We only cover the lower 48 states for now."
    static let placeNotFoundMessage = "We couldn't find that place. Try your ZIP code, or a city and state like Dayton, OH."
    static let noMatchMessage = "We couldn't match that address. Try your 5-digit ZIP code instead."
    static let notRespondingMessage = "The address lookup isn't responding. Try your 5-digit ZIP code instead."
    static func zipNotFoundMessage(_ zip: String) -> String {
        "We couldn't find ZIP \(zip). Try a nearby ZIP or your street address."
    }

    /// A ZIP code as it is typed: nil until it has five digits, then its county or the website's message.
    static func zip(_ digits: String, tables: Tables) -> Outcome? {
        guard digits.count == 5 else { return nil }
        return zipOutcome(digits, tables: tables)
    }

    private static func zipOutcome(_ zip: String, tables: Tables) -> Outcome {
        switch LocationLookup.zip(zip, tables: tables) {
        case .found(let location): return .found(location, method: "zip")
        case .outsideUS48: return .message(outsideMessage)
        case .notFound: return .message(zipNotFoundMessage(zip))
        }
    }

    /// The phone's position: the county of the nearest ZIP code, kept as that ZIP code's own point.
    static func device(lat: Double, lon: Double, tables: Tables) -> Outcome {
        switch LocationLookup.nearest(lat: lat, lon: lon, tables: tables) {
        case .found(let location): return .found(location, method: "device")
        case .outsideUS48, .notFound: return .message(outsideMessage)
        }
    }

    /// A town, county, state, ZIP code or street address, as the website's form takes it.
    static func text(_ text: String, tables: Tables, address: AddressLookup) async -> Outcome {
        let addressText: String
        switch LocationLookup.classify(text) {
        case .empty:
            return .message(emptyMessage)
        case .state(let code, _):
            return .message(LocationLookup.stateMessage(code))
        case .zip(let zip):
            return zipOutcome(zip, tables: tables)
        case .place(let place):
            switch LocationLookup.place(place, tables: tables) {
            case .found(let location, let kind):
                // The website sends the kind of place found: "city" or "county".
                return .found(location, method: kind.rawValue)
            case .ambiguous(let states):
                return .message(LocationLookup.ambiguousMessage(place, states: states))
            case .outsideUS48:
                return .message(outsideMessage)
            case .notFound:
                // A name we don't know that has a number in it may be a street address. (/\d/)
                guard place.unicodeScalars.contains(where: isASCIIDigit) else {
                    return .message(placeNotFoundMessage)
                }
                addressText = place
            }
        case .address(let typed):
            addressText = typed
        }
        do {
            switch try await address.county(for: addressText) {
            case .found(let location): return .found(location, method: "address")
            case .outsideUS48: return .message(outsideMessage)
            case .notFound: return .message(noMatchMessage)
            }
        } catch {
            // The website says the same for no answer and for no answer in time.
            return .message(notRespondingMessage)
        }
    }

    static func isASCIIDigit(_ u: Unicode.Scalar) -> Bool { u.value >= 48 && u.value <= 57 }

    /// The first five ASCII digits in what was typed or pasted.
    static func zipDigits(_ typed: String) -> String {
        var digits = String.UnicodeScalarView()
        for u in typed.unicodeScalars where isASCIIDigit(u) && digits.count < 5 { digits.append(u) }
        return String(digits)
    }
}

/// The website's superseded(): every lookup takes a new number, and a lookup whose number is no longer the latest sets
/// nothing and sends nothing. The number moves on when another lookup starts, when what was typed changes, and when the
/// screen closes (Cancel in the Change location sheet).
@MainActor
final class LatestLookup {
    private var latest = 0

    /// Runs one lookup. Its answer, or nil when it was overtaken while it waited.
    func run<T>(_ work: @MainActor () async -> T) async -> T? {
        latest += 1
        let mine = latest
        let answer = await work()
        return mine == latest ? answer : nil
    }

    /// Any lookup still waiting will set nothing.
    func supersede() { latest += 1 }
}

struct FirstOpenView: View {
    enum Mode: Hashable {
        case zip, text
    }

    /// Called after the county is set, for Settings to close its sheet.
    var onChosen: (() -> Void)? = nil
    /// In the Settings sheet: the Cancel button, which first stops any lookup still waiting.
    var onCancel: (() -> Void)? = nil

    @Environment(AppModel.self) private var model

    @State private var mode: Mode = .zip
    @State private var zip = ""
    @State private var text = ""
    @State private var outcome: FirstOpenLookup.Outcome?
    @State private var busy = false
    /// Made on the first tap of "Use my location", so the phone's location service is not touched before then.
    @State private var myLocation: MyLocation?
    @State private var latest = LatestLookup()
    @FocusState private var focus: Mode?
    @Environment(\.dynamicTypeSize) private var typeSize

    /// In the Settings sheet, which has its own "Change location" title.
    private var inSheet: Bool { onChosen != nil }

    static let deniedMessage = "Hereabouts can't use your location. Try your 5-digit ZIP code instead."
    static let unavailableMessage = "We couldn't find your location. Try your 5-digit ZIP code instead."

    /// Heights measured on screen, none of which depends on where the button and links are placed, so the choice made
    /// from them cannot flip back and forth. Zero until the first measurement.
    @State private var screenHeight: CGFloat = 0
    @State private var formHeight: CGFloat = 0
    @State private var buttonHeight: CGFloat = 0
    @State private var linksHeight: CGFloat = 0

    /// The button and both links stay pinned above the keyboard while they leave room for the label, the field and the
    /// county. At the largest text sizes they do not, so the two links follow the county in the scroll area instead;
    /// the button stays pinned, so the screen's one action is always in view.
    private var pinsActions: Bool {
        screenHeight == 0 || pinnedHeight + formHeight + 16 <= screenHeight
    }

    /// The button and links with the pinned area's padding above and below them.
    private var pinnedHeight: CGFloat { buttonHeight + 4 + linksHeight + 14 }

    /// What the pinned area takes: the button and both links, or the button alone at the largest text sizes.
    private var pinnedNow: CGFloat { pinsActions ? pinnedHeight : buttonHeight + 14 }

    /// The number pad's Done is shown at the accessibility text sizes, where the scrolling needs a way to close the pad.
    private var showsDone: Bool { mode == .zip && typeSize.isAccessibilitySize }

    var body: some View {
        // The stack measures the height between the top bar and the keyboard, which the scroll view alone may not give,
        // since it can reach under them.
        VStack(spacing: 0) { scrollArea }
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { screenHeight = $0 }
            .task {
                // Let the screen settle before the keyboard comes up.
                try? await Task.sleep(for: .milliseconds(400))
                focus = mode
            }
            .onChange(of: mode) { _, newMode in
                latest.supersede()
                busy = false
                outcome = newMode == .zip ? FirstOpenLookup.zip(zip, tables: model.tables) : nil
                focus = newMode
            }
            .toolbar {
                // The number pad has no return key, so it gets the system's Done to close it.
                if showsDone {
                    ToolbarItemGroup(placement: .keyboard) {
                        Spacer()
                        Button("Done") { focus = nil }
                    }
                }
                if let onCancel {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") {
                            // A lookup still waiting sets nothing, even while the sheet is closing.
                            latest.supersede()
                            onCancel()
                        }
                    }
                }
            }
            // The screen closed some other way, such as the sheet swiped down.
            .onDisappear { latest.supersede() }
    }

    private var scrollArea: some View {
        ScrollViewReader { scroller in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    header
                    form
                    if !pinsActions {
                        links
                            .padding(.top, 8)
                            // Room to scroll the links fully clear of the pinned button.
                            .padding(.bottom, 16 + pinnedNow)
                    }
                }
                .padding(.horizontal, 24)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            // Only tapping the title closes the number pad, so scrolling at large text sizes keeps it up.
            .scrollDismissesKeyboard(.never)
            .background(Color.white)
            // Nothing may show under the clock and the status icons: a solid white band covers the status bar.
            .overlay(alignment: .top) {
                if !inSheet {
                    Color.clear.frame(height: 0)
                        .background(Color.white.ignoresSafeArea(edges: .top))
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                VStack(alignment: .leading, spacing: 4) {
                    button
                    if pinsActions { links }
                }
                .padding(.horizontal, 24)
                .padding(.top, 10)
                // The system's Done floats over the content just above the number pad and takes the touches there, so
                // the pinned area leaves it room instead of putting a link or the button underneath it.
                .padding(.bottom, showsDone && focus == .zip ? 52 : 4)
                .background(Color.white)
            }
            // The field and the county found are kept in view as the keyboard comes up and as the county appears.
            .onChange(of: focus) { _, _ in keepFormInView(scroller) }
            .onChange(of: outcome) { _, _ in keepFormInView(scroller) }
            .onChange(of: screenHeight) { _, _ in keepFormInView(scroller) }
            .onChange(of: pinsActions) { _, _ in keepFormInView(scroller) }
            .onChange(of: formHeight) { _, _ in keepFormInView(scroller) }
        }
    }

    private func keepFormInView(_ scroller: ScrollViewProxy) {
        guard focus != nil || outcome != nil else { return }
        // The county found is kept in view under the field. Before there is one, when the label and the field are
        // taller than the room above the button (the largest text with the number pad up), the field wins.
        let fits = screenHeight == 0 || formHeight <= screenHeight - pinnedNow
        withAnimation {
            if fits || outcome != nil {
                // With a county or a message to show, its bottom goes right above the pinned button, even when that
                // scrolls the label and part of the field off the top. The answer to what was typed is never covered.
                scroller.scrollTo(Self.statusID, anchor: .bottom)
            } else {
                scroller.scrollTo(Self.formID, anchor: .top)
            }
        }
    }

    private static let statusID = "status"
    private static let formID = "form"

    // MARK: Parts

    private var fieldFont: Font { .body(24, .regular, relativeTo: .title2) }

    /// The approved picture's wordmark and title. Tapping them closes the number pad.
    @ViewBuilder
    private var header: some View {
        if !inSheet {
            VStack(alignment: .leading, spacing: 0) {
                Image("Wordmark")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 168)
                    .accessibilityLabel("Hereabouts")
                    .padding(.bottom, 32)
                Text("Native plants\nfor your county")
                    .font(.display(.largeTitle))
                    .foregroundStyle(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityLabel("Native plants for your county")
                    .accessibilityAddTraits(.isHeader)
            }
            .padding(.top, 32)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture { focus = nil }
        }
    }

    /// The label, the field and the county found: always in view, above the button.
    private var form: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(mode == .zip ? "ZIP code" : "Town or street address")
                .font(.body(15, .semibold, relativeTo: .subheadline))
                .foregroundStyle(Theme.ink)
                .padding(.top, inSheet ? 16 : 24)
            field
                .padding(.top, 8)
            // One line is kept for the county, so the layout does not move when it appears.
            ZStack(alignment: .topLeading) {
                Color.clear.frame(height: 24)
                status
            }
            .padding(.top, 8)
            .id(Self.statusID)
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { formHeight = $0 }
        .id(Self.formID)
    }

    @ViewBuilder
    private var field: some View {
        Group {
            if mode == .zip {
                TextField("", text: $zip)
                    .keyboardType(.numberPad)
                    .textContentType(.postalCode)
                    .focused($focus, equals: .zip)
                    .accessibilityLabel("ZIP code")
                    .accessibilityIdentifier("zipField")
                    .onChange(of: zip) { _, typed in zipChanged(typed) }
            } else {
                TextField("", text: $text)
                    .keyboardType(.default)
                    .textContentType(.fullStreetAddress)
                    .textInputAutocapitalization(.words)
                    .autocorrectionDisabled()
                    .submitLabel(.search)
                    .focused($focus, equals: .text)
                    .accessibilityLabel("Town or street address")
                    .accessibilityIdentifier("placeField")
                    .onChange(of: text) { _, _ in
                        // A lookup still waiting for the old text sets nothing.
                        latest.supersede()
                        busy = false
                        outcome = nil
                    }
                    .onSubmit { Task { await lookUpText(thenChoose: false) } }
            }
        }
        .font(fieldFont)
        .foregroundStyle(Theme.ink)
        .tint(Theme.moss)
        .padding(.horizontal, 16)
        .frame(minHeight: 56)
        .overlay(
            RoundedRectangle(cornerRadius: Theme.brandCorner)
                .strokeBorder(Theme.moss, lineWidth: 2)
        )
    }

    @ViewBuilder
    private var status: some View {
        if busy {
            Text("Looking up…")
                .font(.body(.body))
                .foregroundStyle(Theme.inkSoft)
        } else if case .found(let location, _) = outcome {
            HStack(spacing: 10) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 22))
                    .foregroundStyle(Theme.moss)
                    .accessibilityHidden(true)
                Text("\(location.countyName), \(location.stateName)")
                    .font(.body(17, .medium, relativeTo: .body))
                    .foregroundStyle(Theme.moss)
            }
        } else if case .message(let message) = outcome {
            Text(message)
                .font(.body(.body))
                .foregroundStyle(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("locationMessage")
        }
    }

    private var links: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(mode == .zip ? "Use a town or street address instead" : "Use a ZIP code instead") {
                mode = mode == .zip ? .text : .zip
            }
            Button("Use my location") {
                Task { await useMyLocation() }
            }
        }
        .buttonStyle(LinkButtonStyle())
        .disabled(busy)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { linksHeight = $0 }
    }

    private var button: some View {
        Button {
            Task { await choose() }
        } label: {
            Text(buttonTitle)
                .font(.body(17, .semibold, relativeTo: .headline))
        }
        // The style draws both looks itself. A disabled plain button is dimmed by the system, which made the waiting
        // words about 2 to 1; .disabled stays so VoiceOver still says the button is unavailable.
        .buttonStyle(ContinueButtonStyle(waiting: !buttonEnabled))
        .disabled(!buttonEnabled)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { buttonHeight = $0 }
        .accessibilityIdentifier("continue")
    }

    // MARK: What the button says and does

    private var found: (location: Location, method: String)? {
        if case .found(let location, let method) = outcome { return (location, method) }
        return nil
    }

    /// The picture's "Show 122 plants": how many plants are native to the county found, or the website's "Show my
    /// plants" before one is found.
    private var buttonTitle: String {
        guard let found else { return "Show my plants" }
        let count = model.data.plants.filter { LocationLookup.nativeHere($0.id, found.location, geo: model.data.geo) }.count
        switch count {
        case 0: return "Show my plants"
        case 1: return "Show 1 plant"
        default: return "Show \(count) plants"
        }
    }

    /// A ZIP code needs a county found first. Typed text can also be looked up by the button itself.
    private var buttonEnabled: Bool {
        if busy { return false }
        if found != nil { return true }
        return mode == .text && outcome == nil && !text.trimmingCharacters(in: .whitespaces).isEmpty
    }

    private func choose() async {
        if found == nil, mode == .text {
            await lookUpText(thenChoose: true)
            return
        }
        guard let found else { return }
        focus = nil
        model.setLocation(found.location, method: found.method)
        onChosen?()
    }

    // MARK: Lookups

    private func zipChanged(_ typed: String) {
        // Only the first five digits count; anything else is taken out as it is typed or pasted.
        let digits = FirstOpenLookup.zipDigits(typed)
        if digits != typed {
            zip = digits
            return
        }
        // "Use my location" still waiting sets nothing once a ZIP code is typed.
        latest.supersede()
        busy = false
        outcome = FirstOpenLookup.zip(digits, tables: model.tables)
    }

    private func lookUpText(thenChoose: Bool) async {
        guard !busy else { return }
        busy = true
        let typed = text
        let tables = model.tables
        // Nil when the text changed, another lookup started or the screen closed while the Census was asked.
        guard let result = await latest.run({
            await FirstOpenLookup.text(typed, tables: tables, address: AddressLookup(tables: tables))
        }) else { return }
        busy = false
        outcome = result
        if thenChoose, found != nil { await choose() }
    }

    private func useMyLocation() async {
        guard !busy else { return }
        busy = true
        focus = nil
        let finder = myLocation ?? MyLocation()
        myLocation = finder
        guard let result = await latest.run({ await finder.position() }) else { return }
        busy = false
        switch result {
        case .position(let lat, let lon):
            outcome = FirstOpenLookup.device(lat: lat, lon: lon, tables: model.tables)
        case .denied:
            outcome = .message(Self.deniedMessage)
        case .unavailable:
            outcome = .message(Self.unavailableMessage)
        }
    }
}

/// A plain moss link, for the two other ways to find the county.
private struct LinkButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body(17, .medium, relativeTo: .body))
            .foregroundStyle(Theme.moss)
            .opacity(configuration.isPressed || !isEnabled ? 0.5 : 1)
            .frame(minHeight: 44, alignment: .leading)
            .contentShape(Rectangle())
    }
}

/// The first-open button. Waiting for a county: white with a hairline and grey words that read at 4.5 to 1 or better.
/// Ready: moss with white words. The colours are the style's own, so the system's dimming of a disabled button does
/// not change them.
struct ContinueButtonStyle: ButtonStyle {
    let waiting: Bool

    static let waitingTextHex = Theme.inkFaintHex
    static let waitingBackgroundHex: UInt32 = 0xFFFFFF

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(waiting ? Color(hex: Self.waitingTextHex) : Color.white)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(waiting ? Color(hex: Self.waitingBackgroundHex) : Theme.moss, in: RoundedRectangle(cornerRadius: Theme.brandCorner))
            .overlay {
                if waiting {
                    RoundedRectangle(cornerRadius: Theme.brandCorner).strokeBorder(Theme.ruleStrong, lineWidth: 1)
                }
            }
            .opacity(configuration.isPressed ? 0.85 : 1)
    }
}
