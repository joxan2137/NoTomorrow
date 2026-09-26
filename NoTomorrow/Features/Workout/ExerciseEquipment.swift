import Foundation

extension ExerciseLibrary {
    /// Equipment filter chips of the exercise picker, mapped onto free-exercise-db `equipment` values: the EZ bar
    /// counts as a barbell, and medicine / exercise balls, foam rollers, "other" and no equipment at all are Other.
    enum Equipment: String, CaseIterable, Identifiable {
        case all, barbell, dumbbell, machine, cable, bodyweight, kettlebell, band, other
        var id: String { rawValue }

        var titleKey: String {
            switch self {
            case .all: "equipment.all"
            case .barbell: "equipment.barbell"
            case .dumbbell: "equipment.dumbbell"
            case .machine: "equipment.machine"
            case .cable: "equipment.cable"
            case .bodyweight: "equipment.body_only"
            case .kettlebell: "equipment.kettlebells"
            case .band: "equipment.bands"
            case .other: "equipment.other"
            }
        }

        /// The chip an exercise's raw `equipment` value falls under (never `.all`).
        static func of(_ equipment: String?) -> Equipment {
            switch equipment?.lowercased() {
            case "barbell", "e-z curl bar": .barbell
            case "dumbbell": .dumbbell
            case "machine": .machine
            case "cable": .cable
            case "body only": .bodyweight
            case "kettlebells": .kettlebell
            case "bands": .band
            default: .other
            }
        }

        func matches(_ equipment: String?) -> Bool {
            self == .all || Self.of(equipment) == self
        }

        /// The raw value a custom exercise created under this chip gets, so it stays in the filtered list.
        var representative: String? {
            switch self {
            case .all: nil
            case .barbell: "barbell"
            case .dumbbell: "dumbbell"
            case .machine: "machine"
            case .cable: "cable"
            case .bodyweight: "body only"
            case .kettlebell: "kettlebells"
            case .band: "bands"
            case .other: "other"
            }
        }
    }
}
