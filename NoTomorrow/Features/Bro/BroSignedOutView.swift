import SwiftUI

/// Bro tab when the real backend is in use and there is no account session: pairing needs an account,
/// so the code card gives way to one primary "Sign in to pair" action.
struct BroSignedOutView: View {
    var onSignedIn: () -> Void

    @State private var showsSignIn = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("bro.signedOut.subtitle")
                .font(NT.Fonts.subheadline)
                .foregroundStyle(NT.Colors.ink2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)

            NTCard {
                VStack(alignment: .leading, spacing: 16) {
                    HStack(spacing: 12) {
                        Image(systemName: "person.2")
                            .font(.system(size: 22, weight: .semibold))
                            .foregroundStyle(NT.Colors.ink2)
                            .frame(width: 44, height: 44)
                            .background(NT.Colors.surface2, in: Circle())
                        VStack(alignment: .leading, spacing: 2) {
                            Text("bro.signedOut.title").font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink)
                            Text("bro.signedOut.body").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    PrimaryButton(title: "bro.signInToPair", height: NT.Size.cardButton) { showsSignIn = true }
                }
            }
            .padding(.top, 28)

            Text("onboarding.pair.helper")
                .font(NT.Fonts.footnote)
                .foregroundStyle(NT.Colors.ink2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 22)
        }
        .sheet(isPresented: $showsSignIn) {
            SignInView(onSignedIn: onSignedIn)
        }
    }
}
