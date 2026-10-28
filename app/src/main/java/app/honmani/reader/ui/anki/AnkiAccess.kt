package app.honmani.reader.ui.anki

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.anki.AnkiDroid
import app.honmani.reader.anki.AnkiResult
import app.honmani.reader.anki.VoiceProblem

/** Runs an action that needs AnkiDroid once it is installed and Honmani may add cards. */
class AnkiAccess internal constructor(
    private val context: Context,
    private val anki: AnkiDroid,
    private val requestPermission: (onGranted: () -> Unit) -> Unit,
) {
    /**
     * Runs [action] right away if AnkiDroid is ready; asks for the permission first if needed,
     * or explains that AnkiDroid is missing and opens its store page.
     */
    fun whenReady(action: () -> Unit) {
        when {
            anki.packageName() == null -> {
                toast(context, context.getString(R.string.anki_not_installed))
                openStore(context)
            }
            anki.hasPermission() -> action()
            else -> requestPermission(action)
        }
    }
}

@Composable
fun rememberAnkiAccess(): AnkiAccess {
    val context = LocalContext.current
    val resources = LocalResources.current
    val anki = remember { (context.applicationContext as HonmaniApplication).container.ankiDroid }
    val pending = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pending[0]
        pending[0] = null
        if (granted) action?.invoke() else toast(context, resources.getString(R.string.anki_no_permission))
    }
    return remember(launcher) {
        AnkiAccess(context, anki) { onGranted ->
            pending[0] = onGranted
            launcher.launch(AnkiDroid.PERMISSION)
        }
    }
}

/** Tells the reader how adding [expression] went. */
fun showAnkiResult(context: Context, expression: String, result: AnkiResult) {
    val message = when (result) {
        is AnkiResult.Added -> result.audioProblem?.let {
            context.getString(R.string.anki_added_without_audio, expression, context.getString(it.label()))
        } ?: context.getString(R.string.anki_added, expression)
        AnkiResult.AlreadyInAnki -> context.getString(R.string.anki_already, expression)
        AnkiResult.NotInstalled -> context.getString(R.string.anki_not_installed)
        AnkiResult.NoPermission -> context.getString(R.string.anki_no_permission)
        AnkiResult.Refused -> context.getString(R.string.anki_refused)
        is AnkiResult.Failed -> context.getString(R.string.anki_failed, result.reason)
    }
    toast(context, message)
}

/** Tells why the lookup panel's speaker couldn't say a word. */
fun showVoiceProblem(context: Context, problem: VoiceProblem) {
    toast(context, context.getString(R.string.listen_failed, context.getString(problem.label())))
}

fun VoiceProblem.label(): Int = when (this) {
    VoiceProblem.NO_ENGINE -> R.string.voice_problem_no_engine
    VoiceProblem.NO_JAPANESE -> R.string.voice_problem_no_japanese
    VoiceProblem.FAILED -> R.string.voice_problem_failed
    VoiceProblem.NO_KEY -> R.string.voice_problem_no_key
    VoiceProblem.KEY_REJECTED -> R.string.voice_problem_key_rejected
    VoiceProblem.QUOTA -> R.string.voice_problem_quota
    VoiceProblem.NETWORK -> R.string.voice_problem_network
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, if (message.length > 60) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

private fun openStore(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AnkiDroid.PLAY_STORE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No browser or store; the message already says what to install.
    }
}
