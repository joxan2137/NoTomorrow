import SwiftUI

/// The editable estimate: photo with detection tags, hero total, one row per food (grams, count, edit, remove),
/// "add something it missed" from the food database, details + recalculate, disclaimer, and the log bar.
struct AIScanResultView: View {
    @Bindable var model: AIScanModel
    var onLog: () -> Void

    @State private var showAddMissed = false
    @State private var editing: AIFood?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                AIScanPhoto(image: model.image, tags: model.foods.map(\.name))
                    .padding(.top, 12)
                totalBlock
                    .padding(.top, 16)
                rows
                    .padding(.top, 10)
                addMissedRow
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(model.assumptions, id: \.self) { Text($0).font(NT.Fonts.footnote) }
                    ForEach(model.questions, id: \.self) { Text($0).font(NT.Fonts.subheadline) }
                    AIMealNotes(notes: $model.notes)
                    SecondaryButton(title: "fuel.ai.refine") { model.analyze() }
                        .disabled(!model.hasItems)
                        .opacity(model.hasItems ? 1 : 0.4)
                }.padding(.vertical, 12)
                disclaimer
                    .padding(.top, 6)
            }
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .safeAreaInset(edge: .bottom) { bottomBar }
        .sheet(isPresented: $showAddMissed) {
            FoodSearchView(meal: model.meal, pick: FoodSearchPick(mode: .add, title: String(localized: "fuel.ai.addMissed")) { food, grams in
                withAnimation { model.append(AIFood.fromDatabase(food, grams: grams ?? food.servingSizeG ?? 100)) }
            })
        }
        .sheet(item: $editing) { food in
            AIScanItemSheet(food: food, meal: model.meal, onDone: { edited in
                withAnimation { model.update(edited) }
                editing = nil
            }, onRemove: {
                withAnimation { model.remove(food.id) }
                editing = nil
            })
        }
    }

    // MARK: Total

    private var totalBlock: some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 4) {
                Text("fuel.ai.estimatedTotal").eyebrow()
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(Fmt.kcal(model.totalKcal, withUnit: false))
                        .font(NT.Fonts.display(56))
                        .foregroundStyle(NT.Colors.ink)
                        .tabular()
                        .contentTransition(.numericText())
                        .animation(.easeOut(duration: 0.25), value: model.totalKcal)
                    Text(verbatim: "kcal")
                        .font(NT.Fonts.title2)
                        .foregroundStyle(NT.Colors.ink2)
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 6) {
                Text(String(format: String(localized: "fuel.ai.macrosTotal"),
                            AIScanFormat.wholeGrams(model.totalProtein),
                            AIScanFormat.wholeGrams(model.totalCarbs),
                            AIScanFormat.wholeGrams(model.totalFat)))
                    .font(NT.Fonts.footnote)
                    .foregroundStyle(NT.Colors.ink)
                    .tabular()
                HStack(spacing: 6) {
                    ConfidenceDots(filled: model.confidenceLevel.bars)
                    Text(model.confidenceLevel.labelKey)
                        .font(NT.Fonts.footnote)
                        .foregroundStyle(NT.Colors.ink2)
                }
            }
            .padding(.bottom, 6)
        }
    }

    // MARK: Rows

    private var rows: some View {
        VStack(spacing: 0) {
            ForEach(model.foods) { food in
                AIScanFoodRow(
                    food: food,
                    onScale: { model.scale(by: $0, for: food.id) },
                    onSetGrams: { model.setGrams($0, for: food.id) },
                    onStepCount: { up in model.stepCount(up: up, for: food.id) },
                    onEdit: { editing = food },
                    onRemove: { withAnimation { model.remove(food.id) } }
                )
            }
        }
    }

    private var addMissedRow: some View {
        Button { showAddMissed = true } label: {
            HStack(spacing: 6) {
                Image(systemName: "plus")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(NT.Colors.ink2)
                Text("fuel.ai.addMissed")
                    .font(NT.Fonts.subheadline)
                    .foregroundStyle(NT.Colors.ink2)
                Spacer()
            }
            .frame(height: NT.Size.control)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var disclaimer: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "info.circle")
                .font(.system(size: 14, weight: .regular))
                .foregroundStyle(NT.Colors.ink2)
                .padding(.top, 1)
            Text("fuel.ai.disclaimer")
                .font(NT.Fonts.footnote)
                .foregroundStyle(NT.Colors.ink2)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: Bottom bar

    private var bottomBar: some View {
        HStack(spacing: 10) {
            SecondaryButton(title: "fuel.ai.saveRecipe") {
                withAnimation { model.saveAsRecipe() }
            }
            .fixedSize(horizontal: true, vertical: false)

            LogToMealButton(meal: $model.meal, isEnabled: model.hasItems, action: onLog)
        }
        .padding(.horizontal, NT.Spacing.screenH)
        .padding(.top, 10)
        .padding(.bottom, 8)
        .background(NT.Colors.ground)
    }
}

