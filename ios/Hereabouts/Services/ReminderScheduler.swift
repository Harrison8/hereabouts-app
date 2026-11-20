// The Saturday reminders as local notifications. Each time something they depend on changes (the app opens, a plant is
// saved or unsaved, the county changes, the Settings switch is turned), `reschedule` removes the Saturday reminders
// the app has waiting (only those whose identifier starts hereabouts.week., so any other notification the app plans
// later is left alone) and adds one per Saturday that HereaboutsCore's Reminders.weekly gives, at 08:00 phone time,
// with the identifier hereabouts.week.<yyyymmdd>. Nothing is added while the phone does not allow the app's
// notifications.
// The runs are taken one at a time, in the order asked, so a later run's removal never lands between an earlier
// run's additions.
//
// The phone is asked whether the app may send notifications only the first time a plant is saved while a county is
// set (AppModel.pressSave), never at first open.
@preconcurrency import UserNotifications
import Foundation
import HereaboutsCore

/// One reminder as the phone keeps it: its identifier, words, and the local time it is due.
struct PlannedReminder: Equatable, Sendable {
    let id: String
    let title: String
    let body: String
    /// Year, month, day, hour and minute on the Gregorian calendar, in whatever time zone the phone is in.
    let at: DateComponents
}

/// What the phone says about the app's notifications.
enum NotificationPermission: Sendable {
    case notAsked, allowed, denied
}

/// The phone's notification settings and waiting notifications. The app uses the phone's own; the screen tests use a
/// stand-in, so no system question appears in the middle of a test.
protocol NotificationCentre: Sendable {
    func permission() async -> NotificationPermission
    /// Asks the person, and returns whether they allowed it.
    func ask() async -> Bool
    func add(_ reminder: PlannedReminder) async
    /// Removes the waiting notifications whose identifier starts with `prefix`, and no others.
    func removeWaiting(startingWith prefix: String) async
    func waiting() async -> [PlannedReminder]
}

/// The phone's own notification centre.
struct SystemNotifications: NotificationCentre {
    func permission() async -> NotificationPermission {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        switch settings.authorizationStatus {
        case .notDetermined: return .notAsked
        case .denied: return .denied
        // Allowed, allowed quietly, or allowed for a short time: each shows the reminder somewhere.
        default: return .allowed
        }
    }

    func ask() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])) ?? false
    }

    func add(_ reminder: PlannedReminder) async {
        let content = UNMutableNotificationContent()
        content.title = reminder.title
        content.body = reminder.body
        content.sound = .default
        let trigger = UNCalendarNotificationTrigger(dateMatching: reminder.at, repeats: false)
        let request = UNNotificationRequest(identifier: reminder.id, content: content, trigger: trigger)
        try? await UNUserNotificationCenter.current().add(request)
    }

    func removeWaiting(startingWith prefix: String) async {
        let waiting = await UNUserNotificationCenter.current().pendingNotificationRequests()
        let ids = waiting.map(\.identifier).filter { $0.hasPrefix(prefix) }
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ids)
    }

    func waiting() async -> [PlannedReminder] {
        let requests = await UNUserNotificationCenter.current().pendingNotificationRequests()
        return requests.compactMap { request in
            guard let trigger = request.trigger as? UNCalendarNotificationTrigger else { return nil }
            return PlannedReminder(id: request.identifier, title: request.content.title, body: request.content.body,
                                   at: trigger.dateComponents)
        }
    }
}

@MainActor
final class ReminderScheduler {
    /// Every reminder's identifier starts with this, followed by its Saturday as yyyymmdd.
    nonisolated static let idPrefix = "hereabouts.week."

    let centre: any NotificationCentre
    /// The latest run. Each run waits for the one before it.
    private var latest: Task<Void, Never>?
    /// True while the permission question is being asked, so two quick saves ask once.
    private var asking = false

    init(centre: any NotificationCentre) {
        self.centre = centre
    }

    /// Removes the waiting Saturday reminders and adds `wanted`, if the phone allows the app's notifications.
    func reschedule(_ wanted: [PlannedReminder]) {
        let previous = latest
        let centre = centre
        #if DEBUG
        let report = reportTo
        #endif
        latest = Task {
            await previous?.value
            await centre.removeWaiting(startingWith: Self.idPrefix)
            if !wanted.isEmpty, await centre.permission() == .allowed {
                for reminder in wanted { await centre.add(reminder) }
            }
            #if DEBUG
            if let report { await Self.send(await centre.waiting(), to: report) }
            #endif
        }
    }

    /// Asks the person whether the app may send notifications, if the phone has never asked them. Returns their
    /// answer, or nil when they were asked before (or are being asked now).
    func askIfNotAsked() async -> Bool? {
        guard !asking else { return nil }
        asking = true
        defer { asking = false }
        guard await centre.permission() == .notAsked else { return nil }
        return await centre.ask()
    }

    /// "2027-10-09T08:00", from a reminder's date parts.
    nonisolated static func localTime(_ at: DateComponents) -> String {
        func pad(_ n: Int?, _ width: Int = 2) -> String {
            let text = String(n ?? 0)
            return String(repeating: "0", count: max(0, width - text.count)) + text
        }
        return "\(pad(at.year, 4))-\(pad(at.month))-\(pad(at.day))T\(pad(at.hour)):\(pad(at.minute))"
    }

    #if DEBUG
    /// In a Debug build launched by a screen test, the stand-in website. After every run the reminders left waiting
    /// are posted to its /uitest/reminders, so the test can compare them with the website's.
    var reportTo: URL?

    /// Waits for the runs asked for so far to finish. The unit tests use it to read what was left waiting.
    func settled() async {
        await latest?.value
    }

    private static func send(_ waiting: [PlannedReminder], to site: URL) async {
        let list = waiting.map { ["id": $0.id, "title": $0.title, "body": $0.body, "at": localTime($0.at)] }
        var request = URLRequest(url: site.appendingPathComponent("uitest/reminders"))
        request.httpMethod = "POST"
        request.httpBody = try? JSONSerialization.data(withJSONObject: list)
        _ = try? await URLSession.shared.data(for: request)
    }
    #endif
}

#if DEBUG
/// The screen tests' stand-in for the phone's notification settings, set by `-uiTestNotifications`: "allowed" (the
/// person already said yes), "willAllow" or "willDeny" (not asked yet, and that is the answer they will give). With
/// no setting, the person was asked before and said no, so a test that saves a plant meets no question and sends no
/// answer. The reminders it holds are the ones the app would leave with the phone.
actor TestNotifications: NotificationCentre {
    private var state: NotificationPermission
    private let answer: Bool
    private var held: [String: PlannedReminder] = [:]

    init(setting: String?) {
        switch setting {
        case "allowed": state = .allowed; answer = true
        case "willAllow": state = .notAsked; answer = true
        case "willDeny": state = .notAsked; answer = false
        default: state = .denied; answer = false
        }
    }

    func permission() async -> NotificationPermission { state }

    func ask() async -> Bool {
        state = answer ? .allowed : .denied
        return answer
    }

    func add(_ reminder: PlannedReminder) async { held[reminder.id] = reminder }

    func removeWaiting(startingWith prefix: String) async {
        held = held.filter { !$0.key.hasPrefix(prefix) }
    }

    func waiting() async -> [PlannedReminder] { held.values.sorted { $0.id < $1.id } }
}
#endif
