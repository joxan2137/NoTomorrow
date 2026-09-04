import SwiftUI
import SwiftData

/// AI photo estimate for one meal slot. Pick a source → analysing → editable result → log.
/// Presented as a sheet / full-screen cover from the Fuel tab; dismisses itself after `onLogged`.
struct AIScanView: View {
    let initialMeal: MealSlot
    /// Day the entries are logged to (start of day is applied by `MealEntry`). Defaults to today.
    let day: Date
    var onLogged: () -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var modelContext
    @State private var model: AIScanModel

    init(meal: MealSlot, day: Date = .now, onLogged: @escaping () -> Void = {}) {
        self.initialMeal = meal
        self.day = day
        self.onLogged = onLogged
        _model = State(initialValue: AIScanModel(meal: meal))
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            content
        }
        .ntScreenBackground()
        .overlay(alignment: .bottom) { toastOverlay }
        .alert(
            Text(String(format: String(localized: "fuel.ai.consent.title"), model.providerName)),
            isPresented: Bindable(model).showConsent
        ) {
            Button("fuel.ai.consent.accept") { model.acceptConsent() }
            Button("common.cancel", role: .cancel) { model.declineConsent() }
        } message: {
            Text(String(format: String(localized: "fuel.ai.consent.body"), model.providerName))
        }
    }

    // MARK: Header

    private var header: some View {
        HStack(spacing: 12) {
            Button { dismiss() } label: {
                Image(systemName: "arrow.left")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(NT.Colors.ink)
                    .frame(width: NT.Size.control, height: NT.Size.control)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("common.back"))

            Text("fuel.ai.title")
                .font(NT.Fonts.title2)
                .foregroundStyle(NT.Colors.ink)
                .frame(maxWidth: .infinity, alignment: .leading)

            if showsRetake {
                Button { model.retake() } label: {
                    Text("fuel.ai.retake")
                        .font(NT.Fonts.body)
                        .foregroundStyle(NT.Colors.ink2)
                        .frame(height: NT.Size.control)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .frame(height: NT.Size.control)
        .padding(.horizontal, NT.Spacing.screenH)
    }

    private var showsRetake: Bool {
        switch model.phase {
        case .pickSource: false
        case .analyzing, .result, .failed: true
        }
    }

    // MARK: Phases

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .pickSource:
            if model.upload == .google, !AuthStore.shared.isSignedIn {
                AIScanSignedOutView(meal: model.meal) {}
            } else {
                AIScanSourceView(meal: model.meal) { model.handlePicked($0) }
            }
        case .analyzing:
            AIScanAnalyzingView(image: model.image)
        case .result:
            AIScanResultView(model: model, onLog: log)
        case .failed(let message):
            AIScanFailedView(image: model.image, message: message) { model.retake() }
        }
    }

    private func log() {
        model.log(into: modelContext, day: day)
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        onLogged()
        dismiss()
    }

    // MARK: Toast

    @ViewBuilder
    private var toastOverlay: some View {
        if let toast = model.toast {
            Text(toast)
                .font(NT.Fonts.subheadlineBold)
                .foregroundStyle(NT.Colors.ink)
                .padding(.horizontal, 16)
                .frame(height: NT.Size.control)
                .background(NT.Colors.surface2, in: Capsule())
                .padding(.bottom, NT.Size.primaryButton + 28)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .task {
                    try? await Task.sleep(for: .seconds(2))
                    withAnimation { model.toast = nil }
                }
        }
    }
}

// MARK: - Analysing

/// Photo with a spinner and the "looking at your plate" line while the estimate is in flight.
struct AIScanAnalyzingView: View {
    var image: UIImage?

    var body: some View {
        VStack(spacing: NT.Spacing.section) {
            AIScanPhoto(image: image, tags: [])
                .overlay(NT.Colors.ground.opacity(0.35), in: RoundedRectangle(cornerRadius: NT.Radius.card, style: .continuous))
                .overlay {
                    ProgressView().tint(NT.Colors.ink).controlSize(.large)
                }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.top, 12)
            Text("fuel.ai.analyzing")
                .font(NT.Fonts.subheadline)
                .foregroundStyle(NT.Colors.ink2)
            Spacer()
        }
    }
}

// MARK: - Failed

struct AIScanFailedView: View {
    var image: UIImage?
    var message: String
    var onRetake: () -> Void

    var body: some View {
        VStack(spacing: NT.Spacing.section) {
            if image != nil {
                AIScanPhoto(image: image, tags: [])
                    .opacity(0.5)
                    .padding(.horizontal, NT.Spacing.screenH)
                    .padding(.top, 12)
            }
            VStack(spacing: 8) {
                Image(systemName: "eye.slash")
                    .font(.system(size: 28, weight: .semibold))
                    .foregroundStyle(NT.Colors.ink2)
                Text(message)
                    .font(NT.Fonts.body)
                    .foregroundStyle(NT.Colors.ink)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.top, image == nil ? 40 : 0)
            Spacer()
            PrimaryButton(title: "fuel.ai.retake", systemImage: "camera", action: onRetake)
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.bottom, 12)
        }
    }
}
