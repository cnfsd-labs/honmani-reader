package app.honmani.reader.ui.anki

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.res.stringResource
import app.honmani.reader.R
import app.honmani.reader.anki.AnkiCheck
import app.honmani.reader.anki.AnkiResult
import app.honmani.reader.anki.CustomProblem

/** The reader's own ("My own") note type [name] can't take cards: [problem] says why. */
data class AnkiProblemNotice(val name: String, val problem: CustomProblem) {
    val message: Int
        get() = when (problem) {
            CustomProblem.NOT_CHOSEN -> R.string.anki_custom_not_chosen
            CustomProblem.MISSING -> R.string.anki_custom_missing
            CustomProblem.NO_WORD_FIELD -> R.string.anki_custom_no_word
        }
}

/** The dialog for a result that the card settings fix, or null. */
fun AnkiResult.problemNotice(): AnkiProblemNotice? = (this as? AnkiResult.CustomUnusable)?.let { AnkiProblemNotice(it.name, it.problem) }

/** The dialog for a check that the card settings fix, or null. */
fun AnkiCheck.problemNotice(): AnkiProblemNotice? = (this as? AnkiCheck.CustomUnusable)?.let { AnkiProblemNotice(it.name, it.problem) }

/** An activity that shows [AnkiProblemDialog] for [ankiProblem]; elsewhere the problem is a toast. */
interface AnkiProblemHost {
    val ankiProblem: MutableState<AnkiProblemNotice?>
}

/** Hands [notice] to the activity's dialog; false if [context] has none. */
internal fun showInDialog(context: Context, notice: AnkiProblemNotice): Boolean {
    val host = context.activity() as? AnkiProblemHost ?: return false
    host.ankiProblem.value = notice
    return true
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Says why "My own" note type can't take cards, with the way to its settings. */
@Composable
fun AnkiProblemDialog(host: AnkiProblemHost, onOpenCardSettings: () -> Unit) {
    val notice = host.ankiProblem.value ?: return
    AlertDialog(
        onDismissRequest = { host.ankiProblem.value = null },
        title = { Text(stringResource(R.string.anki_custom_problem_title)) },
        text = { Text(stringResource(notice.message, notice.name)) },
        confirmButton = {
            TextButton(onClick = {
                host.ankiProblem.value = null
                onOpenCardSettings()
            }) { Text(stringResource(R.string.anki_open_card_settings)) }
        },
        dismissButton = {
            TextButton(onClick = { host.ankiProblem.value = null }) { Text(stringResource(R.string.action_close)) }
        },
    )
}
