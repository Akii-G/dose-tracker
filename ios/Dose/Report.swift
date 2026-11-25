import Foundation
import UIKit

/// The report for the doctor, the same as the PC's (src/dose, report_pdf): for a period, each
/// medicine's doses taken, skipped and not taken, those taken when needed, the changes, then the
/// doses day by day. As a PDF, and as a CSV for a spreadsheet.
@MainActor
enum Report {
    struct Count { let med: Medicine; var due = 0, taken = 0, skipped = 0, missed = 0 }
    /// status: taken, skipped, missed, needed
    struct Row { let time: String; let med: Medicine; let status: String; let at: String; let amount: Double }
    struct Data {
        var meds: [Count] = []
        var needed: [(Medicine, Int)] = []
        var days: [(String, [Row])] = []
        var changes: [(String, Medicine, Change)] = []
    }

    /// A medicine stopped later still counts on the days before it was stopped.
    private static func wasDue(_ m: Medicine, _ day: String) -> Bool {
        let stop = m.history.filter { $0.kind == "stopped" }.map(\.date).max()
        var med = m
        if m.archived, let stop, day < stop { med.archived = false }
        return med.due(on: day)
    }

    static func data(_ store: AppStore, from start: String, to end: String, now: Date = Date()) -> Data {
        var counts: [String: Count] = [:], needed: [String: Int] = [:]
        var out = Data()
        var day = start
        while day <= end {
            var rows: [Row] = []
            for m in store.medicines.values where wasDue(m, day) {
                for t in m.schedule.times {
                    let dose = store.doses[AppStore.slotID(m.id, day, t)]
                    if dose == nil, let at = Day.time(t, on: day), at > now { continue }   // still to come
                    let status = dose?.status ?? "missed"
                    var c = counts[m.id] ?? Count(med: m)
                    c.due += 1
                    switch status { case "taken": c.taken += 1; case "skipped": c.skipped += 1; default: c.missed += 1 }
                    counts[m.id] = c
                    rows.append(Row(time: t, med: m, status: status, at: dose?.at ?? "", amount: dose?.amount ?? m.amount(at: t)))
                }
            }
            for d in store.extra(day) {
                guard let m = store.medicines[d.med] else { continue }
                needed[m.id, default: 0] += 1
                rows.append(Row(time: d.at, med: m, status: "needed", at: d.at, amount: d.amount))
            }
            rows.sort { ($0.time, $0.med.name.lowercased()) < ($1.time, $1.med.name.lowercased()) }
            if !rows.isEmpty { out.days.append((day, rows)) }
            day = Day.adding(1, to: day)
        }
        out.meds = counts.values.sorted { $0.med.name.lowercased() < $1.med.name.lowercased() }
        out.needed = needed.compactMap { id, n in store.medicines[id].map { ($0, n) } }
            .sorted { $0.0.name.lowercased() < $1.0.name.lowercased() }
        out.changes = store.medicines.values.flatMap { m in m.history.filter { $0.date >= start && $0.date <= end }.map { ($0.date, m, $0) } }
            .sorted { ($0.0, $0.1.name.lowercased()) < ($1.0, $1.1.name.lowercased()) }
        return out
    }

    static func changeText(_ c: Change) -> String {
        let word: String
        switch c.kind {
        case "started": word = tr("Started")
        case "stopped": word = tr("Stopped")
        case "restarted": word = tr("Started again")
        default: word = tr("Dose changed")
        }
        guard let d = c.detail, c.kind != "stopped" else { return word }
        return word + " · " + Describe.line(d.amount, d.unit, d.schedule.cleaned)
    }

    private static func word(_ r: Row) -> String {
        switch r.status {
        case "taken": return tr("Taken at %@", r.at)
        case "skipped": return tr("Skipped")
        case "missed": return tr("Not taken")
        default: return tr("When needed, at %@", r.at)
        }
    }

