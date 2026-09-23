package app.notomorrow.feature.fuel

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.data.dao.FoodDao
import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtAlert
import app.notomorrow.designsystem.NtAlertAction
import app.notomorrow.designsystem.NtAlertRole
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.NtSpinner
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.SecondaryButton
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntDismissKeyboardOnScroll
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.designsystem.tabular
import app.notomorrow.di.LocalAppContainer
import app.notomorrow.model.FoodSource
import app.notomorrow.service.BarcodeKey
import app.notomorrow.service.ProductStub
import app.notomorrow.util.ImageDownscaler
import app.notomorrow.util.LocaleProvider
import app.notomorrow.util.Parsing
import app.notomorrow.util.S
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

/**
 * Saves a product the database cannot size (not in Open Food Facts, a name without nutrition, or
 * an in-store code) from its printed label, so the next scan finds it on this device — the port
 * of `Features/Fuel/ProductLabelSheet.swift`.
 *
 * [code] is the scanned code; the item is filed under [BarcodeKey.storageKey], which is the
 * 7-digit item key for in-store weight labels. A stub from Open Food Facts pre-fills the name,
 * brand and serving. "Photograph the label" lets the AI read the nutrition table into the fields
 * ([LabelPhotoReader]). [onSave] writes the food ([ProductLabel.save]; `brand` is the one read
 * from the label, used when Open Food Facts gave none) and opens its portion sheet; a throw shows
 * `fuel.label.saveFailed` and keeps the sheet up.
 */
