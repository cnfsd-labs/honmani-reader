package app.honmani.reader.known

import app.honmani.reader.data.opds.HeaderServer
import app.honmani.reader.data.opds.respond
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WaniKaniClientTest {
    private val token = "11111111-2222-3333-4444-555555555555"
    private val now = 1_000_000_000_000L
    private val pauses = mutableListOf<Long>()

    private var handler: (path: String, out: OutputStream) -> Unit = { _, out -> out.respond("404 Not Found", "{}") }
    private val server = HeaderServer { path, out -> handler(path, out) }
    private val base = "http://127.0.0.1:${server.port}"
    private val client = WaniKaniClient(base, now = { now }, pause = { pauses += it })

    @After
    fun tearDown() = server.stop()

    private fun assignment(id: Int, type: String, stage: Int) =
        """{"id":$id,"object":"assignment","data":{"subject_id":$id,"subject_type":"$type","srs_stage":$stage}}"""

    private fun subject(id: Int, type: String, characters: String?, reading: String?) = buildString {
        append("""{"id":$id,"object":"$type","data":{"characters":""")
        append(if (characters == null) "null" else "\"$characters\"")
        append(""","readings":[""")
        if (reading != null) {
            append("""{"reading":"x","primary":false},{"reading":"$reading","primary":true}""")
        }
        append("]}}")
    }

    private fun page(data: List<String>, next: String? = null) =
        """{"object":"collection","pages":{"next_url":${next?.let { "\"$it\"" } ?: "null"},"per_page":500},"data":[${data.joinToString(",")}]}"""

    private fun answerNormally(extraAssignments: List<String> = emptyList()) {
        handler = { path, out ->
            when {
                path.startsWith("/v2/assignments") && "page_after_id" !in path -> out.respond(
                    "200 OK",
                    page(listOf(assignment(1, "vocabulary", 5), assignment(2, "vocabulary", 4), assignment(3, "kanji", 9)) + extraAssignments, "$base/v2/assignments?page_after_id=3"),
                )
                path.startsWith("/v2/assignments") -> out.respond("200 OK", page(listOf(assignment(4, "vocabulary", 7), assignment(5, "kana_vocabulary", 5))))
                path.startsWith("/v2/subjects") -> out.respond(
                    "200 OK",
                    page(
                        listOf(
                            subject(1, "vocabulary", "大人", "おとな"),
                            subject(2, "vocabulary", "子供", "こども"),
                            subject(3, "kanji", "人", null),
                            subject(4, "vocabulary", "一つ", "ひとつ"),
                            subject(5, "kana_vocabulary", "ありがとう", null),
                        ),
                    ),
                )
                else -> out.respond("404 Not Found", "{}")
            }
        }
    }

    private fun problemOf(block: suspend () -> Unit): WaniKaniProblem {
        try {
            runBlocking { block() }
        } catch (e: WaniKaniException) {
            assertFalse(token in e.message.orEmpty())
            assertFalse(token in e.cause?.message.orEmpty())
            return e.problem
        }
        fail("Expected a WaniKaniException")
        throw AssertionError()
    }

    @Test
    fun parsesAssignmentsAndSubjects() {
        val assignments = WaniKaniClient.parseAssignments(
            Json.parseToJsonElement(page(listOf(assignment(1, "vocabulary", 5), """{"data":{"subject_id":2}}"""))).jsonObject,
        )
        assertEquals(1, assignments.size)
        assertEquals(1L, assignments[0].subjectId)
        assertEquals("vocabulary", assignments[0].type)
        assertEquals(5, assignments[0].stage)

        val subjects = WaniKaniClient.parseSubjects(
            Json.parseToJsonElement(page(listOf(subject(1, "vocabulary", "大人", "おとな"), subject(2, "radical", null, null), subject(3, "kanji", "人", null)))).jsonObject,
        )
        assertEquals(listOf("大人", "人"), subjects.map { it.characters })
        assertEquals("おとな", subjects[0].reading)
        assertNull(subjects[1].reading)
    }

    @Test
    fun readsVocabularyAtGuruAndAbove_acrossPages_withTheTokenInAHeader() = runBlocking {
        answerNormally()

        val items = client.knownItems(" $token ", includeKanji = false)

        // Stage 4 and the kana vocabulary are left out, and so is the kanji that was not asked for.
        assertEquals(listOf(WaniKaniItem("大人", "おとな", true), WaniKaniItem("一つ", "ひとつ", true)), items)
        val first = server.requests.first()
        assertEquals("Bearer $token", first.headers["authorization"])
        assertEquals("20170710", first.headers["wanikani-revision"])
        assertTrue("subject_types=vocabulary&" in first.path)
        assertTrue("srs_stages=5,6,7,8,9" in first.path)
        assertTrue(server.requests.none { token in it.path })
        assertTrue(server.requests.any { it.path.startsWith("/v2/subjects?ids=") })
    }

    @Test
    fun kanjiOnlyWhenAsked_withoutAReading() = runBlocking {
        answerNormally()

        val items = client.knownItems(token, includeKanji = true)

        assertTrue("subject_types=vocabulary,kanji&" in server.requests.first().path)
        assertEquals(WaniKaniItem("人", null, false), items.single { !it.vocabulary })
        assertEquals(3, items.size)
    }

    @Test
    fun aNextUrlOfAnotherOriginIsRefused_andNoRequestGoesThere() {
        handler = { _, out -> out.respond("200 OK", page(listOf(assignment(1, "vocabulary", 5)), "https://evil.example/v2/assignments?page_after_id=1")) }

        assertEquals(WaniKaniProblem.FAILED, problemOf { client.knownItems(token, false) })
        assertEquals(1, server.requests.size)
    }

    @Test
    fun aRepeatedNextUrlStopsTheLoop() {
        handler = { path, out -> out.respond("200 OK", page(listOf(assignment(1, "vocabulary", 5)), "$base$path")) }

        assertEquals(WaniKaniProblem.FAILED, problemOf { client.knownItems(token, false) })
    }

    @Test
    fun aRateLimitedRequestWaitsUntilTheResetAndIsRepeated() = runBlocking {
        var calls = 0
        answerNormally()
        val normal = handler
        handler = { path, out ->
            if (path.startsWith("/v2/assignments") && "page_after_id" !in path && calls++ == 0) {
                out.respond("429 Too Many Requests", "{}", "RateLimit-Remaining: 0", "RateLimit-Reset: ${now / 1000 + 7}")
            } else {
                normal(path, out)
            }
        }

        val items = client.knownItems(token, false)

        assertEquals(2, items.size)
        assertEquals(listOf(7_000L), pauses)
    }

    @Test
    fun aRequestThatKeepsGetting429GivesUpAfterThreeRetries() {
        handler = { _, out -> out.respond("429 Too Many Requests", "{}") }

        assertEquals(WaniKaniProblem.RATE_LIMITED, problemOf { client.knownItems(token, false) })
        assertEquals(4, server.requests.size)
        assertEquals(3, pauses.size)
        assertTrue(pauses.all { it in 1L..60_000L })
    }

    @Test
    fun anExhaustedQuotaWaitsBeforeTheNextRequest_boundedToAMinute() = runBlocking {
        answerNormally()
        val normal = handler
        handler = { path, out ->
            if (path.startsWith("/v2/assignments") && "page_after_id" !in path) {
                out.respond("200 OK", page(listOf(assignment(1, "vocabulary", 5)), "$base/v2/assignments?page_after_id=3"), "RateLimit-Remaining: 0", "RateLimit-Reset: ${now / 1000 + 3600}")
            } else {
                normal(path, out)
            }
        }

        client.knownItems(token, false)

        assertEquals(60_000L, pauses.first())
    }

    @Test
    fun aRejectedTokenIsReported_withoutTheTokenInTheMessage() {
        handler = { _, out -> out.respond("401 Unauthorized", """{"error":"Unauthorized. Nice try, $token","code":401}""") }

        assertEquals(WaniKaniProblem.BAD_TOKEN, problemOf { client.knownItems(token, false) })
        handler = { _, out -> out.respond("403 Forbidden", "{}") }
        assertEquals(WaniKaniProblem.BAD_TOKEN, problemOf { client.knownItems(token, false) })
    }

    @Test
    fun aMalformedOrEmptyTokenSendsNothing() {
        assertEquals(WaniKaniProblem.NO_TOKEN, problemOf { client.knownItems("  ", false) })
        assertEquals(WaniKaniProblem.BAD_TOKEN, problemOf { client.knownItems("abc\r\nX-Evil: 1", false) })
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun aReplyThatIsNotJsonIsAFailure() {
        handler = { _, out -> out.respond("200 OK", "<html>") }

        assertEquals(WaniKaniProblem.FAILED, problemOf { client.knownItems(token, false) })
    }

    @Test
    fun anUnreachableServerIsOffline() {
        server.stop()

        assertEquals(WaniKaniProblem.OFFLINE, problemOf { client.knownItems(token, false) })
    }
}
