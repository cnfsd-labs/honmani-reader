package app.honmani.reader.data.backup

import app.honmani.reader.anki.AnkiCard
import app.honmani.reader.anki.AnkiPackage
import app.honmani.reader.anki.SentenceCard
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The names of the `settings` preferences a backup brings back, with their types, written out.
 * Android's cloud backup and every backup file already made carry these names: renaming one (or
 * changing its type) silently drops that choice for everyone who restores. A failing test here is
 * a reminder to keep the old name, or to read the old one in [Backup] before the new one.
 */
class BackupSettingKeysTest {
    @Test
    fun backedUpSettingNamesAndTypesStayAsPublished() {
        assertEquals(
            mapOf(
                "accent" to "string",
                "accent_random" to "string",
                "accent_custom_hue" to "float",
                "show_difficulty" to "boolean",
                "library_sort" to "string",
                "show_previous_sentence" to "boolean",
                "show_word_frequency" to "boolean",
                "show_examples" to "boolean",
                "show_pitch_accent" to "boolean",
                "lookup_text_percent" to "int",
                "ui_sounds" to "boolean",
                "ui_animations" to "boolean",
                "highlight_extent" to "string",
                "read_aloud_rate" to "float",
                "review_new_per_day" to "int",
                "review_reviews_per_day" to "int",
                "review_show_sentence" to "boolean",
                "review_speakers" to "boolean",
                "review_auto_play" to "string",
                "review_auto_play_content" to "string",
                "review_known_after_days" to "int",
                "day_start_hour" to "int",
                "daily_goal_enabled" to "boolean",
                "daily_goal_minutes" to "int",
                "count_time_during_lookup" to "boolean",
                "anki_enabled" to "boolean",
                "anki_audio" to "string",
                "anki_sentence_speed" to "string",
                "anki_audiobook_sentences" to "boolean",
                "anki_google_voice" to "string",
                "anki_deck" to "string",
                "anki_card_style" to "string",
                "anki_custom_note_type_name" to "string",
                "anki_field_mappings" to "string",
                "anki_sentence_ends" to "string",
            ),
            Backup.SETTING_TYPES,
        )
    }

    /** Names a restore or an Anki import finds again by value: changing one splits users in two. */
    @Test
    fun permanentNamesAndIdsStayAsPublished() {
        assertEquals("honmani-backup.zip", AutoBackup.FILE_NAME)
        assertEquals(1_726_000_000_001L, AnkiPackage.NOTE_TYPE_ID)
        assertEquals(1_726_000_000_001L, AnkiCard.MODEL.id)
        assertEquals(1_726_000_000_002L, SentenceCard.MODEL.id)
    }
}
