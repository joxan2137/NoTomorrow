import SwiftUI
import Charts

// MARK: - Sparkline (72×24 in lift rows, 120×48 in the body card)

/// Single series, no axes; ember when something happened recently, grey otherwise.
struct SparklineChart: View {
    var values: [Double]
    var color: Color
    var lineWidth: CGFloat = 2
    var showsEndDot: Bool = true

    var body: some View {
        Chart {
            ForEach(Array(values.enumerated()), id: \.offset) { index, value in
                LineMark(x: .value("i", index), y: .value("v", value))
                    .interpolationMethod(.linear)
                    .lineStyle(StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round))
                    .foregroundStyle(color)
            }
            if showsEndDot, let last = values.last {
                PointMark(x: .value("i", values.count - 1), y: .value("v", last))
                    .symbolSize(24)
                    .foregroundStyle(color)
            }
        }
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        .chartLegend(.hidden)
        .chartYScale(domain: ChartScale.padded(values))
        .chartXScale(domain: 0...max(1, values.count - 1))
        .chartPlotStyle { $0.background(.clear) }
    }
}

/// Body weight: raw readings (ink2, 1.5 pt) under the 7-day moving average (ember, 2 pt).
struct BodyWeightChart: View {
    var raw: [Double]
    var smoothed: [Double]
    var dates: [Date] = []
    var showsAxes: Bool = false
    var unit: WeightUnit = .kg

    var body: some View {
        Chart {
            ForEach(Array(raw.enumerated()), id: \.offset) { index, value in
                LineMark(x: .value("i", index), y: .value("raw", value), series: .value("s", "raw"))
                    .interpolationMethod(.linear)
                    .lineStyle(StrokeStyle(lineWidth: 1.5, lineCap: .round, lineJoin: .round))
                    .foregroundStyle(NT.Colors.ink2.opacity(0.6))
            }
            ForEach(Array(smoothed.enumerated()), id: \.offset) { index, value in
                LineMark(x: .value("i", index), y: .value("avg", value), series: .value("s", "avg"))
                    .interpolationMethod(.linear)
                    .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                    .foregroundStyle(NT.Colors.ember)
            }
            if let last = smoothed.last {
                PointMark(x: .value("i", smoothed.count - 1), y: .value("avg", last))
                    .symbol {
                        Circle().fill(NT.Colors.ember)
                            .overlay(Circle().strokeBorder(NT.Colors.surface, lineWidth: 2))
                            .frame(width: 9, height: 9)
                    }
                    .symbolSize(56)
            }
        }
        .chartLegend(.hidden)
        .chartYScale(domain: ChartScale.padded(raw + smoothed))
        .chartXScale(domain: 0...max(1, raw.count - 1))
        .chartXAxis {
            if showsAxes, dates.count > 1 {
                AxisMarks(values: [0, dates.count - 1]) { value in
                    AxisValueLabel(anchor: value.index == 0 ? .topLeading : .topTrailing) {
                        if let i = value.as(Int.self), dates.indices.contains(i) {
                            ChartAxisText(Fmt.dayMonth(dates[i]))
                        }
                    }
                }
            }
        }
        .chartYAxis {
            if showsAxes {
                AxisMarks(position: .trailing, values: .automatic(desiredCount: 3)) { value in
                    AxisGridLine(stroke: StrokeStyle(lineWidth: 1)).foregroundStyle(NT.Colors.hairline)
                    AxisValueLabel {
                        if let v = value.as(Double.self) { ChartAxisText(Fmt.weight(v, unit: unit, withUnit: false)) }
                    }
                }
            }
        }
        .chartPlotStyle { $0.background(.clear) }
    }
}

// MARK: - e1RM line + area with PR marks

struct E1RMChart: View {
    var points: [E1RMPoint]
    var unit: WeightUnit = .kg

    private var gradient: LinearGradient {
        LinearGradient(colors: [NT.Colors.ember.opacity(0.22), NT.Colors.ember.opacity(0)], startPoint: .top, endPoint: .bottom)
    }

