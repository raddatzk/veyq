package me.raddatz.veyq.core.platform.capabilities

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import me.raddatz.veyq.core.appshell.MusicFrameHost
import me.raddatz.veyq.core.config.VeyqConfig
import org.jboss.logging.Logger
import java.time.Instant
import java.util.Base64

/**
 * The register entries a deployment configures (the catalog's `source: deployment`, ADR 0131 decision 4): which of them
 * the environment leaves unconfigured right now. The registry counts those as switched off at the instance level, as it
 * counts an external service without an address (ADR 0088 decision 7). [ALL] is the test seam that configures every one.
 */
fun interface DeploymentCapabilities {
    fun unconfigured(): Set<String>

    companion object {
        val ALL = DeploymentCapabilities { emptySet() }
    }
}

/** The values a deployment entry carries on the wire while active: the frame's address, or a service's credential. */
data class DeployedValues(val serviceUrl: String? = null, val spotifyClientId: String? = null, val appleDeveloperToken: String? = null)

/**
 * The music frame's configuration (ADR 0131 decisions 2 and 4), checked once at boot: the music host and the two
 * services' public credentials, each optional. A malformed value refuses the boot, naming the variable, rather than serve
 * a frame that cannot work; an Apple token past its `exp` is read as absent at the moment it is asked, so the entry goes
 * off on the day the token does, with no restart.
 */
data class MusicSettings(
    val domain: String?,
    val spotifyClientId: String?,
    val appleDeveloperToken: String?,
    val appleExpiry: Instant?,
) {
    private fun spotify() = domain != null && spotifyClientId != null
    private fun apple(now: Instant) = domain != null && appleDeveloperToken != null && (appleExpiry == null || now.isBefore(appleExpiry))

    /** The three entries this configuration leaves off at [now]: a child without its credential, the parent without a host
     *  or without any child. */
    fun unconfigured(now: Instant): Set<String> = setOfNotNull(
        CapabilityIds.MEMORIES_MUSIC.takeUnless { spotify() || apple(now) },
        CapabilityIds.MEMORIES_MUSIC_SPOTIFY.takeUnless { spotify() },
        CapabilityIds.MEMORIES_MUSIC_APPLE_MUSIC.takeUnless { apple(now) },
    )

    /** The frame's address, which the app embeds: always `https`, the one scheme a browser grants EME and a sign-in to. */
    val frameUrl: String? get() = domain?.let { "https://$it$FRAME_PATH" }

    /** What each configured entry carries; an entry that is off is in no set, so its values never reach a client. */
    fun values(): Map<String, DeployedValues> = buildMap {
        frameUrl?.let { put(CapabilityIds.MEMORIES_MUSIC, DeployedValues(serviceUrl = it)) }
        spotifyClientId?.let { put(CapabilityIds.MEMORIES_MUSIC_SPOTIFY, DeployedValues(spotifyClientId = it)) }
        appleDeveloperToken?.let { put(CapabilityIds.MEMORIES_MUSIC_APPLE_MUSIC, DeployedValues(appleDeveloperToken = it)) }
    }

    companion object {
        /** The frame's page on the music host; its Spotify callback is the same page under [CALLBACK_PATH]. */
        const val FRAME_PATH = MusicFrameHost.PATH
        const val CALLBACK_PATH = MusicFrameHost.CALLBACK_PATH

        /** `TenantIngressProjector`'s hostname shape: the value becomes a Traefik `Host(` matcher and a policy source. */
        private val HOSTNAME = Regex("^([a-z0-9](-?[a-z0-9])*\\.)+[a-z]{2,}$")

        /** A Spotify client ID is 32 hex characters; the bound is looser so a format change upstream does not refuse a boot. */
        private val CLIENT_ID = Regex("^[0-9A-Za-z]{8,64}$")

        /** A JWT's three base64url segments, at most the contract's 2048 characters. */
        private val JWT = Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")

        /** Nothing configured: what the music is while its release flag is off (musicReleased, ADR 0131 decision 8). */
        val DARK = MusicSettings(null, null, null, null)

        /**
         * The deployment's settings behind the TEMPORARY release flag (`veyq.music.released`, ADR 0131 decision 8): while
         * it is off the three values are not read at all — not checked either, so a value left over from a test refuses no
         * boot — and the instance is what it was before ADR 0131. Removal: call [from] directly.
         */
        fun deployed(musicReleased: Boolean, domain: () -> String?, spotifyClientId: () -> String?, appleDeveloperToken: () -> String?): MusicSettings =
            if (!musicReleased) DARK else from(domain(), spotifyClientId(), appleDeveloperToken())

        fun from(domain: String?, spotifyClientId: String?, appleDeveloperToken: String?): MusicSettings {
            val host = domain?.trim()?.lowercase()?.ifEmpty { null }
            require(host == null || HOSTNAME.matches(host)) { "VEYQ_MUSIC_DOMAIN '$domain' is not a host name (ADR 0131)" }
            val client = spotifyClientId?.trim()?.ifEmpty { null }
            require(client == null || CLIENT_ID.matches(client)) { "VEYQ_MUSIC_SPOTIFY_CLIENT_ID is not a Spotify client ID (ADR 0131)" }
            val token = appleDeveloperToken?.trim()?.ifEmpty { null }
            require(token == null || (token.length <= 2048 && JWT.matches(token))) {
                "VEYQ_MUSIC_APPLE_DEVELOPER_TOKEN is not a JWT (ADR 0131)"
            }
            return MusicSettings(host, client, token, token?.let(::expiryOf))
        }

        /** The token's `exp` claim, or null where it states none; a payload that is not JSON refuses the boot. */
        private fun expiryOf(token: String): Instant? {
            val payload = runCatching { Base64.getUrlDecoder().decode(token.split('.')[1]) }
                .getOrElse { throw IllegalArgumentException("VEYQ_MUSIC_APPLE_DEVELOPER_TOKEN's payload is not base64url (ADR 0131)") }
            val claims = runCatching { ObjectMapper().readTree(payload) }
                .getOrElse { throw IllegalArgumentException("VEYQ_MUSIC_APPLE_DEVELOPER_TOKEN's payload is not JSON (ADR 0131)") }
            return claims.path("exp").takeIf { it.isNumber }?.let { Instant.ofEpochSecond(it.asLong()) }
        }
    }
}

