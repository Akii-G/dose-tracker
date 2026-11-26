import SwiftUI
import UserNotifications

@main
struct DoseApp: App {
    @State private var store = AppStore.shared
    @State private var sync = SyncCoordinator()
    @Environment(\.scenePhase) private var phase
    @AppStorage("tone") private var tone = "violet"

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
                .id(tone)                     // a new colour tone redraws everything in it
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

/// A colour tone: accent, accent at night, "all taken", "some taken". The PC and Android have the same.
struct Tone {
    let key: String, name: String
    let accent: UInt32, night: UInt32, taken: UInt32, some: UInt32

    static let all = [
        Tone(key: "violet", name: "Violet", accent: 0x5c56b4, night: 0x8e86d8, taken: 0x6f67c9, some: 0xc9c4f2),
        Tone(key: "blue", name: "Blue", accent: 0x2f6fcf, night: 0x6fa3ee, taken: 0x3f7fe0, some: 0xc3d8f7),
        Tone(key: "teal", name: "Teal", accent: 0x1f7f7c, night: 0x4fc2bd, taken: 0x2a9a96, some: 0xbfe6e3),
        Tone(key: "green", name: "Green", accent: 0x3a7f47, night: 0x6cc47c, taken: 0x48a058, some: 0xcbe9cf),
        Tone(key: "rose", name: "Rose", accent: 0xb0466f, night: 0xe48ab0, taken: 0xc85a8c, some: 0xf3cadb),
        Tone(key: "amber", name: "Amber", accent: 0xa8681a, night: 0xe8a64a, taken: 0xcc8526, some: 0xf5dcb3),
    ]
    /// The tone's name, translated.
    var label: String {
        switch key {
        case "blue": return tr("Blue")
        case "teal": return tr("Teal")
        case "green": return tr("Green")
        case "rose": return tr("Rose")
        case "amber": return tr("Amber")
        default: return tr("Violet")
        }
    }

    static var current: Tone {
        let key = UserDefaults.standard.string(forKey: "tone") ?? "violet"
        return all.first { $0.key == key } ?? all[0]
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xff) / 255, green: Double((hex >> 8) & 0xff) / 255, blue: Double(hex & 0xff) / 255)
    }
}

enum Palette {
    /// The chosen tone (violet, like Sottovoce, by default), lighter at night.
    static var accent: Color {
        let t = Tone.current
        return Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(Color(hex: t.night)) : UIColor(Color(hex: t.accent)) })
    }
    static var taken: Color { Color(hex: Tone.current.taken) }
    static var some: Color { Color(hex: Tone.current.some) }
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
