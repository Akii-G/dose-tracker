import SwiftUI

struct TodayView: View {
    @Environment(AppStore.self) private var store
    @State private var adding = false
    @State private var minute = Day.nowTime
    private let clock = Timer.publish(every: 30, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            Group {
                if store.medicines.isEmpty {
                    ContentUnavailableView {
                        Label("No medicines yet", systemImage: "pills")
                    } description: {
                        Text("Add the medicines you take, and Dose will remind you when it's time and remember what you took.")
                    } actions: {
                        Button("Add a medicine") { adding = true }
                            .buttonStyle(.borderedProminent)
                    }
                } else {
                    List {
                        Section {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(Day.pretty(Day.today)).font(.title2.bold())
                                Text(summary).foregroundStyle(.secondary)
                            }
                            .padding(.vertical, 4)
                            let low = store.active.filter { store.isLow($0) }
                            if !low.isEmpty {
                                Label(tr("Running low: %@", low.map { m in
                                    "\(m.name) (\(trn("%@ day left", "%@ days left", store.daysLeft(m) ?? 0)))"
                                }.joined(separator: ", ")), systemImage: "exclamationmark.circle")
                                .foregroundStyle(Palette.accent)
                                .font(.subheadline)
                            }
                        }
                        DayList(day: Day.today, minute: minute)
                    }
                }
            }
            .navigationTitle("Today")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { adding = true } label: { Label("Add a medicine", systemImage: "plus") }
                }
            }
            .sheet(isPresented: $adding) { EditorView(original: nil) }
            .onReceive(clock) { _ in minute = Day.nowTime }
        }
    }

    private var summary: String {
        let c = store.counts(Day.today)
        if c.due == 0 { return tr("Nothing scheduled today") }
        var line = c.taken < c.due ? tr("%@ of %@ taken", String(c.taken), String(c.due)) : tr("All taken today")
        if c.skipped > 0 { line += " · " + trn("%@ skipped", "%@ skipped", c.skipped) }
        return line
    }
}

/// The doses of one day, by part of the day, then the medicines taken when needed.
struct DayList: View {
    @Environment(AppStore.self) private var store
    let day: String
    var minute: String = ""

    var body: some View {
        let slots = store.slots(day)
        let times = Dictionary(grouping: slots, by: \.time)
        ForEach(times.keys.sorted(), id: \.self) { time in
            Section {
                ForEach(times[time] ?? []) { slot in
                    DoseRow(day: day, slot: slot, minute: minute)
                }
            } header: {
                Text(verbatim: time)
                    .font(.title3.weight(.semibold).monospacedDigit())
                    .foregroundStyle(Palette.accent)
                    .textCase(nil)
            }
        }
        let needed = store.active.filter(\.asNeeded)
        let extra = store.extra(day)
        let ids = needed.map(\.id) + extra.map(\.med).filter { id in !needed.contains { $0.id == id } }
        let unique = ids.reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }
        if !unique.isEmpty {
            Section("When needed") {
                ForEach(unique, id: \.self) { id in
                    if let med = store.medicines[id] {
                        NeededRow(day: day, med: med, doses: extra.filter { $0.med == id })
                    }
                }
            }
        }
    }
}

struct DoseRow: View {
    @Environment(AppStore.self) private var store
    let day: String
    let slot: AppStore.Slot
    var minute: String = ""
    @State private var picking = false
    @State private var at = Date()
    @Environment(\.dynamicTypeSize) private var textSize

