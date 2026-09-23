import SwiftUI
import SwiftData

// Shared helpers for the Fuel feature: meal slot naming, the food payload a PortionSheet accepts,
// and a couple of localisation shortcuts for catalog keys that carry format arguments.

extension MealSlot {
    /// Display order on the Fuel home and in menus.
    static let ordered: [MealSlot] = [.breakfast, .lunch, .snack, .dinner]

    var titleKey: LocalizedStringKey { LocalizedStringKey("meal." + rawValue) }

    var localizedName: String { String(localized: String.LocalizationValue("meal." + rawValue)) }

    /// The slot the bottom bar targets when the user has not picked one: by clock time.
    static func suggested(at date: Date = .now) -> MealSlot {
        let hour = Calendar.current.component(.hour, from: date)
        switch hour {
        case ..<11: return .breakfast
        case ..<15: return .lunch
        case ..<18: return .snack
        default: return .dinner
        }
    }
}

extension FoodSource {
    var labelKey: LocalizedStringKey { LocalizedStringKey("fuel.source." + rawValue) }
}

enum FuelText {
    /// `String(format:)` over a catalog key whose value carries `%@` / `%lld` placeholders.
    static func format(_ key: String, _ args: CVarArg...) -> String {
        String(format: String(localized: String.LocalizationValue(key)), locale: .current, arguments: args)
    }

    /// "Add to Lunch" / "Dodaj do: Obiad"
    static func addTo(_ meal: MealSlot) -> String { format("fuel.addTo", meal.localizedName) }

    /// Wraps an already-localised string so components that take a `LocalizedStringKey` show it verbatim.
    static func verbatim(_ text: String) -> LocalizedStringKey { "\(text)" }

    /// A figure as it should appear in an editable field: at most one decimal in the user's locale and never grouped,
    /// so it parses back through `NumberInput` ("1234,5", not "1 234,5" or "1,234.5").
    static func fieldText(_ value: Double, locale: Locale = Fmt.locale) -> String {
        value.formatted(.number.precision(.fractionLength(0...1)).grouping(.never).locale(locale))
    }

    /// The value of an edit field that was prefilled from a stored figure. The field shows the figure rounded, so text
    /// the user left alone keeps the exact figure; anything else is parsed (nil when it is not a number).
    static func editedFigure(_ text: String, prefill: String, original: Double) -> Double? {
        text == prefill ? original : NumberInput.nonNegative(text)
    }

    static var locale: String {
        Locale.current.language.languageCode?.identifier ?? "en"
    }
}

/// What a `PortionSheet` is sizing: a fresh Open Food Facts hit, or a food already in the library.
enum PortionFood: Identifiable {
    case candidate(FoodCandidate)
    case item(FoodItem)

    var id: String {
        switch self {
        case .candidate(let c): c.id
        case .item(let i): i.id
        }
    }
    var name: String {
        switch self {
        case .candidate(let c): c.name
        case .item(let i): i.name
        }
    }
    var brand: String? {
        switch self {
        case .candidate(let c): c.brand
        case .item(let i): i.brand
        }
    }
    var source: FoodSource {
        switch self {
        case .candidate: .openFoodFacts
        case .item(let i): i.source
        }
    }
    var kcalPer100: Double {
        switch self {
        case .candidate(let c): c.kcalPer100
        case .item(let i): i.kcalPer100
        }
    }
    var proteinPer100: Double {
        switch self {
        case .candidate(let c): c.proteinPer100
        case .item(let i): i.proteinPer100
        }
    }
    var carbsPer100: Double {
        switch self {
        case .candidate(let c): c.carbsPer100
        case .item(let i): i.carbsPer100
        }
    }
    var fatPer100: Double {
        switch self {
        case .candidate(let c): c.fatPer100
        case .item(let i): i.fatPer100
        }
    }
    var servingSizeG: Double? {
        switch self {
        case .candidate(let c): c.servingSizeG
        case .item(let i): i.servingSizeG
        }
    }
    var servingLabel: String? {
        switch self {
        case .candidate(let c): c.servingLabel
        case .item(let i): i.servingLabel
        }
    }
    /// Open Food Facts estimated the figures from the ingredients; the portion sheet says so.
    var isEstimated: Bool {
        switch self {
        case .candidate(let c): c.isEstimated
        case .item: false
        }
    }

