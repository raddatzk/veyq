package me.raddatz.veyq.core.platform.capabilities

import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.MEMORIES_MUSIC
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.MEMORIES_MUSIC_APPLE_MUSIC
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.MEMORIES_MUSIC_SPOTIFY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.Base64

/** The music frame's deployment configuration (ADR 0131 decision 4): which entries it leaves off, what they carry, and the
 *  values that refuse a boot. Pure JVM. */
class MusicSettingsTest {

    private val now = Instant.parse("2026-11-12T12:00:00Z")

    private fun token(exp: Long?): String {
        fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        val claims = if (exp == null) """{"iss":"T"}""" else """{"iss":"T","exp":$exp}"""
        return "${b64("""{"alg":"ES256"}""")}.${b64(claims)}.c2ln"
    }

    private val later = token(now.epochSecond + 3600)

    @Test
    fun musicReleasedOffTheDeploymentsValuesAreNeitherReadNorChecked() {
        val read = mutableListOf<String>()
        val dark = MusicSettings.deployed(false, { read += "domain"; "music.example.org" }, { read += "spotify"; "not a client id!" }, { read += "apple"; later })
        assertEquals(MusicSettings.DARK, dark)
        assertEquals(emptyList<String>(), read, "dark, not one value is read")
        assertEquals(setOf(MEMORIES_MUSIC, MEMORIES_MUSIC_SPOTIFY, MEMORIES_MUSIC_APPLE_MUSIC), dark.unconfigured(now))
        assertEquals(emptyMap<String, DeployedValues>(), dark.values())
        assertEquals(null, dark.frameUrl)
        // Control: released, the same values are read, and the malformed one refuses the boot.
        assertThrows<IllegalArgumentException> { MusicSettings.deployed(true, { "music.example.org" }, { "not a client id!" }, { later }) }
        assertEquals(emptySet<String>(), MusicSettings.deployed(true, { "music.example.org" }, { "abcdef0123456789" }, { later }).unconfigured(now))
    }

    @Test
    fun nothingConfiguredLeavesAllThreeOff() {
        assertEquals(setOf(MEMORIES_MUSIC, MEMORIES_MUSIC_SPOTIFY, MEMORIES_MUSIC_APPLE_MUSIC), MusicSettings.from(null, null, null).unconfigured(now))
        assertEquals(emptyMap<String, DeployedValues>(), MusicSettings.from("", " ", null).values(), "a blank variable is no value")
    }

    @Test
    fun aCredentialWithoutAHostConfiguresNothing() {
        assertEquals(setOf(MEMORIES_MUSIC, MEMORIES_MUSIC_SPOTIFY, MEMORIES_MUSIC_APPLE_MUSIC), MusicSettings.from(null, "abcdef0123456789", later).unconfigured(now))
    }

    @Test
    fun eachServiceIsOnWithItsOwnCredentialAndTheParentWithEither() {
        assertEquals(setOf(MEMORIES_MUSIC_APPLE_MUSIC), MusicSettings.from("music.example.org", "abcdef0123456789", null).unconfigured(now))
        assertEquals(setOf(MEMORIES_MUSIC_SPOTIFY), MusicSettings.from("music.example.org", null, later).unconfigured(now))
        assertEquals(setOf(MEMORIES_MUSIC, MEMORIES_MUSIC_SPOTIFY, MEMORIES_MUSIC_APPLE_MUSIC), MusicSettings.from("music.example.org", null, null).unconfigured(now))
    }

    @Test
    fun anAppleTokenPastItsExpIsUnconfiguredFromThatMomentAndOneWithoutExpNever() {
        val settings = MusicSettings.from("music.example.org", null, later)
        assertEquals(emptySet<String>() + MEMORIES_MUSIC_SPOTIFY, settings.unconfigured(now))
        assertEquals(setOf(MEMORIES_MUSIC, MEMORIES_MUSIC_SPOTIFY, MEMORIES_MUSIC_APPLE_MUSIC), settings.unconfigured(now.plusSeconds(3600)))
        assertEquals(setOf(MEMORIES_MUSIC_SPOTIFY), MusicSettings.from("music.example.org", null, token(null)).unconfigured(now.plusSeconds(10L * 365 * 86400)))
    }

    @Test
    fun theValuesAreTheFramesHttpsAddressAndEachCredentialOnItsOwnEntry() {
        val values = MusicSettings.from("Music.Example.org", "abcdef0123456789", later).values()
        assertEquals(DeployedValues(serviceUrl = "https://music.example.org/music-frame"), values[MEMORIES_MUSIC])
        assertEquals(DeployedValues(spotifyClientId = "abcdef0123456789"), values[MEMORIES_MUSIC_SPOTIFY])
        assertEquals(DeployedValues(appleDeveloperToken = later), values[MEMORIES_MUSIC_APPLE_MUSIC])
    }

    @Test
    fun aMalformedValueRefusesTheBootNamingItsVariable() {
        val host = assertThrows<IllegalArgumentException> { MusicSettings.from("music.example.org`) || Host(`x", null, null) }
        assertTrue(host.message!!.contains("VEYQ_MUSIC_DOMAIN"))
        assertThrows<IllegalArgumentException> { MusicSettings.from("https://music.example.org", null, null) }
        val client = assertThrows<IllegalArgumentException> { MusicSettings.from("music.example.org", "not a client id!", null) }
        assertTrue(client.message!!.contains("VEYQ_MUSIC_SPOTIFY_CLIENT_ID"))
        val apple = assertThrows<IllegalArgumentException> { MusicSettings.from("music.example.org", null, "not-a-jwt") }
        assertTrue(apple.message!!.contains("VEYQ_MUSIC_APPLE_DEVELOPER_TOKEN"))
        assertThrows<IllegalArgumentException> { MusicSettings.from("music.example.org", null, "a.bm90LWpzb24.c") } // payload "not-json"
        // Control: the same shapes, well-formed, are accepted.
        MusicSettings.from("music.example.org", "abcdef0123456789", later)
    }
}
