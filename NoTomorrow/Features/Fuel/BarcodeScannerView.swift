import SwiftUI
import VisionKit
import Vision
import UIKit

/// Rear-camera barcode scanner. Reads EAN-13 / EAN-8 / UPC-E, GS1 DataBar (produce and fresh-food stickers), and QR /
/// DataMatrix only when they carry a GTIN (GS1 Digital Link or element string), so a promo QR on the same pack is
/// ignored. Calls `onCode` once with the first product code (`GTINExtractor`). On the simulator or any device VisionKit
/// cannot scan on, falls back to a manual code field.
struct BarcodeScannerView: View {
    var onCode: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var fired = false
    @State private var manual = false

    private var scannerUsable: Bool {
        DataScannerViewController.isSupported && DataScannerViewController.isAvailable
    }

    var body: some View {
        ZStack {
            if scannerUsable && !manual {
                DataScannerRepresentable(onCode: deliver, onUnavailable: { manual = true })
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
            Button("fuel.scan.manual") { manual = true }.padding().background(.ultraThinMaterial, in: Capsule())
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
    var onUnavailable: () -> Void

    static let symbologies: [VNBarcodeSymbology] = [
        .ean13, .ean8, .upce, .gs1DataBar, .gs1DataBarExpanded, .gs1DataBarLimited, .qr, .dataMatrix,
    ]

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode, onUnavailable: onUnavailable) }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let controller = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: Self.symbologies)],
            qualityLevel: .balanced,
            // Several codes at once, so a promo QR recognised first cannot hold the scanner while the EAN waits.
            recognizesMultipleItems: true,
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
        do { try controller.startScanning() } catch { DispatchQueue.main.async { context.coordinator.onUnavailable() } }
    }

    static func dismantleUIViewController(_ controller: DataScannerViewController, coordinator: Coordinator) {
        controller.stopScanning()
    }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onCode: (String) -> Void
        let onUnavailable: () -> Void
        weak var controller: DataScannerViewController?
        var finished = false

        init(onCode: @escaping (String) -> Void, onUnavailable: @escaping () -> Void) { self.onCode = onCode; self.onUnavailable = onUnavailable }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            deliverFirstProductCode(in: allItems, from: dataScanner)
        }

        func dataScanner(_ dataScanner: DataScannerViewController, didUpdate updatedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            deliverFirstProductCode(in: allItems, from: dataScanner)
        }

        /// Fires for the first item that carries a product code; anything else (promo QR, misread) keeps scanning.
        private func deliverFirstProductCode(in items: [RecognizedItem], from dataScanner: DataScannerViewController) {
            guard !finished else { return }
            for item in items {
                guard case .barcode(let barcode) = item, let payload = barcode.payloadStringValue,
                      let code = GTINExtractor.gtin(payload: payload, symbology: .init(barcode.observation.symbology))
                else { continue }
                finished = true
                dataScanner.stopScanning()
                onCode(code)
                return
            }
        }

        func dataScanner(_ dataScanner: DataScannerViewController, becameUnavailableWithError error: DataScannerViewController.ScanningUnavailable) {
            finished = true
            onUnavailable()
        }
    }
}

// MARK: - Manual fallback (simulator, no camera)

private struct ManualBarcodeEntry: View {
    var onCode: (String) -> Void
    var onCancel: () -> Void

    @State private var code = ""
    @FocusState private var focused: Bool

    private var digits: String { code.filter(\.isASCIIDigit) }
    /// The code to look up: a GTIN whose check digit is right, or a UPC-E expanded. Nil while it cannot be a real code.
    private var validCode: String? { GTINExtractor.manual(digits) }
    /// A complete-looking code whose check digit is wrong: most likely a typo.
    private var showsCheckDigits: Bool { [8, 12, 13, 14].contains(digits.count) && validCode == nil }

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
                        .onSubmit { if let validCode { onCode(validCode) } }
                    if showsCheckDigits {
                        Text("fuel.scan.checkDigits").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ember)
                    }
                }
            }

            PrimaryButton(title: "fuel.scan.useCode", isEnabled: validCode != nil) { if let validCode { onCode(validCode) } }
            Spacer()
        }
        .padding(.horizontal, NT.Spacing.screenH)
        .padding(.top, 8)
        .onAppear { focused = true }
    }
}

private extension GTINExtractor.Symbology {
    init(_ symbology: VNBarcodeSymbology) {
        switch symbology {
        case .ean13: self = .ean13
        case .ean8: self = .ean8
        case .upce: self = .upce
        case .gs1DataBar, .gs1DataBarExpanded, .gs1DataBarLimited: self = .gs1DataBar
        case .qr: self = .qr
        case .dataMatrix: self = .dataMatrix
        default: self = .other
        }
    }
}
