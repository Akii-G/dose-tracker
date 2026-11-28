import Foundation
import Observation

/// Everything Dose keeps on the phone: one JSON file, the same as dose.json on the PC.
@MainActor
@Observable
final class AppStore {
    static let shared = AppStore()

    var medicines: [String: Medicine] = [:]
    var doses: [String: DoseRecord] = [:]
    var deleted: [String: String] = [:]
    /// Called after every change: sync soon and plan the reminders again.
    @ObservationIgnored var onChange: (() -> Void)?

    private let file: URL?

    init(file: URL? = AppStore.defaultFile) {
        self.file = file
        load()
    }

    nonisolated static var defaultFile: URL? {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        guard let dir else { return nil }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("dose.json")
    }

    nonisolated static func newID() -> String {
        String(UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(16))
    }

    // MARK: - File

    func load() {
        guard let file, let data = try? Data(contentsOf: file),
              let state = try? JSONDecoder().decode(DoseState.self, from: data) else { return }
        medicines = Dictionary(state.medicines.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        doses = Dictionary(state.doses.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        deleted = Dictionary(state.deleted.map { ($0.id, $0.stamp) }, uniquingKeysWith: { a, b in max(a, b) })
    }

    func state() -> DoseState {
        DoseState(medicines: medicines.values.sorted { $0.name.lowercased() < $1.name.lowercased() },
                  doses: doses.values.sorted { ($0.day, $0.time, $0.id) < ($1.day, $1.time, $1.id) },
                  deleted: deleted.map { Tombstone(id: $0.key, stamp: $0.value) }.sorted { $0.id < $1.id })
    }

    func save() {
        guard let file, let data = try? JSONEncoder().encode(state()) else { return }
        try? data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    private func changed() {
        save()
        onChange?()
    }

    /// Merge the PC's copy: the newer change wins, deletions included. Returns how many changed.
    @discardableResult
    func absorb(_ other: DoseState) -> Int {
        var changes = 0
        for t in other.deleted where t.stamp > (deleted[t.id] ?? "") {
            deleted[t.id] = t.stamp
            changes += 1
        }
        for m in other.medicines where m.updated > (medicines[m.id]?.updated ?? "") && m.updated > (deleted[m.id] ?? "") {
            var copy = m
            copy.schedule = copy.schedule.cleaned
            medicines[m.id] = copy
            changes += 1
        }
        for d in other.doses where d.updated > (doses[d.id]?.updated ?? "") && d.updated > (deleted[d.id] ?? "") {
            doses[d.id] = d
            changes += 1
        }
        for (id, m) in medicines where (deleted[id] ?? "") >= m.updated {
            medicines[id] = nil
            changes += 1
        }
        for (id, d) in doses where (deleted[id] ?? "") >= d.updated {
            doses[id] = nil
            changes += 1
        }
        if changes > 0 { save() }
        return changes
    }

    // MARK: - Medicines

    var active: [Medicine] {
        medicines.values.filter { !$0.archived }.sorted { $0.name.lowercased() < $1.name.lowercased() }
    }

    var stopped: [Medicine] {
        medicines.values.filter { $0.archived }.sorted { $0.name.lowercased() < $1.name.lowercased() }
    }

    /// Add or change a medicine; a new one, or a new dose or schedule, goes in its history.
    func put(_ med: Medicine, on day: String = Day.today) {
        var m = med
        m.schedule = m.schedule.cleaned
        m.updated = Stamp.now()
        if let old = medicines[m.id] {
            m.history = old.history
            if Snapshot(old) != Snapshot(m) { m.history.append(Change(date: day, kind: "changed", detail: Snapshot(m))) }
        } else {
            m.history = [Change(date: day, kind: "started", detail: Snapshot(m))]
        }
        medicines[m.id] = m
        deleted[m.id] = nil
        changed()
    }

    func setArchived(_ id: String, _ archived: Bool) {
        guard var m = medicines[id] else { return }
        m.archived = archived
        m.history.append(Change(date: Day.today, kind: archived ? "stopped" : "restarted", detail: Snapshot(m)))
        m.updated = Stamp.now()
        medicines[id] = m
        changed()
    }

    func delete(_ id: String) {
        let stamp = Stamp.now()
        medicines[id] = nil
        deleted[id] = stamp
        for (key, d) in doses where d.med == id {
            doses[key] = nil
            deleted[key] = stamp
        }
        changed()
    }

    // MARK: - Doses

    static func slotID(_ med: String, _ day: String, _ time: String) -> String { "\(med)@\(day)@\(time)" }

    struct Slot: Identifiable {
        let time: String
        let med: Medicine
        let dose: DoseRecord?
        var id: String { med.id + "@" + time }
    }

    /// The scheduled doses of a day, by time then name.
    func slots(_ day: String) -> [Slot] {
        var out: [Slot] = []
        for m in medicines.values where m.due(on: day) {
            for t in m.schedule.times {
                out.append(Slot(time: t, med: m, dose: doses[AppStore.slotID(m.id, day, t)]))
            }
        }
        return out.sorted { ($0.time, $0.med.name.lowercased()) < ($1.time, $1.med.name.lowercased()) }
    }

    /// Doses taken when needed on a day.
    func extra(_ day: String) -> [DoseRecord] {
        doses.values.filter { $0.day == day && $0.time.isEmpty }.sorted { $0.at < $1.at }
    }

    func mark(_ medID: String, day: String, time: String, status: String, at: String? = nil, notify: Bool = true) {
        guard let m = medicines[medID] else { return }
        let key = AppStore.slotID(medID, day, time)
        let now = Stamp.now()
        doses[key] = DoseRecord(id: key, med: medID, day: day, time: time, status: status,
                                at: at ?? Day.nowTime, amount: m.amount(at: time), stamp: now, updated: now)
        deleted[key] = nil
        if notify { changed() } else { save() }
    }

    func takeNow(_ medID: String, day: String = Day.today, at: String? = nil) {
        guard let m = medicines[medID] else { return }
        let key = AppStore.newID(), now = Stamp.now()
        doses[key] = DoseRecord(id: key, med: medID, day: day, time: "", status: "taken",
                                at: at ?? Day.nowTime, amount: m.amount, stamp: now, updated: now)
        changed()
    }

    func unmark(_ id: String) {
        guard doses[id] != nil else { return }
        doses[id] = nil
        deleted[id] = Stamp.now()
        changed()
    }

    /// (due, taken, skipped) on a day.
    func counts(_ day: String) -> (due: Int, taken: Int, skipped: Int) {
        let s = slots(day)
        return (s.count, s.filter { $0.dose?.status == "taken" }.count, s.filter { $0.dose?.status == "skipped" }.count)
    }

    // MARK: - Stock

    func remaining(_ m: Medicine) -> Double? {
        guard let stock = m.stock else { return nil }
        let used = doses.values.filter { $0.med == m.id && $0.status == "taken" && $0.stamp > stock.set }
            .reduce(0) { $0 + $1.amount }
        return max(stock.count - used, 0)
    }

    func daysLeft(_ m: Medicine) -> Int? {
        guard let left = remaining(m), m.perDay > 0 else { return nil }
        return Int(left / m.perDay)
    }

    func isLow(_ m: Medicine) -> Bool {
        guard let d = daysLeft(m) else { return false }
        return d <= m.refillDays
    }
}