    /// Returns the persisted `FoodItem` for this food, inserting it on first use, and bumps its usage stats.
    func resolveItem(in context: ModelContext) -> FoodItem {
        let item: FoodItem
        switch self {
        case .item(let existing):
            item = existing
        case .candidate(let candidate):
            let id = candidate.id
            let descriptor = FetchDescriptor<FoodItem>(predicate: #Predicate { $0.id == id })
            if let existing = try? context.fetch(descriptor).first {
                item = existing
            } else {
                item = candidate.makeFoodItem()
                context.insert(item)
            }
        }
        item.useCount += 1
        item.lastUsedAt = .now
        return item
    }
}

/// Small "N kcal" pair used in rows: number in ink, unit in ink2.
struct KcalLabel: View {
    var kcal: Double
    var numberFont: Font = NT.Fonts.subheadline

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 3) {
            Text(Fmt.kcal(kcal, withUnit: false)).font(numberFont).foregroundStyle(NT.Colors.ink).tabular()
            Text("unit.kcal").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
        }
    }
}

// MARK: - Editing a logged entry

extension MealEntry {
    /// Re-sizes a food-backed entry: the macros follow the food's per-100 g figures, like `PortionSheet` on insert.
    /// No-op for custom entries (nothing to derive from).
    func resize(to newGrams: Double) {
        guard let food else { return }
        let factor = newGrams / 100
        grams = newGrams
        kcal = food.kcalPer100 * factor
        proteinG = food.proteinPer100 * factor
        carbsG = food.carbsPer100 * factor
        fatG = food.fatPer100 * factor
    }

    /// Overwrites a custom (quick-add / AI) entry with the user's figures. Once any number is changed the row is
    /// no longer an estimate, so the AI badge and confidence are dropped; a rename alone keeps them.
    func overwrite(name: String, grams newGrams: Double, kcal newKcal: Double,
                   proteinG newProtein: Double, carbsG newCarbs: Double, fatG newFat: Double) {
        let figuresChanged = grams != newGrams || kcal != newKcal
            || proteinG != newProtein || carbsG != newCarbs || fatG != newFat
        customName = name
        grams = newGrams
        kcal = newKcal
        proteinG = newProtein
        carbsG = newCarbs
        fatG = newFat
        if figuresChanged {
            isAIEstimate = false
            confidence = nil
        }
    }

    /// Moves the entry to another calendar day. Slot, figures and `loggedAt` stay: the slot is the "time" the UI shows,
    /// and the row sorts by `loggedAt` inside it. Picking the day it is already listed under changes nothing, so a
    /// midnight stored in another time zone is not rewritten.
    func move(to newDay: Date, calendar: Calendar = .current) {
        let target = calendar.startOfDay(for: newDay)
        guard FuelCalendar.dayKey(day, calendar: calendar) != target else { return }
        day = target
    }

    /// A new entry with this one's slot, food, name and figures (AI flags included) on the calendar day of `newDay`
    /// in `calendar`, logged at `loggedAt`. Pass a moment of that day (e.g. now), not a midnight computed elsewhere:
    /// the day is taken once, here, so a midnight from another zone cannot slide onto the day before.
    func copy(to newDay: Date, loggedAt: Date = .now, calendar: Calendar = .current) -> MealEntry {
        let copy = MealEntry(day: newDay, slot: slot, food: food, customName: customName, grams: grams, kcal: kcal,
                             proteinG: proteinG, carbsG: carbsG, fatG: fatG,
                             isAIEstimate: isAIEstimate, confidence: confidence)
        copy.day = calendar.startOfDay(for: newDay)   // the init reads Calendar.current; the caller's calendar wins
        copy.loggedAt = loggedAt
        return copy
    }

    /// Every stored property, so a delete can be undone with the same id, day and row order.
    struct Snapshot {
        let id: UUID
        let day: Date
        let slot: MealSlot
        let food: FoodItem?
        let customName: String?
        let grams: Double
        let kcal: Double
        let proteinG: Double
        let carbsG: Double
        let fatG: Double
        let isAIEstimate: Bool
        let confidence: Double?
        let loggedAt: Date
        /// The name the row showed, for the rare restore whose food was deleted in the meantime.
        let displayName: String

