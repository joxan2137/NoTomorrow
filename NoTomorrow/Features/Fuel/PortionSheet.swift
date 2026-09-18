import SwiftUI
import SwiftData

/// Bottom sheet that sizes a portion of one food and logs it as a `MealEntry`.
/// Present with `.sheet(item:)`; it brings its own detent, surface background and grabber.
struct PortionSheet: View {
    let food: PortionFood
    let meal: MealSlot
    var day: Date = .now
    /// Called after the entry is saved. The presenter is expected to close everything down to Fuel home.
    var onAdded: () -> Void
    /// Set when the sheet re-sizes an entry that is already logged instead of inserting a new one.
    private let editing: MealEntry?

    @Environment(\.modelContext) private var modelContext
    @State private var grams: Double
    @State private var gramsText: String
    @State private var slot: MealSlot
    @FocusState private var gramsFocused: Bool

    static let step: Double = 10
    static let minimum: Double = 5
    private static let height: CGFloat = 376
    /// One `MealSlotPicker` row (32) plus the stack spacing (16).
    private static let slotRowHeight: CGFloat = 48

    init(food: PortionFood, meal: MealSlot, day: Date = .now, onAdded: @escaping () -> Void) {
        self.food = food
        self.meal = meal
        self.day = day
        self.onAdded = onAdded
        self.editing = nil
        let start = food.servingSizeG ?? 100
        _grams = State(initialValue: start)
        _gramsText = State(initialValue: Self.text(for: start))
        _slot = State(initialValue: meal)
    }

    /// Edit mode: grams start at the entry's portion, the slot can be changed, and Save rewrites the entry in place.
    init(editing entry: MealEntry, food: FoodItem, onSaved: @escaping () -> Void) {
        self.food = .item(food)
        self.meal = entry.slot
        self.day = entry.day
        self.onAdded = onSaved
        self.editing = entry
        _grams = State(initialValue: entry.grams)
        _gramsText = State(initialValue: Self.text(for: entry.grams))
        _slot = State(initialValue: entry.slot)
    }

    private var isEditing: Bool { editing != nil }

    private var factor: Double { grams / 100 }
    private var kcal: Double { food.kcalPer100 * factor }
    private var protein: Double { food.proteinPer100 * factor }
    private var carbs: Double { food.carbsPer100 * factor }
    private var fat: Double { food.fatPer100 * factor }

    var body: some View {
        VStack(spacing: 16) {
            Grabber()
            titleRow
            stepperRow
            portionChips
            macroRow
            if isEditing {
                MealSlotPicker(slot: $slot)
            }
            PrimaryButton(title: isEditing ? "common.save" : FuelText.verbatim(FuelText.addTo(slot)), isEnabled: grams > 0) { add() }
        }
        .padding(.horizontal, NT.Spacing.screenH)
        .padding(.bottom, 12)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(NT.Colors.surface)
        .presentationDetents([.height(Self.height + (isEditing ? Self.slotRowHeight : 0))])
        .presentationDragIndicator(.hidden)
        .presentationCornerRadius(24)
        .presentationBackground(NT.Colors.surface)
        .onChange(of: gramsText) { _, text in
            let cleaned = text.replacingOccurrences(of: ",", with: ".")
            if let value = Double(cleaned), value >= 0 { grams = value }
        }
    }

    // MARK: - Pieces

    private var titleRow: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(food.name).font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).lineLimit(2)
                HStack(spacing: 0) {
                    if let brand = food.brand {
                        Text(brand + " · ")
                    }
                    Text(food.source.labelKey)
                }
                .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).lineLimit(1)
            }
            Spacer(minLength: 8)
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(Fmt.kcal(kcal, withUnit: false))
                    .font(NT.Fonts.display(40)).foregroundStyle(NT.Colors.ink).tabular()
                    .contentTransition(.numericText())
                    .animation(.easeOut(duration: 0.15), value: kcal)
                Text("unit.kcal").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
            }
        }
    }

    private var stepperRow: some View {
        HStack(spacing: 12) {
            stepButton("minus") { set(grams - Self.step) }
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                TextField("fuel.grams", text: $gramsText)
                    .font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink).tabular()
                    .multilineTextAlignment(.trailing)
                    .keyboardType(.decimalPad)
                    .focused($gramsFocused)
                    .fixedSize(horizontal: true, vertical: false)
                    .frame(minWidth: 40)
                Text("unit.g").font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 48)
            .background(NT.Colors.ground, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(NT.Colors.ink, lineWidth: 1.5))
            .contentShape(Rectangle())
            .onTapGesture { gramsFocused = true }
            stepButton("plus") { set(grams + Self.step) }
        }
    }

    private func stepButton(_ symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(NT.Colors.ink)
                .frame(width: 48, height: 48)
                .background(NT.Colors.surface2, in: Circle())
        }
        .buttonStyle(PressScale())
    }

    private var portionChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                portionChip(grams: 100, label: nil)
                if let serving = food.servingSizeG, serving > 0, abs(serving - 100) > 0.5 {
                    portionChip(grams: serving, label: food.servingLabel ?? String(localized: "fuel.serving"))
                }
            }
        }
        .frame(height: 32)
    }

    private func portionChip(grams value: Double, label: String?) -> some View {
        let selected = abs(grams - value) < 0.5
        let title = label.map { "\(Fmt.grams(value)) · \($0)" } ?? Fmt.grams(value)
        return Button { set(value) } label: {
            Text(title)
                .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink).tabular().lineLimit(1)
                .padding(.horizontal, 12)
                .frame(height: 32)
                .background(selected ? NT.Colors.surface3 : NT.Colors.surface2, in: Capsule())
                .frame(minHeight: NT.Size.control)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressScale())
    }

    private var macroRow: some View {
        HStack(spacing: 18) {
            macro("macro.protein", protein)
            macro("macro.carbs", carbs)
            macro("macro.fat", fat)
            Spacer()
        }
    }

    private func macro(_ label: LocalizedStringKey, _ value: Double) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).eyebrow(NT.Colors.ink3)
            Text(Fmt.grams(value)).font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink).tabular()
        }
    }

    // MARK: - Actions

    private func set(_ value: Double) {
        let clamped = max(Self.minimum, value)
        grams = clamped
        gramsText = Self.text(for: clamped)
        gramsFocused = false
    }

    private static func text(for value: Double) -> String {
        value.rounded() == value ? String(Int(value)) : value.formatted(.number.precision(.fractionLength(1)))
    }

    private func add() {
        guard grams > 0 else { return }
        if let editing {
            editing.resize(to: grams)
            editing.slot = slot
        } else {
            let item = food.resolveItem(in: modelContext)
            let entry = MealEntry(day: day, slot: slot, food: item, grams: grams,
                                  kcal: kcal, proteinG: protein, carbsG: carbs, fatG: fat)
            modelContext.insert(entry)
        }
        try? modelContext.save()
        onAdded()
    }
}
