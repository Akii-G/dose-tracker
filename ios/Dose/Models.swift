import Foundation

// The same records as Dose on the PC (src/dose, docs/FORMAT.md). Field names match its
// dose.json and the sync JSON. Every decoder tolerates missing fields, so a newer or older
// PC never breaks the app.

struct Schedule: Codable, Hashable {
    var kind: String = "daily"            // daily, weekdays, every, needed
    var times: [String] = ["08:00"]       // "HH:MM", sorted
    var weekdays: [Int] = [1, 2, 3, 4, 5, 6, 7]   // 1 = Monday … 7 = Sunday
    var every: Int = 2                    // for "every": every N days from start
    var start: String = Day.today         // "YYYY-MM-DD"
    var end: String = ""                  // "" or the last day of a course
    var amounts: [String: Double] = [:]   // a different amount at some times ("20:00": 0.5)

    init() {}

    enum CodingKeys: String, CodingKey { case kind, times, weekdays, every, start, end, amounts }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(kind, forKey: .kind); try c.encode(times, forKey: .times)
        try c.encode(weekdays, forKey: .weekdays); try c.encode(every, forKey: .every)
        try c.encode(start, forKey: .start); try c.encode(end, forKey: .end)
        if !amounts.isEmpty { try c.encode(amounts, forKey: .amounts) }   // left out when not used
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        kind = (try? c.decode(String.self, forKey: .kind)) ?? "daily"
        times = (try? c.decode([String].self, forKey: .times)) ?? []
        weekdays = (try? c.decode([Int].self, forKey: .weekdays)) ?? [1, 2, 3, 4, 5, 6, 7]
        every = (try? c.decode(Int.self, forKey: .every)) ?? 2
        start = (try? c.decode(String.self, forKey: .start)) ?? Day.today
        end = (try? c.decode(String.self, forKey: .end)) ?? ""
        amounts = (try? c.decode([String: Double].self, forKey: .amounts)) ?? [:]
    }

    /// Checked like the PC's clean_schedule.
    var cleaned: Schedule {
        var s = self
        if !["daily", "weekdays", "every", "needed"].contains(s.kind) { s.kind = "daily" }
        s.times = Array(Set(s.times.filter { Day.isTime($0) })).sorted()
        if s.kind != "needed" && s.times.isEmpty { s.times = ["08:00"] }
        if s.kind == "needed" { s.times = [] }
        s.weekdays = Array(Set(s.weekdays.filter { (1...7).contains($0) })).sorted()
        if s.weekdays.isEmpty { s.weekdays = [1, 2, 3, 4, 5, 6, 7] }
        s.every = min(max(s.every, 1), 365)
        if Day.date(s.start) == nil { s.start = Day.today }
        if !s.end.isEmpty && Day.date(s.end) == nil { s.end = "" }
        s.amounts = s.amounts.filter { s.times.contains($0.key) && $0.value > 0 && $0.value <= 1000 }
            .mapValues { ($0 * 100).rounded() / 100 }
        return s
    }
}

struct Stock: Codable, Hashable {
    var count: Double
    var set: String        // UTC stamp: doses taken after it are subtracted
}

/// What a medicine was at a change: the dose and the schedule.
struct Snapshot: Codable, Hashable {
    var amount: Double
    var unit: String
    var strength: String
    var schedule: Schedule

    init(_ m: Medicine) {
        amount = m.amount; unit = m.unit; strength = m.strength; schedule = m.schedule
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        amount = (try? c.decode(Double.self, forKey: .amount)) ?? 1
        unit = (try? c.decode(String.self, forKey: .unit)) ?? "pill"
        strength = (try? c.decode(String.self, forKey: .strength)) ?? ""
        schedule = (try? c.decode(Schedule.self, forKey: .schedule)) ?? Schedule()
    }
}

struct Change: Codable, Hashable {
    var date: String       // "YYYY-MM-DD"
    var kind: String       // started, changed, stopped, restarted
    var detail: Snapshot?
}

