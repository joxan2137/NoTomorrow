import SwiftUI
import SwiftData

/// Full-screen cover for the workout in progress (presented on `session.showsActiveWorkout`).
/// Header (collapse · title · Finish) · exercise sections with the set table · floating rest pill ·
/// finish → `WorkoutDoneView` inside the same cover. Collapsing hands the workout to the mini bar on every tab.
struct ActiveWorkoutView: View {
    let workout: Workout

    @Environment(\.modelContext) private var context
    @Environment(RestTimerController.self) private var restTimer
    @Environment(WorkoutSessionController.self) private var session
    @Environment(\.dismiss) private var dismiss

    /// The session's model for this workout (owned by `WorkoutSessionController`, so it outlives a collapse);
    /// kept here too so the cover can finish animating out after the session lets go.
    @State private var model: ActiveWorkoutModel?
    @State private var showsFinishDialog = false
    @State private var showsPicker = false
    @State private var showsRestSheet = false
    @FocusState private var focus: SetField?

    var body: some View {
        ZStack {
            if let model {
                if model.showsSummary {
                    WorkoutDoneView(workout: workout, unit: model.unit, onDone: {
                        model.commitFinish(session: session)
                        dismiss()
                    }, onEditSets: {
                        withAnimation(.easeInOut(duration: 0.25)) { model.reopen() }
                    })
                    .transition(.move(edge: .trailing).combined(with: .opacity))
                } else {
                    content(model)
                        .transition(.opacity)
                }
            }
        }
        .ntScreenBackground()
        .onAppear {
            guard model == nil else { return }
            let shared = session.model(for: workout, context: context)
            // History may have been edited (or the unit changed) while the workout sat in the mini bar.
            shared.reloadPrevious()
            model = shared
        }
        .background { RestTimerExpiryWatcher() }
        .sheet(isPresented: $showsRestSheet) {
            RestTimerView(workout: workout, upNext: model?.upNext)
        }
        // A rest notification / Live Activity tap asks for the rest sheet; wait for the cover to finish presenting.
        .task(id: session.wantsRestSheet) {
            guard session.wantsRestSheet else { return }
            try? await Task.sleep(for: .milliseconds(450))
            guard !Task.isCancelled else { return }
            session.wantsRestSheet = false
            if restTimer.isRunning, model?.showsSummary != true { showsRestSheet = true }
        }
    }

    // MARK: Workout

