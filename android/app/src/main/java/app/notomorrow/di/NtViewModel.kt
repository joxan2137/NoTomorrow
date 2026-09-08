package app.notomorrow.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * The one way a feature builds its view model.
 *
 * ```kotlin
 * val model = ntViewModel { container -> DashboardViewModel(container.db.workoutDao(), …) }
 * ```
 *
 * The factory is remembered against the container, so the view model survives recomposition and is
 * scoped to the nearest [ViewModelStoreOwner] — the nav back-stack entry inside a `NavHost`, the
 * Activity otherwise. This is the whole DI story for features: no Hilt, no `SavedStateHandle`
 * plumbing unless a screen actually needs one.
 *
 * @param key distinguishes two view models of the same type in one owner (e.g. one editor per
 *   settings route hosted by the same entry).
 */
@Composable
inline fun <reified VM : ViewModel> ntViewModel(
    key: String? = null,
    viewModelStoreOwner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "ntViewModel needs a ViewModelStoreOwner in the composition."
    },
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = LocalAppContainer.current
    val factory = remember(container) {
        viewModelFactory { initializer { create(container) } }
    }
    return viewModel(viewModelStoreOwner = viewModelStoreOwner, key = key, factory = factory)
}
