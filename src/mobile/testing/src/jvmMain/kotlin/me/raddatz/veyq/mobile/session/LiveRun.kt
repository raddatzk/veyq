package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Every device a live case (`VEYQ_LIVE`) signs in with, taken off the account again when the case ends — passed, failed
 * or timed out. A case gets its devices from here ([device], [store]) or hands over the store it built itself ([track],
 * the bridge's host store), and calls [removeAll] from its `@AfterTest`; [removeAll] signs in once more on each store,
 * as that very device, and [VeyqAccount.removeThisDevice] deletes the wraps it holds (DEVICE, DEVICE_KEY), revokes its
 * session families and takes its entry out of the register. A store nothing signed in with is signed in and removed all
 * the same: it leaves nothing either.
 *
 * Without it every live run left a register entry and a session family behind, and a wrap wherever it trusted the
 * device: measured on the dev instance 2026-11-13, 20 DEVICE + 20 DEVICE_KEY wraps (the server's limit per method,
 * `409 too_many_devices` from then on), 653 session families, and a register (`MAX_DEVICES` = 32) full of test names.
 *
 * [who] is the prefix of the account's credentials in `.run/.env` (or `VEYQ_ENV_FILE`'s): `VEYQ` for the seeded
 * principal, `VEYQ_USER2` for the seeded member; [envFile] names another account's env file (a throwaway one). A
 * throwaway's case builds one per account over the throwaway's file (`LiveRun(who, t.file)`), never the bare
 * `LiveRun()`, whose [removeAll] would sign the seeded principal in with the throwaway's stores (`ThrowawayLiveRunTest`).
 *
 * Every sign-in — the case's and [removeAll]'s — spends the login challenge and verify against the instance's per-IP
 * budget (`veyq.ratelimit.auth.*`, 60 a minute by default, ADR 0051), which every browser, agent and live run on this
 * machine shares. A module's live suite comes close to it on its own (measured 2026-11-23: `:apps:memories` run twice
 * back to back failed with `SignInFailure: challenge 429` / `verify 429`, four cases the second time; each throwaway case
 * alone, a minute apart, passed). So every live client is [LiveHttp]'s, which sits out the `Retry-After` of such a 429 up to a
 * bound (inbox #1191); a 429 that still reaches a case is a budget spent past that bound, never the case's defect.
 */
class LiveRun(private val who: String = "VEYQ", private val envFile: File? = null) {
    private val p = BouncyCastlePrimitives
    private val stores = ConcurrentLinkedQueue<DeviceStore>()

    /** The origin of the server under test, or null: the case is skipped. */
    val origin: String? = System.getenv("VEYQ_LIVE")

    /** [envFile], else `.run/.env` of this checkout or `VEYQ_ENV_FILE`'s (a worktree has none). Never printed. */
    val env: Map<String, String> by lazy {
        (envFile ?: System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")).readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
    }

    /** A device in memory, taken off the account by [removeAll]. */
    fun device(label: String = "live test device", keys: DeviceKeys = SoftwareDeviceKeys(p)): ThisDevice = ThisDevice(store(), keys, label, p)

    /** A device store in memory, safe from any thread, taken off the account by [removeAll]. */
    fun store(): DeviceStore = track(MapStore(ConcurrentHashMap()))

    /** [store], taken off the account by [removeAll]. */
    fun <S : DeviceStore> track(store: S): S = store.also { stores += it }

    /** The map a host keeps its device store in (the bridge's), taken off the account by [removeAll]; once however often
     *  a case hands it over (each bridge it starts over the same map is the same device). */
    @Synchronized
    fun <M : MutableMap<String, String>> track(kept: M): M = kept.also {
        if (stores.none { s -> s is MapStore && s.values === kept }) stores += MapStore(it)
    }

    /**
     * Each tracked device signed in once more and removed ([VeyqAccount.removeThisDevice]), then forgotten here. Throws
     * after trying every one when any could not be removed: a case that leaves something behind does not pass.
     */
    fun removeAll() {
        val origin = origin ?: return stores.clear()
        val failed = mutableListOf<String>()
        runBlocking {
            while (true) {
                val store = stores.poll() ?: break
                try {
                    VeyqAccount.signIn(
                        p, origin, env.getValue("${who}_EMAIL"), env.getValue("${who}_PASSWORD"), env.getValue("${who}_SECRET_KEY"),
                        ThisDevice(store, SoftwareDeviceKeys(p), "live test device", p),
                    ) { block -> LiveHttp.client(block) }.removeThisDevice()
                } catch (e: Exception) {
                    failed += "${e::class.simpleName}: ${e.message}"
                }
            }
        }
        if (failed.isNotEmpty()) throw AssertionError("live devices not taken off the account: $failed")
    }

    private class MapStore(val values: MutableMap<String, String>) : DeviceStore {
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }
}
