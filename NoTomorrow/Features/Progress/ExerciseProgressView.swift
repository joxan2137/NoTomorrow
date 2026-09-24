import SwiftUI
import SwiftData

/// One lift over time: hero e1RM + delta chip, line/area chart with PR marks, tiles, weekly volume, records.
struct ExerciseProgressView: View {
    var exercise: Exercise

    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss
    @State private var model = ProgressModel()
    @State private var range: ProgressRange = .m3

    private var lift: LiftSummary? { model.lift(for: exercise) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                if let lift {
                    hero(lift).padding(.top, 18)
                    chart(lift).padding(.top, 12)
                    tiles(lift).padding(.top, 14)
                    weekly.padding(.top, NT.Spacing.section)
                    records(lift).padding(.top, 18)
                } else {
                    Text("progress.empty")
                        .font(NT.Fonts.subheadline)
                        .foregroundStyle(NT.Colors.ink2)
                        .padding(.top, 40)
                }
            }
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.bottom, 32)
        }
        .ntScreenBackground()
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { model.reload(modelContext) }
        .onReceive(NotificationCenter.default.publisher(for: .workoutHistoryDidChange)) { _ in model.reload(modelContext) }
    }

    // MARK: Header (back · name · 1M 3M 1Y All)

    private var header: some View {
        HStack(spacing: 12) {
            Button { dismiss() } label: {
                Image(systemName: "arrow.left")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(NT.Colors.ink)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("common.back"))
            .padding(.leading, -10)

            Text(exercise.localizedName)
                .font(NT.Fonts.title2)
                .foregroundStyle(NT.Colors.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .frame(maxWidth: .infinity, alignment: .leading)

            ProgressSegmented(options: ProgressRange.allCases, label: \.titleKey, selection: $range)
                .frame(width: 168)
        }
        .frame(height: 44)
    }

    // MARK: Hero

    private func hero(_ lift: LiftSummary) -> some View {
        let delta = lift.delta(in: range)
        return HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 6) {
                Text("progress.e1rm").eyebrow()
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(Fmt.weight(lift.current, unit: model.unit, withUnit: false))
                        .font(NT.Fonts.display(64))
                        .foregroundStyle(NT.Colors.ink)
                        .tabular()
                    Text(model.unit.rawValue).font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink2)
                }
            }
            Spacer()
            ProgressDeltaChip(delta: delta, range: range, unit: model.unit)
                .padding(.bottom, 6)
        }
    }

    // MARK: Chart

    @ViewBuilder
    private func chart(_ lift: LiftSummary) -> some View {
        let points = lift.points(in: range)
        if points.count >= 2 {
            E1RMChart(points: points, unit: model.unit)
                .frame(height: 172)
        } else {
            Text(points.count == 1 ? "progress.firstSessionHint" : "progress.noSessionsInRange")
                .font(NT.Fonts.footnote)
                .foregroundStyle(NT.Colors.ink2)
                .frame(maxWidth: .infinity)
                .frame(height: 172)
        }
    }

    // MARK: Tiles

    private func tiles(_ lift: LiftSummary) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 4) {
                Text("progress.lastPR").eyebrow()
                Group {
                    if let date = lift.lastPR { Text(ProgressPhrase.ago(date)) } else { Text("progress.noPRYet") }
                }
                .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).tabular().lineLimit(1).minimumScaleFactor(0.8)
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.tile, style: .continuous))

            StatTile(label: "progress.thisWeek", value: Fmt.volume(lift.thisWeekVolume, unit: model.unit))

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 0) {
                    Text("progress.sessions")
                    Text(" · ")
                    Text(range.titleKey)
                }
                .eyebrow()
                .lineLimit(1)
                Text(lift.sessions(in: range).formatted())
                    .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).tabular()
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.tile, style: .continuous))
        }
    }

    // MARK: Weekly volume (all lifts)

    private var weekly: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("progress.weeklyVolume").font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink)
                Spacer()
                HStack(spacing: 0) {
                    Text(Fmt.volume(model.thisWeekVolume, unit: model.unit))
                    if let ratio = model.weekOverWeek {
                        Text(" · ")
                        Text(Fmt.signedPercent(ratio) + " ").foregroundStyle(ratio >= 0 ? NT.Colors.ember : NT.Colors.ink2)
                        Text("progress.vsLastWeek").foregroundStyle(ratio >= 0 ? NT.Colors.ember : NT.Colors.ink2)
                    }
                }
                .font(NT.Fonts.footnote)
                .foregroundStyle(NT.Colors.ink2)
                .tabular()
                .lineLimit(1)
            }
            WeeklyVolumeChart(weeks: model.weekly)
                .frame(height: 86)
        }
    }

    // MARK: Records

    private func records(_ lift: LiftSummary) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("progress.records").font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink)
                .padding(.bottom, 4)
            if let heaviest = lift.heaviest {
                recordRow(label: "progress.heaviestSet", set: heaviest)
                Hairline()
            }
            if let most = lift.mostReps {
                recordRow(label: "progress.mostReps", set: most)
            }
        }
    }

    private func recordRow(label: LocalizedStringKey, set: LiftSet) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "trophy")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(NT.Colors.ember)
                .frame(width: 18)
            Text(label).font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text("\(Fmt.weight(set.weightKg, unit: model.unit)) × \(set.reps.formatted())")
                .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink).tabular()
            Text(Fmt.dayMonth(set.date))
                .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular()
                .frame(width: 52, alignment: .trailing)
        }
        .frame(height: 52)
    }
}
