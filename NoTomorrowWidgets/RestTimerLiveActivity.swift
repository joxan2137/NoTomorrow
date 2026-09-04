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
                    Text("Rest").font(.system(size: 11, weight: .semibold)).tracking(0.9).textCase(.uppercase).foregroundStyle(W.ember)
                    Text(timerInterval: Date.now...context.state.endDate, countsDown: true)
                        .font(.system(size: 34, weight: .bold)).monospacedDigit().foregroundStyle(W.ink)
                    Text("\(context.attributes.exerciseName) · \(context.attributes.nextSetLabel)")
                        .font(.system(size: 13)).foregroundStyle(W.ink2).lineLimit(1)
                }
                Spacer()
            }
            .padding(16)
            .activityBackgroundTint(W.ground)
            .activitySystemActionForegroundColor(W.ink)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    ring(context).frame(width: 44, height: 44).padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(timerInterval: Date.now...context.state.endDate, countsDown: true)
                        .font(.system(size: 30, weight: .bold)).monospacedDigit().foregroundStyle(W.ink)
                        .frame(width: 84, alignment: .trailing)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text("\(context.attributes.exerciseName) · \(context.attributes.nextSetLabel)")
                        .font(.system(size: 13)).foregroundStyle(W.ink2).lineLimit(1)
                }
            } compactLeading: {
                Image(systemName: "timer").foregroundStyle(W.ember)
            } compactTrailing: {
                Text(timerInterval: Date.now...context.state.endDate, countsDown: true)
                    .monospacedDigit().foregroundStyle(W.ink).frame(width: 46)
            } minimal: {
                Image(systemName: "timer").foregroundStyle(W.ember)
            }
            .keylineTint(W.ember)
        }
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
