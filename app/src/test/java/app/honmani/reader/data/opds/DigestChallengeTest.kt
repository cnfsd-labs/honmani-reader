package app.honmani.reader.data.opds

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestChallengeTest {
    /** RFC 7616 section 3.9.1, with the MD5 and the SHA-256 challenge. */
    @Test
    fun answersTheRfcExample() {
        val header = """Digest realm="http-auth@example.org", qop="auth, auth-int", algorithm=SHA-256, """ +
            """nonce="7ypf/xlj9XXwfDPEoM4URrv/xwf94BcCAzFZH4GiTo0v", opaque="FQhe/qaU925kfnzjCev0ciny7QMkPqMAFRtzCUYo5tdS", """ +
            """Digest realm="http-auth@example.org", qop="auth, auth-int", algorithm=MD5, """ +
            """nonce="7ypf/xlj9XXwfDPEoM4URrv/xwf94BcCAzFZH4GiTo0v", opaque="FQhe/qaU925kfnzjCev0ciny7QMkPqMAFRtzCUYo5tdS""""
        val cnonce = "f2/wE4q74E6zIJEtWaHKaf5wv/H5QzzpXusqGemxURZJ"

        val sha = DigestChallenge.from(listOf(header))!!
            .authorization("Mufasa", "Circle of Life", "GET", "/dir/index.html", cnonce)
        assertTrue(sha, "algorithm=SHA-256" in sha)
        assertTrue(sha, "response=\"753927fa0e85d155564e2e272a28d1802ca10daf4496794697cf8db5856cb6c1\"" in sha)
        assertTrue(sha, "qop=auth, nc=00000001, cnonce=\"$cnonce\"" in sha)
        assertTrue(sha, "opaque=\"FQhe/qaU925kfnzjCev0ciny7QMkPqMAFRtzCUYo5tdS\"" in sha)

        val md5 = DigestChallenge.from(listOf(header.substringAfter("tdS\", ")))!!
            .authorization("Mufasa", "Circle of Life", "GET", "/dir/index.html", cnonce)
        assertTrue(md5, "response=\"8ca523f5e9506fed4657c9700eebdbec\"" in md5)
    }

    /** RFC 2617's form without qop, as older servers send it. */
    @Test
    fun answersAChallengeWithoutQop() {
        val challenge = DigestChallenge.from(listOf("Digest realm=\"r\", nonce=\"n\""))!!
        val header = challenge.authorization("u", "p", "GET", "/opds")
        val expected = md5("${md5("u:r:p")}:n:${md5("GET:/opds")}")
        assertTrue(header, "response=\"$expected\"" in header)
        assertTrue(header, "qop" !in header)
    }

    @Test
    fun countsRequestsWithOneNonce() {
        val challenge = DigestChallenge.from(listOf("Digest realm=\"r\", nonce=\"n\", qop=\"auth\""))!!
        challenge.authorization("u", "p", "GET", "/a")
        assertTrue("nc=00000002" in challenge.authorization("u", "p", "GET", "/b"))
    }

    @Test
    fun findsDigestBesideOtherSchemes_andSkipsWhatItCannotAnswer() {
        val mixed = DigestChallenge.from(listOf("Basic realm=\"calibre\"", "Digest realm=\"calibre\", nonce=\"abc\", qop=\"auth\", stale=TRUE"))!!
        assertEquals("abc", mixed.nonce)
        assertTrue(mixed.stale)
        assertEquals("calibre", DigestChallenge.from(listOf("Basic realm=\"x\", Digest realm=\"calibre\", nonce=\"a\""))!!.realm)
        assertNull(DigestChallenge.from(listOf("Basic realm=\"x\"")))
        assertNull(DigestChallenge.from(listOf("Digest realm=\"x\", nonce=\"n\", qop=\"auth-int\"")))
        assertNull(DigestChallenge.from(listOf("Digest realm=\"x\", nonce=\"n\", algorithm=SHA-512-256")))
        assertNull(DigestChallenge.from(listOf("Digest realm=\"x\"")))
    }

    @Test
    fun quotedValuesKeepCommasAndEscapes() {
        val params = DigestChallenge.challenges("""Digest realm="a, \"b\"", nonce=n1""").single().second
        assertEquals("a, \"b\"", params["realm"])
        assertEquals("n1", params["nonce"])
    }

    @Test
    fun prefersSha256OverMd5_andNamesNoAlgorithmWhenTheServerDidNot() {
        val both = DigestChallenge.from(
            listOf("Digest realm=\"r\", nonce=\"n\", algorithm=MD5", "Digest realm=\"r\", nonce=\"n\", algorithm=sha-256"),
        )!!
        val header = both.authorization("u", "p", "GET", "/a", "c")
        assertTrue(header, "algorithm=sha-256" in header)
        assertEquals(sha256("${sha256("u:r:p")}:n:${sha256("GET:/a")}"), responseOf(header))

        val plain = DigestChallenge.from(listOf("Digest realm=\"r\", nonce=\"n\""))!!.authorization("u", "p", "GET", "/a")
        assertTrue(plain, "algorithm" !in plain)
    }

    /** The "-sess" variants hash the nonce and cnonce into the first hash. */
    @Test
    fun answersTheSessVariant() {
        val challenge = DigestChallenge.from(listOf("Digest realm=\"r\", nonce=\"n\", qop=auth, algorithm=MD5-sess"))!!
        val header = challenge.authorization("u", "p", "GET", "/a", "cn")
        val ha1 = md5("${md5("u:r:p")}:n:cn")
        assertEquals(md5("$ha1:n:00000001:cn:auth:${md5("GET:/a")}"), responseOf(header))
        assertTrue(header, "algorithm=MD5-sess" in header)
    }

    @Test
    fun qopListWithAuthIsAnswered_andCredentialsAreEscaped() {
        val challenge = DigestChallenge.from(listOf("Digest realm=\"r\", nonce=\"n\", qop=\"auth-int,auth\""))!!
        val header = challenge.authorization("a\"b", "p", "GET", "/a", "cn")
        assertTrue(header, "qop=auth," in header)
        assertTrue(header, "username=\"a\\\"b\"" in header)
    }

    @Test
    fun ignoresMalformedHeaders() {
        assertNull(DigestChallenge.from(emptyList()))
        assertNull(DigestChallenge.from(listOf("")))
        assertNull(DigestChallenge.from(listOf("Digest")))
        assertNull(DigestChallenge.from(listOf("abc123==")))
        assertEquals("r", DigestChallenge.from(listOf("DIGEST REALM=\"r\", NONCE=\"n\""))!!.realm)
    }

    private fun responseOf(header: String) = Regex("""response="([0-9a-f]+)"""").find(header)!!.groupValues[1]

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun md5(text: String) =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
