// Keeps which garden jobs were ticked off, week by week. A week runs Saturday to Friday and is named by its Saturday
// as yyyymmdd (the same form the reminders use for their ids). Ticks from weeks before the current one are dropped
// when they are read, so each week starts with nothing ticked.
import Foundation
import HereaboutsCore

@MainActor
struct TicksStore {
    static let key = "hereabouts.ticks"
    let defaults: UserDefaults

    /// The week `date` falls in, named by the Saturday on or before it, as yyyymmdd in the phone's own time zone.
    nonisolated static func weekId(for date: Date, calendar: Calendar = .current) -> String {
        var gregorian = Calendar(identifier: .gregorian)
        gregorian.timeZone = calendar.timeZone
        // The calendar numbers Sunday 1 to Saturday 7, so this is the number of days since the last Saturday.
        let daysSinceSaturday = gregorian.component(.weekday, from: date) % 7
        let saturday = gregorian.date(byAdding: .day, value: -daysSinceSaturday, to: date) ?? date
        return String(Reminders.id(for: saturday, calendar: gregorian))
    }

    /// The ticks by week id, without any week before the one `now` falls in. What was dropped is also forgotten.
    func load(now: Date = .now, calendar: Calendar = .current) -> [String: Set<String>] {
        let stored = defaults.dictionary(forKey: Self.key) as? [String: [String]] ?? [:]
        let current = Self.weekId(for: now, calendar: calendar)
        var kept: [String: Set<String>] = [:]
        for (week, rules) in stored where Self.isWeekId(week) && week >= current && !rules.isEmpty {
            kept[week] = Set(rules)
        }
        if kept.count != stored.count { save(kept) }
        return kept
    }

    func save(_ ticks: [String: Set<String>]) {
        let plain = ticks.filter { !$0.value.isEmpty }.mapValues { $0.sorted() }
        defaults.set(plain, forKey: Self.key)
    }

    /// Eight digits. Comparing two of them as text then orders them by date.
    private nonisolated static func isWeekId(_ text: String) -> Bool {
        text.count == 8 && text.allSatisfy { $0.isASCII && $0.isNumber }
    }
}
