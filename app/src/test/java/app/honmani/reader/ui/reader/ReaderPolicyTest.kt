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
            "script-src 'self'; object-src 'none'; frame-src 'none'; base-uri 'self'; form-action 'none'",
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
}
