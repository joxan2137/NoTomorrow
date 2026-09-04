import SwiftUI
import SwiftData

/// Full-screen cover for the workout in progress (presented on `session.showsActiveWorkout`).
/// Header · exercise sections with the set table · floating rest pill · finish → `WorkoutDoneView` inside the same cover.
struct ActiveWorkoutView: View {
    let workout: Workout

    @Environment(\.modelContext) private var context
    @Environment(RestTimerController.self) private var restTimer
    @Environment(WorkoutSessionController.self) private var session
    @Environment(\.dismiss) private var dismiss

    @State private var model: ActiveWorkoutModel?
    @State private var showsFinishDialog = false
    @State private var showsDone = false
    @State private var showsPicker = false
    @State private var showsRestSheet = false
    @FocusState private var focus: SetField?

    var body: some View {
        ZStack {
            if let model {
                if showsDone {
                    WorkoutDoneView(workout: workout, endedAt: model.finishedAt, onDone: {
                        model.commitFinish(session: session)
                        dismiss()
                    }, onEditSets: {
                        model.reopen()
                        withAnimation(.easeInOut(duration: 0.25)) { showsDone = false }
                    })
                    .transition(.move(edge: .trailing).combined(with: .opacity))
                } else {
                    content(model)
                        .transition(.opacity)
                }
            }
        }
        .ntScreenBackground()
        .onAppear { if model == nil { model = ActiveWorkoutModel(workout: workout, context: context) } }
        .background { RestTimerExpiryWatcher() }
    }

    // MARK: Workout

    private func content(_ model: ActiveWorkoutModel) -> some View {
        VStack(spacing: 0) {
            header(model)
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.top, 8)
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(model.exercises) { exercise in
                        let expanded = model.expandedExerciseID == exercise.persistentModelID
                        WorkoutExerciseSection(exercise: exercise, model: model, isExpanded: expanded, focus: $focus) { set in
                            toggle(set, in: exercise, model: model)
                        }
                        Hairline().padding(.top, expanded ? 8 : 0)
                    }
                    GhostButton(title: "workout.addExercise", systemImage: "plus") { showsPicker = true }
                        .padding(.top, 14)
                    Color.clear.frame(height: restTimer.isRunning ? 96 : 24)
                }
                .padding(.horizontal, NT.Spacing.screenH)
            }
            .scrollDismissesKeyboard(.interactively)
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
        .confirmationDialog("workout.finishConfirm", isPresented: $showsFinishDialog, titleVisibility: .visible) {
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
        .sheet(isPresented: $showsRestSheet) {
            RestTimerView(workout: workout, upNext: model.upNext)
        }
    }

    private func header(_ model: ActiveWorkoutModel) -> some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(workout.name).font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink).lineLimit(1)
                HStack(spacing: 6) {
                    Circle().fill(NT.Colors.ember).frame(width: 6, height: 6)
                    TimelineView(.periodic(from: .now, by: 1)) { ctx in
                        Text(Fmt.clock((workout.endedAt ?? ctx.date).timeIntervalSince(workout.startedAt)))
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

    // MARK: Actions

    private func toggle(_ set: SetEntry, in exercise: WorkoutExercise, model: ActiveWorkoutModel) {
        if set.isCompleted {
            model.uncomplete(set)
        } else {
            focus = nil
            model.complete(set, in: exercise, restTimer: restTimer)
            Haptics.tap()
        }
    }

    private func finish(_ model: ActiveWorkoutModel) {
        focus = nil
        restTimer.skip()
        model.finish()
        Haptics.success()
        withAnimation(.easeInOut(duration: 0.3)) { showsDone = true }
    }
}
