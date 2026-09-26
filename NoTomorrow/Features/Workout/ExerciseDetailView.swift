import SwiftUI

/// An exercise's sheet: what it is (equipment, level, compound or isolation), the looping form demo (the
/// public-domain free-exercise-db photos, or the app's own mannequin for exercises without them), the body map of
/// the muscles it works, the app's own form cues and common mistakes where it has them, and the numbered steps.
struct ExerciseDetailView: View {
    let exercise: Exercise
    @Environment(\.dismiss) private var dismiss
    @State private var selectedMuscle: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: NT.Spacing.section) {
                    header
                    let photos = ExerciseMedia.images[exercise.id] ?? []
                    if !photos.isEmpty {
                        FormDemoView(paths: photos, name: exercise.localizedName)
                    } else if let pattern = MotionLibrary.pattern(for: exercise.id) {
                        MotionDemoView(pattern: pattern, hot: MotionLibrary.hotSegments(for: exercise.primaryMuscles),
                                       name: exercise.localizedName)
                    }
                    musclesSection
                    if let cues = FormCues.cues(for: exercise.id) {
                        cuesSection(cues)
                    }
                    if !exercise.instructions.isEmpty {
                        stepsSection
                    }
                    if !photos.isEmpty {
                        Link("free-exercise-db · Public domain", destination: URL(string: "https://github.com/yuhonas/free-exercise-db")!)
                            .font(NT.Fonts.footnote)
                            .foregroundStyle(NT.Colors.ink3)
                    }
                }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.bottom, 32)
                .foregroundStyle(NT.Colors.ink)
            }
            .ntScreenBackground()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("common.done") { dismiss() } } }
        }
    }

    // MARK: Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(exercise.localizedName)
                .font(NT.Fonts.title2)
                .fixedSize(horizontal: false, vertical: true)
            let facts = ExerciseFacts.labels(equipment: exercise.equipment, level: exercise.level,
                                             mechanic: exercise.mechanic, force: exercise.force)
            if !facts.isEmpty {
                BroFlowLayout(spacing: 6) {
                    ForEach(facts, id: \.self) { fact in
                        Text(fact)
                            .font(NT.Fonts.caption)
                            .foregroundStyle(NT.Colors.ink2)
                            .padding(.horizontal, 10)
                            .frame(height: 26)
                            .background(NT.Colors.surface, in: Capsule())
                    }
                }
            }
        }
    }

    // MARK: Muscles

    private var musclesSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "exercises.musclesWorked")
            NTCard {
                VStack(alignment: .leading, spacing: 14) {
                    MuscleModelView(primary: exercise.primaryMuscles, secondary: exercise.secondaryMuscles,
                                    selected: $selectedMuscle)
                    Group {
                        if let selectedMuscle {
                            Text(verbatim: selectedLine(selectedMuscle))
                                .foregroundStyle(NT.Colors.ink)
                        } else {
                            Text("exercises.tapMuscle").foregroundStyle(NT.Colors.ink3)
                        }
                    }
                    .font(NT.Fonts.footnote)
                    .frame(maxWidth: .infinity)
                    Hairline()
                    muscleLegend(title: "exercises.primary", muscles: exercise.primaryMuscles, color: NT.Colors.ember)
                    if !exercise.secondaryMuscles.isEmpty {
                        muscleLegend(title: "exercises.secondary", muscles: exercise.secondaryMuscles, color: NT.Colors.heat[2])
                    }
                    Text("exercises.muscleNote")
                        .font(NT.Fonts.footnote)
                        .foregroundStyle(NT.Colors.ink3)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    private func selectedLine(_ muscle: String) -> String {
        let name = WorkoutStrings.muscle(muscle)
        if exercise.primaryMuscles.contains(muscle) { return name + " · " + String(localized: "exercises.role.primary") }
        if exercise.secondaryMuscles.contains(muscle) { return name + " · " + String(localized: "exercises.role.secondary") }
        return name + " · " + String(localized: "exercises.role.notUsed")
    }

    private func muscleLegend(title: LocalizedStringKey, muscles: [String], color: Color) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).eyebrow()
            BroFlowLayout(spacing: 6) {
                ForEach(muscles, id: \.self) { muscle in
                    Button {
                        withAnimation(.easeOut(duration: 0.15)) { selectedMuscle = selectedMuscle == muscle ? nil : muscle }
                    } label: {
                        HStack(spacing: 6) {
                            Circle().fill(color).frame(width: 8, height: 8)
                            Text(WorkoutStrings.muscle(muscle)).font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink)
                        }
                        .padding(.horizontal, 12)
                        .frame(height: 32)
                        .background(selectedMuscle == muscle ? NT.Colors.surface3 : NT.Colors.surface2, in: Capsule())
                    }
                    .buttonStyle(PressScale())
                }
            }
        }
    }

    // MARK: Form cues

    private func cuesSection(_ cues: FormCues.Entry) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "exercises.formCues")
            NTCard {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(cues.cues, id: \.self) { cue in
                        cueRow(cue, systemImage: "checkmark.circle.fill", color: NT.Colors.good)
                    }
                    if !cues.mistakes.isEmpty {
                        Hairline()
                        Text("exercises.mistakes").eyebrow()
                        ForEach(cues.mistakes, id: \.self) { mistake in
                            cueRow(mistake, systemImage: "xmark.circle.fill", color: NT.Colors.bad)
                        }
                    }
                }
            }
        }
    }

    private func cueRow(_ text: String, systemImage: String, color: Color) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Image(systemName: systemImage).foregroundStyle(color).font(.system(size: 15, weight: .semibold))
            Text(text).font(NT.Fonts.subheadline).fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: Steps

    private var stepsSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "exercises.instructions")
            NTCard {
                VStack(alignment: .leading, spacing: 14) {
                    ForEach(Array(exercise.instructions.enumerated()), id: \.offset) { index, instruction in
                        HStack(alignment: .top, spacing: 12) {
                            Text(verbatim: "\(index + 1)")
                                .font(NT.Fonts.footnoteBold)
                                .foregroundStyle(NT.Colors.ember)
                                .frame(width: 24, height: 24)
                                .background(NT.Colors.emberTint, in: Circle())
                            Text(instruction)
                                .font(NT.Fonts.subheadline)
                                .foregroundStyle(NT.Colors.ink)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
            }
        }
    }
}

