package app.notomorrow.feature.health

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.notomorrow.R
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.GhostButton
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NTTheme
import app.notomorrow.designsystem.NtText

/**
 * Health Connect's privacy rationale — the screen behind
 * `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` (API ≤ 33) and, through the
 * `ViewPermissionUsageActivity` alias, `android.intent.action.VIEW_PERMISSION_USAGE` (API 34+).
 * Both entries are in `AndroidManifest.xml`, so this class must exist for the app to install.
 *
 * Play requires it to state the same policy as the store listing: what is read, what is written,
 * and where it goes (research §6.4). iOS has no counterpart — HealthKit shows Apple's own sheet —
 * so the copy is Android-only and lives in `res/values/strings_health.xml`.
 *
 * It is a standalone Activity launched by another app: it must not assume the tab shell, a
 * navigation host or a signed-in session, and it reads nothing from the container.
 *
 * Every string here goes through `R.string.*` rather than `util.S`: most of the copy is
 * Android-only and has no catalog key, and mixing the two accessors in one file hides which is
 * which at the call site.
 */
class PermissionsRationaleActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark irrespective of the system theme, exactly like MainActivity.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            NTTheme {
                PermissionsRationaleScreen(onDone = ::finish)
            }
        }
    }
}

@Composable
private fun PermissionsRationaleScreen(onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NT.Colors.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NT.Spacing.screenH)
            .padding(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(NT.Spacing.section),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Eyebrow(text = stringResource(R.string.health_rationale_eyebrow))
            NtText(
                text = stringResource(R.string.health_rationale_title),
                modifier = Modifier.fillMaxWidth(),
                style = NT.Fonts.title1,
                color = NT.Colors.ink,
            )
            NtText(
                text = stringResource(R.string.health_rationale_intro),
                modifier = Modifier.fillMaxWidth(),
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink2,
            )
        }

        Hairline()

        RationaleSection(
            title = stringResource(R.string.health_rationale_weight_title),
            body = stringResource(R.string.health_rationale_weight_body),
        )
        RationaleSection(
            title = stringResource(R.string.health_rationale_nutrition_title),
            body = stringResource(R.string.health_rationale_nutrition_body),
        )
        RationaleSection(
            title = stringResource(R.string.health_rationale_workouts_title),
            body = stringResource(R.string.health_rationale_workouts_body),
        )

        Hairline()

        RationaleSection(
            title = stringResource(R.string.health_rationale_privacy_title),
            body = stringResource(R.string.health_rationale_privacy_body),
        )

        GhostButton(title = stringResource(R.string.common_done), onClick = onDone)
    }
}

/** One "what we do with X" block: headline title over a footnote body, as on every editor screen. */
@Composable
private fun RationaleSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NtText(
            text = title,
            modifier = Modifier.fillMaxWidth(),
            style = NT.Fonts.headline,
            color = NT.Colors.ink,
        )
        NtText(
            text = body,
            modifier = Modifier.fillMaxWidth(),
            style = NT.Fonts.footnote,
            color = NT.Colors.ink2,
        )
    }
}
