import SwiftUI
import SwiftData
import PhotosUI

/// A stored progress photo at a display size; a surface placeholder (or nothing) while it decodes.
struct ProgressPhotoImage: View {
    var url: URL
    var maxPixel: CGFloat
    /// Fill (and crop) the frame, or fit inside it.
    var fill: Bool = true

    @State private var image: UIImage?

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: fill ? .fill : .fit)
            } else {
                Rectangle().fill(fill ? NT.Colors.surface2 : Color.clear)
            }
        }
        .task(id: url) {
            image = await ProgressPhotoLoader.image(at: url, maxPixel: maxPixel)
        }
    }
}

/// Progress > Body, under the measurements: private progress photos. A strip of thumbnails (newest first) after
/// an Add photo tile, a full-screen viewer and Compare (two photos side by side). Photos never leave the phone.
struct ProgressPhotosSection: View {
    private struct ViewerStart: Identifiable {
        let id: UUID
    }

    private static let tileWidth: CGFloat = 84
    private static let tileHeight: CGFloat = 112

    @Environment(\.modelContext) private var modelContext
    @Query(sort: \ProgressPhoto.takenAt, order: .reverse) private var rows: [ProgressPhoto]
    @State private var pickerItem: PhotosPickerItem?
    @State private var pendingJPEG: Data?
    @State private var asksPose = false
    @State private var isImporting = false
    @State private var showsFailure = false
    @State private var viewerStart: ViewerStart?
    @State private var showsCompare = false

    private let store = ProgressPhotoStore.standard

    private var photos: [ProgressPhoto] {
        ProgressPhotos.newestFirst(rows, takenAt: \.takenAt, id: \.id)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            Text("photos.private")
                .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                .fixedSize(horizontal: false, vertical: true)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    addTile
                    ForEach(photos) { photo in
                        thumbnail(photo)
                    }
                }
            }
            .padding(.top, 12)
        }
        .onChange(of: pickerItem) { _, item in
            guard let item else { return }
            isImporting = true
            Task {
                let data = try? await item.loadTransferable(type: Data.self)
                var jpeg: Data?
                if let data {
                    jpeg = await PickedPhoto.data(data).preparedJPEG(maxLongEdge: ProgressPhotos.maxLongEdge)
                }
                let prepared = jpeg
                await MainActor.run {
                    pickerItem = nil
                    isImporting = false
                    if let prepared {
                        pendingJPEG = prepared
                        asksPose = true
                    } else {
                        showsFailure = true
                    }
                }
            }
        }
        .confirmationDialog("photos.pose.question", isPresented: $asksPose, titleVisibility: .visible) {
            ForEach(ProgressPose.allCases, id: \.self) { pose in
                Button(ProgressPhotos.poseKey(pose)) { save(pose: pose) }
            }
            Button("photos.pose.none") { save(pose: nil) }
            Button("common.cancel", role: .cancel) { pendingJPEG = nil }
        }
        .alert("photos.failed", isPresented: $showsFailure) {
            Button("common.done", role: .cancel) {}
        }
        .fullScreenCover(item: $viewerStart) { start in
            ProgressPhotoViewer(startID: start.id, store: store)
        }
        .fullScreenCover(isPresented: $showsCompare) {
            ProgressPhotoCompareView(store: store)
        }
    }

    private var header: some View {
        HStack {
            Text("photos.title").font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink)
            Spacer()
            if photos.count >= 2 {
                Button { showsCompare = true } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "rectangle.split.2x1").font(.system(size: 14, weight: .semibold))
                            .accessibilityHidden(true)
                        Text("photos.compare").font(NT.Fonts.subheadlineBold)
                    }
                    .foregroundStyle(NT.Colors.ember)
                    .frame(minHeight: NT.Size.control)
                }
                .buttonStyle(PressScale())
            }
        }
        .frame(minHeight: NT.Size.control)
    }

    private var addTile: some View {
        PhotosPicker(selection: $pickerItem, matching: .images, photoLibrary: .shared()) {
            VStack(spacing: 6) {
                if isImporting {
                    ProgressView().tint(NT.Colors.ink)
                } else {
                    Image(systemName: "plus").font(.system(size: 20, weight: .semibold))
                        .accessibilityHidden(true)
                }
                Text("photos.add")
                    .font(NT.Fonts.caption)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
            }
            .foregroundStyle(NT.Colors.ink)
            .padding(.horizontal, 6)
            .frame(width: Self.tileWidth, height: Self.tileHeight)
            .background(NT.Colors.surface2, in: RoundedRectangle(cornerRadius: NT.Radius.cell, style: .continuous))
        }
        .buttonStyle(PressScale())
        .disabled(isImporting)
    }

    private func thumbnail(_ photo: ProgressPhoto) -> some View {
        Button { viewerStart = ViewerStart(id: photo.id) } label: {
            ProgressPhotoImage(url: store.url(for: photo.fileName), maxPixel: 320)
                .frame(width: Self.tileWidth, height: Self.tileHeight)
                .clipShape(RoundedRectangle(cornerRadius: NT.Radius.cell, style: .continuous))
                .contentShape(RoundedRectangle(cornerRadius: NT.Radius.cell, style: .continuous))
                .overlay(alignment: .bottomLeading) {
                    Text(verbatim: Fmt.dayMonth(photo.takenAt))
                        .font(NT.Fonts.caption)
                        .foregroundStyle(Color.white)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.black.opacity(0.55), in: Capsule())
                        .padding(5)
                }
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(Text(verbatim: ProgressPhotos.accessibilityLabel(takenAt: photo.takenAt, pose: photo.pose)))
    }

    private func save(pose: ProgressPose?) {
        guard let jpeg = pendingJPEG else { return }
        pendingJPEG = nil
        do {
            try ProgressPhotos.add(jpeg: jpeg, pose: pose, store: store, in: modelContext)
            Haptics.success()
        } catch {
            showsFailure = true
        }
    }
}

