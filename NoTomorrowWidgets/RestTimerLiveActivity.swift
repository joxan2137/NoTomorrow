import ActivityKit
import WidgetKit
import SwiftUI

private enum W {
    static let ground = Color(red: 10/255, green: 10/255, blue: 11/255)
    static let ink = Color(red: 242/255, green: 242/255, blue: 244/255)
    static let ink2 = Color(red: 235/255, green: 235/255, blue: 245/255).opacity(0.6)
    static let ember = Color(red: 255/255, green: 106/255, blue: 43/255)
    static let track = Color(red: 42/255, green: 42/255, blue: 46/255)
}

struct RestTimerLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: RestTimerAttributes.self) { context in
            // Lock Screen / banner
            HStack(spacing: 14) {
                ring(context)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: context.attributes.restLabel)
                        .font(.system(size: 11, weight: .semibold)).tracking(0.9).textCase(.uppercase).foregroundStyle(W.ember)
                    if isOver(context) {
                        Text(verbatim: context.attributes.overLabel)
                            .font(.system(size: 22, weight: .bold)).foregroundStyle(W.ink)
                            .lineLimit(1).minimumScaleFactor(0.7)
                    } else {
                        countdown(context)
                            .font(.system(size: 34, weight: .bold)).monospacedDigit().foregroundStyle(W.ink)
                    }
                    Text(verbatim: detail(context))
                        .font(.system(size: 13)).foregroundStyle(W.ink2).lineLimit(1)
                }
                Spacer()
            }
            .padding(16)
            .activityBackgroundTint(W.ground)
            .activitySystemActionForegroundColor(W.ink)
            .widgetURL(RestTimerAttributes.deepLink)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    ring(context).frame(width: 44, height: 44).padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    countdown(context)
                        .font(.system(size: 30, weight: .bold)).monospacedDigit().foregroundStyle(W.ink)
                        .frame(width: 84, alignment: .trailing)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text(verbatim: isOver(context) ? context.attributes.overLabel : detail(context))
                        .font(.system(size: 13)).foregroundStyle(W.ink2).lineLimit(1)
                }
            } compactLeading: {
                Image(systemName: "timer").foregroundStyle(W.ember)
            } compactTrailing: {
                countdown(context)
                    .monospacedDigit().foregroundStyle(W.ink).frame(width: 46)
            } minimal: {
                Image(systemName: "timer").foregroundStyle(W.ember)
            }
            .keylineTint(W.ember)
            .widgetURL(RestTimerAttributes.deepLink)
        }
    }

    /// Counts down over a fixed range (the rest's own start…end), never `now…end`: that range traps once the
    /// rest has ended and the system re-renders the activity. Past the end it simply reads 0:00.
    private func countdown(_ context: ActivityViewContext<RestTimerAttributes>) -> Text {
        let end = context.state.endDate
        let start = end.addingTimeInterval(-Double(max(1, context.state.totalSeconds)))
        return Text(timerInterval: start...end, countsDown: true)
    }

    private func isOver(_ context: ActivityViewContext<RestTimerAttributes>) -> Bool {
        context.isStale || context.state.endDate <= .now
    }

    private func detail(_ context: ActivityViewContext<RestTimerAttributes>) -> String {
        let state = context.state
        return state.nextSetLabel.isEmpty ? state.exerciseName : "\(state.exerciseName) · \(state.nextSetLabel)"
    }

    @ViewBuilder
    private func ring(_ context: ActivityViewContext<RestTimerAttributes>) -> some View {
        let total = max(1, Double(context.state.totalSeconds))
        ZStack {
            Circle().stroke(W.track, lineWidth: 5)
            ProgressView(timerInterval: context.state.endDate.addingTimeInterval(-total)...context.state.endDate, countsDown: true, label: { EmptyView() }, currentValueLabel: { EmptyView() })
                .progressViewStyle(.circular)
                .tint(W.ember)
        }
        .frame(width: 48, height: 48)
    }
}
