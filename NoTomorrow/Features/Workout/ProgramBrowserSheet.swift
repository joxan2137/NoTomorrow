import SwiftUI
import SwiftData

/// "Browse programs" from the Train tab: the built-in programs (`ProgramLibrary`) with their description, days a week
/// and routine count; tapping one shows its routines and exercises and "Add N routines", which writes them through
/// `RoutineStore.add` (unique names, appended to the routine list).
struct ProgramBrowserSheet: View {
    var programs: [TrainingProgram] = ProgramLibrary.all

    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context
    @State private var selected: TrainingProgram?
    /// Library id → localized exercise name, for the detail's lines.
    @State private var exerciseNames: [String: String] = [:]

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let selected {
                        detail(selected)
                    } else {
                        list
                    }
                }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.bottom, NT.Spacing.section)
            }
            .id(selected?.id ?? "")
            if let selected {
                addBar(selected)
            }
        }
        .ntScreenBackground()
        .presentationBackground(NT.Colors.ground)
        .presentationDragIndicator(.visible)
        .onAppear(perform: loadNames)
    }

    // MARK: Header

    /// Back (on a program) · Programs · Done.
    private var header: some View {
        ZStack {
            Text("program.title")
                .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).lineLimit(1)
            HStack {
                if selected != nil {
                    Button { selected = nil } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "chevron.left").font(.system(size: 15, weight: .semibold))
                            Text("common.back").font(NT.Fonts.body)
                        }
                        .foregroundStyle(NT.Colors.ink2)
                        .frame(minHeight: NT.Size.control)
                    }
                }
                Spacer()
                Button { dismiss() } label: {
                    Text("common.done").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink2)
                        .frame(minHeight: NT.Size.control)
                }
            }
        }
        .padding(.horizontal, NT.Spacing.screenH)
        .padding(.top, 16)
        .padding(.bottom, 12)
    }

    // MARK: List

    private var list: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("program.intro")
                .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.bottom, 6)
            ForEach(programs) { program in
                Button { selected = program } label: { programCard(program) }
                    .buttonStyle(PressScale())
            }
        }
        .padding(.top, 4)
    }

    private func programCard(_ program: TrainingProgram) -> some View {
        NTCard {
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(TrainingProgram.text(program.name))
                        .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink).lineLimit(1)
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold)).foregroundStyle(NT.Colors.ink3)
                }
                Text(TrainingProgram.text(program.summary))
                    .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                meta(program)
                    .padding(.top, 4)
            }
        }
    }

    /// The level badge, then "3× a week · 3 routines".
    private func meta(_ program: TrainingProgram) -> some View {
        HStack(spacing: 8) {
            Badge(text: LocalizedStringKey(program.level.titleKey))
            Text(verbatim: Self.metaLine(program))
                .font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink2).tabular().lineLimit(1)
        }
    }

    static func metaLine(_ program: TrainingProgram) -> String {
        let perWeek = String(format: String(localized: "program.perWeek"), locale: .current, program.daysPerWeek)
        return perWeek + " · " + WorkoutStrings.routines(program.routines.count)
    }

    // MARK: Detail

    private func detail(_ program: TrainingProgram) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(TrainingProgram.text(program.name))
                .font(NT.Fonts.title1).foregroundStyle(NT.Colors.ink)
                .fixedSize(horizontal: false, vertical: true)
            Text(TrainingProgram.text(program.summary))
                .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 6)
            meta(program)
                .padding(.top, 10)
            ForEach(program.routines) { day in
                SectionHeader(title: LocalizedStringKey(day.name),
                              trailing: Text(verbatim: WorkoutStrings.exercises(day.items.count)))
                    .padding(.top, NT.Spacing.section)
                    .padding(.bottom, 4)
                VStack(spacing: 0) {
                    ForEach(Array(day.items.enumerated()), id: \.offset) { index, line in
                        if index > 0 { Hairline() }
                        lineRow(line)
                    }
                }
            }
        }
        .padding(.top, 4)
    }

    /// "Bench Press ······ 3 × 8  2:00".
    private func lineRow(_ line: TrainingProgram.Line) -> some View {
        HStack(spacing: 12) {
            Text(exerciseNames[line.exercise] ?? line.exercise.replacingOccurrences(of: "_", with: " "))
                .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink).lineLimit(1)
            Spacer(minLength: 8)
            Text(verbatim: "\(line.sets) × \(line.reps)")
                .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular()
            Text(verbatim: RoutineRest.label(line.rest))
                .font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink3).tabular()
                .frame(minWidth: 34, alignment: .trailing)
        }
        .padding(.vertical, 10)
    }

    // MARK: Add

    private func addBar(_ program: TrainingProgram) -> some View {
        PrimaryButton(title: LocalizedStringKey(WorkoutStrings.addRoutines(program.routines.count))) {
            RoutineStore.add(program, in: context)
            Haptics.tap()
            dismiss()
        }
        .padding(.horizontal, NT.Spacing.screenH)
        .padding(.vertical, 8)
        .background(NT.Colors.ground)
    }

    private func loadNames() {
        let ids = Array(Set(programs.flatMap(\.exerciseIDs)))
        let found = (try? context.fetch(FetchDescriptor<Exercise>(predicate: #Predicate { ids.contains($0.id) }))) ?? []
        exerciseNames = Dictionary(found.map { ($0.id, $0.localizedName) }, uniquingKeysWith: { first, _ in first })
    }
}
