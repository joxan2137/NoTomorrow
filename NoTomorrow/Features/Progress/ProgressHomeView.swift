import SwiftUI
import SwiftData

/// Progress tab: last-PR eyebrow, title, Lifts / Body segmented; body-weight card and the e1RM list.
struct ProgressHomeView: View {
    private enum Tab: Hashable { case lifts, body }

    @Environment(AppState.self) private var appState
    @Environment(\.modelContext) private var modelContext
    @State private var model = ProgressModel()
    @State private var tab: Tab = .lifts
    @State private var showsLogWeight = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    header
                    switch tab {
                    case .lifts:
                        BodyWeightCard(stats: model.body, unit: model.unit) { showsLogWeight = true }
                            .padding(.top, 18)
                        liftsSection.padding(.top, NT.Spacing.section)
                    case .body:
                        BodyTabView(stats: model.body, unit: model.unit) { showsLogWeight = true }
                            .padding(.top, 18)
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
