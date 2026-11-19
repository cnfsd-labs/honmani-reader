package app.honmani.reader.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.R
import app.honmani.reader.anki.AnkiSettings
import app.honmani.reader.anki.CloudVoiceInfo
import app.honmani.reader.anki.VoiceException
import app.honmani.reader.anki.VoiceProblem
import app.honmani.reader.data.opds.CredentialCipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GoogleKeyCheckTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = context.getSharedPreferences("key-check-device", Context.MODE_PRIVATE)
    private val settings = AnkiSettings(
        context.getSharedPreferences("key-check-choices", Context.MODE_PRIVATE),
        object : CredentialCipher {
            override fun encrypt(plain: String) = "enc:$plain"

            override fun decrypt(encrypted: String) = encrypted.removePrefix("enc:")
        },
        device,
    )

    @Test
    fun removingTheKey_emptiesTheStore_andClearsTheCheck() = runTest {
        val check = GoogleKeyCheck(settings, this) { throw VoiceException(VoiceProblem.KEY_REJECTED) }
        check.save("secret")
        advanceUntilIdle()
        assertTrue(check.state is KeyCheck.Failed)

        check.remove()

        assertNull(check.state)
        assertFalse(settings.hasGoogleKey.value)
        assertNull(settings.googleKey())
        assertTrue(device.all.isEmpty())
    }

    @Test
    fun removingTheKeyWhileItIsChecked_dropsTheLateResult() = runTest {
        val answer = CompletableDeferred<List<CloudVoiceInfo>>()
        val check = GoogleKeyCheck(settings, this) { answer.await() }
        check.save("secret")
        advanceUntilIdle()
        assertEquals(KeyCheck.Checking, check.state)

        check.remove()
        answer.complete(emptyList())
        advanceUntilIdle()

        assertNull(check.state)
    }

    @Test
    fun aFailureNotCausedByTheKey_canBeCheckedAgain() = runTest {
        var calls = 0
        val check = GoogleKeyCheck(settings, this) {
            calls++
            if (calls == 1) throw VoiceException(VoiceProblem.NETWORK) else emptyList()
        }
        check.save("secret")
        advanceUntilIdle()
        assertEquals(KeyCheck.Failed(R.string.voice_problem_network, canRetry = true), check.state)

        check.check()
        advanceUntilIdle()

        assertEquals(2, calls)
        assertEquals(KeyCheck.Works(emptyList()), check.state)
    }

    @Test
    fun quotaAndServerFailuresOfferARetry_aRejectedKeyDoesNot() = runTest {
        for ((problem, retry) in listOf(
            VoiceProblem.QUOTA to true,
            VoiceProblem.FAILED to true,
            VoiceProblem.KEY_REJECTED to false,
        )) {
            val check = GoogleKeyCheck(settings, this) { throw VoiceException(problem) }
            check.save("secret")
            advanceUntilIdle()
            assertEquals(problem.name, retry, (check.state as KeyCheck.Failed).canRetry)
        }
    }
}
