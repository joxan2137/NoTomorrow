package app.notomorrow.feature.fuel

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.notomorrow.R
import app.notomorrow.designsystem.*

@Composable
fun ProductLabelSheet(barcode: String, onDismiss: () -> Unit, onSave: suspend (String, List<Double>) -> Unit) {
    var name by remember { mutableStateOf("") }
    val values = remember { mutableStateListOf("", "", "", "") }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val numbers = values.mapNotNull { it.replace(',', '.').trim().toDoubleOrNull() }
    val valid = name.isNotBlank() && numbers.size == 4 && numbers.all { it.isFinite() && it >= 0 } && numbers[0] <= 950 && numbers.drop(1).sum() <= 100
    val labels = listOf(R.string.unit_kcal, R.string.fuel_macro_p, R.string.fuel_macro_c, R.string.fuel_macro_f)
    NtSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NtText(stringResource(R.string.fuel_label_title), style = NT.Fonts.title2)
            NtText(stringResource(R.string.fuel_label_hint), style = NT.Fonts.footnote)
            NtText(barcode, style = NT.Fonts.footnote)
            OutlinedTextField(name, { name = it }, label = { NtText(stringResource(R.string.fuel_foodName), style = NT.Fonts.body) })
            NtText(stringResource(R.string.fuel_per100), style = NT.Fonts.headline)
            labels.forEachIndexed { i, label ->
                OutlinedTextField(values[i], { values[i] = it },
                    label = { NtText(stringResource(label), style = NT.Fonts.body) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
            }
            if (failed) NtText(stringResource(R.string.fuel_label_saveFailed), style = NT.Fonts.footnote)
            PrimaryButton(title = stringResource(R.string.common_save), enabled = valid && !saving) {
                saving = true
                scope.launch {
                    try { onSave(name.trim(), numbers) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { failed = true }
                    finally { saving = false }
                }
            }
        }
    }
}
