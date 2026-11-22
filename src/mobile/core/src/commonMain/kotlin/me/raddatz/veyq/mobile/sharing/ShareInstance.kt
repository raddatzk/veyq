package me.raddatz.veyq.mobile.sharing

import me.raddatz.veyq.mobile.api.apis.SetupApi
import me.raddatz.veyq.mobile.api.models.SetupStatus
import me.raddatz.veyq.mobile.session.ClientWindow

/**
 * Whether the app opens a link of an instance (ADR 0150 decision 2; the web's `shareInstance.ts` `embeddability`): the
 * app is a foreign client to **every** instance, its own too, so it reads the link's origin's `/api/setup` and opens only
 * where that instance answered as a veyq instance does (a version, a capability list), runs a major the app speaks
 * ([ClientWindow], the web's `EMBED_MAJORS` rule) and serves `core.public-shares`. `.embeddable` is not asked: it gates
 * another origin's page inside a web page (ADR 0121 decision 3), and the app is no web page. One pure function over
 * what was read ([decide]); the read ([read]) only fetches, and turns every failure into "no answer".
 */
object ShareInstance {
    const val PUBLIC_SHARES = "core.public-shares"
    const val EMBEDDABLE = "core.public-shares.embeddable"

    /** Why a link is not opened in the app — each its own line, with *Open in the browser* (decision 8). */
    enum class Refusal {
        /** No answer a veyq instance gives: the network, or not a veyq server. */
        UNREACHABLE,
        /** A major the app does not speak. */
        OTHER_VERSION,
        /** It serves no public links (`core.public-shares` off). */
        NO_LINKS,
    }

    /** Null where the app opens the instance's links; else why not. Fails closed on what it cannot read. */
    fun decide(setup: SetupStatus?, speaks: (Int) -> Boolean = ClientWindow::speaks): Refusal? =
        refusal(setup?.version?.major, setup?.capabilities?.map { it.id }, speaks)

    /**
     * The one reading of what an instance's `/api/setup` said ([major] and [capabilities] as read; null: no such answer), for
     * the app's open and the web page's embed alike: null where its links open; else why not. Held by
     * `contracts/platform/share-links/cases.v1.json` (`embed`'s `app`).
     */
    fun refusal(major: Int?, capabilities: List<String>?, speaks: (Int) -> Boolean): Refusal? = when {
        major == null || capabilities == null -> Refusal.UNREACHABLE
        !speaks(major) -> Refusal.OTHER_VERSION
        PUBLIC_SHARES !in capabilities -> Refusal.NO_LINKS
        else -> null
    }

    /**
     * Whether a web page shows another instance's share in place (ADR 0121 decision 4; the web's bookmark page): this
     * instance's own `core.share-embedding` ([on]) first, then [refusal], then `.embeddable` — a child is absent where its
     * parent is off (ADR 0088 decision 2), and both are read, so a leaf without its parent is refused too. Null where it
     * embeds; else why not, in the page's words (`switchedOff`, `unreachable`, `otherVersion`, `noLinks`,
     * `notEmbeddable`; `embed`'s `page` in the vectors).
     */
    fun embedding(on: Boolean, major: Int?, capabilities: List<String>?, speaks: (Int) -> Boolean): String? {
        if (!on) return "switchedOff"
        return when (refusal(major, capabilities, speaks)) {
            Refusal.UNREACHABLE -> "unreachable"
            Refusal.OTHER_VERSION -> "otherVersion"
            Refusal.NO_LINKS -> "noLinks"
            null -> if (EMBEDDABLE in capabilities!!) null else "notEmbeddable"
        }
    }

    /** The setup status of [client]'s instance, or null for any failure. */
    suspend fun read(client: GuestClient): SetupStatus? = try {
        val r = SetupApi(client.origin, client.http).getSetupStatus()
        if (r.success) r.body() else null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
