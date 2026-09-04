import SwiftUI

// STUB — replace this file with the real screen (see docs/architecture.md).
struct ProgressHomeView: View {
    var body: some View {
        VStack(spacing: 12) {
            Text("tab.progress").font(NT.Fonts.largeTitle).foregroundStyle(NT.Colors.ink)
            Text("Coming next").font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ntScreenBackground()
    }
}
