@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.mfbooks.ui.text

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.mfbooks.data.Book
import app.mfbooks.text.Matching
import app.mfbooks.text.ParsedText
import app.mfbooks.text.wordCount
import app.mfbooks.ui.player.SheetBase
import app.mfbooks.ui.settings.Choice
import app.mfbooks.ui.theme.tabular
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** Language options: the two matchable ones, plus [current] when the text declares something else. */
fun languageChoices(current: String) = (MATCHABLE_LANGS + current).distinct().map { Choice(it, langName(it)) }

/** [onAdd] gets the language, "start now", and whether matching can run at all for that language. */
@Composable
fun ReviewSheet(book: Book, parsed: ParsedText, onAdd: (lang: String, now: Boolean, match: Boolean) -> Unit, onReplace: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val doc = parsed.doc
    val lang = doc.lang
    var now by remember { mutableStateOf(false) }
    var continued by remember { mutableStateOf(false) }
    val words = remember(doc) { doc.wordCount() }
    val spoken by produceState<String?>(null) {
        value = try { Matching.detectSpokenLanguage(ctx, book.id, doc.lang) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }
    val matchable = lang in MATCHABLE_LANGS
    val mismatch = spoken?.takeIf { it != lang && !continued }

    SheetBase(onDismiss, "Add this text?", parsed.fileName) {
        Identity(book, parsed)
        Row(Modifier.fillMaxWidth()) {
            Fact("Words", "%,d".format(Locale.US, words), Modifier.weight(1f))
            Fact("Chapters", doc.toc.size.toString(), Modifier.weight(1f))
            Fact("Language", langName(lang), Modifier.weight(1f))
        }
        if (matchable) {
            Column(Modifier.selectableGroup()) {
                Text("Match text to audio", Modifier.padding(start = 4.dp, bottom = 6.dp), style = MaterialTheme.typography.titleSmall)
                MatchOption("While charging", !now) { now = false }
                MatchOption("Start now", now) { now = true }
            }
        } else Text("Word matching isn't available for this language yet", Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (matchable) VoskOffer(lang)

        if (mismatch != null) {
            Text("Audio is in ${langName(mismatch)}, text is in ${langName(lang)}", style = MaterialTheme.typography.titleMedium)
            PanelButtons("Replace text", onReplace, "Continue", { continued = true }, Modifier.padding(bottom = 4.dp))
        } else {
            Column {
                Button({ onAdd(lang, now, matchable) }, Modifier.fillMaxWidth().height(52.dp)) { Text("Add text", style = MaterialTheme.typography.titleSmall) }
                TextButton(onDismiss, Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("Cancel") }
            }
        }
    }
}

/** Title and author of the text, or both names with a warning when they don't look like the audiobook. */
@Composable
private fun Identity(book: Book, parsed: ParsedText) {
    val cs = MaterialTheme.colorScheme
    val doc = parsed.doc
    val same = remember(book, doc) { sameBook(doc.title, doc.author, book.title, book.author) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerHighest).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(if (same) cs.primary else cs.errorContainer), Alignment.Center) {
            if (same) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = cs.onPrimary)
            else Icon(Icons.Rounded.WarningAmber, "May be a different book", Modifier.size(18.dp), tint = cs.onErrorContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Names(if (same) null else "Text", doc.title ?: parsed.fileName, doc.author)
            if (!same) Names("Audiobook", book.title, book.author)
        }
    }
}

@Composable
private fun Names(label: String?, title: String, author: String?) {
    Column {
        label?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(title, style = MaterialTheme.typography.titleSmall)
        author?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.titleMedium.tabular())
    }
}

@Composable
private fun MatchOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).selectable(selected, role = Role.RadioButton, onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
