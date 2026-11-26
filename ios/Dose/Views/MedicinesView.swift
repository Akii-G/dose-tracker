import SwiftUI

struct MedicinesView: View {
    @Environment(\.dynamicTypeSize) private var textSize
    @Environment(AppStore.self) private var store
    @State private var adding = false
    @State private var editing: Medicine?

    var body: some View {
        NavigationStack {
            List {
                if store.active.isEmpty {
                    Text("No medicines yet.").foregroundStyle(.secondary)
                } else {
                    Section {
                        ForEach(store.active) { med in row(med) }
                    }
                }
                if !store.stopped.isEmpty {
                    Section("No longer taken") {
                        ForEach(store.stopped) { med in row(med) }
                    }
                }
                Section {
                } footer: {
                    Text("Medicine names: AIFA open data (CC BY 4.0). Dose doesn't give medical advice: follow what your doctor prescribed.")
                }
            }
            .navigationTitle("Medicines")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { adding = true } label: { Label("Add a medicine", systemImage: "plus") }
                }
            }
            .sheet(isPresented: $adding) { EditorView(original: nil) }
            .sheet(item: $editing) { med in EditorView(original: med) }
        }
    }

    private func row(_ med: Medicine) -> some View {
        // with the largest text sizes the stock goes under the name instead of beside it
        let layout = textSize >= .xxxLarge ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4)) : AnyLayout(HStackLayout())
        return Button { editing = med } label: {
            layout {
                UnitBadge(unit: med.unit)
                VStack(alignment: .leading, spacing: 3) {
                    // Color.primary, not .primary: in a button .primary is the button's colour (the accent)
                    Text(med.title).font(.body.weight(.semibold)).foregroundStyle(Color.primary)
                    Text(Describe.line(med.amount, med.unit, med.schedule))
                        .font(.caption).foregroundStyle(Color.secondary)
                }
                if textSize < .xxxLarge { Spacer() }
                if let left = store.remaining(med) {
                    VStack(alignment: textSize >= .xxxLarge ? .leading : .trailing, spacing: 2) {
                        Text(tr("%@ left", String(format: "%g", left)))
                        if let days = store.daysLeft(med) {
                            Text(trn("%@ day", "%@ days", days))
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(store.isLow(med) ? Palette.warning : Color.secondary)
                }
            }
        }
    }
}
