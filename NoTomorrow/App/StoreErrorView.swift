import SwiftUI

/// Shown instead of the app when the saved data cannot be opened (see `StoreLoader`). Try again first; save a copy
/// of the files through the share sheet; or, as a last resort, move them into a backup and start from setup.
struct StoreErrorView: View {
    @Environment(StoreLoader.self) private var store
    @Environment(AppState.self) private var appState

    @State private var confirmsStartFresh = false
    /// The raw error, collapsed: it is English and technical, there for a bug report.
    @State private var showsDetails = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(NT.Colors.ember)
                    .frame(width: 56, height: 56)
                    .background(NT.Colors.emberTint, in: Circle())
                    .accessibilityHidden(true)
                Text("store.error.title")
                    .font(NT.Fonts.title1)
                    .foregroundStyle(NT.Colors.ink)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                    .padding(.top, 20)
                Text("store.error.body")
                    .font(NT.Fonts.subheadline)
                    .foregroundStyle(NT.Colors.ink2)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)
                if let failure = store.failure {
                    DisclosureGroup(isExpanded: $showsDetails) {
                        // Raw error for a bug report; not translated on purpose. Selectable, so it can be copied.
                        Text(verbatim: failure)
                            .font(NT.Fonts.caption)
                            .foregroundStyle(NT.Colors.ink3)
                            .textSelection(.enabled)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.top, 8)
                    } label: {
                        Text("store.error.details")
                            .font(NT.Fonts.footnote)
                            .foregroundStyle(NT.Colors.ink2)
                    }
                    .tint(NT.Colors.ink2)
                    .padding(.top, 14)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 56)
            .padding(.horizontal, NT.Spacing.screenH)
        }
        .scrollBounceBehavior(.basedOnSize)
        .safeAreaInset(edge: .bottom) { actions }
        .ntScreenBackground()
        .confirmationDialog("store.error.startFresh.title", isPresented: $confirmsStartFresh, titleVisibility: .visible) {
            Button("store.error.startFresh", role: .destructive) { startFresh() }
            Button("common.cancel", role: .cancel) {}
        } message: {
            Text("store.error.startFresh.message")
        }
    }

    private var actions: some View {
        VStack(spacing: 12) {
            PrimaryButton(title: "store.error.retry") {
                if store.retry() { Haptics.success() } else { Haptics.warning() }
            }
            let files = store.shareableFiles
            if !files.isEmpty {
                ShareLink(items: files) {
                    HStack(spacing: 8) {
                        Image(systemName: "square.and.arrow.up").font(.system(size: 16, weight: .semibold))
                        Text("store.error.share").font(NT.Fonts.headline).lineLimit(1)
                    }
                    .foregroundStyle(NT.Colors.ink)
                    .frame(maxWidth: .infinity)
                    .frame(height: NT.Size.primaryButton)
                    .padding(.horizontal, 16)
                    .background(NT.Colors.surface2, in: Capsule())
                }
                .buttonStyle(PressScale())
            }
            Button { confirmsStartFresh = true } label: {
                Text("store.error.startFresh")
                    .font(NT.Fonts.subheadlineBold)
                    .foregroundStyle(NT.Colors.bad)
                    .frame(maxWidth: .infinity)
                    .frame(height: NT.Size.control)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressScale())
        }
        .padding(.horizontal, NT.Spacing.screenH)
        .padding(.top, 12)
        .padding(.bottom, 8)
        .background(NT.Colors.ground)
    }

    /// Moves the old files aside, then opens a new store. Setup is reset as soon as the files are gone, before the
    /// reopen: an empty store has no profile or schedule (the same as after deleting the account), and if the reopen
    /// fails this screen stays up with Try again, which must then land in setup too.
    private func startFresh() {
        guard store.moveAside() else {
            Haptics.warning()
            return
        }
        appState.hasOnboarded = false
        if store.retry() { Haptics.success() } else { Haptics.warning() }
    }
}
