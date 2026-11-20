// "Where to buy", as where-to-buy-sheet.png draws it: a sheet over the plant page, half the screen high and pulled up
// to full height. A line saying how many sellers ship the plant to the person's state and whether any has a shop
// nearby; one row per seller in the website's order, with the seller, size and price; a nearby shop's row has a
// Directions button that opens Apple Maps at the same place as the website's Google Maps link; every other row opens
// the seller's page, the address made exactly as the website makes it (HereaboutsCore's Sellers.buyView with the
// app's channel). Every link opens in the phone's own browser. Below the rows: the website's line when no seller
// sells the plant to the state, and the commission note when any link earns one.
import HereaboutsCore
import SwiftUI

struct WhereToBuySheet: View {
    let plant: Plant
    let view: BuyView
    let location: Location?

    @Environment(AppModel.self) private var model
    @Environment(Connectivity.self) private var connectivity
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                if connectivity.isOffline { OfflineBanner() }
                ForEach(view.rows.indices, id: \.self) { i in
                    sellerRow(view.rows[i])
                    Theme.rule.frame(height: 1).padding(.leading, 16)
                }
                if let none = view.noSellerText {
                    Text(none)
                        .font(.body(16, .regular, relativeTo: .callout))
                        .foregroundStyle(Theme.ink)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 16)
                        .padding(.top, 16)
                        .accessibilityIdentifier("noSeller")
                }
                // At the largest text sizes the note scrolls with the rows, so it never covers half the sheet.
                if view.hasAffiliate && typeSize.isAccessibilitySize { commissionNote.padding(.top, 16) }
            }
            .padding(.bottom, 24)
        }
        .accessibilityIdentifier("whereToBuyRows")
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if view.hasAffiliate && !typeSize.isAccessibilitySize { commissionNote.background(Color.white) }
        }
        .background(Color.white)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Where to buy")
                    .font(.display(.title2))
                    .foregroundStyle(Theme.ink)
                    .accessibilityAddTraits(.isHeader)
                if let summary = Self.summary(plant: plant, view: view, location: location) {
                    Text(summary)
                        .font(.body(15, .regular, relativeTo: .subheadline))
                        .foregroundStyle(Theme.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.top, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
            Button("Done") { dismiss() }
                .font(.body(17, .semibold, relativeTo: .body))
                .foregroundStyle(Theme.moss)
                .frame(minWidth: 48, minHeight: 48)
                .accessibilityIdentifier("whereToBuyDone")
        }
        .padding(.leading, 16)
        .padding(.trailing, 8)
        .padding(.top, 16)
        .padding(.bottom, 8)
    }

    // MARK: Rows

    @ViewBuilder
    private func sellerRow(_ row: BuyRow) -> some View {
        if row.kind == "directions", let place = OutsideLink.appleMaps(row.directions) {
            let layout = typeSize.isAccessibilitySize
                ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
                : AnyLayout(HStackLayout(alignment: .center, spacing: 8))
            layout {
                info(row)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(row.name)
                    .accessibilityValue(Self.lines(row, location: location).joined(separator: ", "))
                    .accessibilityIdentifier("seller")
                Button {
                    model.track("local_vendor_click", ["plant": .string(plant.id), "vendor": .string(row.name)])
                    openURL(place)
                } label: {
                    // 48, not 44: on iOS 26 the half-height sheet floats slightly shrunk (about 96%), so a 44-point
                    // button measured 42 points on screen. 48 stays above 44 at that size.
                    Text("Directions")
                        .font(.body(16, .semibold, relativeTo: .callout))
                        .foregroundStyle(Theme.moss)
                        .padding(.horizontal, 14)
                        .frame(minHeight: 48)
                        .overlay(RoundedRectangle(cornerRadius: Theme.brandCorner).strokeBorder(Theme.moss, lineWidth: 1))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Directions to \(row.name)")
                .accessibilityHint("Opens outside the app")
                .accessibilityIdentifier("directions")
            }
            .padding(.vertical, 8)
            .padding(.leading, 16)
            .padding(.trailing, 16)
            .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
        } else {
            let url = OutsideLink.url(row.href)
            Button {
                guard let url else { return }
                var props: [String: EventValue] = ["plant": .string(plant.id), "vendor": .string(row.name),
                                                   "local": .bool(row.local), "affiliate": .bool(row.affiliate)]
                model.track("affiliate_click", props)
                if row.local {
                    props = ["plant": .string(plant.id), "vendor": .string(row.name)]
                    model.track("local_vendor_click", props)
                }
                openURL(url)
            } label: {
                HStack(spacing: 8) {
                    info(row)
                    Image(systemName: "arrow.up.forward.square")
                        .font(.system(size: 20))
                        .foregroundStyle(url == nil ? Theme.ruleStrong : Theme.moss)
                        .frame(width: 44, height: 44)
                }
                .padding(.vertical, 8)
                .padding(.leading, 16)
                .padding(.trailing, 4)
                .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(url == nil)
            .accessibilityLabel(row.name)
            .accessibilityValue(Self.lines(row, location: location).joined(separator: ", "))
            .accessibilityHint("Opens outside the app")
            .accessibilityIdentifier("seller")
        }
    }

    /// The seller's name, then how the person gets the plant and the size and price.
    private func info(_ row: BuyRow) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(row.name)
                .font(.body(17, .semibold, relativeTo: .body))
                .foregroundStyle(Theme.ink)
            ForEach(Self.lines(row, location: location), id: \.self) { line in
                Text(line)
                    .font(.body(14, .regular, relativeTo: .footnote))
                    .foregroundStyle(Theme.inkSoft)
                    .monospacedDigit()
            }
        }
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The lines under a seller's name. A shop nearby: "Pick up · 15 mi · Goshen, KY", the website's words. With no
    /// county set, where the seller ships, as the website says it. Then the size and price ("1-Pack · $69.00"), in
    /// the order the approved picture writes them.
    static func lines(_ row: BuyRow, location: Location?) -> [String] {
        var lines: [String] = []
        if row.local, let miles = row.miles {
            lines.append("Pick up \u{00B7} \(miles) mi \u{00B7} \(row.place ?? "Storefront")")
        } else if location == nil, let ships = row.ships, !ships.isEmpty {
            lines.append(ships)
        }
        let detail = [row.size, row.price].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " \u{00B7} ")
        if !detail.isEmpty { lines.append(detail) }
        return lines
    }

    // MARK: Words

    /// The line under the title: how many sellers ship the plant to the person's state, and whether any has a shop
    /// near them. nil when no seller sells it; the website's no-seller line says so instead.
    static func summary(plant: Plant, view: BuyView, location: Location?) -> String? {
        let rows = view.rows
        guard !rows.isEmpty else { return nil }
        guard let location else {
            return rows.count == 1 ? "1 seller sells \(plant.common)." : "\(rows.count) sellers sell \(plant.common)."
        }
        let near = rows.filter(\.local).count
        let ships = rows.count - near
        var parts: [String] = []
        if ships == 1 {
            parts.append("1 seller ships \(plant.common) to \(location.stateName).")
        } else if ships > 1 {
            parts.append("\(ships) sellers ship \(plant.common) to \(location.stateName).")
        }
        if near == 1 {
            parts.append("1 seller has a shop near you.")
        } else if near > 1 {
            parts.append("\(near) sellers have a shop near you.")
        } else {
            parts.append("None has a shop near you.")
        }
        return parts.joined(separator: " ")
    }

    /// The website's commission note, with its "How this works" link made a full-size button.
    private var commissionNote: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Each link opens in your browser. Some of these links earn us a commission.")
                .font(.body(13, .regular, relativeTo: .footnote))
                .foregroundStyle(Theme.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("commissionNote")
            Button {
                openURL(SettingsView.affiliateURL)
            } label: {
                HStack(spacing: 4) {
                    Text("How this works").underline()
                    Image(systemName: "arrow.up.forward.square")
                }
                .font(.body(15, .medium, relativeTo: .subheadline))
                .foregroundStyle(Theme.moss)
                .frame(minHeight: 48)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("Opens outside the app")
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .overlay(alignment: .top) { Theme.rule.frame(height: 1) }
    }
}

