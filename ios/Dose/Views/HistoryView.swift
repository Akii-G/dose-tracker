import SwiftUI

struct HistoryView: View {
    @Environment(AppStore.self) private var store
    @State private var month = Day.date(String(Day.today.prefix(7)) + "-01") ?? Date()
    @State private var selected = Day.today

    var body: some View {
        NavigationStack {
            List {
                Section {
                    calendar
                }
                Section {
                    Text(Day.pretty(selected, style: "EEEE d MMMM yyyy")).font(.headline)
                }
                DayList(day: selected)
                if store.slots(selected).isEmpty && store.extra(selected).isEmpty && !store.active.contains(where: \.asNeeded) {
                    Text("Nothing scheduled on this day.").foregroundStyle(.secondary)
                }
                let changes = store.medicines.values.flatMap { m in m.history.map { (m, $0) } }
                    .sorted { ($0.1.date, $0.0.name) > ($1.1.date, $1.0.name) }
                if !changes.isEmpty {
                    Section("Changes") {
                        ForEach(Array(changes.prefix(60).enumerated()), id: \.offset) { _, item in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(kindText(item.1.kind) + " · " + item.0.name).font(.subheadline.weight(.semibold))
                                Text(detail(item.0, item.1)).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
            .navigationTitle("History")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private var monthDays: [String] {
        let first = Day.string(month)
        var out: [String] = []
        var d = first
        while d.prefix(7) == first.prefix(7) { out.append(d); d = Day.adding(1, to: d) }
        return out
    }

    private var calendar: some View {
        VStack(spacing: 10) {
            HStack {
                Text(Day.pretty(Day.string(month), style: "LLLL yyyy")).font(.title3.bold())
                Spacer()
                Button { shift(-1) } label: { Image(systemName: "chevron.left") }
                    .buttonStyle(.borderless)
                Button { shift(1) } label: { Image(systemName: "chevron.right") }
                    .buttonStyle(.borderless)
                    .disabled(Day.string(month).prefix(7) >= Day.today.prefix(7))
            }
            let days = monthDays
            let lead = Day.isoWeekday(days[0]) - 1
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 7), spacing: 6) {
                ForEach(Day.weekdayShort, id: \.self) { name in
                    Text(name).font(.caption2).foregroundStyle(.secondary)
                }
                ForEach(0..<lead, id: \.self) { _ in Color.clear.frame(height: 36) }
                ForEach(days, id: \.self) { d in dayCell(d) }
            }
            HStack(spacing: 14) {
                legend(Palette.taken, tr("All taken"))
                legend(Palette.some, tr("Some"))
                legend(Palette.none, tr("None marked"))
            }
            .font(.caption2)
            .foregroundStyle(.secondary)
        }
        .padding(.vertical, 6)
    }

    private func dayCell(_ d: String) -> some View {
        let c = d <= Day.today ? store.counts(d) : (due: 0, taken: 0, skipped: 0)
        var fill = Color.clear
        if c.due > 0 && (d < Day.today || c.taken == c.due || c.taken > 0) {
            fill = c.taken == c.due ? Palette.taken : c.taken > 0 ? Palette.some : Palette.none
        }
        let isSel = d == selected
        return Button { selected = d } label: {
            Text(String(Int(d.suffix(2)) ?? 0))
                .font(.subheadline.weight(d == Day.today ? .bold : .regular))
                .frame(maxWidth: .infinity, minHeight: 36)
                .background(fill == .clear ? Color.primary.opacity(0.05) : fill, in: RoundedRectangle(cornerRadius: 9))
                .foregroundStyle(c.due > 0 && c.taken == c.due && fill != .clear ? Color.white : .primary)
                .overlay(RoundedRectangle(cornerRadius: 9).stroke(isSel ? Palette.accent : .clear, lineWidth: 2.5))
        }
        .buttonStyle(.plain)
        .disabled(d > Day.today)
        .opacity(d > Day.today ? 0.35 : 1)
    }

    private func legend(_ color: Color, _ text: String) -> some View {
        HStack(spacing: 4) {
            Circle().fill(color).frame(width: 9, height: 9)
            Text(text)
        }
    }

    private func shift(_ n: Int) {
        if let m = Day.calendar.date(byAdding: .month, value: n, to: month) { month = m }
    }

    private func kindText(_ kind: String) -> String {
        switch kind {
        case "started": return tr("Started")
        case "stopped": return tr("Stopped")
        case "restarted": return tr("Started again")
        default: return tr("Dose changed")
        }
    }

    private func detail(_ m: Medicine, _ c: Change) -> String {
        var parts = [Day.pretty(c.date, style: "d MMM yyyy")]
        if let d = c.detail, c.kind != "stopped" {
            parts.append(Units.amount(d.amount, d.unit))
            parts.append(Describe.schedule(d.schedule.cleaned))
        }
        return parts.joined(separator: " · ")
    }
}
