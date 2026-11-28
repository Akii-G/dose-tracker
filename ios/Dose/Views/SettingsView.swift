import SwiftUI
import UniformTypeIdentifiers
import UserNotifications

struct SettingsView: View {
    @Environment(AppStore.self) private var store
    @Environment(SyncCoordinator.self) private var sync
    @AppStorage("server") private var server = ""
    @AppStorage("token") private var token = ""
    @AppStorage("autoSync") private var autoSync = true
    @AppStorage("followUp") private var followUp = true
    @AppStorage("discreet") private var discreet = false
    @AppStorage("priority") private var priority = "normal"
    @AppStorage("sound") private var sound = "default"
    @AppStorage("tone") private var tone = "violet"
    @State private var testing = ""
    @State private var allowed: UNAuthorizationStatus = .notDetermined
    @State private var reporting = false
    @State private var exporting = false
    @State private var importing = false
    @State private var dataNote = ""
    @State private var shareNote = ""

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
                    Picker("Priority", selection: $priority) {
                        Text("Time Sensitive").tag("timeSensitive")
                        Text("Normal").tag("normal")
                        Text("Quiet").tag("quiet")
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                } header: {
                    Text("Priority")
                } footer: {
                    switch priority {
                    case "timeSensitive":
                        Text("Can reach you during a Focus and stays on the Lock Screen for an hour, if your iPhone allows it for Dose (Settings → Notifications → Dose → Time Sensitive).")
                    case "quiet":
                        Text("No sound and no banner: it waits in Notification Centre.")
                    default:
                        Text("A banner with a sound.")
                    }
                }
                if priority != "quiet" {
                    Section {
                        Picker("Sound", selection: $sound) {
                            Text("The iPhone's notification sound").tag("default")
                            Text("Chime").tag("chime")
                            Text("Bell").tag("bell")
                            Text("Soft").tag("soft")
                            Text("No sound").tag("none")
                        }
                        .pickerStyle(.inline)
                        .labelsHidden()
                    } header: {
                        Text("Sound")
                    } footer: {
                        Text("Tap a sound to hear it.")
                    }
                }
                Section {
                    HStack {
                        ForEach(Tone.all, id: \.key) { t in
                            Button { tone = t.key } label: {
                                Circle().fill(Color(hex: t.accent))
                                    .frame(width: 34, height: 34)
                                    .padding(4)
                                    .overlay(Circle().stroke(Color.primary, lineWidth: tone == t.key ? 3 : 0))
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Text(t.label))
                            .accessibilityAddTraits(tone == t.key ? .isSelected : [])
                            if t.key != Tone.all.last?.key { Spacer(minLength: 0) }
                        }
                    }
                    .padding(.vertical, 4)
                } header: {
                    Text("Colour")
                } footer: {
                    Text(Tone.current.label)
                }
                Section {
                    Button("Report for your doctor") { reporting = true }
                    Button("Back up") { exporting = true }
                    Button("Restore from a backup") { importing = true }
                } header: {
                    Text("Your data")
                } footer: {
                    Text(dataNote.isEmpty ? tr("A backup is one file with all your medicines and doses. Restoring it on a new phone brings them back.") : dataNote)
                }
                Section {
                    Button("Send to Sottovoce now") {
                        shareNote = SottovoceShare.send(store) ? "" : tr("Couldn't prepare the data.")
                    }
                    .disabled(!SottovoceShare.sottovoceInstalled)
                    if !SottovoceShare.sottovoceInstalled {
                        Text("Sottovoce isn't on this iPhone, or it's older than 2.5.1.").font(.footnote).foregroundStyle(.secondary)
                    } else if let sent = SottovoceShare.lastSent {
                        Text(tr("Last sent: %@", sent.formatted(date: .abbreviated, time: .shortened)))
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    if !shareNote.isEmpty { Text(shareNote).font(.footnote).foregroundStyle(.red) }
                } header: {
                    Text("Sottovoce on this iPhone")
                } footer: {
                    Text("Without a PC: Dose sends your medicines and doses straight to Sottovoce on this iPhone, through a link between the two apps. Usually you don't need this button: in Sottovoce, Update from Dose asks Dose for them. Nothing goes on the internet.")
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
            .sheet(isPresented: $reporting) { ReportView() }
            .fileExporter(isPresented: $exporting, document: BackupDocument(data: Backup.data(store)), contentType: .json,
                          defaultFilename: "Dose-backup-\(Day.today)") { result in
                if case .success = result { dataNote = tr("Backed up. Keep the file somewhere safe, like your cloud or a computer.") }
            }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .data]) { result in
                guard case .success(let url) = result else { return }
                let open = url.startAccessingSecurityScopedResource()
                defer { if open { url.stopAccessingSecurityScopedResource() } }
                guard let data = try? Data(contentsOf: url), let n = Backup.restore(store, data) else {
                    dataNote = tr("This isn't a Dose backup."); return
                }
                dataNote = trn("Restored: %@ change.", "Restored: %@ changes.", n) + " "
                    + tr("What you had here stays; the backup only adds what's missing or newer.")
            }
            .onChange(of: followUp) { _, _ in Reminders.plan(store) }
            .onChange(of: discreet) { _, _ in Reminders.plan(store) }
            .onChange(of: priority) { _, _ in Reminders.plan(store) }
            .onChange(of: sound) { _, new in Reminders.preview(new); Reminders.plan(store) }
            .task {
                allowed = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
            }
        }
    }
}