    var body: some View {
        Chart {
            ForEach(points) { point in
                AreaMark(x: .value("date", point.date), y: .value("e1RM", point.e1RM))
                    .interpolationMethod(.linear)
                    .foregroundStyle(gradient)
                LineMark(x: .value("date", point.date), y: .value("e1RM", point.e1RM))
                    .interpolationMethod(.linear)
                    .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                    .foregroundStyle(NT.Colors.ember)
            }
            ForEach(points.filter(\.isPR)) { point in
                let isLatest = point.id == points.last?.id
                PointMark(x: .value("date", point.date), y: .value("e1RM", point.e1RM))
                    .symbol {
                        // Custom symbols get no size from symbolSize; pin it so a bare Circle can't fill the chart.
                        if isLatest {
                            Circle().fill(NT.Colors.ember)
                                .overlay(Circle().strokeBorder(NT.Colors.ground, lineWidth: 2))
                                .frame(width: 12, height: 12)
                        } else {
                            Circle().fill(NT.Colors.ground)
                                .overlay(Circle().strokeBorder(NT.Colors.ember, lineWidth: 2))
                                .frame(width: 9, height: 9)
                        }
                    }
                    .symbolSize(isLatest ? 110 : 60)
            }
        }
        .chartLegend(.hidden)
        .chartYScale(domain: ChartScale.padded(points.map(\.e1RM), bottom: 0.08, top: 0.06))
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 4)) { value in
                AxisValueLabel {
                    if let d = value.as(Date.self) { ChartAxisText(Fmt.dayMonth(d)) }
                }
            }
        }
        .chartYAxis {
            AxisMarks(position: .trailing, values: .automatic(desiredCount: 3)) { value in
                AxisGridLine(stroke: StrokeStyle(lineWidth: 1)).foregroundStyle(NT.Colors.hairline)
                AxisValueLabel {
                    if let v = value.as(Double.self) { ChartAxisText(Fmt.weight(v, unit: unit, withUnit: false)) }
                }
            }
        }
        .chartPlotStyle { $0.background(.clear) }
    }
}

// MARK: - Weekly volume bars (last 8 ISO weeks)

struct WeeklyVolumeChart: View {
    var weeks: [WeekVolume]

    var body: some View {
        Chart(weeks) { week in
            BarMark(
                x: .value("week", week.weekStart, unit: .weekOfYear),
                y: .value("kg", week.volumeKg),
                width: .ratio(0.66)
            )
            .clipShape(RoundedRectangle(cornerRadius: 3, style: .continuous))
            .foregroundStyle(week.isCurrent ? NT.Colors.ember : NT.Colors.surface2)
        }
        .chartLegend(.hidden)
        .chartYAxis(.hidden)
        .chartXAxis {
            AxisMarks(values: [weeks.first?.weekStart ?? .now, weeks.last?.weekStart ?? .now]) { value in
                AxisValueLabel(anchor: value.index == 0 ? .topLeading : .topTrailing) {
                    if let d = value.as(Date.self) {
                        if let last = weeks.last, Calendar.current.isDate(d, inSameDayAs: last.weekStart) {
                            Text("progress.thisWeek").font(.system(size: 12)).foregroundStyle(NT.Colors.ink2)
                        } else {
                            ChartAxisText(Fmt.dayMonth(d))
                        }
                    }
                }
            }
        }
        .chartYScale(domain: 0...max(1, (weeks.map(\.volumeKg).max() ?? 0) * 1.05))
        .chartPlotStyle {
            $0.background(.clear)
                .overlay(alignment: .bottom) { Rectangle().fill(NT.Colors.hairline).frame(height: 1) }
        }
    }
}

// MARK: - Helpers

struct ChartAxisText: View {
    var text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text).font(.system(size: 12)).foregroundStyle(NT.Colors.ink2).tabular()
    }
}

enum ChartScale {
    /// Y domain with breathing room so line ends and dots are not clipped by the plot edge.
    static func padded(_ values: [Double], bottom: Double = 0.15, top: Double = 0.15) -> ClosedRange<Double> {
        guard let lo = values.min(), let hi = values.max() else { return 0...1 }
        let span = max(hi - lo, max(hi * 0.04, 1))
        return (lo - span * bottom)...(hi + span * top)
    }
}