@Composable
fun ProductLabelSheet(
    code: String,
    stub: ProductStub?,
    onDismiss: () -> Unit,
    onSave: suspend (name: String, values: LabelValues, brand: String?) -> Unit,
) {
    var name by remember(code) { mutableStateOf(stub?.name ?: "") }
    var kcalText by remember(code) { mutableStateOf("") }
    var proteinText by remember(code) { mutableStateOf("") }
    var carbsText by remember(code) { mutableStateOf("") }
    var fatText by remember(code) { mutableStateOf("") }
    var fiberText by remember(code) { mutableStateOf("") }
    var servingText by remember(code) {
        mutableStateOf(stub?.servingSizeG?.let { FuelDerive.portionText(it, LocaleProvider.current()) } ?: "")
    }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    /** A brand printed on the label, used when Open Food Facts gave none. */
    var readBrand by remember(code) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val reader = remember(code) {
        LabelPhotoReader(
            scope = scope,
            upload = { container.aiEstimateService.upload() },
            service = { container.aiEstimateService.service(it) },
            consentOnce = { container.appPrefs.aiConsentOnce(it) },
            recordConsent = { container.appPrefs.setAiConsent(it, true) },
            language = { LocaleProvider.current().language.ifEmpty { "en" } },
        )
    }
    DisposableEffect(reader) { onDispose { reader.cancel() } }
    val readerState by reader.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val readLabel: (suspend () -> ByteArray?) -> Unit = { photo ->
        reader.read(photo) { reading ->
            val fill = LabelFill.from(reading, name, LocaleProvider.current())
            fill.name?.let { name = it }
            fill.brand?.let { readBrand = it }
            fill.kcal?.let { kcalText = it }
            fill.protein?.let { proteinText = it }
            fill.carbs?.let { carbsText = it }
            fill.fat?.let { fatText = it }
            fill.fiber?.let { fiberText = it }
            fill.serving?.let { servingText = it }
        }
    }
    val library = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            readLabel { withContext(Dispatchers.IO) { ImageDownscaler.jpeg(context, uri, ImageDownscaler.LABEL_LONG_EDGE) } }
        }
    }
    val cameraAvailable = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var showsCamera by remember { mutableStateOf(false) }
    val openLibrary = {
        focusManager.clearFocus()
        library.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val key = BarcodeKey.storageKey(code)
    val trimmedName = name.trim()
    val values = LabelValues.parse(
        kcal = kcalText, protein = proteinText, carbs = carbsText, fat = fatText,
        fiber = fiberText, serving = servingText,
    )
    val canSave = values != null && trimmedName.isNotEmpty() && !saving
    val missing = LabelValues.missingRequired(
        name = trimmedName, kcal = kcalText, protein = proteinText, carbs = carbsText, fat = fatText,
    )

    val nameFocus = remember { FocusRequester() }
    val kcalFocus = remember { FocusRequester() }
    LaunchedEffect(code) {
        runCatching { if (name.isEmpty()) nameFocus.requestFocus() else kcalFocus.requestFocus() }
    }

    NtSheet(onDismiss = onDismiss, containerColor = NT.Colors.ground) {
        Column(Modifier.fillMaxWidth().fillMaxHeight()) {
            ProductLabelHeader(onCancel = onDismiss)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // `.scrollDismissesKeyboard(.interactively)`.
                    .ntDismissKeyboardOnScroll()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = NT.Spacing.screenH)
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ProductLine(code = code, key = key, stub = stub, readBrand = readBrand)
                LabelPhotoRow(
                    status = readerState.status,
                    cameraAvailable = cameraAvailable,
                    onPhoto = {
                        if (cameraAvailable) {
                            focusManager.clearFocus()
                            showsCamera = true
                        } else {
                            openLibrary()
                        }
                    },
                    onLibrary = openLibrary,
                )
                LabelTextRow(
                    label = stringResource(S.fuel_foodName),
                    value = name,
                    onValue = { name = it },
                    focusRequester = nameFocus,
                    nextFocusRequester = kcalFocus,
                )
                Eyebrow(
                    text = stringResource(S.fuel_per100),
                    modifier = Modifier.padding(top = 8.dp),
                    color = NT.Colors.ink3,
                )
                LabelNumberRow(
                    label = stringResource(S.unit_kcal),
                    unit = stringResource(S.unit_kcal),
                    value = kcalText,
                    onValue = { kcalText = it },
                    modifier = Modifier.fillMaxWidth(),
                    focusRequester = kcalFocus,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabelNumberRow(
                        label = stringResource(S.fuel_macro_p),
                        accessibilityLabel = stringResource(S.macro_protein),
                        unit = stringResource(S.unit_g),
                        value = proteinText,
                        onValue = { proteinText = it },
                        modifier = Modifier.weight(1f),
                    )
                    LabelNumberRow(
                        label = stringResource(S.fuel_macro_c),
                        accessibilityLabel = stringResource(S.macro_carbs),
                        unit = stringResource(S.unit_g),
                        value = carbsText,
                        onValue = { carbsText = it },
                        modifier = Modifier.weight(1f),
                    )
                    LabelNumberRow(
                        label = stringResource(S.fuel_macro_f),
                        accessibilityLabel = stringResource(S.macro_fat),
                        unit = stringResource(S.unit_g),
                        value = fatText,
                        onValue = { fatText = it },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabelNumberRow(
                        label = stringResource(S.macro_fiber),
                        unit = stringResource(S.unit_g),
                        value = fiberText,
                        onValue = { fiberText = it },
                        modifier = Modifier.weight(1f),
                    )
                    LabelNumberRow(
                        label = stringResource(S.fuel_label_servingSize),
                        unit = null,
                        value = servingText,
                        onValue = { servingText = it },
                        modifier = Modifier.weight(1f),
                    )
                }
                NtText(
                    text = stringResource(S.fuel_label_hint),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                )
                if (failed) {
                    NtText(
                        text = stringResource(S.fuel_label_saveFailed),
                        style = NT.Fonts.footnote,
                        color = NT.Colors.bad,
                    )
                }
            }

            val saveHint = when {
                saving -> null
                missing.isNotEmpty() -> stringResource(
                    S.fuel_label_missing,
                    missing.map { field ->
                        when (field) {
                            LabelValues.Field.Name -> stringResource(S.fuel_foodName)
                            LabelValues.Field.Kcal -> stringResource(S.unit_kcal)
                            LabelValues.Field.Protein -> stringResource(S.macro_protein)
                            LabelValues.Field.Carbs -> stringResource(S.macro_carbs)
                            LabelValues.Field.Fat -> stringResource(S.macro_fat)
                        }
                    }.joinToString(", "),
                )
                // Everything is filled in but a figure is out of range (kcal over 950, macros past
                // 105 g, fiber over 100, a bad serving); the photo read may already be saying so.
                values == null && (readerState.status as? LabelPhotoReader.Status.Filled)?.needsReview != true ->
                    stringResource(S.fuel_label_checkMacros)
                else -> null
            }
            if (saveHint != null) {
                NtText(
                    text = saveHint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NT.Spacing.screenH)
                        .padding(top = 8.dp, bottom = 10.dp),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                )
            }
            PrimaryButton(
                title = stringResource(S.common_save),
                modifier = Modifier
                    .padding(horizontal = NT.Spacing.screenH)
                    .padding(bottom = 12.dp),
                enabled = canSave,
                onClick = {
                    val parsed = values ?: return@PrimaryButton
                    if (trimmedName.isEmpty()) return@PrimaryButton
                    saving = true
                    failed = false
                    scope.launch {
                        try {
                            onSave(trimmedName, parsed, readBrand)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failed = true
                        } finally {
                            saving = false
                        }
                    }
                },
            )
        }
    }

    if (showsCamera) {
        CameraCaptureCover(
            onCaptured = { jpeg ->
                showsCamera = false
                if (jpeg != null) readLabel { jpeg }
            },
            onCancel = { showsCamera = false },
            maxLongEdge = ImageDownscaler.LABEL_LONG_EDGE,
        )
    }

    readerState.consent?.let { upload ->
        val provider = stringResource(upload.providerNameRes)
        NtAlert(
            title = stringResource(S.fuel_ai_consent_title, provider),
            message = stringResource(S.fuel_ai_consent_body, provider),
            actions = listOf(
                NtAlertAction(title = stringResource(S.fuel_ai_consent_accept), onClick = reader::acceptConsent),
                NtAlertAction(
                    title = stringResource(S.common_cancel),
                    role = NtAlertRole.Cancel,
                    onClick = reader::declineConsent,
                ),
            ),
            // Runs before a tapped button's handler as well as on an outside tap / back, so it
            // only hides the alert; the reader turns an un-actioned dismissal into the decline.
            onDismiss = reader::dismissConsent,
        )
    }
}

