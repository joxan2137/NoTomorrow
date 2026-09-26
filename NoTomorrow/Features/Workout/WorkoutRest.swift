import Foundation

/// The active workout's per-exercise rest menu (Hevy / Strong style). A workout exercise always stores a real
/// length; "Default" is the length this exercise gets from the user's Rest length setting (heavy compounds a
/// little longer), which is also what "Save as routine" turns back into a routine's default rest.
enum WorkoutRest {
    struct Option: Hashable {
        let seconds: Int
        let isDefault: Bool
    }

    /// Lengths offered after Default: the routine editor's 30 s to 5 min.
    static let lengths = RoutineDraft.restOptions.filter { $0 > 0 }

    /// Default first, then the fixed lengths. A current length that is neither (an imported workout, a routine
    /// saved with an odd value) is listed in order too, so the checkmark always has a row.
    static func options(current: Int, defaultSeconds: Int) -> [Option] {
        var seconds = lengths
        if current > 0, current != defaultSeconds, !seconds.contains(current) {
            seconds.append(current)
            seconds.sort()
        }
        return [Option(seconds: defaultSeconds, isDefault: true)] + seconds.map { Option(seconds: $0, isDefault: false) }
    }

    /// One checkmark: Default when the length is the default, else the matching length.
    static func isChecked(_ option: Option, current: Int, defaultSeconds: Int) -> Bool {
        if option.isDefault { return current == defaultSeconds }
        return current != defaultSeconds && current == option.seconds
    }

    /// "Default (1:30)" or "2:00".
    static func label(_ option: Option) -> String {
        let clock = Fmt.clock(TimeInterval(option.seconds))
        guard option.isDefault else { return clock }
        return String(format: String(localized: "workout.restDefault"), locale: .current, clock)
    }
}
