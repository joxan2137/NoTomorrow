package app.notomorrow.feature.settings

import app.notomorrow.data.entity.BroPairingEntity
import app.notomorrow.data.entity.GymScheduleEntity
import app.notomorrow.data.entity.UserProfileEntity
import app.notomorrow.model.AIProvider
import app.notomorrow.model.TrainingGoal
import app.notomorrow.model.WeightUnit
import java.io.File

// The immutable state `SettingsViewModel` publishes — the Settings sheet's whole world in one
// object, the way every Swift editor binds to the one `SettingsModel`.

/** Health Connect's three states (`docs/android-architecture.md`, Settings → Health app). */
data class HealthState(
    val isAvailable: Boolean = false,
    val needsProviderUpdate: Boolean = false,
    val isAuthorized: Boolean = false,
    val error: String? = null,
)

/** `ExportView`'s three fields. */
data class ExportState(
    // false until prepareExport() starts: it returns early while a build is in flight, so a
    // true default meant the export was never built. The view shows "preparing" while files is empty.
    val isPreparing: Boolean = false,
    val files: List<File> = emptyList(),
    val counts: SettingsExportFiles.Counts = SettingsExportFiles.Counts(),
)

internal data class BusyState(
    val isDeleting: Boolean = false,
    val isUnpairing: Boolean = false,
    val isSigningOut: Boolean = false,
)

internal data class AccountState(
    val isSignedIn: Boolean,
    val maskedAnthropicKey: String?,
    val maskedGeminiKey: String?,
    val accountName: String?,
    val partnerName: String?,
    val pairedAt: Long?,
    val myCode: String?,
)

internal data class ConfigState(
    val aiProvider: AIProvider,
    val useDemoData: Boolean,
    val restAutoStart: Boolean,
    val languageOverride: String?,
    val busy: BusyState,
)

/** One immutable state object for the sheet and its twelve editors. */
data class SettingsUiState(
    val profile: UserProfileEntity? = null,
    val schedule: GymScheduleEntity? = null,
    val pairing: BroPairingEntity? = null,
    val isSignedIn: Boolean = false,
    /** `null` while signed in but before `me()` came back — the row falls back to "Signed in". */
    val accountName: String? = null,
    val maskedAnthropicKey: String? = null,
    val maskedGeminiKey: String? = null,
    val partnerName: String? = null,
    val pairedAt: Long? = null,
    val myCode: String? = null,
    val aiProvider: AIProvider = AIProvider.Standard,
    val useDemoData: Boolean = false,
    val restAutoStart: Boolean = true,
    val languageOverride: String? = null,
    val isDeleting: Boolean = false,
    val isUnpairing: Boolean = false,
    val isSigningOut: Boolean = false,
    val health: HealthState = HealthState(),
    val export: ExportState = ExportState(),
) {
    val units: WeightUnit get() = profile?.units ?: WeightUnit.Kg

    val goal: TrainingGoal get() = profile?.goal ?: TrainingGoal.BuildMuscle

    val restSeconds: Int get() = profile?.defaultRestSeconds ?: 90

    val weekdays: List<Int> get() = schedule?.weekdays ?: GymScheduleEntity().weekdays

    val minuteOfDay: Int get() = schedule?.defaultMinuteOfDay ?: GymScheduleEntity.DEFAULT_MINUTE_OF_DAY

    val remindHourBefore: Boolean get() = schedule?.remindHourBefore ?: true

    val askIfSkippedAt21: Boolean get() = schedule?.askIfSkippedAt21 ?: true

    val isPaired: Boolean get() = (partnerName ?: pairing?.partnerName) != null
}
