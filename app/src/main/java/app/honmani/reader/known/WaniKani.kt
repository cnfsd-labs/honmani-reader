package app.honmani.reader.known

import app.honmani.reader.data.net.originOf
import app.honmani.reader.diagnostics.NetworkLog
import app.honmani.reader.diagnostics.NetworkPurpose
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Why reading WaniKani failed; each has a readable sentence in the app. */
enum class WaniKaniProblem { NO_TOKEN, BAD_TOKEN, OFFLINE, RATE_LIMITED, FAILED }

/** The message is the problem's name only: never the token, an address or anything the server said. */
class WaniKaniException(val problem: WaniKaniProblem, cause: Throwable? = null) : Exception(problem.name, cause)

/** A WaniKani item the reader has learned: a [vocabulary] word with its [reading], or a kanji (no reading). */
data class WaniKaniItem(val characters: String, val reading: String?, val vocabulary: Boolean)

/**
 * Reads the items a reader has at Guru (SRS stage 5) or above from the WaniKani API v2 with their
 * own read-only token. The token goes in the Authorization header to [baseUrl] only (a `next_url`
 * of another origin is refused), is used for this one run and kept nowhere.
 */
class WaniKaniClient(
    private val baseUrl: String = BASE_URL,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    /** The vocabulary (and, if [includeKanji], kanji) at stage [MIN_STAGE] or above, in the API's order. */
    suspend fun knownItems(token: String, includeKanji: Boolean, onProgress: (Int) -> Unit = {}): List<WaniKaniItem> {
        val key = token.trim()
        if (key.isEmpty()) throw WaniKaniException(WaniKaniProblem.NO_TOKEN)
        // A token is a UUID; anything else (a line break would split the header) is not one.
        if (!TOKEN.matches(key)) throw WaniKaniException(WaniKaniProblem.BAD_TOKEN)
        val session = Session(key)
        val types = if (includeKanji) "vocabulary,kanji" else "vocabulary"
        val assignments = ArrayList<Assignment>()
        var url: String? = "$baseUrl/v2/assignments?subject_types=$types&srs_stages=${(MIN_STAGE..MAX_STAGE).joinToString(",")}"
        var pages = 0
        val seen = HashSet<String>()
        while (url != null) {
            if (++pages > MAX_PAGES || !seen.add(url)) throw WaniKaniException(WaniKaniProblem.FAILED)
            val page = session.get(url)
            assignments += parseAssignments(page)
            url = nextUrl(page)
            onProgress(assignments.size)
        }
        val wanted = assignments.filter { it.stage >= MIN_STAGE && (it.type == VOCABULARY || includeKanji && it.type == KANJI) }
        val subjects = HashMap<Long, Subject>()
        for (chunk in wanted.map { it.subjectId }.distinct().chunked(SUBJECTS_PER_REQUEST)) {
            var next: String? = "$baseUrl/v2/subjects?ids=${chunk.joinToString(",")}"
            var subjectPages = 0
            while (next != null) {
                if (++subjectPages > MAX_PAGES || !seen.add(next)) throw WaniKaniException(WaniKaniProblem.FAILED)
                val page = session.get(next)
                for (subject in parseSubjects(page)) subjects[subject.id] = subject
                next = nextUrl(page)
            }
            onProgress(subjects.size)
        }
        val items = LinkedHashMap<String, WaniKaniItem>()
        for (assignment in wanted) {
            val subject = subjects[assignment.subjectId] ?: continue
            val vocabulary = assignment.type == VOCABULARY
            val item = WaniKaniItem(subject.characters, if (vocabulary) subject.reading else null, vocabulary)
            items.putIfAbsent("${item.characters}\u0000${item.reading}", item)
        }
        return items.values.toList()
    }

    /** The next page's address; refuses another origin than [baseUrl]'s, which would get the token. */
    private fun nextUrl(page: JsonObject): String? {
        val next = ((page["pages"] as? JsonObject)?.get("next_url") as? JsonPrimitive)?.contentOrNull ?: return null
        if (originOf(next) != originOf(baseUrl)) throw WaniKaniException(WaniKaniProblem.FAILED)
        return next
    }

    /** One run's requests: spends WaniKani's rate limit carefully and retries a 429 a few times. */
    private inner class Session(private val token: String) {
        private var remaining: Int? = null
        private var resetAt: Long? = null

        suspend fun get(url: String): JsonObject {
            var retries = 0
            while (true) {
                if (remaining == 0) resetAt?.let { pause(waitUntil(it, 0)) }
                val response = withContext(Dispatchers.IO) { request(url) }
                remaining = response.remaining
                resetAt = response.resetAt
                when {
                    response.status == 429 -> {
                        if (retries >= MAX_RETRIES) throw WaniKaniException(WaniKaniProblem.RATE_LIMITED)
                        pause(response.resetAt?.let { waitUntil(it, 1_000) } ?: (2_000L * (retries + 1)))
                        // The wait above spent the quota that was out; don't wait for it a second time.
                        remaining = null
                        retries++
                    }
                    response.status == 401 || response.status == 403 -> throw WaniKaniException(WaniKaniProblem.BAD_TOKEN)
                    response.status !in 200..299 -> throw WaniKaniException(WaniKaniProblem.FAILED)
                    else -> return try {
                        Json.parseToJsonElement(response.body).jsonObject
                    } catch (e: IllegalArgumentException) {
                        // Not JSON, or not an object.
                        throw WaniKaniException(WaniKaniProblem.FAILED, e)
                    }
                }
            }
        }

        /** Milliseconds until the epoch second [reset], at least [floor] and at most a minute. */
        private fun waitUntil(reset: Long, floor: Long): Long = (reset * 1_000 - now()).coerceIn(floor, MAX_WAIT_MS)

        private fun request(url: String): Response {
            val connection = try {
                URL(url).openConnection() as HttpURLConnection
            } catch (e: IOException) {
                throw WaniKaniException(WaniKaniProblem.OFFLINE, e)
            }
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Wanikani-Revision", REVISION)
                connection.setRequestProperty("Accept", "application/json")
                val status = connection.responseCode
                NetworkLog.shared.answered(NetworkPurpose.WANIKANI, url, status, connection.contentLengthLong)
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                return Response(
                    status,
                    body,
                    connection.getHeaderField("RateLimit-Remaining")?.trim()?.toIntOrNull(),
                    connection.getHeaderField("RateLimit-Reset")?.trim()?.toLongOrNull(),
                )
            } catch (e: IOException) {
                NetworkLog.shared.failed(NetworkPurpose.WANIKANI, url, e)
                throw WaniKaniException(WaniKaniProblem.OFFLINE, e)
            } finally {
                connection.disconnect()
            }
        }
    }

    private class Response(val status: Int, val body: String, val remaining: Int?, val resetAt: Long?)

    internal class Assignment(val subjectId: Long, val type: String, val stage: Int)

    internal class Subject(val id: Long, val characters: String, val reading: String?)

    companion object {
        const val BASE_URL = "https://api.wanikani.com"

        /** Guru: the first stage WaniKani counts as passed. */
        const val MIN_STAGE = 5
        private const val MAX_STAGE = 9
        private const val VOCABULARY = "vocabulary"
        private const val KANJI = "kanji"
        private const val REVISION = "20170710"
        private const val SUBJECTS_PER_REQUEST = 300
        private const val MAX_PAGES = 100
        private const val MAX_RETRIES = 3
        private const val MAX_WAIT_MS = 60_000L
        private const val TIMEOUT_MS = 20_000
        private val TOKEN = Regex("[A-Za-z0-9-]{8,64}")

        internal fun parseAssignments(page: JsonObject): List<Assignment> = try {
            page["data"]?.jsonArray.orEmpty().mapNotNull { element ->
                val data = element.jsonObject["data"]?.jsonObject ?: return@mapNotNull null
                Assignment(
                    data["subject_id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                    data["subject_type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    data["srs_stage"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                )
            }
        } catch (e: IllegalArgumentException) {
            // A reply of another shape than the API documents.
            throw WaniKaniException(WaniKaniProblem.FAILED, e)
        }

        /** Subjects with characters; a vocabulary word's reading is its primary one. */
        internal fun parseSubjects(page: JsonObject): List<Subject> = try {
            page["data"]?.jsonArray.orEmpty().mapNotNull { element ->
                val subject = element.jsonObject
                val data = subject["data"]?.jsonObject ?: return@mapNotNull null
                val characters = data["characters"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val readings = data["readings"] as? JsonArray ?: JsonArray(emptyList())
                val reading = readings.map { it.jsonObject }
                    .firstOrNull { it["primary"]?.jsonPrimitive?.contentOrNull == "true" }
                    ?.get("reading")?.jsonPrimitive?.contentOrNull
                Subject(subject["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null, characters, reading)
            }
        } catch (e: IllegalArgumentException) {
            throw WaniKaniException(WaniKaniProblem.FAILED, e)
        }
    }
}
