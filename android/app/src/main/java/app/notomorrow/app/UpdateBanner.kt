package app.notomorrow.app

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.ntClickable
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.di.LocalAppContainer
import app.notomorrow.service.UpdateChecker
import app.notomorrow.util.S

/**
 * "A new version is out" strip at the top of the screen, shown while [UpdateChecker] has a newer
 * release — the port of `NoTomorrow/App/UpdateBanner.swift`. Tapping it opens the release on
 * GitHub, where the `.apk` is; the close button hides it until the next release.
 */
@Composable
fun UpdateBanner(tag: String, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(NT.Radius.tile)
    Row(
        modifier = modifier
            .padding(horizontal = NT.Spacing.screenH / 2)
            .padding(top = 4.dp, bottom = 8.dp)
            .fillMaxWidth()
            .shadow(12.dp, shape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
            .background(NT.Colors.surface, shape)
            .border(1.dp, NT.Colors.hairline, shape)
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f).ntPlainClickable(role = Role.Button, onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NtIcon(NtIcons.ArrowDownCircleFill, size = sfIconSize(22f), tint = NT.Colors.ember)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(S.update_banner_title_s, tag),
                    style = NT.Fonts.subheadlineBold,
                    color = NT.Colors.ink,
                )
                Text(
                    stringResource(S.update_banner_subtitle),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                )
            }
            Spacer(Modifier.width(0.dp))
        }

        Box(
            modifier = Modifier
                .size(32.dp)
                .ntClickable(role = Role.Button, onClickLabel = stringResource(S.update_banner_dismiss), onClick = onDismiss)
                .background(NT.Colors.surface2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            NtIcon(
                NtIcons.Xmark,
                size = sfIconSize(13f),
                tint = NT.Colors.ink2,
                contentDescription = stringResource(S.update_banner_dismiss),
            )
        }
    }
}

/**
 * Shows [UpdateBanner] above [content] (`.updateBannerInset()` on iOS), so the screen's own header
 * starts below it: the banner sits under the top system-bar inset and consumes it for [content],
 * whose `windowInsetsPadding(safeDrawing…)` then adds nothing. Applied per tab page and around onboarding
 * and the store error screen, never around the workout, which covers the banner as on iOS.
 */
@Composable
fun UpdateBannerInset(
    checker: UpdateChecker = LocalAppContainer.current.updateChecker,
    content: @Composable () -> Unit,
) {
    val tag by checker.availableTag.collectAsStateWithLifecycle()
    // Keeps the tag on screen while the banner animates away after Dismiss.
    val shownTag = rememberLastNonNull(tag)
    val context = LocalContext.current
    val top = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = tag != null
    // While the banner is up or animating, this column owns the top inset and [content] sees none;
    // either way [content] starts at the same place, so nothing jumps when the banner settles.
    val ownsInset = visibility.currentState || visibility.targetState
    Column(
        Modifier
            .fillMaxSize()
            .then(if (ownsInset) Modifier.windowInsetsPadding(top) else Modifier),
    ) {
        AnimatedVisibility(
            visibleState = visibility,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            UpdateBanner(
                tag = shownTag.orEmpty(),
                onOpen = {
                    val intent = Intent(Intent.ACTION_VIEW, UpdateChecker.releasePage(shownTag.orEmpty()).toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
                onDismiss = checker::dismiss,
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (ownsInset) Modifier.consumeWindowInsets(top) else Modifier),
        ) {
            content()
        }
    }
}