/**
 * Every number field's placeholder. A grey "0" in a required field read as a value already there;
 * the dash reads as empty, and the hint above Save names the fields still missing.
 */
private const val EMPTY_PLACEHOLDER = "–"

@Composable
private fun ProductLabelHeader(onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .padding(horizontal = NT.Spacing.screenH)
            .height(NT.Size.control),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(
            text = stringResource(S.fuel_label_title),
            modifier = Modifier.weight(1f),
            style = NT.Fonts.title2,
            color = NT.Colors.ink,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .defaultMinSize(minHeight = NT.Size.control)
                .ntPlainClickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            NtText(
                text = stringResource(S.common_cancel),
                style = NT.Fonts.body,
                color = NT.Colors.ink2,
                maxLines = 1,
            )
        }
    }
}

/**
 * "Photograph the label" (camera, or the library without one) plus a round library shortcut; while
 * reading, a spinner; afterwards, what happened.
 */
@Composable
private fun LabelPhotoRow(
    status: LabelPhotoReader.Status,
    cameraAvailable: Boolean,
    onPhoto: () -> Unit,
    onLibrary: () -> Unit,
) {
    val reading = status == LabelPhotoReader.Status.Reading
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.alpha(if (reading) 0.5f else 1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryButton(
                title = stringResource(S.fuel_label_photo),
                modifier = Modifier.weight(1f),
                icon = if (cameraAvailable) NtIcons.Camera else NtIcons.PhotoOnRectangle,
                height = NT.Size.cardButton,
                enabled = !reading,
                onClick = onPhoto,
            )
            if (cameraAvailable) {
                Box(
                    modifier = Modifier
                        .size(NT.Size.cardButton)
                        // Named by the icon's description; TalkBack's default "double-tap to
                        // activate" follows it (the title is not an action phrase).
                        .pressScale(enabled = !reading, onClick = onLibrary)
                        .background(NT.Colors.surface2, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    NtIcon(
                        NtIcons.PhotoOnRectangle,
                        size = sfIconSize(17f),
                        tint = NT.Colors.ink,
                        contentDescription = stringResource(S.fuel_ai_chooseLibrary),
                    )
                }
            }
        }
        LabelReadStatus(status)
    }
}

