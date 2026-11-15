package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.api.apis.AccountEmailApi
import me.raddatz.veyq.mobile.api.apis.SessionApi
import me.raddatz.veyq.mobile.api.apis.SetupApi
import me.raddatz.veyq.mobile.api.infrastructure.HttpResponse
import me.raddatz.veyq.mobile.api.models.MasterKeyWrap
import me.raddatz.veyq.mobile.api.models.SessionFamily
import me.raddatz.veyq.mobile.api.apis.AccountApi
import me.raddatz.veyq.mobile.api.apis.AccountWrapsApi
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.MsgKey

/**
 * One signed-in, unlocked account's Settings page (ADR 0136 decision 2; `uc.account.sessions`): the address it
 * signs in with, this device (its name in the account's device register, how it stays signed in), every active session
 * family of the account named from the register, the devices with a saved key and no session, and the entry to "Add a
 * device" while the instance links devices (`core.device-linking`). The web's `SessionsPanel` is the reference, call for
 * call, and `uc.account.sessions` the behaviour:
 *
 *  - A family carries no name on the server (ADR 0004): names come from the Master-Key-sealed register, joined here; a
 *    family the register does not know shows its dates. Each [refresh] reconciles the register against the two lists
 *    the server is authoritative for (write moment 3) before it reads the names back.
 *  - A revoke never takes key material (ADR 0058): [signOut] ends a session, [signOutAndRemoveKey] ends it and then
 *    removes that device's saved key — the revoke first, since it is what stops someone online now; a removal that
 *    fails after it is told ([Msg.ACCOUNT_SETTINGS_ESCALATE_HALF_FAILED], E5) and the device stays under the devices
 *    with a saved key for the retry. Signing out this device's own family is a sign-out here (E2); a family already gone
 *    is refreshed away without a word (E1, the server's revoke answers an unknown id as a known one).
 *  - "Remove every saved key except this device's" is defined by exclusion (ADR 0058 §5): every served device wrap but
 *    the one this device's own record holds.
 *  - The trust level is the one "Manage servers" shows ([SessionModel.managed]), changed through the same calls.
 *
 * The page is reached from the drawer of an unlocked account; a locked device's "sign every device out" (E3) is the
 * sign-in screen's. A UI observes [state] (or [watch]), calls [refresh] when it opens, and draws; it holds no rule.
 */
