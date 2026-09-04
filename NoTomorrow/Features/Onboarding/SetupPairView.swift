import SwiftUI
import SwiftData

/// Step "Pair": your code (share / copy), enter your bro's code, Continue or Not now.
struct SetupPairView: View {
    @Environment(AppState.self) private var appState
    @Environment(\.modelContext) private var modelContext
    @Bindable var model: OnboardingModel

    @FocusState private var codeFocused: Bool
    @State private var copied = false
    @State private var isFinishing = false

    var body: some View {
        OBStepScaffold(index: model.stepIndex ?? 2, count: model.stepCount, onBack: { model.back() }) {
            VStack(alignment: .leading, spacing: 0) {
                OBStepTitle(title: "onboarding.pair.title", subtitle: "onboarding.pair.subtitle")

                avatars
                    .frame(maxWidth: .infinity)
                    .padding(.top, 36)

                codeCard
                    .padding(.horizontal, NT.Spacing.screenH)
                    .padding(.top, 32)

                if !model.isPaired {
                    orDivider
                        .padding(.horizontal, NT.Spacing.screenH)
                        .padding(.top, 24)
                    entryRow
                        .padding(.horizontal, NT.Spacing.screenH)
                        .padding(.top, 18)
                    if model.pairFailed {
                        Text("onboarding.pair.invalid")
                            .font(NT.Fonts.footnote)
                            .foregroundStyle(NT.Colors.bad)
                            .padding(.horizontal, NT.Spacing.screenH)
                            .padding(.top, 10)
                    }
                }
            }
        } footer: {
            VStack(spacing: 12) {
                helperLine
                PrimaryButton(title: "common.continue", isEnabled: !isFinishing) { advance() }
                if !model.isPaired {
                    Button { advance() } label: {
                        Text("onboarding.pair.notNow")
                            .font(NT.Fonts.subheadline)
                            .foregroundStyle(NT.Colors.ink2)
                            .frame(maxWidth: .infinity)
                            .frame(height: NT.Size.control)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(PressScale())
                    .disabled(isFinishing)
                    .padding(.bottom, -10)
                }
            }
        }
        .task { await model.loadCode() }
    }

    // MARK: Pieces

    private var avatars: some View {
        HStack(spacing: -14) {
            OBBigAvatar(initial: model.trimmedName, background: NT.Colors.surface2)
            if let partner = model.partnerName {
                OBBigAvatar(initial: partner, background: NT.Colors.surface)
            } else {
                Image(systemName: "plus")
                    .font(.system(size: 24, weight: .medium))
                    .foregroundStyle(NT.Colors.ink3)
                    .frame(width: 72, height: 72)
                    .background(NT.Colors.surface, in: Circle())
                    .overlay(Circle().strokeBorder(NT.Colors.ground, lineWidth: 3))
            }
        }
        .animation(.easeOut(duration: 0.2), value: model.partnerName)
    }

    private var codeCard: some View {
        NTCard(padding: 0) {
            VStack(spacing: 14) {
                Text("onboarding.pair.yourCode").eyebrow()
                Text(verbatim: model.myCode)
                    .font(NT.Fonts.display(56))
                    .foregroundStyle(NT.Colors.ink)
                    .tracking(2.2)
                    .tabular()
                    .contentTransition(.numericText())
                HStack(spacing: 10) {
                    ShareLink(item: shareText) {
                        HStack(spacing: 8) {
                            Image(systemName: "square.and.arrow.up").font(.system(size: 16, weight: .semibold))
                            Text("onboarding.pair.share").font(NT.Fonts.headline)
                        }
                        .foregroundStyle(NT.Colors.onPrimary)
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                        .background(NT.Colors.ink, in: Capsule())
                    }
                    .buttonStyle(PressScale())

                    Button(action: copyCode) {
                        Image(systemName: copied ? "checkmark" : "doc.on.doc")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(copied ? NT.Colors.good : NT.Colors.ink)
                            .frame(width: 48, height: 48)
                            .background(NT.Colors.surface2, in: Circle())
                            .contentTransition(.symbolEffect(.replace))
                    }
                    .buttonStyle(PressScale())
                    .accessibilityLabel(Text(copied ? "common.copied" : "common.copy"))
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 20)
            .padding(.horizontal, 18)
            .padding(.bottom, 18)
        }
    }

    private var orDivider: some View {
        HStack(spacing: 12) {
            Hairline()
            Text("onboarding.pair.orEnter").font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink2).lineLimit(1).fixedSize()
            Hairline()
        }
    }

    private var entryRow: some View {
        HStack(spacing: 10) {
            TextField("onboarding.pair.codePlaceholder", text: $model.codeEntry)
                .font(NT.Fonts.display(26))
                .foregroundStyle(NT.Colors.ink)
                .tracking(1)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .keyboardType(.asciiCapable)
                .submitLabel(.go)
                .focused($codeFocused)
                .onSubmit { pair() }
                .obField(isFocused: codeFocused)

            Button(action: pair) {
                ZStack {
                    Text("onboarding.pair.pair")
                        .font(NT.Fonts.headline)
                        .foregroundStyle(model.canPair ? NT.Colors.ink : NT.Colors.ink2)
                        .opacity(model.isPairing ? 0 : 1)
                    if model.isPairing { ProgressView().tint(NT.Colors.ink) }
                }
                .padding(.horizontal, 22)
                .frame(height: 52)
                .background(NT.Colors.surface2, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(PressScale())
            .disabled(!model.canPair)
        }
    }

    private var helperLine: some View {
        Group {
            if let partner = model.partnerName {
                Text("onboarding.pair.paired \(partner)")
            } else if AuthStore.shared.needsSignIn {
                Text("onboarding.pair.signInLater")
            } else {
                Text("onboarding.pair.helper")
            }
        }
        .font(NT.Fonts.footnote)
        .foregroundStyle(NT.Colors.ink2)
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
    }

    private var shareText: String {
        let format = OBL10n.string("onboarding.pair.shareText", language: appState.languageOverride)
        return String(format: format, model.myCode)
    }

    // MARK: Actions

    private func pair() {
        codeFocused = false
        Task { await model.pair(in: modelContext) }
    }

    private func copyCode() {
        UIPasteboard.general.string = model.myCode
        withAnimation { copied = true }
        Task {
            try? await Task.sleep(for: .seconds(1.5))
            withAnimation { copied = false }
        }
    }

    private func advance() {
        codeFocused = false
        if model.isLastStep {
            isFinishing = true
            Task {
                await model.finish(in: modelContext, appState: appState)
                isFinishing = false
            }
        } else {
            model.next()
        }
    }
}

/// 72 pt avatar with a 3 pt ground ring so the pair overlaps cleanly.
struct OBBigAvatar: View {
    var initial: String
    var background: Color

    var body: some View {
        Text(initial.prefix(1).uppercased())
            .font(NT.Fonts.title1)
            .foregroundStyle(NT.Colors.ink)
            .frame(width: 72, height: 72)
            .background(background, in: Circle())
            .overlay(Circle().strokeBorder(NT.Colors.ground, lineWidth: 3))
    }
}
