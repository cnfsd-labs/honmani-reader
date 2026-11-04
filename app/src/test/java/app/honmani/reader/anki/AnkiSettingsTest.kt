package app.honmani.reader.anki

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.opds.CredentialCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnkiSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val choices = context.getSharedPreferences("anki-choices", Context.MODE_PRIVATE)
    private val device = context.getSharedPreferences("anki-device", Context.MODE_PRIVATE)
    private val plainCipher = object : CredentialCipher {
        override fun encrypt(plain: String) = plain

        override fun decrypt(encrypted: String) = encrypted
    }

    @Test
    fun theKeyAndAnkiDroidsIdsStayOnThisPhone_theChoicesAreBackedUp() {
        val settings = AnkiSettings(choices, plainCipher, device)

        settings.setDeck("Japanese")
        settings.setAudio(CardAudio.GOOGLE)
        settings.setCardStyle(CardStyle.SENTENCE)
        settings.setNoteTypeId(CardStyle.SENTENCE, 8)
        settings.setGoogleKey("key")
        settings.setNoteTypeId(CardStyle.HONMANI, 7)
        settings.checkedNoteTypeId = 7

        assertEquals(setOf("anki_deck", "anki_audio", "anki_card_style"), choices.all.keys)
        assertEquals(4, device.all.size)
    }

    @Test
    fun sentenceEnds_keepTheDefaultUntilChanged_andGoBackToItWhenBlank() {
        val settings = AnkiSettings(choices, plainCipher, device)
        assertEquals("。！？!?…", settings.sentenceEnds.value)

        settings.setSentenceEnds("。 。♪")
        assertEquals("。♪", settings.sentenceEnds.value)
        assertEquals("。♪", AnkiSettings(choices, plainCipher, device).sentenceEnds.value)

        settings.setSentenceEnds("  ")
        assertEquals("。！？!?…", settings.sentenceEnds.value)
    }

    @Test
    fun aRestoredBackupHasTheChoicesButNoKey() {
        AnkiSettings(choices, plainCipher, device).apply {
            setDeck("Japanese")
            setGoogleKey("key")
        }
        val newPhone = context.getSharedPreferences("anki-new-phone", Context.MODE_PRIVATE)

        val restored = AnkiSettings(choices.copy(), plainCipher, newPhone)

        assertEquals("Japanese", restored.deck.value)
        assertFalse(restored.hasGoogleKey.value)
        assertTrue(AnkiSettings(choices, plainCipher, device).hasGoogleKey.value)
    }

    private fun SharedPreferences.copy(): SharedPreferences =
        context.getSharedPreferences("anki-restored", Context.MODE_PRIVATE).also { copy ->
            val editor = copy.edit()
            all.forEach { (key, value) -> if (value is String) editor.putString(key, value) }
            editor.commit()
        }
}
