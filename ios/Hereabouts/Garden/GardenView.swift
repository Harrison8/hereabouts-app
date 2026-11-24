// The Garden tab, as d1-my-garden.png and my-garden-empty.png draw it: the county under the title, after a map pin;
// This week, the jobs due for the saved plants in the week that runs from Saturday to Friday, each with a tick circle,
// and how many are ticked off; a line saying where the dates come from; then the saved plants. Tapping a job opens its
// sheet (JobSheet); tapping a plant opens its page, and swiping it to the left shows Remove (SavedGrid). With nothing
// saved, a short note and a button to the Plants tab.
//
// The jobs are the website's: HereaboutsCore's Care.dueJobs for the county's timing dates and the saved plants, with
// the week's Saturday as the send date, every saved plant named. Saved plants that are not native to the county
// still count, as they do in the reminders.
import HereaboutsCore
import SwiftUI

struct GardenView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        // The open plant pages and the open job are kept in the model, so a tapped reminder can close them.
        @Bindable var model = model
        let week = GardenWeek(model: model)
        NavigationStack(path: $model.gardenPath) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    countyLine
                    if week.savedPlants.isEmpty {
                        emptyState
                    } else {
                        thisWeek(week)
                        SavedGrid(plants: week.savedPlants, photos: model.photos,
                                  jobCount: { id in week.rows.filter { $0.plantIds.contains(id) }.count },
                                  open: { self.model.gardenPath.append($0) },
                                  remove: { self.model.pressSave($0) },
                                  swipeable: model.selectedTab == .garden && model.gardenPath.isEmpty)
                            .padding(.top, 32)
                    }
                }
                .padding(.bottom, 24)
            }
            .background(Theme.surface)
            .navigationTitle("My garden")
            .navigationDestination(for: String.self) { id in
                if let plant = model.data.plants.first(where: { $0.id == id }) {
                    PlantPage(plant: plant)
                }
            }
        }
        .sheet(item: $model.openJob) { row in
            JobSheet(row: row, week: GardenWeek(model: self.model))
                .capturingLinksForTests()
        }
    }

    // MARK: - the county

    @ViewBuilder
    private var countyLine: some View {
        if let loc = model.location {
            countyRow(loc)
        } else {
            Color.clear.frame(height: 4)
        }
    }

    private func countyRow(_ loc: Location) -> some View {
        CountyLine(color: Theme.ink, weight: .regular) {
            Text("\(loc.countyName), \(loc.stateName)")
                .font(.body(17, .regular, relativeTo: .body))
                .foregroundStyle(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("gardenCounty")
        }
        .padding(.horizontal, 16)
        .padding(.top, 4)
    }

    // MARK: - nothing saved

    private var emptyState: some View {
        VStack(spacing: 16) {
            Text("No plants saved yet")
                .font(.display(.title2))
                .foregroundStyle(Theme.ink)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("gardenEmpty")
            // The Saturday sentence only while a reminder will come: not with the switch off in Settings, and not
            // when the phone has refused the app's notifications.
            let saturday = model.remindersWillCome
                ? " Each Saturday at 8:00 you’ll get the week’s garden jobs for the plants you saved." : ""
            Text("Tap \(Image(systemName: "bookmark")) on any plant to save it here.\(saturday)")
                .font(.body(17, .regular, relativeTo: .body))
                .foregroundStyle(Theme.inkSoft)
                .accessibilityLabel("Tap the save button on any plant to save it here.\(saturday)")
                .accessibilityIdentifier("gardenEmptyNote")
            Button {
                model.selectedTab = .plants
            } label: {
                Text("Browse plants")
                    .font(.body(17, .semibold, relativeTo: .body))
                    .foregroundStyle(Theme.onMoss)
                    .padding(.horizontal, 32)
                    .frame(minWidth: 200, minHeight: 50)
                    .background(Theme.moss, in: RoundedRectangle(cornerRadius: Theme.brandCorner))
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("browsePlants")
            .padding(.top, 8)
        }
        .multilineTextAlignment(.center)
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 32)
        .padding(.top, 96)
    }

    // MARK: - This week

    @ViewBuilder
    private func thisWeek(_ week: GardenWeek) -> some View {
        let headerLayout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4))
            : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: 12))
        headerLayout {
            Text("This week")
                .font(.display(.title2))
                .foregroundStyle(Theme.ink)
                .accessibilityAddTraits(.isHeader)
            if !typeSize.isAccessibilitySize { Spacer(minLength: 0) }
            Text(week.datesLabel)
                .font(.body(17, .regular, relativeTo: .body))
                .foregroundStyle(Theme.inkSoft)
                .accessibilityIdentifier("thisWeekDates")
        }
        .padding(.horizontal, 16)
        .padding(.top, 32)
        .padding(.bottom, 4)

        if !week.rows.isEmpty {
            Text("\(week.rows.filter { model.isTicked($0.id, now: model.now) }.count) of \(week.rows.count) done")
                .font(.body(15, .medium, relativeTo: .subheadline))
                .foregroundStyle(Theme.inkSoft)
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
                .accessibilityIdentifier("doneCount")
        }

        VStack(spacing: 0) {
            if week.rows.isEmpty {
                Text("Nothing to do this week.")
                    .font(.body(17, .regular, relativeTo: .body))
                    .foregroundStyle(Theme.inkSoft)
                    .padding(16)
                    .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
            }
            ForEach(Array(week.rows.enumerated()), id: \.element.id) { i, row in
                if i > 0 { Theme.rule.frame(height: 1) }
                jobRow(row, week: week)
            }
        }
        .background(Color.white)
        .overlay(RoundedRectangle(cornerRadius: Theme.brandCorner).strokeBorder(Theme.rule, lineWidth: 1))
        .padding(.horizontal, 16)
        .padding(.top, week.rows.isEmpty ? 8 : 0)

        Text(week.footer(remindersWillCome: model.remindersWillCome))
            .font(.body(15, .regular, relativeTo: .subheadline))
            .foregroundStyle(Theme.inkSoft)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 32)
            .padding(.top, 8)
            .accessibilityIdentifier("thisWeekFooter")
    }

    private func jobRow(_ row: WeekRow, week: GardenWeek) -> some View {
        let done = model.isTicked(row.id, now: model.now)
        let subline = week.subline(row)
        // At the largest text sizes a row is many lines tall, so the tick circle sits beside the job's first line
        // instead of halfway down the row.
        return HStack(alignment: typeSize.isAccessibilitySize ? .top : .center, spacing: 0) {
            TickCircle(done: done) { model.toggleTick(row.id, now: model.now) }
                .accessibilityLabel("Tick off: \(row.heading)")
                .accessibilityIdentifier("tick-\(row.id)")
                .padding(.leading, 4)
                .padding(.top, typeSize.isAccessibilitySize ? 12 : 0)
            Button {
                model.openJob = row
            } label: {
                HStack(spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.heading)
                            .font(.body(17, .regular, relativeTo: .body))
                            .foregroundStyle(done ? Theme.inkSoft : Theme.ink)
                        if !subline.isEmpty {
                            Text(subline)
                                .font(.body(15, .regular, relativeTo: .subheadline))
                                .foregroundStyle(Theme.inkSoft)
                        }
                    }
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Theme.ruleStrong)
                        .accessibilityHidden(true)
                }
                .padding(.vertical, 12)
                .padding(.trailing, 16)
                .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(subline.isEmpty ? row.heading : "\(row.heading), \(subline)")
            .accessibilityAddTraits(.isButton)
            .accessibilityIdentifier("job-\(row.id)")
        }
    }
}

