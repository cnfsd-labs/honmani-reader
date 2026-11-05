package app.honmani.reader.ui.known

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.honmani.reader.AppContainer
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.data.words.KnownBatchEntity
import app.honmani.reader.data.words.KnownSource
import app.honmani.reader.diagnostics.logWarning
import app.honmani.reader.known.KnownPlan
import app.honmani.reader.known.TopNResult
import app.honmani.reader.known.WordListTooLargeException
import app.honmani.reader.known.WordListParser
import app.honmani.reader.ui.anki.KnownFromAnkiDialogs
import app.honmani.reader.ui.anki.rememberKnownFromAnki
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The sizes offered for "the N most common words". */
private val TOP_SIZES = listOf(500, 1000, 2000, 3000, 5000)

/** How many unmatched words the preview names. */
private const val PREVIEW_UNMATCHED = 5

/**
 * The steps of "Tell Honmani what you already know" after the sheet: a word list or the most
 * common words, worked out first and saved only on [confirm] (as one batch that can be undone).
 */
internal class KnownWordsFlow(
    private val context: Context,
    private val scope: CoroutineScope,
    private val container: AppContainer,
) {
    sealed interface Step {
        data object Working : Step

        data object Paste : Step

        data class Review(val plan: KnownPlan, val dictionary: String? = null) : Step

        data class Problem(val message: String) : Step
    }

    var step by mutableStateOf<Step?>(null)
        private set

    private var job: Job? = null

    fun showPaste() {
        step = Step.Paste
    }

    fun topN(n: Int) = work {
        when (val result = container.knownWords.planTopN(n)) {
            TopNResult.NoFrequencyDictionary -> Step.Problem(context.getString(R.string.known_no_frequency))
            is TopNResult.Ready -> Step.Review(result.plan, result.dictionary)
        }
    }

    fun pasted(text: String) = work {
        review(context.getString(R.string.known_pasted_name), WordListParser.parse(text))
    }

    fun file(uri: Uri) = work {
        val list = try {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use(WordListParser::read)
            } ?: return@work Step.Problem(context.getString(R.string.known_file_unreadable))
        } catch (e: WordListTooLargeException) {
            return@work Step.Problem(context.getString(R.string.known_file_too_large))
        }
        review(fileName(uri), list)
    }

    private suspend fun review(name: String, list: app.honmani.reader.known.ParsedWordList): Step {
        if (list.entries.isEmpty()) return Step.Problem(context.getString(R.string.known_list_empty))
        return Step.Review(container.knownWords.planWordList(name, list))
    }

    private fun work(block: suspend () -> Step) {
        job?.cancel()
        step = Step.Working
        job = scope.launch {
            step = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logWarning("Honmani.known", "Preparing known words failed", e)
                Step.Problem(context.getString(R.string.known_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun confirm(plan: KnownPlan) {
        step = null
        scope.launch {
            val (_, change) = withContext(NonCancellable) { container.knownWords.confirm(plan) }
            toast(context.resources.getQuantityString(R.plurals.known_done, change.changed, change.changed))
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        step = null
    }

    fun undo(batch: KnownBatchEntity) {
        scope.launch {
            val undone = withContext(NonCancellable) { container.knownWords.batches.undo(batch.id) }
            val text = context.resources.getQuantityString(R.plurals.known_undone, undone.removed + undone.restored, undone.removed + undone.restored)
            toast(if (undone.leftAlone > 0) text + " " + context.resources.getQuantityString(R.plurals.known_left_alone, undone.leftAlone, undone.leftAlone) else text)
        }
    }

    private fun fileName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: context.getString(R.string.known_file_name)

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}

/**
 * "Tell Honmani what you already know": mark the most common words, a word list or an Anki
 * deck as Known in one step, and undo any earlier step. From the Words screen and the settings.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun KnownWordsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as HonmaniApplication).container }
    val scope = rememberCoroutineScope()
    val flow = remember { KnownWordsFlow(context, scope, container) }
    val knownFromAnki = rememberKnownFromAnki()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(flow::file) }
    val batches by container.knownWords.batches.batches.collectAsState(initial = emptyList())

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.known_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = stringResource(R.string.known_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Text(
                text = stringResource(R.string.known_top_heading),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                for (n in TOP_SIZES) {
                    AssistChip(onClick = { flow.topN(n) }, label = { Text(n.toString()) })
                }
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.known_word_list)) },
                supportingContent = { Text(stringResource(R.string.known_word_list_details)) },
                modifier = Modifier.clickable { picker.launch(arrayOf("text/*", "application/octet-stream")) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.known_paste)) },
                modifier = Modifier.clickable(onClick = flow::showPaste),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.known_anki_deck)) },
                supportingContent = { Text(stringResource(R.string.known_anki_deck_details)) },
                modifier = Modifier.clickable(onClick = knownFromAnki::start),
            )
            // Not built yet; the batch list already understands its results (KnownSource.QUIZ).
            ListItem(
                headlineContent = { Text(stringResource(R.string.known_quiz), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                supportingContent = { Text(stringResource(R.string.known_quiz_soon)) },
            )
            if (batches.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(R.string.known_batches),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                for (batch in batches) {
                    ListItem(
                        headlineContent = { Text(batchTitle(batch)) },
                        supportingContent = {
                            Text(
                                pluralStringResource(R.plurals.known_batch_words, batch.count, batch.count) + " · " +
                                    DateUtils.getRelativeTimeSpanString(batch.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                            )
                        },
                        trailingContent = { TextButton(onClick = { flow.undo(batch) }) { Text(stringResource(R.string.known_undo)) } },
                    )
                }
            }
        }
    }
    KnownFromAnkiDialogs(knownFromAnki)
    KnownWordsDialogs(flow)
}

@Composable
private fun batchTitle(batch: KnownBatchEntity): String = when (batch.source) {
    KnownSource.TOP_N -> stringResource(R.string.known_batch_top, batch.detail)
    KnownSource.ANKI_DECK -> stringResource(R.string.known_batch_anki, batch.detail)
    KnownSource.WORD_LIST -> stringResource(R.string.known_batch_list, batch.detail)
    KnownSource.QUIZ -> stringResource(R.string.known_batch_quiz)
}

@Composable
private fun KnownWordsDialogs(flow: KnownWordsFlow) {
    when (val step = flow.step) {
        null -> Unit
        KnownWordsFlow.Step.Working -> AlertDialog(
            onDismissRequest = flow::cancel,
            title = { Text(stringResource(R.string.known_working)) },
            text = { CircularProgressIndicator() },
            confirmButton = {},
            dismissButton = { TextButton(onClick = flow::cancel) { Text(stringResource(R.string.action_cancel)) } },
        )
        KnownWordsFlow.Step.Paste -> {
            var text by rememberSaveable { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = flow::cancel,
                title = { Text(stringResource(R.string.known_paste)) },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(stringResource(R.string.known_paste_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { flow.pasted(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.known_check)) }
                },
                dismissButton = { TextButton(onClick = flow::cancel) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
        is KnownWordsFlow.Step.Problem -> AlertDialog(
            onDismissRequest = flow::cancel,
            text = { Text(step.message) },
            confirmButton = { TextButton(onClick = flow::cancel) { Text(stringResource(R.string.action_close)) } },
        )
        is KnownWordsFlow.Step.Review -> {
            val plan = step.plan
            val change = plan.change
            AlertDialog(
                onDismissRequest = flow::cancel,
                title = { Text(pluralStringResource(R.plurals.anki_known_title, change.changed, change.changed)) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            when (plan.source) {
                                KnownSource.TOP_N -> stringResource(R.string.known_review_top, plan.detail, step.dictionary.orEmpty())
                                KnownSource.ANKI_DECK -> stringResource(R.string.known_review_anki, plan.detail)
                                else -> stringResource(R.string.known_review_list, plan.detail)
                            },
                        )
                        Text(stringResource(R.string.known_review_marks, change.changed, change.created, change.upgraded))
                        if (change.unchanged > 0) Text(stringResource(R.string.known_review_already, change.unchanged))
                        if (plan.resolved.names > 0) Text(stringResource(R.string.known_review_names, plan.resolved.names))
                        if (plan.skipped > 0) Text(stringResource(R.string.known_review_skipped, plan.skipped))
                        if (plan.resolved.unmatched.isNotEmpty()) {
                            Text(
                                stringResource(
                                    R.string.known_review_unmatched,
                                    plan.resolved.unmatched.size,
                                    plan.resolved.unmatched.take(PREVIEW_UNMATCHED).joinToString("、"),
                                ),
                            )
                        }
                        if (change.changed > 0) Text(stringResource(R.string.known_review_undo))
                    }
                },
                confirmButton = {
                    TextButton(onClick = { flow.confirm(plan) }, enabled = change.changed > 0) { Text(stringResource(R.string.anki_known_mark)) }
                },
                dismissButton = { TextButton(onClick = flow::cancel) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
    }
}
