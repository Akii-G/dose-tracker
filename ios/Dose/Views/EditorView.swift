import SwiftUI

/// Add or change a medicine: search the list, then the dose, the schedule and the stock.
struct EditorView: View {
    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let original: Medicine?

    @State private var query = ""
    @State private var name = ""
    @State private var strength = ""
    @State private var form = ""
    @State private var ingredient = ""
    @State private var note = ""
    @State private var amount = 1.0
    @State private var unit = "pill"
    @State private var kind = "daily"
    @State private var times: [TimeItem] = []
    @State private var weekdays: Set<Int> = [1, 2, 3, 4, 5, 6, 7]
    @State private var every = 2
    @State private var start = Date()
    @State private var hasEnd = false
    @State private var end = Date()
    @State private var counted = false
    @State private var count = 30.0
    @State private var refillDays = 7
    @State private var askDelete = false
    @State private var results: [MedicineList.Item] = []

    init(original: Medicine?) {
        self.original = original
        let m = original ?? Medicine(name: "")
        _name = State(initialValue: m.name)
        _strength = State(initialValue: m.strength)
        _form = State(initialValue: m.form)
        _ingredient = State(initialValue: m.ingredient)
        _note = State(initialValue: m.note)
        _amount = State(initialValue: m.amount)
        _unit = State(initialValue: m.unit)
        _kind = State(initialValue: m.schedule.kind)
        let day = Day.today
        _times = State(initialValue: (m.schedule.times.isEmpty ? ["08:00"] : m.schedule.times)
            .compactMap { Day.time($0, on: day) }.map { TimeItem(date: $0) })
        // all seven ticked is "every day": start with none, so tapping Friday means only Friday
        _weekdays = State(initialValue: m.schedule.kind == "weekdays" && m.schedule.weekdays.count < 7 ? Set(m.schedule.weekdays) : [])
        _every = State(initialValue: max(m.schedule.every, 2))
        _start = State(initialValue: Day.date(m.schedule.start) ?? Date())
        _hasEnd = State(initialValue: !m.schedule.end.isEmpty)
        _end = State(initialValue: Day.date(m.schedule.end) ?? Date().addingTimeInterval(6 * 86_400))
        _counted = State(initialValue: m.stock != nil)
        _refillDays = State(initialValue: m.refillDays)
    }

