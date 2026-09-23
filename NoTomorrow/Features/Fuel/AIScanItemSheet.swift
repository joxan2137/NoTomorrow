import SwiftUI

/// Edits one item of an AI estimate before it is logged: name, count, grams (per unit or total) or a swap for a
/// food-database product. kcal and macros follow the item's per-100 g values; those are never typed here.
struct AIScanItemSheet: View {
    let meal: MealSlot
    var onDone: (AIFood) -> Void
    var onRemove: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var draft: AIFood
    @State private var name: String
    @State private var perUnitText: String
    @State private var totalText: String
    @State private var showsSearch = false
    @FocusState private var focus: Field?

    enum Field: Hashable { case name, perUnit, total }

    init(food: AIFood, meal: MealSlot, onDone: @escaping (AIFood) -> Void, onRemove: @escaping () -> Void) {
        self.meal = meal
        self.onDone = onDone
        self.onRemove = onRemove
        _draft = State(initialValue: food)
        _name = State(initialValue: food.name)
        _perUnitText = State(initialValue: FuelText.fieldText(food.unitGrams))
        _totalText = State(initialValue: FuelText.fieldText(food.grams))
    }

    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var canSave: Bool { !trimmedName.isEmpty && draft.grams > 0 }

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    summary
                    textRow
                    countRow
                    if draft.unitName != nil {
                        numberRow("fuel.ai.item.gramsPerUnit", text: $perUnitText, field: .perUnit)
                    }
                    numberRow("fuel.ai.item.totalGrams", text: $totalText, field: .total)
                    GhostButton(title: "fuel.ai.item.findInDatabase", systemImage: "magnifyingglass") {
                        focus = nil
                        showsSearch = true
                    }
                    .padding(.top, 6)
                    Button(role: .destructive) {
                        onRemove()
                    } label: {
                        Label("fuel.ai.removeItem", systemImage: "trash")
                            .font(NT.Fonts.subheadlineBold)
                            .foregroundStyle(NT.Colors.bad)
                            .frame(maxWidth: .infinity)
                            .frame(height: NT.Size.control)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.top, 16)
            }
            .scrollDismissesKeyboard(.interactively)
            PrimaryButton(title: "common.done", isEnabled: canSave) { save() }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.bottom, 12)
        }
        .ntScreenBackground()
        .onChange(of: perUnitText) { _, text in
            guard focus == .perUnit, let value = NumberInput.nonNegative(text), value > 0 else { return }
            draft = draft.withGramsPerUnit(value)
            totalText = FuelText.fieldText(draft.grams)
        }
        .onChange(of: totalText) { _, text in
            guard focus == .total, let value = NumberInput.nonNegative(text), value > 0 else { return }
            draft = draft.scaled(toGrams: value)
            perUnitText = FuelText.fieldText(draft.unitGrams)
        }
        .sheet(isPresented: $showsSearch) {
            FoodSearchView(meal: meal, pick: FoodSearchPick(mode: .replace,
                                                            title: String(localized: "fuel.ai.item.findInDatabase")) { food, _ in
                draft = draft.replaced(by: food)
                name = draft.name
                syncTexts()
            })
        }
    }

    private var header: some View {
        HStack {
            Text("fuel.ai.item.title").font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink)
            Spacer()
            Button { dismiss() } label: {
                Text("common.cancel").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink2)
                    .frame(minHeight: NT.Size.control)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .frame(height: NT.Size.control)
        .padding(.horizontal, NT.Spacing.screenH)
        .padding(.top, 12)
    }

    /// Live kcal and macros for the draft, and its density, which the portion scales.
    private var summary: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(Fmt.kcal(draft.kcal, withUnit: false))
                    .font(NT.Fonts.display(40)).foregroundStyle(NT.Colors.ink).tabular()
                    .contentTransition(.numericText())
                    .animation(.easeOut(duration: 0.15), value: draft.kcal)
                Text("unit.kcal").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                Spacer()
                Text(String(format: String(localized: "fuel.ai.macrosTotal"),
                            AIScanFormat.wholeGrams(draft.protein),
                            AIScanFormat.wholeGrams(draft.carbs),
                            AIScanFormat.wholeGrams(draft.fat)))
                    .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink).tabular()
            }
            if let per100 = draft.kcalPer100 {
                Text(FuelText.format("fuel.ai.item.per100", Fmt.kcal(per100, withUnit: false)))
                    .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
            }
        }
        .padding(.bottom, 6)
    }

    /// The caption is the field's VoiceOver label (and is not read a second time on its own).
    private var textRow: some View {
        HStack(spacing: 12) {
            Text("fuel.foodName").font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                .accessibilityHidden(true)
            TextField("", text: $name)
                .font(NT.Fonts.body).foregroundStyle(NT.Colors.ink).tint(NT.Colors.ink)
                .multilineTextAlignment(.trailing)
                .submitLabel(.done)
                .focused($focus, equals: .name)
                .accessibilityLabel(Text("fuel.foodName"))
        }
        .padding(.horizontal, 14)
        .frame(height: 52)
        .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.field, style: .continuous))
    }

    /// "Count   − 6 szt. +": whole units, half a unit at the bottom.
    private var countRow: some View {
        HStack(spacing: 0) {
            Text("fuel.ai.item.count").font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
            Spacer(minLength: 8)
            stepButton("minus", enabled: draft.units > 0.5, label: FuelText.format("fuel.ai.item.minusOne", unitLabel)) {
                step(up: false)
            }
            Text(countText)
                .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).tabular()
                .lineLimit(1).minimumScaleFactor(0.8)
                .frame(minWidth: 72)
            stepButton("plus", enabled: draft.units < AIScanCorrections.maxCount,
                       label: FuelText.format("fuel.ai.item.plusOne", unitLabel)) {
                step(up: true)
            }
        }
        .padding(.leading, 14)
        .frame(height: 52)
        .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.field, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("fuel.ai.item.count"))
        .accessibilityValue(Text(countText))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: step(up: true)
            case .decrement: step(up: false)
            @unknown default: break
            }
        }
    }

    private var unitLabel: String { draft.unitName ?? "" }

    private var countText: String {
        let count = FuelText.fieldText(draft.units)
        return draft.unitName.map { "\(count) \($0)" } ?? count
    }

    private func stepButton(_ symbol: String, enabled: Bool, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(enabled ? NT.Colors.ink : NT.Colors.ink3.opacity(0.4))
                .frame(width: NT.Size.control, height: NT.Size.control)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(Text(label))
    }

    private func numberRow(_ label: LocalizedStringKey, text: Binding<String>, field: Field) -> some View {
        HStack(spacing: 8) {
            Text(label).font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                .accessibilityHidden(true)
            TextField("0", text: text)
                .font(NT.Fonts.body).foregroundStyle(NT.Colors.ink).tint(NT.Colors.ink).tabular()
                .multilineTextAlignment(.trailing)
                .keyboardType(.decimalPad)
                .focused($focus, equals: field)
                .accessibilityLabel(Text(label))
            Text("unit.g").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                .accessibilityHidden(true)
        }
        .padding(.horizontal, 14)
        .frame(height: 52)
        .frame(maxWidth: .infinity)
        .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.field, style: .continuous))
        .contentShape(Rectangle())
        .onTapGesture { focus = field }
    }

    private func step(up: Bool) {
        focus = nil
        draft = draft.withCount(AIScanCorrections.steppedCount(draft.units, up: up))
        syncTexts()
    }

    private func syncTexts() {
        perUnitText = FuelText.fieldText(draft.unitGrams)
        totalText = FuelText.fieldText(draft.grams)
    }

    private func save() {
        guard canSave else { return }
        var edited = draft
        edited.name = trimmedName
        onDone(edited)
    }
}
