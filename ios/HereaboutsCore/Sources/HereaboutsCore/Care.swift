// Which garden jobs are due in a given week, ported line by line from scripts/newsletter/care-core.mjs, which the
// newsletter and the website's reminders share. The rules are the bundle's tables/care-rules.json; the timing of
// each county (its frost dates and first warm week) is tables/care-anchors.json.
//
// Each rule covers a stretch of the year that runs from one of a county's timing dates plus some days to another
// plus some days. A stretch whose start is later than its end runs past New Year. Days of the year run 1 to 365.
import Foundation

/// One care rule, as care-rules.json describes it.
public struct CareRule: Decodable, Hashable, Sendable {
    /// One end of a rule's stretch: a timing date (`anchor`, like "lastSpring") plus `days` (negative is before it).
    public struct Bound: Decodable, Hashable, Sendable {
        public let anchor: String
        public let days: Int

        public init(anchor: String, days: Int) {
            self.anchor = anchor
            self.days = days
        }
    }

    public let id: String
    public let heading: String
    /// The plant types the job applies to, like "perennial".
    public let categories: [String]
    public let from: Bound
    public let to: Bound
    public let facts: [String]
    /// Rules with the same pair name are shown together, as one job, when any of them is due. nil for most rules.
    public let pair: String?
    /// Lower comes first.
    public let order: Double

    public init(id: String, heading: String, categories: [String], from: Bound, to: Bound, facts: [String], pair: String?,
                order: Double) {
        self.id = id
        self.heading = heading
        self.categories = categories
        self.from = from
        self.to = to
        self.facts = facts
        self.pair = pair
        self.order = order
    }
}

/// One county's timing dates, each a day of the year (1 to 365), or nil where the county has none (a place with no
/// frost has no frost dates).
public struct Anchors: Decodable, Hashable, Sendable {
    /// Last spring frost.
    public let lastSpring: Int?
    /// First fall frost.
    public let firstFall: Int?
    /// Last hard spring freeze.
    public let hardSpring: Int?
    /// First hard fall freeze.
    public let hardFall: Int?
    /// First week of the year with highs of 50°F.
    public let highs50: Int?

    public init(lastSpring: Int?, firstFall: Int?, hardSpring: Int?, hardFall: Int?, highs50: Int?) {
        self.lastSpring = lastSpring
        self.firstFall = firstFall
        self.hardSpring = hardSpring
        self.hardFall = hardFall
        self.highs50 = highs50
    }

    /// The timing date with this name, or nil when the county has none or the name is not one of the five.
    public subscript(name: String) -> Int? {
        switch name {
        case "lastSpring": return lastSpring
        case "firstFall": return firstFall
        case "hardSpring": return hardSpring
        case "hardFall": return hardFall
        case "highs50": return highs50
        default: return nil
        }
    }
}

/// One job due this week.
public struct Job: Hashable, Sendable {
    public let ruleId: String
    public let heading: String
    public let facts: [String]
    /// The plants the job is about, sorted by id.
    public let examplePlantIds: [String]
    public let pair: String?

    public init(ruleId: String, heading: String, facts: [String], examplePlantIds: [String], pair: String?) {
        self.ruleId = ruleId
        self.heading = heading
        self.facts = facts
        self.examplePlantIds = examplePlantIds
        self.pair = pair
    }
}

public enum Care {
    /// MAX_SLOTS: at most four jobs a week (a pair counts as one).
    public static let maxSlots = 4

    /// The days a rule covers, both included. `start` later than `end` means the stretch runs past New Year.
    public struct Window: Hashable, Sendable {
        public let start: Int
        public let end: Int

        public init(start: Int, end: Int) {
            self.start = start
            self.end = end
        }
    }

    /// The first day of each month in a 365-day year, less one.
    private static let monthStart = [0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334]

    /// dayOfYear: the day of the year in a 365-day year; Feb 29 counts as Feb 28. nil for a month outside 1-12,
    /// where the website's answer is NaN (and nothing is ever due).
    static func dayOfYear(month: Int, day: Int) -> Int? {
        if month == 2 && day == 29 { return 59 }
        guard (1...12).contains(month) else { return nil }
        return monthStart[month - 1] + day
    }

    /// wrap: folds any whole number into 1-365.
    static func wrap(_ d: Int) -> Int {
        (((d - 1) % 365) + 365) % 365 + 1
    }

    /// sendDayOf for a "YYYY-MM-DD" text: its day of the year. nil when the text is not in that form (the website
    /// throws) or the month is outside 1-12. The day is not checked, as on the website: "2027-04-40" is day 130.
    static func sendDay(_ sendDate: String) -> Int? {
        let s = Array(sendDate.utf8)
        guard s.count == 10, s[4] == UInt8(ascii: "-"), s[7] == UInt8(ascii: "-") else { return nil }
        func number(_ r: Range<Int>) -> Int? {
            var n = 0
            for i in r {
                guard s[i] >= 0x30 && s[i] <= 0x39 else { return nil }
                n = n * 10 + Int(s[i] - 0x30)
            }
            return n
        }
        guard number(0..<4) != nil, let month = number(5..<7), let day = number(8..<10) else { return nil }
        return dayOfYear(month: month, day: day)
    }

