import SwiftUI
import PhotosUI
import UIKit

/// Step 1 of the AI flow: where the photo comes from. Camera when the device has one, photo library always.
struct AIScanSourceView: View {
    var meal: MealSlot
    var onPicked: (UIImage) -> Void

    @State private var libraryItem: PhotosPickerItem?
    @State private var showCamera = false

    private var cameraAvailable: Bool { UIImagePickerController.isSourceTypeAvailable(.camera) }

    var body: some View {
        VStack(alignment: .leading, spacing: NT.Spacing.section) {
            VStack(alignment: .leading, spacing: 6) {
                Text(AIScanText.slotKey(meal)).eyebrow()
                Text("fuel.ai.source.subtitle")
                    .font(NT.Fonts.subheadline)
                    .foregroundStyle(NT.Colors.ink2)
            }

            plateIllustration

            VStack(spacing: 10) {
                if cameraAvailable {
                    PrimaryButton(title: "fuel.ai.takePhoto", systemImage: "camera") { showCamera = true }
                }
                PhotosPicker(selection: $libraryItem, matching: .images, photoLibrary: .shared()) {
                    HStack(spacing: 8) {
                        Image(systemName: "photo.on.rectangle").font(.system(size: 16, weight: .semibold))
                        Text("fuel.ai.chooseLibrary").font(NT.Fonts.headline).lineLimit(1)
                    }
                    .foregroundStyle(cameraAvailable ? NT.Colors.ink : NT.Colors.onPrimary)
                    .frame(maxWidth: .infinity)
                    .frame(height: NT.Size.primaryButton)
                    .padding(.horizontal, 16)
                    .background(cameraAvailable ? NT.Colors.surface2 : NT.Colors.ink, in: Capsule())
                }
                .buttonStyle(PressScale())
            }
            Spacer()
        }
        .padding(.horizontal, NT.Spacing.screenH)
        .padding(.top, 12)
        .fullScreenCover(isPresented: $showCamera) {
            CameraPicker { image in
                showCamera = false
                if let image { onPicked(image) }
            }
            .ignoresSafeArea()
        }
        .onChange(of: libraryItem) { _, item in
            guard let item else { return }
            Task {
                let data = try? await item.loadTransferable(type: Data.self)
                await MainActor.run {
                    libraryItem = nil
                    if let data, let image = UIImage(data: data) { onPicked(image) }
                }
            }
        }
    }

    /// Empty-state visual: a plate outline in the photo frame, 210 pt like the result photo.
    private var plateIllustration: some View {
        ZStack {
            RoundedRectangle(cornerRadius: NT.Radius.card, style: .continuous).fill(NT.Colors.surface)
            Circle().stroke(NT.Colors.surface3, lineWidth: 2).frame(width: 150, height: 150)
            Circle().stroke(NT.Colors.surface2, lineWidth: 2).frame(width: 118, height: 118)
            Image(systemName: "fork.knife")
                .font(.system(size: 30, weight: .medium))
                .foregroundStyle(NT.Colors.ink3)
        }
        .frame(height: 210)
        .frame(maxWidth: .infinity)
    }
}

/// Meal-slot labels, kept local so they cannot collide with helpers other Fuel views may add.
enum AIScanText {
    static func slotKey(_ slot: MealSlot) -> LocalizedStringKey {
        switch slot {
        case .breakfast: "meal.breakfast"
        case .lunch: "meal.lunch"
        case .snack: "meal.snack"
        case .dinner: "meal.dinner"
        }
    }

    static func slotName(_ slot: MealSlot) -> String {
        switch slot {
        case .breakfast: String(localized: "meal.breakfast")
        case .lunch: String(localized: "meal.lunch")
        case .snack: String(localized: "meal.snack")
        case .dinner: String(localized: "meal.dinner")
        }
    }
}

// MARK: - Camera

/// `UIImagePickerController` in camera mode. Calls back with `nil` on cancel.
struct CameraPicker: UIViewControllerRepresentable {
    var onFinish: (UIImage?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.cameraCaptureMode = .photo
        picker.allowsEditing = false
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onFinish: (UIImage?) -> Void
        init(onFinish: @escaping (UIImage?) -> Void) { self.onFinish = onFinish }

        func imagePickerController(_ picker: UIImagePickerController,
                                   didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            onFinish(info[.originalImage] as? UIImage)
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onFinish(nil)
        }
    }
}
