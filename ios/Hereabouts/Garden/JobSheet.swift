// One job of This week, as job-detail-sheet.png draws it, in a sheet that opens at full height and can be pulled
// down to half. The job's heading, the saved plants it names, when to do it (with the county's timing dates it comes
// from), what to do (the rule's facts), and the sources the facts come from, each opening in the phone's own
// browser. A job that shares a pair with another shows the other below it, since either way will do. Mark as done
// ticks the job off for the week, as its tick circle does.
import HereaboutsCore
import SwiftUI

struct JobSheet: View {
    let row: WeekRow
    let week: GardenWeek

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    /// The sheet opens at full height, so the job, the plants, when and what to do show without pulling it up. It
    /// can still be pulled down to half height.
    @State private var height: PresentationDetent = .large

    private var done: Bool { model.isTicked(row.id, now: model.now) }

    /// The sources of every job in the row, each once, in order.
    private var sources: [CareTables.Source] {
        var seen = Set<String>()
        return row.jobs.flatMap { model.care.sources[$0.ruleId] ?? [] }.filter { seen.insert($0.url).inserted }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                ForEach(row.plantIds, id: \.self) { id in
                    if let plant = week.plant(id) { plantLine(plant) }
                }
                ForEach(Array(row.jobs.enumerated()), id: \.element.ruleId) { i, job in
                    if i > 0 {
                        Text("Or: \(job.heading)")
                            .font(.display(.title3))
                            .foregroundStyle(Theme.ink)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, 16)
                            .padding(.top, 32)
                            .accessibilityAddTraits(.isHeader)
                    }
                    jobDetail(job)
                }
                if !sources.isEmpty { sourceList }
            }
            .padding(.bottom, 24)
        }
        .background(Color.white)
        .safeAreaInset(edge: .bottom, spacing: 0) { markDoneBar }
        .presentationDetents([.medium, .large], selection: $height)
        .presentationDragIndicator(.visible)
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 12) {
            Text(row.heading)
                .font(.display(.title2))
                .foregroundStyle(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
                .padding(.top, 6)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button("Done") { dismiss() }
                .font(.body(17, .semibold, relativeTo: .body))
                .foregroundStyle(Theme.moss)
                .frame(minWidth: 48, minHeight: 48)
                .accessibilityIdentifier("jobSheetDone")
        }
        .padding(.leading, 16)
        .padding(.trailing, 8)
        .padding(.top, 16)
        .padding(.bottom, 8)
    }

    /// "Your Spicebush", with its photo and the photo's credit.
    private func plantLine(_ plant: Plant) -> some View {
        HStack(spacing: 16) {
            PlantThumbnail(plant: plant, photos: model.photos, side: 64)
            VStack(alignment: .leading, spacing: 4) {
                Text("Your \(plant.common)")
                    .font(.body(17, .regular, relativeTo: .body))
                    .foregroundStyle(Theme.ink)
                if let credit = Labels.mainPhoto(plant)?.photo.credit, !credit.isEmpty {
                    Text(credit)
                        .font(.body(10, .regular, relativeTo: .caption2))
                        .foregroundStyle(Theme.creditGrey)
                }
            }
            .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }

    private func label(_ text: String) -> some View {
        Text(text)
            .font(.body(17, .semibold, relativeTo: .body))
            .foregroundStyle(Theme.ink)
            .padding(.horizontal, 16)
            .padding(.top, 24)
            .padding(.bottom, 6)
            .accessibilityAddTraits(.isHeader)
    }

    @ViewBuilder
    private func jobDetail(_ job: Job) -> some View {
        let when = week.when(job)
        let explained = week.whenExplained(job)
        if !when.isEmpty {
            label("When")
            Text(when)
                .font(.body(17, .regular, relativeTo: .body))
                .foregroundStyle(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 16)
                .accessibilityIdentifier("jobWhen-\(job.ruleId)")
            if !explained.isEmpty {
                Text(explained)
                    .font(.body(15, .regular, relativeTo: .subheadline))
                    .foregroundStyle(Theme.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 16)
                    .padding(.top, 6)
            }
        }
        label("What to do")
        VStack(alignment: .leading, spacing: 10) {
            ForEach(job.facts, id: \.self) { fact in
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Rectangle()
                        .fill(Theme.ink)
                        .frame(width: 6, height: 6)
                        // The square sits about halfway up the first line's small letters.
                        .alignmentGuide(.firstTextBaseline) { d in d.height + 2 }
                        .accessibilityHidden(true)
                    Text(fact)
                        .font(.body(17, .regular, relativeTo: .body))
                        .foregroundStyle(Theme.ink)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .padding(.horizontal, 16)
    }

    private var sourceList: some View {
        VStack(alignment: .leading, spacing: 0) {
            label("Sources")
            ForEach(Array(sources.enumerated()), id: \.element.url) { i, source in
                if i > 0 { Theme.rule.frame(height: 1) }
                sourceRow(source, index: i)
            }
        }
    }

    private func sourceRow(_ source: CareTables.Source, index: Int) -> some View {
        let url = OutsideLink.url(source.url)
        return Button {
            if let url { openURL(url) }
        } label: {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(source.title)
                        .font(.body(17, .regular, relativeTo: .body))
                        .foregroundStyle(Theme.ink)
                    if let host = url?.host() {
                        Text(host.hasPrefix("www.") ? String(host.dropFirst(4)) : host)
                            .font(.body(15, .regular, relativeTo: .subheadline))
                            .foregroundStyle(Theme.inkSoft)
                    }
                }
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "arrow.up.forward.square")
                    .font(.system(size: 20))
                    .foregroundStyle(url == nil ? Theme.ruleStrong : Theme.moss)
                    .frame(width: 44, height: 44)
                    .accessibilityHidden(true)
            }
            .padding(.vertical, 8)
            .padding(.leading, 16)
            .padding(.trailing, 4)
            .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(url == nil)
        .accessibilityLabel(source.title)
        .accessibilityHint("Opens outside the app")
        .accessibilityIdentifier("source-\(index)")
    }

    /// Mark as done, kept above the bottom edge.
    private var markDoneBar: some View {
        Button {
            model.toggleTick(row.id, now: model.now)
        } label: {
            HStack(spacing: 10) {
                if !done {
                    TickMark()
                        .stroke(Theme.onMoss, style: StrokeStyle(lineWidth: 2.2, lineCap: .round, lineJoin: .round))
                        .frame(width: 16, height: 13)
                        .accessibilityHidden(true)
                }
                Text(done ? "Mark as not done" : "Mark as done")
                    .font(.body(17, .semibold, relativeTo: .body))
            }
            .foregroundStyle(done ? Theme.moss : Theme.onMoss)
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(done ? Color.white : Theme.moss, in: RoundedRectangle(cornerRadius: Theme.brandCorner))
            .overlay(RoundedRectangle(cornerRadius: Theme.brandCorner).strokeBorder(Theme.moss, lineWidth: done ? 1.5 : 0))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("markDone")
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Color.white)
        .overlay(alignment: .top) { Theme.rule.frame(height: 1) }
    }
}
