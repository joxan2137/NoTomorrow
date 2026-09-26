import SwiftUI
import SwiftData

/// Progress > Milestones: every tier of every track — reached ones with their day, the rest with how far along they
/// are. The strength track needs a body weight; without one it says how to unlock it.
struct MilestonesView: View {
    @Environment(\.dismiss) private var dismiss
    @Query(filter: #Predicate<Workout> { $0.endedAt != nil }, sort: \Workout.startedAt)
    private var workouts: [Workout]
    @Query(sort: \BodyWeightEntry.day) private var weights: [BodyWeightEntry]
    @Query private var profiles: [UserProfile]

    private var unit: WeightUnit { profiles.first?.units ?? .kg }

    var body: some View {
        let bodyWeight = Milestones.bodyWeight(entries: weights, profile: profiles.first)
        let milestones = Milestones.evaluate(sessions: Milestones.sessions(from: workouts), bodyWeightKg: bodyWeight)
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                section("milestones.section.workouts", milestones.filter { $0.kind == .workouts })
                    .padding(.top, 18)
                section("milestones.section.volume", milestones.filter { $0.kind == .volume })
                    .padding(.top, NT.Spacing.section)
                section("milestones.section.weekStreak", milestones.filter { $0.kind == .weekStreak })
                    .padding(.top, NT.Spacing.section)
                strengthSection(milestones.filter { $0.kind.isLift }, bodyWeight: bodyWeight)
                    .padding(.top, NT.Spacing.section)
            }
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.bottom, 32)
        }
        .ntScreenBackground()
        .toolbar(.hidden, for: .navigationBar)
    }

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

            Text("milestones.title")
                .font(NT.Fonts.title2)
                .foregroundStyle(NT.Colors.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(height: 44)
    }

    private func section(_ title: LocalizedStringKey, _ milestones: [Milestones.Milestone]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: title)
            NTCard {
                rows(milestones)
            }
        }
    }

    private func strengthSection(_ milestones: [Milestones.Milestone], bodyWeight: Double?) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "milestones.section.strength")
            NTCard {
                VStack(alignment: .leading, spacing: 12) {
                    if let bodyWeight, !milestones.isEmpty {
                        rows(milestones)
                        Text("milestones.strengthNote \(Fmt.weight(bodyWeight, unit: unit))")
                            .font(NT.Fonts.footnote)
                            .foregroundStyle(NT.Colors.ink2)
                            .fixedSize(horizontal: false, vertical: true)
                    } else {
                        Text("milestones.noBodyWeight")
                            .font(NT.Fonts.subheadline)
                            .foregroundStyle(NT.Colors.ink2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        }
    }

    private func rows(_ milestones: [Milestones.Milestone]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(milestones.enumerated()), id: \.element.id) { index, milestone in
                Group {
                    if milestone.isAchieved {
                        MilestoneAchievedRow(milestone: milestone, unit: unit)
                    } else {
                        MilestoneProgressRow(milestone: milestone, unit: unit)
                    }
                }
                .padding(.vertical, 10)
                if index < milestones.count - 1 { Hairline() }
            }
        }
    }
}