/// A round tick: an empty ring until the job is done, then a filled moss circle with a white tick. The whole
/// 44-point square answers a tap.
struct TickCircle: View {
    let done: Bool
    let toggle: () -> Void

    var body: some View {
        Button(action: toggle) {
            ZStack {
                if done {
                    Circle().fill(Theme.moss)
                    TickMark().stroke(Theme.onMoss, style: StrokeStyle(lineWidth: 2.2, lineCap: .round, lineJoin: .round))
                        .padding(7)
                } else {
                    Circle().strokeBorder(Theme.ruleStrong, lineWidth: 1.5)
                }
            }
            .frame(width: 26, height: 26)
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityValue(done ? "Done" : "Not done")
    }
}

/// A tick, drawn in the box it is given.
struct TickMark: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.minX, y: rect.midY + rect.height * 0.05))
        path.addLine(to: CGPoint(x: rect.minX + rect.width * 0.36, y: rect.maxY - rect.height * 0.12))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY + rect.height * 0.12))
        return path
    }
}

// MARK: - the week's jobs

/// One row of This week: a job, or jobs that share a pair, shown together under the first. Its id is the first
/// job's rule id, which is also what a tick is kept under.
struct WeekRow: Identifiable, Hashable {
    let jobs: [Job]
    var id: String { jobs[0].ruleId }
    var heading: String { jobs[0].heading }
    /// The saved plants any of its jobs applies to, in the order the jobs name them.
    var plantIds: [String] {
        var seen = Set<String>()
        return jobs.flatMap(\.examplePlantIds).filter { seen.insert($0).inserted }
    }
}