    var body: some View {
        NavigationStack {
            Form {
                if original == nil {
                    Section {
                        TextField("Search by name or active ingredient", text: $query)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .onChange(of: query) { _, q in results = MedicineList.search(q) }
                        ForEach(results, id: \.self) { item in
                            Button { pick(item) } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(item.strength.isEmpty ? item.name : "\(item.name) \(item.strength)")
                                        .foregroundStyle(.primary)
                                    Text([item.form, item.ingredient].filter { !$0.isEmpty }.joined(separator: " · "))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    } header: {
                        Text("Medicine")
                    } footer: {
                        Text("Not in the list? Just type the name below.")
                    }
                }
                Section {
                    TextField("Name", text: $name)
                    TextField("Strength, e.g. 50 mg", text: $strength)
                    TextField("Form, e.g. tablets", text: $form)
                    TextField("Note, e.g. with food", text: $note)
                }
                Section("Each time") {
                    Stepper(value: $amount, in: 0.25...1000, step: unit == "drop" || unit == "ml" ? 1 : 0.25) {
                        Text(Units.amount(amount, unit))
                    }
                    Picker("Unit", selection: $unit) {
                        ForEach(Units.keys, id: \.self) { u in Text(Units.name(u, plural: true)).tag(u) }
                    }
                }
                Section("When") {
                    Picker("How often", selection: $kind) {
                        ForEach(Kinds.all, id: \.key) { k in Text(k.label).tag(k.key) }
                    }
                    if kind == "weekdays" {
                        Text(weekdays.isEmpty ? tr("Tap the days you take it.")
                             : weekdays.sorted().map { Day.weekdayShort[$0 - 1] }.joined(separator: ", "))
                            .font(.caption).foregroundStyle(.secondary)
                        HStack(spacing: 6) {
                            ForEach(1...7, id: \.self) { d in
                                let on = weekdays.contains(d)
                                Button {
                                    if on { weekdays.remove(d) } else { weekdays.insert(d) }
                                } label: {
                                    Text(String(Day.weekdayShort[d - 1].prefix(2)))
                                        .font(.subheadline.weight(on ? .semibold : .regular))
                                        .frame(maxWidth: .infinity, minHeight: 34)
                                        .background(on ? Palette.accent.opacity(0.18) : Color.primary.opacity(0.06), in: Capsule())
                                        .foregroundStyle(on ? Palette.accent : .primary)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    if kind == "every" {
                        Stepper(value: $every, in: 2...365) { Text(tr("Every %@ days", String(every))) }
                    }
                    if kind != "needed" {
                        ForEach($times) { $item in
                            HStack {
                                DatePicker("Time", selection: $item.date, displayedComponents: .hourAndMinute)
                                if times.count > 1 {
                                    Button { times.removeAll { $0.id == item.id } } label: { Image(systemName: "minus.circle.fill") }
                                        .buttonStyle(.borderless)
                                        .foregroundStyle(.red)
                                }
                            }
                        }
                        Button("Add a time") {
                            times.append(TimeItem(date: Day.time("20:00", on: Day.today) ?? Date()))
                        }
                    }
                    DatePicker("From", selection: $start, displayedComponents: .date)
                    Toggle("Until a date (a course, like an antibiotic)", isOn: $hasEnd)
                    if hasEnd {
                        DatePicker("Until", selection: $end, in: start..., displayedComponents: .date)
                    }
                }
                Section {
                    Toggle("Count what's left", isOn: $counted)
                    if counted {
                        Stepper(value: $count, in: 0...10_000, step: 1) {
                            Text(tr("Now: %@", String(format: "%g", count)))
                        }
                        Stepper(value: $refillDays, in: 0...90) {
                            Text(tr("Tell me when it lasts fewer than %@ days", String(refillDays)))
                        }
                    }
                } header: {
                    Text("Stock")
                } footer: {
                    Text("Each dose you take is subtracted. Change the number when you buy more.")
                }
                if let original {
                    Section {
                        Button(original.archived ? tr("Start taking again") : tr("Stopped taking it")) {
                            store.setArchived(original.id, !original.archived)
                            dismiss()
                        }
                        Button("Delete", role: .destructive) { askDelete = true }
                    } footer: {
                        Text("Stopped medicines keep their history. Delete removes the medicine and every dose you marked.")
                    }
                }
            }
            .navigationTitle(original == nil ? tr("Add a medicine") : original!.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || (kind == "weekdays" && weekdays.isEmpty))
                }
            }
            .confirmationDialog(tr("Delete %@ and every dose you marked for it? This can't be undone.", original?.name ?? ""),
                                isPresented: $askDelete, titleVisibility: .visible) {
                Button("Delete", role: .destructive) {
                    if let original { store.delete(original.id) }
                    dismiss()
                }
            }
            .onAppear {
                if let original, let left = store.remaining(original) { count = left }
            }
        }
    }

    private func pick(_ item: MedicineList.Item) {
        name = item.name
        strength = item.strength
        form = item.form
        ingredient = item.ingredient
        unit = MedicineList.unit(for: item.form)
        query = ""
        results = []
    }

    private func save() {
        var m = original ?? Medicine(name: name)
        m.name = name.trimmingCharacters(in: .whitespaces)
        m.strength = strength.trimmingCharacters(in: .whitespaces)
        m.form = form.trimmingCharacters(in: .whitespaces)
        m.ingredient = ingredient
        m.note = note.trimmingCharacters(in: .whitespaces)
        m.amount = amount
        m.unit = unit
        var s = Schedule()
        s.kind = kind
        s.times = times.map { Day.timeString($0.date) }
        s.weekdays = Array(weekdays).sorted()
        s.every = every
        s.start = Day.string(start)
        s.end = hasEnd ? Day.string(max(end, start)) : ""
        m.schedule = s.cleaned
        if counted {
            let left = original.flatMap { store.remaining($0) }
            if let old = original?.stock, let left, abs(left - count) < 0.001 {
                m.stock = old           // unchanged: keep counting from when it was set
            } else {
                m.stock = Stock(count: count, set: Stamp.now())
            }
        } else {
            m.stock = nil
        }
        m.refillDays = refillDays
        store.put(m)
        if original == nil { Reminders.ask() }
        dismiss()
    }
}

struct TimeItem: Identifiable {
    let id = UUID()
    var date: Date
}
