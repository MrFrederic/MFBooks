@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.mfbooks.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

class Choice<T>(val value: T, val label: String, val description: String? = null)

@Composable
private fun <T> RadioOption(c: Choice<T>, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, null, Modifier.padding(start = 8.dp))
        Column(Modifier.padding(start = 16.dp, end = 8.dp).padding(vertical = 6.dp)) {
            Text(c.label, style = MaterialTheme.typography.bodyLarge)
            c.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Single-choice dialog with radio options; each option may carry a short explanation. */
@Composable
fun <T> ChoiceDialog(title: String, options: List<Choice<T>>, selected: T, onSelect: (T) -> Unit, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { c -> RadioOption(c, c.value == selected) { onSelect(c.value) } }
            }
        },
        confirmButton = { TextButton(dismiss) { Text("Cancel") } },
    )
}

/**
 * Bottom sheet for the next/previous button mapping: seconds choices (as chips, producing "[prefix]:n") and
 * command actions listed separately.
 */
@Composable
fun ActionSheet(title: String, secondsPrefix: String, secondsLabel: String, seconds: List<Int>, actions: List<Choice<String>>, selected: String, onSelect: (String) -> Unit, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(title, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.headlineSmallEmphasized)
            Text(secondsLabel, Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary)
            FlowRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                seconds.forEach { n ->
                    val v = "$secondsPrefix:$n"
                    FilterChip(selected == v, { onSelect(v) }, label = { Text(secs(n)) })
                }
            }
            Text("Or run an action", Modifier.padding(start = 24.dp, top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(horizontal = 12.dp)) { actions.forEach { c -> RadioOption(c, c.value == selected) { onSelect(c.value) } } }
        }
    }
}
