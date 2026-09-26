import SwiftUI
import SwiftData
import UniformTypeIdentifiers

/// Settings > Import workouts: pick a CSV exported from Strong, Hevy or NoTomorrow, see what it holds, import it.
struct ImportView: View {
    /// A file already read when the screen opens (screenshot tests); normally nil until one is picked.
    var initialPreview: WorkoutImport.Parsed? = nil
    var initialFileName: String = ""

    @Environment(\.modelContext) private var modelContext
    @Query private var profiles: [UserProfile]

    @State private var showsPicker = false
    @State private var parsed: WorkoutImport.Parsed?
    @State private var fileName = ""
    @State private var failed = false
    @State private var summary: WorkoutImporter.Summary?

    private var unit: WeightUnit { profiles.first?.units ?? .kg }

    var body: some View {
        STEditorScreen(title: "import.title") {
            Text("import.description")
                .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                .fixedSize(horizontal: false, vertical: true)

            if let summary {
                STGroup(title: "import.done") {
                    STInfoRow(label: "settings.export.workouts", value: summary.workouts.formatted())
                    STInfoRow(label: "settings.export.sets", value: summary.sets.formatted())
                    if summary.duplicates > 0 {
                        STInfoRow(label: "import.duplicates", value: summary.duplicates.formatted())
                    }
                    if !summary.newExercises.isEmpty {
                        STInfoRow(label: "import.newExercises", value: summary.newExercises.count.formatted())
                    }
                }
                if !summary.newExercises.isEmpty {
                    Text("import.newExercisesNote")
                        .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 16)
                }
            } else if let parsed {
                STGroup(title: LocalizedStringKey(stringLiteral: fileName)) {
                    STInfoRow(label: "import.format", value: Self.formatName(parsed.format))
                    STInfoRow(label: "settings.export.workouts", value: parsed.workouts.count.formatted())
                    STInfoRow(label: "settings.export.sets",
                              value: parsed.workouts.reduce(0) { $0 + $1.setCount }.formatted())
                    if let first = parsed.workouts.map(\.startedAt).min(), let last = parsed.workouts.map(\.startedAt).max() {
                        STInfoRow(label: "import.range", value: "\(Self.day(first)) – \(Self.day(last))")
                    }
                    if parsed.skippedRows > 0 {
                        STInfoRow(label: "import.skipped", value: parsed.skippedRows.formatted())
                    }
                }
                PrimaryButton(title: "import.confirm \(parsed.workouts.count)", isEnabled: !parsed.workouts.isEmpty) {
                    runImport(parsed)
                }
            }

            if failed {
                Text("import.failed")
                    .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.bad)
                    .fixedSize(horizontal: false, vertical: true)
            }

            SecondaryButton(title: parsed == nil || summary != nil ? "import.choose" : "import.chooseAnother",
                            systemImage: "doc") { showsPicker = true }
        }
        .fileImporter(isPresented: $showsPicker, allowedContentTypes: [.commaSeparatedText, .plainText, .text]) { result in
            load(result)
        }
        .onAppear {
            if parsed == nil, summary == nil, let initialPreview {
                parsed = initialPreview
                fileName = initialFileName
            }
        }
    }

    private static func day(_ date: Date) -> String {
        date.formatted(.dateTime.day().month(.abbreviated).year().locale(Fmt.locale))
    }

    static func formatName(_ format: WorkoutImport.Format) -> String {
        switch format {
        case .strong: "Strong"
        case .hevy: "Hevy"
        case .noTomorrow: "No Tomorrow"
        }
    }

    private func load(_ result: Result<URL, Error>) {
        failed = false
        summary = nil
        parsed = nil
        guard case .success(let url) = result else { return }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url),
              let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1),
              let result = try? WorkoutImport.parse(text, unit: unit) else {
            failed = true
            return
        }
        fileName = url.lastPathComponent
        parsed = result
    }

    private func runImport(_ parsed: WorkoutImport.Parsed) {
        summary = WorkoutImporter.importWorkouts(parsed.workouts, into: modelContext)
        Haptics.success()
    }
}
