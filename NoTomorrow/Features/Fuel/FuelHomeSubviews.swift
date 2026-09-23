import SwiftUI

// MARK: - Hero: ring + macro bars

struct FuelHeroView: View {
    var model: FuelModel

    var body: some View {
        HStack(alignment: .center, spacing: 22) {
            ZStack {
                ProgressRing(progress: model.ringProgress, lineWidth: 10, color: NT.Colors.ember, track: NT.Colors.surface)
                    .frame(width: 132, height: 132)
                VStack(spacing: 2) {
                    Text(Fmt.kcal(model.kcalLeft, withUnit: false))
                        .font(NT.Fonts.display(44))
                        .foregroundStyle(NT.Colors.ink)
                        .tabular()
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                    Text("fuel.kcalLeft").font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink2)
                }
                .frame(width: 100)
            }
            .accessibilityElement(children: .combine)

            VStack(alignment: .leading, spacing: 12) {
                MacroBar(label: "macro.protein", value: model.proteinEaten, goal: model.goals.protein, fill: NT.Colors.ink)
                MacroBar(label: "macro.carbs", value: model.carbsEaten, goal: model.goals.carbs, fill: NT.Colors.ink2)
                MacroBar(label: "macro.fat", value: model.fatEaten, goal: model.goals.fat, fill: NT.Colors.ink2)
                Text(FuelText.format("fuel.eatenGoal",
                                     Fmt.kcal(model.kcalEaten, withUnit: false),
                                     Fmt.kcal(model.goals.kcal, withUnit: false)))
                    .font(NT.Fonts.footnote)
                    .foregroundStyle(NT.Colors.ink2)
                    .tabular()
            }
        }
    }
}

// MARK: - Meal slot rows (header, entries, empty hint, hairline)

/// One meal slot on the Fuel home. Entry rows: tap or swipe right to edit, swipe left to delete, long-press for the
/// menu. On a past day (`onLogAgain` / `onCopyToToday` set) the menu adds "Log again today" and a non-empty slot
/// header gets a "Copy to today" button.
struct FuelMealRows: View {
    var slot: MealSlot
    var entries: [MealEntry]
    var kcal: Double
    var proteinRemaining: Double
    var isLast: Bool
    var onOpen: () -> Void
    var onEdit: (MealEntry) -> Void
    var onDelete: (MealEntry) -> Void
    /// Past days only: copy one entry into the same slot today.
    var onLogAgain: ((MealEntry) -> Void)? = nil
    /// Past days only: copy the whole slot into the same slot today.
    var onCopyToToday: (() -> Void)? = nil

    private var rowInsets: EdgeInsets {
        EdgeInsets(top: 0, leading: NT.Spacing.screenH, bottom: 0, trailing: NT.Spacing.screenH)
    }

