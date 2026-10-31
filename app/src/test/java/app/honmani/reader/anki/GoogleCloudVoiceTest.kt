package app.honmani.reader.anki

import app.honmani.reader.data.opds.HeaderServer
import app.honmani.reader.data.opds.respond
import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GoogleCloudVoiceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var status = "200 OK"
    private var body = ""
    private val server = HeaderServer { _, out -> out.respond(status, body, "Content-Type: application/json") }
    private val baseUrl = "http://127.0.0.1:${server.port}"

    @After
    fun tearDown() = server.stop()

    @Test
    fun speakSendsTheTextAndKey_andWritesTheAudio() = runBlocking {
        val audio = byteArrayOf(1, 2, 3, 4)
        body = """{"audioContent":"${Base64.getEncoder().encodeToString(audio)}"}"""
        val out = File(folder.root, "a/word.mp3")

        GoogleCloudVoice(" secret-key ", "ja-JP-Chirp3-HD-Aoede", baseUrl).speak("なまえ", out)

        assertArrayEquals(audio, out.readBytes())
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/text:synthesize", request.path)
        assertEquals("secret-key", request.headers["x-goog-api-key"])
        // The key never goes into the address.
        assertFalse("secret" in request.path)
        val json = Json.parseToJsonElement(request.body).jsonObject
        assertEquals("なまえ", json["input"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("ja-JP-Chirp3-HD-Aoede", json["voice"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("MP3", json["audioConfig"]!!.jsonObject["audioEncoding"]!!.jsonPrimitive.content)
        // Normal speed is Google's default; nothing is sent for it.
        assertFalse("speakingRate" in json["audioConfig"]!!.jsonObject)
    }

    @Test
    fun audioThatIsNotBase64IsAFailure() = runBlocking {
        body = """{"audioContent":"not base64 !"}"""
        try {
            GoogleCloudVoice("key", "ja-JP-Neural2-B", baseUrl).speak("ねこ", File(folder.root, "n.mp3"))
            fail("Expected a VoiceException")
        } catch (e: VoiceException) {
            assertEquals(VoiceProblem.FAILED, e.problem)
        }
    }

    @Test
    fun aSlowerRateIsSentAsSpeakingRate() = runBlocking {
        body = """{"audioContent":"${Base64.getEncoder().encodeToString(byteArrayOf(1))}"}"""

        GoogleCloudVoice("key", "ja-JP-Neural2-B", baseUrl).speak("名前はまだ無い。", File(folder.root, "s.mp3"), rate = 0.7)

        val json = Json.parseToJsonElement(server.requests.single().body).jsonObject
        assertEquals(0.7, json["audioConfig"]!!.jsonObject["speakingRate"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun voicesListsJapaneseVoices_newestModelsFirst() = runBlocking {
        body = """{"voices":[
            {"name":"ja-JP-Standard-A","ssmlGender":"FEMALE"},
            {"name":"ja-JP-Neural2-B","ssmlGender":"FEMALE"},
            {"name":"ja-JP-Chirp3-HD-Charon","ssmlGender":"MALE"},
            {"name":"ja-JP-Chirp3-HD-Aoede","ssmlGender":"FEMALE"},
            {"name":"en-US-Neural2-A","ssmlGender":"MALE"}
        ]}"""

        val voices = GoogleCloudVoice.voices("key", baseUrl)

        assertEquals(
            listOf("ja-JP-Chirp3-HD-Aoede", "ja-JP-Chirp3-HD-Charon", "ja-JP-Neural2-B", "ja-JP-Standard-A"),
            voices.map { it.name },
        )
        assertEquals("Aoede", voices[0].shortName)
        assertEquals("Chirp3-HD", voices[0].model)
        assertEquals("ja-JP-Chirp3-HD-Aoede", GoogleCloudVoice.defaultVoice(voices)?.name)
        assertEquals("/v1/voices?languageCode=ja-JP", server.requests.single().path)
    }

    @Test
    fun aRejectedKeyIsReportedAsSuch() = runBlocking {
        status = "400 Bad Request"
        body = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.",""" +
            """"status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}"""
        try {
            GoogleCloudVoice.voices("wrong", baseUrl)
            fail("expected an exception")
        } catch (e: VoiceException) {
            assertEquals(VoiceProblem.KEY_REJECTED, e.problem)
        }
    }

    @Test
    fun aReplyOfAnotherShapeIsAFailureNotACrash() = runBlocking {
        body = """{"voices":"none"}"""
        try {
            GoogleCloudVoice.voices("key", baseUrl)
            fail("expected an exception")
        } catch (e: VoiceException) {
            assertEquals(VoiceProblem.FAILED, e.problem)
        }
    }

    @Test
    fun problemsByStatus() {
        assertEquals(VoiceProblem.KEY_REJECTED, GoogleCloudVoice.problemFor(403, "SERVICE_DISABLED"))
        assertEquals(VoiceProblem.QUOTA, GoogleCloudVoice.problemFor(429, ""))
        assertEquals(VoiceProblem.FAILED, GoogleCloudVoice.problemFor(400, "Voice does not exist"))
        assertEquals(VoiceProblem.FAILED, GoogleCloudVoice.problemFor(500, ""))
    }

    @Test
    fun noKeyFailsWithoutARequest() = runBlocking {
        try {
            GoogleCloudVoice("", "v", baseUrl).speak("あ", File(folder.root, "x.mp3"))
            fail("expected an exception")
        } catch (e: VoiceException) {
            assertEquals(VoiceProblem.NO_KEY, e.problem)
        }
        assertEquals(0, server.requests.size)
    }
}
