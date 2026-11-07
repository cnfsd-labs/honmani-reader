package app.honmani.reader.readaloud.generate

import app.honmani.reader.anki.GoogleCloudVoice
import app.honmani.reader.diagnostics.NetworkPurpose

/**
 * Turns one sentence into MP3 audio for a generated audiobook. A failure is a
 * [app.honmani.reader.anki.VoiceException] (its status and Retry-After tell [AudiobookMaker] what to do).
 * Every call that is made counts as billed, whatever comes of it.
 */
interface SentenceVoice {
    /** The MP3 of [text] at [sampleRateHertz]. */
    suspend fun synthesize(text: String, sampleRateHertz: Int): ByteArray
}

/** Google Cloud Text-to-Speech with the reader's own key: one request per sentence, MP3. */
class GoogleSentenceVoice(
    apiKey: String,
    voice: String,
    baseUrl: String = GoogleCloudVoice.BASE_URL,
) : SentenceVoice {
    private val client = GoogleCloudVoice(apiKey, voice, baseUrl)

    override suspend fun synthesize(text: String, sampleRateHertz: Int): ByteArray =
        client.synthesize(text, sampleRateHertz = sampleRateHertz, purpose = NetworkPurpose.GOOGLE_AUDIOBOOK)
}
