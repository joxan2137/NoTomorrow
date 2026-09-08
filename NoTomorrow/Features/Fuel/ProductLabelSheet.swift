import SwiftUI
import SwiftData

/// A retailer item absent from OFF can be saved once from its printed label and rescanned offline.
struct ProductLabelSheet: View {
    let barcode: String
    let onSaved: (FoodItem) -> Void
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var values = ["", "", "", ""]
    @State private var failed = false
    private let labels = ["unit.kcal", "fuel.macro.p", "fuel.macro.c", "fuel.macro.f"]
    private var numbers: [Double]? {
        let parsed = values.compactMap { Double($0.replacingOccurrences(of: ",", with: ".").trimmingCharacters(in: .whitespaces)) }
        guard parsed.count == 4, parsed.allSatisfy({ $0.isFinite && $0 >= 0 }), parsed[0] <= 950,
              parsed.dropFirst().reduce(0, +) <= 100 else { return nil }
        return parsed
    }
    var body: some View {
        NavigationStack {
            Form {
                Text("fuel.label.hint")
                Text(barcode).font(.caption.monospaced())
                TextField("fuel.foodName", text: $name)
                Section("fuel.per100") {
                    ForEach(0..<4, id: \.self) { i in
                        HStack {
                            Text(LocalizedStringKey(labels[i]))
                            TextField("0", text: $values[i]).keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                        }
                    }
                }
                if failed { Text("fuel.label.saveFailed").foregroundStyle(.red) }
            }
            .navigationTitle("fuel.label.title")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("common.cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.save") { save() }.disabled(numbers == nil || name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
    private func save() {
        guard let n = numbers else { return }
        let item = FoodItem(id: "label:\(barcode)", name: name.trimmingCharacters(in: .whitespaces), source: .custom, barcode: barcode,
                            kcalPer100: n[0], proteinPer100: n[1], carbsPer100: n[2], fatPer100: n[3])
        context.insert(item)
        do { try context.save(); onSaved(item) } catch { context.rollback(); failed = true }
    }
}