/// The small facts under the exercise's name, localized: equipment, level, compound/isolation, push/pull/hold.
enum ExerciseFacts {
    static func labels(equipment: String?, level: String?, mechanic: String?, force: String?) -> [String] {
        var labels: [String] = []
        if let equipment, !equipment.isEmpty { labels.append(WorkoutStrings.equipment(equipment)) }
        for (prefix, raw) in [("exercises.level", level), ("exercises.mechanic", mechanic), ("exercises.force", force)] {
            guard let raw, !raw.isEmpty else { continue }
            let key = "\(prefix).\(raw == "advanced" ? "expert" : raw)"
            let value = String(localized: String.LocalizationValue(key))
            if value != key { labels.append(value) }
        }
        return labels
    }
}

/// The app's own form cues and common mistakes (`form_cues.json`, written for NoTomorrow) for the most common lifts,
/// in English and Polish.
enum FormCues {
    struct Entry: Decodable, Equatable {
        let cues: [String]
        let mistakes: [String]
    }

    static let all: [String: [String: Entry]] = ExerciseMedia.load("form_cues") ?? [:]

    /// `language` defaults to the catalog's language, the one `String(localized:)` uses.
    static func cues(for id: String, language: String = Bundle.main.preferredLocalizations.first ?? "en") -> Entry? {
        guard let byLanguage = all[id] else { return nil }
        return byLanguage[language] ?? byLanguage["en"]
    }
}

enum ExerciseMedia {
    struct Region: Decodable { let muscle: String; let view: String; let d: String }
    static let regions: [Region] = load("muscle_model") ?? []
    static let images: [String: [String]] = {
        let records: [ExerciseLibrary.Record] = load("exercises") ?? []
        return Dictionary(records.map { ($0.id, $0.images ?? []) }, uniquingKeysWith: { a, _ in a })
    }()
    static func url(_ path: String) -> URL? {
        guard !path.contains(".."), !path.contains(":") else { return nil }
        return URL(string: "https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/")?.appendingPathComponent(path)
    }
    static func load<T: Decodable>(_ name: String) -> T? {
        guard let url = Bundle.main.url(forResource: name, withExtension: "json"), let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }
}
