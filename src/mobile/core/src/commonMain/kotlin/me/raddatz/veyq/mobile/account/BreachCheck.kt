package me.raddatz.veyq.mobile.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.raddatz.veyq.mobile.session.OutsideHttp
import me.raddatz.veyq.mobile.session.PasswordChange

/**
 * The breached-password check of a password-choosing flow (ADR 0151 decision 5, security-model § the breached-password
 * check): the web's `useBreachCheck` and `pwned.ts`, ported. Automatic, advisory, never while typing:
 *
 * - [check] is bound to the new password's field being left — the moment a person treats the password as done. One query
 *   per distinct finished password: a second [check] of the same one asks nothing again (and one in flight is shared), a
 *   password under [PasswordChange.MIN_PASSWORD_LENGTH] is never sent.
 * - [edited] on any edit: the verdict, and any acceptance of it, was about another password.
 * - [proceed] is the submit's guard, run by the flow before its own work: a password edited since the last check is
 *   checked once here; clean, unavailable or accepted go ahead, a hit stops until [useAnyway].
 *
 * Only the first five hex digits of the password's SHA-1 leave the device, to the instance's configured range service
 * ([address], `core.breached-password`'s `serviceUrl`, read once) through [http] — the core's door to a third party, no
 * bearer — with `Add-Padding: true`; the suffix is matched here. A failure or a non-2xx is [BreachKind.UNAVAILABLE], never
 * zero. No address: no request and no notice, and nothing is stopped.
 */
class BreachCheck(private val http: OutsideHttp?, private val address: suspend () -> String?) {
    enum class BreachKind { IDLE, CHECKING, CLEAN, HIT, UNAVAILABLE, ACCEPTED }

    /**
     * Where the check stands for the password last checked: [kind], and the breaches [count] for [BreachKind.HIT] and
     * [BreachKind.ACCEPTED]. The password it speaks about and the query in flight ride along for the check's own bookkeeping,
     * never serialized and never printed.
     */
    @Serializable
    data class BreachState(
        val kind: BreachKind = BreachKind.IDLE,
        val count: Int = 0,
        @Transient internal val about: String? = null,
        @Transient internal val query: CompletableDeferred<Int?>? = null,
    ) {
        override fun toString() = "BreachState(kind=$kind, count=$count)"
    }

    private val _state = MutableStateFlow(BreachState())
    val state: StateFlow<BreachState> = _state.asStateFlow()
    val current: BreachState get() = _state.value

    private val loading = Mutex()
    private var loaded = false
    private var url: String? = null

    /** The range service's address, read once (the flow's open); null where the instance runs no check or the read failed. */
    suspend fun load(): String? = loading.withLock {
        if (!loaded) {
            url = if (http == null) null else try {
                address()?.takeIf { it.isNotBlank() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            loaded = true
        }
        url
    }

    /** The new password's field was left with [password] in it: checked once, a query for it in flight shared. */
    suspend fun check(password: String) {
        val range = load() ?: return
        if (password.length < PasswordChange.MIN_PASSWORD_LENGTH) return
        var mine: CompletableDeferred<Int?>? = null
        var shared: CompletableDeferred<Int?>? = null
        _state.update { s ->
            if (password == s.about) {
                mine = null
                shared = s.query
                s
            } else {
                val q = CompletableDeferred<Int?>()
                mine = q
                shared = null
                BreachState(BreachKind.CHECKING, 0, password, q)
            }
        }
        val query = mine
        if (query == null) {
            shared?.await()
            return
        }
        val count = try {
            count(password, range)
        } catch (e: CancellationException) {
            _state.update { s -> if (s.query === query) BreachState() else s }
            query.complete(null)
            throw e
        }
        // edited while the query was in flight: the answer is about a password no longer in the field
        _state.update { s ->
            if (s.query !== query) s
            else when {
                count == null -> BreachState(BreachKind.UNAVAILABLE, 0, s.about)
                count == 0 -> BreachState(BreachKind.CLEAN, 0, s.about)
                else -> BreachState(BreachKind.HIT, count, s.about)
            }
        }
        query.complete(count)
    }

    /** Any edit of the new password: back to [BreachKind.IDLE], the acceptance gone, a query in flight disregarded. Sends nothing. */
    fun edited() {
        _state.value = BreachState()
    }

    /** *Use it anyway*: a hit accepted for this password ([on]), or the acceptance taken back. Means nothing without a hit. */
    fun useAnyway(on: Boolean) {
        _state.update { s ->
            when {
                on && s.kind == BreachKind.HIT -> s.copy(kind = BreachKind.ACCEPTED)
                !on && s.kind == BreachKind.ACCEPTED -> s.copy(kind = BreachKind.HIT)
                else -> s
            }
        }
    }

    /**
     * The submit's guard for [password]: whether the flow may go ahead — clean, unavailable or accepted; a hit (or a
     * password edited while its check was in flight) stops it. True where the instance runs no check.
     */
    suspend fun proceed(password: String): Boolean {
        if (load() == null) return true
        check(password)
        val s = current
        if (s.about != password) return false
        return s.kind == BreachKind.CLEAN || s.kind == BreachKind.UNAVAILABLE || s.kind == BreachKind.ACCEPTED
    }

    /** The flow closed: the password it spoke about is dropped. */
    fun close() {
        _state.value = BreachState()
    }

    private suspend fun count(password: String, range: String): Int? {
        val hash = Sha1.hexUpper(password)
        val answer = http?.get(range + hash.substring(0, 5), listOf(PADDING to "true")) ?: return null
        if (answer.status !in 200..299) return null
        return countIn(hash, answer.body.decodeToString())
    }

    companion object {
        /** The capability whose `serviceUrl` is the range service's address (instance-only, ADR 0088 decision 7). */
        const val CAPABILITY = "core.breached-password"

        /** The range service's padding request: the answer's length tells no observer which prefix was asked. */
        const val PADDING = "Add-Padding"

        /** A flow with no check: no address, nothing asked, nothing stopped. */
        fun none(): BreachCheck = BreachCheck(null) { null }

        /**
         * The check of [account]'s instance: the address its manifest serves (`getSetupStatus`, the instance's own — the
         * tenant's set adds nothing), asked through the account's `outside` client, never its bearer one.
         */
        fun of(account: me.raddatz.veyq.mobile.session.VeyqAccount): BreachCheck =
            BreachCheck(OutsideHttp(account.outside, account.server)) {
                account.call { me.raddatz.veyq.mobile.api.apis.SetupApi(account.server, account.http).getSetupStatus() }
                    .capabilities.firstOrNull { it.id == CAPABILITY }?.serviceUrl
            }

        /** The full upper-case SHA-1 and a range answer (`SUFFIX:COUNT` per line) → the breaches (0 if absent) — the
         *  web's `breachCountFromRange`. A padding row has count 0 and so never hits. */
        internal fun countIn(hashHexUpper: String, body: String): Int {
            val suffix = hashHexUpper.substring(5)
            for (line in body.split('\n')) {
                val i = line.indexOf(':')
                if (i > 0 && line.substring(0, i).trim().uppercase() == suffix) return line.substring(i + 1).trim().toIntOrNull() ?: 0
            }
            return 0
        }
    }
}
