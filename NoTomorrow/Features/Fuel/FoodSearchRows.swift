import SwiftUI

// Rows and states for FoodSearchView, per design/FoodSearch.dc.html: 62 pt rows with a hairline,
// name / detail on the left, kcal and a 32 pt "+" (44 pt hit box) on the right.

struct FoodSectionLabel: View {
    var text: String
    var body: some View {
        Text(text).eyebrow(NT.Colors.ink3).padding(.bottom, 4)
    }
}

struct FoodResultRow: View {
    var candidate: FoodCandidate
    var onAdd: () -> Void

    private var detail: String {
        var parts: [String] = []
        if let brand = candidate.brand { parts.append(brand) }
        parts.append(String(localized: "fuel.per100"))
        parts.append(FuelText.format("fuel.proteinShort", Fmt.grams(candidate.proteinPer100)))
        return parts.joined(separator: " · ")
    }

    var body: some View {
        FoodRow(name: candidate.name, detail: detail, kcal: candidate.kcalPer100, onAdd: onAdd)
    }
}

struct FoodRecentRow: View {
    var item: FoodItem
    var onAdd: () -> Void

    private var amount: Double { item.servingSizeG ?? 100 }

    private var detail: String {
        var parts: [String] = []
        if let brand = item.brand { parts.append(brand) }
        parts.append(Fmt.grams(amount))
        return parts.joined(separator: " · ")
    }

    var body: some View {
        FoodRow(name: item.name, detail: detail, kcal: item.kcalPer100 * amount / 100, onAdd: onAdd)
    }
}

struct FoodRow: View {
    var name: String
    var detail: String
    var kcal: Double
    var onAdd: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onAdd) {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(name).font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).lineLimit(1)
                        Text(detail).font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    KcalLabel(kcal: kcal)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .frame(height: 62)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Button(action: onAdd) {
                Image(systemName: "plus")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(NT.Colors.ink)
                    .frame(width: 32, height: 32)
                    .background(NT.Colors.surface2, in: Circle())
                    .frame(width: NT.Size.control, height: NT.Size.control)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(Text("fuel.quickAdd"))
            .padding(.trailing, -6)
        }
        .overlay(alignment: .bottom) { Hairline() }
    }
}

/// Loading / not found / error / idle-hint rows. Empty states carry their action.
struct FoodStateRow: View {
    enum Kind: Equatable {
        case loading
        case notFound
        case error(String)
        case hint
    }

    var kind: Kind
    var onRetry: (() -> Void)? = nil
    var onQuickAdd: (() -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            switch kind {
            case .loading:
                HStack(spacing: 10) {
                    ProgressView().tint(NT.Colors.ink2)
                    Text("fuel.searching").font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                }
                .frame(height: 62)
            case .notFound:
                Text("fuel.notFound").font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                    .padding(.top, 12)
                if let onQuickAdd { GhostButton(title: "fuel.quickAdd", systemImage: "plus", action: onQuickAdd) }
            case .error(let message):
                Text(message).font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                    .padding(.top, 12)
                HStack(spacing: 10) {
                    if let onRetry { GhostButton(title: "fuel.search.retry", action: onRetry) }
                    if let onQuickAdd { GhostButton(title: "fuel.quickAdd", systemImage: "plus", action: onQuickAdd) }
                }
            case .hint:
                Text("fuel.search.hint").font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                    .padding(.top, 12)
                if let onQuickAdd { GhostButton(title: "fuel.quickAdd", systemImage: "plus", action: onQuickAdd) }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
