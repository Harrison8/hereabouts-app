// The Saturday-morning reminders: which Saturdays, which jobs, and which saved plants each job names. Ported line by
// line from scripts/app/reminders.mjs, the version the website's app build uses. The app turns each Reminder into a
// local notification. The tests check it against expected/care.json.
import Foundation

public enum Reminders {
    /// REMINDER_TITLE: every reminder's title.
    public static let title = "This week in your garden"

    /// One Saturday's reminder. `id` is the date as yyyymmdd, so the same Saturday always gets the same id.
    public struct Reminder: Hashable, Sendable {
        public let id: Int
        public let at: Date
        public let title: String
        public let body: String
        public let jobs: [Job]

        public init(id: Int, at: Date, title: String, body: String, jobs: [Job]) {
            self.id = id
            self.at = at
            self.title = title
            self.body = body
            self.jobs = jobs
        }
    }

    /// The website's dates are always Gregorian. A phone set to the Japanese, Persian, Hebrew or Islamic calendar
    /// would otherwise give era years or other months, so every date part here is read from a Gregorian calendar in
    /// the passed calendar's time zone.
    static func gregorian(_ calendar: Calendar) -> Calendar {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = calendar.timeZone
        return cal
    }

    /// saturdaysAhead: the next `weeks` Saturdays at 08:00 in the calendar's time zone, each later than `now`. On a
    /// Saturday before 08:00 that same morning is the first. Looks at most 7 × weeks + 14 days ahead, as the website
    /// does.
    public static func saturdaysAhead(now: Date, weeks: Int = 8, calendar: Calendar) -> [Date] {
        let calendar = gregorian(calendar)
        var out: [Date] = []
        let today = calendar.dateComponents([.year, .month, .day], from: now)
        // Noon on today's date: adding whole days to it never lands on a skipped or repeated hour.
        guard let noon = calendar.date(from: DateComponents(year: today.year, month: today.month, day: today.day, hour: 12))
        else { return [] }
        var i = 0
        while out.count < weeks && i < 7 * weeks + 14 {
            defer { i += 1 }
            guard let day = calendar.date(byAdding: .day, value: i, to: noon) else { continue }
            let c = calendar.dateComponents([.year, .month, .day], from: day)
            // new Date(y, m, d, 8, 0, 0): 08:00 local time on that date.
            guard let at = calendar.date(from: DateComponents(year: c.year, month: c.month, day: c.day, hour: 8, minute: 0,
                                                             second: 0))
            else { continue }
            // getDay() === 6 is Saturday; the calendar numbers Sunday 1, so Saturday is 7.
            if calendar.component(.weekday, from: at) == 7 && at > now { out.append(at) }
        }
        return out
    }

    /// reminderId: the date as yyyymmdd, from its local year, month and day.
    public static func id(for date: Date, calendar: Calendar) -> Int {
        let calendar = gregorian(calendar)
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return (c.year ?? 0) * 10000 + (c.month ?? 0) * 100 + (c.day ?? 0)
    }

    /// The date as "YYYY-MM-DD" from its local fields (localDate in reminders.mjs).
    static func localDate(_ date: Date, calendar: Calendar) -> String {
        let calendar = gregorian(calendar)
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        func pad(_ n: Int) -> String { n < 10 ? "0\(n)" : "\(n)" }
        return "\(c.year ?? 0)-\(pad(c.month ?? 0))-\(pad(c.day ?? 0))"
    }

    /// joinNames: "a", "a and b", "a, b and c".
    static func joinNames(_ names: [String]) -> String {
        if names.count <= 1 { return names.joined() }
        return names.dropLast().joined(separator: ", ") + " and " + names[names.count - 1]
    }

    /// reminderBody: one line per job, "<heading>: your <plants>.", with the plants' common names in lower case. A
    /// pair shows once, under its first member. A job whose plants are all unknown (or have no name) has no line.
    public static func body(jobs: [Job], plants: [Plant]) -> String {
        // new Map(plants.map(...)): a repeated id keeps the last plant.
        var byId: [String: Plant] = [:]
        for p in plants { byId[p.id] = p }
        var seenPairs = Set<String>()
        var lines: [String] = []
        for job in jobs {
            // if (job.pair): an empty pair name counts as none.
            if let pair = job.pair, !pair.isEmpty {
                if seenPairs.contains(pair) { continue }
                seenPairs.insert(pair)
            }
            // .filter(Boolean) drops a missing plant and an empty name.
            let names = job.examplePlantIds.compactMap { byId[$0]?.common }.filter { !$0.isEmpty }.map { $0.lowercased() }
            if names.isEmpty { continue }
            lines.append("\(job.heading): your \(joinNames(names)).")
        }
        return lines.joined(separator: "\n")
    }

    /// weeklyReminders: a reminder for each of the next eight Saturdays that has at least one job, naming every saved
    /// plant each job applies to. None when the county has no timing dates or no saved plant is known.
    public static func weekly(rules: [CareRule], anchors: Anchors?, savedIds: [String], plants: [Plant], now: Date,
                              calendar: Calendar) -> [Reminder] {
        guard let anchors else { return [] }
        let calendar = gregorian(calendar)
        let known = Set(plants.map(\.id))
        let nativePlantIds = savedIds.filter { known.contains($0) }
        if nativePlantIds.isEmpty { return [] }
        var out: [Reminder] = []
        for at in saturdaysAhead(now: now, calendar: calendar) {
            // maxExamples: Infinity on the website: every saved plant the job applies to.
            let jobs = Care.dueJobs(rules: rules, anchors: anchors, nativePlantIds: nativePlantIds,
                                    sendDate: localDate(at, calendar: calendar), plants: plants, maxExamples: .max)
            if jobs.isEmpty { continue }
            out.append(Reminder(id: id(for: at, calendar: calendar), at: at, title: title,
                                body: body(jobs: jobs, plants: plants), jobs: jobs))
        }
        return out
    }
}
