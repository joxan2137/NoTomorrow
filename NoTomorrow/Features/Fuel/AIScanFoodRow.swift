import SwiftUI

/// One recognised food: name, portion ("6 szt. × 35 g") and macros, a grams cell with the correction menu, kcal on the
/// right. At least 58 pt, hairline below. Tapping the name opens the item editor.
struct AIScanFoodRow: View {
    var food: AIFood
    var onScale: (Double) -> Void
    var onSetGrams: (Double) -> Void
    var onStepCount: (_ up: Bool) -> Void
    var onEdit: () -> Void
    var onRemove: () -> Void

    @State private var showCustom = false
    @State private var customText = ""

    private var showsGuess: Bool { food.isGuess || food.confidence < 0.4 }

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onEdit) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 8) {
                        Text(food.name)
                            .font(NT.Fonts.headline)
                            .foregroundStyle(NT.Colors.ink)
                            .lineLimit(1)
                        if showsGuess {
                            Badge(text: "fuel.ai.guess", color: NT.Colors.ink2)
                        }
                    }
                    if let basis = AIScanFormat.portionBasis(food) {
                        Text(basis)
                            .font(NT.Fonts.footnote)
                            .foregroundStyle(NT.Colors.ink2)
                            .tabular()
                            .lineLimit(1)
                    }
                    Text(String(format: String(localized: "fuel.ai.macrosRow"),
                                AIScanFormat.wholeGrams(food.protein),
                                AIScanFormat.wholeGrams(food.carbs),
                                AIScanFormat.wholeGrams(food.fat)))
                        .font(NT.Fonts.footnote)
                        .foregroundStyle(NT.Colors.ink2)
                        .tabular()
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(Text("fuel.ai.editItem"))

            gramsMenu

            Text(Fmt.kcal(food.kcal, withUnit: false))
                .font(NT.Fonts.subheadline)
                .foregroundStyle(NT.Colors.ink)
                .tabular()
                .frame(width: 64, alignment: .trailing)
                .contentTransition(.numericText())
                .animation(.easeOut(duration: 0.2), value: food.kcal)
        }
        .padding(.vertical, 8)
        .frame(minHeight: 58)
        .overlay(alignment: .bottom) { Hairline() }
        .alert("fuel.ai.grams.customTitle", isPresented: $showCustom) {
            TextField("fuel.ai.grams.placeholder", text: $customText)
                .keyboardType(.decimalPad)
            Button("common.done") { applyCustom() }
            Button("common.cancel", role: .cancel) {}
        }
    }

    /// 36 pt surface-2 cell: grams · "g" · chevron. The menu rescales kcal and macros, steps the count, opens the
    /// editor or removes the item.
    private var gramsMenu: some View {
        Menu {
            ForEach([-0.25, -0.10, 0.10, 0.25], id: \.self) { delta in
                Button { onScale(1 + delta) } label: {
                    Text(delta.formatted(.percent.sign(strategy: .always())))
                }
            }
            Divider()
            if let unit = food.unitName {
                Button { onStepCount(true) } label: {
                    Label(FuelText.format("fuel.ai.item.plusOne", unit), systemImage: "plus")
                }
                Button { onStepCount(false) } label: {
                    Label(FuelText.format("fuel.ai.item.minusOne", unit), systemImage: "minus")
                }
                .disabled(food.units <= 0.5)
            }
            Button {
                customText = AIScanFormat.wholeGrams(food.grams)
                showCustom = true
            } label: {
                Label("fuel.ai.grams.custom", systemImage: "scalemass")
            }
            Button(action: onEdit) {
                Label("fuel.ai.editItem", systemImage: "pencil")
            }
            Divider()
            Button(role: .destructive, action: onRemove) {
                Label("fuel.ai.removeItem", systemImage: "trash")
            }
        } label: {
            HStack(spacing: 6) {
                Text(AIScanFormat.wholeGrams(food.grams))
                    .font(NT.Fonts.subheadline)
                    .foregroundStyle(NT.Colors.ink)
                    .tabular()
                    .contentTransition(.numericText())
                Text(verbatim: "g")
                    .font(NT.Fonts.footnote)
                    .foregroundStyle(NT.Colors.ink2)
                Image(systemName: "chevron.down")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(NT.Colors.ink3)
            }
            .padding(.horizontal, 10)
            .frame(height: 36)
            .background(NT.Colors.surface2, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            .frame(minHeight: NT.Size.control)
            .contentShape(Rectangle())
            .animation(.easeOut(duration: 0.2), value: food.grams)
        }
        .buttonStyle(.plain)
    }

    private func applyCustom() {
        if let grams = NumberInput.nonNegative(customText), grams > 0 { onSetGrams(grams) }
        customText = ""
    }
}
