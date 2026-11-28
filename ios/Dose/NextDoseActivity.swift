import ActivityKit
import Foundation

/// Keeps the next-dose Live Activity in step with Dose: started when Dose is open, updated after
/// each change (also from the Taken button and the notifications), ended when nothing is left
/// to take today and tomorrow or when it's switched off in Settings.
@MainActor
enum NextDoseActivity {
    static var isOn: Bool { UserDefaults.standard.object(forKey: "liveActivity") as? Bool ?? true }

    static func refresh(_ store: AppStore) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let running = Activity<DoseActivityAttributes>.activities
        guard isOn, let state = next(store) else {
            for activity in running { Task { await activity.end(nil, dismissalPolicy: .immediate) } }
            return
        }
        let content = ActivityContent(state: state, staleDate: state.due.addingTimeInterval(6 * 3600))
        if let first = running.first {
            if first.content.state != state { Task { await first.update(content) } }
            for extra in running.dropFirst() { Task { await extra.end(nil, dismissalPolicy: .immediate) } }
        } else {
            _ = try? Activity.request(attributes: DoseActivityAttributes(), content: content, pushType: nil)
        }
    }

    /// The earliest time with doses still to take: today (late ones included), else tomorrow.
    static func next(_ store: AppStore) -> DoseActivityAttributes.ContentState? {
        let discreet = UserDefaults.standard.bool(forKey: "discreet")
        for offset in 0...1 {
            let day = Day.adding(offset, to: Day.today)
            let open = store.slots(day).filter { $0.dose == nil }
            guard let time = open.map(\.time).min(), let due = Day.time(time, on: day) else { continue }
            let here = open.filter { $0.time == time }
            let items = discreet ? [] : here.map {
                DoseActivityAttributes.Item(name: $0.med.title, amount: Units.amount($0.med.amount(at: time), $0.med.unit))
            }
            return DoseActivityAttributes.ContentState(
                day: day, time: time, due: due, items: items, count: here.count,
                heading: due < Date() ? tr("Not taken yet") : (offset == 0 ? tr("Next dose") : tr("Tomorrow")),
                summary: trn("%@ dose", "%@ doses", here.count),
                takenText: tr("Taken"),
                accent: Tone.current.accent)
        }
        return nil
    }
}
