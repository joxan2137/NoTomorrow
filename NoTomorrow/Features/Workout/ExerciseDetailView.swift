import SwiftUI

/// Offline front/back muscle model plus the original public-domain demonstration frames.
struct ExerciseDetailView: View {
    let exercise: Exercise
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text(exercise.localizedName).font(NT.Fonts.title2)
                    MuscleModelView(primary: exercise.primaryMuscles, secondary: exercise.secondaryMuscles)
                    VStack(alignment: .leading, spacing: 8) {
                        Label(String(localized: "exercises.primary") + ": " + exercise.primaryMuscles.map(WorkoutStrings.muscle).joined(separator: ", "), systemImage: "circle.fill")
                            .foregroundStyle(Color.orange)
                        Label(String(localized: "exercises.secondary") + ": " + exercise.secondaryMuscles.map(WorkoutStrings.muscle).joined(separator: ", "), systemImage: "circle.fill")
                            .foregroundStyle(Color.cyan)
                        Text("exercises.muscleNote").foregroundStyle(NT.Colors.ink2)
                    }.font(NT.Fonts.footnote)
                    let photos = ExerciseMedia.images[exercise.id] ?? []
                    if !photos.isEmpty {
                        Text("exercises.demonstration").font(NT.Fonts.headline)
                        ForEach(photos, id: \.self) { path in
                            AsyncImage(url: ExerciseMedia.url(path)) { phase in
                                switch phase {
                                case .success(let image): image.resizable().scaledToFit()
                                case .failure: Label("exercises.photoUnavailable", systemImage: "photo")
                                default: ProgressView().frame(maxWidth: .infinity, minHeight: 150)
                                }
                            }
                            .frame(maxWidth: .infinity)
                            .clipShape(RoundedRectangle(cornerRadius: 14))
                        }
                    }
                    Text("exercises.instructions").font(NT.Fonts.headline)
                    ForEach(Array(exercise.instructions.enumerated()), id: \.offset) { index, instruction in
                        Text("\(index + 1). \(instruction)").font(NT.Fonts.body)
                    }
                    if !photos.isEmpty {
                        Link("free-exercise-db · Public domain", destination: URL(string: "https://github.com/yuhonas/free-exercise-db")!)
                            .font(NT.Fonts.footnote)
                    }
                }
                .padding(20)
                .foregroundStyle(NT.Colors.ink)
            }
            .ntScreenBackground()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("common.done") { dismiss() } } }
        }
    }
}

struct MuscleModelView: View {
    let primary: [String]
    let secondary: [String]
    var body: some View {
        VStack(spacing: 4) {
            HStack { Text("exercises.front"); Spacer(); Text("exercises.back") }
                .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).padding(.horizontal, 36)
            Canvas { context, size in
                for region in ExerciseMedia.regions {
                    var path = Path()
                    for (i, point) in region.points.enumerated() where point.count == 2 {
                        let p = CGPoint(x: point[0] / 200 * size.width, y: point[1] / 300 * size.height)
                        if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
                    }
                    path.closeSubpath()
                    let color: Color = primary.contains(region.muscle) ? .orange : secondary.contains(region.muscle) ? .cyan : region.muscle == "outline" ? NT.Colors.surface3 : NT.Colors.ink3.opacity(0.4)
                    context.fill(path, with: .color(color))
                    context.stroke(path, with: .color(NT.Colors.ground), lineWidth: 1)
                }
            }
            .aspectRatio(2.0 / 3.0, contentMode: .fit)
            .frame(maxHeight: 360)
            .accessibilityLabel(Text("exercises.muscleNote"))
        }
    }
}

enum ExerciseMedia {
    struct Region: Decodable { let muscle: String; let points: [[Double]] }
    static let regions: [Region] = load("muscle_model") ?? []
    static let images: [String: [String]] = {
        let records: [ExerciseLibrary.Record] = load("exercises") ?? []
        return Dictionary(records.map { ($0.id, $0.images ?? []) }, uniquingKeysWith: { a, _ in a })
    }()
    static func url(_ path: String) -> URL? {
        guard !path.contains(".."), !path.contains(":") else { return nil }
        return URL(string: "https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/")?.appendingPathComponent(path)
    }
    private static func load<T: Decodable>(_ name: String) -> T? {
        guard let url = Bundle.main.url(forResource: name, withExtension: "json"), let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }
}
