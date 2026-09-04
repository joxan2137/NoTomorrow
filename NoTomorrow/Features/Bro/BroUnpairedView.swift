import SwiftUI
import SwiftData

/// Unpaired Bro tab: the code card (share + copy) and the "enter your bro's code" field.
struct BroUnpairedView: View {
    var myCode: String?
    var isLoading: Bool
    var onPair: (String) async -> Bool

    @State private var enteredCode = ""
    @State private var copied = false
    @State private var isPairing = false
    @State private var pairFailed = false
    @FocusState private var codeFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("bro.unpaired.subtitle")
                .font(NT.Fonts.subheadline)
                .foregroundStyle(NT.Colors.ink2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)

            codeCard.padding(.top, 28)

            HStack(spacing: 12) {
                Hairline()
                Text("onboarding.pair.orEnter").font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink2).lineLimit(1).fixedSize()
                Hairline()
            }
            .padding(.top, 24)

            enterRow.padding(.top, 18)

            if pairFailed {
                Text("bro.invalidCode")
                    .font(NT.Fonts.footnote)
                    .foregroundStyle(NT.Colors.bad)
                    .padding(.top, 10)
            }

            Text("onboarding.pair.helper")
                .font(NT.Fonts.footnote)
                .foregroundStyle(NT.Colors.ink2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 22)
        }
    }

    // MARK: Code card

    private var codeCard: some View {
        NTCard(padding: 0) {
            VStack(spacing: 14) {
                Text("onboarding.pair.yourCode").eyebrow()
                codeText
                    .font(NT.Fonts.display(56))
                    .tracking(2)
                    .foregroundStyle(myCode == nil ? NT.Colors.ink3 : NT.Colors.ink)
                    .tabular()
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                HStack(spacing: 10) {
                    shareButton
                    copyButton
                }
            }
            .frame(maxWidth: .infinity)
            .padding(EdgeInsets(top: 20, leading: 18, bottom: 18, trailing: 18))
        }
    }

    private var codeText: Text {
        if let myCode { return Text(myCode) }
        return Text("bro.codePlaceholder")
    }

    @ViewBuilder
    private var shareButton: some View {
        if let myCode {
            ShareLink(item: String(format: String(localized: "bro.shareText"), myCode)) {
                shareLabel
            }
            .buttonStyle(PressScale())
        } else {
            shareLabel.opacity(0.4)
        }
    }

    private var shareLabel: some View {
        HStack(spacing: 8) {
            Image(systemName: "square.and.arrow.up").font(.system(size: 16, weight: .semibold))
            Text("onboarding.pair.share").font(NT.Fonts.headline).lineLimit(1)
        }
        .foregroundStyle(NT.Colors.onPrimary)
        .frame(maxWidth: .infinity)
        .frame(height: 48)
        .background(NT.Colors.ink, in: Capsule())
    }

    private var copyButton: some View {
        Button {
            guard let myCode else { return }
            UIPasteboard.general.string = myCode
            withAnimation(.easeOut(duration: 0.15)) { copied = true }
            Task {
                try? await Task.sleep(for: .seconds(1.5))
                withAnimation(.easeOut(duration: 0.3)) { copied = false }
            }
        } label: {
            Image(systemName: copied ? "checkmark" : "doc.on.doc")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(copied ? NT.Colors.good : NT.Colors.ink)
                .frame(width: 48, height: 48)
                .background(NT.Colors.surface2, in: Circle())
        }
        .buttonStyle(PressScale())
        .disabled(myCode == nil)
        .accessibilityLabel(Text(copied ? "bro.copied" : "bro.copy"))
    }

    // MARK: Enter code

    private var enterRow: some View {
        HStack(spacing: 10) {
            TextField("bro.codePlaceholder", text: $enteredCode)
                .font(NT.Fonts.display(26))
                .tracking(1)
                .foregroundStyle(NT.Colors.ink)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .submitLabel(.go)
                .focused($codeFocused)
                .onSubmit { pair() }
                .onChange(of: enteredCode) { _, value in
                    pairFailed = false
                    if value.count > 12 { enteredCode = String(value.prefix(12)) }
                }
                .padding(.horizontal, 16)
                .frame(height: 52)
                .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(NT.Colors.hairline, lineWidth: 1))

            Button(action: pair) {
                Group {
                    if isPairing || isLoading {
                        ProgressView().tint(NT.Colors.ink)
                    } else {
                        Text("onboarding.pair.pair").font(NT.Fonts.headline)
                    }
                }
                .foregroundStyle(canPair ? NT.Colors.ink : NT.Colors.ink2)
                .padding(.horizontal, 22)
                .frame(height: 52)
                .background(NT.Colors.surface2, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(PressScale())
            .disabled(!canPair || isPairing)
        }
    }

    private var canPair: Bool {
        enteredCode.trimmingCharacters(in: .whitespacesAndNewlines).count >= 4
    }

    private func pair() {
        guard canPair, !isPairing else { return }
        codeFocused = false
        isPairing = true
        pairFailed = false
        let code = enteredCode
        Task {
            let ok = await onPair(code)
            isPairing = false
            pairFailed = !ok
            if ok { enteredCode = "" }
        }
    }
}