    /// Every dose of the period, one per line. ";" between columns in Italian, as Excel expects there.
    static func csv(_ store: AppStore, from start: String, to end: String) -> Foundation.Data {
        let sep = isItalian ? ";" : ","
        func cell(_ s: String) -> String {
            s.contains(sep) || s.contains("\"") || s.contains("\n") ? "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\"" : s
        }
        let words = ["taken": tr("Taken"), "skipped": tr("Skipped"), "missed": tr("Not taken"), "needed": tr("Taken when needed")]
        var text = "\u{FEFF}" + [tr("Date"), tr("Time"), tr("Medicine"), tr("Strength"), tr("Amount"), tr("Status"), tr("Taken at")]
            .map(cell).joined(separator: sep) + "\r\n"
        for (day, rows) in data(store, from: start, to: end).days {
            for r in rows {
                text += [day, r.status == "needed" ? "" : r.time, r.med.name, r.med.strength, Units.amount(r.amount, r.med.unit),
                         words[r.status] ?? "", r.status == "taken" || r.status == "needed" ? r.at : ""]
                    .map(cell).joined(separator: sep) + "\r\n"
            }
        }
        return Foundation.Data(text.utf8)
    }

    /// A4 pages, drawn like the PC's.
    static func pdf(_ store: AppStore, from start: String, to end: String) -> Foundation.Data {
        let d = data(store, from: start, to: end)
        let W: CGFloat = 595, H: CGFloat = 842, M: CGFloat = 48
        let violet = UIColor(red: 0.36, green: 0.34, blue: 0.71, alpha: 1), ink = UIColor(white: 0.1, alpha: 1)
        let grey = UIColor(white: 0.45, alpha: 1), red = UIColor(red: 0.8, green: 0.25, blue: 0.25, alpha: 1)
        let period = Day.pretty(start, style: "d MMM yyyy") + " – " + Day.pretty(end, style: "d MMM yyyy")
        let format = UIGraphicsPDFRendererFormat()
        format.documentInfo = [kCGPDFContextTitle as String: tr("Medicines report"), kCGPDFContextCreator as String: "Dose"]
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: W, height: H), format: format)
        return renderer.pdfData { ctx in
            var y = M, page = 0

            @discardableResult
            func text(_ x: CGFloat, _ top: CGFloat, _ s: String, _ size: CGFloat = 10, bold: Bool = false, color: UIColor = ink,
                      width: CGFloat? = nil, center: Bool = false) -> CGFloat {
                let para = NSMutableParagraphStyle()
                para.alignment = center ? .center : .natural
                para.lineBreakMode = .byWordWrapping
                let attrs: [NSAttributedString.Key: Any] = [.font: bold ? UIFont.boldSystemFont(ofSize: size) : UIFont.systemFont(ofSize: size),
                                                            .foregroundColor: color, .paragraphStyle: para]
                let w = width ?? (W - M - x)
                let rect = (s as NSString).boundingRect(with: CGSize(width: w, height: .greatestFiniteMagnitude),
                                                        options: [.usesLineFragmentOrigin], attributes: attrs, context: nil)
                (s as NSString).draw(with: CGRect(x: x, y: top, width: w, height: ceil(rect.height)),
                                     options: [.usesLineFragmentOrigin], attributes: attrs, context: nil)
                return ceil(rect.height)
            }
            func footer() { text(M, H - 34, tr("Dose · %@ · page %@", period, String(page)), 8, color: grey, width: W - 2 * M, center: true) }
            func newPage() { ctx.beginPage(); page += 1; y = M }
            func need(_ h: CGFloat) { if y + h > H - 54 { footer(); newPage() } }
            func rule(_ at: CGFloat) {
                let path = UIBezierPath(); path.move(to: CGPoint(x: M, y: at)); path.addLine(to: CGPoint(x: W - M, y: at))
                UIColor(white: 0.87, alpha: 1).setStroke(); path.lineWidth = 0.7; path.stroke()
            }

            newPage()
            y += text(M, y, tr("Medicines report"), 22, bold: true, color: violet) + 4
            y += text(M, y, period, 12, color: grey) + 2
            y += text(M, y, tr("Made with Dose on %@. Doses not yet due are not counted.", Day.pretty(Day.today, style: "d MMM yyyy")), 8, color: grey) + 18

            let cols: [(CGFloat, String)] = [(M, tr("Medicine")), (M + 250, tr("Taken")), (M + 305, tr("Skipped")), (M + 365, tr("Not taken")), (M + 430, tr("% taken"))]
            y += text(M, y, tr("Scheduled doses"), 13, bold: true) + 6
            if d.meds.isEmpty {
                y += text(M, y, tr("No scheduled doses in this period."), 10, color: grey) + 10
            } else {
                for (x, label) in cols { text(x, y, label, 8, bold: true, color: grey, width: x > M ? 60 : 200) }
                y += 16; rule(y); y += 6
                for c in d.meds {
                    need(40)
                    let name = c.med.title + (c.med.archived ? " (" + tr("stopped") + ")" : "")
                    var h = text(M, y, name, 10, bold: true, width: 240)
                    h += text(M, y + h, Describe.line(c.med.amount, c.med.unit, c.med.schedule), 8, color: grey, width: 240)
                    let pct = c.due > 0 ? Int((100.0 * Double(c.taken) / Double(c.due)).rounded()) : 0
                    let values = [String(c.taken), String(c.skipped), String(c.missed), "\(pct)%"]
                    let colors = [ink, ink, c.missed > 0 ? red : ink, violet]
                    for i in 0..<4 { text(cols[i + 1].0, y, values[i], 10, bold: i == 3, color: colors[i], width: 60) }
                    y += max(h, 14) + 6; rule(y - 3)
                }
                y += 10
            }
            if !d.needed.isEmpty {
                need(40)
                y += text(M, y, tr("Taken when needed"), 13, bold: true) + 6
                for (m, n) in d.needed { need(18); y += text(M, y, m.title + " · " + trn("%@ time", "%@ times", n), 10) + 4 }
                y += 10
            }
            if !d.changes.isEmpty {
                need(40)
                y += text(M, y, tr("Changes"), 13, bold: true) + 6
                for (day, m, c) in d.changes {
                    need(18)
                    text(M, y, Day.pretty(day, style: "d MMM yyyy"), 9, color: grey, width: 85)
                    y += text(M + 90, y, m.title + " · " + changeText(c), 9) + 4
                }
                y += 10
            }
            need(40)
            y += text(M, y, tr("Day by day"), 13, bold: true) + 6
            for (day, rows) in d.days.reversed() {
                need(22 + 14 * CGFloat(min(rows.count, 3)))
                let title = Day.pretty(day, style: "EEEE d MMMM yyyy")
                y += text(M, y, title.prefix(1).uppercased() + title.dropFirst(), 10, bold: true, color: violet) + 3
                for r in rows {
                    need(14)
                    if r.status != "needed" { text(M + 10, y, r.time, 9, color: grey, width: 40) }
                    text(M + 55, y, r.med.title + " · " + Units.amount(r.amount, r.med.unit), 9, width: 280)
                    y += text(M + 345, y, word(r), 9, bold: r.status == "taken",
                              color: r.status == "missed" ? red : r.status == "skipped" ? grey : violet) + 3
                }
                y += 6
            }
            footer()
        }
    }
}

/// A Dose backup: everything, the same as dose.json, marked as a backup.
@MainActor
enum Backup {
    private struct File: Encodable {
        let medicines: [Medicine], doses: [DoseRecord], deleted: [Tombstone]
        let backup: [String: String]
    }

    static func data(_ store: AppStore) -> Foundation.Data {
        let s = store.state()
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        let file = File(medicines: s.medicines, doses: s.doses, deleted: s.deleted,
                        backup: ["app": "Dose", "version": version, "made": Stamp.now()])
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        return (try? enc.encode(file)) ?? Foundation.Data()
    }

    /// Merges a backup into what's here (the newer change wins); nil if it isn't a Dose backup.
    static func restore(_ store: AppStore, _ data: Foundation.Data) -> Int? {
        guard let top = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              top["medicines"] is [Any], top["doses"] is [Any],
              let state = try? JSONDecoder().decode(DoseState.self, from: data) else { return nil }
        let n = store.absorb(state)
        store.save()
        store.onChange?()
        return n
    }
}