// MARK: - Viewer

/// Full screen: swipe between the photos (newest first), date and pose under each, Delete with a confirmation.
struct ProgressPhotoViewer: View {
    var store: ProgressPhotoStore

    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss
    @Query(sort: \ProgressPhoto.takenAt, order: .reverse) private var rows: [ProgressPhoto]
    @State private var selection: UUID?
    @State private var confirmsDelete = false

    init(startID: UUID, store: ProgressPhotoStore) {
        self.store = store
        _selection = State(initialValue: startID)
    }

    private var photos: [ProgressPhoto] {
        ProgressPhotos.newestFirst(rows, takenAt: \.takenAt, id: \.id)
    }

    private var currentIndex: Int? {
        photos.firstIndex { $0.id == selection }
    }

    var body: some View {
        let list = photos
        let index = currentIndex ?? 0
        VStack(spacing: 0) {
            HStack {
                Button { dismiss() } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(NT.Colors.ink)
                        .frame(width: NT.Size.control, height: NT.Size.control)
                }
                .accessibilityLabel(Text("common.done"))
                Spacer()
                if list.count > 1 {
                    Text(verbatim: "\(index + 1) / \(list.count)")
                        .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2).tabular()
                }
                Spacer()
                Button { confirmsDelete = true } label: {
                    Image(systemName: "trash")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(NT.Colors.bad)
                        .frame(width: NT.Size.control, height: NT.Size.control)
                }
                .accessibilityLabel(Text("common.delete"))
                .disabled(list.isEmpty)
            }
            .padding(.horizontal, 8)

            TabView(selection: $selection) {
                ForEach(list) { photo in
                    ProgressPhotoImage(url: store.url(for: photo.fileName), maxPixel: 2048, fill: false)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(Text(verbatim: ProgressPhotos.accessibilityLabel(takenAt: photo.takenAt,
                                                                                             pose: photo.pose)))
                        .accessibilityAddTraits(.isImage)
                        .tag(Optional(photo.id))
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))

