import SwiftUI
import SwiftData

/// Progress tab: last-PR eyebrow, title, Lifts / Body segmented. Lifts: a chip per lift over the selected lift's
/// focal card (e1RM, range delta, chart, range picker), the muscles trained this week, the e1RM list, the training
/// calendar and the weekly stats.
struct ProgressHomeView: View {
    private enum Tab: Hashable { case lifts, body }

    @Environment(AppState.self) private var appState
    @Environment(\.modelContext) private var modelContext
    @State private var model = ProgressModel()
    @State private var tab: Tab = .lifts
    @State private var showsLogWeight = false
    @State private var selectedLiftID: PersistentIdentifier?
    @State private var range: ProgressRange = .m3

    /// The lift whose chip is on: the one tapped, else the first.
    private var selectedLift: LiftSummary? {
        model.lifts.first { $0.id == selectedLiftID } ?? model.lifts.first
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    header
                    switch tab {
                    case .lifts:
                        if model.hasCompletedSets, let lift = selectedLift {
                            liftChips(selected: lift).padding(.top, 18)
                            focalCard(lift).padding(.top, 12)
                        }
                        musclesSection.padding(.top, model.hasCompletedSets ? NT.Spacing.section : 18)
                        liftsSection.padding(.top, NT.Spacing.section)
                        TrainingCalendarCard(unit: model.unit).padding(.top, NT.Spacing.section)
                        WeeklyStatsCard(unit: model.unit).padding(.top, NT.Spacing.section)
                    case .body:
                        BodyTabView(stats: model.body, unit: model.unit) { showsLogWeight = true }
                            .padding(.top, 18)
                        MeasurementsSection(unit: model.unit).padding(.top, NT.Spacing.section)
                    }
                }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.bottom, 32)
            }
            .ntScreenBackground()
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: PersistentIdentifier.self) { id in
                if let exercise = model.lifts.first(where: { $0.id == id })?.exercise {
                    ExerciseProgressView(exercise: exercise)
                }
            }
        }
        .sheet(isPresented: $showsLogWeight) {
            LogWeightSheet(unit: model.unit, suggestedKg: model.body.latest?.kg) { model.reload(modelContext) }
        }
        .onAppear { model.reload(modelContext) }
        .onReceive(NotificationCenter.default.publisher(for: .workoutHistoryDidChange)) { _ in model.reload(modelContext) }
    }

    // MARK: Header

    private var header: some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 0) {
                    if let date = model.lastPRDate {
                        Text("progress.lastPR")
                        Text(" · ")
                        Text(ProgressPhrase.eyebrowDate(date))
                    } else {
                        Text("progress.noPRYet")
                    }
                }
                .eyebrow()
                Text("progress.title").font(NT.Fonts.largeTitle).foregroundStyle(NT.Colors.ink)
            }
            Spacer()
            ProgressSegmented(
                options: [Tab.lifts, Tab.body],
                label: { $0 == .lifts ? "progress.lifts" : "progress.body" },
                selection: $tab,
                segmentHeight: 34,
                inset: 3,
                radius: 11,
                font: NT.Fonts.subheadline
            )
            .frame(width: 150)
            .padding(.bottom, 4)
        }
    }

    // MARK: Selected lift

    /// Every lift, in the list's order; bleeds to the screen edge.
    private func liftChips(selected: LiftSummary) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(model.lifts) { lift in
                    Chip(title: lift.name, isSelected: lift.id == selected.id) { selectedLiftID = lift.id }
                }
            }
            .padding(.horizontal, NT.Spacing.screenH)
        }
        .padding(.horizontal, -NT.Spacing.screenH)
    }

    /// The screen's focal card: current e1RM, its change over the range, the chart (opens the lift) and the range.
    private func focalCard(_ lift: LiftSummary) -> some View {
        FocalCard {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("progress.e1rm").eyebrow()
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            Text(Fmt.weight(lift.current, unit: model.unit, withUnit: false))
                                .font(NT.Fonts.display(56))
                                .foregroundStyle(NT.Colors.ink)
                                .tabular()
                            Text(model.unit.rawValue).font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink2)
                        }
                    }
                    Spacer(minLength: 0)
                    ProgressDeltaChip(delta: lift.delta(in: range), range: range, unit: model.unit,
                                      neutral: NT.Colors.surface2)
                }
                NavigationLink(value: lift.id) {
                    liftChart(lift)
                }
                .buttonStyle(.plain)
                ProgressSegmented(options: ProgressRange.allCases, label: \.titleKey, selection: $range)
            }
        }
    }

    /// `ExerciseProgressView`'s chart at 160 pt, or its one-line hint under two points.
    @ViewBuilder
    private func liftChart(_ lift: LiftSummary) -> some View {
        let points = lift.points(in: range)
        if points.count >= 2 {
            E1RMChart(points: points, unit: model.unit)
                .frame(height: 160)
                .contentShape(Rectangle())
        } else {
            Text(points.count == 1 ? "progress.firstSessionHint" : "progress.noSessionsInRange")
                .font(NT.Fonts.footnote)
                .foregroundStyle(NT.Colors.ink2)
                .frame(maxWidth: .infinity)
                .frame(height: 160)
                .contentShape(Rectangle())
        }
    }

    // MARK: Muscles this week

    /// Body map and the five most-trained muscles; under them, which of the big ones are still at zero.
    private var musclesSection: some View {
        let muscles = model.muscles
        let top = muscles.top
        let notTrained = muscles.notTrainedYet.map(WorkoutStrings.muscle).joined(separator: ", ")
        return VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "progress.musclesThisWeek",
                          trailing: Text(verbatim: WorkoutStrings.sets(muscles.totalSets)).monospacedDigit())
            NTCard {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 16) {
                        MuscleHeatView(setsByMuscle: muscles.setsByMuscle)
                            .frame(width: 120, height: 180)
                        if !top.isEmpty {
                            VStack(spacing: 0) {
                                ForEach(Array(top.enumerated()), id: \.element.muscle) { index, item in
                                    HStack(spacing: 8) {
                                        Text(WorkoutStrings.muscle(item.muscle))
                                            .font(NT.Fonts.subheadline)
                                            .foregroundStyle(NT.Colors.ink)
                                            .lineLimit(1)
                                        Spacer(minLength: 0)
                                        Text(item.sets.formatted())
                                            .font(NT.Fonts.subheadline)
                                            .foregroundStyle(NT.Colors.ink2)
                                            .tabular()
                                    }
                                    .frame(height: 36)
                                    if index < top.count - 1 { Hairline() }
                                }
                            }
                            .frame(maxWidth: .infinity)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    if !notTrained.isEmpty {
                        Text("progress.notTrainedYet \(notTrained)")
                            .font(NT.Fonts.footnote)
                            .foregroundStyle(NT.Colors.ink2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        }
    }

    // MARK: Lifts

    @ViewBuilder
    private var liftsSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("progress.e1rm").font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink)
                Spacer()
                Text("progress.range3Months").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
            }
            .padding(.bottom, 4)

            if model.hasCompletedSets {
                ForEach(Array(model.lifts.enumerated()), id: \.element.id) { index, lift in
                    NavigationLink(value: lift.id) {
                        ProgressLiftRow(lift: lift, unit: model.unit, showsHairline: index < model.lifts.count - 1)
                    }
                    .buttonStyle(.plain)
                }
            } else {
                emptyState
            }
        }
    }

    private var emptyState: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("progress.empty")
                .font(NT.Fonts.subheadline)
                .foregroundStyle(NT.Colors.ink2)
                .fixedSize(horizontal: false, vertical: true)
            GhostButton(title: "workout.start") { appState.selectedTab = .train }
        }
        .padding(.top, 24)
    }
}
