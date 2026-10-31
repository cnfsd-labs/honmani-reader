package app.honmani.reader.ui.anki

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.honmani.reader.AppContainer
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.anki.DeckWords
import app.honmani.reader.diagnostics.logWarning
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Mark words from an Anki deck as known": pick one of AnkiDroid's decks, see how many saved
 * words it holds and confirm. Used from the Anki settings and from the offer in the Words list;
 * show its dialogs with [KnownFromAnkiDialogs].
 */
class KnownFromAnki internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val container: AppContainer,
    private val access: AnkiAccess,
) {
    sealed interface Step {
        data class Decks(val names: List<String>) : Step

        data class Reading(val deck: String) : Step

        data class Confirm(val found: DeckWords.Found) : Step
    }

    var step by mutableStateOf<Step?>(null)
        private set

    /** Lists AnkiDroid's decks, once AnkiDroid is ready (installed and allowed). */
    fun start() {
        access.whenReady {
            scope.launch {
                val names = withContext(Dispatchers.IO) {
                    try {
                        container.ankiDroid.deckNames()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logWarning("Honmani.anki", "Could not list the AnkiDroid decks", e)
                        emptyList()
                    }
                }
                if (names.isEmpty()) toast(context.getString(R.string.anki_known_no_decks)) else step = Step.Decks(names.sorted())
            }
        }
    }

    fun pick(deck: String) {
        step = Step.Reading(deck)
        scope.launch {
            when (val result = container.ankiSync.deckWords(deck)) {
                is DeckWords.Found -> if (result.matching.isEmpty()) {
                    step = null
                    toast(context.resources.getQuantityString(R.plurals.anki_known_none, result.notes, result.notes, deck))
                } else {
                    step = Step.Confirm(result)
                }
                DeckWords.NotInstalled -> finish(context.getString(R.string.anki_not_installed))
                DeckWords.NoPermission -> finish(context.getString(R.string.anki_no_permission))
                is DeckWords.Failed -> finish(context.getString(R.string.anki_known_failed, result.reason))
            }
        }
    }

    fun confirm(found: DeckWords.Found) {
        step = null
        scope.launch {
            container.ankiSync.markKnown(found.matching)
            toast(context.resources.getQuantityString(R.plurals.anki_known_done, found.matching.size, found.matching.size))
        }
    }

    fun cancel() {
        step = null
    }

    private fun finish(message: String) {
        step = null
        toast(message)
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}

@Composable
fun rememberKnownFromAnki(): KnownFromAnki {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as HonmaniApplication).container }
    val access = rememberAnkiAccess()
    val scope = rememberCoroutineScope()
    return remember(access, scope) { KnownFromAnki(context, scope, container, access) }
}

@Composable
fun KnownFromAnkiDialogs(state: KnownFromAnki) {
    when (val step = state.step) {
        null -> Unit
        is KnownFromAnki.Step.Decks -> AlertDialog(
            onDismissRequest = state::cancel,
            title = { Text(stringResource(R.string.anki_known_pick_deck)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    for (name in step.names) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { state.pick(name) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = state::cancel) { Text(stringResource(R.string.action_cancel)) } },
        )
        is KnownFromAnki.Step.Reading -> AlertDialog(
            onDismissRequest = {},
            title = { Text(step.deck) },
            text = { CircularProgressIndicator() },
            confirmButton = {},
        )
        is KnownFromAnki.Step.Confirm -> {
            val count = step.found.matching.size
            AlertDialog(
                onDismissRequest = state::cancel,
                title = { Text(pluralStringResource(R.plurals.anki_known_title, count, count)) },
                text = { Text(stringResource(R.string.anki_known_text, step.found.deck, step.found.notes)) },
                confirmButton = {
                    TextButton(onClick = { state.confirm(step.found) }) { Text(stringResource(R.string.anki_known_mark)) }
                },
                dismissButton = { TextButton(onClick = state::cancel) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
    }
}