/// The addresses the plant page hands to the phone's browser.
enum OutsideLink {
    /// A seller's address from HereaboutsCore, as a URL the phone can open, or nil when the website would show no link
    /// ("#", or anything but a web address). An address the website writes correctly comes out unchanged, character
    /// for character. Any other character is written as %XX, its UTF-8 bytes: a space as %20, an accented letter as its
    /// bytes, and a second "#" as %23. For a space or an accented letter that is what a browser does too. A browser
    /// leaves several marks as they are that this escapes, such as "[", "]", "|", "^" and a second "#", so for an
    /// address holding one of those the phone gets an escaped form of the website's link. It still opens the same
    /// page. No seller address in the bundled data holds one; the every-link unit test checks that.
    static func url(_ href: String) -> URL? {
        guard href != "#", let colon = href.firstIndex(of: ":") else { return nil }
        let scheme = href[..<colon].lowercased()
        guard scheme == "http" || scheme == "https" else { return nil }
        return URL(string: escaped(href))
    }

    static func escaped(_ href: String) -> String {
        var out = ""
        var seenHash = false
        for scalar in href.unicodeScalars {
            if scalar == "#" {
                out += seenHash ? "%23" : "#"
                seenHash = true
            } else if allowed.contains(scalar) {
                out.unicodeScalars.append(scalar)
            } else {
                for byte in String(scalar).utf8 { out += String(format: "%%%02X", byte) }
            }
        }
        return out
    }

    /// The characters kept as they are: letters, digits, the unreserved marks, the separators (RFC 3986), and "%" for the
    /// escapes already in the address. Everything else is escaped, including marks a browser would leave alone in
    /// some parts of an address, such as "[", "]", "|" and "^".
    private static let allowed: Set<Unicode.Scalar> = Set(
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?@!$&'()*+,;=%".unicodeScalars)

    /// Apple Maps directions to the same place as the website's Google Maps link (its `destination`), or nil when the
    /// link has none. The person's own position is not in it, as on the website.
    static func appleMaps(_ directions: String?) -> URL? {
        guard let directions, let parts = URLComponents(string: directions),
              let place = parts.queryItems?.first(where: { $0.name == "destination" })?.value, !place.isEmpty
        else { return nil }
        var maps = URLComponents()
        maps.scheme = "https"
        maps.host = "maps.apple.com"
        maps.path = "/"
        maps.queryItems = [URLQueryItem(name: "daddr", value: place)]
        return maps.url
    }
}
