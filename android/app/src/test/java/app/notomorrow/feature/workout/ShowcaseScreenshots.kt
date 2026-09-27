package app.notomorrow.feature.workout

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NTCard
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
 * Renders the 3D muscle view to PNG for checking it without a device. Off by default; run with
 * `./gradlew :app:testDebugUnitTest --tests '*ShowcaseScreenshots*' -PshowcaseShots=<dir>`. The demo
 * clips play on a TextureView, which Robolectric cannot draw, so they are checked from their files.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ShowcaseScreenshots {
    private val outDir: File? = System.getProperty("showcaseShots")?.let(::File)

    @Test
    fun bodies() {
        shoot("body3d-bench", 411, 470) {
            NTCard {
                Body3DCanvas(
                    strength = { muscleModelStrength(it, listOf("chest"), listOf("triceps", "shoulders")) },
                    modifier = Modifier.fillMaxWidth(),
                    selected = "chest",
                )
            }
        }
        shoot("body3d-heat", 411, 200) {
            NTCard {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MuscleHeatView(
                        mapOf("chest" to 12, "triceps" to 8, "shoulders" to 5, "lats" to 3, "quadriceps" to 9, "glutes" to 2),
                        Modifier.width(150.dp),
                    )
                }
            }
        }
    }

    private fun shoot(name: String, widthDp: Int, heightDp: Int, content: @Composable () -> Unit) {
        val dir = outDir
        assumeTrue("pass -PshowcaseShots=<dir> to render", dir != null)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        // The composables read their files off the main thread; load them first so the frame has them.
        Body3D.load(activity)
        val view = ComposeView(activity).apply {
            setContent { Column(Modifier.fillMaxSize().background(NT.Colors.ground).padding(8.dp)) { content() } }
        }
        activity.setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        val density = activity.resources.displayMetrics.density
        val w = (widthDp * density).toInt()
        val h = (heightDp * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        shadowOf(Looper.getMainLooper()).idle()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        dir!!.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
