import SwiftUI

/// The one card on the dashboard (its `FocalCard`): next session time, relative day + countdown, bro row, action row.
struct NextSessionCard: View {
    var session: DashboardSession?
    var routineName: String?
    var isPaired: Bool
    var partnerName: String?
    var myState: DayState
    var myTime: Date?
    var partnerState: DayState
    var partnerTime: Date?
    var hasActiveWorkout: Bool
    /// A finished workout with a completed set started today.
    var trainedToday: Bool = false
    var isConfirming: Bool
    var onConfirm: () -> Void
    var onStart: () -> Void
    var onCantMakeIt: () -> Void

    private var sessionIsToday: Bool { session?.isToday ?? false }
    private var isIn: Bool { sessionIsToday && (myState == .confirmed || myState == .attended) }
    private var isOut: Bool { sessionIsToday && myState.isMissedOrCancelled }
    private var partnerIsIn: Bool { partnerState == .confirmed || partnerState == .attended }

    var body: some View {
        FocalCard {
            VStack(alignment: .leading, spacing: 14) {
                titleRow
                heroRow
                Hairline()
                statusRow
                buttonRow
            }
        }
    }

    // MARK: Rows

    private var titleRow: some View {
        HStack {
            Text(eyebrowKey)
                .eyebrow(NT.Colors.ember)
            Spacer()
            if let routineName {
                Text(routineName).font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink2)
            }
        }
    }

    @ViewBuilder
    private var heroRow: some View {
        if let session {
            HStack(alignment: .lastTextBaseline, spacing: 12) {
                Text(Fmt.time(minuteOfDay: session.minuteOfDay))
                    .font(NT.Fonts.display(64))
                    .foregroundStyle(NT.Colors.ink)
                    .tabular()
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                VStack(alignment: .leading, spacing: 2) {
                    Text(Fmt.relativeDay(session.date))
                        .font(NT.Fonts.headline)
                        .foregroundStyle(NT.Colors.ink)
                    TimelineView(.periodic(from: .now, by: 60)) { context in
                        Text(countdown(to: session.date, now: context.date))
                            .font(NT.Fonts.subheadline)
                            .foregroundStyle(NT.Colors.ink2)
                            .tabular()
                    }
                }
                .padding(.bottom, 4)
            }
        } else {
            Text("dashboard.noSchedule")
                .font(NT.Fonts.headline)
                .foregroundStyle(NT.Colors.ink)
                .padding(.vertical, 8)
        }
    }

    @ViewBuilder
    private var statusRow: some View {
        if !sessionIsToday {
            HStack(spacing: 10) {
                Image(systemName: "moon.zzz")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(NT.Colors.ink2)
                Text("dashboard.restDay")
                    .font(NT.Fonts.subheadlineBold)
                    .foregroundStyle(NT.Colors.ink2)
            }
        } else if !isPaired {
            HStack(spacing: 10) {
                avatarStack
                Text(soloKey)
                    .font(NT.Fonts.subheadlineBold)
                    .foregroundStyle(isIn || isOut ? NT.Colors.ink : NT.Colors.ink2)
                    .lineLimit(2)
                Spacer(minLength: 8)
                if isIn, let myTime { timeStamp(myTime, color: NT.Colors.good) }
            }
        } else {
            HStack(spacing: 10) {
                avatarStack
                Text(broLine)
                    .font(NT.Fonts.subheadlineBold)
                    .foregroundStyle(NT.Colors.ink)
                    .lineLimit(2)
                Spacer(minLength: 8)
                broTimes
            }
        }
    }

    private var buttonRow: some View {
        HStack(spacing: 10) {
            if hasActiveWorkout {
                PrimaryButton(title: "dashboard.resumeWorkout", height: NT.Size.cardButton, action: onStart)
            } else if !sessionIsToday {
                SecondaryButton(title: "dashboard.startWorkout", height: NT.Size.cardButton, action: onStart)
            } else if isIn {
                PrimaryButton(title: "dashboard.startWorkout", height: NT.Size.cardButton, action: onStart)
            } else {
                PrimaryButton(title: "dashboard.imIn", height: NT.Size.cardButton, isEnabled: !isConfirming, action: onConfirm)
            }
            if Self.offersCantMakeIt(sessionIsToday: sessionIsToday, myState: myState, trainedToday: trainedToday) {
                SecondaryButton(title: "dashboard.cantMakeIt", height: NT.Size.cardButton, action: onCantMakeIt)
            }
        }
    }

    /// "Can't make it" is for a session still ahead today: not once I am out, and not once I trained (it would
    /// cancel a day that is already attended).
    static func offersCantMakeIt(sessionIsToday: Bool, myState: DayState, trainedToday: Bool) -> Bool {
        sessionIsToday && !myState.isMissedOrCancelled && myState != .attended && !trainedToday
    }

    // MARK: Keys

    private var eyebrowKey: LocalizedStringKey {
        session == nil || sessionIsToday ? "dashboard.nextSession" : "dashboard.restDay.next"
    }

    private var soloKey: LocalizedStringKey {
        if isIn { return "dashboard.youreIn" }
        if isOut { return "dashboard.youreOut" }
        return "dashboard.noBro"
    }

    // MARK: Bro row pieces

    private var broLine: String {
        let name = partnerName ?? ""
        switch (isIn, partnerIsIn) {
        case (true, true): return String(localized: "dashboard.bothIn")
        case (false, true): return String(format: String(localized: "dashboard.broIsIn"), name)
        case (true, false):
            return partnerState.isMissedOrCancelled
                ? String(format: String(localized: "dashboard.broOut"), name)
                : String(localized: "dashboard.youreIn")
        case (false, false):
            if isOut { return String(localized: "dashboard.youreOut") }
            return partnerState.isMissedOrCancelled
                ? String(format: String(localized: "dashboard.broOut"), name)
                : String(format: String(localized: "dashboard.broNotYet"), name)
        }
    }

    @ViewBuilder
    private var broTimes: some View {
        switch (isIn, partnerIsIn) {
        case (true, true):
            HStack(spacing: 6) {
                Circle().fill(NT.Colors.good).frame(width: 8, height: 8)
                Text([myTime, partnerTime].compactMap { $0 }.map { Fmt.time($0) }.joined(separator: " · "))
                    .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular()
            }
        case (false, true):
            if let partnerTime { timeStamp(partnerTime, color: NT.Colors.good) }
        case (true, false):
            if let myTime { timeStamp(myTime, color: NT.Colors.good) }
        case (false, false):
            EmptyView()
        }
    }

    private func timeStamp(_ date: Date, color: Color) -> some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(Fmt.time(date)).font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular()
        }
    }

    private var avatarStack: some View {
        HStack(spacing: -8) {
            participantAvatar(initial: "", background: NT.Colors.surface2, isIn: isIn, isOut: isOut, dimmed: !isIn)
            if isPaired {
                participantAvatar(initial: partnerName ?? "", background: NT.Colors.surface3,
                                  isIn: partnerIsIn, isOut: partnerState.isMissedOrCancelled, dimmed: !partnerIsIn)
            }
        }
    }

    private func participantAvatar(initial: String, background: Color, isIn: Bool, isOut: Bool, dimmed: Bool) -> some View {
        Avatar(initial: initial.isEmpty ? " " : initial, size: 28, background: background, dimmed: dimmed)
            .overlay(Circle().strokeBorder(isIn ? NT.Colors.good : (isOut ? NT.Colors.bad : NT.Colors.surface), lineWidth: 2))
    }

    private func countdown(to date: Date, now: Date) -> String {
        date <= now ? String(localized: "dashboard.now") : Fmt.countdown(to: date)
    }
}