@Composable
private fun LabelReadStatus(status: LabelPhotoReader.Status) {
    when (status) {
        LabelPhotoReader.Status.Idle -> Unit
        LabelPhotoReader.Status.Reading -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NtSpinner(color = NT.Colors.ink2)
            NtText(
                text = stringResource(S.fuel_label_reading),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
                maxLines = 1,
            )
        }
        is LabelPhotoReader.Status.Filled -> LabelStatusText(
            text = stringResource(if (status.needsReview) S.fuel_label_checkMacros else S.fuel_label_aiFilled),
            color = if (status.needsReview) NT.Colors.ember else NT.Colors.ink2,
        )
        LabelPhotoReader.Status.Unreadable -> LabelStatusText(stringResource(S.fuel_label_unreadable), NT.Colors.ember)
        is LabelPhotoReader.Status.Failed -> LabelStatusText(stringResource(status.messageRes), NT.Colors.ember)
    }
}

@Composable
private fun LabelStatusText(text: String, color: Color) {
    NtText(text = text, modifier = Modifier.fillMaxWidth(), style = NT.Fonts.footnote, color = color)
}

/** "MOWI · 150 g · 2050401935713", plus the store-label note when the item is filed under its 7-digit key. */
@Composable
private fun ProductLine(code: String, key: String, stub: ProductStub?, readBrand: String?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TabularText(
            text = (listOfNotNull(stub?.brand ?: readBrand, stub?.quantity) + code).joinToString(" · "),
            style = NT.Fonts.footnote,
            color = NT.Colors.ink2,
            maxLines = 1,
        )
        if (key != code) {
            NtText(
                text = stringResource(S.fuel_label_storeCode),
                style = NT.Fonts.footnote,
                color = NT.Colors.ember,
            )
        }
    }
}

/** 52 dp `surface` row: label on the left, right-aligned free text on the right. */
@Composable
private fun LabelTextRow(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    focusRequester: FocusRequester,
    nextFocusRequester: FocusRequester,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(NT.Colors.surface, NtShapes.field)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(text = label, style = NT.Fonts.subheadline, color = NT.Colors.ink2, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .semantics { contentDescription = label },
            textStyle = NT.Fonts.body.copy(color = NT.Colors.ink, textAlign = TextAlign.End),
            singleLine = true,
            cursorBrush = SolidColor(NT.Colors.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            // `.submitLabel(.next).onSubmit { focus = .kcal }`.
            keyboardActions = KeyboardActions(
                onNext = { runCatching { nextFocusRequester.requestFocus() } },
            ),
        )
    }
}

/** The same row with a decimal keypad, a placeholder and an optional trailing unit. */
@Composable
private fun LabelNumberRow(
    label: String,
    unit: String?,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** What TalkBack calls the field; "B" / "W" / "T" on screen are "Białko" / "Węglowodany" / "Tłuszcze". */
    accessibilityLabel: String = label,
    placeholder: String = EMPTY_PLACEHOLDER,
    focusRequester: FocusRequester? = null,
) {
    val field = remember { FocusRequester() }
    val requester = focusRequester ?: field
    Row(
        modifier = modifier
            .height(52.dp)
            .background(NT.Colors.surface, NtShapes.field)
            .ntPlainClickable(onClick = { runCatching { requester.requestFocus() } })
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(text = label, style = NT.Fonts.subheadline, color = NT.Colors.ink2, maxLines = 1)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (value.isEmpty()) {
                NtText(
                    text = placeholder,
                    style = labelFieldStyle(NT.Colors.ink3),
                    color = NT.Colors.ink3,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValue,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(requester)
                    .semantics { contentDescription = accessibilityLabel },
                textStyle = labelFieldStyle(NT.Colors.ink),
                singleLine = true,
                cursorBrush = SolidColor(NT.Colors.ink),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {}),
            )
        }
        if (unit != null) {
            NtText(text = unit, style = NT.Fonts.footnote, color = NT.Colors.ink2, maxLines = 1)
        }
    }
}

