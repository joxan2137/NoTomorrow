import SwiftUI

/// "A new version is out" strip at the top of the screen, shown while `UpdateChecker` has a newer release. Tapping it
/// opens the GitHub releases page; the close button hides it until the next release.
struct UpdateBanner: View {
    let tag: String
    let onDismiss: () -> Void
    @Environment(\.openURL) private var openURL

    var body: some View {
        HStack(spacing: 12) {
            Button {
                openURL(UpdateChecker.releasesPage)
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "arrow.down.circle.fill")
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(NT.Colors.ember)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("update.banner.title \(tag)")
                            .font(NT.Fonts.subheadlineBold)
                            .foregroundStyle(NT.Colors.ink)
                        Text("update.banner.subtitle")
                            .font(NT.Fonts.footnote)
                            .foregroundStyle(NT.Colors.ink2)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(NT.Colors.ink2)
                    .frame(width: 32, height: 32)
                    .background(NT.Colors.surface2, in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("update.banner.dismiss"))
        }
        .padding(.leading, 14)
        .padding(.trailing, 10)
        .padding(.vertical, 10)
        .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.tile, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: NT.Radius.tile, style: .continuous).stroke(NT.Colors.hairline))
        .shadow(color: .black.opacity(0.35), radius: 12, y: 4)
        .padding(.horizontal, NT.Spacing.screenH / 2)
        .padding(.top, 4)
        .padding(.bottom, 8)
    }
}