struct Medicine: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var strength: String = ""
    var form: String = ""
    var ingredient: String = ""
    var note: String = ""
    var amount: Double = 1
    var unit: String = "pill"
    var schedule = Schedule()
    var stock: Stock? = nil
    var refillDays: Int = 7
    var archived: Bool = false
    var history: [Change] = []
    var updated: String = Stamp.now()

    init(id: String = AppStore.newID(), name: String) {
        self.id = id
        self.name = name
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        strength = (try? c.decode(String.self, forKey: .strength)) ?? ""
        form = (try? c.decode(String.self, forKey: .form)) ?? ""
        ingredient = (try? c.decode(String.self, forKey: .ingredient)) ?? ""
        note = (try? c.decode(String.self, forKey: .note)) ?? ""
        amount = (try? c.decode(Double.self, forKey: .amount)) ?? 1
        unit = (try? c.decode(String.self, forKey: .unit)) ?? "pill"
        schedule = ((try? c.decode(Schedule.self, forKey: .schedule)) ?? Schedule()).cleaned
        stock = try? c.decode(Stock.self, forKey: .stock)
        refillDays = (try? c.decode(Int.self, forKey: .refillDays)) ?? 7
        archived = (try? c.decode(Bool.self, forKey: .archived)) ?? false
        history = (try? c.decode([Change].self, forKey: .history)) ?? []
        updated = (try? c.decode(String.self, forKey: .updated)) ?? Stamp.now()
        if !Units.keys.contains(unit) { unit = "pill" }
        amount = min(max(amount, 0.25), 1000)
    }

    var title: String { strength.isEmpty ? name : "\(name) \(strength)" }
    var asNeeded: Bool { schedule.kind == "needed" }

    /// Average amount a day, for how long the stock lasts.
    /// How much is taken at `time`: its own amount if it has one, else the medicine's.
    func amount(at time: String) -> Double { schedule.amounts[time] ?? amount }

    var perDay: Double {
        let n = schedule.times.reduce(0) { $0 + amount(at: $1) }
        switch schedule.kind {
        case "weekdays": return n * Double(schedule.weekdays.count) / 7
        case "every": return n / Double(max(schedule.every, 1))
        case "needed": return 0
        default: return n
        }
    }

    /// Whether it has doses on `day` (as-needed ones never do).
    func due(on day: String) -> Bool {
        if archived || schedule.kind == "needed" || day < schedule.start { return false }
        if !schedule.end.isEmpty && day > schedule.end { return false }
        switch schedule.kind {
        case "weekdays": return schedule.weekdays.contains(Day.isoWeekday(day))
        case "every": return Day.between(schedule.start, day) % max(schedule.every, 1) == 0
        default: return true
        }
    }
}

/// A dose marked taken or skipped. Scheduled ones have the id "med@day@time"; one taken when
/// needed has its own id and an empty time.
struct DoseRecord: Codable, Identifiable, Hashable {
    var id: String
    var med: String
    var day: String
    var time: String
    var status: String     // taken, skipped
    var at: String         // "HH:MM", when it was actually taken
    var amount: Double
    var stamp: String      // UTC, when it was marked: counts against the stock after its set time
    var updated: String
}

struct Tombstone: Codable, Hashable {
    var id: String
    var stamp: String
}

struct DoseState: Codable {
    var medicines: [Medicine]
    var doses: [DoseRecord]
    var deleted: [Tombstone]

    init(medicines: [Medicine], doses: [DoseRecord], deleted: [Tombstone]) {
        self.medicines = medicines; self.doses = doses; self.deleted = deleted
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        // one bad record doesn't lose the others
        medicines = ((try? c.decode([Lenient<Medicine>].self, forKey: .medicines)) ?? []).compactMap(\.value)
        doses = ((try? c.decode([Lenient<DoseRecord>].self, forKey: .doses)) ?? []).compactMap(\.value)
        deleted = ((try? c.decode([Lenient<Tombstone>].self, forKey: .deleted)) ?? []).compactMap(\.value)
    }
}

/// Decodes a value, or nil instead of failing the whole list.
struct Lenient<T: Decodable>: Decodable {
    let value: T?
    init(from decoder: Decoder) throws { value = try? T(from: decoder) }
}

enum Units {
    static let keys = ["pill", "capsule", "drop", "ml", "puff", "sachet", "patch", "injection", "application"]

    static func name(_ unit: String, plural: Bool) -> String {
        switch unit {
        case "capsule": return plural ? tr("capsules") : tr("capsule")
        case "drop": return plural ? tr("drops") : tr("drop")
        case "ml": return tr("ml")
        case "puff": return plural ? tr("puffs") : tr("puff")
        case "sachet": return plural ? tr("sachets") : tr("sachet")
        case "patch": return plural ? tr("patches") : tr("patch")
        case "injection": return plural ? tr("injections") : tr("injection")
        case "application": return plural ? tr("applications") : tr("application")
        default: return plural ? tr("pills") : tr("pill")
        }
    }

    /// 1 -> "1 pill", 0.5 -> "½ pill", 1.5 -> "1½ pills", 2.5 ml -> "2.5 ml".
    static func amount(_ amount: Double, _ unit: String) -> String {
        let whole = Int(amount)
        let frac = (amount - Double(whole) + 0.0001) * 100
        let glyph: String? = abs(frac - 25) < 1 ? "¼" : abs(frac - 50) < 1 ? "½" : abs(frac - 75) < 1 ? "¾" : nil
        var number: String
        if amount == Double(whole) {
            number = "\(whole)"
        } else if unit != "ml", let glyph {
            number = (whole > 0 ? "\(whole)" : "") + glyph
        } else {
            number = String(format: "%g", amount)
            if isItalian { number = number.replacingOccurrences(of: ".", with: ",") }
        }
        return "\(number) \(name(unit, plural: amount > 1))"
    }
}