private fun labelFieldStyle(color: Color): TextStyle =
    NT.Fonts.body.tabular().copy(color = color, textAlign = TextAlign.End)

// ─────────────────────────────────────────────────────────────────────────────
// Rules and storage
// ─────────────────────────────────────────────────────────────────────────────

/** The figures typed from a pack's nutrition table, per 100 g, with the form's rules (`LabelValues`). */
data class LabelValues(
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val fiber: Double? = null,
    val servingG: Double? = null,
) {
    /** The fields Save needs, in form order. */
    enum class Field { Name, Kcal, Protein, Carbs, Fat }

    companion object {
        /**
         * Protein + carbs + fat can pass 100 g only through label rounding or a per-100 ml table;
         * the backend's `nutritionFromProduct` uses the same bound.
         */
        const val MAX_MACRO_SUM: Double = 105.0
        const val MAX_SERVING_G: Double = 5000.0

        /**
         * `null` when kcal or a macro is empty or not a number, kcal is over 950, the macros add up
         * past [MAX_MACRO_SUM], fiber is over 100, or the serving is not a positive weight. Fiber
         * and serving may stay empty.
         */
        fun parse(kcal: String, protein: String, carbs: String, fat: String, fiber: String, serving: String): LabelValues? {
            val k = Parsing.nonNegative(kcal) ?: return null
            val p = Parsing.nonNegative(protein) ?: return null
            val c = Parsing.nonNegative(carbs) ?: return null
            val f = Parsing.nonNegative(fat) ?: return null
            if (k > 950 || p + c + f > MAX_MACRO_SUM) return null
            var values = LabelValues(kcal = k, protein = p, carbs = c, fat = f)
            if (fiber.isNotBlank()) {
                val value = Parsing.nonNegative(fiber)?.takeIf { it <= 100 } ?: return null
                values = values.copy(fiber = value)
            }
            if (serving.isNotBlank()) {
                val value = Parsing.nonNegative(serving)?.takeIf { it > 0 && it <= MAX_SERVING_G } ?: return null
                values = values.copy(servingG = value)
            }
            return values
        }

        /** The required fields still empty, in form order: what the hint above a disabled Save names. */
        fun missingRequired(name: String, kcal: String, protein: String, carbs: String, fat: String): List<Field> =
            listOf(
                Field.Name to name,
                Field.Kcal to kcal,
                Field.Protein to protein,
                Field.Carbs to carbs,
                Field.Fat to fat,
            ).filter { (_, text) -> text.isBlank() }.map { it.first }
    }
}

object ProductLabel {
    /**
     * Writes the label as the custom food `label:<key>` filed under barcode [key], updating it
     * when the user saves the same code again (logged entries keep their own figures, and the row
     * keeps its usage stats). Brand and photo come from the stub; [brand] (read from the label
     * photo) fills in when the stub has none; a later save without either keeps them.
     */
    suspend fun save(
        key: String,
        name: String,
        stub: ProductStub?,
        values: LabelValues,
        dao: FoodDao,
        brand: String? = null,
    ): FoodItemEntity {
        val id = "label:$key"
        val existing = dao.byId(id)
        val base = existing ?: FoodItemEntity(
            id = id, name = name, source = FoodSource.Custom,
            kcalPer100 = 0.0, proteinPer100 = 0.0, carbsPer100 = 0.0, fatPer100 = 0.0,
        )
        val item = base.copy(
            name = name,
            brand = stub?.brand ?: brand ?: base.brand,
            source = FoodSource.Custom,
            barcode = key,
            kcalPer100 = values.kcal,
            proteinPer100 = values.protein,
            carbsPer100 = values.carbs,
            fatPer100 = values.fat,
            fiberPer100 = values.fiber,
            servingSizeG = values.servingG,
            servingLabel = null,
            imageURL = stub?.imageURL ?: base.imageURL,
        )
        dao.upsert(item)
        return item
    }
}