    /// ruleWindow: the days a rule covers for a county, or nil when either of its timing dates is missing there.
    /// A stretch of 364 days or more covers the whole year: (1, 365).
    public static func ruleWindow(_ rule: CareRule, anchors: Anchors?) -> Window? {
        guard let a = anchors?[rule.from.anchor], let b = anchors?[rule.to.anchor] else { return nil }
        let start = a + rule.from.days
        let end = b + rule.to.days
        if end - start >= 364 { return Window(start: 1, end: 365) }
        return Window(start: wrap(start), end: wrap(end))
    }

    private static func inWindow(_ w: Window, _ d: Int) -> Bool {
        w.start <= w.end ? d >= w.start && d <= w.end : d >= w.start || d <= w.end
    }

    /// isDueWeek: true when any of the seven days from `sendDay` (running past day 365 into January) is in the
    /// window. A send day above 365 counts as 365.
    public static func isDueWeek(_ window: Window?, sendDay: Int) -> Bool {
        guard let window else { return false }
        let first = min(sendDay, 365)
        for i in 0..<7 where inWindow(window, wrap(first + i)) { return true }
        return false
    }

    private struct Candidate {
        let rule: CareRule
        let examples: [String]
        let due: Bool
    }

    private struct Unit {
        let order: Double
        let id: String
        let members: [Candidate]
    }

    /// byOrder: lower order first, then id, compared as JavaScript compares strings.
    private static func byOrder(_ xOrder: Double, _ xId: String, _ yOrder: Double, _ yId: String) -> Int {
        // x.order - y.order || ...: a difference of 0 (or NaN, which no rule has) falls through to the ids.
        let d = xOrder - yOrder
        if d != 0 && !d.isNaN { return d < 0 ? -1 : 1 }
        return JSText.utf16Less(xId, yId) ? -1 : JSText.utf16Less(yId, xId) ? 1 : 0
    }

    /// dueJobs: this week's jobs for a county and a list of plants: at most four slots, lowest `order` first, then
    /// id. Rules that share a pair fill one slot together, at the place of their lowest-order member, and when any
    /// of them is due every member that applies is shown, due or not.
    ///
    /// `sendDate` is "YYYY-MM-DD"; any other text gives no jobs. `examplePlantIds` holds at most `maxExamples`
    /// plants (as JavaScript's slice: a negative number drops that many from the end). Plant ids not in `plants`
    /// are ignored; a repeated id is kept, as on the website.
    public static func dueJobs(rules: [CareRule], anchors: Anchors?, nativePlantIds: [String], sendDate: String,
                               plants: [Plant], maxExamples: Int = 3) -> [Job] {
        guard let day = Self.sendDay(sendDate) else { return [] }
        // new Map(plants.map(...)): a repeated id keeps the last plant's category.
        var category: [String: String] = [:]
        for p in plants { category[p.id] = p.category }
        // [...ids].filter(...).sort(): the default sort compares UTF-16 code units.
        let natives = nativePlantIds.filter { category[$0] != nil }.sorted(by: JSText.utf16Less)

        var candidates: [Candidate] = []
        for rule in rules {
            guard let window = ruleWindow(rule, anchors: anchors) else { continue }
            let examples = natives.filter { id in category[id].map { rule.categories.contains($0) } ?? false }
            if examples.isEmpty { continue }
            candidates.append(Candidate(rule: rule, examples: examples, due: isDueWeek(window, sendDay: day)))
        }

        var units: [Unit] = []
        // A Map keeps its keys in the order they were first added.
        var pairNames: [String] = []
        var pairs: [String: [Candidate]] = [:]
        for c in candidates {
            // !c.rule.pair: an empty pair name counts as none.
            guard let pair = c.rule.pair, !pair.isEmpty else {
                if c.due { units.append(Unit(order: c.rule.order, id: c.rule.id, members: [c])) }
                continue
            }
            if pairs[pair] == nil {
                pairNames.append(pair)
                pairs[pair] = []
            }
            pairs[pair]!.append(c)
        }
        for name in pairNames {
            let all = pairs[name] ?? []
            if !all.contains(where: { $0.due }) { continue }
            let members = PlantList.stableSorted(all) { i, j in
                byOrder(all[i].rule.order, all[i].rule.id, all[j].rule.order, all[j].rule.id)
            }
            units.append(Unit(order: members[0].rule.order, id: members[0].rule.id, members: members))
        }

        let sorted = PlantList.stableSorted(units) { i, j in
            byOrder(units[i].order, units[i].id, units[j].order, units[j].id)
        }
        return sorted.prefix(maxSlots).flatMap { $0.members }.map { c in
            Job(ruleId: c.rule.id, heading: c.rule.heading, facts: c.rule.facts,
                examplePlantIds: slice(c.examples, maxExamples), pair: c.rule.pair)
        }
    }

    /// list.slice(0, end), with a negative end counted from the back.
    private static func slice(_ list: [String], _ end: Int) -> [String] {
        let n = end >= 0 ? min(end, list.count) : max(0, list.count + end)
        return Array(list.prefix(n))
    }

    // MARK: - loading

    private struct RulesFile: Decodable {
        let rules: [CareRule]
    }

    /// The rules in care-rules.json ({ _about, rules }).
    public static func rules(from data: Data) throws -> [CareRule] {
        try JSONDecoder().decode(RulesFile.self, from: data).rules
    }

    /// care-anchors.json: each county's timing dates, by five-digit county code.
    public static func anchorsByCounty(from data: Data) throws -> [String: Anchors] {
        try JSONDecoder().decode([String: Anchors].self, from: data)
    }
}
