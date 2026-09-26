import AppIntents
import Foundation
import WidgetKit

// The widgets' buttons. This file is compiled into both targets (Shared/): WidgetKit needs the types in the extension
// to render `Button(intent:)`, and a `LiveActivityIntent` is performed in the app's process, where the app's copy of
// the type does the work. `WIDGET_EXTENSION` (set on the extension target only) tells the two copies apart.

/// One tap on a quick food: logs it for today without opening the app (`docs/widgets.md`, "Quick log").
struct LogQuickFoodIntent: AppIntent {
    static var title: LocalizedStringResource = "widget.fuel.name"
    static var isDiscoverable: Bool = false

    @Parameter(title: "widget.fuel.name")
    var key: String

    init() {}

    init(key: String) {
        self.key = key
    }

    func perform() async throws -> some IntentResult {
        WidgetStore.logQuickFood(key: key)
        #if !WIDGET_EXTENSION
        // Performed by the app after all (it was running): take the entry in now rather than on the next foreground.
        await MainActor.run { NotificationCenter.default.post(name: .widgetQuickLogQueued, object: nil) }
        #endif
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetKind.fuel)
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetKind.history)
        return .result()
    }
}

extension Notification.Name {
    /// A quick-log tap was queued in this process; `WidgetSync` drains the queue.
    static let widgetQuickLogQueued = Notification.Name("nt.widget.quickLogQueued")
}

// MARK: - Break timer

/// Starts a rest of `seconds`. A `LiveActivityIntent`, so iOS runs it in the app (launching it in the background),
/// where it can start the Live Activity like a rest started from a set.
struct StartBreakIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "widget.rest.name"
    static var isDiscoverable: Bool = false

    @Parameter(title: "widget.rest.name")
    var seconds: Int

    init() {}

    init(seconds: Int) {
        self.seconds = seconds
    }

    func perform() async throws -> some IntentResult {
        await RestCommandRunner.run(.start(seconds: seconds))
        return .result()
    }
}

/// +15 / −15 on a running rest.
struct AdjustBreakIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "widget.rest.name"
    static var isDiscoverable: Bool = false

    @Parameter(title: "widget.rest.name")
    var seconds: Int

    init() {}

    init(seconds: Int) {
        self.seconds = seconds
    }

    func perform() async throws -> some IntentResult {
        await RestCommandRunner.run(.adjust(seconds: seconds))
        return .result()
    }
}

/// Skip: ends the rest now.
struct SkipBreakIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "common.skip"
    static var isDiscoverable: Bool = false

    init() {}

    func perform() async throws -> some IntentResult {
        await RestCommandRunner.run(.skip)
        return .result()
    }
}

enum RestCommandRunner {
    /// In the app: `RestTimerController.shared` (Live Activity, notification, haptic, widget reload). In the
    /// extension, only if iOS ever runs the intent there: the shared state and the notification, which the app adopts
    /// when it next opens.
    static func run(_ command: RestCommand) async {
        #if WIDGET_EXTENSION
        command.applyWithoutApp(overLabel: WidgetText.string("timer.notification.title"))
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetKind.rest)
        #else
        await MainActor.run { RestTimerController.shared.handle(command) }
        #endif
    }
}
