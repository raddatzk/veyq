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
    fun decide(setup: SetupStatus?, speaks: (Int) -> Boolean = ClientWindow::speaks): Refusal? {
        if (setup == null) return Refusal.UNREACHABLE
        if (!speaks(setup.version.major)) return Refusal.OTHER_VERSION
        if (setup.capabilities.none { it.id == PUBLIC_SHARES }) return Refusal.NO_LINKS
        return null
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
