package app.honmani.reader.data.opds

import app.honmani.reader.data.net.LoginGate
import java.io.File
import java.io.OutputStream
import java.net.ServerSocket
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpdsClientTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val client = OpdsClient(connectTimeoutMs = 2_000, readTimeoutMs = 2_000)
    private val servers = mutableListOf<HeaderServer>()

    @After
    fun tearDown() = servers.forEach { it.stop() }

    private fun server(respond: (path: String, out: OutputStream) -> Unit) = HeaderServer(respond).also { servers += it }

    @Test
    fun sendsTheLoginToTheCatalogue_butNotToOtherServers() = runTest {
        val other = server { _, out -> out.respond("200 OK", FEED) }
        lateinit var catalogue: HeaderServer
        catalogue = server { path, out ->
            when {
                catalogue.authorizations.last() == null ->
                    out.respond("401 Unauthorized", "", "WWW-Authenticate: Basic realm=\"x\"")
                path == "/opds" -> out.respond("200 OK", FEED)
                else -> out.respond("302 Found", "", "Location: http://127.0.0.1:${other.port}/elsewhere")
            }
        }
        val opds = OpdsServer("http://127.0.0.1:${catalogue.port}/opds", "reader", "秘密")

        assertEquals("Test", client.feed(opds, opds.url).title)
        // Over http the login only went once the server asked for it.
        assertEquals(listOf(null, "Basic cmVhZGVyOuenmOWvhg=="), catalogue.authorizations)

        client.feed(opds, "http://127.0.0.1:${catalogue.port}/redirect")
        assertEquals(listOf(null), other.authorizations)
    }

    @Test
    fun dockerStyleHostNamesGetTheLogin() {
        val opds = OpdsServer("http://calibre_web:8083/opds", "reader", "secret")
        assertTrue(OpdsClient.authorization(opds, "http://calibre_web:8083/opds/new")!!.startsWith("Basic "))
        assertNull(OpdsClient.authorization(opds, "http://other_host:8083/opds"))
    }

    @Test
    fun relativeLinksFollowTheAddressAfterRedirects() = runTest {
        val catalogue = server { path, out ->
            when (path) {
                "/opds" -> out.respond("301 Moved Permanently", "", "Location: /opds/")
                else -> out.respond(
                    "200 OK",
                    """<feed xmlns="http://www.w3.org/2005/Atom"><title>Test</title>
                    <link rel="next" href="feed?page=2" type="application/atom+xml"/>
                    <entry><id>1</id><title>Book</title>
                    <link rel="http://opds-spec.org/acquisition" href="get/1.epub" type="application/epub+zip"/>
                    </entry></feed>""",
                )
            }
        }
        val base = "http://127.0.0.1:${catalogue.port}"
        val feed = client.feed(OpdsServer("$base/opds"), "$base/opds")

        assertEquals("$base/opds/feed?page=2", feed.nextUrl)
        assertEquals("$base/opds/get/1.epub", feed.entries.single().acquisitions.single().url)
    }

    @Test
    fun overHttpTheLoginWaitsForTheServerToAsk_onEveryNetwork() = runTest {
        var network = "home"
        val client = OpdsClient(connectTimeoutMs = 2_000, readTimeoutMs = 2_000, logins = LoginGate(network = { network }))
        // A device that happens to have the address on another Wi-Fi and never asks for a login.
        val stranger = server { _, out -> out.respond("200 OK", FEED) }
        val opds = OpdsServer("http://127.0.0.1:${stranger.port}/opds", "reader", "秘密")
        client.feed(opds, opds.url)
        client.feed(opds, opds.url)
        assertEquals(listOf(null, null), stranger.authorizations)

        lateinit var home: HeaderServer
        home = server { _, out ->
            if (home.authorizations.last() == null) {
                out.respond("401 Unauthorized", "", "WWW-Authenticate: Basic realm=\"x\"")
            } else {
                out.respond("200 OK", FEED)
            }
        }
        val nas = opds.copy(url = "http://127.0.0.1:${home.port}/opds")
        client.feed(nas, nas.url)
        client.feed(nas, nas.url)
        assertEquals(listOf(null, "Basic", "Basic"), home.authorizations.map { it?.substringBefore(' ') })

        network = "café"
        client.feed(nas, nas.url)
        assertEquals(null, home.authorizations[3])
    }

    @Test
    fun onAHotspotTheLoginIsNotSentOverHttp_evenWhenAsked() = runTest {
        val client = OpdsClient(connectTimeoutMs = 2_000, readTimeoutMs = 2_000, logins = LoginGate(hotspot = { true }))
        val asking = server { _, out -> out.respond("401 Unauthorized", "", "WWW-Authenticate: Basic realm=\"x\"") }
        val opds = OpdsServer("http://127.0.0.1:${asking.port}/opds", "reader", "秘密")
        assertFails(OpdsException.Reason.HOTSPOT) { client.feed(opds, opds.url) }
        assertEquals(listOf(null), asking.authorizations)
    }

    @Test
    fun errorsSayWhatWentWrong() = runTest {
        val catalogue = server { path, out ->
            when (path) {
                "/denied" -> out.respond("401 Unauthorized", "", "WWW-Authenticate: Basic realm=\"x\"")
                "/missing" -> out.respond("404 Not Found", "")
                "/broken" -> out.respond("500 Internal Server Error", "")
                else -> out.respond("200 OK", "<!DOCTYPE html><html><body>Login</body></html>")
            }
        }
        val opds = OpdsServer("http://127.0.0.1:${catalogue.port}/")
        assertFails(OpdsException.Reason.UNAUTHORIZED) { client.feed(opds, opds.url + "denied") }
        assertFails(OpdsException.Reason.NOT_A_CATALOGUE) { client.feed(opds, opds.url + "missing") }
        assertFails(OpdsException.Reason.SERVER) { client.feed(opds, opds.url + "broken") }
        assertFails(OpdsException.Reason.NOT_A_CATALOGUE) { client.feed(opds, opds.url + "login") }
        val closedPort = ServerSocket(0).use { it.localPort }
        assertFails(OpdsException.Reason.NETWORK) { client.feed(opds, "http://127.0.0.1:$closedPort/opds") }
    }

    @Test
    fun otherSchemesAreNotCatalogues() = runTest {
        // Typed into the catalogue dialog: these gave no HTTP connection and closed the app.
        for (address in listOf("ftp://books.example.com/opds", "file:///sdcard/feed.xml", "jar:file:/a.zip!/b")) {
            assertFails(OpdsException.Reason.NOT_A_CATALOGUE) { client.feed(OpdsServer(address), address) }
        }
    }

    @Test
    fun downloadsWithProgressAndTheSuggestedName() = runTest {
        val book = ByteArray(200_000) { it.toByte() }
        val catalogue = server { _, out ->
            out.head(
                "200 OK",
                "Content-Length: ${book.size}",
                "Content-Disposition: attachment; filename=\"neko.epub\"; filename*=UTF-8''%E7%8C%AB.epub",
            )
            out.write(book)
        }
        val target = File(folder.root, "book.part")
        var last = 0L to 0L
        val name = client.download(OpdsServer("http://127.0.0.1:${catalogue.port}/"), "http://127.0.0.1:${catalogue.port}/b", target) { done, total ->
            last = done to total
        }
        assertEquals("猫.epub", name)
        assertEquals(book.size.toLong() to book.size.toLong(), last)
        assertEquals(book.toList(), target.readBytes().toList())
    }

    @Test
    fun fileNamesFromContentDisposition() {
        assertEquals("a b.epub", OpdsClient.fileNameFromDisposition("attachment; filename=\"a b.epub\""))
        assertEquals("plain.txt", OpdsClient.fileNameFromDisposition("attachment; filename=plain.txt"))
        assertEquals("猫.epub", OpdsClient.fileNameFromDisposition("attachment; filename*=UTF-8''%E7%8C%AB.epub"))
        assertEquals("passwd", OpdsClient.fileNameFromDisposition("attachment; filename=\"../../etc/passwd\""))
        assertNull(OpdsClient.fileNameFromDisposition("attachment"))
        assertNull(OpdsClient.fileNameFromDisposition("attachment; filename=\"..\""))
    }

    /** A server like Calibre's content server: Digest only, nonce "n1" until it goes stale. */
    private fun digestServer(password: String, staleAfter: Int = Int.MAX_VALUE): HeaderServer {
        lateinit var server: HeaderServer
        var answered = 0
        server = server { path, out ->
            val params = server.authorizations.last()?.takeIf { it.startsWith("Digest ") }
                ?.let { DigestChallenge.challenges(it).single().second }
            val nonce = if (answered >= staleAfter) "n2" else "n1"
            val expected = params?.let {
                val ha1 = md5("${it["username"]}:calibre:$password")
                val ha2 = md5("GET:${it["uri"]}")
                md5("$ha1:${it["nonce"]}:${it["nc"]}:${it["cnonce"]}:auth:$ha2")
            }
            if (params != null && params["uri"] == path && params["nonce"] == nonce && params["response"] == expected) {
                answered++
                out.respond("200 OK", FEED)
            } else {
                val stale = if (params?.get("response") == expected && params?.get("nonce") != nonce) ", stale=true" else ""
                out.respond("401 Unauthorized", "", "WWW-Authenticate: Digest realm=\"calibre\", nonce=\"$nonce\", qop=\"auth\", opaque=\"o\"$stale")
            }
        }
        return server
    }

    @Test
    fun answersDigest_andKeepsUsingItForThatServer() = runTest {
        val calibre = digestServer("秘密")
        val opds = OpdsServer("http://127.0.0.1:${calibre.port}/opds", "reader", "秘密")

        assertEquals("Test", client.feed(opds, opds.url).title)
        assertEquals("Test", client.feed(opds, opds.url + "/new?page=2").title)

        val sent = calibre.authorizations.map { it?.substringBefore(' ') }
        assertEquals(listOf(null, "Digest", "Digest"), sent)
        assertTrue(calibre.authorizations.last()!!.contains("uri=\"/opds/new?page=2\""))
        assertTrue(calibre.authorizations.last()!!.contains("nc=00000002"))
        assertTrue(calibre.authorizations.none { it?.contains("秘密") == true })
    }

    @Test
    fun aStaleNonceIsRenewed_butAWrongPasswordFails() = runTest {
        val calibre = digestServer("right", staleAfter = 1)
        val opds = OpdsServer("http://127.0.0.1:${calibre.port}/opds", "reader", "right")
        client.feed(opds, opds.url)
        assertEquals("Test", client.feed(opds, opds.url).title)

        val wrong = digestServer("right")
        val badLogin = OpdsServer("http://127.0.0.1:${wrong.port}/opds", "reader", "wrong")
        assertFails(OpdsException.Reason.UNAUTHORIZED) { client.feed(badLogin, badLogin.url) }
        assertEquals(2, wrong.authorizations.size)
    }

    private fun md5(text: String) =
        java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun authorizationOnlyForTheSameOrigin() {
        val server = OpdsServer("https://books.example.com/opds", "u", "p")
        assertEquals("Basic dTpw", OpdsClient.authorization(server, "https://books.example.com:443/opds/new"))
        assertNull(OpdsClient.authorization(server, "http://books.example.com/opds/new"))
        assertNull(OpdsClient.authorization(server, "https://books.example.com:8443/opds"))
        assertNull(OpdsClient.authorization(server, "https://cdn.example.com/cover.jpg"))
        assertNull(OpdsClient.authorization(server.copy(username = null), server.url))
    }

    @Test
    fun theLoginIsNotInToString() {
        val text = OpdsServer("https://a.example", "user", "hunter2").toString()
        assertEquals(false, "hunter2" in text)
    }

    private suspend fun assertFails(reason: OpdsException.Reason, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected $reason")
        } catch (e: OpdsException) {
            assertEquals(e.message, reason, e.reason)
        }
    }

    private companion object {
        const val FEED = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Test</title></feed>"""
    }
}
