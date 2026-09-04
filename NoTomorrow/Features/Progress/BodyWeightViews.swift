import SwiftUI

/// Compact card at the top of the Lifts tab: latest weight, 4-week delta, 120×48 sparkline.
struct BodyWeightCard: View {
    var stats: BodyStats
    var unit: WeightUnit
    var onLog: () -> Void

    var body: some View {
        NTCard(padding: 0) {
            HStack(spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("progress.bodyWeightTrend").eyebrow()
                    if let latest = stats.latest {
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            Text(Fmt.weight(latest.kg, unit: unit, withUnit: false))
                                .font(NT.Fonts.display(40))
                                .foregroundStyle(NT.Colors.ink)
                                .tabular()
                            Text(unit.rawValue).font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                        }
                        BodyDeltaLine(stats: stats, unit: unit)
                    } else {
                        Text("progress.noWeightYet")
                            .font(NT.Fonts.subheadline)
                            .foregroundStyle(NT.Colors.ink2)
                        Button(action: onLog) {
                            Text("progress.logWeight")
                                .font(NT.Fonts.subheadlineBold)
                                .foregroundStyle(NT.Colors.ink)
                                .frame(height: 44)
                        }
                        .buttonStyle(PressScale())
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if stats.entries.count >= 2 {
                    let recent = Array(stats.entries.suffix(28))
                    let smoothed = Array(stats.smoothed.suffix(28))
                    BodyWeightChart(raw: recent.map(\.kg), smoothed: smoothed)
                        .frame(width: 120, height: 48)
                }
            }
            .padding(.vertical, 16)
            .padding(.horizontal, 18)
        }
        .contentShape(RoundedRectangle(cornerRadius: NT.Radius.card, style: .continuous))
        .onTapGesture { if stats.latest != nil { onLog() } }
    }
}

/// "+0.4 kg over 4 weeks · logged 21 of 28 days"
struct BodyDeltaLine: View {
    var stats: BodyStats
    var unit: WeightUnit

    var body: some View {
        HStack(spacing: 4) {
            if let delta = stats.delta4w {
                Text(Fmt.signedWeight(delta, unit: unit, withUnit: true))
                    .foregroundStyle(NT.Colors.ember)
                    .tabular()
                Text("progress.over4Weeks \(stats.loggedLast28)")
            } else {
                Text("progress.over4Weeks \(stats.loggedLast28)")
            }
        }
        .font(NT.Fonts.footnote)
        .foregroundStyle(NT.Colors.ink2)
        .lineLimit(1)
        .minimumScaleFactor(0.85)
    }
}

/// Body tab: big card with the full trend chart, Log weight, and the recent readings.
struct BodyTabView: View {
    var stats: BodyStats
    var unit: WeightUnit
    var onLog: () -> Void

    private var recent: [BodyWeightEntry] { Array(stats.entries.suffix(90)) }
    private var recentSmoothed: [Double] { Array(stats.smoothed.suffix(90)) }

    var body: some View {
        VStack(spacing: NT.Spacing.section) {
            NTCard {
                VStack(alignment: .leading, spacing: 14) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("progress.bodyWeightTrend").eyebrow()
                        if let latest = stats.latest {
                            HStack(alignment: .firstTextBaseline, spacing: 6) {
                                Text(Fmt.weight(latest.kg, unit: unit, withUnit: false))
                                    .font(NT.Fonts.display(40))
                                    .foregroundStyle(NT.Colors.ink)
                                    .tabular()
                                Text(unit.rawValue).font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                                Spacer()
                                Text(Fmt.dayMonth(latest.day)).font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                            }
                            BodyDeltaLine(stats: stats, unit: unit)
                        } else {
                            Text("progress.noWeightYet").font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                        }
                    }
                    if recent.count >= 2 {
                        BodyWeightChart(raw: recent.map(\.kg), smoothed: recentSmoothed, dates: recent.map(\.day), showsAxes: true, unit: unit)
                            .frame(height: 150)
                    }
                    PrimaryButton(title: "progress.logWeight", height: NT.Size.cardButton, action: onLog)
                }
            }

            if !recent.isEmpty {
                VStack(spacing: 0) {
                    SectionHeader(title: "progress.recentWeights")
                        .padding(.bottom, 4)
                    ForEach(Array(recent.suffix(10).reversed().enumerated()), id: \.element.day) { index, entry in
                        HStack {
                            Text(Fmt.longDay(entry.day)).font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink)
                            Spacer()
                            Text(Fmt.weight(entry.kg, unit: unit)).font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink).tabular()
                        }
                        .frame(height: 44)
                        if index < min(recent.count, 10) - 1 { Hairline() }
                    }
                }
            }
        }
    }
}
