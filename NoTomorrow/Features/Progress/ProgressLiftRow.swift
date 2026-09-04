import SwiftUI

/// One lift in the home list: name, context/last PR, 72×24 sparkline, current e1RM + delta over 3 months.
struct ProgressLiftRow: View {
    var lift: LiftSummary
    var unit: WeightUnit
    var range: ProgressRange = .m3
    var showsHairline: Bool = true

    private var color: Color { lift.prInLast30Days ? NT.Colors.ember : NT.Colors.ink2 }
    private var delta: Double { lift.delta(in: range) }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(lift.name)
                        .font(NT.Fonts.headline)
                        .foregroundStyle(NT.Colors.ink)
                        .lineLimit(1)
                    HStack(spacing: 0) {
                        if let context = lift.context, !context.isEmpty {
                            Text(context)
                            Text(" · ")
                        }
                        Text(ProgressPhrase.lastPR(lift.lastPR))
                    }
                    .font(NT.Fonts.footnote)
                    .foregroundStyle(NT.Colors.ink2)
                    .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                SparklineChart(values: lift.points(in: range).map(\.e1RM), color: color)
                    .frame(width: 72, height: 24)

                VStack(alignment: .trailing, spacing: 2) {
                    Text(Fmt.weight(lift.current, unit: unit))
                        .font(NT.Fonts.headline)
                        .foregroundStyle(NT.Colors.ink)
                        .tabular()
                    Text(Fmt.signedWeight(delta, unit: unit))
                        .font(NT.Fonts.footnote)
                        .foregroundStyle(delta > 0 && lift.prInLast30Days ? NT.Colors.ember : NT.Colors.ink2)
                        .tabular()
                }
                .frame(minWidth: 64, alignment: .trailing)
                .lineLimit(1)
            }
            .frame(height: 64)
            if showsHairline { Hairline() }
        }
        .contentShape(Rectangle())
    }
}
