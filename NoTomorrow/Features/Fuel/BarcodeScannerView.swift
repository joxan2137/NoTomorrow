import SwiftUI
import VisionKit
import UIKit

/// Rear-camera barcode scanner (EAN-13 / EAN-8 / UPC-E). Calls `onCode` once for the first recognised barcode.
/// On the simulator or any device VisionKit cannot scan on, falls back to a manual code field.
struct BarcodeScannerView: View {
    var onCode: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var fired = false

    private var scannerUsable: Bool {
        DataScannerViewController.isSupported && DataScannerViewController.isAvailable
    }

    var body: some View {
        ZStack {
            if scannerUsable {
                DataScannerRepresentable(onCode: deliver)
                    .ignoresSafeArea()
                cameraChrome
            } else {
                ManualBarcodeEntry(onCode: deliver, onCancel: { dismiss() })
            }
        }
        .ntScreenBackground()
    }

    /// Guidance line at the top, Cancel at the bottom; the camera view fills the rest.
    private var cameraChrome: some View {
        VStack {
            Text("fuel.scan.hint")
                .font(NT.Fonts.subheadline)
                .foregroundStyle(NT.Colors.ink)
                .padding(.horizontal, 14)
                .frame(height: 36)
                .background(NT.Colors.ground.opacity(0.85), in: Capsule())
                .padding(.top, 12)
            Spacer()
            Button { dismiss() } label: {
                Text("common.cancel")
                    .font(NT.Fonts.headline)
                    .foregroundStyle(NT.Colors.ink)
                    .frame(maxWidth: .infinity)
                    .frame(height: NT.Size.primaryButton)
                    .background(NT.Colors.surface2.opacity(0.9), in: Capsule())
            }
            .buttonStyle(PressScale())
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.bottom, 12)
        }
    }

    private func deliver(_ code: String) {
        guard !fired else { return }
        fired = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        onCode(code)
        dismiss()
    }
}

// MARK: - VisionKit wrapper

private struct DataScannerRepresentable: UIViewControllerRepresentable {
    var onCode: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let controller = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.ean13, .ean8, .upce])],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isPinchToZoomEnabled: true,
            isGuidanceEnabled: true,
            isHighlightingEnabled: true
        )
        controller.delegate = context.coordinator
        context.coordinator.controller = controller
        return controller
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {
        // The view must be in a window before scanning can start; this runs after the first layout pass.
        guard !controller.isScanning, !context.coordinator.finished else { return }
        try? controller.startScanning()
    }

    static func dismantleUIViewController(_ controller: DataScannerViewController, coordinator: Coordinator) {
        controller.stopScanning()
    }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onCode: (String) -> Void
        weak var controller: DataScannerViewController?
        var finished = false

        init(onCode: @escaping (String) -> Void) { self.onCode = onCode }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            guard !finished else { return }
            for item in addedItems {
                if case .barcode(let barcode) = item, let payload = barcode.payloadStringValue, !payload.isEmpty {
                    finished = true
                    dataScanner.stopScanning()
                    onCode(payload)
                    return
                }
            }
        }

        func dataScanner(_ dataScanner: DataScannerViewController, becameUnavailableWithError error: DataScannerViewController.ScanningUnavailable) {
            finished = true
        }
    }
}

// MARK: - Manual fallback (simulator, no camera)

private struct ManualBarcodeEntry: View {
    var onCode: (String) -> Void
    var onCancel: () -> Void

    @State private var code = ""
    @FocusState private var focused: Bool

    private var digits: String { code.filter(\.isNumber) }
    private var isValid: Bool { (8...14).contains(digits.count) }

    var body: some View {
        VStack(alignment: .leading, spacing: NT.Spacing.section) {
            HStack(spacing: 12) {
                Button(action: onCancel) {
                    Image(systemName: "arrow.left")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(NT.Colors.ink)
                        .frame(width: NT.Size.control, height: NT.Size.control)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                Text("fuel.scan.title").font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink)
                Spacer()
            }
            .frame(height: NT.Size.control)

            Text("fuel.scan.unavailable")
                .font(NT.Fonts.subheadline)
                .foregroundStyle(NT.Colors.ink2)

            NTCard {
                VStack(alignment: .leading, spacing: 12) {
                    Text("fuel.barcode").eyebrow()
                    TextField("fuel.scan.codePlaceholder", text: $code)
                        .keyboardType(.numberPad)
                        .textContentType(.oneTimeCode)
                        .font(NT.Fonts.title2)
                        .foregroundStyle(NT.Colors.ink)
                        .tabular()
                        .focused($focused)
                        .padding(.horizontal, 14)
                        .frame(height: NT.Size.control + 4)
                        .background(NT.Colors.surface2, in: RoundedRectangle(cornerRadius: NT.Radius.field, style: .continuous))
                        .submitLabel(.done)
                        .onSubmit { if isValid { onCode(digits) } }
                }
            }

            PrimaryButton(title: "fuel.scan.useCode", isEnabled: isValid) { onCode(digits) }
            Spacer()
        }
        .padding(.horizontal, NT.Spacing.screenH)
        .padding(.top, 8)
        .onAppear { focused = true }
    }
}
