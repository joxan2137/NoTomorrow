import AVFoundation

/// Plays the "rest is over" chime (`Resources/Sounds/rest_over.caf`, rendered by `scripts/sounds/rest_over.py`) in the
/// app. The notification carries the same sound whenever it is shown; this covers the one case where it is not —
/// the rest ending with the full workout on screen (`NotificationRouter`).
///
/// `.ambient`: the ringer switch silences it, like the notification, and music keeps playing underneath.
@MainActor
enum RestChime {
    private static var player: AVAudioPlayer?

    static func play() {
        guard let url = Bundle.main.url(forResource: "rest_over", withExtension: "caf") else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.ambient, options: [.mixWithOthers])
            try session.setActive(true)
            let player = try AVAudioPlayer(contentsOf: url)
            player.volume = 0.9
            player.prepareToPlay()
            player.play()
            self.player = player   // held until the next chime; AVAudioPlayer stops when released
        } catch {
            // No sound is not worth an error: the haptic already says the rest is over.
        }
    }
}
