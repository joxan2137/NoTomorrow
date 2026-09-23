import Foundation
import Observation
import SwiftData

/// Username + password sign-in / registration against `AppConfig.shared.makeBackendClient()`.
/// On success the session goes to the Keychain (`AuthStore.shared`) and `BroService.didSignIn` pushes the
/// local schedule and refreshes the partner state. Errors are generic on purpose (no account enumeration).
@Observable
@MainActor
final class SignInModel {
    enum Mode: Equatable {
        case signIn
        case createAccount
    }

    /// Server rules (`backend/src/auth/password.ts`): username 3–24 `[a-z0-9_.]`, password 10–128 characters.
    static let usernameRange = 3...24
    static let passwordMinimum = 10

    var mode: Mode = .signIn
    var username = ""
    var password = ""
    var isBusy = false
    var errorMessage: String?

    var trimmedUsername: String { username.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }

    var canSubmit: Bool {
        guard !isBusy, Self.usernameRange.contains(trimmedUsername.count) else { return false }
        return mode == .createAccount ? password.count >= Self.passwordMinimum : !password.isEmpty
    }

    func switchMode() {
        mode = mode == .signIn ? .createAccount : .signIn
        errorMessage = nil
    }

    /// Signs in (or registers), stores the session and runs the post-sign-in sync. `true` on success.
    func submit(in context: ModelContext) async -> Bool {
        guard canSubmit else { return false }
        isBusy = true
        errorMessage = nil
        defer { isBusy = false }
        let client = AppConfig.shared.makeBackendClient()
        do {
            let session: Session
            switch mode {
            case .signIn: session = try await client.signIn(username: trimmedUsername, password: password)
            case .createAccount: session = try await client.register(username: trimmedUsername, password: password, email: nil)
            }
            AuthStore.shared.save(session)
            password = ""
            await BroShared.service.didSignIn(in: context)
            return true
        } catch {
            errorMessage = Self.message(for: BackendError.wrap(error), mode: mode)
            return false
        }
    }

    /// Generic copy for credential failures; specific only where the user can act on it (taken name, weak password).
    static func message(for error: BackendError, mode: Mode) -> String {
        switch error {
        case .network, .timedOut:
            return String(localized: "error.network")
        case .http(let status, let code, _):
            if code == "username_taken" { return String(localized: "auth.error.usernameTaken") }
            if code == "username_invalid" { return String(localized: "auth.hint.username") }
            if code.hasPrefix("password_") { return String(localized: "auth.error.password") }
            if status == 429 { return String(localized: "auth.error.tooMany") }
            return String(localized: mode == .signIn ? "auth.error.generic" : "auth.error.register")
        default:
            return String(localized: mode == .signIn ? "auth.error.generic" : "auth.error.register")
        }
    }

    // MARK: Sign-out

    /// Revokes the refresh token server-side (best effort), clears the Keychain session and the account-bound
    /// bro state. Workouts, meals and the local schedule stay on the phone.
    static func signOut() async {
        let auth = AuthStore.shared
        if let refreshToken = auth.session?.refreshToken {
            try? await AppConfig.shared.makeBackendClient().logout(refreshToken: refreshToken)
        }
        auth.clear()
        BroShared.service.resetSession()
    }
}
