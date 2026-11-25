import SwiftUI
import VisionKit

/// Points the camera at the box and reads its barcode: the AIC number in Code 39 / Code 32 or in
/// the DataMatrix. VisionKit does the reading on the phone; `onCode` gets the 9-digit AIC.
struct ScanView: View {
    let onCode: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if DataScannerViewController.isSupported && DataScannerViewController.isAvailable {
                    ScannerRepresentable { code in onCode(code); dismiss() }
                        .ignoresSafeArea()
                        .overlay(alignment: .bottom) {
                            Text("Point the camera at the barcode on the box (the one under \"A0…\", or the square code).")
                                .font(.callout).multilineTextAlignment(.center)
                                .padding().background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
                                .padding()
                        }
                } else {
                    ContentUnavailableView("Can't use the camera",
                                           systemImage: "camera.metering.unknown",
                                           description: Text("Allow Dose to use the camera in Settings → Dose, or type the AIC number printed on the box."))
                }
            }
            .navigationTitle("Scan the box")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }
}

private struct ScannerRepresentable: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let vc = DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.code39, .code39Checksum, .dataMatrix])],
                                           qualityLevel: .accurate, recognizesMultipleItems: false,
                                           isHighFrameRateTrackingEnabled: false, isHighlightingEnabled: true)
        vc.delegate = context.coordinator
        try? vc.startScanning()
        return vc
    }

    func updateUIViewController(_ vc: DataScannerViewController, context: Context) {}

    static func dismantleUIViewController(_ vc: DataScannerViewController, coordinator: Coordinator) {
        vc.stopScanning()
    }

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onCode: (String) -> Void
        var done = false
        init(onCode: @escaping (String) -> Void) { self.onCode = onCode }

        func dataScanner(_ scanner: DataScannerViewController, didAdd items: [RecognizedItem], allItems: [RecognizedItem]) {
            guard !done else { return }
            for item in items {
                if case .barcode(let code) = item, let text = code.payloadStringValue, let aic = Aic.fromBarcode(text) {
                    done = true
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                    onCode(aic)
                    return
                }
            }
        }
    }
}
