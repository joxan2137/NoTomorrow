import SwiftUI
import WidgetKit

/// Fuel calendar: the Fuel history as a GitHub-style contribution graph under one label, with a dot on every day you
/// trained.
/// `docs/widgets.md`, "Fuel calendar".
struct FuelCalendarWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetKind.history, provider: SnapshotProvider()) { entry in
            FuelCalendarView(entry: entry)
                .widgetGround()
                .widgetURL(URL(string: "notomorrow://fuel"))
        }
        .configurationDisplayName("widget.history.name")
        .description("widget.history.description")
        .supportedFamilies([.systemMedium, .systemLarge])
    }
}

/// The grid's data for one render: levels and trained flags by day.
struct FuelCalendarModel {
    let today: Date
    let byDay: [Date: WidgetSnapshot.CalendarGrid.Day]
    let grid: WidgetSnapshot.CalendarGrid

    init(snapshot: WidgetSnapshot, now: Date) {
        let cal = Calendar.current
        today = cal.startOfDay(for: now)
        grid = snapshot.calendar
        byDay = Dictionary(snapshot.calendar.days.map { (cal.startOfDay(for: $0.date), $0) }, uniquingKeysWith: { _, last in last })
    }

    func level(_ day: Date) -> Int {
        let kcal = byDay[day]?.kcal
        return FuelHeat.level(kcalEaten: kcal ?? 0, kcalGoal: grid.kcalGoal, under: grid.under, over: grid.over,
                              hasEntries: kcal != nil, isToday: day == today)
    }

    func trained(_ day: Date) -> Bool { byDay[day]?.trained ?? false }

    /// Monday of the column `index` of `count`, the last one holding today.
    func monday(column index: Int, of count: Int) -> Date {
        let cal = Calendar.current
        let thisMonday = cal.date(byAdding: .day, value: -(cal.widgetISOWeekday(for: today) - 1), to: today) ?? today
        return cal.date(byAdding: .day, value: -7 * (count - 1 - index), to: thisMonday) ?? thisMonday
    }
}

struct FuelCalendarView: View {
    let entry: SnapshotEntry

    var body: some View {
        if let snapshot = entry.snapshot, entry.isReady {
            VStack(alignment: .leading, spacing: 8) {
                Text(verbatim: WidgetText.string("widget.history.label")).eyebrowStyle().lineLimit(1)
                ContributionGrid(model: FuelCalendarModel(snapshot: snapshot, now: entry.date))
            }
        } else {
            WidgetSetupView()
        }
    }
}

/// The Fuel calendar as a GitHub contribution graph, squares only: one column per week, Monday on top, the current
/// week at the right. Each day is a small rounded square in its `heat` colour, a day you trained gets an `ink` dot in
/// the middle, today an `ink` ring; days still ahead are left out. Cells are square and as big as the height allows
/// (at most `maxPitch`); when that would leave only a few weeks across, the weeks wrap into a second band under the
/// first (the older half on top). The grid is centred. Same metrics as Android's `WidgetCharts.contributions`.
struct ContributionGrid: View {
    let model: FuelCalendarModel

    private static let gapRatio: CGFloat = 0.22
    private static let maxPitch: CGFloat = 22
    private static let minTwoBandPitch: CGFloat = 15
    private static let bandGap: CGFloat = 12

    private struct Metrics {
        var bands: Int
        var perBand: Int
        var pitch: CGFloat
        var gap: CGFloat { pitch * ContributionGrid.gapRatio }
        var cell: CGFloat { pitch - gap }
        var weeks: Int { bands * perBand }
    }

    private func metrics(_ size: CGSize) -> Metrics {
        func pitch(_ bands: Int) -> CGFloat {
            let b = CGFloat(bands)
            return (size.height - (b - 1) * Self.bandGap) / (b * 7 - Self.gapRatio)
        }
        let bands = pitch(1) > Self.maxPitch && pitch(2) >= Self.minTwoBandPitch ? 2 : 1
        let p = max(4, min(pitch(bands), Self.maxPitch))
        let fit = Int((size.width + p * Self.gapRatio) / p)
        let perBand = min(max(1, fit), WidgetSnapshot.CalendarGrid.weeks / bands)
        return Metrics(bands: bands, perBand: perBand, pitch: p)
    }

    var body: some View {
        GeometryReader { geo in
            let m = metrics(geo.size)
            VStack(spacing: Self.bandGap) {
                ForEach(0..<m.bands, id: \.self) { band in
                    HStack(alignment: .top, spacing: m.gap) {
                        ForEach(0..<m.perBand, id: \.self) { column in
                            weekColumn(model.monday(column: band * m.perBand + column, of: m.weeks), m)
                        }
                    }
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
    }

    private func weekColumn(_ monday: Date, _ m: Metrics) -> some View {
        VStack(spacing: m.gap) {
            ForEach(0..<7, id: \.self) { row in
                cell(Calendar.current.date(byAdding: .day, value: row, to: monday) ?? monday, m)
            }
        }
    }

    @ViewBuilder
    private func cell(_ date: Date, _ m: Metrics) -> some View {
        let day = Calendar.current.startOfDay(for: date)
        if day > model.today {
            Color.clear.frame(width: m.cell, height: m.cell)
        } else {
            let shape = RoundedRectangle(cornerRadius: m.cell * 0.24, style: .continuous)
            let dot = max(3.5, m.cell * 0.4)
            ZStack {
                shape.fill(W.heat[model.level(day)])
                if model.trained(day) {
                    Circle().fill(W.ink).frame(width: dot, height: dot)
                }
                if day == model.today {
                    shape.strokeBorder(W.ink, lineWidth: 1.5)
                }
            }
            .frame(width: m.cell, height: m.cell)
        }
    }
}
