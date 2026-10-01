import SwiftUI

/// Step 1 of the AI flow without a photo: the meal typed out in words, the last resort when there is no picture.
/// The text is the model's `notes` (capped at 1500 characters like the photo details); "Estimate calories" stays
/// disabled until it says something.
struct AIScanDescribeView: View {
    @Binding var notes: String
    var meal: MealSlot
    var canSubmit: Bool
    var onSubmit: () -> Void

    @FocusState private var focused: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: NT.Spacing.section) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(AIScanText.slotKey(meal)).eyebrow()
                    Text("fuel.ai.describe.title")
                        .font(NT.Fonts.title3)
                        .foregroundStyle(NT.Colors.ink)
                    Text("fuel.ai.describe.subtitle")
                        .font(NT.Fonts.subheadline)
                        .foregroundStyle(NT.Colors.ink2)
                        .fixedSize(horizontal: false, vertical: true)
                }

                TextField("fuel.ai.describe.placeholder", text: $notes, axis: .vertical)
                    .font(NT.Fonts.body)
                    .foregroundStyle(NT.Colors.ink)
                    .tint(NT.Colors.ink)
                    .lineLimit(5...10)
                    .focused($focused)
                    .padding(12)
                    .background(NT.Colors.surface2, in: RoundedRectangle(cornerRadius: 12))
                    .onChange(of: notes) { _, value in if value.count > 1500 { notes = String(value.prefix(1500)) } }

                PrimaryButton(title: "fuel.ai.describe.estimate", systemImage: "sparkles", isEnabled: canSubmit) {
                    focused = false
                    onSubmit()
                }
            }
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.top, 12)
            .padding(.bottom, 24)
        }
        .scrollDismissesKeyboard(.interactively)
        .onAppear { focused = true }
    }
}