    private func content(_ model: ActiveWorkoutModel) -> some View {
        VStack(spacing: 0) {
            header(model)
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.top, 8)
            if !model.exercises.isEmpty {
                rail(model)
                    .padding(.horizontal, NT.Spacing.screenH)
                    .padding(.top, 6)
            }
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(model.exercises) { exercise in
                            let expanded = model.expandedExerciseID == exercise.persistentModelID
                            WorkoutExerciseSection(exercise: exercise, model: model, isExpanded: expanded, focus: $focus,
                                                   onToggleSet: { toggle($0, in: exercise, model: model) },
                                                   onDeleteSet: { deleteSet($0, in: exercise, model: model) })
                            .id(exercise.persistentModelID)
                            Hairline().padding(.top, expanded ? 8 : 0)
                        }
                        GhostButton(title: "workout.addExercise", systemImage: "plus") { showsPicker = true }
                            .padding(.top, 14)
                        Color.clear.frame(height: restTimer.isRunning ? 96 : 24)
                    }
                    .padding(.horizontal, NT.Spacing.screenH)
                }
                .scrollDismissesKeyboard(.interactively)
                // Expanding from the mini bar lands on the exercise the user was on.
                .onAppear {
                    guard let id = model.expandedExerciseID, id != model.exercises.first?.persistentModelID else { return }
                    proxy.scrollTo(id, anchor: .top)
                }
            }
        }
        .overlay(alignment: .bottom) {
            if restTimer.isRunning {
                RestPillView { showsRestSheet = true }
                    .padding(.horizontal, NT.Spacing.screenH)
                    .padding(.bottom, 14)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.35, dampingFraction: 0.85), value: restTimer.isRunning)
        .animation(.easeInOut(duration: 0.2), value: model.expandedExerciseID)
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("common.done") { focus = nil }
                    .font(NT.Fonts.headline)
            }
        }
        // An alert, not a confirmation dialog: on iOS 26 a dialog here turns into a popover floating over the
        // exercise list, pointing at nothing, and drops its Cancel. The alert keeps Cancel on screen.
        .alert("workout.finishConfirm", isPresented: $showsFinishDialog) {
            Button("workout.finish") { finish(model) }
            if workout.completedSetCount == 0 {
                Button("workout.discard", role: .destructive) {
                    restTimer.skip()
                    model.discard(session: session)
                    dismiss()
                }
            }
            Button("common.cancel", role: .cancel) {}
        }
        .sheet(isPresented: $showsPicker, onDismiss: { model.reloadPrevious() }) {
            ExercisePickerView(workout: workout)
        }
    }

    private func header(_ model: ActiveWorkoutModel) -> some View {
        HStack(alignment: .center, spacing: 8) {
            Button(action: collapse) {
                Image(systemName: "chevron.down")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(NT.Colors.ink)
                    .frame(width: 36, height: 36)
                    .background(NT.Colors.surface2, in: Circle())
                    .frame(width: NT.Size.control, height: NT.Size.control)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressScale())
            .accessibilityLabel(Text("workout.minimize"))
            .padding(.leading, -(NT.Size.control - 36) / 2)
            VStack(alignment: .leading, spacing: 2) {
                Text(workout.name).font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink).lineLimit(1)
                HStack(spacing: 6) {
                    Circle().fill(NT.Colors.ember).frame(width: 6, height: 6)
                    TimelineView(.periodic(from: .now, by: 1)) { ctx in
                        Text(Fmt.elapsed((workout.endedAt ?? ctx.date).timeIntervalSince(workout.startedAt)))
                            .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ember).tabular()
                    }
                    if !model.exercises.isEmpty {
                        Text(verbatim: "·").font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                        Text("workout.exerciseOf \(model.currentExerciseIndex) \(model.exercises.count)")
                            .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular()
                    }
                }
            }
            Spacer(minLength: 12)
            Button {
                focus = nil
                showsFinishDialog = true
            } label: {
                Text("workout.finish")
                    .font(NT.Fonts.subheadlineBold).foregroundStyle(NT.Colors.ink)
                    .padding(.horizontal, 18)
                    .frame(height: NT.Size.control)
                    .background(NT.Colors.surface2, in: Capsule())
            }
            .buttonStyle(PressScale())
        }
    }

    /// One 4 pt capsule per exercise, filled in ember by its completed share of sets; the current one's track is
    /// brighter. A tap (16 pt tall target) opens that exercise, as its collapsed row does.
    private func rail(_ model: ActiveWorkoutModel) -> some View {
        let exercises = model.exercises
        let current = model.currentExerciseIndex - 1
        return HStack(spacing: 4) {
            ForEach(Array(exercises.enumerated()), id: \.element.persistentModelID) { index, exercise in
                let done = Double(exercise.sets.filter(\.isCompleted).count)
                let fraction = exercise.sets.isEmpty ? 0 : done / Double(exercise.sets.count)
                Button {
                    if model.expandedExerciseID != exercise.persistentModelID { model.toggleExpanded(exercise) }
                } label: {
                    Capsule()
                        .fill(Color.white.opacity(index == current ? 0.24 : 0.10))
                        .overlay(alignment: .leading) {
                            GeometryReader { geo in
                                Rectangle().fill(NT.Colors.ember).frame(width: geo.size.width * fraction)
                            }
                        }
                        .clipShape(Capsule())
                        .frame(height: 4)
                        .frame(maxWidth: .infinity)
                        .frame(height: 16)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(verbatim: exercise.exercise?.localizedName ?? ""))
            }
        }
        .animation(.easeOut(duration: 0.3), value: model.workout.completedSetCount)
    }

    // MARK: Actions

    /// Hands the workout to the mini bar; everything already typed is saved.
    private func collapse() {
        focus = nil
        session.collapse(context: context)
    }

    private func toggle(_ set: SetEntry, in exercise: WorkoutExercise, model: ActiveWorkoutModel) {
        if set.isCompleted {
            model.uncomplete(set)
        } else {
            focus = nil
            model.complete(set, in: exercise, restTimer: restTimer)
            if set.isCompleted {
                Haptics.tap()
            } else {
                // Nothing to log yet: the row stays open and the reps cell asks for a number.
                Haptics.warning()
                focus = SetField(setID: set.persistentModelID, isReps: true)
            }
        }
    }

    private func deleteSet(_ set: SetEntry, in exercise: WorkoutExercise, model: ActiveWorkoutModel) {
        focus = nil
        withAnimation(.easeInOut(duration: 0.2)) { model.removeSet(set, in: exercise) }
    }

    private func finish(_ model: ActiveWorkoutModel) {
        focus = nil
        restTimer.skip()
        Haptics.success()
        withAnimation(.easeInOut(duration: 0.3)) { model.finish() }
    }
}
