import SwiftUI
import SwiftData

/// Partner row destination: who you are paired with, since when, your code, and Unpair.
struct PartnerEditor: View {
    @Bindable var model: SettingsModel
    var onUnpaired: () -> Void

    @Environment(\.modelContext) private var modelContext
    @Query private var pairings: [BroPairing]
    @State private var showsUnpair = false

    private var name: String { model.partnerName ?? pairings.first?.partnerName ?? "" }
    private var since: Date { model.pairedAt ?? pairings.first?.pairedAt ?? .now }

    var body: some View {
        STEditorScreen(title: "settings.gymBro") {
            STGroup {
                STInfoRow(label: "settings.bro.partner", value: name, dot: NT.Colors.good)
                STInfoRow(label: "settings.bro.since", value: Fmt.dayMonth(since))
                SettingsCodeRow(code: model.bro.myCode ?? pairings.first?.myCode)
            }
            .stFootnote("settings.bro.shareHint")

            SecondaryButton(title: "settings.unpair", height: NT.Size.cardButton, tint: NT.Colors.bad) {
                showsUnpair = true
            }
            .disabled(model.isUnpairing)
            .opacity(model.isUnpairing ? 0.5 : 1)
        }
        .confirmationDialog(Text("settings.unpair.confirm \(name)"), isPresented: $showsUnpair, titleVisibility: .visible) {
            Button("settings.unpair", role: .destructive) {
                Task {
                    await model.unpair(in: modelContext)
                    onUnpaired()
                }
            }
            Button("common.cancel", role: .cancel) {}
        } message: {
            Text("settings.unpair.message")
        }
    }
}
