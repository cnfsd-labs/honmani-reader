package app.honmani.reader.data.opds

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpdsRepositoryTest {
    private val dao = FakeOpdsCatalogDao()
    private val repository = OpdsRepository(dao, ReversingCipher(), OpdsClient(), now = { 1L })

    @Test
    fun candidateUrls() {
        assertEquals(
            listOf("https://books.example.com", "https://books.example.com/opds"),
            OpdsRepository.candidateUrls(" books.example.com/ "),
        )
        assertEquals(listOf("http://192.168.1.5:8083/opds"), OpdsRepository.candidateUrls("http://192.168.1.5:8083/opds/"))
        assertEquals(emptyList<String>(), OpdsRepository.candidateUrls("  "))
    }

    @Test
    fun passwordsAreStoredEncrypted_andKeptWhenNotRetyped() = runTest {
        val id = repository.save(null, "Calibre", "https://a.example/opds", "reader", "pw")
        assertEquals("wp", dao.find(id)!!.passwordCipher)
        assertEquals(OpdsServer("https://a.example/opds", "reader", "pw"), repository.server(id))

        repository.save(id, "Calibre", "https://b.example/opds", "reader", null)
        assertEquals(OpdsServer("https://b.example/opds", "reader", "pw"), repository.server(id))

        // An empty one is the password now, not a missing one.
        repository.save(id, "Calibre", "https://b.example/opds", "reader", "")
        assertEquals("", repository.server(id)!!.password)

        val catalog = repository.catalogs.first().single()
        assertEquals("https://b.example/opds", catalog.url)
        assertFalse(catalog.needsPassword)
    }

    @Test
    fun aKeystoreFailureIsAReadableError_andNothingIsSaved() = runTest {
        // Unit tests have no Android Keystore: the real cipher fails as a broken one would.
        val broken = OpdsRepository(dao, KeystoreCredentialCipher(), OpdsClient(), now = { 1L })
        val error = try {
            broken.save(null, "Calibre", "https://a.example/opds", "reader", "pw")
            null
        } catch (e: OpdsException) {
            e
        }
        assertEquals(OpdsException.Reason.PASSWORD_STORAGE, error!!.reason)
        assertTrue(repository.catalogs.first().isEmpty())
    }

    @Test
    fun addingAListedServerAgain_fillsInItsPasswordInsteadOfAddingIt() = runTest {
        // A restored backup lists the server with its user name but without the password.
        val restored = dao.insert(OpdsCatalogEntity(title = "Calibre-Web", url = "http://127.0.0.1:7300/opds", username = "test", addedAt = 1L))
        assertTrue(repository.catalogs.first().single().needsPassword)

        val again = repository.save(null, "Calibre-Web", "https://127.0.0.1:7300/opds/", "test", "secret")

        assertEquals(restored, again)
        val catalog = repository.catalogs.first().single()
        assertTrue(catalog.hasPassword)
        assertFalse(catalog.needsPassword)
        assertEquals("secret", repository.server(restored)!!.password)

        // Another login on the same server is another catalogue.
        repository.save(null, "Calibre-Web", "http://127.0.0.1:7300/opds", "guest", "pw")
        assertEquals(2, repository.catalogs.first().size)
    }

    @Test
    fun aDeliberatelyEmptyPassword_isStored_andNotAskedForAgain() = runTest {
        val id = repository.save(null, "Home", "http://192.168.1.5:8083/opds", "reader", "")

        val catalog = repository.catalogs.first().single()
        assertTrue(catalog.hasPassword)
        assertFalse(catalog.needsPassword)
        assertEquals("", repository.server(id)!!.password)
    }

    @Test
    fun onAHotspotOrAPortalAnswer_noAdviceToTypeHttp() = runTest {
        // A captive portal answers http in the server's place, here with 511.
        val portal = PlainHttpServer("HTTP/1.1 511 Network Authentication Required\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        val server = PlainHttpServer()
        try {
            val client = OpdsClient(connectTimeoutMs = 2_000, readTimeoutMs = 2_000)
            val repository = OpdsRepository(dao, ReversingCipher(), client)
            assertEquals(OpdsException.Reason.CERTIFICATE, connectError(repository, "127.0.0.1:${portal.port}"))
            // On a hotspot with a login page even a plausible answer is the portal's.
            val onHotspot = OpdsRepository(dao, ReversingCipher(), client, onHotspot = { true })
            assertEquals(OpdsException.Reason.CERTIFICATE, connectError(onHotspot, "127.0.0.1:${server.port}"))
        } finally {
            portal.stop()
            server.stop()
        }
    }

    @Test
    fun anAddressWithoutSchemeOnAPlainHttpServer_saysToTypeHttp_withoutSendingTheLogin() = runTest {
        val server = PlainHttpServer()
        try {
            val client = OpdsRepository(dao, ReversingCipher(), OpdsClient(connectTimeoutMs = 2_000, readTimeoutMs = 2_000))
            assertEquals(OpdsException.Reason.HTTP_ONLY, connectError(client, "127.0.0.1:${server.port}"))
            // Typed with https:// the certificate error stays: the user asked for https.
            assertEquals(OpdsException.Reason.CERTIFICATE, connectError(client, "https://127.0.0.1:${server.port}"))
            assertTrue(server.requests.none { it.contains("Authorization", ignoreCase = true) })
        } finally {
            server.stop()
        }
    }

    private suspend fun connectError(repository: OpdsRepository, address: String) = try {
        repository.connect(address, "reader", "pw")
        null
    } catch (e: OpdsException) {
        e.reason
    }

    @Test
    fun noUserNameMeansNoPassword() = runTest {
        val id = repository.save(null, "Public", "https://a.example/opds", " ", "pw")
        assertNull(dao.find(id)!!.username)
        assertNull(dao.find(id)!!.passwordCipher)
        assertTrue(repository.catalogs.first().none { it.hasPassword })
    }
}

/** Stands in for the Keystore, which unit tests don't have. */
private class ReversingCipher : CredentialCipher {
    override fun encrypt(plain: String) = plain.reversed()

    override fun decrypt(encrypted: String) = encrypted.reversed()
}

private class FakeOpdsCatalogDao : OpdsCatalogDao {
    private val catalogs = MutableStateFlow<List<OpdsCatalogEntity>>(emptyList())

    override fun observeAll(): Flow<List<OpdsCatalogEntity>> = catalogs

    override suspend fun find(id: Long) = catalogs.value.find { it.id == id }

    override suspend fun insert(catalog: OpdsCatalogEntity): Long {
        val id = (catalogs.value.maxOfOrNull { it.id } ?: 0) + 1
        catalogs.update { it + catalog.copy(id = id) }
        return id
    }

    override suspend fun update(catalog: OpdsCatalogEntity) =
        catalogs.update { list -> list.map { if (it.id == catalog.id) catalog else it } }

    override suspend fun delete(id: Long) = catalogs.update { list -> list.filterNot { it.id == id } }
}

/** Answers every connection with a plain-http [response] (401) at once, so a TLS handshake to it fails. */
private class PlainHttpServer(
    private val response: String =
        "HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"x\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
) {
    private val socket = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val port = socket.localPort
    private val received: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** A copy: the server thread may still add the request it is reading while a test looks. */
    val requests: List<String> get() = synchronized(received) { received.toList() }

    init {
        Thread {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                client.use {
                    it.soTimeout = 500
                    it.getOutputStream().write(response.toByteArray())
                    it.shutdownOutput()
                    val bytes = java.io.ByteArrayOutputStream()
                    try {
                        it.getInputStream().copyTo(bytes)
                    } catch (_: Exception) {
                    }
                    received += bytes.toString(Charsets.ISO_8859_1.name())
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() = socket.close()
}
