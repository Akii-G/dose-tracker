import Foundation
import UserNotifications

/// Notifications when doses are due: one at the time (doses due together share one), and one
/// more 30 minutes later if it isn't marked. "Taken" in the notification marks them; "Later"
/// asks again in 10 minutes. Planned again after every change, so marked doses don't ring.
enum Reminders {
    static let category = "DOSE"
    static let prefix = "dose-"

    static func register() {
        let taken = UNNotificationAction(identifier: "TAKEN", title: tr("Taken"), options: [])
        let later = UNNotificationAction(identifier: "LATER", title: tr("Later (10 minutes)"), options: [])
        let cat = UNNotificationCategory(identifier: category, actions: [taken, later], intentIdentifiers: [], options: [])
        UNUserNotificationCenter.current().setNotificationCategories([cat])
    }

    static func ask() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    static var followUp: Bool { UserDefaults.standard.object(forKey: "followUp") as? Bool ?? true }
    static var discreet: Bool { UserDefaults.standard.bool(forKey: "discreet") }

    static func content(day: String, time: String, meds: [Medicine], again: Bool) -> UNMutableNotificationContent {
        let c = UNMutableNotificationContent()
        if discreet {
            c.title = "Dose"
            c.body = tr("Time for your %@ dose", time)
        } else {
            c.title = again ? tr("%@ · still to take", time) : tr("%@ · time for your medicine", time)
            var body = meds.map(\.title).joined(separator: ", ")
            if meds.count == 1 { body += " · " + Units.amount(meds[0].amount, meds[0].unit) }
            c.body = body
        }
        c.sound = .default
        c.categoryIdentifier = category
        c.threadIdentifier = "dose"
        c.userInfo = ["day": day, "time": time]
        return c
    }

    /// Plans the next three days (at most 60 notifications, iOS keeps 64).
    @MainActor
    static func plan(_ store: AppStore) {
        let center = UNUserNotificationCenter.current()
        center.getPendingNotificationRequests { pending in
            let ours = pending.map(\.identifier).filter { $0.hasPrefix(prefix) && !$0.hasSuffix("-later") }
            center.removePendingNotificationRequests(withIdentifiers: ours)
            Task { @MainActor in add(store) }
        }
    }

    @MainActor
    private static func add(_ store: AppStore) {
        let center = UNUserNotificationCenter.current()
        let now = Date()
        var count = 0
        for offset in 0..<3 {
            let day = Day.adding(offset, to: Day.today)
            var groups: [String: [Medicine]] = [:]
            for slot in store.slots(day) where slot.dose == nil { groups[slot.time, default: []].append(slot.med) }
            for time in groups.keys.sorted() {
                guard let at = Day.time(time, on: day), let meds = groups[time] else { continue }
                for again in followUp ? [false, true] : [false] {
                    let when = again ? at.addingTimeInterval(30 * 60) : at
                    if when <= now || count >= 60 { continue }
                    let comps = Day.calendar.dateComponents([.year, .month, .day, .hour, .minute], from: when)
                    let id = "\(prefix)\(day)-\(time)" + (again ? "-again" : "")
                    center.add(UNNotificationRequest(identifier: id, content: content(day: day, time: time, meds: meds, again: again),
                                                     trigger: UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)))
                    count += 1
                }
            }
        }
        // marked doses: take their notifications off the screen too
        center.getDeliveredNotifications { delivered in
            let items: [(id: String, day: String, time: String)] = delivered.compactMap { n in
                guard let day = n.request.content.userInfo["day"] as? String,
                      let time = n.request.content.userInfo["time"] as? String else { return nil }
                return (n.request.identifier, day, time)
            }
            Task { @MainActor in
                let done = items.filter { item in
                    !store.slots(item.day).contains { $0.time == item.time && $0.dose == nil }
                }.map(\.id)
                center.removeDeliveredNotifications(withIdentifiers: done)
            }
        }
    }

    /// "Taken" from a notification: every dose of that time still unmarked.
    @MainActor
    static func takeAll(_ store: AppStore, day: String, time: String) {
        for slot in store.slots(day) where slot.time == time && slot.dose == nil {
            store.mark(slot.med.id, day: day, time: time, status: "taken", notify: false)
        }
        plan(store)              // no follow-up for what's taken, even if Dose was asleep
        store.onChange?()
    }

    static func later(_ original: UNNotificationContent) {
        let c = (original.mutableCopy() as? UNMutableNotificationContent) ?? UNMutableNotificationContent()
        let day = original.userInfo["day"] as? String ?? Day.today
        let time = original.userInfo["time"] as? String ?? ""
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 10 * 60, repeats: false)
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "\(prefix)\(day)-\(time)-later",
                                                                     content: c, trigger: trigger))
    }
}

/// Answers notifications: Taken, Later, or a tap that opens Today.
final class NotificationHandler: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationHandler()

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let content = response.notification.request.content
        let day = content.userInfo["day"] as? String ?? Day.today
        let time = content.userInfo["time"] as? String ?? ""
        switch response.actionIdentifier {
        case "TAKEN":
            Task { @MainActor in
                AppStore.shared.load()            // the app may have been asleep: read the file first
                Reminders.takeAll(AppStore.shared, day: day, time: time)
                completionHandler()
            }
            return
        case "LATER":
            Reminders.later(content)
        default:
            break
        }
        completionHandler()
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound, .list])
    }
}
