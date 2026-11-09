package app.honmani.reader.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import app.honmani.reader.data.dictionary.DictionaryCatalog
import app.honmani.reader.data.fonts.FontCatalog
import app.honmani.reader.ui.HonmaniTopAppBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A licence text in the app's assets. */
enum class LicenceText(@StringRes val title: Int, val asset: String) {
    GPL(R.string.about_licence, "licenses/gpl-3.0.txt"),
    TTU(R.string.about_ttu, "licenses/ttu.txt"),

    /** Written by the build from ttu's bundle (see `BuildTtuTask`). */
    TTU_LIBRARIES(R.string.about_ttu_libraries, "licenses/ttu-libraries.txt"),
    APACHE(R.string.about_android_libraries, "licenses/apache-2.0.txt"),
    ;

    companion object {
        private val BLANK_LINE = Regex("\n[ \t]*\n")

        /** The paragraphs of a plain-text licence, with its hard line breaks joined for a phone screen. */
        fun paragraphs(text: String): List<String> =
            text.replace("\r\n", "\n")
                .split(BLANK_LINE)
                .map { paragraph -> paragraph.lines().joinToString(" ") { it.trim() }.trim() }
                .filter { it.isNotEmpty() }
    }
}

/**
 * About Honmani: copyright, the GPL notice (no warranty, where the licence and the source are),
 * and the credits for what Honmani is built with and downloads. Reached from Settings, which is
 * the "prominent item" GPL-3.0 asks of interactive programs for their legal notices.
 */
@Composable
fun AboutScreen(version: String, onBack: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<LicenceText?>(null) }
    val text = open
    if (text != null) {
        LicenceTextScreen(text, onBack = { open = null })
        return
    }
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    Scaffold(topBar = { HonmaniTopAppBar(title = stringResource(R.string.about_title), onBack = onBack) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    text = stringResource(R.string.settings_version, version) + "\n" + stringResource(R.string.about_copyright),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    text = stringResource(R.string.about_free_software),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                Entry(R.string.about_licence, stringResource(R.string.about_licence_details)) { open = LicenceText.GPL }
            }
            item {
                Entry(R.string.about_source, SOURCE_URL.removePrefix("https://")) { openUrl(context, SOURCE_URL) }
            }
            item {
                Entry(R.string.about_privacy_policy, stringResource(R.string.about_privacy_policy_details)) {
                    openUrl(context, PRIVACY_POLICY_URL)
                }
            }
            item { Heading(R.string.about_built_with) }
            item { Entry(R.string.about_ttu, stringResource(R.string.about_ttu_details)) { open = LicenceText.TTU } }
            item {
                Entry(R.string.about_ttu_libraries, stringResource(R.string.about_ttu_libraries_details)) {
                    open = LicenceText.TTU_LIBRARIES
                }
            }
            item {
                Entry(R.string.about_android_libraries, stringResource(R.string.about_android_libraries_details)) {
                    open = LicenceText.APACHE
                }
            }
            item {
                Entry(R.string.about_radicals, stringResource(R.string.about_radicals_details)) {
                    openUrl(context, EDRDG_LICENCE_URL)
                }
            }
            item {
                Entry(R.string.about_examples, stringResource(R.string.about_examples_details)) {
                    openUrl(context, TATOEBA_LICENCE_URL)
                }
            }
            item { Heading(R.string.about_downloads) }
            item {
                Text(
                    text = stringResource(R.string.about_downloads_details),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(DictionaryCatalog.dictionaries, key = { it.id }) { dictionary ->
                ListItem(
                    headlineContent = { Text(dictionary.name) },
                    supportingContent = { Text(dictionary.license) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_fonts)) },
                    supportingContent = {
                        Text(stringResource(R.string.about_fonts_details, FontCatalog.fonts.joinToString(", ") { it.name }))
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_sounds)) },
                    supportingContent = { Text(stringResource(R.string.about_sounds_details)) },
                )
            }
            item {
                Entry(R.string.about_aozora, stringResource(R.string.about_aozora_details)) {
                    openUrl(context, AOZORA_LICENCE_URL)
                }
            }
            item {
                Entry(R.string.about_aozora_difficulty, stringResource(R.string.about_aozora_difficulty_details)) {
                    openUrl(context, CC_BY_SA_LICENCE_URL)
                }
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_sample_book)) },
                    supportingContent = { Text(stringResource(R.string.about_sample_book_details)) },
                )
            }
        }
    }
}

@Composable
private fun Heading(@StringRes title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
    )
}

@Composable
private fun Entry(@StringRes title: Int, details: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(details) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** A licence text, reflowed into paragraphs. */
@Composable
private fun LicenceTextScreen(text: LicenceText, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val paragraphs by produceState<List<String>?>(null, text) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(text.asset).use { it.readBytes().toString(Charsets.UTF_8) } }
                .map(LicenceText::paragraphs)
                .getOrDefault(emptyList())
        }
    }
    Scaffold(topBar = { HonmaniTopAppBar(title = stringResource(text.title), onBack = onBack) }) { padding ->
        val list = paragraphs
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(list) { paragraph ->
                    Text(
                        text = paragraph,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser; the address is on screen.
    }
}

/** Where Honmani's source is published, as GPL-3.0 asks. */
const val SOURCE_URL = "https://github.com/cnfsd-labs/honmani-reader"

/** The published privacy policy (`docs/privacy-policy.md`, served by GitHub Pages); the Play listing links the same page. */
const val PRIVACY_POLICY_URL = "https://cnfsd-labs.github.io/honmani-reader/privacy-policy.html"

private const val TATOEBA_LICENCE_URL = "https://creativecommons.org/licenses/by/2.0/fr/"
private const val CC_BY_SA_LICENCE_URL = "https://creativecommons.org/licenses/by-sa/4.0/"
private const val AOZORA_LICENCE_URL = "https://creativecommons.org/licenses/by/4.0/"
private const val EDRDG_LICENCE_URL = "https://www.edrdg.org/edrdg/licence.html"
