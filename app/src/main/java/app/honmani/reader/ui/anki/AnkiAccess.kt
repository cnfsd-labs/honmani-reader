package app.honmani.reader.ui.anki

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import app.honmani.reader.AppContainer
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.anki.AnkiCheck
import app.honmani.reader.anki.AnkiDroid
import app.honmani.reader.anki.AnkiResult
import app.honmani.reader.anki.AnkiSettings
import app.honmani.reader.anki.VoiceProblem
import app.honmani.reader.data.dictionary.FailureReason

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
    // Saved, like the launcher's result, across a recreation while the dialog is open.
    var rationaleBefore by rememberSaveable { mutableStateOf(false) }
    val activity = LocalActivity.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pending[0]
        pending[0] = null
        val devicePreferences = context.getSharedPreferences(AppContainer.DEVICE_SETTINGS, Context.MODE_PRIVATE)
        val rationaleAfter = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, AnkiDroid.PERMISSION)
        val refusedBefore = devicePreferences.getBoolean(KEY_REFUSED_WITHOUT_RATIONALE, false)
        if (!granted && activity != null) {
            devicePreferences.edit().putBoolean(KEY_REFUSED_WITHOUT_RATIONALE, !rationaleAfter).apply()
        }
        when {
            // The screen was recreated while the dialog was open: the tapped action is gone.
            granted -> action?.invoke() ?: toast(context, resources.getString(R.string.anki_permission_granted_tap_again))
            activity != null && deniedForGood(rationaleBefore, rationaleAfter, refusedBefore) -> {
                toast(context, resources.getString(R.string.anki_no_permission_settings))
                openAppSettings(context)
            }
            else -> toast(context, resources.getString(R.string.anki_no_permission))
        }
    }
    return remember(launcher) {
        AnkiAccess(context, anki) { onGranted ->
            pending[0] = onGranted
            rationaleBefore = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, AnkiDroid.PERMISSION)
            launcher.launch(AnkiDroid.PERMISSION)
        }
    }
}

/** Device preference: the last refusal left Android without a rationale to show. */
private const val KEY_REFUSED_WITHOUT_RATIONALE = "anki_permission_refused_without_rationale"

/**
 * Whether a refused permission request means Android shows no dialog any more ("Don't ask again",
 * or a second Deny on Android 11+), so only the app's settings can allow it. Android gives no
 * rationale both then and when the dialog was only dismissed (Back, outside tap) before any Deny:
 * - a rationale now: the reader denied once, the dialog still comes next time;
 * - a rationale before but none now: this refusal was the final one;
 * - none before or now: final only if the previous refusal also ended without one (a first
 *   dismissal doesn't send the reader to the settings; a refusal with no dialog shown does).
 */
internal fun deniedForGood(rationaleBefore: Boolean, rationaleAfter: Boolean, refusedWithoutRationaleBefore: Boolean): Boolean =
    !rationaleAfter && (rationaleBefore || refusedWithoutRationaleBefore)

/**
 * Whether Add-to-Anki shows: the reader's choice in the settings, or until they chose, whether
 * AnkiDroid is installed (looked up again when the screen resumes).
 */
@Composable
fun rememberAnkiEnabled(): Boolean {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as HonmaniApplication).container }
    val choice by container.ankiSettings.enabledChoice.collectAsStateWithLifecycle()
    var installed by remember { mutableStateOf(container.ankiDroid.packageName() != null) }
    LifecycleResumeEffect(Unit) {
        installed = container.ankiDroid.packageName() != null
        onPauseOrDispose {}
    }
    return AnkiSettings.isEnabled(choice, installed)
}

/** Tells the reader how adding [expression] went. */
fun showAnkiResult(context: Context, expression: String, result: AnkiResult) {
    result.problemNotice()?.let { notice -> if (showInDialog(context, notice)) return }
    val message = when (result) {
        is AnkiResult.Added -> result.audioProblem?.let {
            context.getString(R.string.anki_added_without_audio, expression, context.getString(it.label()))
        } ?: context.getString(R.string.anki_added, expression)
        AnkiResult.AlreadyInAnki -> context.getString(R.string.anki_already, expression)
        AnkiResult.NotInstalled -> context.getString(R.string.anki_not_installed)
        AnkiResult.NoPermission -> context.getString(R.string.anki_no_permission)
        AnkiResult.Refused -> context.getString(R.string.anki_refused)
        is AnkiResult.CustomUnusable -> context.getString(AnkiProblemNotice(result.name, result.problem).message, result.name)
        is AnkiResult.Failed -> context.getString(
            if (result.reason == FailureReason.NO_SPACE) R.string.anki_failed_no_space else R.string.anki_failed,
        )
    }
    toast(context, message)
}

/** Tells what "Check against Anki" changed. */
fun showAnkiCheck(context: Context, result: AnkiCheck) {
    result.problemNotice()?.let { notice -> if (showInDialog(context, notice)) return }
    val resources = context.resources
    val message = when (result) {
        is AnkiCheck.Done -> when {
            result.cleared == 0 && result.marked == 0 -> resources.getString(R.string.anki_check_nothing)
            else -> listOfNotNull(
                result.cleared.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.anki_check_cleared, it, it) },
                result.marked.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.anki_check_marked, it, it) },
            ).joinToString(" – ")
        }
        AnkiCheck.NotInstalled -> context.getString(R.string.anki_not_installed)
        AnkiCheck.NoPermission -> context.getString(R.string.anki_no_permission)
        is AnkiCheck.CustomUnusable -> context.getString(AnkiProblemNotice(result.name, result.problem).message, result.name)
        is AnkiCheck.Failed -> context.getString(R.string.anki_check_failed)
    }
    toast(context, message)
}

/** Tells why the lookup panel's speaker couldn't say a word. */
fun showVoiceProblem(context: Context, problem: VoiceProblem) {
    toast(context, context.getString(R.string.listen_failed, context.getString(problem.label())))
}

/** Confirms that "Mark as learned" moved [word] to Known. */
fun VoiceProblem.label(): Int = when (this) {
    VoiceProblem.NO_ENGINE -> R.string.voice_problem_no_engine
    VoiceProblem.NO_JAPANESE -> R.string.voice_problem_no_japanese
    VoiceProblem.FAILED -> R.string.voice_problem_failed
    VoiceProblem.NO_KEY -> R.string.voice_problem_no_key
    VoiceProblem.KEY_REJECTED -> R.string.voice_problem_key_rejected
    VoiceProblem.QUOTA -> R.string.voice_problem_quota
    VoiceProblem.NETWORK -> R.string.voice_problem_network
    VoiceProblem.BILLING_DISABLED -> R.string.voice_problem_billing_disabled
    VoiceProblem.SERVICE_DISABLED -> R.string.voice_problem_service_disabled
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, if (message.length > 60) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // A trimmed system without that page; the message says where to allow it.
    }
}

private fun openStore(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AnkiDroid.PLAY_STORE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No browser or store; the message already says what to install.
    }
}
