import SwiftUI
import PhotosUI
import VisionKit
import Vision
import UIKit

/// Full-screen machine scanner opened from the exercise picker (docs/machine-scan.md). The rear camera reads the text on
/// a machine's placard on device (VisionKit live text; Vision on a picked photo), `MachineLabelMatcher` turns it into
/// up to three exercises, and one tap adds the chosen one. Nothing leaves the phone. Where live scanning is not
/// available (simulator, older devices, camera denied) a photo from the library does the same job.
struct MachineScanView: View {
    var candidates: [Exercise]
    /// Already in the workout: never offered.
    var excluding: Set<String>
    var onAdd: (Exercise) -> Void
    /// "Search instead": the most prominent text read so far, for the picker's search field.
    var onSearch: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var matcher: MachineLabelMatcher?
    @State private var matches: [MachineLabelMatcher.Match] = []
    @State private var headline = ""
    @State private var liveUnavailable = false
    @State private var photo: PhotoState = .none
    @State private var showsLibrary = false
    @State private var libraryItem: PhotosPickerItem?
    @State private var fired = false

    enum PhotoState: Equatable { case none, reading, done }

    private var liveUsable: Bool {
        DataScannerViewController.isSupported && DataScannerViewController.isAvailable && !liveUnavailable
    }

    private var byID: [String: Exercise] {
        Dictionary(candidates.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    }

    var body: some View {
        ZStack {
            if liveUsable && photo == .none {
                LiveTextScanner(onLines: receive, onUnavailable: { liveUnavailable = true })
                    .ignoresSafeArea()
            }
            VStack(spacing: 0) {
                hint.padding(.top, 12)
                Spacer()
                panel
            }
        }
        .ntScreenBackground()
        .task { await buildMatcher() }
        .photosPicker(isPresented: $showsLibrary, selection: $libraryItem, matching: .images, photoLibrary: .shared())
        .onChange(of: libraryItem) { _, item in
            guard let item else { return }
            photo = .reading
            matches = []
            Task {
                let data = try? await item.loadTransferable(type: Data.self)
                var lines: [MachineLabelMatcher.Line] = []
                if let data { lines = await MachineLabelReader.lines(fromImageData: data) }
                await MainActor.run {
                    libraryItem = nil
                    receive(lines)
                    photo = .done
                }
            }
        }
    }

    // MARK: Chrome

    private var hint: some View {
        Text(liveUsable ? LocalizedStringKey("scan.machine.hint") : LocalizedStringKey("scan.machine.photoHint"))
            .font(NT.Fonts.subheadline)
            .foregroundStyle(NT.Colors.ink)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(NT.Colors.ground.opacity(0.85), in: Capsule())
            .padding(.horizontal, NT.Spacing.screenH)
    }

    private var panel: some View {
        VStack(alignment: .leading, spacing: 10) {
            if matches.isEmpty {
                Text(statusKey)
                    .font(NT.Fonts.subheadline)
                    .foregroundStyle(NT.Colors.ink2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 6)
            } else {
                Text("scan.machine.matches").eyebrow()
                ForEach(Array(matches.enumerated()), id: \.element.id) { index, match in
                    if let exercise = byID[match.id] {
                        MatchRow(exercise: exercise, isBest: index == 0) { add(exercise) }
                    }
                }
            }
            HStack(spacing: 10) {
                SecondaryButton(title: "scan.machine.photo", systemImage: "photo", height: NT.Size.control) {
                    showsLibrary = true
                }
                SecondaryButton(title: "scan.machine.search", systemImage: "magnifyingglass", height: NT.Size.control) {
                    onSearch(headline)
                    dismiss()
                }
            }
            Button { dismiss() } label: {
                Text("common.cancel")
                    .font(NT.Fonts.headline)
                    .foregroundStyle(NT.Colors.ink2)
                    .frame(maxWidth: .infinity)
                    .frame(height: NT.Size.control)
            }
            .buttonStyle(PressScale())
        }
        .padding(NT.Spacing.cardPadding)
        .background(NT.Colors.ground.opacity(0.94), in: RoundedRectangle(cornerRadius: NT.Radius.tile, style: .continuous))
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
        .animation(.easeOut(duration: 0.2), value: matches)
    }

    private var statusKey: LocalizedStringKey {
        switch photo {
        case .reading: "scan.machine.reading"
        case .done: "scan.machine.noMatch"
        case .none: liveUsable ? LocalizedStringKey("scan.machine.looking") : LocalizedStringKey("scan.machine.photoHint")
        }
    }

    // MARK: Matching

    private func buildMatcher() async {
        let list = candidates.map {
            MachineLabelMatcher.Candidate(id: $0.id, name: $0.name, namePL: $0.namePL, equipment: $0.equipment)
        }
        let built = await Task.detached(priority: .userInitiated) { MachineLabelMatcher(candidates: list) }.value
        matcher = built
    }

    /// Live frames keep the last matches on screen while the camera briefly loses the text, so the rows do not
    /// flicker away under a thumb; a photo always shows its own result.
    private func receive(_ lines: [MachineLabelMatcher.Line]) {
        guard let matcher, !fired else { return }
        if let top = lines.max(by: { $0.weight < $1.weight }) { headline = top.text }
        let found = matcher.match(lines, excluding: excluding)
        if !found.isEmpty || photo != .none {
            if found.first?.id != matches.first?.id, !found.isEmpty {
                UISelectionFeedbackGenerator().selectionChanged()
            }
            matches = found
        }
    }

    private func add(_ exercise: Exercise) {
        guard !fired else { return }
        fired = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        onAdd(exercise)
    }
}

// MARK: - Match row

private struct MatchRow: View {
    var exercise: Exercise
    var isBest: Bool
    var action: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(exercise.localizedName)
                    .font(isBest ? NT.Fonts.headline : NT.Fonts.subheadline)
                    .foregroundStyle(NT.Colors.ink)
                    .lineLimit(2)
                Text(WorkoutStrings.subtitle(for: exercise))
                    .font(NT.Fonts.footnote)
                    .foregroundStyle(NT.Colors.ink2)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            Button(action: action) {
                Text("scan.machine.add")
                    .font(NT.Fonts.headline)
                    .foregroundStyle(isBest ? NT.Colors.onPrimary : NT.Colors.ink)
                    .padding(.horizontal, 16)
                    .frame(height: 36)
                    .background(isBest ? NT.Colors.ink : NT.Colors.surface2, in: Capsule())
            }
            .buttonStyle(PressScale())
            .accessibilityLabel(Text(exercise.localizedName))
            .accessibilityHint(Text("scan.machine.add"))
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 60)
        .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.tile, style: .continuous))
    }
}

