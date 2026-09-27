import SwiftUI

/// The picture the finish screen's Share sends (Strong/Hevy-style workout card): name and day, volume, time, sets,
/// each exercise with its best set, PR count, and the app's name. Rendered off-screen with `ImageRenderer`.
struct WorkoutShareCard: View {
    struct Line: Equatable {
        var name: String
        var sets: Int
        var best: String
    }

    var title: String
    var subtitle: String
    var volume: String
    var time: String
    var sets: String
    var prs: Int
    var lines: [Line]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(verbatim: subtitle).eyebrow(NT.Colors.ember)
            Text(verbatim: title)
                .font(NT.Fonts.display(40)).foregroundStyle(NT.Colors.ink).lineLimit(2)
                .padding(.top, 6)
            HStack(spacing: 10) {
                stat("workout.volume", volume)
                stat("workout.time", time)
                stat("workout.sets", sets)
            }
            .padding(.top, 20)
            VStack(spacing: 0) {
                ForEach(Array(lines.prefix(8).enumerated()), id: \.offset) { index, line in
                    if index > 0 { Hairline() }
                    HStack(spacing: 10) {
                        Text(verbatim: "\(line.sets) ×").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                            .frame(width: 30, alignment: .leading)
                        Text(verbatim: line.name).font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink).lineLimit(1)
                        Spacer(minLength: 8)
                        Text(verbatim: line.best).font(NT.Fonts.subheadlineBold).foregroundStyle(NT.Colors.ink)
                    }
                    .frame(height: 40)
                }
                if lines.count > 8 {
                    Text(verbatim: "+\(lines.count - 8)").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 6)
                }
            }
            .padding(.top, 18)
            HStack {
                if prs > 0 {
                    Label {
                        Text(verbatim: WorkoutStrings.prs(prs))
                    } icon: {
                        Image(systemName: "trophy.fill")
                    }
                    .font(NT.Fonts.footnoteBold).foregroundStyle(NT.Colors.ember)
                }
                Spacer()
                Text(verbatim: "NO TOMORROW").font(NT.Fonts.eyebrow).tracking(2).foregroundStyle(NT.Colors.ink3)
            }
            .padding(.top, 20)
        }
        .padding(24)
        .frame(width: 390, alignment: .leading)
        .background(NT.Colors.ground)
        .environment(\.colorScheme, .dark)
    }

    private func stat(_ label: LocalizedStringKey, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).eyebrow()
            Text(verbatim: value).font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).lineLimit(1).minimumScaleFactor(0.7)
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.tile, style: .continuous))
    }

    /// The card's lines: each exercise with completed working sets, and its heaviest one ("100 kg × 5").
    static func lines(for workout: Workout, unit: WeightUnit) -> [Line] {
        workout.sortedExercises.compactMap { item in
            let done = item.sortedSets.filter { $0.isCompleted && $0.kind != .warmup }
            guard let exercise = item.exercise, !done.isEmpty else { return nil }
            let best = done.max { ($0.weightKg, $0.reps) < ($1.weightKg, $1.reps) }!
            let label = best.weightKg > 0 ? "\(Fmt.weight(best.weightKg, unit: unit)) × \(best.reps)" : "× \(best.reps)"
            return Line(name: exercise.localizedName, sets: done.count, best: label)
        }
    }

    @MainActor
    static func render(_ card: WorkoutShareCard) -> Image? {
        let renderer = ImageRenderer(content: card)
        renderer.scale = 3
        return renderer.uiImage.map { Image(uiImage: $0) }
    }
}
