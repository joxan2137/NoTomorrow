import SwiftUI
import WidgetKit

/// A timeline entry that carries the snapshot (nil: no App Group, or the app has not published one yet).
struct SnapshotEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot?

    /// What every widget shows before the app has run once (and in the gallery when there is no data).
    var isReady: Bool { snapshot?.hasProfile == true }

    static func load(at date: Date = .now) -> SnapshotEntry {
        let snapshot = WidgetStore.readSnapshot()
        WidgetText.languageOverride = snapshot?.languageOverride
        return SnapshotEntry(date: date, snapshot: snapshot)
    }
}

/// `widget.setup`, centred: the state of a widget with nothing to show yet.
struct WidgetSetupView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(verbatim: "NO TOMORROW").font(W.eyebrow).tracking(0.9).foregroundStyle(W.ember)
            Text(verbatim: WidgetText.string("widget.setup"))
                .font(W.footnote).foregroundStyle(W.ink2)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

/// The app's `MacroRing`: a track, and the eaten kcal as protein / carbs / fat arcs (4/4/9 kcal per gram) with round
/// caps and a small gap between them, clockwise from 12 o'clock.
struct WidgetMacroRing: View {
    var protein: Double
    var carbs: Double
    var fat: Double
    var kcalGoal: Double
    var lineWidth: CGFloat

    private struct Arc { var start: Double; var end: Double; var color: Color }

    private func arcs(gap: Double) -> [Arc] {
        guard kcalGoal > 0 else { return [] }
        let shares = [protein * 4, carbs * 4, fat * 9].map { max(0, $0) / kcalGoal }
        let total = shares.reduce(0, +)
        let scale = total > 1 ? 1 / total : 1
        let colors = [W.protein, W.carbs, W.fat]
        var arcs: [Arc] = []
        var cursor = 0.0
        for (index, share) in shares.enumerated() {
            let length = share * scale
            if length > gap { arcs.append(Arc(start: cursor, end: cursor + length - gap, color: colors[index])) }
            cursor += length
        }
        return arcs
    }

    var body: some View {
        GeometryReader { geo in
            let circumference = Double.pi * min(geo.size.width, geo.size.height)
            let gap = circumference > 0 ? Double(lineWidth + 3) / circumference : 0
            ZStack {
                Circle().stroke(W.track, lineWidth: lineWidth)
                ForEach(Array(arcs(gap: gap).enumerated()), id: \.offset) { _, arc in
                    Circle()
                        .trim(from: arc.start, to: arc.end)
                        .stroke(arc.color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                }
            }
            .padding(lineWidth / 2)
        }
    }
}

/// A capsule button face: `surface2` with `ink` text, or white with `ground` text for the primary one.
struct CapsuleFace: View {
    var title: String
    var primary = false
    var height: CGFloat = 36

    var body: some View {
        Text(verbatim: title)
            .font(W.subheadlineBold).monospacedDigit()
            .lineLimit(1).minimumScaleFactor(0.7)
            .foregroundStyle(primary ? W.ground : W.ink)
            .frame(maxWidth: .infinity, minHeight: height, maxHeight: height)
            .background(Capsule().fill(primary ? W.ink : W.surface2))
    }
}

extension WidgetSnapshot {
    /// Today's totals as of `date`: a snapshot from an earlier day has eaten nothing yet today.
    func fuelToday(at date: Date) -> Fuel {
        let today = Calendar.current.startOfDay(for: date)
        guard fuel.day == today else {
            return Fuel(day: today, kcal: 0, protein: 0, carbs: 0, fat: 0, kcalGoal: fuel.kcalGoal)
        }
        return fuel
    }
}
