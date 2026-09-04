import SwiftUI
import SwiftData

/// Sign-in / create-account sheet. Presented from Settings → Account, the Bro tab's signed-out state and the
/// AI photo flow. Calls `onSignedIn` and dismisses itself on success.
struct SignInView: View {
    var onSignedIn: () -> Void = {}

    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var modelContext
    @State private var model = SignInModel()
    @FocusState private var focus: Field?

    private enum Field { case username, password }

    var body: some View {
        VStack(spacing: 0) {
            Grabber()
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    titleBlock
                    modeSwitch
                    fields
                    if let message = model.errorMessage {
                        Text(message)
                            .font(NT.Fonts.footnote)
                            .foregroundStyle(NT.Colors.bad)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    submitButton
                    orDivider
                    googleRow
                    Text("auth.footnote")
                        .font(NT.Fonts.footnote)
                        .foregroundStyle(NT.Colors.ink2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.top, 18)
                .padding(.bottom, 24)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .background(NT.Colors.surface.ignoresSafeArea())
        .presentationDetents([.large])
        .presentationDragIndicator(.hidden)
        .presentationBackground(NT.Colors.surface)
        .presentationCornerRadius(24)
        .animation(.easeOut(duration: 0.2), value: model.mode)
        .animation(.easeOut(duration: 0.2), value: model.errorMessage)
    }

    // MARK: Sections

    private var titleBlock: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(model.mode == .signIn ? "auth.signIn" : "auth.createAccount")
                .font(NT.Fonts.title2)
                .foregroundStyle(NT.Colors.ink)
            Text("auth.subtitle")
                .font(NT.Fonts.subheadline)
                .foregroundStyle(NT.Colors.ink2)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var modeSwitch: some View {
        STSegmented(
            options: [.init(value: .signIn, title: "auth.signIn"), .init(value: .createAccount, title: "auth.createAccount")],
            selection: Binding(get: { model.mode }, set: { if $0 != model.mode { model.switchMode() } })
        )
    }

    private var fields: some View {
        VStack(alignment: .leading, spacing: 14) {
            TextField(String(localized: "auth.username"), text: $model.username)
                .font(NT.Fonts.body)
                .foregroundStyle(NT.Colors.ink)
                .textContentType(.username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.next)
                .focused($focus, equals: .username)
                .onSubmit { focus = .password }
                .stField(isFocused: focus == .username)
                .stLabeled("auth.username")

            SecureField(String(localized: "auth.password"), text: $model.password)
                .font(NT.Fonts.body)
                .foregroundStyle(NT.Colors.ink)
                .textContentType(model.mode == .signIn ? .password : SignInView.newPasswordContentType)
                .submitLabel(.go)
                .focused($focus, equals: .password)
                .onSubmit(submit)
                .stField(isFocused: focus == .password)
                .stLabeled("auth.password")

            if model.mode == .createAccount {
                VStack(alignment: .leading, spacing: 2) {
                    Text("auth.hint.username")
                    Text("auth.hint.password")
                }
                .font(NT.Fonts.footnote)
                .foregroundStyle(NT.Colors.ink2)
                .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var submitButton: some View {
        PrimaryButton(title: model.mode == .signIn ? "auth.signIn" : "auth.createAccount",
                      isEnabled: model.canSubmit) { submit() }
            .overlay {
                if model.isBusy { ProgressView().tint(NT.Colors.onPrimary) }
            }
    }

    private var orDivider: some View {
        HStack(spacing: 12) {
            Hairline()
            Text("auth.or").font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink2).lineLimit(1).fixedSize()
            Hairline()
        }
    }

    /// Placeholder until the Google Sign-In SDK is wired; the row is visibly disabled.
    private var googleRow: some View {
        SecondaryButton(title: "auth.google", systemImage: "g.circle") {}
            .disabled(true)
            .opacity(0.4)
    }

    // MARK: Actions

    private func submit() {
        guard model.canSubmit else { return }
        focus = nil
        Task {
            if await model.submit(in: modelContext) {
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                onSignedIn()
                dismiss()
            }
        }
    }
}


extension SignInView {
    /// The simulator has no iCloud Keychain and covers a `.newPassword` field with an "Automatic Strong Password" overlay that blocks typing.
    static var newPasswordContentType: UITextContentType {
        #if targetEnvironment(simulator)
        return .password
        #else
        return .newPassword
        #endif
    }
}