        /// The entry again, ready to insert. A food deleted in the meantime is dropped; the row keeps its name and figures.
        func restore() -> MealEntry {
            let liveFood = food?.modelContext == nil ? nil : food
            let name = food != nil && liveFood == nil ? displayName : customName
            let entry = MealEntry(day: day, slot: slot, food: liveFood, customName: name,
                                  grams: grams, kcal: kcal, proteinG: proteinG, carbsG: carbsG, fatG: fatG,
                                  isAIEstimate: isAIEstimate, confidence: confidence)
            entry.id = id
            entry.day = day   // exact: the init normalises to this zone's midnight
            entry.loggedAt = loggedAt
            return entry
        }
    }

    var snapshot: Snapshot {
        Snapshot(id: id, day: day, slot: slot, food: food, customName: customName, grams: grams, kcal: kcal,
                 proteinG: proteinG, carbsG: carbsG, fatG: fatG, isAIEstimate: isAIEstimate, confidence: confidence,
                 loggedAt: loggedAt, displayName: displayName)
    }

    /// What the quick-add edit sheet prefills: grams only when the row has a portion (AI rows), figures ungrouped.
    struct EditTexts: Equatable {
        var grams: String
        var kcal: String
        var protein: String
        var carbs: String
        var fat: String
    }

    func editTexts(locale: Locale = Fmt.locale) -> EditTexts {
        EditTexts(grams: grams > 0 ? FuelText.fieldText(grams, locale: locale) : "",
                  kcal: FuelText.fieldText(kcal, locale: locale),
                  protein: FuelText.fieldText(proteinG, locale: locale),
                  carbs: FuelText.fieldText(carbsG, locale: locale),
                  fat: FuelText.fieldText(fatG, locale: locale))
    }

    struct Figures: Equatable {
        var kcal: Double
        var protein: Double
        var carbs: Double
        var fat: Double
    }

    /// This entry's kcal and macros at `newGrams`, keeping its figures per gram: an AI or quick-add row whose portion
    /// is weighed later scales like a food would. Nil when the row has no portion to scale from.
    func figures(atGrams newGrams: Double) -> Figures? {
        guard grams > 0, newGrams > 0 else { return nil }
        let factor = newGrams / grams
        return Figures(kcal: kcal * factor, protein: proteinG * factor, carbs: carbsG * factor, fat: fatG * factor)
    }

    /// The edit sheet's figure texts after its grams field changed to `gramsText`: rescaled from this entry, or the
    /// prefill again when the grams are back at theirs. Nil when the grams are not a positive number (leave the fields).
    func rescaledTexts(gramsText: String, prefill: EditTexts, locale: Locale = Fmt.locale) -> EditTexts? {
        if gramsText == prefill.grams { return prefill }
        guard let newGrams = NumberInput.nonNegative(gramsText), let f = figures(atGrams: newGrams) else { return nil }
        return EditTexts(grams: gramsText,
                         kcal: FuelText.fieldText(f.kcal, locale: locale),
                         protein: FuelText.fieldText(f.protein, locale: locale),
                         carbs: FuelText.fieldText(f.carbs, locale: locale),
                         fat: FuelText.fieldText(f.fat, locale: locale))
    }