class AccountSettings<A : Connection, T : Any>(
    private val door: Door,
    private val here: Here<A, T>,
    /** The account's own address, shown until the server's copy read. */
    email: String,
    /** The reader's decimal sign (the platform's locale data), as the limit's free size field writes its amount. */
    private val separator: Char = '.',
    private val now: () -> Long,
) {
    /**
     * The account's server calls and this device's part in them, behind one seam so a test can play the server — the
     * bridge's tests too, which is why it is public; the device register stays the core's ([register]: a door without one
     * shows no names and renames nothing, as a register that does not open).
     */
    abstract class Door {
        /** This device's id in the register ([ThisDevice.id]) and the label it names itself with until renamed. */
        abstract val deviceId: String
        abstract val fallbackLabel: String

        /** The wrap this device's own trust record holds, if any: the one "every other saved key" leaves. */
        abstract fun myWrapId(): String?
        abstract suspend fun email(): Pair<String, Boolean>
        abstract suspend fun families(): List<SessionFamily>
        abstract suspend fun revoke(family: String)
        abstract suspend fun revokeOthers()

        /** Every device wrap (DEVICE, DEVICE_KEY) the server serves the account; throws when it could not be read. */
        abstract suspend fun savedKeys(): List<String>
        abstract suspend fun removeSavedKey(wrap: String)

        /** The instance's active capabilities (`GET /api/setup`'s manifest). */
        abstract suspend fun capabilities(): Set<String>

        /** What this account's offline store on this device holds (ADR 0140 decision 10); null where it keeps none. */
        open suspend fun storage(): Storage? = null

        /** Why this device keeps no offline store of the account where it could (no trust in it); null otherwise. */
        open fun storageUnavailable(): Msg? = null

        /** The limit set to [bytes] (0: the automatic tier off); lowering it evicts at once. */
        open suspend fun setLimit(bytes: Long) {}

        /** "Clear cache": the automatic tier dropped, the pins kept. */
        open suspend fun clearCache() {}

        /** "Remove all offline content": every pin and everything kept. */
        open suspend fun removeAllOffline() {}

        /** The pin of [node] taken off. */
        open suspend fun unpin(node: String) {}

        /** Each change of the pins or their fetches: what the section reads anew while it is shown ([followStorage]). */
        open val storageChanges: kotlinx.coroutines.flow.Flow<Unit> get() = kotlinx.coroutines.flow.emptyFlow()
        internal open fun register(): DeviceRegisterStore = throw UnsupportedOperationException("this door keeps no device register")
    }

    /** This device's trust in the account and its sign-out, as [SessionModel] holds them ("Manage servers"' source). */
    interface Here<A : Connection, T : Any> {
        fun trust(): Trust
        suspend fun stay(level: StaySignedIn): TrustChange
        suspend fun trustWithPassword(password: String): TrustChange
        suspend fun stopTrusting()
        suspend fun signOut(): SessionModel.SignedOut<A, T>
    }

    /** How this device trusts the account ([method] null: not at all), and the levels it may move to. */
    @Serializable
    data class Trust(val method: TrustedDevice.Method?, val level: StaySignedIn?, val levels: List<StaySignedIn>)

    /** This device on the page: its name, the family it is signed in with (once the server marked it), its trust. */
    @Serializable
    data class Device(val label: String, val familyId: String?, val trust: Trust)

    /**
     * One active session family: when it was created and last renewed (epoch milliseconds; null where the server sent
     * none), whether it is this device's, the register's name for its device (null: the dates show), and whether "Sign
     * out and remove saved key" is offered — only where the register joins it to another device's served wrap.
     */
    @Serializable
    data class Family(
        val id: String,
        val createdAt: Long?,
        val lastUsedAt: Long?,
        val current: Boolean,
        val label: String?,
        val removesKey: Boolean,
    )

    /**
     * "Storage on this device" (ADR 0140 decision 10), in bytes: the [limit] the person set (0: the automatic tier off) and
     * as its free size field shows it ([field]), what the [automatic] tier and the pinned items use ([pinned]), the
     * volume's [free] space (null where the system does not say), and the pinned [items], each removable — named from the
     * tree the app holds, never from the store.
     */
    @Serializable
    data class Storage(
        val limit: Long,
        val automatic: Long,
        val pinned: Long,
        val free: Long?,
        val field: me.raddatz.veyq.mobile.offline.LimitField = me.raddatz.veyq.mobile.offline.OfflineShown.field(limit),
        val items: List<me.raddatz.veyq.mobile.offline.OfflineItem> = emptyList(),
    )

    /** A device the register knows that holds a served saved key and no session: removable ([removeSavedKey]). */
    @Serializable
    data class SavedKeyDevice(val deviceId: String, val label: String)

    @Serializable
    data class State(
        val email: String,
        /** Whether the server holds the address as confirmed; null until read. */
        val emailConfirmed: Boolean? = null,
        val device: Device,
        /** Every active family, this device's first; null until the list first read. */
        val families: List<Family>? = null,
        val savedKeyDevices: List<SavedKeyDevice> = emptyList(),
        /** Whether "Remove every saved key except this device's" has anything to remove. */
        val otherSavedKeys: Boolean = false,
        /** Whether "Add a device" is offered: the instance has `core.device-linking`. */
        val addDevice: Boolean = false,
        val working: Boolean = false,
        /** Why the last action, or the list, did not come through. */
        @Serializable(with = MsgKey::class) val refusal: Msg? = null,
        /** Why the device names are missing: the register refused (ADR 0028 decision 7); every row still acts. */
        @Serializable(with = MsgKey::class) val registerRefusal: Msg? = null,
        /** This account's offline store on this device; null where it keeps none (a device without trust, no directory). */
        val storage: Storage? = null,
        /** Why the section keeps nothing here ([Msg.ACCOUNT_SETTINGS_STORAGE_NO_TRUST]); null where it does or is not shown. */
        @Serializable(with = MsgKey::class) val storageUnavailable: Msg? = null,
        /** "Remove all offline content" asked ([askRemoveAllOffline]): its question shows until confirmed or cancelled. */
        val askingRemoveAll: Boolean = false,
    ) {
        /** Whether "Sign out every other" has anything to end. */
        val hasOthers: Boolean get() = families.orEmpty().any { !it.current }
    }

    private val _state = MutableStateFlow(State(email = email, device = Device(door.fallbackLabel, null, here.trust())))
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    class Watch internal constructor(private val job: kotlinx.coroutines.Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch = Watch(CoroutineScope(Dispatchers.Default).launch { state.collect(listener) })

    private val acting = Mutex()

    /** The register's view of the last read: what the wrap-bearing actions act on. */
    private var view = EMPTY_VIEW

    /** The served device wraps but this device's own, as last read: what [removeEveryOtherSavedKey] removes. */
    private var others: List<String> = emptyList()

    /** Everything read anew: the address, the families, the register (reconciled, then read), the capability. */
    suspend fun refresh() = act { load() }

    /** This device's name in the register — its own entry only (single writer). A blank name is no name: nothing. */
    suspend fun rename(label: String) {
        val name = label.trim()
        if (name.isEmpty()) return
        act {
            attempt { door.register().rename(door.deviceId, name, now()) }.onFailure { refuse(registerWords(it) ?: Msg.ACCOUNT_SETTINGS_RENAME_FAILED) }
            load(keepRefusal = true)
        }
    }

    /**
     * Ends session family [familyId]. This device's own is a sign-out here (E2), answered with what it left; any other
     * is revoked and the list read anew — one already gone simply drops out (E1).
     */
    suspend fun signOut(familyId: String): SessionModel.SignedOut<A, T>? {
        if (current.families?.firstOrNull { it.id == familyId }?.current == true) {
            return act {
                attempt { door.revoke(familyId) }
                here.signOut()
            }
        }
        act {
            attempt { door.revoke(familyId) }.onFailure { refuse(Msg.ACCOUNT_SETTINGS_REVOKE_FAILED) }
            load(keepRefusal = true)
        }
        return null
    }

    /** Ends every family of the account but this device's; this one stays signed in. */
    suspend fun signOutOthers() = act {
        attempt { door.revokeOthers() }.onFailure { refuse(Msg.ACCOUNT_SETTINGS_REVOKE_FAILED) }
        load(keepRefusal = true)
    }

    /**
     * ADR 0058's escalation for family [familyId]: revoked first, then its device's saved keys removed. A revoke that
     * fails touches no key; a removal that fails after it is told, and the device is listed under the devices with a
     * saved key for the retry (E5). Not offered for this device's own family, nor where the register joins no saved key.
     */
    suspend fun signOutAndRemoveKey(familyId: String) = act {
        val wraps = view.wrapsOfFamily[familyId].orEmpty()
        val family = current.families?.firstOrNull { it.id == familyId }
        if (wraps.isEmpty() || family == null || family.current) return@act
        if (attempt { door.revoke(familyId) }.isFailure) {
            refuse(Msg.ACCOUNT_SETTINGS_REVOKE_FAILED)
            return@act
        }
        attempt { wraps.forEach { door.removeSavedKey(it) } }.onFailure { refuse(Msg.ACCOUNT_SETTINGS_ESCALATE_HALF_FAILED) }
        load(keepRefusal = true)
    }

    /** Removes the saved keys of [deviceId], a device with a saved key and no session. */
    suspend fun removeSavedKey(deviceId: String) = act {
        val wraps = view.savedKeyDevices[deviceId]?.second ?: return@act
        attempt { wraps.forEach { door.removeSavedKey(it) } }.onFailure { refuse(Msg.ACCOUNT_SETTINGS_KEY_REMOVE_FAILED) }
        load(keepRefusal = true)
    }

    /** Removes every saved key the server serves the account except this device's own (ADR 0058 §5). */
    suspend fun removeEveryOtherSavedKey() = act {
        attempt { others.forEach { door.removeSavedKey(it) } }.onFailure { refuse(Msg.ACCOUNT_SETTINGS_KEY_REMOVE_FAILED) }
        load(keepRefusal = true)
    }

    // ---- "Storage on this device" (ADR 0140 decision 10)

    /**
     * The limit typed in the free size field: [amount] in [unit] (`OfflineShown.parse`: a positive number, a point or a
     * comma as its decimal sign); anything else is refused with its word and nothing changes. Lowering it evicts at once.
     */
    suspend fun setOfflineLimit(amount: String, unit: me.raddatz.veyq.mobile.offline.LimitUnit) = act {
        val bytes = me.raddatz.veyq.mobile.offline.OfflineShown.parse(amount, unit)
        if (bytes == null) {
            refuse(Msg.ACCOUNT_SETTINGS_STORAGE_LIMIT_INVALID)
            return@act
        }
        attempt { door.setLimit(bytes) }.onFailure { refuse(Msg.ACCOUNT_SETTINGS_LOAD_FAILED) }
        loadStorage()
    }

    /** The limit "Off": the automatic tier keeps nothing (what it held goes at once); pins still work. */
    suspend fun turnOfflineLimitOff() = act {
        attempt { door.setLimit(0) }.onFailure { refuse(Msg.ACCOUNT_SETTINGS_LOAD_FAILED) }
        loadStorage()
    }

    /** "Clear cache": what was kept of content viewed goes; the items available offline stay. */
    suspend fun clearOfflineCache() = act {
        attempt { door.clearCache() }.onFailure { refuse(Msg.ACCOUNT_SETTINGS_LOAD_FAILED) }
        loadStorage()
    }

    /** Item [node] no longer available offline (its row's Remove). */
    suspend fun unpinOffline(node: String) = act {
        attempt { door.unpin(node) }.onFailure { refuse(Msg.ACCOUNT_SETTINGS_LOAD_FAILED) }
        loadStorage()
    }

    /** "Remove all offline content" asked: nothing goes until [removeAllOffline]. */
    fun askRemoveAllOffline() {
        if (current.storage != null) _state.value = current.copy(askingRemoveAll = true)
    }

    fun cancelRemoveAllOffline() {
        _state.value = current.copy(askingRemoveAll = false)
    }

    /** What [askRemoveAllOffline] asked: every pin and everything kept goes. Nothing without the question asked first. */
    suspend fun removeAllOffline() = act {
        if (!current.askingRemoveAll) return@act
        _state.value = current.copy(askingRemoveAll = false)
        attempt { door.removeAllOffline() }.onFailure { refuse(Msg.ACCOUNT_SETTINGS_LOAD_FAILED) }
        loadStorage()
    }

    /**
     * The section read anew at each change of the pins or their fetches, while the page is shown (a host runs it as the
     * page's own job, as a gallery's live poll; it ends when that is cancelled).
     */
    suspend fun followStorage() {
        door.storageChanges.collect { loadStorage() }
    }

    /**
     * [followStorage] as a job the host stops ([Watch.stop]) when the page closes: Swift cannot cancel a suspend call it
     * awaits, so the Apple app holds this instead.
     */
    fun startFollowStorage(): Watch = Watch(CoroutineScope(Dispatchers.Default).launch { followStorage() })

    private suspend fun loadStorage() {
        attempt { door.storage() }.onSuccess {
            val storage = it?.copy(field = me.raddatz.veyq.mobile.offline.OfflineShown.field(it.limit, separator))
            _state.value = current.copy(storage = storage, storageUnavailable = if (it == null) door.storageUnavailable() else null)
        }
    }

    /** Staying signed in at [level], as "Manage servers" changes it ([SessionModel.stay]). */
    suspend fun stay(level: StaySignedIn): TrustChange = act { here.stay(level).also { load() } }

    /** Back to the password trust, as "Manage servers" changes it ([SessionModel.trustWithPassword]). */
    suspend fun trustWithPassword(password: String): TrustChange = act { here.trustWithPassword(password).also { load() } }

    /** "Stop trusting this device" ([SessionModel.forgetDevice]): its saved key goes, the session stays. */
    suspend fun stopTrusting() = act {
        here.stopTrusting()
        load()
    }

    /** Signs this account out on this device ([SessionModel.signOut]); the answer says what the app shows next. */
    suspend fun signOutHere(): SessionModel.SignedOut<A, T> = act { here.signOut() }

    private suspend fun <R> act(block: suspend () -> R): R = acting.withLock {
        _state.value = current.copy(working = true, refusal = null)
        try {
            block()
        } finally {
            _state.value = current.copy(working = false)
        }
    }

    private fun refuse(why: Msg) {
        _state.value = current.copy(refusal = why)
    }

    /** The web's loader: the list first; the register's half after it, never able to take the list down (E4). */
    private suspend fun load(keepRefusal: Boolean = false) {
        if (!keepRefusal) _state.value = current.copy(refusal = null)
        loadStorage()
        attempt { door.email() }.onSuccess { (address, confirmed) -> _state.value = current.copy(email = address, emailConfirmed = confirmed) }
        attempt { door.capabilities() }.onSuccess { _state.value = current.copy(addDevice = DEVICE_LINKING in it) }
        val families = attempt { door.families() }.getOrElse {
            if (current.refusal == null) refuse(Msg.ACCOUNT_SETTINGS_LOAD_FAILED)
            _state.value = current.copy(device = current.device.copy(trust = here.trust()))
            return
        }
        val live = families.map { it.id }
        val currentFamily = families.firstOrNull { it.current == true }?.id
        // a list the server did not answer is no claim that none exists: no wrap row is pruned and no wrap action offered
        val served = attempt { door.savedKeys() }.getOrNull()
        val mine = door.myWrapId()
        others = served.orEmpty().filter { it != mine }
        var label = current.device.label
        var registerRefusal: Msg? = null
        view = attempt {
            val register = door.register()
            register.reconcile(live, served, door.deviceId, door.fallbackLabel, currentFamily, now())
            val plain = register.read()
            plain.devices[door.deviceId]?.let { label = it.label }
            DeviceRegisterStore.view(plain, live, served, door.deviceId)
        }.getOrElse {
            registerRefusal = registerWords(it)
            EMPTY_VIEW
        }
        _state.value = current.copy(
            device = Device(label, currentFamily, here.trust()),
            families = families.map { f ->
                Family(
                    id = f.id,
                    createdAt = f.createdAt?.let(::epochMs),
                    lastUsedAt = f.lastUsedAt?.let(::epochMs),
                    current = f.current == true,
                    label = view.names[f.id],
                    removesKey = f.current != true && !view.wrapsOfFamily[f.id].isNullOrEmpty(),
                )
            }.sortedByDescending { it.current },
            savedKeyDevices = view.savedKeyDevices.map { (id, d) -> SavedKeyDevice(id, d.first) }.sortedBy { it.label },
            otherSavedKeys = others.isNotEmpty(),
            registerRefusal = registerRefusal,
        )
    }

    /** [block]'s outcome, a cancellation passed on. */
    private inline fun <R> attempt(block: () -> R): Result<R> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        /** The instance links devices (ADR 0059): the capability the "Add a device" entry needs. */
        const val DEVICE_LINKING = "core.device-linking"

        private val EMPTY_VIEW = DeviceRegisterStore.View(emptyMap(), emptyMap(), emptyMap())

        private fun registerWords(e: Throwable): Msg? = when ((e as? TrustStateRefused)?.code) {
            TrustStateRefused.Code.ROLLED_BACK -> Msg.ACCOUNT_SETTINGS_REGISTER_ROLLED_BACK
            TrustStateRefused.Code.UNREADABLE -> Msg.ACCOUNT_SETTINGS_REGISTER_UNREADABLE
            null -> null
        }

        @OptIn(kotlin.time.ExperimentalTime::class)
        private fun epochMs(iso: String): Long? = runCatching { kotlin.time.Instant.parse(iso).toEpochMilliseconds() }.getOrNull()

        /**
         * [account]'s Settings, its trust and sign-out those of [session] ("Manage servers"' model); [separator] the
         * reader's decimal sign (the platform's locale data, as the entry editor takes it).
         */
        @OptIn(kotlin.time.ExperimentalTime::class)
        fun of(session: SessionModel<VeyqAccount, TenantSession>, account: VeyqAccount, separator: Char): AccountSettings<VeyqAccount, TenantSession> =
            AccountSettings(Over(account), Held(session, account), account.email, separator) { kotlin.time.Clock.System.now().toEpochMilliseconds() }
    }

    /** [Here] over the [SessionModel] that holds [account]. */
    internal class Held<A : Connection, T : Any>(private val session: SessionModel<A, T>, private val account: A) : Here<A, T> {
        override fun trust(): Trust {
            val m = session.managed().firstOrNull { it.account == account }
            return Trust(m?.trusted, m?.level, m?.levels ?: StaySignedIn.entries.filter { it in session.staySignedIn })
        }
        override suspend fun stay(level: StaySignedIn) = session.stay(account, level)
        override suspend fun trustWithPassword(password: String) = session.trustWithPassword(account, password)
        override suspend fun stopTrusting() = session.forgetDevice(account)
        override suspend fun signOut() = session.signOut(account)
    }

    /** [Door] over a signed-in [VeyqAccount]: the generated client's calls, each refreshed once on a `401`. */
    internal class Over(private val account: VeyqAccount) : Door() {
        override val deviceId: String get() = account.thisDevice.id
        override val fallbackLabel: String get() = account.thisDevice.label
        override fun myWrapId(): String? = account.trusted?.wrapId
        override suspend fun email() = account.call { AccountEmailApi(account.server, account.http).getAccountEmail() }.let { it.email to it.confirmed }
        override suspend fun families() = account.call { SessionApi(account.server, account.http).listSessionFamilies() }
        override suspend fun revoke(family: String) = done { SessionApi(account.server, account.http).revokeSessionFamily(family) }
        override suspend fun revokeOthers() = done { SessionApi(account.server, account.http).revokeOtherSessionFamilies() }
        override suspend fun savedKeys() = account.call { AccountApi(account.server, account.http).getAccountKeyMaterial() }.wraps
            .filter { it.method == MasterKeyWrap.Method.DEVICE || it.method == MasterKeyWrap.Method.DEVICE_KEY }
            .mapNotNull { it.id }
        override suspend fun removeSavedKey(wrap: String) = done { AccountWrapsApi(account.server, account.http).deleteAccountWrap(wrap) }
        override suspend fun capabilities() = account.call { SetupApi(account.server, account.http).getSetupStatus() }.capabilities.map { it.id }.toSet()
        override fun register() = account.register()
        override suspend fun storage(): Storage? {
            val o = account.offline ?: return null
            val u = o.usage()
            val pins = account.offlinePins
            val bytes = o.pinnedBytes()
            val progress = pins?.progress?.value.orEmpty()
            val items = pins?.pins?.value.orEmpty().map { p ->
                val status = me.raddatz.veyq.mobile.offline.OfflineShown.status(p.node, listOf(p), progress)
                me.raddatz.veyq.mobile.offline.OfflineItem(
                    p.tenant, p.scope, p.node, p.kind, me.raddatz.veyq.mobile.offline.OfflineShown.title(pins?.nameOf(p)),
                    status, me.raddatz.veyq.mobile.offline.OfflineShown.said(status, progress[p.node]), bytes[p.node] ?: 0L,
                )
            }
            return Storage(u.limit, u.automatic, u.pinned, u.free, items = items)
        }

        // the host named a directory, and this device keeps no trust in the account: nothing is kept (ADR 0140 decision 4)
        override fun storageUnavailable() =
            Msg.ACCOUNT_SETTINGS_STORAGE_NO_TRUST.takeIf { account.thisDevice.offlineRoot != null && account.trusted == null }
        override suspend fun setLimit(bytes: Long) { account.offline?.setLimit(bytes) }
        override suspend fun clearCache() { account.offline?.clearCache() }
        override suspend fun removeAllOffline() { account.offline?.removeAll() }
        override suspend fun unpin(node: String) { account.offlinePins?.unpin(node) }
        override val storageChanges get() = account.offlinePins?.changes ?: kotlinx.coroutines.flow.emptyFlow()

        /** A call answered with no body: anything but a success throws. */
        private suspend fun done(f: suspend () -> HttpResponse<Unit>) {
            val r = account.respond(f)
            if (!r.success) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "HTTP ${r.status}")
        }
    }
}