    var body: some View {
        HStack(spacing: 4) {
            Button(action: onOpen) {
                HStack(alignment: .firstTextBaseline) {
                    Text(slot.titleKey).font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink)
                    Spacer()
                    if entries.isEmpty {
                        Text("fuel.nothingYet").font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink3)
                    } else {
                        KcalLabel(kcal: kcal)
                    }
                }
                .frame(minHeight: NT.Size.control)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if let onCopyToToday, !entries.isEmpty {
                Button(action: onCopyToToday) {
                    Image(systemName: "plus.square.on.square")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(NT.Colors.ink2)
                        .frame(width: NT.Size.control, height: NT.Size.control, alignment: .trailing)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("fuel.copyToToday"))
            }
        }
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets(top: 4, leading: NT.Spacing.screenH, bottom: 0, trailing: NT.Spacing.screenH))

        ForEach(entries, id: \.id) { entry in
            Button { onEdit(entry) } label: { FuelEntryRow(entry: entry) }
                .buttonStyle(.plain)
                .contentShape(.contextMenuPreview, RoundedRectangle(cornerRadius: NT.Radius.field, style: .continuous))
                .contextMenu { entryMenu(entry) }
                .accessibilityHint(Text("common.edit"))
                .accessibilityActions {
                    if let onLogAgain {
                        Button("fuel.logAgainToday") { onLogAgain(entry) }
                    }
                }
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
                .listRowInsets(rowInsets)
                .swipeActions(edge: .leading, allowsFullSwipe: false) {
                    Button { onEdit(entry) } label: {
                        Label("common.edit", systemImage: "pencil")
                    }
                    .tint(NT.Colors.surface3)
                }
                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                    Button(role: .destructive) { onDelete(entry) } label: {
                        Label("common.delete", systemImage: "trash")
                    }
                    .tint(NT.Colors.bad)
                }
        }

        if entries.isEmpty, proteinRemaining > 0 {
            Text(FuelText.format("fuel.proteinToGo", Fmt.grams(proteinRemaining)))
                .font(NT.Fonts.footnoteBold)
                .foregroundStyle(NT.Colors.ember)
                .tabular()
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
                .listRowInsets(EdgeInsets(top: 0, leading: NT.Spacing.screenH, bottom: 8, trailing: NT.Spacing.screenH))
        }

        if !isLast {
            Hairline()
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
                .listRowInsets(EdgeInsets(top: 8, leading: NT.Spacing.screenH, bottom: 4, trailing: NT.Spacing.screenH))
        } else {
            Color.clear.frame(height: 24)
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
                .listRowInsets(rowInsets)
        }
    }

    /// Long-press menu of an entry row: Edit · Log again today (past days) · Delete.
    @ViewBuilder
    private func entryMenu(_ entry: MealEntry) -> some View {
        Button { onEdit(entry) } label: { Label("common.edit", systemImage: "pencil") }
        if let onLogAgain {
            Button { onLogAgain(entry) } label: { Label("fuel.logAgainToday", systemImage: "plus.square.on.square") }
        }
        Divider()
        Button(role: .destructive) { onDelete(entry) } label: { Label("common.delete", systemImage: "trash") }
    }
}

struct FuelEntryRow: View {
    var entry: MealEntry

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(entry.displayName).font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink).lineLimit(1)
            if entry.grams > 0 {
                Text("· \(Fmt.grams(entry.grams))").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular()
            }
            if entry.isAIEstimate {
                Badge(text: "fuel.est", color: NT.Colors.ink2)
            }
            Spacer(minLength: 8)
            Text(Fmt.kcal(entry.kcal, withUnit: false)).font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular()
            // Tells the row opens something; the text alone read as a static list.
            Image(systemName: "chevron.right")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(NT.Colors.ink3)
                .accessibilityHidden(true)
        }
        .frame(minHeight: 30)
        .contentShape(Rectangle())
    }
}

// MARK: - Add bar: three 56 pt tiles

struct FuelAddBar: View {
    var onAIPhoto: () -> Void
    var onBarcode: () -> Void
    var onSearch: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            tile("fuel.aiPhoto", symbol: "camera", primary: true, action: onAIPhoto)
            tile("fuel.barcode", symbol: "barcode.viewfinder", primary: false, action: onBarcode)
            tile("fuel.search", symbol: "magnifyingglass", primary: false, action: onSearch)
        }
        .padding(.horizontal, NT.Spacing.screenH)
        .padding(.top, 12)
        .padding(.bottom, 10)
        .background(
            LinearGradient(colors: [NT.Colors.ground.opacity(0), NT.Colors.ground, NT.Colors.ground],
                           startPoint: .top, endPoint: .bottom)
        )
    }

    private func tile(_ title: LocalizedStringKey, symbol: String, primary: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 3) {
                Image(systemName: symbol).font(.system(size: 20, weight: .regular))
                Text(title).font(NT.Fonts.caption).fontWeight(.semibold)
            }
            .foregroundStyle(primary ? NT.Colors.onPrimary : NT.Colors.ink)
            .frame(maxWidth: .infinity)
            .frame(height: NT.Size.primaryButton)
            .background(primary ? NT.Colors.ink : NT.Colors.surface,
                        in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(primary ? .clear : NT.Colors.hairline, lineWidth: 1)
            )
        }
        .buttonStyle(PressScale())
    }
}