    /// Whether the quick-add edit sheet can save this custom (quick-add / AI) row: a name and a kcal figure that
    /// parses. 0 kcal is fine here, unlike a new quick add: AI logging keeps 0 kcal rows that have a portion (water,
    /// black coffee), and they must still be movable, re-slottable and renamable.
    func canSaveEdit(name: String, kcalText: String, prefill: EditTexts) -> Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty
            && FuelText.editedFigure(kcalText, prefill: prefill.kcal, original: kcal) != nil
    }

    /// The quick-add edit sheet's Save on this custom row. Fields still showing their prefill keep the exact figures;
    /// new grams with untouched figures save the exact rescale. Then the slot and the day move. Returns false and
    /// writes nothing when `canSaveEdit` says no.
    @discardableResult
    func applyEdit(name: String, texts: EditTexts, prefill: EditTexts, figuresUntouched: Bool,
                   slot newSlot: MealSlot, day newDay: Date, calendar: Calendar = .current) -> Bool {
        let figure = FuelText.editedFigure
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, let newKcal = figure(texts.kcal, prefill.kcal, kcal) else { return false }
        let hasPortion = grams > 0
        let newGrams = hasPortion ? (figure(texts.grams, prefill.grams, grams) ?? grams) : 0
        if hasPortion, newGrams != grams, figuresUntouched, let exact = figures(atGrams: newGrams) {
            // The fields show the rescale rounded; save it exact.
            overwrite(name: trimmed, grams: newGrams, kcal: exact.kcal, proteinG: exact.protein,
                      carbsG: exact.carbs, fatG: exact.fat)
        } else {
            overwrite(name: trimmed, grams: newGrams, kcal: newKcal,
                      proteinG: figure(texts.protein, prefill.protein, proteinG) ?? 0,
                      carbsG: figure(texts.carbs, prefill.carbs, carbsG) ?? 0,
                      fatG: figure(texts.fat, prefill.fat, fatG) ?? 0)
        }
        slot = newSlot
        move(to: newDay, calendar: calendar)
        return true
    }
}

extension MealEntry.EditTexts {
    /// Same kcal and macro texts (grams ignored): the user has not typed over what the sheet filled in.
    func sameFigures(as other: MealEntry.EditTexts) -> Bool {
        kcal == other.kcal && protein == other.protein && carbs == other.carbs && fat == other.fat
    }
}

/// Four capsules (Breakfast · Lunch · Snack · Dinner), the selected one on surface3. The edit sheets use it to move an entry.
struct MealSlotPicker: View {
    @Binding var slot: MealSlot

    var body: some View {
        HStack(spacing: 8) {
            ForEach(MealSlot.ordered, id: \.rawValue) { candidate in
                let selected = candidate == slot
                Button { slot = candidate } label: {
                    Text(candidate.titleKey)
                        .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink).lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .frame(maxWidth: .infinity)
                        .frame(height: 32)
                        .background(selected ? NT.Colors.surface3 : NT.Colors.surface2, in: Capsule())
                        .contentShape(Capsule())
                }
                .buttonStyle(PressScale())
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .accessibilityLabel(Text("fuel.mealSlot"))
    }
}

/// "Day   ‹ Yesterday ›": moves an entry to another day from the edit sheets. Past days only, so `›` stops at today.
/// VoiceOver reads one adjustable element (swipe up = next day).
struct EntryDayStepper: View {
    @Binding var day: Date
    var today: Date = Calendar.current.startOfDay(for: .now)
    var height: CGFloat = NT.Size.control
    var background: Color = NT.Colors.surface2

    var body: some View {
        HStack(spacing: 0) {
            Text("fuel.day").font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
            Spacer(minLength: 8)
            chevron("chevron.left", enabled: true) { day = Self.step(day, by: -1, today: today) }
            Text(Fmt.dayTitle(day, now: today))
                .font(NT.Fonts.footnoteBold).foregroundStyle(NT.Colors.ink)
                .lineLimit(1).minimumScaleFactor(0.8)
                .frame(minWidth: 88)
            chevron("chevron.right", enabled: day < today) { day = Self.step(day, by: 1, today: today) }
        }
        .padding(.leading, 14)
        .frame(height: height)
        .background(background, in: RoundedRectangle(cornerRadius: NT.Radius.field, style: .continuous))
        .sensoryFeedback(.selection, trigger: day)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("fuel.day"))
        .accessibilityValue(Text(Fmt.longDay(day)))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: day = Self.step(day, by: 1, today: today)
            case .decrement: day = Self.step(day, by: -1, today: today)
            @unknown default: break
            }
        }
    }

    private func chevron(_ symbol: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(enabled ? NT.Colors.ink : NT.Colors.ink3.opacity(0.4))
                .frame(width: NT.Size.control, height: NT.Size.control)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }

    /// `days` from `day`, as a start of day, never past `today`.
    static func step(_ day: Date, by days: Int, today: Date, calendar: Calendar = .current) -> Date {
        let start = calendar.startOfDay(for: day)
        let moved = calendar.date(byAdding: .day, value: days, to: start) ?? start
        return min(calendar.startOfDay(for: moved), calendar.startOfDay(for: today))
    }
}
