import SwiftUI
import SwiftData

/// Picking a food for an AI estimate instead of logging it: nothing is written until the estimate itself is logged.
struct FoodSearchPick {
    enum Mode {
        /// "Add something it missed": the portion sheet sizes the food and adds it to the estimate.
        case add
        /// "Find in food database" for one item: a tap picks the product; the item keeps its grams (`nil` here).
        case replace
    }

    var mode: Mode
    var title: String
    var onPick: (PortionFood, _ grams: Double?) -> Void
}

/// "Add to <meal>" sheet: search field + barcode, the saved foods (recent, or matching the query), Open Food Facts
/// results, portion sheet on tap. With `pick` it hands the food back to the AI estimate instead (no quick add).
struct FoodSearchView: View {
    let meal: MealSlot
    var day: Date = .now
    var pick: FoodSearchPick? = nil

    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var modelContext
    @State private var model = FoodSearchModel()
    @State private var portionFood: PortionFood?
    @State private var showsScanner = false
    @State private var showsQuickAdd = false
    @State private var quickAddName = ""
    @State private var barcodeFlow = BarcodeLookupFlow()
    @FocusState private var searchFocused: Bool

    /// The whole saved-food library, most recently used first (never-used labels last).
    @Query(sort: \FoodItem.lastUsedAt, order: .reverse)
    private var savedItems: [FoodItem]

    /// No query: the 10 most recently used foods. A query: saved foods whose name or brand contains every typed word,
    /// ignoring case and Polish diacritics, so they stay reachable offline and when Open Food Facts is down.
    private var recent: [FoodItem] {
        let q = model.trimmedQuery
        let matches = q.isEmpty
            ? savedItems.lazy.filter { $0.lastUsedAt != nil }
            : savedItems.lazy.filter { FoodMatch.matches(q, name: $0.name, brand: $0.brand) }
        return Array(matches.prefix(10))
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            searchRow.padding(.top, 12)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    content
                }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.top, 16)
                .padding(.bottom, 40)
            }
            .scrollDismissesKeyboard(.immediately)
        }
        .ntScreenBackground()
        .onAppear { searchFocused = true }
        .onChange(of: model.query) { _, _ in model.queryChanged() }
        .sheet(item: $portionFood) { food in
            if let pick {
                PortionSheet(picking: food) { grams in
                    portionFood = nil
                    pick.onPick(food, grams)
                    dismiss()
                }
            } else {
                PortionSheet(food: food, meal: meal, day: day) {
                    portionFood = nil
                    dismiss()
                }
            }
        }
        // The lookup starts once the scanner has gone, so its portion sheet or alert can present.
        .sheet(isPresented: $showsScanner, onDismiss: {
            barcodeFlow.startPending(in: modelContext) { select($0) }
        }) {
            BarcodeScannerView { code in
                barcodeFlow.pendingCode = code
                showsScanner = false
            }
        }
        .sheet(isPresented: $showsQuickAdd) {
            QuickAddSheet(meal: meal, day: day, initialName: quickAddName) {
                showsQuickAdd = false
                dismiss()
            }
        }
        .barcodeLookupFlow(barcodeFlow, onFood: { select($0) }, onQuickAdd: pick == nil ? openQuickAdd : nil)
        .overlay(alignment: .bottom) {
            if barcodeFlow.isLookingUp {
                HStack(spacing: 10) {
                    ProgressView().tint(NT.Colors.ink)
                    Text("fuel.lookingUp").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink)
                }
                .padding(.horizontal, 16)
                .frame(height: 40)
                .background(NT.Colors.surface2, in: Capsule())
                .padding(.bottom, 24)
            }
        }
    }

    // MARK: - Header + search

    private var header: some View {
        HStack {
            Text(pick?.title ?? FuelText.addTo(meal)).font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink).lineLimit(1)
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

    private var searchRow: some View {
        HStack(spacing: 10) {
            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(NT.Colors.ink2)
                TextField("fuel.searchProduct", text: $model.query)
                    .font(NT.Fonts.body)
                    .foregroundStyle(NT.Colors.ink)
                    .tint(NT.Colors.ink)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.search)
                    .focused($searchFocused)
                    .onSubmit { model.retry() }
                if !model.query.isEmpty {
                    Button { model.query = "" } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 9, weight: .heavy))
                            .foregroundStyle(NT.Colors.ground)
                            .frame(width: 20, height: 20)
                            .background(NT.Colors.ink3, in: Circle())
                            .frame(width: 32, height: NT.Size.control)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 14)
            .frame(height: NT.Size.control)
            .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.field, style: .continuous))

            Button { showsScanner = true } label: {
                Image(systemName: "barcode.viewfinder")
                    .font(.system(size: 20, weight: .regular))
                    .foregroundStyle(NT.Colors.ink)
                    .frame(width: NT.Size.control, height: NT.Size.control)
                    .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.field, style: .continuous))
            }
            .buttonStyle(PressScale())
            .accessibilityLabel(Text("fuel.barcode"))
        }
        .padding(.horizontal, NT.Spacing.screenH)
    }

    // MARK: - Content

    @ViewBuilder
    private var content: some View {
        if !recent.isEmpty {
            FoodSectionLabel(text: String(localized: model.trimmedQuery.isEmpty ? "fuel.recent" : "fuel.yourFoods"))
            ForEach(recent, id: \.id) { item in
                FoodRecentRow(item: item) { select(.item(item)) }
            }
        }

        if model.canSearch {
            switch model.phase {
            case .idle, .loading:
                FoodSectionLabel(text: String(localized: "fuel.products")).padding(.top, recent.isEmpty ? 0 : 16)
                FoodStateRow(kind: .loading)
            case .results(let found):
                // A product saved already is listed once, in the section above.
                let hits = FoodSearchModel.remoteHits(found, excluding: Set(recent.map(\.id)))
                if !hits.isEmpty {
                    FoodSectionLabel(text: "\(String(localized: "fuel.products")) · \(FuelText.format("fuel.found", hits.count))")
                        .padding(.top, recent.isEmpty ? 0 : 16)
                    // Its own identities: a saved food and an Open Food Facts hit share "off:<code>", and two rows
                    // with one identity in this lazy stack rendered one of them blank.
                    ForEach(hits, id: \.remoteRowID) { hit in
                        FoodResultRow(candidate: hit) { select(.candidate(hit)) }
                    }
                }
            case .empty:
                FoodStateRow(kind: .notFound, onQuickAdd: quickAddAction)
                    .padding(.top, recent.isEmpty ? 0 : 16)
            case .error(let message):
                FoodStateRow(kind: .error(message), onRetry: { model.retry() }, onQuickAdd: quickAddAction)
                    .padding(.top, recent.isEmpty ? 0 : 16)
            }
        } else if recent.isEmpty {
            FoodStateRow(kind: .hint, onQuickAdd: quickAddAction)
        }
    }

    /// A tapped food: the portion sheet, or straight back to the estimate when replacing an item.
    private func select(_ food: PortionFood) {
        if let pick, pick.mode == .replace {
            pick.onPick(food, nil)
            dismiss()
        } else {
            portionFood = food
        }
    }

    /// Quick add logs a custom row, which has no place in a pick for the AI estimate.
    private var quickAddAction: (() -> Void)? {
        pick == nil ? { openQuickAdd(model.trimmedQuery) } : nil
    }

    private func openQuickAdd(_ name: String) {
        quickAddName = name
        showsQuickAdd = true
    }
}

private extension FoodCandidate {
    /// Row identity in the results list, apart from the saved foods' ids in the same stack.
    var remoteRowID: String { "remote|\(id)" }
}
