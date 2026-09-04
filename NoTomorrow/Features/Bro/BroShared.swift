import Foundation

/// One `BroService` per process for the Bro tab and the Can't-make-it sheet.
/// `NoTomorrowApp` does not inject a `BroService` into the environment yet; once it does, swap this for
/// `@Environment(BroService.self)`. The underlying `BackendClient` is already cached by `AppConfig`,
/// so the mock partner's timers survive across instances either way.
enum BroShared {
    @MainActor static let service = BroService()
}
