import SwiftUI
import SwiftData

/// One lift over time: hero e1RM + delta chip, line/area chart with PR marks, tiles, weekly volume, records.
struct ExerciseProgressView: View {
    var exercise: Exercise

    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss
    @State private var model = ProgressModel()
    @State private var range: ProgressRange = .m3
    @State private var showsCalculator = false

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
                    repMaxes(lift).padding(.top, NT.Spacing.section)
                    percentages(lift).padding(.top, NT.Spacing.section)
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
        .sheet(isPresented: $showsCalculator) { OneRepMaxCalculatorSheet(unit: model.unit) }
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

    /// Last PR · This week · Sessions. Labels wrap to two lines before shrinking ("Ostatnia życiówka",
    /// "Sessions · 3M") instead of truncating; the row takes its tallest tile's height and each value sits on
    /// the tile's bottom edge so the three values stay level.
    private func tiles(_ lift: LiftSummary) -> some View {
        HStack(spacing: 10) {
            tile(Text("progress.lastPR")) {
                Group {
                    if let date = lift.lastPR { Text(ProgressPhrase.ago(date)) } else { Text("progress.noPRYet") }
                }
                .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).tabular().lineLimit(1).minimumScaleFactor(0.8)
            }

            // `StatTile`'s value: shrinks rather than truncates.
            tile(Text("progress.thisWeek")) {
                Text(Fmt.volume(lift.thisWeekVolume, unit: model.unit))
                    .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).tabular().lineLimit(1)
                    .minimumScaleFactor(0.6).allowsTightening(true)
            }

            tile(Text("progress.sessions") + Text(verbatim: " · ") + Text(range.titleKey)) {
                Text(lift.sessions(in: range).formatted())
                    .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).tabular()
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    /// `StatTile` geometry with a two-line label and a caller-supplied value pinned to the bottom.
    private func tile<Value: View>(_ label: Text, @ViewBuilder value: () -> Value) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            label
                .eyebrow()
                .lineLimit(2)
                .minimumScaleFactor(0.8)
            Spacer(minLength: 0)
            value()
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.tile, style: .continuous))
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

    // MARK: Rep maxes

    /// Reps · best actually lifted for at least that many (with its date) · what the best e1RM predicts.
    private func repMaxes(_ lift: LiftSummary) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("repmax.title").font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink)
            HStack {
                Text("workout.reps").frame(width: 44, alignment: .leading)
                Text("repmax.best").frame(maxWidth: .infinity, alignment: .leading)
                Text("repmax.estimated").frame(width: 84, alignment: .trailing)
            }
            .font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink2)
            .padding(.top, 10).padding(.bottom, 4)
            // Each row below names its own columns for VoiceOver.
            .accessibilityHidden(true)
            ForEach(Array(RepMax.rows(sets: lift.sets, e1RM: lift.current).enumerated()), id: \.offset) { index, row in
                if index > 0 { Hairline() }
                HStack {
                    Text(verbatim: "\(row.reps)")
                        .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).tabular()
                        .frame(width: 44, alignment: .leading)
                    Group {
                        if let best = row.best {
                            HStack(spacing: 6) {
                                Text(verbatim: Fmt.set(best.weightKg, best.reps, unit: model.unit))
                                    .foregroundStyle(NT.Colors.ink)
                                Text(Fmt.dayMonth(best.date)).font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                            }
                        } else {
                            Text(verbatim: "—").foregroundStyle(NT.Colors.ink3)
                        }
                    }
                    .font(NT.Fonts.subheadline).tabular()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Text(Fmt.weight(row.estimatedKg, unit: model.unit))
                        .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2).tabular()
                        .frame(width: 84, alignment: .trailing)
                }
                .frame(minHeight: 44)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text("workout.reps") + Text(verbatim: " \(row.reps)"))
                .accessibilityValue(repMaxValue(row))
            }
            Text("repmax.footnote")
                .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink3)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)
        }
    }

    /// "Best lifted 100 kg × 5, 4 Sep, Estimated 102 kg" for one rep-max row.
    private func repMaxValue(_ row: RepMax.Row) -> Text {
        let best: Text = row.best.map { set in
            Text("repmax.best") + Text(verbatim: " " + Fmt.set(set.weightKg, set.reps, unit: model.unit)
                + ", " + Fmt.dayMonth(set.date))
        } ?? (Text("repmax.best") + Text(verbatim: " —"))
        return best + Text(verbatim: ", ") + Text("repmax.estimated")
            + Text(verbatim: " " + Fmt.weight(row.estimatedKg, unit: model.unit))
    }

    // MARK: Percentages

    /// 100 … 50 % of the best e1RM, rounded to plates, with the reps each allows; "1RM calculator" beside the title.
    private func percentages(_ lift: LiftSummary) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("onerm.percentages").font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink)
                Spacer()
                Button { showsCalculator = true } label: {
                    Text("onerm.calculator").font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                        .frame(minHeight: NT.Size.control)
                        .contentShape(Rectangle())
                }
                .buttonStyle(PressScale())
            }
            PercentageTable(e1RM: SetInput.display(lift.current, unit: model.unit), unit: model.unit)
                .padding(.top, 4)
            Text("onerm.footnote")
                .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink3)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)
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
