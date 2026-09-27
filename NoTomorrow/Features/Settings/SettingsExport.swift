import SwiftUI
import SwiftData

/// Export my data: builds workouts.csv (one row per set) and meals.csv in the temp directory and hands them to ShareLink.
struct ExportView: View {
    @Environment(\.modelContext) private var modelContext
    @State private var files: [URL] = []
    @State private var counts = ExportCSV.Counts()
    @State private var isPreparing = true

    var body: some View {
        STEditorScreen(title: "settings.export") {
            Text("settings.export.description")
                .font(NT.Fonts.subheadline)
                .foregroundStyle(NT.Colors.ink2)
                .fixedSize(horizontal: false, vertical: true)

            STGroup {
                STInfoRow(label: "settings.export.workouts", value: counts.workouts.formatted())
                STInfoRow(label: "settings.export.sets", value: counts.sets.formatted())
                STInfoRow(label: "settings.export.meals", value: counts.meals.formatted())
            }

            if isPreparing || files.isEmpty {
                SecondaryButton(title: "settings.export.preparing") {}
                    .opacity(0.5)
                    .disabled(true)
            } else {
                ShareLink(items: files) {
                    HStack(spacing: 8) {
                        Image(systemName: "square.and.arrow.up").font(.system(size: 16, weight: .semibold))
                        Text("settings.export.share").font(NT.Fonts.headline).lineLimit(1)
                    }
                    .foregroundStyle(NT.Colors.onPrimary)
                    .frame(maxWidth: .infinity)
                    .frame(height: NT.Size.primaryButton)
                    .background(NT.Colors.ink, in: Capsule())
                }
                .buttonStyle(PressScale())
            }
        }
        .task { prepare() }
    }

    private func prepare() {
        isPreparing = true
        let result = ExportCSV.build(in: modelContext)
        files = result.files
        counts = result.counts
        isPreparing = false
    }
}

// MARK: - CSV builder

enum ExportCSV {
    struct Counts {
        var workouts = 0
        var sets = 0
        var meals = 0
    }

    static func build(in context: ModelContext) -> (files: [URL], counts: Counts) {
        var counts = Counts()
        let stamp = Date.now.formatted(.iso8601.year().month().day())
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("NoTomorrow-export-\(stamp)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        var files: [URL] = []

        var workoutsDescriptor = FetchDescriptor<Workout>(sortBy: [SortDescriptor(\.startedAt)])
        workoutsDescriptor.predicate = #Predicate { $0.endedAt != nil }
        let workouts = (try? context.fetch(workoutsDescriptor)) ?? []
        counts.workouts = workouts.count
        var rows = ["workout_id,workout_name,started_at,ended_at,exercise,set,kind,weight_kg,reps,seconds,completed_at,pr,set_record"]
        for w in workouts {
            for ex in w.sortedExercises {
                for s in ex.sortedSets where s.isCompleted {
                    counts.sets += 1
                    rows.append(line([
                        w.id.uuidString, w.name, iso(w.startedAt), w.endedAt.map(iso) ?? "",
                        ex.exercise?.name ?? "", String(s.order + 1), s.kind.rawValue,
                        number(s.weightKg), String(s.reps), s.seconds > 0 ? String(s.seconds) : "", s.completedAt.map(iso) ?? "",
                        s.isPR ? "1" : "0", s.isSetRecord ? "1" : "0",
                    ]))
                }
            }
        }
        if let url = write(rows, to: dir.appendingPathComponent("workouts.csv")) { files.append(url) }

        let meals = (try? context.fetch(FetchDescriptor<MealEntry>(sortBy: [SortDescriptor(\.day), SortDescriptor(\.loggedAt)]))) ?? []
        counts.meals = meals.count
        var mealRows = ["day,slot,food,brand,grams,kcal,protein_g,carbs_g,fat_g,ai_estimate,logged_at"]
        for m in meals {
            mealRows.append(line([
                day(m.day), m.slot.rawValue, m.displayName, m.food?.brand ?? "",
                number(m.grams), number(m.kcal), number(m.proteinG), number(m.carbsG), number(m.fatG),
                m.isAIEstimate ? "1" : "0", iso(m.loggedAt),
            ]))
        }
        if let url = write(mealRows, to: dir.appendingPathComponent("meals.csv")) { files.append(url) }

        return (files, counts)
    }

    // MARK: Helpers (machine-readable: ISO dates, dot decimals — not user-facing text)

    private static func write(_ rows: [String], to url: URL) -> URL? {
        let text = rows.joined(separator: "\n") + "\n"
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
            return url
        } catch {
            return nil
        }
    }

    private static func line(_ fields: [String]) -> String {
        fields.map(escape).joined(separator: ",")
    }

    private static func escape(_ field: String) -> String {
        guard field.contains(",") || field.contains("\"") || field.contains("\n") else { return field }
        return "\"" + field.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }

    private static func iso(_ date: Date) -> String {
        date.formatted(.iso8601)
    }

    private static func day(_ date: Date) -> String {
        date.formatted(.iso8601.year().month().day())
    }

    private static func number(_ value: Double) -> String {
        value == value.rounded() ? String(Int(value)) : String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), value)
    }
}