/** The production [DeploymentCapabilities]: the environment's `veyq.music.*`, read and checked at boot — only while the
 *  TEMPORARY release flag `veyq.music.released` is on (ADR 0131 decision 8); dark, every consumer sees no music host. */
@ApplicationScoped
class MusicDeployment(config: VeyqConfig) : DeploymentCapabilities {
    /** The backend's ONE read of the music release flag (musicReleased, ADR 0131 decision 8): the settings below and the
     *  setup status's `musicReleased`, which the web's one helper reads, are its only users. */
    val released: Boolean = config.music().released()

    val settings: MusicSettings = config.music().let {
        MusicSettings.deployed(released, { it.domain().orElse(null) }, { it.spotifyClientId().orElse(null) }, { it.appleDeveloperToken().orElse(null) })
    }

    init {
        if (!released && config.music().domain().orElse("").isNotBlank()) {
            log.info("music: VEYQ_MUSIC_DOMAIN is set but VEYQ_MUSIC_RELEASED is not true, background music stays off (ADR 0131 decision 8)")
        }
        settings.appleExpiry?.let { expiry ->
            if (!Instant.now().isBefore(expiry)) log.warnf("music: the Apple developer token expired at %s, Apple Music is off (ADR 0131)", expiry)
        }
    }

    override fun unconfigured(): Set<String> = settings.unconfigured(Instant.now())

    /** The music host, or null without one: the app shell serves the frame there and nothing else. */
    val domain: String? get() = settings.domain

    private companion object {
        val log: Logger = Logger.getLogger(MusicDeployment::class.java)
    }
}
