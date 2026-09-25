import SwiftUI
import WidgetKit

/// Quick log: kcal left as the Fuel ring, and the foods you eat most, each one tap from being logged
/// (`LogQuickFoodIntent`, no app launch). `docs/widgets.md`, "Quick log".
struct QuickLogWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetKind.fuel, provider: SnapshotProvider(extraDates: Self.checkExpiry)) { entry in
            QuickLogView(entry: entry)
                .widgetGround()
                .widgetURL(URL(string: "notomorrow://fuel"))
        }
        .configurationDisplayName("widget.fuel.name")
        .description("widget.fuel.description")
        .supportedFamilies([.systemSmall, .systemMedium])
    }

    /// How long a row shows the "Logged" check after its tap.
    static let checkSeconds: TimeInterval = 4

    /// Re-render when the check should go away.
    static func checkExpiry(_ snapshot: WidgetSnapshot?, _ now: Date) -> [Date] {
        guard let at = snapshot?.lastLogged?.at else { return [] }
        return [at.addingTimeInterval(checkSeconds)]
    }
}

struct QuickLogView: View {
    let entry: SnapshotEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        if let snapshot = entry.snapshot, entry.isReady {
            let fuel = snapshot.fuelToday(at: entry.date)
            switch family {
            case .systemMedium: medium(snapshot, fuel)
            default: small(snapshot, fuel)
            }
        } else {
            WidgetSetupView()
        }
    }

    // MARK: Sizes

    private func small(_ snapshot: WidgetSnapshot, _ fuel: WidgetSnapshot.Fuel) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(verbatim: WidgetText.string("fuel.kcalLeft")).eyebrowStyle()
            Spacer(minLength: 6)
            HStack(spacing: 12) {
                ring(fuel, size: 70, line: 7, number: 22)
                VStack(alignment: .leading, spacing: 4) {
                    macroEaten(W.protein, fuel.protein)
                    macroEaten(W.carbs, fuel.carbs)
                    macroEaten(W.fat, fuel.fat)
                }
            }
            Spacer(minLength: 8)
            if let food = snapshot.quickFoods.first {
                Button(intent: LogQuickFoodIntent(key: food.key)) {
                    smallButtonFace(food, logged: isJustLogged(food, snapshot))
                }
                .buttonStyle(.plain)
            } else {
                Text(verbatim: WidgetText.string("widget.fuel.empty"))
                    .font(W.caption).foregroundStyle(W.ink2).lineLimit(2)
            }
        }
    }

    private func medium(_ snapshot: WidgetSnapshot, _ fuel: WidgetSnapshot.Fuel) -> some View {
        HStack(alignment: .center, spacing: 16) {
            VStack(alignment: .center, spacing: 6) {
                ring(fuel, size: 92, line: 8, number: 30, showsLeft: true)
                Text(verbatim: WidgetText.format("fuel.eatenGoal", WidgetText.kcal(fuel.kcal), WidgetText.kcal(fuel.kcalGoal)))
                    .font(W.caption).foregroundStyle(W.ink2).monospacedDigit()
                    .lineLimit(1).minimumScaleFactor(0.7)
            }
            .frame(width: 118)

            VStack(alignment: .leading, spacing: 0) {
                let foods = Array(snapshot.quickFoods.prefix(3))
                if foods.isEmpty {
                    Text(verbatim: WidgetText.string("widget.fuel.empty"))
                        .font(W.footnote).foregroundStyle(W.ink2)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                } else {
                    ForEach(Array(foods.enumerated()), id: \.element.key) { index, food in
                        if index > 0 { Rectangle().fill(W.hairline).frame(height: 1) }
                        Button(intent: LogQuickFoodIntent(key: food.key)) {
                            row(food, logged: isJustLogged(food, snapshot))
                        }
                        .buttonStyle(.plain)
                    }
                    Spacer(minLength: 0)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
    }

    // MARK: Parts

    private func ring(_ fuel: WidgetSnapshot.Fuel, size: CGFloat, line: CGFloat, number: CGFloat,
                      showsLeft: Bool = false) -> some View {
        ZStack {
            WidgetMacroRing(protein: fuel.protein, carbs: fuel.carbs, fat: fuel.fat, kcalGoal: fuel.kcalGoal, lineWidth: line)
            VStack(spacing: -2) {
                Text(verbatim: WidgetText.kcal(max(0, fuel.kcalGoal - fuel.kcal)))
                    .font(W.display(number)).monospacedDigit().foregroundStyle(W.ink)
                    .lineLimit(1).minimumScaleFactor(0.5)
                if showsLeft {
                    Text(verbatim: WidgetText.string("dashboard.left")).font(W.caption).foregroundStyle(W.ink2)
                }
            }
            .padding(.horizontal, line + 4)
        }
        .frame(width: size, height: size)
    }

    /// "● 62 g" — grams eaten of one macro, in its hue.
    private func macroEaten(_ color: Color, _ eaten: Double) -> some View {
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 6, height: 6)
            Text(verbatim: WidgetText.grams(eaten))
                .font(W.caption).monospacedDigit().foregroundStyle(W.ink).lineLimit(1)
        }
    }

    private func smallButtonFace(_ food: QuickFood, logged: Bool) -> some View {
        HStack(spacing: 6) {
            Image(systemName: logged ? "checkmark" : "plus")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(logged ? W.good : W.ink)
            Text(verbatim: logged ? WidgetText.string("widget.fuel.logged") : food.name)
                .font(W.caption.weight(.semibold)).foregroundStyle(W.ink).lineLimit(1)
            Spacer(minLength: 2)
            Text(verbatim: WidgetText.kcal(food.kcal))
                .font(W.caption).monospacedDigit().foregroundStyle(W.ink2)
        }
        .padding(.horizontal, 12)
        .frame(height: 36)
        .background(Capsule().fill(W.surface2))
    }

    private func row(_ food: QuickFood, logged: Bool) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: food.name).font(W.subheadlineBold).foregroundStyle(W.ink).lineLimit(1)
                Text(verbatim: logged ? WidgetText.string("widget.fuel.logged")
                                      : "\(WidgetText.grams(food.grams)) · \(WidgetText.kcal(food.kcal))\u{00A0}kcal")
                    .font(W.footnote).monospacedDigit().foregroundStyle(logged ? W.good : W.ink2).lineLimit(1)
            }
            Spacer(minLength: 4)
            ZStack {
                Circle().fill(logged ? W.good.opacity(0.18) : W.surface2)
                Image(systemName: logged ? "checkmark" : "plus")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(logged ? W.good : W.ink)
            }
            .frame(width: 30, height: 30)
        }
        .frame(maxWidth: .infinity, minHeight: 40, alignment: .leading)
        .contentShape(Rectangle())
    }

    private func isJustLogged(_ food: QuickFood, _ snapshot: WidgetSnapshot) -> Bool {
        guard let last = snapshot.lastLogged, last.key == food.key else { return false }
        let age = entry.date.timeIntervalSince(last.at)
        return age >= -1 && age < QuickLogWidget.checkSeconds
    }
}
