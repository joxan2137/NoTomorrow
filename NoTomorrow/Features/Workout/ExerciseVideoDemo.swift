import AVFoundation
import SwiftUI
import UIKit

/// The 3D form demos for exercises without free-exercise-db photos: short looping clips of the app's own rendered
/// body, worked muscles glowing (`scripts/anatomy/body3d/render_demos.py`). `Demos/demos.json` maps an exercise to its
/// clip; each clip is `<clip>.mp4` plus a `<clip>.jpg` poster of the finishing pose.
enum ExerciseDemos {
    private struct File: Decodable { let clips: [String: String] }

    static let clips: [String: String] = {
        guard let url = Bundle.main.url(forResource: "demos", withExtension: "json", subdirectory: "Demos"),
              let data = try? Data(contentsOf: url),
              let file = try? JSONDecoder().decode(File.self, from: data) else { return [:] }
        return file.clips
    }()

    static func clip(for exerciseID: String) -> String? { clips[exerciseID] }

    static func video(_ clip: String) -> URL? {
        Bundle.main.url(forResource: clip, withExtension: "mp4", subdirectory: "Demos")
    }

    static func poster(_ clip: String) -> UIImage? {
        Bundle.main.path(forResource: clip, ofType: "jpg", inDirectory: "Demos").flatMap(UIImage.init(contentsOfFile:))
    }
}

/// A demo clip looping silently in the same 3:2 tile as the photo demo, with a pause button. The poster shows until
/// the first frame is ready, and stays (paused) when Reduce Motion is on until play is pressed.
struct ExerciseVideoDemoView: View {
    let clip: String
    let name: String

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @State private var playing: Bool?

    private var isPlaying: Bool { (playing ?? !reduceMotion) && scenePhase == .active }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            RoundedRectangle(cornerRadius: NT.Radius.tile, style: .continuous).fill(NT.Colors.surface)
            if let poster = ExerciseDemos.poster(clip) {
                Image(uiImage: poster).resizable().scaledToFill()
            }
            if let url = ExerciseDemos.video(clip) {
                LoopingVideo(url: url, playing: isPlaying)
            }
            Button {
                playing = !isPlaying
            } label: {
                Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(NT.Colors.ink)
                    .frame(width: 36, height: 36)
                    .background(NT.Colors.surface2.opacity(0.9), in: Circle())
            }
            .buttonStyle(PressScale())
            .accessibilityLabel(Text(isPlaying ? "exercises.pause" : "exercises.play"))
            .padding(10)
        }
        .aspectRatio(3.0 / 2.0, contentMode: .fit)
        .frame(maxWidth: .infinity)
        .clipShape(RoundedRectangle(cornerRadius: NT.Radius.tile, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text(name))
    }
}

/// An `AVPlayerLayer` looping one muted file with `AVPlayerLooper`. It never takes the audio session, so music keeps
/// playing, and it stays transparent until its first frame is ready, so the poster underneath shows through.
private struct LoopingVideo: UIViewRepresentable {
    let url: URL
    let playing: Bool

    func makeUIView(context: Context) -> PlayerView {
        let view = PlayerView()
        view.load(url)
        return view
    }

    func updateUIView(_ view: PlayerView, context: Context) {
        if view.url != url { view.load(url) }
        view.setPlaying(playing)
    }

    static func dismantleUIView(_ view: PlayerView, coordinator: ()) { view.stop() }

    final class PlayerView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        private var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
        private var looper: AVPlayerLooper?
        private var ready: NSKeyValueObservation?
        private(set) var url: URL?

        func load(_ url: URL) {
            stop()
            self.url = url
            let player = AVQueuePlayer()
            player.isMuted = true
            player.preventsDisplaySleepDuringVideoPlayback = false
            player.allowsExternalPlayback = false
            looper = AVPlayerLooper(player: player, templateItem: AVPlayerItem(url: url))
            playerLayer.player = player
            playerLayer.videoGravity = .resizeAspectFill
            playerLayer.opacity = 0
            ready = playerLayer.observe(\.isReadyForDisplay, options: [.initial, .new]) { layer, _ in
                guard layer.isReadyForDisplay else { return }
                DispatchQueue.main.async { layer.opacity = 1 }
            }
            isAccessibilityElement = false
        }

        func setPlaying(_ playing: Bool) {
            guard let player = playerLayer.player else { return }
            if playing, player.rate == 0 { player.play() } else if !playing, player.rate != 0 { player.pause() }
        }

        func stop() {
            ready = nil
            playerLayer.player?.pause()
            looper?.disableLooping()
            looper = nil
            playerLayer.player = nil
        }
    }
}
