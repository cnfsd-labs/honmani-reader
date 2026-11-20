package app.honmani.reader.ui.known

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.widget.Toast
import java.io.IOException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.honmani.reader.AppContainer
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.ui.PullDownSheet
import app.honmani.reader.data.dictionary.CatalogDictionary
import app.honmani.reader.data.dictionary.DictionaryCatalog
import app.honmani.reader.data.dictionary.ImportStatus
import app.honmani.reader.data.words.KnownBatchEntity
import app.honmani.reader.data.words.KnownSource
import app.honmani.reader.diagnostics.logWarning
import app.honmani.reader.known.KnownPlan
import app.honmani.reader.known.KnownWords
import app.honmani.reader.known.MissingDictionary
import app.honmani.reader.known.PlacementQuiz
import app.honmani.reader.known.QuizAnswer
import app.honmani.reader.known.QuizSetup
import app.honmani.reader.known.TopNResult
import app.honmani.reader.known.WaniKaniException
import app.honmani.reader.known.WaniKaniProblem
import app.honmani.reader.known.WordListTooLargeException
import app.honmani.reader.known.WordListParser
import app.honmani.reader.ui.anki.KnownFromAnkiDialogs
import app.honmani.reader.ui.anki.rememberKnownFromAnki
import app.honmani.reader.ui.rememberWithNotifications
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.exp
import kotlin.math.ln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
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

        /** Asks for the WaniKani token (kept only in [waniKaniToken], never saved). */
        data object WaniKani : Step

        data class Review(val plan: KnownPlan, val dictionary: String? = null) : Step

        /**
         * [offer]: the problem is a missing dictionary, which one tap can download (never one already installed).
         * [retryWaniKani]: WaniKani could not be read; "Try again" returns to the token, still filled in.
         */
        /** [inDictionaries]: the fix is on the Dictionaries screen (a dictionary turned off or still downloading). */
        data class Problem(
            val message: String,
            val offer: CatalogDictionary? = null,
            val retryWaniKani: Boolean = false,
            val inDictionaries: Boolean = false,
        ) : Step

        /** The quiz is asking word number `answers.size + 1`; [answers] are the answers so far (true = known). */
        data class Quiz(val setup: QuizSetup, val answers: List<Boolean> = emptyList()) : Step

        /** All asked: [estimate] is where the answers say the reader's vocabulary ends (a slider start). */
        data class QuizResult(val setup: QuizSetup, val answers: List<Boolean>, val estimate: Int) : Step
    }

    var step by mutableStateOf<Step?>(null)
        private set

    private var job: Job? = null

    private var requested: CatalogDictionary? = null

    /** The WaniKani token while the steps are open, so "Try again" keeps it; in memory only, cleared on [cancel]. */
    var waniKaniToken by mutableStateOf("")
    var waniKaniKanji by mutableStateOf(false)

    /**
     * What the most common words lack. "Downloading" only while the requested download is still
     * queued or running; once it failed or was cancelled, the download is offered again. A dictionary
     * installed but turned off is not offered again (its download would fail as already installed).
     */
    private suspend fun missing(what: MissingDictionary): Step {
        if (requested != null && !frequencyPending(container.dictionaryImportScheduler.imports.first())) requested = null
        if (requested != null) return downloading()
        val installed = container.dictionaryRepository.dictionaries.first()
        return when (what) {
            MissingDictionary.FREQUENCY ->
                if (DictionaryCatalog.isInstalled(DictionaryCatalog.frequency, installed.map { it.title })) {
                    Step.Problem(context.getString(R.string.known_frequency_off), inDictionaries = true)
                } else {
                    Step.Problem(context.getString(R.string.known_no_frequency), offer = DictionaryCatalog.frequency)
                }
            MissingDictionary.WORDS ->
                if (installed.any { it.termCount > 0 }) {
                    Step.Problem(context.getString(R.string.known_words_off), inDictionaries = true)
                } else {
                    Step.Problem(context.getString(R.string.known_no_words, DictionaryCatalog.words.name), offer = DictionaryCatalog.words)
                }
        }
    }

    private fun downloading() = Step.Problem(context.getString(R.string.known_dictionary_downloading), inDictionaries = true)

    /** Starts the same download as the Dictionaries screen's (once); the reader tries again once it is in. */
    fun download(dictionary: CatalogDictionary) {
        if (requested == null) container.dictionaryImportScheduler.download(dictionary)
        requested = dictionary
        step = downloading()
    }

    fun showPaste() {
        step = Step.Paste
    }

    fun showWaniKani() {
        step = Step.WaniKani
    }

    /** Reads the reader's WaniKani items at Guru and above with [waniKaniToken], which is not saved anywhere. */
    fun waniKani() = work {
        val token = waniKaniToken
        try {
            container.knownWords.planWaniKani(token, waniKaniKanji)?.let { Step.Review(it) }
                ?: Step.Problem(context.getString(R.string.known_wanikani_empty))
        } catch (e: WaniKaniException) {
            Step.Problem(
                context.getString(
                    when (e.problem) {
                        WaniKaniProblem.NO_TOKEN -> R.string.known_wanikani_no_token
                        WaniKaniProblem.BAD_TOKEN -> R.string.known_wanikani_bad_token
                        WaniKaniProblem.OFFLINE -> R.string.known_wanikani_offline
                        WaniKaniProblem.RATE_LIMITED -> R.string.known_wanikani_rate_limited
                        WaniKaniProblem.FAILED -> R.string.known_wanikani_failed
                    },
                ),
                retryWaniKani = true,
            )
        }
    }

    fun topN(n: Int) = work {
        when (val result = container.knownWords.planTopN(n)) {
            is TopNResult.Missing -> missing(result.dictionary)
            is TopNResult.Ready -> Step.Review(result.plan, result.dictionary)
        }
    }

    fun startQuiz() = work {
        val setup = container.knownWords.prepareQuiz()
        when {
            setup == null -> container.knownWords.missingDictionary()?.let { missing(it) }
                ?: Step.Problem(context.getString(R.string.known_list_empty))
            setup.questions.isEmpty() -> Step.Problem(context.getString(R.string.known_list_empty))
            else -> Step.Quiz(setup)
        }
    }

    fun answer(known: Boolean) {
        val quiz = step as? Step.Quiz ?: return
        val answers = quiz.answers + known
        val questions = quiz.setup.questions
        step = if (answers.size < questions.size) {
            quiz.copy(answers = answers)
        } else {
            val estimate = PlacementQuiz.estimate(questions.zip(answers) { q, a -> QuizAnswer(q.rank, a) }, quiz.setup.size) ?: 0
            Step.QuizResult(quiz.setup, answers, PlacementQuiz.tidy(estimate).coerceAtMost(quiz.setup.size))
        }
    }

    fun undoAnswer() {
        val quiz = step as? Step.Quiz ?: return
        step = quiz.copy(answers = quiz.answers.dropLast(1))
    }

    /** Back in the quiz: undoes the last answer while there is one, else leaves. */
    fun quizBack() {
        val current = step ?: return
        step = quizStepBack(current)
        if (step == null) cancel()
    }

    /** From the slider to the usual review of what would be marked. */
    fun quizReview(result: Step.QuizResult, n: Int) = work {
        val answers = result.setup.questions.zip(result.answers)
        when (val planned = container.knownWords.planQuiz(n, answers)) {
            is TopNResult.Missing -> missing(planned.dictionary)
            is TopNResult.Ready -> Step.Review(planned.plan, planned.dictionary)
        }
    }

    fun pasted(text: String) = work {
        review(context.getString(R.string.known_pasted_name), withContext(Dispatchers.Default) { WordListParser.parse(text) })
    }

    fun file(uri: Uri) = work {
        val list = try {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use(WordListParser::read)
            } ?: return@work Step.Problem(context.getString(R.string.known_file_unreadable))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val problem = fileProblem(e) ?: throw e
            logWarning("Honmani.known", "Reading a word list failed", e)
            return@work Step.Problem(context.getString(problem))
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
                Step.Problem(context.getString(R.string.known_failed))
            }
        }
    }

    fun confirm(plan: KnownPlan) {
        step = null
        waniKaniToken = ""
        scope.launch {
            val (_, change) = withContext(NonCancellable) { container.knownWords.confirm(plan) }
            toast(context.resources.getQuantityString(R.plurals.known_done, change.changed, change.changed))
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        step = null
        waniKaniToken = ""
        waniKaniKanji = false
    }

    fun undo(batch: KnownBatchEntity) {
        scope.launch {
            val undone = withContext(NonCancellable) { container.knownWords.batches.undo(batch.id) }
            val text = context.resources.getQuantityString(R.plurals.known_undone, undone.removed + undone.restored, undone.removed + undone.restored)
            toast(if (undone.leftAlone > 0) text + " " + context.resources.getQuantityString(R.plurals.known_left_alone, undone.leftAlone, undone.leftAlone) else text)
        }
    }

    private suspend fun fileName(uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } ?: context.getString(R.string.known_file_name)

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}

