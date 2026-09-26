import SwiftUI
import SwiftData

/// "DONE." summary after finishing: volume hero, delta vs the last workout with the same name,
/// a "New milestone" chip per milestone it crossed, Time / Sets / Exercises tiles, records list, bro card when paired, Done + Edit sets.
struct WorkoutDoneView: View {
    let workout: Workout
    var unit: WeightUnit = .kg
    /// When nil, Done just dismisses the presenting view.
    var onDone: (() -> Void)? = nil
    /// When nil, the "Edit sets" link is hidden.
    var onEditSets: (() -> Void)? = nil

    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @Query private var pairings: [BroPairing]
    @State private var previousVolume: Double?
    @State private var shareImage: Image?
    /// Milestones this workout crossed (`Milestones.crossed`), read once on appear.
    @State private var newMilestones: [Milestones.Milestone] = []

    private var volume: Double { workout.totalVolumeKg }
    /// `endedAt` is stamped on Finish, before this screen shows.
    private var duration: TimeInterval { (workout.endedAt ?? .now).timeIntervalSince(workout.startedAt) }
    private var records: [SetEntry] { RecordService.records(in: workout) }
    private var exerciseCount: Int { workout.exercises.filter { $0.sets.contains(where: \.isCompleted) }.count }

    var body: some View {
        VStack(spacing: 0) {
            header
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.top, 8)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    hero.padding(.top, 24)
                    tiles.padding(.top, NT.Spacing.section)
                    recordsSection.padding(.top, NT.Spacing.section)
                    if let partner = pairings.first?.partnerName, !partner.isEmpty {
                        broCard(partner: partner).padding(.top, 18)
                    }
                    Color.clear.frame(height: 16)
                }
                .padding(.horizontal, NT.Spacing.screenH)
            }
            VStack(spacing: 12) {
                PrimaryButton(title: "common.done") {
                    if let onDone { onDone() } else { dismiss() }
                }
                if let onEditSets {
                    Button(action: onEditSets) {
                        Text("workout.done.editSets")
                            .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                            .frame(maxWidth: .infinity)
                            .frame(height: NT.Size.control)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.bottom, 8)
        }
        .ntScreenBackground()
        .onAppear {
            loadPrevious()
            loadMilestones()
            renderShareImage()
        }
    }

    // MARK: Header

    private var header: some View {
        HStack {
            Text("\(workout.name) · \(Fmt.relativeDay(workout.startedAt)) \(Fmt.time(workout.startedAt))")
                .eyebrow(NT.Colors.ember)
                .lineLimit(1)
            Spacer(minLength: 12)
            Group {
                if let shareImage {
                    ShareLink(item: shareImage, message: Text(verbatim: shareText),
                              preview: SharePreview(Text(verbatim: workout.name), image: shareImage)) { shareLabel }
                } else {
                    ShareLink(item: shareText) { shareLabel }
                }
            }
        }
        .frame(height: NT.Size.control)
    }

    private var shareLabel: some View {
        Text("common.share").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink2)
            .frame(height: NT.Size.control)
            .contentShape(Rectangle())
    }

    /// The workout card picture (`WorkoutShareCard`), rendered once the screen is up.
    private func renderShareImage() {
        let card = WorkoutShareCard(
            title: workout.name,
            subtitle: "\(Fmt.longDay(workout.startedAt)) · \(Fmt.time(workout.startedAt))",
            volume: Fmt.volume(volume, unit: unit), time: Fmt.duration(duration),
            sets: "\(workout.completedSetCount)", prs: records.filter(\.isPR).count,
            lines: WorkoutShareCard.lines(for: workout, unit: unit))
        shareImage = WorkoutShareCard.render(card)
    }

    private var shareText: String {
        String(localized: "workout.done.shareText \(workout.name) \(Fmt.duration(duration)) \(Fmt.volume(volume, unit: unit))")
    }

    // MARK: Hero

    private var hero: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("workout.done.title").font(NT.Fonts.display(72)).foregroundStyle(NT.Colors.ink)
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(Fmt.volume(volume, unit: unit, withUnit: false))
                    .font(NT.Fonts.display(56)).foregroundStyle(NT.Colors.ink).tabular()
                Text(unit == .lb ? LocalizedStringKey("workout.done.lbMoved") : "workout.done.kgMoved")
                    .font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink2)
            }
            if let previousVolume, previousVolume != volume {
                deltaChip(delta: volume - previousVolume)
            }
            ForEach(newMilestones) { milestone in
                milestoneChip(milestone)
            }
        }
    }

    /// "New milestone: 50 workouts" under the volume.
    private func milestoneChip(_ milestone: Milestones.Milestone) -> some View {
        HStack(spacing: 6) {
            Image(systemName: "trophy").font(.system(size: 12, weight: .bold))
            Text("milestone.new \(MilestoneText.title(milestone, unit: unit))").lineLimit(1)
        }
        .font(NT.Fonts.footnoteBold).tabular()
        .foregroundStyle(NT.Colors.ember)
        .padding(.horizontal, 12)
        .frame(height: 30)
        .background(NT.Colors.emberTint, in: Capsule())
    }

    private func deltaChip(delta: Double) -> some View {
        let up = delta > 0
        return HStack(spacing: 6) {
            Image(systemName: up ? "arrow.up" : "arrow.down").font(.system(size: 12, weight: .bold))
            if up {
                Text("workout.done.moreThanLast \(Fmt.volume(delta, unit: unit)) \(workout.name)")
            } else {
                Text("workout.done.lessThanLast \(Fmt.volume(-delta, unit: unit)) \(workout.name)")
            }
        }
        .font(NT.Fonts.footnoteBold).tabular()
        .foregroundStyle(up ? NT.Colors.ember : NT.Colors.ink2)
        .padding(.horizontal, 12)
        .frame(height: 30)
        .background(up ? NT.Colors.emberTint : NT.Colors.surface2, in: Capsule())
    }

    // MARK: Tiles

    private var tiles: some View {
        HStack(spacing: 10) {
            StatTile(label: "workout.time", value: Fmt.duration(duration))
            StatTile(label: "workout.sets", value: "\(workout.completedSetCount)")
            StatTile(label: "workout.exercises", value: "\(exerciseCount)")
        }
    }

    // MARK: Records

    private var recordsSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("workout.done.records").font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink)
                .padding(.bottom, 4)
            if records.isEmpty {
                Text("workout.done.noRecords")
                    .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                    .padding(.vertical, 12)
            } else {
                ForEach(Array(records.enumerated()), id: \.element.persistentModelID) { index, set in
                    WorkoutRecordRow(entry: set, unit: unit)
                    if index < records.count - 1 { Hairline() }
                }
            }
        }
    }

    // MARK: Bro

    private func broCard(partner: String) -> some View {
        NTCard(padding: 14) {
            HStack(spacing: 12) {
                Avatar(initial: partner, size: 32, background: NT.Colors.surface3)
                VStack(alignment: .leading, spacing: 2) {
                    Text("workout.done.sharedWith \(partner)")
                        .font(NT.Fonts.subheadlineBold).foregroundStyle(NT.Colors.ink).lineLimit(1)
                    Text("workout.done.sharedDetail")
                        .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).lineLimit(2)
                }
                Spacer(minLength: 8)
                Circle().fill(NT.Colors.good).frame(width: 8, height: 8)
            }
            .padding(.horizontal, 2)
        }
    }

    // MARK: Data

    private func loadMilestones() {
        newMilestones = Milestones.crossed(by: workout.id, in: Milestones.evaluate(in: context, including: workout))
    }

    private func loadPrevious() {
        let name = workout.name
        let started = workout.startedAt
        let myID = workout.id
        var d = FetchDescriptor<Workout>(
            predicate: #Predicate { $0.endedAt != nil && $0.name == name && $0.startedAt < started && $0.id != myID },
            sortBy: [SortDescriptor(\.startedAt, order: .reverse)]
        )
        d.fetchLimit = 1
        previousVolume = (try? context.fetch(d))?.first?.totalVolumeKg
    }
}

