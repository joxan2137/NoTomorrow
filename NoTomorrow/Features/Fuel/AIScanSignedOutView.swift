import SwiftUI

/// AI photo flow when the backend path needs an account and there is none: no photo is taken and nothing
/// leaves the device until the user signs in (or switches to their own Claude key in Settings).
struct AIScanSignedOutView: View {
    var meal: MealSlot
    var onSignedIn: () -> Void

    @State private var showsSignIn = false

    var body: some View {
        VStack(alignment: .leading, spacing: NT.Spacing.section) {
            VStack(alignment: .leading, spacing: 6) {
                Text(AIScanText.slotKey(meal)).eyebrow()
                Text("fuel.ai.signedOut.title")
                    .font(NT.Fonts.title3)
                    .foregroundStyle(NT.Colors.ink)
                Text("fuel.ai.signedOut.subtitle")
                    .font(NT.Fonts.subheadline)
                    .foregroundStyle(NT.Colors.ink2)
                    .fixedSize(horizontal: false, vertical: true)
            }

            ZStack {
                RoundedRectangle(cornerRadius: NT.Radius.card, style: .continuous).fill(NT.Colors.surface)
                Image(systemName: "person.crop.circle.badge.exclamationmark")
                    .font(.system(size: 34, weight: .medium))
                    .foregroundStyle(NT.Colors.ink3)
            }
            .frame(height: 210)
            .frame(maxWidth: .infinity)

            PrimaryButton(title: "auth.signIn") { showsSignIn = true }
            Spacer()
        }
        .padding(.horizontal, NT.Spacing.screenH)
        .padding(.top, 12)
        .sheet(isPresented: $showsSignIn) {
            SignInView(onSignedIn: onSignedIn)
        }
    }
}
