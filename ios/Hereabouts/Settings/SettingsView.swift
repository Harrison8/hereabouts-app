// The Settings tab, as the approved picture draws it: the county with a Change button (which opens the first-open
// screen as a sheet), the Saturday reminders switch, the privacy policy and "How affiliate links work" (both open in
// Safari, never inside the app), the photo credits, and the app's version and the date of the plant and seller data
// it is using.
import HereaboutsCore
import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @State private var changingLocation = false

    static let privacyURL = URL(string: "https://hereabouts.garden/privacy.html")!
    static let affiliateURL = URL(string: "https://hereabouts.garden/affiliate-disclosure.html")!

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    groupLabel("Your location")
                    SettingsGroup {
                        SettingsRow {
                            Text(model.location.map { "\($0.countyName), \($0.stateName)" } ?? "")
                                .foregroundStyle(Theme.ink)
                            Spacer(minLength: 12)
                            Button {
                                changingLocation = true
                            } label: {
                                // The whole 44-point area answers a tap, not only the word.
                                Text("Change")
                                    .font(.body(17, .medium, relativeTo: .body))
                                    .foregroundStyle(Theme.moss)
                                    .frame(minWidth: 44, minHeight: 44)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("changeLocation")
                                .accessibilityLabel("Change location")
                        }
                    }

                    groupLabel("Reminders")
                    SettingsGroup {
                        SettingsRow {
                            Toggle("Saturday reminders", isOn: $model.remindersOn)
                                .tint(Theme.moss)
                                .foregroundStyle(Theme.ink)
                        }
                    }
                    Text("Each Saturday at 8:00 you’ll get the week’s garden jobs for the plants you saved.")
                        .font(.body(15, .regular, relativeTo: .subheadline))
                        .foregroundStyle(Theme.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 32)
                        .padding(.top, 8)

                    groupLabel("About Hereabouts")
                    SettingsGroup {
                        outsideLink("Privacy policy", Self.privacyURL)
                        Divider().overlay(Theme.rule)
                        outsideLink("How affiliate links work", Self.affiliateURL)
                        Divider().overlay(Theme.rule)
                        NavigationLink {
                            PhotoCreditsView()
                        } label: {
                            SettingsRow {
                                Text("Photo credits and licenses")
                                    .foregroundStyle(Theme.ink)
                                Spacer(minLength: 12)
                                Image(systemName: "chevron.right")
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundStyle(Theme.inkSoft.opacity(0.6))
                                    .accessibilityHidden(true)
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Photo credits and licenses")
                    }

                    groupLabel("This app")
                    SettingsGroup {
                        SettingsRow {
                            Text("Version")
                                .foregroundStyle(Theme.ink)
                            Spacer(minLength: 12)
                            Text(Self.versionText(info: Bundle.main.infoDictionary))
                                .foregroundStyle(Theme.inkSoft)
                        }
                        Divider().overlay(Theme.rule)
                        SettingsRow {
                            Text("Plant and seller data")
                                .foregroundStyle(Theme.ink)
                            Spacer(minLength: 12)
                            Text(Self.dataDay(model.data.version.dataDate))
                                .foregroundStyle(Theme.inkSoft)
                        }
                    }
                }
                .font(.body(.body))
                .padding(.bottom, 32)
            }
            .background(Color.white)
            .navigationTitle("Settings")
        }
        .sheet(isPresented: $changingLocation) {
            NavigationStack {
                FirstOpenView(onChosen: { changingLocation = false }, onCancel: { changingLocation = false })
                    .navigationTitle("Change location")
                    .navigationBarTitleDisplayMode(.inline)
            }
        }
        // A tapped reminder closes every open sheet (AppModel.openedFromNotification).
        .onChange(of: model.closeSheetsCount) { _, _ in
            changingLocation = false
        }
    }

    private func groupLabel(_ text: String) -> some View {
        Text(text)
            .font(.body(15, .semibold, relativeTo: .subheadline))
            .foregroundStyle(Theme.inkSoft)
            .padding(.top, 24)
            .padding(.bottom, 8)
            .padding(.horizontal, 32)
            .accessibilityAddTraits(.isHeader)
    }

    /// A row that opens a page of the website in Safari.
    private func outsideLink(_ title: String, _ url: URL) -> some View {
        Button {
            openURL(url)
        } label: {
            SettingsRow {
                Text(title)
                    .foregroundStyle(Theme.ink)
                Spacer(minLength: 12)
                Image(systemName: "arrow.up.forward.square")
                    .font(.system(size: 20))
                    .foregroundStyle(Theme.ink)
                    .accessibilityHidden(true)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        .accessibilityHint("Opens outside the app")
    }

    /// "1.0 (1)": the version people see, then the build number.
    nonisolated static func versionText(info: [String: Any]?) -> String {
        let version = info?["CFBundleShortVersionString"] as? String ?? ""
        let build = info?["CFBundleVersion"] as? String ?? ""
        return build.isEmpty ? version : "\(version) (\(build))"
    }

    /// The day the data was written, as "November 9, 2026", read from its first ten characters (yyyy-mm-dd) so it
    /// is the same day in every time zone.
    nonisolated static func dataDay(_ dataDate: String) -> String {
        let months = ["January", "February", "March", "April", "May", "June", "July", "August", "September",
                      "October", "November", "December"]
        let parts = dataDate.prefix(10).split(separator: "-")
        guard parts.count == 3, let year = Int(parts[0]), let month = Int(parts[1]), let day = Int(parts[2]),
              (1...12).contains(month) else { return String(dataDate.prefix(10)) }
        return "\(months[month - 1]) \(day), \(year)"
    }
}

/// A group of rows with a thin rule round it and 4-point corners.
private struct SettingsGroup<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 0) { content }
            .background(Color.white)
            .overlay(RoundedRectangle(cornerRadius: Theme.brandCorner).strokeBorder(Theme.rule, lineWidth: 1))
            .padding(.horizontal, 16)
    }
}

/// One row: at least 52 points high, its parts side by side.
private struct SettingsRow<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        HStack(spacing: 0) { content }
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
            .contentShape(Rectangle())
    }
}

/// Who took each plant photo and under which licence, plant by plant in name order.
private struct PhotoCreditsView: View {
    @Environment(AppModel.self) private var model

    private var rows: [(id: String, name: String, credits: [String])] {
        model.data.plants
            .sorted { hereaboutsCompare($0.common, $1.common) == .orderedAscending }
            .compactMap { plant -> (id: String, name: String, credits: [String])? in
                var credits: [String] = []
                for (_, photo) in Labels.photos(plant) where !credits.contains(photo.credit) {
                    credits.append(photo.credit)
                }
                if credits.isEmpty { return nil }
                return (id: plant.id, name: plant.common, credits: credits)
            }
    }

    var body: some View {
        List(rows, id: \.id) { row in
            VStack(alignment: .leading, spacing: 4) {
                Text(row.name)
                    .font(.display(.headline))
                    .foregroundStyle(Theme.ink)
                ForEach(row.credits, id: \.self) { credit in
                    Text(credit)
                        .font(.body(.subheadline))
                        .foregroundStyle(Theme.inkSoft)
                }
            }
            .padding(.vertical, 4)
            .accessibilityElement(children: .combine)
        }
        .listStyle(.plain)
        .navigationTitle("Photo credits and licenses")
        .navigationBarTitleDisplayMode(.inline)
    }
}