/// 60 pt record row: trophy (PR, ember) or medal (set record, grey), "Exercise · 85 × 7", detail line, trailing tag.
struct WorkoutRecordRow: View {
    let entry: SetEntry
    var unit: WeightUnit = .kg

    private var exerciseName: String { entry.workoutExercise?.exercise?.localizedName ?? "" }

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle().fill(entry.isPR ? NT.Colors.ember.opacity(0.14) : NT.Colors.surface2)
                Image(systemName: entry.isPR ? "trophy" : "medal")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(entry.isPR ? NT.Colors.ember : NT.Colors.ink)
            }
            .frame(width: 36, height: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(exerciseName) · \(Fmt.set(entry.weightKg, entry.reps, unit: unit))")
                    .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).tabular().lineLimit(1)
                detail
                    .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular().lineLimit(1)
            }
            Spacer(minLength: 8)
            if entry.isPR {
                Text("workout.pr").eyebrow(NT.Colors.ember)
            } else {
                Text("workout.set").eyebrow()
            }
        }
        .frame(height: 60)
    }

    private var detail: Text {
        if entry.isPR {
            let before = entry.workoutExercise?.exercise.map { RecordService.previousSets(for: $0, before: entry) }?
                .map(\.estimatedOneRepMax).max() ?? 0
            return Text("workout.done.prDetail \(Fmt.weight(entry.estimatedOneRepMax, unit: unit)) \(Fmt.weight(before, unit: unit, withUnit: false))")
        }
        return Text("workout.done.setRecordDetail \(Fmt.weight(entry.weightKg, unit: unit))")
    }
}
