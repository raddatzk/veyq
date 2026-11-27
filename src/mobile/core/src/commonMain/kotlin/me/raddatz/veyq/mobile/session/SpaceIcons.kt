package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.CancellationException
import me.raddatz.veyq.mobile.api.apis.ScopesApi
import me.raddatz.veyq.mobile.api.models.SetScopeIconRequest
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.Pad
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.ValueKeys
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.media.WebpEncoder
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice

/**
 * A space's icon (uc.spaces.set-space-icon; ADR 0027, ADR 0115), the port of the web's `spaceIcon.ts` and of `scopes.ts`'
 * `setSpaceIcon` / `openMyTenantIcon` / `openGroupIcon`: the encoded picture is padded to the one constant [PADDED_LEN] and
 * sealed (`space-icon`, bound to the scope and its icon epoch) under a value key `K_v` minted fresh at every write, whose
 * wrap the scope key holds at its epoch (`value-key`, the address of `space-icon` in the SEAL schema). A rotation re-wraps
 * `K_v` and never touches the icon. Pinned by `v1.json#value_key` and `seal.sym['space-icon']`, and held to a fixture the
 * web's `@veyq/crypto` wrote (`SpaceIconsTest`). Every open is null on anything that does not open, never a throw.
 */
object SpaceIcons {
    /** Every icon's plaintext length (`ICON_PADDED_LEN`): the server cannot tell a logo from a photo. */
    const val PADDED_LEN = 16384

    fun context(scopeId: String, iconEpoch: Long) = Context("space-icon", "scopeId" to scopeId, "iconEpoch" to iconEpoch)

    /** A fresh icon: [encoded] sealed at [epoch] (its icon epoch) under a fresh `K_v`, and `K_v`'s wrap under [scopeKey] at [epoch]. */
    class Sealed(val sealedIcon: String, val wrappedIconValueKey: String)

    fun seal(suite: V1, scopeId: String, epoch: Long, scopeKey: ByteArray, encoded: ByteArray): Sealed {
        val ctx = context(scopeId, epoch)
        val padded = Pad.padTo(encoded, PADDED_LEN)
        val k = suite.p.randomBytes(32)
        try {
            return Sealed(b64(suite.seal(ctx, k, padded)), b64(ValueKeys(suite).wrap(scopeKey, epoch, ctx, k)))
        } finally {
            k.fill(0)
            padded.fill(0)
        }
    }

    /** The encoded picture under [sealed], its `K_v` opened from [wrapped] with [scopeKey] of [keyEpoch]; null where nothing opens. */
    fun open(suite: V1, scopeId: String, keyEpoch: Long, scopeKey: ByteArray, iconEpoch: Long, sealed: String, wrapped: String?): ByteArray? {
        if (wrapped == null) return null
        return runCatching {
            val ctx = context(scopeId, iconEpoch)
            val k = ValueKeys(suite).unwrap(scopeKey, keyEpoch, ctx, unb64(wrapped))
            try {
                Pad.unpad(suite.open(ctx, k, unb64(sealed)))
            } finally {
                k.fill(0)
            }
        }.getOrNull()
    }
}

/**
 * The icon of a scope this session holds — the tenant's on Settings' *Members*, a group's on its page (the web's
 * `TenantMembersPanel` / `GroupMembersPanel` identity header): opened on the device under the caller's own key, set and
 * removed by an owner (the server's gate). A set is the web's: the platform crops the picture square ([ProfileModel.EDGE]
 * RGBA), the core encodes it by the web's ladder, seals it at the epoch it holds; a `409 stale_epoch` (a rotation moved the
 * epoch meanwhile) reads the scopes anew and seals once more, as the web retries once.
 */
class ScopeIcons internal constructor(private val tenant: TenantSession, private val encoder: WebpEncoder?) {
    private val account get() = tenant.account

    /** [scopeId]'s icon as the image's encoded bytes, read anew; null where none is set or it does not open (the initial shows). */
    @Throws(Exception::class)
    suspend fun icon(scopeId: String): ByteArray? = try {
        val r = account.respond { ScopesApi(tenant.base, account.http).listMyScopes() }
        val s = if (r.success) r.body().firstOrNull { it.id == scopeId } else null
        val sealed = s?.sealedIcon
        if (sealed == null) null
        else {
            // the open scope's key, else its own grant opened (a deleted group is not open; the web's `openGroupIcon`)
            val held = tenant.scopeKey(scopeId)
            if (held != null) SpaceIcons.open(account.suite, scopeId, held.second.toLong(), held.first, s.iconEpoch.toLong(), sealed, s.wrappedIconValueKey)
            else {
                val opened = account.openOwnGrant(s, null, false) as? GrantOutcome.Opened
                opened?.let {
                    try {
                        SpaceIcons.open(account.suite, scopeId, s.grantedEpoch.toLong(), it.key, s.iconEpoch.toLong(), sealed, s.wrappedIconValueKey)
                    } finally {
                        it.key.fill(0)
                    }
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** How a set or a removal ended: [done] where it went through, [notice] what is said either way. */
    class Outcome(val done: Boolean, val notice: Notice)

    /** *Add icon* / *Change icon*: [rgba] (the platform's square crop, [ProfileModel.EDGE] on a side) as [scopeId]'s icon. */
    @Throws(Exception::class)
    suspend fun set(scopeId: String, rgba: ByteArray): Outcome = attempt {
        val encoded = ProfileModel.encodeLadder(encoder, rgba) ?: return@attempt Outcome(false, Notice(Msg.SPACE_ICON_FAILED))
        for (round in 0..1) {
            val (key, epoch) = tenant.scopeKey(scopeId) ?: return@attempt Outcome(false, Notice(Msg.SPACE_ICON_FAILED))
            val s = SpaceIcons.seal(account.suite, scopeId, epoch.toLong(), key, encoded)
            val r = account.respond {
                ScopesApi(tenant.base, account.http).setScopeIcon(scopeId, SetScopeIconRequest(s.sealedIcon, epoch, s.wrappedIconValueKey))
            }
            if (r.success) return@attempt Outcome(true, Notice(Msg.SPACE_ICON_SAVED))
            if (r.status == 409 && account.errorCode(r) == ProfileModel.STALE_EPOCH) {
                if (round == 0) {
                    runCatching { tenant.reopenMoved() }.onFailure { if (it is CancellationException) throw it }
                    continue
                }
                return@attempt Outcome(false, Notice(Msg.SPACE_ICON_STALE))
            }
            return@attempt Outcome(false, Notice(Msg.SPACE_ICON_FAILED))
        }
        Outcome(false, Notice(Msg.SPACE_ICON_FAILED))
    }

    /** *Remove icon* after its question: every member falls back to the initial. */
    @Throws(Exception::class)
    suspend fun clear(scopeId: String): Outcome = attempt {
        val r = account.respond { ScopesApi(tenant.base, account.http).clearScopeIcon(scopeId) }
        if (r.success) Outcome(true, Notice(Msg.SPACE_ICON_REMOVED)) else Outcome(false, Notice(Msg.SPACE_ICON_FAILED))
    }

    private inline fun attempt(block: () -> Outcome): Outcome = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome(false, Notice(Msg.SPACE_ICON_FAILED))
    }
}
