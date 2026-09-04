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
