import SwiftUI

struct MedicinesView: View {
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
        Button { editing = med } label: {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(med.title).font(.body.weight(.semibold)).foregroundStyle(.primary)
                    Text(Units.amount(med.amount, med.unit) + " · " + Describe.schedule(med.schedule))
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                if let left = store.remaining(med) {
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(tr("%@ left", String(format: "%g", left)))
                        if let days = store.daysLeft(med) {
                            Text(trn("%@ day", "%@ days", days))
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(store.isLow(med) ? Palette.warning : .secondary)
                }
            }
        }
    }
}
