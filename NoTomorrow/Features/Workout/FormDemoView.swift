import SwiftUI
import UIKit

/// free-exercise-db's photos (public domain), downloaded once and kept in Caches/ExercisePhotos and in memory,
/// so a demo opened again plays at once and works offline.
final class ExercisePhotoStore: @unchecked Sendable {
    static let shared = ExercisePhotoStore()

    private let memory = NSCache<NSString, UIImage>()
    private let directory: URL? = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?
        .appendingPathComponent("ExercisePhotos", isDirectory: true)

    init() { memory.countLimit = 24 }

    /// The photo if it is already in memory; no disk or network.
    func cached(_ path: String) -> UIImage? { memory.object(forKey: path as NSString) }

    func image(_ path: String) async -> UIImage? {
        if let image = cached(path) { return image }
        let file = directory?.appendingPathComponent(path.replacingOccurrences(of: "/", with: "__"))
        if let file, let data = try? Data(contentsOf: file), let image = UIImage(data: data) {
            memory.setObject(image, forKey: path as NSString)
            return image
        }
        guard let url = ExerciseMedia.url(path),
              let (data, response) = try? await URLSession.shared.data(from: url),
              (response as? HTTPURLResponse)?.statusCode == 200,
              data.count < 2 * 1024 * 1024,
              let image = UIImage(data: data) else { return nil }
        memory.setObject(image, forKey: path as NSString)
        if let file, let directory {
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try? data.write(to: file, options: .atomic)
        }
        return image
    }
}

/// The exercise's start and end photos played as a loop (a crossfade every `interval`), with a pause button and a
/// dot per frame; tapping a dot pauses on that frame. Starts paused when Reduce Motion is on.
struct FormDemoView: View {
    let paths: [String]
    let name: String

    static let interval: Duration = .milliseconds(1100)

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var images: [UIImage?] = []
    @State private var failed = false
    @State private var index = 0
    @State private var playing = true
    @State private var started = false

    private var loaded: [UIImage] { images.compactMap { $0 } }

    var body: some View {
        ZStack(alignment: .bottom) {
            RoundedRectangle(cornerRadius: NT.Radius.tile, style: .continuous).fill(NT.Colors.surface2)
            if loaded.count == paths.count, !loaded.isEmpty {
                ZStack {
                    ForEach(Array(loaded.enumerated()), id: \.offset) { i, image in
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFill()
                            .opacity(i == index ? 1 : 0)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: NT.Radius.tile, style: .continuous))
                .accessibilityElement()
                .accessibilityLabel(Text(name))
                .accessibilityAddTraits(.isImage)
                controls
            } else if failed {
                Label("exercises.photoUnavailable", systemImage: "wifi.slash")
                    .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2)
                    .multilineTextAlignment(.center)
                    .padding(20)
                    .frame(maxHeight: .infinity)
            } else {
                ProgressView().tint(NT.Colors.ink2).frame(maxHeight: .infinity)
            }
        }
        .aspectRatio(3.0 / 2.0, contentMode: .fit)
        .frame(maxWidth: .infinity)
        .task(id: paths) { await load() }
        .task(id: playing && loaded.count > 1) { await loop() }
    }

    private var controls: some View {
        HStack(spacing: 8) {
            HStack(spacing: 6) {
                ForEach(0..<loaded.count, id: \.self) { i in
                    Button {
                        playing = false
                        withAnimation(.easeInOut(duration: 0.25)) { index = i }
                    } label: {
                        Capsule()
                            .fill(i == index ? NT.Colors.ink : NT.Colors.ink3)
                            .frame(width: i == index ? 18 : 7, height: 7)
                            .frame(height: 28)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(verbatim: "\(i + 1) / \(loaded.count)"))
                }
            }
            .padding(.horizontal, 10)
            .background(.ultraThinMaterial, in: Capsule())
            Spacer()
            if loaded.count > 1 {
                Button { playing.toggle() } label: {
                    Image(systemName: playing ? "pause.fill" : "play.fill")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(NT.Colors.ink)
                        .frame(width: 36, height: 36)
                        .background(.ultraThinMaterial, in: Circle())
                }
                .buttonStyle(PressScale())
                .accessibilityLabel(Text(playing ? "exercises.pause" : "exercises.play"))
            }
        }
        .padding(10)
        .environment(\.colorScheme, .dark)
    }

    private func load() async {
        if !started {
            started = true
            playing = !reduceMotion
        }
        images = paths.map { ExercisePhotoStore.shared.cached($0) }
        if loaded.count == paths.count { return }
        failed = false
        let fetched = await withTaskGroup(of: (Int, UIImage?).self) { group in
            for (i, path) in paths.enumerated() {
                group.addTask { (i, await ExercisePhotoStore.shared.image(path)) }
            }
            var result = [UIImage?](repeating: nil, count: paths.count)
            for await (i, image) in group { result[i] = image }
            return result
        }
        images = fetched
        failed = fetched.contains { $0 == nil }
    }

    private func loop() async {
        guard playing, loaded.count > 1 else { return }
        while !Task.isCancelled {
            try? await Task.sleep(for: Self.interval)
            if Task.isCancelled { return }
            withAnimation(.easeInOut(duration: 0.35)) { index = (index + 1) % loaded.count }
        }
    }
}
