import WidgetKit
import SwiftUI

@main
struct NoTomorrowWidgetsBundle: WidgetBundle {
    var body: some Widget {
        QuickLogWidget()
        FuelCalendarWidget()
        WeekWidget()
        BreakTimerWidget()
        RestTimerLiveActivity()
    }
}
