package app.notomorrow.feature.workoutscan

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.designsystem.NTTheme
import app.notomorrow.feature.workout.MachineScanChrome
import app.notomorrow.util.S
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the machine scanner's panel (docs/machine-scan.md) to PNG without a device; the camera
 * preview is stood in for by a dark gradient. Off by default; run with
 * `./gradlew :app:testDebugUnitTest --tests '*MachineScanScreenshots*' -PscanShots=<dir>`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class MachineScanScreenshots {

    private val outDir: File? = System.getProperty("scanShots")?.let(::File)

    private val matches = listOf(
        ExerciseEntity("Seated_Leg_Curl", "Seated Leg Curl", "Uginanie nóg siedząc na maszynie", listOf("hamstrings"), equipment = "machine"),
        ExerciseEntity("nt_hs_seated_leg_curl", "Seated Leg Curl (Hammer Strength)", null, listOf("hamstrings"), listOf("calves"), equipment = "machine"),
        ExerciseEntity("Standing_Leg_Curl", "Standing Leg Curl", null, listOf("hamstrings"), equipment = "machine"),
    )

    @Test
    fun matches() = shoot("scan-matches") { MachineScanChrome(true, S.scan_machine_looking, matches, {}, {}, {}, {}) }

    @Test
    fun looking() = shoot("scan-looking") { MachineScanChrome(true, S.scan_machine_looking, emptyList(), {}, {}, {}, {}) }

    @Test
    fun photoNoMatch() = shoot("scan-photo-no-match") { MachineScanChrome(false, S.scan_machine_noMatch, emptyList(), {}, {}, {}, {}) }

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        val dir = outDir
        assumeTrue("pass -PscanShots=<dir> to render", dir != null)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            NTTheme {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color(0xFF3A3D42), Color(0xFF1B1C1F), Color(0xFF2A2B2E))),
                    ),
                ) { content() }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        val root: View = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        dir!!.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
