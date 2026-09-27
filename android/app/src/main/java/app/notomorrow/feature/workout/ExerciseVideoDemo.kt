package app.notomorrow.feature.workout

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.effects.rememberReduceMotion
import app.notomorrow.designsystem.ntClickable
import app.notomorrow.util.S
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `ExerciseDemos` (`ExerciseVideoDemo.swift`): the 3D form demos for exercises without free-exercise-db
 * photos, short looping clips of the app's own rendered body with the worked muscles glowing
 * (`scripts/anatomy/body3d/render_demos.py`). `demos/demos.json` maps an exercise to its clip; each clip
 * is `<clip>.mp4` plus a `<clip>.jpg` poster of the finishing pose.
 */
internal object ExerciseDemos {
    @Serializable
    data class File(val clips: Map<String, String>)

    private val json = Json { ignoreUnknownKeys = true }

    /** Read once per process. */
    @Volatile var clips: Map<String, String>? = null

    fun decode(text: String): Map<String, String> = json.decodeFromString<File>(text).clips

    fun load(context: Context): Map<String, String> = clips ?: runCatching {
        context.assets.open("demos/demos.json").bufferedReader().use { decode(it.readText()) }
    }.getOrDefault(emptyMap()).also { clips = it }
}

/**
 * `ExerciseVideoDemoView`: a demo clip looping silently in the same 3:2 tile as the photo demo, with a
 * pause button. The poster shows until the first frame is on screen, and stays (paused) when Reduce
 * Motion is on until play is pressed. Playback pauses while the app is in the background.
 */
@Composable
internal fun ExerciseVideoDemo(clip: String, name: String) {
    val context = LocalContext.current
    val reduceMotion = rememberReduceMotion()
    var playing by remember(clip) { mutableStateOf(!reduceMotion) }
    var started by remember(clip) { mutableStateOf(false) }
    val poster by produceState<ImageBitmap?>(null, clip) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("demos/$clip.jpg").use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
        }
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(3f / 2f).clip(RoundedCornerShape(NT.Radius.tile)).background(NT.Colors.surface)
            .semantics { contentDescription = name },
    ) {
        LoopingVideo(clip, playing, onFirstFrame = { started = true }, Modifier.fillMaxSize())
        poster?.let {
            Image(it, contentDescription = null, Modifier.fillMaxSize().alpha(if (started) 0f else 1f), contentScale = ContentScale.Crop)
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(10.dp).size(36.dp).clip(CircleShape).background(NT.Colors.surface2.copy(alpha = 0.9f))
                .ntClickable(onClickLabel = stringResource(if (playing) S.exercises_pause else S.exercises_play)) { playing = !playing },
            contentAlignment = Alignment.Center,
        ) {
            NtIcon(if (playing) NtIcons.PauseFill else NtIcons.PlayFill, size = 16.dp, tint = NT.Colors.ink)
        }
    }
}

/** One muted, looping [MediaPlayer] on a [TextureView], reading the clip straight from the APK's assets. */
@Composable
private fun LoopingVideo(clip: String, playing: Boolean, onFirstFrame: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(clip) { DemoPlayer(context, clip, onFirstFrame) }
    player.wantPlaying = playing
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.background = true
                Lifecycle.Event.ON_START -> player.background = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.release()
        }
    }
    AndroidView(
        factory = { TextureView(it).apply { surfaceTextureListener = player } },
        modifier = modifier,
        update = { if (it.surfaceTextureListener !== player) it.surfaceTextureListener = player },
    )
}

/** Opens the clip once the texture exists and follows [wantPlaying] and [background] from then on. */
private class DemoPlayer(
    private val context: Context,
    private val clip: String,
    private val onFirstFrame: () -> Unit,
) : TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false

    var wantPlaying = false
        set(value) { field = value; apply() }
    var background = false
        set(value) { field = value; apply() }

    private fun apply() {
        val p = player ?: return
        if (!prepared) return
        val run = wantPlaying && !background
        if (run && !p.isPlaying) p.start() else if (!run && p.isPlaying) p.pause()
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        release()
        val s = Surface(texture)
        surface = s
        player = runCatching {
            MediaPlayer().apply {
                context.assets.openFd("demos/$clip.mp4").use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                setSurface(s)
                isLooping = true
                setVolume(0f, 0f)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onFirstFrame()
                    false
                }
                setOnPreparedListener {
                    prepared = true
                    // Paused from the start (Reduce Motion): show the first frame instead of a black texture.
                    if (!(wantPlaying && !background)) it.seekTo(0)
                    apply()
                }
                prepareAsync()
            }
        }.getOrNull()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    fun release() {
        prepared = false
        player?.release()
        player = null
        surface?.release()
        surface = null
    }
}
