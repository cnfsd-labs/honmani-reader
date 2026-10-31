package app.honmani.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPolicyTest {
    @Test
    fun inlineScripts_areAllowedByTheirHash() {
        val policy = ReaderPolicy.contentSecurityPolicy("<head><script>alert(1)</script></head>")
        // printf 'alert(1)' | openssl dgst -sha256 -binary | base64
        assertTrue(policy, policy.startsWith("script-src 'self' 'sha256-bhHHL3z2vDgxUt0W3dWQOrprscmda2Y5pLsLg4GF+pI='; "))
    }

    @Test
    fun scriptFiles_needNoHash() {
        val policy = ReaderPolicy.contentSecurityPolicy("<script type=\"module\" src=\"/_app/start.js\"></script>")
        assertTrue(policy, policy.startsWith("script-src 'self'; "))
    }

    @Test
    fun framesPluginsAndForms_areBlocked() {
        val policy = ReaderPolicy.contentSecurityPolicy("<p>no scripts</p>")
        assertEquals(
            "script-src 'self'; worker-src 'self' blob:; object-src 'none'; frame-src 'none'; base-uri 'self'; form-action 'none'",
            policy,
        )
        assertFalse(policy.contains("unsafe"))
    }

    @Test
    fun multilineScripts_areHashedWhole() {
        val one = ReaderPolicy.contentSecurityPolicy("<script>\n  a();\n</script>")
        val other = ReaderPolicy.contentSecurityPolicy("<script>\n  b();\n</script>")
        assertTrue(one.contains("'sha256-"))
        assertFalse(one == other)
    }

    private val own = "appassets.androidplatform.net"

    @Test
    fun theReadersOwnOrigin_loadsInTheWebView() {
        assertEquals(Navigation.LOAD, ReaderPolicy.navigation("https", own, hasGesture = false))
        assertEquals(Navigation.LOAD, ReaderPolicy.navigation("https", own, hasGesture = true))
    }

    @Test
    fun theOwnHostOnAnotherSchemeIsNotTheReadersOrigin() {
        assertEquals(Navigation.BLOCK_WITHOUT_TAP, ReaderPolicy.navigation("http", own, hasGesture = false))
        assertEquals(Navigation.OPEN_EXTERNALLY, ReaderPolicy.navigation("http", own, hasGesture = true))
    }

    @Test
    fun aWebLinkOpensInTheBrowserOnlyOnATap() {
        assertEquals(Navigation.OPEN_EXTERNALLY, ReaderPolicy.navigation("https", "example.org", true))
        assertEquals(Navigation.OPEN_EXTERNALLY, ReaderPolicy.navigation("http", "example.org", true))
        assertEquals(Navigation.BLOCK_WITHOUT_TAP, ReaderPolicy.navigation("https", "example.org", false))
        assertEquals(Navigation.BLOCK_WITHOUT_TAP, ReaderPolicy.navigation("http", "example.org", false))
    }

    @Test
    fun aHostThatOnlyLooksLikeTheReadersOwn_isAnExternalLink() {
        assertEquals(Navigation.OPEN_EXTERNALLY, ReaderPolicy.navigation("https", "$own.evil.example", true))
        assertEquals(Navigation.BLOCK_WITHOUT_TAP, ReaderPolicy.navigation("https", "evil-$own", false))
    }

    @Test
    fun otherSchemesAreNeverOpened_withOrWithoutATap() {
        for (scheme in listOf("javascript", "intent", "file", "content", "data", "market", "ftp", "about", null)) {
            assertEquals(scheme, Navigation.BLOCK, ReaderPolicy.navigation(scheme, null, hasGesture = true))
            assertEquals(scheme, Navigation.BLOCK_WITHOUT_TAP, ReaderPolicy.navigation(scheme, null, hasGesture = false))
        }
    }
}