    var body: some View {
        // with the largest text sizes the buttons go under the name, full width, instead of squeezing it
        let big = textSize >= .xxxLarge   // about 135%, like Android's 130%
        AdaptiveRow(big: big) {
            VStack(alignment: .leading, spacing: 2) {
                Text(slot.med.title).font(.body.weight(.semibold))
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
        } trailing: {
            if let dose = slot.dose {
                Text(dose.status == "taken" ? tr("Taken at %@", dose.at) : tr("Skipped"))
                    .font(.subheadline.weight(dose.status == "taken" ? .semibold : .regular))
                    .foregroundStyle(dose.status == "taken" ? Palette.taken : .secondary)
            } else {
                Button { mark("skipped") } label: { Text("Skip").frame(maxWidth: big ? .infinity : nil) }
                    .buttonStyle(.bordered)
                Button { mark("taken") } label: { Text("Taken").frame(maxWidth: big ? .infinity : nil) }
                    .buttonStyle(.borderedProminent)
            }
        }
        .contextMenu {
            if let dose = slot.dose {
                Button("Undo", systemImage: "arrow.uturn.backward") { store.unmark(dose.id) }
            } else {
                Button("Taken at another time", systemImage: "clock") {
                    at = Day.time(slot.time, on: day) ?? Date()
                    picking = true
                }
            }
        }
        .swipeActions {
            if let dose = slot.dose {
                Button("Undo") { store.unmark(dose.id) }.tint(.gray)
            }
        }
        .sheet(isPresented: $picking) {
            NavigationStack {
                Form {
                    DatePicker("Taken at", selection: $at, displayedComponents: .hourAndMinute)
                        .datePickerStyle(.wheel)
                }
                .navigationTitle(slot.med.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { picking = false } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") {
                            store.mark(slot.med.id, day: day, time: slot.time, status: "taken", at: Day.timeString(at))
                            picking = false
                        }
                    }
                }
            }
            .presentationDetents([.medium])
        }
    }

    private var detail: String {
        var parts = [Units.amount(slot.med.amount(at: slot.time), slot.med.unit)]
        if !slot.med.note.isEmpty { parts.append(slot.med.note) }
        if slot.dose == nil && (day < Day.today || (day == Day.today && minute > slot.time)) { parts.append(tr("Not taken")) }
        return parts.joined(separator: " · ")
    }

    private func mark(_ status: String) {
        // on a past day "Taken" means at the time it was due; on another time, use the menu
        let at = status == "taken" && day != Day.today ? slot.time : nil
        store.mark(slot.med.id, day: day, time: slot.time, status: status, at: at)
    }
}

struct NeededRow: View {
    @Environment(AppStore.self) private var store
    let day: String
    let med: Medicine
    let doses: [DoseRecord]
    @Environment(\.dynamicTypeSize) private var textSize

    var body: some View {
        let big = textSize >= .xxxLarge   // about 135%, like Android's 130%
        AdaptiveRow(big: big) {
            VStack(alignment: .leading, spacing: 2) {
                Text(med.title).font(.body.weight(.semibold))
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
        } trailing: {
            Button {
                store.takeNow(med.id, day: day, at: day == Day.today ? nil : "12:00")
            } label: {
                Text(day == Day.today ? tr("Take now") : tr("Add")).frame(maxWidth: big ? .infinity : nil)
            }
            .buttonStyle(.bordered)
        }
        .swipeActions {
            if let last = doses.last {
                Button("Remove the last one") { store.unmark(last.id) }.tint(.gray)
            }
        }
    }

    private var detail: String {
        if doses.isEmpty { return Units.amount(med.amount, med.unit) + " · " + tr("when needed") }
        return doses.map { "\($0.at) (\(Units.amount($0.amount, med.unit)))" }.joined(separator: ", ")
    }
}

/// A list row: the main part with buttons or a status on the right; with the largest text sizes
/// the trailing part goes on its own line under it, so nothing is squeezed.
struct AdaptiveRow<Main: View, Trailing: View>: View {
    let big: Bool
    @ViewBuilder let main: () -> Main
    @ViewBuilder let trailing: () -> Trailing

    var body: some View {
        if big {
            VStack(alignment: .leading, spacing: 10) {
                main()
                HStack(spacing: 10) { trailing() }
            }
            .padding(.vertical, 4)
        } else {
            HStack(spacing: 10) {
                main()
                Spacer(minLength: 4)
                trailing()
            }
        }
    }
}