/// This week for the model's county, saved plants and time: the rows, the dates, and the words built from them.
@MainActor
struct GardenWeek {
    let care: CareTables
    let plants: [Plant]
    let anchors: Anchors?
    let countyName: String
    /// The Saturday the week starts on, at the time of day `now` has.
    let saturday: Date
    let savedPlants: [Plant]
    let rows: [WeekRow]

    init(model: AppModel) {
        care = model.care
        plants = model.data.plants
        anchors = model.location.flatMap { model.care.anchors[$0.countyFips] }
        countyName = model.location?.countyName ?? ""
        let calendar = Self.calendar
        let daysSinceSaturday = calendar.component(.weekday, from: model.now) % 7
        saturday = calendar.date(byAdding: .day, value: -daysSinceSaturday, to: model.now) ?? model.now
        let byId = Dictionary(model.data.plants.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
        savedPlants = model.saved.compactMap { byId[$0] }
        let jobs = Care.dueJobs(rules: model.care.rules, anchors: anchors, nativePlantIds: model.saved,
                                sendDate: Self.isoDate(saturday), plants: model.data.plants, maxExamples: .max)
        // A job that shares a pair joins the row of the pair's first job.
        var pairRow: [String: Int] = [:]
        var grouped: [[Job]] = []
        for job in jobs {
            if let pair = job.pair, !pair.isEmpty, let i = pairRow[pair] {
                grouped[i].append(job)
                continue
            }
            if let pair = job.pair, !pair.isEmpty { pairRow[pair] = grouped.count }
            grouped.append([job])
        }
        rows = grouped.map { WeekRow(jobs: $0) }
    }

    /// The website's dates are Gregorian, in the phone's time zone.
    static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        return calendar
    }

    private static let shortMonths = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
    private static let months = ["January", "February", "March", "April", "May", "June", "July", "August", "September",
                                 "October", "November", "December"]
    /// The day before each month starts, in a 365-day year, as the care rules count days.
    private static let monthStart = [0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334]

