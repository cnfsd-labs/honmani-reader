package app.honmani.reader.anki

import app.honmani.reader.diagnostics.NetworkLog
import app.honmani.reader.diagnostics.NetworkPurpose
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A Google Cloud Text-to-Speech voice, such as "ja-JP-Chirp3-HD-Aoede". */
data class CloudVoiceInfo(val name: String, val gender: String?) {
    /** The voice's own name without the language and model, such as "Aoede". */
    val shortName: String get() = name.substringAfterLast('-')

    /** The model family, such as "Chirp3-HD" or "Neural2". */
    val model: String get() = name.removePrefix("$LANGUAGE-").substringBeforeLast('-')
}

/**
 * Google Cloud Text-to-Speech with the reader's own API key. Its newest Japanese voices
 * (Chirp 3: HD) sound close to a person. The key goes in a header, never in the address, and
 * only to Google's API.
 */
class GoogleCloudVoice(
    private val apiKey: String,
    private val voice: String,
    private val baseUrl: String = BASE_URL,
) : CardVoice {
    override val extension = "mp3"

    override suspend fun speak(text: String, out: File, rate: Double) {
        val body = buildJsonObject {
            putJsonObject("input") { put("text", text) }
            putJsonObject("voice") {
                put("languageCode", LANGUAGE)
                put("name", voice)
            }
            putJsonObject("audioConfig") {
                put("audioEncoding", "MP3")
                if (rate != 1.0) put("speakingRate", rate)
            }
        }
        val response = request(apiKey, "$baseUrl/v1/text:synthesize", body.toString())
        val audio = response["audioContent"]?.jsonPrimitive?.contentOrNull
            ?: throw VoiceException(VoiceProblem.FAILED)
        val bytes = try {
            Base64.getDecoder().decode(audio)
        } catch (e: IllegalArgumentException) {
            throw VoiceException(VoiceProblem.FAILED, e)
        }
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
    }

    companion object {
        const val BASE_URL = "https://texttospeech.googleapis.com"

        /** Japanese voices for [apiKey], best first; also checks that the key works. */
        suspend fun voices(apiKey: String, baseUrl: String = BASE_URL): List<CloudVoiceInfo> {
            val response = request(apiKey, "$baseUrl/v1/voices?languageCode=$LANGUAGE", null)
            val voices = response["voices"]?.jsonArray.orEmpty().mapNotNull { element ->
                val voice = element.jsonObject
                val name = voice["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                CloudVoiceInfo(name, voice["ssmlGender"]?.jsonPrimitive?.contentOrNull)
            }
            return voices.filter { it.name.startsWith("$LANGUAGE-") }
                .sortedWith(compareBy({ modelRank(it.model) }, { it.name }))
        }

        /** The voice to use when the reader hasn't picked one. */
        fun defaultVoice(voices: List<CloudVoiceInfo>): CloudVoiceInfo? =
            voices.firstOrNull { it.name == PREFERRED_VOICE } ?: voices.firstOrNull()

        /** Newer models sound more natural. */
        private fun modelRank(model: String): Int {
            val i = MODELS.indexOfFirst { model.startsWith(it) }
            return if (i < 0) MODELS.size else i
        }

        private suspend fun request(apiKey: String, url: String, body: String?): JsonObject =
            withContext(Dispatchers.IO) {
                if (apiKey.isBlank()) throw VoiceException(VoiceProblem.NO_KEY)
                val connection = try {
                    URL(url).openConnection() as HttpURLConnection
                } catch (e: IOException) {
                    throw VoiceException(VoiceProblem.NETWORK, e)
                }
                try {
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("X-Goog-Api-Key", apiKey.trim())
                    connection.setRequestProperty("Accept", "application/json")
                    if (body != null) {
                        connection.requestMethod = "POST"
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                    val status = connection.responseCode
                    NetworkLog.shared.answered(NetworkPurpose.GOOGLE_VOICE, url, status, connection.contentLengthLong)
                    if (status !in 200..299) {
                        val error = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                        throw VoiceException(problemFor(status, error))
                    }
                    val text = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    Json.parseToJsonElement(text).jsonObject
                } catch (e: VoiceException) {
                    throw e
                } catch (e: IOException) {
                    NetworkLog.shared.failed(NetworkPurpose.GOOGLE_VOICE, url, e)
                    throw VoiceException(VoiceProblem.NETWORK, e)
                } catch (e: IllegalArgumentException) {
                    // Not JSON, or not an object.
                    throw VoiceException(VoiceProblem.FAILED, e)
                } finally {
                    connection.disconnect()
                }
            }

        /**
         * Google answers a bad key with 400 (reason API_KEY_INVALID), a key without the API
         * enabled or with too narrow restrictions with 403, and too many requests with 429.
         */
        internal fun problemFor(status: Int, error: String): VoiceProblem = when {
            status == 429 || "RESOURCE_EXHAUSTED" in error -> VoiceProblem.QUOTA
            status == 401 || status == 403 || "API_KEY_INVALID" in error || "API key not valid" in error ->
                VoiceProblem.KEY_REJECTED
            else -> VoiceProblem.FAILED
        }

        const val PREFERRED_VOICE = "ja-JP-Chirp3-HD-Aoede"
        private val MODELS = listOf("Chirp3-HD", "Chirp-HD", "Neural2", "Wavenet", "Standard")
        private const val TIMEOUT_MS = 20_000
    }
}

private const val LANGUAGE = "ja-JP"
