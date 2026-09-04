import SwiftUI
import SwiftData

/// Bro tab. Paired: shared-week card, heads-up chips, log. Unpaired: code card + enter code.
struct BroView: View {
    @Environment(\.modelContext) private var modelContext
    @Query(sort: \AttendanceRecord.day, order: .reverse) private var records: [AttendanceRecord]
    @Query(sort: \HeadsUp.sentAt, order: .reverse) private var headsUps: [HeadsUp]
    @Query(sort: \Workout.startedAt, order: .reverse) private var workouts: [Workout]
    @Query(sort: \Routine.order) private var routines: [Routine]
    @Query private var schedules: [GymSchedule]
    @Query private var profiles: [UserProfile]
    @Query private var pairings: [BroPairing]

    @State private var showCantMakeIt = false

    private var bro: BroService { BroShared.service }
    private var schedule: GymSchedule? { schedules.first }
    private var pairing: BroPairing? { pairings.first }
    /// Real backend without a session: pairing is impossible, so the tab shows "Sign in to pair" and ignores
    /// any pairing cached from an earlier session.
    private var needsSignIn: Bool { AuthStore.shared.needsSignIn }
    private var isPaired: Bool { !needsSignIn && (bro.isPaired || pairing != nil) }
    private var partnerName: String { bro.partner?.name ?? pairing?.partnerName ?? "" }
    private var myName: String { profiles.first?.name ?? "" }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                if needsSignIn {
                    BroSignedOutView { Task { await bro.refresh(in: modelContext) } }
                } else if isPaired {
                    pairedContent
                } else {
                    BroUnpairedView(myCode: bro.myCode ?? pairing?.myCode, isLoading: bro.isLoading) { code in
                        await bro.pair(code: code, in: modelContext)
                    }
                }
            }
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.top, 8)
            .padding(.bottom, 40)
        }
        .scrollDismissesKeyboard(.interactively)
        .ntScreenBackground()
        .refreshable { await bro.refresh(in: modelContext) }
        .task { await lifecycle() }
        .sheet(isPresented: $showCantMakeIt) {
            CantMakeItSheet(sessionDay: cantMakeItDay, minuteOfDay: cantMakeItMinute, routineName: sessionLine?.routineName) {}
        }
    }

    // MARK: Header

    private var header: some View {
        HStack(alignment: .bottom, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(isPaired ? "\(myName) & \(partnerName)" : String(localized: "bro.title")).eyebrow()
                Text(isPaired ? "bro.title" : "bro.unpaired.title")
                    .font(NT.Fonts.largeTitle)
                    .foregroundStyle(NT.Colors.ink)
            }
            Spacer(minLength: 8)
            if isPaired {
                streakChip.padding(.bottom, 6)
            }
        }
    }

    private var streakChip: some View {
        let together = AttendanceService.sessionsTogether(records: records)
        return HStack(spacing: 6) {
            Image(systemName: "flame")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(NT.Colors.ember)
            Text("bro.together \(together.together) \(together.total)")
                .font(NT.Fonts.footnote)
                .foregroundStyle(NT.Colors.ink)
                .tabular()
        }
        .padding(.horizontal, 12)
        .frame(height: 32)
        .background(NT.Colors.surface, in: Capsule())
    }

    // MARK: Paired

    private var pairedContent: some View {
        VStack(alignment: .leading, spacing: 0) {
            BroSharedWeekCard(week: week, partnerName: partnerName, session: sessionLine)
                .padding(.top, 20)
            BroHeadsUpRow(partnerName: partnerName, sessionDay: cantMakeItDay) { showCantMakeIt = true }
                .padding(.top, NT.Spacing.section)
            BroLogSection(rows: logRows, partnerName: partnerName)
                .padding(.top, NT.Spacing.section)
        }
    }

    // MARK: Derived

    private var week: [WeekDay] {
        AttendanceService.currentWeek(schedule: schedule, records: records)
    }

    private var sessionLine: BroSessionLine? {
        BroDerived.sessionLine(schedule: schedule, records: records, routines: routines, workouts: workouts)
    }

    private var logRows: [BroLogRow] {
        BroDerived.logRows(schedule: schedule, records: records, workouts: workouts, headsUps: headsUps,
                           profileCreatedAt: profiles.first?.createdAt,
                           pairedAt: bro.partner?.pairedAt ?? pairing?.pairedAt)
    }

    private var cantMakeItDay: Date {
        Calendar.current.startOfDay(for: sessionLine?.day ?? .now)
    }

    private var cantMakeItMinute: Int {
        sessionLine?.minuteOfDay ?? schedule?.defaultMinuteOfDay ?? 18 * 60
    }

    // MARK: Lifecycle

    /// Marks stale planned days as missed, then keeps the partner state fresh while the tab is visible
    /// (the mock partner confirms 20 s after you do).
    private func lifecycle() async {
        AttendanceService.markPastPlannedAsMissed(schedule: schedule, context: modelContext)
        while !Task.isCancelled {
            await bro.refresh(in: modelContext)
            if bro.isPaired, bro.myCode == nil { _ = await bro.createCode() }
            try? await Task.sleep(for: .seconds(15))
        }
    }
}