    static func isoDate(_ date: Date) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        func pad(_ n: Int) -> String { n < 10 ? "0\(n)" : "\(n)" }
        return "\(c.year ?? 0)-\(pad(c.month ?? 0))-\(pad(c.day ?? 0))"
    }

    /// "Nov 13 to 19", or "Oct 30 to Nov 5" when the week crosses into the next month.
    var datesLabel: String {
        let calendar = Self.calendar
        let friday = calendar.date(byAdding: .day, value: 6, to: saturday) ?? saturday
        let a = calendar.dateComponents([.month, .day], from: saturday)
        let b = calendar.dateComponents([.month, .day], from: friday)
        let aMonth = Self.shortMonths[(a.month ?? 1) - 1]
        let bMonth = Self.shortMonths[(b.month ?? 1) - 1]
        return a.month == b.month ? "\(aMonth) \(a.day ?? 0) to \(b.day ?? 0)"
            : "\(aMonth) \(a.day ?? 0) to \(bMonth) \(b.day ?? 0)"
    }

    /// The week's Saturday as a day of the year, as the care rules count days (February 29 counts as February 28).
    var sendDay: Int {
        let c = Self.calendar.dateComponents([.month, .day], from: saturday)
        let month = c.month ?? 1, day = c.day ?? 1
        if month == 2 && day == 29 { return 59 }
        return Self.monthStart[month - 1] + day
    }

    /// A day of the year as "April 14", or "Apr 14" when `short`.
    static func monthDay(_ dayOfYear: Int, short: Bool = false) -> String {
        let month = (monthStart.lastIndex { $0 < dayOfYear }) ?? 0
        let name = short ? shortMonths[month] : months[month]
        return "\(name) \(dayOfYear - monthStart[month])"
    }

    /// "a", "a and b", "a, b and c".
    static func joined(_ names: [String]) -> String {
        if names.count <= 1 { return names.joined() }
        return names.dropLast().joined(separator: ", ") + " and " + names[names.count - 1]
    }

    func rule(_ id: String) -> CareRule? { care.rules.first { $0.id == id } }

    func window(_ job: Job) -> Care.Window? {
        rule(job.ruleId).flatMap { Care.ruleWindow($0, anchors: anchors) }
    }

    func plant(_ id: String) -> Plant? { plants.first { $0.id == id } }

    /// "Butterfly Weed and Little Bluestem · until Dec 27".
    func subline(_ row: WeekRow) -> String {
        let names = Self.joined(row.plantIds.compactMap { plant($0)?.common }.filter { !$0.isEmpty })
        var parts = names.isEmpty ? [] : [names]
        if let w = window(row.jobs[0]), !(w.start == 1 && w.end == 365) {
            parts.append("until \(Self.monthDay(w.end, short: true))")
        }
        return parts.joined(separator: " · ")
    }

    /// The line under This week: where the dates come from, and, while a reminder will come (the switch is on and the
    /// phone has not refused the app's notifications), when it comes.
    func footer(remindersWillCome: Bool) -> String {
        guard anchors != nil else { return "Hereabouts has no frost dates for \(countyName) yet. Without them it shows no jobs." }
        return remindersWillCome
            ? "From \(countyName)’s frost dates. A reminder comes every Saturday at 8:00."
            : "From \(countyName)’s frost dates."
    }

    // MARK: When

    /// "Now until about April 14." when the job is due this week, "From about March 10 to about April 7." when not,
    /// "Any time of year." when it runs all year.
    func when(_ job: Job) -> String {
        guard let w = window(job) else { return "" }
        if w.start == 1 && w.end == 365 { return "Any time of year." }
        if Care.isDueWeek(w, sendDay: sendDay) { return "Now until about \(Self.monthDay(w.end))." }
        return "From about \(Self.monthDay(w.start)) to about \(Self.monthDay(w.end))."
    }

    private static let anchorNames = [
        "lastSpring": "last spring frost", "firstFall": "first fall frost", "hardSpring": "last hard freeze",
        "hardFall": "first hard freeze", "highs50": "first week with highs of 50°F",
    ]

    /// "Fairfax County’s first fall frost is about October 29 and its first hard freeze about November 8. This job
    /// runs from the first fall frost to four weeks after the first hard freeze."
    func whenExplained(_ job: Job) -> String {
        guard let rule = rule(job.ruleId), let anchors else { return "" }
        var names: [String] = []
        for anchor in [rule.from.anchor, rule.to.anchor] where !names.contains(anchor) { names.append(anchor) }
        var dates: [String] = []
        for (i, anchor) in names.enumerated() {
            guard let day = anchors[anchor] else { return "" }
            let name = Self.anchorNames[anchor] ?? anchor
            dates.append(i == 0 ? "\(countyName)’s \(name) is about \(Self.monthDay(day))"
                                : "its \(name) about \(Self.monthDay(day))")
        }
        let from = Self.phrase(rule.from, sameAsBefore: false)
        let to = Self.phrase(rule.to, sameAsBefore: rule.to.anchor == rule.from.anchor)
        return "\(dates.joined(separator: " and ")). This job runs from \(from) to \(to)."
    }

    /// "the first fall frost", "four weeks after the first hard freeze", "two weeks before it".
    private static func phrase(_ bound: CareRule.Bound, sameAsBefore: Bool) -> String {
        let target = sameAsBefore ? "it" : "the \(anchorNames[bound.anchor] ?? bound.anchor)"
        if bound.days == 0 { return target }
        let words = ["one", "two", "three", "four", "five", "six", "seven", "eight", "nine"]
        let days = abs(bound.days)
        let amount: String
        if days % 7 == 0 {
            let weeks = days / 7
            let count = weeks <= 9 ? words[weeks - 1] : "\(weeks)"
            amount = weeks == 1 ? "one week" : "\(count) weeks"
        } else {
            amount = days == 1 ? "one day" : "\(days) days"
        }
        return bound.days < 0 ? "\(amount) before \(target)" : "\(amount) after \(target)"
    }
}