// MARK: - Text reading

/// Turns recognised text into `MachineLabelMatcher.Line`s: each line weighted by its height over the tallest one's,
/// so the machine's big name outweighs the fine print.
enum MachineLabelReader {

    static func weighted(_ raw: [(text: String, height: Double)]) -> [MachineLabelMatcher.Line] {
        let kept = raw.filter { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.height > 0 }
        guard let tallest = kept.map(\.height).max(), tallest > 0 else { return [] }
        return kept.map { MachineLabelMatcher.Line(text: $0.text, weight: $0.height / tallest) }
    }

    /// On-device Vision text recognition of a photo (accurate level, language correction on).
    static func lines(fromImageData data: Data) async -> [MachineLabelMatcher.Line] {
        await Task.detached(priority: .userInitiated) { () -> [MachineLabelMatcher.Line] in
            guard let image = UIImage(data: data), let cgImage = image.cgImage else { return [] }
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true
            let handler = VNImageRequestHandler(cgImage: cgImage, orientation: .init(image.imageOrientation))
            do { try handler.perform([request]) } catch { return [] }
            let raw: [(text: String, height: Double)] = (request.results ?? []).compactMap { observation in
                guard let text = observation.topCandidates(1).first?.string else { return nil }
                return (text, Double(observation.boundingBox.height))
            }
            return weighted(raw)
        }.value
    }
}

private extension CGImagePropertyOrientation {
    init(_ orientation: UIImage.Orientation) {
        switch orientation {
        case .up: self = .up
        case .upMirrored: self = .upMirrored
        case .down: self = .down
        case .downMirrored: self = .downMirrored
        case .left: self = .left
        case .leftMirrored: self = .leftMirrored
        case .right: self = .right
        case .rightMirrored: self = .rightMirrored
        @unknown default: self = .up
        }
    }
}

// MARK: - VisionKit live text

private struct LiveTextScanner: UIViewControllerRepresentable {
    var onLines: ([MachineLabelMatcher.Line]) -> Void
    var onUnavailable: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onLines: onLines, onUnavailable: onUnavailable) }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let controller = DataScannerViewController(
            recognizedDataTypes: [.text()],
            qualityLevel: .accurate,
            recognizesMultipleItems: true,
            isHighFrameRateTrackingEnabled: false,
            isPinchToZoomEnabled: true,
            isGuidanceEnabled: false,
            isHighlightingEnabled: true
        )
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {
        context.coordinator.onLines = onLines
        // The view must be in a window before scanning can start; this runs after the first layout pass.
        guard !controller.isScanning, !context.coordinator.stopped else { return }
        do { try controller.startScanning() } catch { DispatchQueue.main.async { context.coordinator.onUnavailable() } }
    }

    static func dismantleUIViewController(_ controller: DataScannerViewController, coordinator: Coordinator) {
        coordinator.stopped = true
        controller.stopScanning()
    }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        var onLines: ([MachineLabelMatcher.Line]) -> Void
        let onUnavailable: () -> Void
        var stopped = false
        private var lastDelivery = Date.distantPast
        /// Matching every tracking update would redo the work ~30 times a second for text that barely changed.
        private static let interval: TimeInterval = 0.3

        init(onLines: @escaping ([MachineLabelMatcher.Line]) -> Void, onUnavailable: @escaping () -> Void) {
            self.onLines = onLines
            self.onUnavailable = onUnavailable
        }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            deliver(allItems)
        }

        func dataScanner(_ dataScanner: DataScannerViewController, didUpdate updatedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            deliver(allItems)
        }

        func dataScanner(_ dataScanner: DataScannerViewController, becameUnavailableWithError error: DataScannerViewController.ScanningUnavailable) {
            stopped = true
            onUnavailable()
        }

        private func deliver(_ items: [RecognizedItem]) {
            let now = Date()
            guard !stopped, now.timeIntervalSince(lastDelivery) >= Self.interval else { return }
            lastDelivery = now
            let raw: [(text: String, height: Double)] = items.flatMap { item -> [(text: String, height: Double)] in
                guard case .text(let text) = item else { return [] }
                let b = text.bounds
                let height = Double(hypot(b.bottomLeft.x - b.topLeft.x, b.bottomLeft.y - b.topLeft.y))
                // A block of several lines is as tall as all of them; each line gets one line's height.
                let lines = text.transcript.split(whereSeparator: \.isNewline).map(String.init)
                return lines.map { (text: $0, height: height / Double(max(lines.count, 1))) }
            }
            onLines(MachineLabelReader.weighted(raw))
        }
    }
}