            if index < list.count {
                caption(list[index])
            }
        }
        .background(NT.Colors.ground.ignoresSafeArea())
        .confirmationDialog("photos.delete.title", isPresented: $confirmsDelete, titleVisibility: .visible) {
            Button("common.delete", role: .destructive) { deleteCurrent() }
            Button("common.cancel", role: .cancel) {}
        } message: {
            Text("photos.delete.message")
        }
    }

    private func caption(_ photo: ProgressPhoto) -> some View {
        VStack(spacing: 4) {
            Text(verbatim: ProgressPhotos.dateLabel(photo.takenAt))
                .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink)
            if let pose = photo.pose {
                Text(ProgressPhotos.poseKey(pose))
                    .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 16)
    }

    private func deleteCurrent() {
        let list = photos
        guard let index = currentIndex, index < list.count else { return }
        let photo = list[index]
        let next = ProgressPhotos.indexAfterDeleting(at: index, count: list.count)
        let remaining = list.filter { $0.id != photo.id }
        ProgressPhotos.delete(photo, store: store, in: modelContext)
        if let next, next < remaining.count {
            selection = remaining[next].id
        } else {
            dismiss()
        }
    }
}

// MARK: - Compare

/// Two photos side by side: the first against the latest to start with; each side picks any photo from a menu.
struct ProgressPhotoCompareView: View {
    var store: ProgressPhotoStore

    @Environment(\.dismiss) private var dismiss
    @Query(sort: \ProgressPhoto.takenAt, order: .reverse) private var rows: [ProgressPhoto]
    @State private var beforeID: UUID?
    @State private var afterID: UUID?

    private var photos: [ProgressPhoto] {
        ProgressPhotos.newestFirst(rows, takenAt: \.takenAt, id: \.id)
    }

    var body: some View {
        let list = photos
        let defaults = ProgressPhotos.defaultComparison(list)
        VStack(spacing: 0) {
            ZStack {
                Text("photos.compare").font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink)
                HStack {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(NT.Colors.ink)
                            .frame(width: NT.Size.control, height: NT.Size.control)
                    }
                    .accessibilityLabel(Text("common.done"))
                    Spacer()
                }
            }
            .padding(.horizontal, 8)

            if let defaults {
                HStack(alignment: .top, spacing: 8) {
                    column(list.first { $0.id == beforeID } ?? defaults.before, options: list,
                           title: "photos.before", selection: $beforeID)
                    column(list.first { $0.id == afterID } ?? defaults.after, options: list,
                           title: "photos.after", selection: $afterID)
                }
                .padding(.horizontal, 12)
                .padding(.top, 16)
            }
            Spacer(minLength: 0)
        }
        .background(NT.Colors.ground.ignoresSafeArea())
    }

    private func column(_ photo: ProgressPhoto, options: [ProgressPhoto], title: LocalizedStringKey,
                        selection: Binding<UUID?>) -> some View {
        VStack(spacing: 8) {
            Color.clear
                .aspectRatio(3.0 / 4.0, contentMode: .fit)
                .overlay {
                    ProgressPhotoImage(url: store.url(for: photo.fileName), maxPixel: 1200)
                }
                .clipShape(RoundedRectangle(cornerRadius: NT.Radius.cell, style: .continuous))
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(title) + Text(verbatim: ": "
                    + ProgressPhotos.accessibilityLabel(takenAt: photo.takenAt, pose: photo.pose)))
                .accessibilityAddTraits(.isImage)
            Menu {
                ForEach(options) { option in
                    Button {
                        selection.wrappedValue = option.id
                    } label: {
                        Text(verbatim: ProgressPhotos.optionLabel(takenAt: option.takenAt, pose: option.pose))
                    }
                }
            } label: {
                VStack(spacing: 2) {
                    Text(title).font(NT.Fonts.eyebrow).foregroundStyle(NT.Colors.ink2)
                    HStack(spacing: 4) {
                        Text(verbatim: ProgressPhotos.optionLabel(takenAt: photo.takenAt, pose: photo.pose))
                            .font(NT.Fonts.footnoteBold)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                        Image(systemName: "chevron.down").font(.system(size: 11, weight: .semibold))
                            .accessibilityHidden(true)
                    }
                    .foregroundStyle(NT.Colors.ink)
                }
                .frame(maxWidth: .infinity, minHeight: NT.Size.control)
            }
        }
        .frame(maxWidth: .infinity)
    }
}
