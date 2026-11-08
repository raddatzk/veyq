package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.raddatz.veyq.mobile.api.apis.TenantsApi
import me.raddatz.veyq.mobile.blob.Sniff
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.Pad
import me.raddatz.veyq.mobile.crypto.unb64

/**
 * The members' profile pictures (ADR 0084, uc.spaces.set-profile-picture), a port of the web's `core/people/avatarCache.ts`
 * over `memberAvatar.ts`'s open. The directory carries only each member's `avatarRevision`; a picture is fetched
 * (`getMemberAvatar`) only for a non-zero revision this device has not settled, revalidated with `If-None-Match`, and opened
 * here: its value key from the wrap under the tenant's key, the picture under it (`member-avatar`, bound to (tenant scope,
 * member)), a plaintext of the one constant pad, an image by its bytes. Anything else shows the letter, never an error.
 *
 * The rules, each as the web keeps them: revision 0 is no picture (no request; a held one dropped); a changed revision
 * replaces it; one (member, revision) is asked once at a time; a `404` drops it and a picture that does not open shows the
 * letter — both remember the revision, so nothing is asked again for it; a failed request (offline, 5xx) leaves what is
 * held for the next sync; at most [CONCURRENCY] requests run at once. Members only: no share page reads it.
 */
class MemberAvatars internal constructor(private val tenant: TenantSession) {
    private val settled = HashMap<String, Int>()
    private val pictures = HashMap<String, ByteArray>()
    private val slots = Semaphore(CONCURRENCY)
    /** (member, revision) pairs being asked: a second sync meanwhile does not ask them again. */
    private val asking = HashSet<String>()

    /** A member's picture (the encoded image bytes), or null: the letter. */
    fun pictureOf(userId: String?): ByteArray? = userId?.let { pictures[it] }

    /** Every picture held, by user id. */
    fun pictures(): Map<String, ByteArray> = pictures.toMap()

    /**
     * Brings what is held in line with [people]: one fetch for each member whose revision is non-zero and not settled, a
     * held picture dropped at revision 0. True when any picture came, changed or went. Never throws for a picture.
     */
    @Throws(Exception::class)
    suspend fun sync(people: Collection<Person>): Boolean = coroutineScope {
        var moved = false
        val due = ArrayList<Person>()
        for (p in people) {
            if (p.avatarRevision <= 0) {
                if (pictures.remove(p.userId) != null) moved = true
                settled.remove(p.userId)
            } else if (settled[p.userId] != p.avatarRevision && asking.add("${p.userId}:${p.avatarRevision}")) {
                due += p
            }
        }
        val key = tenant.scopeKey(tenant.tenant.tenantId)
        if (key == null) {
            due.forEach { asking.remove("${it.userId}:${it.avatarRevision}") }
            return@coroutineScope moved
        }
        val fetched = try {
            due.map { p -> async { slots.withPermit { p to fetch(p, key.first, key.second.toLong()) } } }.awaitAll()
        } finally {
            due.forEach { asking.remove("${it.userId}:${it.avatarRevision}") }
        }
        for ((p, outcome) in fetched) {
            when (outcome) {
                is Fetched.Unchanged -> settled[p.userId] = p.avatarRevision
                is Fetched.Gone -> {
                    if (pictures.remove(p.userId) != null) moved = true
                    settled[p.userId] = p.avatarRevision
                }
                is Fetched.Picture -> {
                    val bytes = outcome.bytes
                    if (bytes == null) {
                        if (pictures.remove(p.userId) != null) moved = true
                    } else {
                        pictures[p.userId] = bytes
                        moved = true
                    }
                    settled[p.userId] = p.avatarRevision
                }
                is Fetched.Failed -> {}
            }
        }
        moved
    }

    private sealed interface Fetched {
        object Unchanged : Fetched
        object Gone : Fetched
        object Failed : Fetched
        class Picture(val bytes: ByteArray?) : Fetched
    }

    private suspend fun fetch(p: Person, tenantKey: ByteArray, epoch: Long): Fetched {
        val known = settled[p.userId]
        val r = runCatching {
            tenant.account.respond {
                TenantsApi(tenant.base, tenant.account.http).getMemberAvatar(tenant.tenant.tenantId, p.userId, known?.let { "\"$it\"" })
            }
        }.getOrElse { return Fetched.Failed }
        return when {
            r.status == 304 -> Fetched.Unchanged
            r.status == 404 -> Fetched.Gone
            !r.success -> Fetched.Failed
            else -> {
                val body = runCatching { r.body() }.getOrElse { return Fetched.Picture(null) }
                Fetched.Picture(
                    open(JoinRecords(tenant.account.suite), tenant.tenant.tenantId, tenantKey, epoch, p.userId, body.sealedAvatar, body.wrappedAvatarValueKey),
                )
            }
        }
    }

    companion object {
        /** How many picture requests run at once (`AVATAR_CONCURRENCY`): a 1,000-member roster is 1,000 pictures. */
        const val CONCURRENCY = 6

        /** The constant plaintext length every picture is padded to: the tenant icon's pad, 16 KiB (`AVATAR_PADDED_LEN`). */
        const val PADDED_LEN = 16_384

        fun context(tenantScopeId: String, userId: String) = Context("member-avatar", "scopeId" to tenantScopeId, "userId" to userId)

        /**
         * A sealed picture opened as every reader opens it (`openMemberAvatar`): the encoded image, or null when its wrap or
         * its seal does not open, it is padded to another length (the first 8 KiB pictures), or its bytes are no image.
         */
        internal fun open(
            joins: JoinRecords, tenantScopeId: String, tenantKey: ByteArray, epoch: Long, userId: String,
            sealedAvatar: String?, wrappedValueKey: String?,
        ): ByteArray? {
            if (sealedAvatar.isNullOrEmpty() || wrappedValueKey.isNullOrEmpty()) return null
            return runCatching {
                val padded = joins.openValueField(tenantScopeId, epoch, tenantKey, context(tenantScopeId, userId), unb64(wrappedValueKey), unb64(sealedAvatar))
                    ?: return null
                if (padded.size != PADDED_LEN) return null
                Pad.unpad(padded).takeIf { Sniff.image(it) != null }
            }.getOrNull()
        }
    }
}
