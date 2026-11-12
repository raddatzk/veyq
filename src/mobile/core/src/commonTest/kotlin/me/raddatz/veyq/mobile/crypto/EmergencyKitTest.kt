package me.raddatz.veyq.mobile.crypto

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.raddatz.veyq.mobile.session.testPrimitives

/** What the vectors leave to the reader: where a token sits in a text, the JSON's shapes, and origins beyond theirs. */
class EmergencyKitTest {
    private val p = testPrimitives
    private val key = ByteArray(32) { it.toByte() }
    private val keyText = SecretKeyText.format(p, key)

    private fun token(json: String) = "veyqkit:" + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(json.encodeToByteArray())
    private fun kit(server: String? = null) = token("""{"v":3,"email":"a@example.org","sk":"$keyText"${server?.let { ""","srv":"$it"""" } ?: ""}}""")

    @Test
    fun theFirstTokenInAnyTextIsRead() {
        val read = assertNotNull(EmergencyKit.read(p, "%PDF-1.3 junk (${kit("https://familie.example.org")}) more"))
        assertEquals("a@example.org", read.email)
        assertEquals(hex(key), hex(read.secretKey))
        assertEquals("https://familie.example.org", read.server)
        assertTrue(keyText !in read.toString(), "the key's text in toString")
        read.wipe()
        assertTrue(read.secretKey.all { it == 0.toByte() })
    }

    @Test
    fun onlyTheFirstTokenIsTried() {
        assertNull(EmergencyKit.read(p, token("""{"v":2}""") + " " + kit()))
        assertNotNull(EmergencyKit.read(p, "veyqkit: " + kit()), "a prefix with no symbols is no token")
    }

    @Test
    fun theJsonsShapesAreChecked() {
        assertNull(EmergencyKit.read(p, token("""{"v":"3","email":"a@example.org","sk":"$keyText"}""")), "a version as text")
        assertNull(EmergencyKit.read(p, token("""{"v":3,"email":"","sk":"$keyText"}""")), "an empty email")
        assertNull(EmergencyKit.read(p, token("""{"v":3,"email":7,"sk":"$keyText"}""")), "an email as a number")
        assertNull(EmergencyKit.read(p, token("""{"v":3,"email":"a@example.org","sk":"$keyText","srv":null}""")), "a null server")
        assertNull(EmergencyKit.read(p, token("""{"v":3,"email":"a@example.org"}""")), "no key")
        assertNull(EmergencyKit.read(p, token("[3]")), "no object")
        assertNull(EmergencyKit.read(p, "veyqkit:A"), "a length no base64 has")
        assertNull(EmergencyKit.read(p, "veyqkit:" + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(byteArrayOf(0xff.toByte(), 0x7b))), "no UTF-8")
        assertNotNull(EmergencyKit.read(p, token("""{"v":3.0,"email":"a@example.org","sk":"${keyText.lowercase()}"}""")), "3.0 is 3, a key folds")
    }

    @Test
    fun anOriginIsItsOwnSerialization() {
        listOf(
            "https://veyq.example.com", "http://veyq.localhost:8081", "https://xn--mller-kva.example", "http://127.0.0.1",
            "http://[::1]:8080", "https://[2001:db8::1]", "https://[1:0:0:2::3]", "https://host_name.example", "https://example.com.",
        ).forEach { assertTrue(EmergencyKit.isServer(it), it) }
        listOf(
            "https://Veyq.example.com", "HTTPS://veyq.example.com", "https://veyq.example.com/", "https://veyq.example.com:443",
            "http://veyq.example.com:80", "https://veyq.example.com:0443", "https://veyq.example.com:", "https://veyq.example.com:65536",
            "https://user@veyq.example.com", "https://müller.example", "https://veyq.example.com?x", "ftp://veyq.example.com",
            "https://", "http://127.1", "http://127.0.0.01", "http://0x7f.0.0.1", "http://256.0.0.1", "https://[::0:1]", "https://[0::1]",
            "https://[1:0:0:2:0:0:0:3]", "https://[::ffff:127.0.0.1]", "https://[::1", "https://ex%41mple.com",
        ).forEach { assertFalse(EmergencyKit.isServer(it), it) }
    }
}