enum Kinds {
    static let all: [(key: String, label: String)] = [
        ("daily", tr("Every day")), ("weekdays", tr("Some days of the week")),
        ("every", tr("Every few days")), ("needed", tr("Only when needed")),
    ]
}

enum Stamp {
    static func now() -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        f.timeZone = TimeZone(identifier: "UTC")
        return f.string(from: Date())
    }
}

/// Days as "YYYY-MM-DD" in the phone's time zone, like the PC.
enum Day {
    static var calendar: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = .current
        return c
    }

    private static func formatter(_ format: String) -> DateFormatter {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = .current
        f.dateFormat = format
        return f
    }

    static var today: String { string(Date()) }
    static func string(_ date: Date) -> String { formatter("yyyy-MM-dd").string(from: date) }
    static func date(_ day: String) -> Date? { formatter("yyyy-MM-dd").date(from: day) }
    static func timeString(_ date: Date) -> String { formatter("HH:mm").string(from: date) }
    static var nowTime: String { timeString(Date()) }

    static func isTime(_ s: String) -> Bool {
        guard s.count == 5, let h = Int(s.prefix(2)), let m = Int(s.suffix(2)), s.dropFirst(2).first == ":" else { return false }
        return (0...23).contains(h) && (0...59).contains(m)
    }

    static func time(_ hhmm: String, on day: String) -> Date? {
        guard let d = date(day), let h = Int(hhmm.prefix(2)), let m = Int(hhmm.suffix(2)) else { return nil }
        return calendar.date(bySettingHour: h, minute: m, second: 0, of: d)
    }

    static func adding(_ days: Int, to day: String) -> String {
        guard let d = date(day), let n = calendar.date(byAdding: .day, value: days, to: d) else { return day }
        return string(n)
    }

    static func between(_ a: String, _ b: String) -> Int {
        guard let x = date(a), let y = date(b) else { return 0 }
        return calendar.dateComponents([.day], from: x, to: y).day ?? 0
    }

    /// 1 = Monday … 7 = Sunday.
    static func isoWeekday(_ day: String) -> Int {
        guard let d = date(day) else { return 1 }
        return (calendar.component(.weekday, from: d) + 5) % 7 + 1
    }

    /// "Tuesday 25 November", in the app's language.
    static func pretty(_ day: String, style: String = "EEEE d MMMM") -> String {
        guard let d = date(day) else { return day }
        let f = DateFormatter()
        f.locale = Locale(identifier: isItalian ? "it_IT" : "en_GB")
        f.dateFormat = style
        let text = f.string(from: d)
        return text.prefix(1).uppercased() + text.dropFirst()
    }

    /// Mon … Sun, Monday first.
    static var weekdayShort: [String] {
        [tr("Mon"), tr("Tue"), tr("Wed"), tr("Thu"), tr("Fri"), tr("Sat"), tr("Sun")]
    }
}

enum Describe {
    /// "Every day at 08:00 and 20:00", "Mon, Thu at 09:00", "Every 3 days at 08:00", "When needed".
    /// "1 pill · Every day at 08:00", or "Every day at 08:00 (1 pill) and 20:00 (½ pill)".
    static func line(_ amount: Double, _ unit: String, _ s: Schedule) -> String {
        if !s.amounts.isEmpty && s.kind != "needed" { return schedule(s, amount: amount, unit: unit) }
        return Units.amount(amount, unit) + " · " + schedule(s)
    }

    static func schedule(_ s: Schedule, amount: Double = 1, unit: String? = nil) -> String {
        var out: String
        if s.kind == "needed" {
            out = tr("When needed")
        } else {
            let shown = unit != nil && !s.amounts.isEmpty
                ? s.times.map { "\($0) (\(Units.amount(s.amounts[$0] ?? amount, unit!)))" } : s.times
            let at = shown.count == 1 ? shown[0]
                : tr("%@ and %@", shown.dropLast().joined(separator: ", "), shown.last ?? "")
            if s.kind == "weekdays" && s.weekdays.count < 7 {
                out = tr("%@ at %@", s.weekdays.map { Day.weekdayShort[$0 - 1] }.joined(separator: ", "), at)
            } else if s.kind == "every" && s.every > 1 {
                out = tr("Every %@ days at %@", String(s.every), at)
            } else {
                out = tr("Every day at %@", at)
            }
        }
        if !s.end.isEmpty {
            out += " · " + tr("until %@", Day.pretty(s.end, style: "d MMM yyyy"))
        }
        return out
    }

}
