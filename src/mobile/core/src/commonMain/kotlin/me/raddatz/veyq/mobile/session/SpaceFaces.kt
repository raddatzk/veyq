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
import me.raddatz.veyq.mobile.api.apis.ScopesApi
import me.raddatz.veyq.mobile.api.models.MyTenant
import me.raddatz.veyq.mobile.api.models.ScopeKind
import me.raddatz.veyq.mobile.api.models.ScopeSummary

/**
 * What the switcher and the drawer show of each space beside its name (the web's `AppSidebar.vue`): its picture, opened on
 * this device (uc.spaces.set-space-icon) — each tenant of the account under the caller's own grant on it (`openMyTenantIcon`),
 * each group of the open tenant under its key, or its own grant where it is not open (`listMyGroups`) — and the *Needs a
 * new lock* mark of a space whose compromise rotation needs an owner (`compromiseRotation`, served to an owner only).
 * Keyed by scope id: a tenant's own scope id is its tenant id, so one map serves the switcher's rows and the drawer's.
 *
 * When they are read is the core's rule: when the tenant opens ([load], started in the background by the host as the
 * avatars are), and after an icon's set or removal ([ScopeIcons]) or a *Start again* ([SpaceStatus.requeue]) on this
 * device. Anything that does not open leaves the initial; a read that fails keeps what was held. One per tenant session
 * ([TenantSession.faces]).
 */
class SpaceFaces internal constructor(private val reader: Reader) {
    /** What one read finds: the pictures by scope id and the marked scopes. */
    internal class Read(val pictures: Map<String, ByteArray>, val needsLock: Set<String>)

    internal fun interface Reader {
        /** The faces read now; null where the lists did not read (what was held stays). */
        suspend fun read(): Read?
    }

    /** The pictures and marks held, and their [round]: a number that moves with every read that changed them (the bridge's `ShellShown.spacePicturesRound`). */
    class Held(val pictures: Map<String, ByteArray>, val needsLock: Set<String>, val round: Int) {
        fun picture(scopeId: String): ByteArray? = pictures[scopeId]
        fun marked(scopeId: String): Boolean = scopeId in needsLock
    }

    private val _held = MutableStateFlow(Held(emptyMap(), emptySet(), 0))

    /** What the switcher and the drawer show now. */
    val held: StateFlow<Held> = _held.asStateFlow()

    /** A watch of [held] for a platform without flows: stopped with [stop]. */
    class Watch internal constructor(private val job: kotlinx.coroutines.Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with what is held now and with each change after (on a background thread). */
    fun watch(listener: (Held) -> Unit): Watch = Watch(CoroutineScope(Dispatchers.Default).launch { held.collect(listener) })

    private val reading = Mutex()

    /** Reads the faces anew; the round moves only where a picture or a mark changed. */
    @Throws(Exception::class)
    suspend fun load() = reading.withLock {
        val read = try {
            reader.read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return@withLock
        val was = _held.value
        if (same(was, read)) return@withLock
        _held.value = Held(read.pictures, read.needsLock, was.round + 1)
    }

    private fun same(was: Held, read: Read): Boolean =
        was.needsLock == read.needsLock && was.pictures.keys == read.pictures.keys &&
            was.pictures.all { (id, bytes) -> read.pictures[id]?.contentEquals(bytes) == true }

    internal companion object {
        fun of(tenant: TenantSession): SpaceFaces = SpaceFaces { read(tenant) }

        /** The tenant scope's row as a grant to open, from the switcher's `listMyTenants` row (the web's `openMyTenantIcon`). */
        fun summaryOf(t: MyTenant): ScopeSummary? {
            val wrapped = t.wrappedGrant ?: return null
            val epoch = t.grantedEpoch ?: return null
            return ScopeSummary(
                id = t.tenantId, kind = ScopeKind.TENANT,
                role = if (t.role == MyTenant.Role.OWNER) ScopeSummary.Role.OWNER else ScopeSummary.Role.MEMBER,
                tenantId = t.tenantId, nameEpoch = 0, iconEpoch = t.iconEpoch,
                // a grant behind the tenant's epoch holds a key no wrap is under any more: the icon does not open (the initial)
                currentEpoch = epoch, grantedEpoch = epoch, wrappedGrant = wrapped,
                sealedIcon = t.sealedIcon, wrappedIconValueKey = t.wrappedIconValueKey,
                granterUserId = t.granterUserId, grantSignature = t.grantSignature, granterIdentityPublicKey = t.granterIdentityPublicKey,
            )
        }

        private suspend fun read(tenant: TenantSession): Read? {
            val account = tenant.account
            val api = ScopesApi(tenant.base, account.http)
            val tenants = account.respond { api.listMyTenants() }.takeIf { it.success }?.body() ?: return null
            val scopes = account.respond { api.listMyScopes() }.takeIf { it.success }?.body() ?: return null
            val pictures = HashMap<String, ByteArray>()
            val marks = HashSet<String>()
            suspend fun open(s: ScopeSummary): ByteArray? {
                val sealed = s.sealedIcon ?: return null
                val held = tenant.scopeKey(s.id)
                if (held != null) return SpaceIcons.open(account.suite, s.id, held.second.toLong(), held.first, s.iconEpoch.toLong(), sealed, s.wrappedIconValueKey)
                val opened = try {
                    account.openOwnGrant(s, null, false) as? GrantOutcome.Opened
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                } ?: return null
                return try {
                    SpaceIcons.open(account.suite, s.id, s.grantedEpoch.toLong(), opened.key, s.iconEpoch.toLong(), sealed, s.wrappedIconValueKey)
                } finally {
                    opened.key.fill(0)
                }
            }
            for (t in tenants) {
                if (t.compromiseRotation != null) marks += t.tenantId
                // the open tenant's scope row first: its key is held; another tenant's own grant from its switcher row
                val s = scopes.firstOrNull { it.id == t.tenantId } ?: summaryOf(t)
                s?.let { open(it) }?.let { pictures[t.tenantId] = it }
            }
            for (g in scopes) {
                if (g.kind != ScopeKind.GROUP || g.deletedAt != null) continue
                if (g.compromiseRotation != null) marks += g.id
                open(g)?.let { pictures[g.id] = it }
            }
            return Read(pictures, marks)
        }
    }
}
