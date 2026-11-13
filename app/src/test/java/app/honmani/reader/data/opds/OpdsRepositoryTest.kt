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

        repository.save(id, "Calibre", "https://b.example/opds", "reader", "")
        assertNull(repository.server(id)!!.password)

        val catalog = repository.catalogs.first().single()
        assertEquals("https://b.example/opds", catalog.url)
        assertFalse(catalog.hasPassword)
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
