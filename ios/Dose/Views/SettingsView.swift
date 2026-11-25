import SwiftUI
import UserNotifications

struct SettingsView: View {
    @Environment(AppStore.self) private var store
    @Environment(SyncCoordinator.self) private var sync
    @AppStorage("server") private var server = ""
    @AppStorage("token") private var token = ""
    @AppStorage("autoSync") private var autoSync = true
    @AppStorage("followUp") private var followUp = true
    @AppStorage("discreet") private var discreet = false
    @State private var testing = ""
    @State private var allowed: UNAuthorizationStatus = .notDetermined

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Address, e.g. pop-os.local:8766", text: $server)
                        .keyboardType(.URL)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    TextField("Token", text: $token)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .font(.body.monospaced())
                    Toggle("Sync automatically", isOn: $autoSync)
                    Button("Test the connection") {
                        testing = tr("Connecting…")
                        Task {
                            do { testing = try await SyncClient.ping() } catch { testing = error.localizedDescription }
                        }
                    }
                    Button("Sync now") { Task { await sync.run(store) } }
                        .disabled(sync.busy || !SyncClient.isConfigured)
                    if !testing.isEmpty { Text(testing).font(.footnote).foregroundStyle(.secondary) }
                    Text(sync.status).font(.footnote).foregroundStyle(.secondary)
                } header: {
                    Text("Sync with your PC")
                } footer: {
                    Text("On the PC: Dose → ☰ → Phone sync. Switch it on, then copy the address and the token here. Everything is encrypted with the token and stays on your home Wi-Fi.")
                }
                Section {
                    if allowed == .denied {
                        Text("Notifications are off for Dose. Turn them on in the iPhone's Settings → Notifications → Dose.")
                            .foregroundStyle(Palette.warning)
                    } else if allowed == .notDetermined {
                        Button("Allow reminders") { Reminders.ask() }
                    }
                    Toggle("Remind me again after 30 minutes", isOn: $followUp)
                    Toggle("Discreet notifications", isOn: $discreet)
                } header: {
                    Text("Reminders")
                } footer: {
                    Text("Discreet notifications say only \"Time for your 08:00 dose\", without the medicine's name.")
                }
                Section {
                    LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")
                    Link("Dose on GitHub", destination: URL(string: "https://github.com/Akii-G/dose-tracker")!)
                } header: {
                    Text("About")
                } footer: {
                    Text("Your medicines, quietly. The sister app of Sottovoce. Medicine names: AIFA open data (CC BY 4.0). Dose doesn't give medical advice: follow what your doctor prescribed.")
                }
            }
            .navigationTitle("Settings")
            .onChange(of: followUp) { _, _ in Reminders.plan(store) }
            .onChange(of: discreet) { _, _ in Reminders.plan(store) }
            .task {
                allowed = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
            }
        }
    }
}
