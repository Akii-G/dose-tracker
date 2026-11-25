import SwiftUI
import UniformTypeIdentifiers

/// A period, then the report as a PDF or a CSV, shared with the app you choose (Mail, Files, print…).
struct ReportView: View {
    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var choice = 30
    @State private var from = Calendar.current.date(byAdding: .day, value: -29, to: Date()) ?? Date()
    @State private var until = Date()

    private let periods: [(Int, String)] = [(7, tr("Last 7 days")), (14, tr("Last 2 weeks")), (30, tr("Last 30 days")),
                                            (90, tr("Last 3 months")), (0, tr("Choose the dates"))]

    private var dates: (String, String) {
        if choice > 0 { return (Day.adding(-(choice - 1), to: Day.today), Day.today) }
        let a = Day.string(min(from, until)), b = Day.string(max(from, until))
        return (a, b)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("For each medicine: the doses taken, skipped and not taken, those taken when needed and the changes; then the doses day by day.")
                        .font(.callout).foregroundStyle(.secondary)
                }
                Section("Period") {
                    Picker("Period", selection: $choice) {
                        ForEach(periods, id: \.0) { Text(verbatim: $0.1).tag($0.0) }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                    if choice == 0 {
                        DatePicker("From", selection: $from, in: ...Date(), displayedComponents: .date)
                        DatePicker("Until", selection: $until, in: ...Date(), displayedComponents: .date)
                    }
                }
                Section {
                    let (start, end) = dates
                    ShareLink(item: ReportFile(kind: "pdf", start: start, end: end, store: store),
                              preview: SharePreview(tr("Medicines report"))) {
                        Label("Share as PDF", systemImage: "doc.richtext")
                    }
                    ShareLink(item: ReportFile(kind: "csv", start: start, end: end, store: store),
                              preview: SharePreview(tr("Medicines report"))) {
                        Label("Share as CSV (spreadsheet)", systemImage: "tablecells")
                    }
                } footer: {
                    Text("Your iPhone asks where it goes: Mail, Files, a printer…")
                }
            }
            .navigationTitle("Report for your doctor")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        }
    }
}

/// The report, made when it's shared, as a file with a clear name.
struct ReportFile: Transferable {
    let kind: String, start: String, end: String
    let store: AppStore

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .data) { file in
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("\(tr("Dose-report"))-\(file.start)-\(file.end).\(file.kind)")
            let data = await MainActor.run {
                file.kind == "pdf" ? Report.pdf(file.store, from: file.start, to: file.end)
                                   : Report.csv(file.store, from: file.start, to: file.end)
            }
            try data.write(to: url, options: .atomic)
            return SentTransferredFile(url)
        }
    }
}

/// A backup to save in Files: the same as dose.json, marked as a backup.
struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    let data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}