// MARK: - Log to <meal ▾>

/// White pill that logs on tap and opens the slot menu on long press (and on the chevron).
struct LogToMealButton: View {
    @Binding var meal: MealSlot
    /// False once every item was removed: nothing would be logged.
    var isEnabled: Bool = true
    var action: () -> Void

    var body: some View {
        Menu {
            ForEach(MealSlot.allCases, id: \.rawValue) { slot in
                Button {
                    meal = slot
                } label: {
                    if slot == meal {
                        Label(AIScanText.slotKey(slot), systemImage: "checkmark")
                    } else {
                        Text(AIScanText.slotKey(slot))
                    }
                }
            }
        } label: {
            HStack(spacing: 6) {
                Text(String(format: String(localized: "fuel.logTo"), AIScanText.slotName(meal)))
                    .font(NT.Fonts.headline)
                    .lineLimit(1)
                Image(systemName: "chevron.down")
                    .font(.system(size: 14, weight: .bold))
            }
            .foregroundStyle(NT.Colors.onPrimary)
            .frame(maxWidth: .infinity)
            .frame(height: NT.Size.primaryButton)
            .padding(.horizontal, 16)
            .background(NT.Colors.ink, in: Capsule())
            .opacity(isEnabled ? 1 : 0.4)
        } primaryAction: {
            action()
        }
        .buttonStyle(PressScale())
        .disabled(!isEnabled)
    }
}

// MARK: - Photo with detection tags

/// 210 pt photo, 22 pt radius, with one pill per recognised food laid over a fixed anchor grid.
struct AIScanPhoto: View {
    var image: UIImage?
    var tags: [String]

    /// Anchor points as fractions of the frame, in the order tags are placed.
    private static let anchors: [CGPoint] = [
        CGPoint(x: 0.11, y: 0.13), CGPoint(x: 0.62, y: 0.21), CGPoint(x: 0.34, y: 0.78),
        CGPoint(x: 0.66, y: 0.60), CGPoint(x: 0.08, y: 0.48), CGPoint(x: 0.40, y: 0.42),
    ]

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: NT.Radius.card, style: .continuous).fill(NT.Colors.surface)
            if let image {
                GeometryReader { geo in
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: geo.size.width, height: geo.size.height)
                        .clipped()
                }
            }
            GeometryReader { geo in
                ForEach(Array(tags.prefix(Self.anchors.count).enumerated()), id: \.offset) { index, name in
                    let anchor = Self.anchors[index]
                    DetectionTag(name: name)
                        .position(x: geo.size.width * anchor.x + 50, y: geo.size.height * anchor.y + 13)
                }
            }
        }
        .frame(height: 210)
        .frame(maxWidth: .infinity)
        .clipShape(RoundedRectangle(cornerRadius: NT.Radius.card, style: .continuous))
    }
}

private struct DetectionTag: View {
    var name: String

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(NT.Colors.ember).frame(width: 6, height: 6)
            Text(name)
                .font(NT.Fonts.caption)
                .foregroundStyle(NT.Colors.ink)
                .lineLimit(1)
        }
        .padding(.horizontal, 10)
        .frame(height: 26)
        .frame(maxWidth: 150)
        .fixedSize()
        .background(NT.Colors.ground.opacity(0.85), in: Capsule())
        .overlay(Capsule().strokeBorder(NT.Colors.border, lineWidth: 1))
    }
}

// MARK: - Confidence dots

struct ConfidenceDots: View {
    var filled: Int
    var body: some View {
        HStack(spacing: 2) {
            ForEach(0..<3, id: \.self) { i in
                RoundedRectangle(cornerRadius: 2, style: .continuous)
                    .fill(i < filled ? NT.Colors.ink : NT.Colors.ink.opacity(0.2))
                    .frame(width: 10, height: 4)
            }
        }
    }
}

enum AIScanFormat {
    /// Whole-gram number without a unit, locale grouping applied.
    static func wholeGrams(_ value: Double) -> String {
        Int(value.rounded()).formatted(.number.grouping(.automatic))
    }

    /// "6 szt. × 35 g" for a counted portion; nil for a single unit or mass, where the grams cell says it all.
    static func portionBasis(_ food: AIFood, locale: Locale = Fmt.locale) -> String? {
        guard let unit = food.unitName, food.units != 1 else { return nil }
        return FuelText.format("fuel.ai.portionBasis", "\(FuelText.fieldText(food.units, locale: locale)) \(unit)",
                               FuelText.fieldText(food.unitGrams, locale: locale))
    }
}