/** Whether a dictionary download or import is still queued or running (failed and cancelled ones are not). */
internal fun frequencyPending(imports: List<ImportStatus>): Boolean =
    imports.any { it.replaces == null && it !is ImportStatus.Failed }

/** The quiz step Back leads to: the previous question (its answer undone), or null to leave. */
internal fun quizStepBack(step: KnownWordsFlow.Step): KnownWordsFlow.Step? = when (step) {
    is KnownWordsFlow.Step.Quiz -> if (step.answers.isEmpty()) null else step.copy(answers = step.answers.dropLast(1))
    is KnownWordsFlow.Step.QuizResult -> KnownWordsFlow.Step.Quiz(step.setup, step.answers.dropLast(1))
    else -> null
}

/** The message for a word list file that could not be opened or read; null for other errors. */
internal fun fileProblem(e: Exception): Int? = when (e) {
    is WordListTooLargeException -> R.string.known_file_too_large
    // A revoked grant, a moved or deleted file, a cloud file that is offline.
    is SecurityException, is IOException -> R.string.known_file_unreadable
    else -> null
}

/**
 * "Tell Honmani what you already know": mark the most common words, a word list or an Anki
 * deck as Known in one step, and undo any earlier step. From the Words screen and the settings.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun KnownWordsSheet(onDismiss: () -> Unit, onOpenDictionaries: (() -> Unit)? = null) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as HonmaniApplication).container }
    val scope = rememberCoroutineScope()
    val flow = remember { KnownWordsFlow(context, scope, container) }
    val knownFromAnki = rememberKnownFromAnki()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(flow::file) }
    val batches by container.knownWords.batches.batches.collectAsState(initial = emptyList())

    PullDownSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState(), overscrollEffect = null).padding(bottom = 24.dp)) {
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
            ListItem(
                headlineContent = { Text(stringResource(R.string.known_wanikani)) },
                supportingContent = { Text(stringResource(R.string.known_wanikani_details)) },
                modifier = Modifier.clickable(onClick = flow::showWaniKani),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.known_quiz)) },
                supportingContent = { Text(stringResource(R.string.known_quiz_details)) },
                modifier = Modifier.clickable(onClick = flow::startQuiz),
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
    KnownWordsDialogs(
        flow,
        onOpenDictionaries = onOpenDictionaries?.let { open ->
            {
                flow.cancel()
                onDismiss()
                open()
            }
        },
    )
}

@Composable
private fun batchTitle(batch: KnownBatchEntity): String = when (batch.source) {
    KnownSource.TOP_N -> stringResource(R.string.known_batch_top, batch.detail)
    KnownSource.ANKI_DECK -> stringResource(R.string.known_batch_anki, batch.detail)
    KnownSource.WORD_LIST -> stringResource(R.string.known_batch_list, batch.detail)
    KnownSource.WANIKANI -> stringResource(
        if (batch.detail == KnownWords.WANIKANI_KANJI) R.string.known_batch_wanikani_kanji else R.string.known_batch_wanikani,
    )
    KnownSource.QUIZ -> stringResource(R.string.known_batch_quiz, "%,d".format(batch.detail.toIntOrNull() ?: 0))
}

@Composable
private fun KnownWordsDialogs(flow: KnownWordsFlow, onOpenDictionaries: (() -> Unit)?) {
    val withNotifications = rememberWithNotifications()
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
                // Back or a stray tap must not throw away pasted text; Cancel does.
                onDismissRequest = { if (text.isBlank()) flow.cancel() },
                properties = DialogProperties(dismissOnClickOutside = false),
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
        KnownWordsFlow.Step.WaniKani -> {
            // Kept in the flow, not rememberSaveable: the token must not be written to the saved instance state.
            val token = flow.waniKaniToken
            val kanji = flow.waniKaniKanji
            AlertDialog(
                onDismissRequest = flow::cancel,
                title = { Text(stringResource(R.string.known_wanikani)) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.known_wanikani_intro))
                        OutlinedTextField(
                            value = token,
                            onValueChange = { flow.waniKaniToken = it },
                            label = { Text(stringResource(R.string.known_wanikani_token)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            // A secret: no suggestions, nothing learnt by the keyboard.
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().toggleable(kanji, role = Role.Checkbox) { flow.waniKaniKanji = it },
                        ) {
                            Checkbox(checked = kanji, onCheckedChange = null)
                            Text(stringResource(R.string.known_wanikani_kanji), modifier = Modifier.padding(start = 8.dp))
                        }
                        Text(stringResource(R.string.known_wanikani_privacy), style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = flow::waniKani, enabled = token.isNotBlank()) { Text(stringResource(R.string.known_check)) }
                },
                dismissButton = { TextButton(onClick = flow::cancel) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
        is KnownWordsFlow.Step.Quiz -> {
            val questions = step.setup.questions
            val question = questions[step.answers.size]
            val word = question.word
            AlertDialog(
                // Back undoes the last answer (leaves at the first question); a stray tap outside does nothing.
                onDismissRequest = flow::quizBack,
                properties = DialogProperties(dismissOnClickOutside = false),
                title = { Text(stringResource(R.string.known_quiz_title, step.answers.size + 1, questions.size)) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(question.shown, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
                        if (word.reading != question.shown) Text(word.reading, style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = flow::undoAnswer, enabled = step.answers.isNotEmpty()) {
                            Text(stringResource(R.string.known_quiz_back))
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { flow.answer(true) }) { Text(stringResource(R.string.known_quiz_know)) } },
                dismissButton = { TextButton(onClick = { flow.answer(false) }) { Text(stringResource(R.string.known_quiz_dont)) } },
            )
        }
        is KnownWordsFlow.Step.QuizResult -> {
            // The slider moves on a log scale: a step near 1,000 words matters more than one near 10,000.
            var position by rememberSaveable { mutableFloatStateOf(ln(step.estimate.coerceAtLeast(10).toFloat())) }
            val n = PlacementQuiz.tidy(exp(position).toInt()).coerceAtMost(step.setup.size)
            AlertDialog(
                onDismissRequest = flow::quizBack,
                properties = DialogProperties(dismissOnClickOutside = false),
                title = { Text(stringResource(R.string.known_quiz_result, "%,d".format(n))) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.known_quiz_result_text))
                        Slider(value = position, onValueChange = { position = it }, valueRange = ln(10f)..ln(step.setup.size.toFloat()))
                    }
                },
                confirmButton = { TextButton(onClick = { flow.quizReview(step, n) }) { Text(stringResource(R.string.known_quiz_continue)) } },
                dismissButton = { TextButton(onClick = flow::cancel) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
        is KnownWordsFlow.Step.Problem -> AlertDialog(
            onDismissRequest = flow::cancel,
            text = { Text(step.message) },
            confirmButton = {
                val offer = step.offer
                if (offer != null) {
                    TextButton(onClick = { withNotifications { flow.download(offer) } }) {
                        Text(stringResource(R.string.known_frequency_download, offer.sizeMb))
                    }
                } else if (step.retryWaniKani) {
                    TextButton(onClick = flow::showWaniKani) { Text(stringResource(R.string.action_retry)) }
                } else if (step.inDictionaries && onOpenDictionaries != null) {
                    TextButton(onClick = onOpenDictionaries) { Text(stringResource(R.string.known_open_dictionaries)) }
                } else {
                    TextButton(onClick = flow::cancel) { Text(stringResource(R.string.action_close)) }
                }
            },
            dismissButton = if (step.offer != null || step.retryWaniKani || (step.inDictionaries && onOpenDictionaries != null)) {
                { TextButton(onClick = flow::cancel) { Text(stringResource(R.string.action_close)) } }
            } else {
                null
            },
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
                                KnownSource.QUIZ -> stringResource(R.string.known_quiz_review, "%,d".format(plan.detail.toIntOrNull() ?: 0), step.dictionary.orEmpty())
                                KnownSource.ANKI_DECK -> stringResource(R.string.known_review_anki, plan.detail)
                                KnownSource.WANIKANI -> stringResource(
                                    if (plan.detail == KnownWords.WANIKANI_KANJI) R.string.known_review_wanikani_kanji else R.string.known_review_wanikani,
                                )
                                else -> stringResource(R.string.known_review_list, plan.detail)
                            },
                        )
                        Text(pluralStringResource(R.plurals.known_review_marks, change.changed, change.changed, change.created, change.upgraded))
                        if (change.unchanged > 0) Text(stringResource(R.string.known_review_already, change.unchanged))
                        if (plan.resolved.names > 0) Text(pluralStringResource(R.plurals.known_review_names, plan.resolved.names, plan.resolved.names))
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
