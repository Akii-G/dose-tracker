import SwiftUI
import UserNotifications

@main
struct DoseApp: App {
    @State private var store = AppStore.shared
    @State private var sync = SyncCoordinator()
    @Environment(\.scenePhase) private var phase

    init() {
        UserDefaults.standard.register(defaults: ["autoSync": true, "followUp": true, "discreet": false])
        // set before launch finishes, so the buttons in a notification work when Dose wasn't running
        UNUserNotificationCenter.current().delegate = NotificationHandler.shared
        Reminders.register()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(store)
                .environment(sync)
                .tint(Palette.accent)
                .onAppear {
                    let coordinator = sync
                    store.onChange = { [weak store] in
                        guard let store else { return }
                        coordinator.syncSoon(store, delay: 2)
                        Reminders.plan(store)
                    }
                    coordinator.syncSoon(store, delay: 0)
                    Reminders.plan(store)
                }
                .onChange(of: phase) { _, newPhase in
                    if newPhase == .active {
                        store.load()      // a "Taken" from a notification may have changed the file
                        sync.syncSoon(store, delay: 0)
                        Reminders.plan(store)
                    }
                }
        }
    }
}

enum Palette {
    // the same colours as Sottovoce: its icon's violet, lavender, and the soft red of "Bad"
    static let accent = Color(red: 0x5c / 255, green: 0x56 / 255, blue: 0xb4 / 255)
    static let taken = Color(red: 0x6f / 255, green: 0x67 / 255, blue: 0xc9 / 255)
    static let some = Color(red: 0xc9 / 255, green: 0xc4 / 255, blue: 0xf2 / 255)
    static let none = Color(red: 0xeb / 255, green: 0x9a / 255, blue: 0x94 / 255)
    static let warning = Color(red: 0xcf / 255, green: 0x3f / 255, blue: 0x3f / 255)
}

struct ContentView: View {
    @Environment(AppStore.self) private var store

    var body: some View {
        TabView {
            TodayView().tabItem { Label("Today", systemImage: "checklist") }
            MedicinesView().tabItem { Label("Medicines", systemImage: "pills") }
            HistoryView().tabItem { Label("History", systemImage: "calendar") }
            SettingsView().tabItem { Label("Settings", systemImage: "gearshape") }
        }
    }
}
