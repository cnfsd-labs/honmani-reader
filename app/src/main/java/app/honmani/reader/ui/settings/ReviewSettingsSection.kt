package app.honmani.reader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.data.review.ReviewLimit
import app.honmani.reader.data.settings.ReviewAutoPlay
import app.honmani.reader.data.settings.ReviewAutoPlayContent
import app.honmani.reader.data.settings.ReviewSettings

/** Settings → Flashcards: new words and reviews a day, the front of a card, and when a word counts as known. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewSettingsSection() {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as HonmaniApplication).container.reviewSettings }
    val newPerDay by settings.newPerDay.collectAsStateWithLifecycle()
    val reviewsPerDay by settings.reviewsPerDay.collectAsStateWithLifecycle()
    val showSentence by settings.showSentence.collectAsStateWithLifecycle()
    val speakers by settings.speakers.collectAsStateWithLifecycle()
    val autoPlay by settings.autoPlay.collectAsStateWithLifecycle()
    val autoPlayContent by settings.autoPlayContent.collectAsStateWithLifecycle()
    val knownAfter by settings.knownAfterDays.collectAsStateWithLifecycle()
    ListItem(
        headlineContent = { Text(stringResource(R.string.review_settings_new_per_day)) },
        supportingContent = { Text(stringResource(R.string.review_settings_new_per_day_details)) },
    )
    Chips {
        // A value restored from a backup that isn't a preset is shown as one more chip.
        for (choice in (ReviewSettings.NEW_PER_DAY_CHOICES + newPerDay).distinct().sorted()) {
            FilterChip(
                selected = choice == newPerDay,
                onClick = { settings.setNewPerDay(choice) },
                label = { Text(choice.toString()) },
            )
        }
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.review_settings_reviews_per_day)) },
        supportingContent = { Text(stringResource(R.string.review_settings_reviews_per_day_details)) },
    )
    Chips {
        // A value restored from a backup that isn't a preset is shown as one more chip.
        val choices = (ReviewLimit.CHOICES + reviewsPerDay).distinct()
        for (choice in choices.filter { it != ReviewLimit.NO_LIMIT }.sorted() + choices.filter { it == ReviewLimit.NO_LIMIT }) {
            FilterChip(
                selected = choice == reviewsPerDay,
                onClick = { settings.setReviewsPerDay(choice) },
                label = {
                    Text(
                        if (choice == ReviewLimit.NO_LIMIT) stringResource(R.string.review_settings_no_limit) else choice.toString(),
                    )
                },
            )
        }
    }
    ListItem(headlineContent = { Text(stringResource(R.string.review_settings_front)) })
    Chips {
        FilterChip(
            selected = showSentence,
            onClick = { settings.setShowSentence(true) },
            label = { Text(stringResource(R.string.review_settings_front_sentence)) },
        )
        FilterChip(
            selected = !showSentence,
            onClick = { settings.setShowSentence(false) },
            label = { Text(stringResource(R.string.review_settings_front_word)) },
        )
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.review_settings_speakers)) },
        supportingContent = { Text(stringResource(R.string.review_settings_speakers_details)) },
        trailingContent = { Switch(checked = speakers, onCheckedChange = null) },
        modifier = Modifier.toggleable(speakers, role = Role.Switch, onValueChange = settings::setSpeakers),
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.review_settings_auto_play)) },
        supportingContent = { Text(stringResource(R.string.review_settings_auto_play_details)) },
    )
    Chips {
        val modes = listOf(
            ReviewAutoPlay.OFF to R.string.review_settings_auto_play_off,
            ReviewAutoPlay.ON_REVEAL to R.string.review_settings_auto_play_reveal,
            ReviewAutoPlay.ON_SHOW to R.string.review_settings_auto_play_show,
        )
        for ((mode, label) in modes) {
            FilterChip(
                selected = mode == autoPlay,
                onClick = { settings.setAutoPlay(mode) },
                enabled = speakers,
                label = { Text(stringResource(label)) },
            )
        }
    }
    if (autoPlay != ReviewAutoPlay.OFF) {
        ListItem(headlineContent = { Text(stringResource(R.string.review_settings_auto_play_content)) })
        Chips {
            FilterChip(
                selected = autoPlayContent == ReviewAutoPlayContent.WORD,
                onClick = { settings.setAutoPlayContent(ReviewAutoPlayContent.WORD) },
                enabled = speakers,
                label = { Text(stringResource(R.string.review_settings_auto_play_word)) },
            )
            FilterChip(
                selected = autoPlayContent == ReviewAutoPlayContent.SENTENCE,
                onClick = { settings.setAutoPlayContent(ReviewAutoPlayContent.SENTENCE) },
                enabled = speakers,
                label = { Text(stringResource(R.string.review_settings_auto_play_sentence)) },
            )
        }
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.review_settings_known_after)) },
        supportingContent = { Text(stringResource(R.string.review_settings_known_after_details)) },
    )
    Chips {
        for (choice in ReviewSettings.KNOWN_AFTER_CHOICES) {
            FilterChip(
                selected = choice == knownAfter,
                onClick = { settings.setKnownAfterDays(choice) },
                label = { Text(stringResource(R.string.review_settings_days, choice)) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}
