package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.api.apis.ScopesApi
import me.raddatz.veyq.mobile.api.apis.TenantsApi
import me.raddatz.veyq.mobile.api.models.MemberInviteLinkRequest
import me.raddatz.veyq.mobile.api.models.MemberInviteRequest
import me.raddatz.veyq.mobile.api.models.PendingMemberInvite
import me.raddatz.veyq.mobile.api.models.SetMemberInviteLabelRequest
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.Invites
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.Trust
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.crypto.utf8
import me.raddatz.veyq.mobile.words.Moment
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The owner's half of a member invite (uc.spaces.add-member, uc.spaces.manage-member-invites; ADR 0111 decisions 1-2,
 * ADR 0129), the port of the web's `memberInvite.ts`, `inviteLabel.ts` and the invite half of `members.ts`. An invite is a
 * placeholder member: a fresh seed derives the invite key, the owner's identity signs it, the tenant's current key is
 * granted to it (its anchor, ADR 0111 decision 19), and the seed — with the head of the owner's key chain and the owner's
 * account — is sealed into the link's secret part under a generated code (or, mailed, a password the owner chose).
 *
 * Two ways (ADR 0129 decision 1): a **link** invite sends no address and no sealed part — the server answers the token and
 * this device writes `token.sealed.code` into the link the owner hands over; a **mailed** invite sends the sealed part, the
 * server mails the link and forgets it, and the owner passes the code on outside veyq. The seed, the code and (for a link)
 * the sealed part never reach the server. The link and the code are made to be handed on, as a share link and its password
 * are (src/mobile/CLAUDE.md § Secrets on screen): they are answered once, to the call that made them, never kept here.
 */
class TenantInvites internal constructor(private val tenant: TenantSession) {
    private val account get() = tenant.account
    private val tenantId get() = tenant.tenant.tenantId
    private val invites get() = Invites(account.suite)

    /** One pending invite as the owners' list shows it. */
    @Serializable
    data class PendingInvite(
        val inviteId: String,
        /** The address a mailed invite went to; null for a link invite. */
        val email: String?,
        /** A link invite's label as it opened here; null where none is set or none opens (uc.spaces.manage-member-invites E2). */
        val label: String?,
        /** Until when it is valid. */
        val expires: Notice,
    ) {
        /** A link invite (ADR 0129 decision 2): no address; its row offers *Rename*. */
        val link: Boolean = email == null

        /** The row's title as written: the address, else the label; null where it is the word [titleWord]. */
        val title: String? = email ?: label

        /** "Link invitation" where the row has neither address nor label. */
        val titleWord: Msg? = if (title == null) Msg.MEMBERS_PENDING_LINK else null

        /** A link invite's subtitle: "Link invitation · Valid until …" under a label, the expiry alone else; null for a mailed one. */
        val subtitle: List<Notice>? = if (email != null) null else listOfNotNull(label?.let { Notice(Msg.MEMBERS_PENDING_LINK) }, expires)
    }

    /**
     * What an invite, a re-issue, a label or a revocation came to: [done], what [notice] says, and the secret it made, once:
     * a link invite's [address] with the message around it ([message]), or a mailed invite's [code] (two groups of five; a
     * password the owner chose is not shown back, [ownChoice]).
     */
    @Serializable
    data class Outcome(
        val done: Boolean,
        val notice: Notice,
        val address: String? = null,
        val message: Notice? = null,
        val code: String? = null,
        val mailedTo: String? = null,
        /** The owner chose a password for a mailed invite: nothing to show back, they pass it on themselves. */
        val ownChoice: Boolean = false,
    )

    /** Which way an invite goes, how the dialog's switch shows it: a link first (ADR 0129 decision 1). */
    enum class Way { LINK, MAIL }

    /** The owners' pending invites, each link invite's label opened under the tenant key; empty for a member or a failed read. */
    @Throws(Exception::class)
    suspend fun pending(): List<PendingInvite> = try {
        val r = account.respond { TenantsApi(tenant.base, account.http).listMemberInvites(tenantId) }
        if (!r.success) emptyList() else r.body().map(::row)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    private fun row(i: PendingMemberInvite): PendingInvite =
        PendingInvite(i.inviteId, i.email, if (i.email == null) openLabel(i) else null, Notice(Msg.MEMBERS_PENDING_EXPIRES, Moment(epochMs(i.inviteExpiresAt))))

    /** A link invite's label, or null where it does not open as this invite's (another invite's, another key's, a corrupt blob). */
    private fun openLabel(i: PendingMemberInvite): String? {
        val sealed = i.sealedLabel ?: return null
        val wrapped = i.wrappedLabelValueKey ?: return null
        val (key, epoch) = tenant.scopeKey(tenantId) ?: return null
        return runCatching {
            JoinRecords(account.suite).openValueField(tenantId, epoch.toLong(), key, labelContext(tenantId, i.inviteId), unb64(wrapped), unb64(sealed))
        }.getOrNull()?.let { normalizeLabel(it.decodeToString()) }
    }

    /** A link invite (ADR 0129 decision 1) with the owners' optional [label], its link shown once. */
    @Throws(Exception::class)
    suspend fun inviteByLink(label: String?): Outcome = attempt(Msg.MEMBERS_LINK_FAILED) {
        val code = invites.generateCode()
        val s = seal(Invites.Mode.CODE, code, label) ?: return@attempt Outcome(false, Notice(Msg.MEMBERS_LINK_FAILED))
        val r = account.respond { TenantsApi(tenant.base, account.http).createMemberInviteLink(tenantId, s.request) }
        if (!r.success) return@attempt Outcome(false, Notice(refusal(r, Msg.MEMBERS_LINK_FAILED)))
        linkOutcome(r.body().bootstrapSecret, s.sealed, code, r.body().inviteExpiresAt, Msg.MEMBERS_LINK_CREATED)
    }

    /**
     * A mailed invite to [email]: the link under a generated code, or under [password] where the owner chose one (the
     * account policy's length floor). The code is answered once; the server mails the link and answers no secret.
     */
    @Throws(Exception::class)
    suspend fun inviteByMail(email: String, password: String?): Outcome = attempt(Msg.MEMBERS_INVITE_FAILED) {
        val to = email.trim().lowercase()
        if (to.isEmpty() || (password != null && password.length < PasswordChange.MIN_PASSWORD_LENGTH)) {
            return@attempt Outcome(false, Notice(Msg.MEMBERS_INVITE_FAILED))
        }
        val code = if (password == null) invites.generateCode() else null
        val s = seal(if (code != null) Invites.Mode.CODE else Invites.Mode.PASSWORD, code ?: password!!, null)
            ?: return@attempt Outcome(false, Notice(Msg.MEMBERS_INVITE_FAILED))
        val r = account.respond { TenantsApi(tenant.base, account.http).inviteTenantMember(tenantId, mailed(to, s)) }
        if (!r.success) return@attempt Outcome(false, Notice(refusal(r, Msg.MEMBERS_INVITE_FAILED)))
        Outcome(true, Notice(Msg.MEMBERS_INVITE_SENT), code = code?.let(Invites::formatCode), mailedTo = to, ownChoice = code == null)
    }

    /**
     * *Send again* / *New link* on a pending invite: a new invite in its place — a new key, link and code; the old code
     * opens nothing any more. A link invite answers its new link (its label sealed anew for the new invite), a mailed one
     * its new code.
     */
    @Throws(Exception::class)
    suspend fun reissue(invite: PendingInvite): Outcome = attempt(Msg.MEMBERS_ACTION_FAILED) {
        val code = invites.generateCode()
        val s = seal(Invites.Mode.CODE, code, invite.label) ?: return@attempt Outcome(false, Notice(Msg.MEMBERS_ACTION_FAILED))
        val api = TenantsApi(tenant.base, account.http)
        val email = invite.email
        if (email == null) {
            val r = account.respond { api.reissueMemberInviteLink(tenantId, invite.inviteId, s.request) }
            if (!r.success) return@attempt Outcome(false, Notice(refusal(r, Msg.MEMBERS_ACTION_FAILED)))
            linkOutcome(r.body().bootstrapSecret, s.sealed, code, r.body().inviteExpiresAt, Msg.MEMBERS_LINK_REISSUED)
        } else {
            val r = account.respond { api.reissueMemberInvite(tenantId, invite.inviteId, mailed(email, s)) }
            if (!r.success) return@attempt Outcome(false, Notice(refusal(r, Msg.MEMBERS_ACTION_FAILED)))
            Outcome(true, Notice(Msg.MEMBERS_INVITE_SENT_TO, email), code = Invites.formatCode(code), mailedTo = email)
        }
    }

    /** *Cancel invite*, after its question: its link stops working. */
    @Throws(Exception::class)
    suspend fun revoke(inviteId: String): Outcome = attempt(Msg.MEMBERS_ACTION_FAILED) {
        val r = account.respond { TenantsApi(tenant.base, account.http).revokeMemberInvite(tenantId, inviteId) }
        if (r.success) Outcome(true, Notice(Msg.MEMBERS_INVITE_REVOKED)) else Outcome(false, Notice(Msg.MEMBERS_ACTION_FAILED))
    }

    /** A link invite's label set to [label] — sealed under the tenant's current key, bound to this invite — or cleared when empty. */
    @Throws(Exception::class)
    suspend fun setLabel(inviteId: String, label: String?): Outcome = attempt(Msg.MEMBERS_LABEL_FAILED) {
        val text = normalizeLabel(label)
        val request = if (text == null) {
            SetMemberInviteLabelRequest(null, null, null)
        } else {
            val (key, epoch) = tenant.scopeKey(tenantId) ?: return@attempt Outcome(false, Notice(Msg.MEMBERS_LABEL_FAILED))
            val v = JoinRecords(account.suite).sealValueField(tenantId, epoch.toLong(), key, labelContext(tenantId, inviteId), utf8(text))
            SetMemberInviteLabelRequest(b64(v.sealed), b64(v.wrappedValueKey), epoch)
        }
        val r = account.respond { TenantsApi(tenant.base, account.http).setMemberInviteLabel(tenantId, inviteId, request) }
        if (r.success) Outcome(true, Notice(Msg.MEMBERS_LABEL_SAVED)) else Outcome(false, Notice(Msg.MEMBERS_LABEL_FAILED))
    }

    private suspend fun linkOutcome(token: String, sealed: String, code: String, expires: String, said: Msg): Outcome {
        val link = "${tenant.base.trimEnd('/')}/tenant/accept#${invites.buildFragment(token, sealed, code)}"
        val message = Notice(Msg.MEMBERS_LINK_MESSAGE, tenant.tenant.name, Moment(epochMs(expires)), link)
        return Outcome(true, Notice(said), address = link, message = message)
    }

    private fun mailed(email: String, s: Sealed) = with(s.request) {
        // the wire carries opaque bytes as standard base64; the server re-encodes them for the link's fragment
        MemberInviteRequest(
            email, inviteId, invitePublicKey, inviteKeySignature, expiresAt, b64(Invites.unb64urlStrict(s.sealed)!!), epoch,
            wrappedGrant, grantSignature, recipientIdentityDigest,
        )
    }

    /** The request bound to nobody and the link's sealed part (base64url): what both ways share. */
    private class Sealed(val request: MemberInviteLinkRequest, val sealed: String)

    /**
     * The invite itself, as the web's `sealInvite`: under the head of this account's own chain (walked to this vault's
     * identity), the tenant's current key and the instance's invite lifetime. Null where any is missing. The seed, the KEK,
     * the secret's bytes and the invite's private key are zeroed before this returns.
     */
    private suspend fun seal(mode: Invites.Mode, secret: String, label: String?): Sealed? {
        val (key, epoch) = tenant.scopeKey(tenantId) ?: return null
        val head = myChainHead() ?: return null
        val ttl = ttlSeconds()
        val inv = invites
        val secretBytes = inv.linkSecret(mode, secret) ?: return null
        val seed = inv.generateSeed()
        val inviteKey = inv.inviteKeyFromSeed(seed)
        val owner = account.identityCopy()
        var kek: ByteArray? = null
        try {
            val inviteId = NodeCreate.uuid(account)
            val publicKey = inviteKey.pub.serialize()
            val publicB64 = b64(publicKey)
            val expiresAt = Clock.System.now().epochSeconds + ttl
            val signature = inv.signInviteKey(owner.signSeed, tenantId, inviteId, publicKey, expiresAt)
            val grant = account.sealGrant(key, tenantId, "TENANT", epoch.toLong(), inviteId, publicB64)
            val salt = account.primitives.randomBytes(16)
            kek = inv.kekInviteLink(secretBytes, salt)
            val sealed = inv.sealLink(tenantId, inviteId, mode, kek, salt, Invites.LinkPayload(seed, unb64(head), account.userId))
            // under the same epoch as the grant, bound to this invite: a re-issue's new id seals it anew
            val text = normalizeLabel(label)
            val sealedLabel = text?.let { JoinRecords(account.suite).sealValueField(tenantId, epoch.toLong(), key, labelContext(tenantId, inviteId), utf8(it)) }
            return Sealed(
                MemberInviteLinkRequest(
                    inviteId, publicB64, b64(signature), Instant.fromEpochSeconds(expiresAt).toString(), epoch,
                    grant.wrappedGrant, grant.grantSignature, grant.recipientIdentityDigest,
                    sealedLabel?.let { b64(it.sealed) }, sealedLabel?.let { b64(it.wrappedValueKey) },
                ),
                sealed,
            )
        } finally {
            seed.fill(0)
            inviteKey.zero()
            owner.kemSeed.fill(0)
            owner.signSeed.fill(0)
            kek?.fill(0)
            secretBytes.fill(0)
        }
    }

    /** The instance's member-invite lifetime as the caller's tenant row serves it (`veyq.tenant.invite.ttl`'s default else). */
    private suspend fun ttlSeconds(): Long = runCatching {
        val r = account.respond { ScopesApi(tenant.base, account.http).listMyTenants() }
        if (r.success) r.body().firstOrNull { it.tenantId == tenantId }?.memberInviteTtlSeconds?.toLong() else null
    }.getOrNull() ?: DEFAULT_TTL_SECONDS

    /** This account's own head (base64) as its chain is served — walked, and required to end at this vault's identity: a
     *  chain that withholds the newest step is refused rather than sealed into a link (ADR 0111 decision 19). */
    private suspend fun myChainHead(): String? {
        val chain = runCatching { account.keyChainOf(account.userId) }.getOrNull() ?: return null
        val links = account.anchoringOfAccount.links(chain) ?: return null
        val walked = Trust(account.suite).verifyKeyChain(account.userId, links, null) as? Trust.KeyChainVerdict.Ok ?: return null
        if (!walked.keys.identityPublicKey.contentEquals(account.myPublic) || b64(walked.head) != chain.head) return null
        return chain.head
    }

    private suspend fun refusal(r: me.raddatz.veyq.mobile.api.infrastructure.HttpResponse<*>, fallback: Msg): Msg = when (account.errorCode(r)) {
        "already_member" -> Msg.MEMBERS_ALREADY_MEMBER
        "not_tenant_owner" -> Msg.MEMBERS_NOT_OWNER
        "rate_limited" -> Msg.MEMBERS_INVITE_RATE_LIMITED
        "mail_unavailable" -> Msg.MEMBERS_MAIL_UNAVAILABLE
        "mail_send_failed" -> Msg.MEMBERS_MAIL_SEND_FAILED
        else -> fallback
    }

    private inline fun attempt(failed: Msg, block: () -> Outcome): Outcome = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome(false, Notice(failed))
    }

    companion object {
        /** The invite lifetime the server takes when the caller's tenant row does not carry it. */
        const val DEFAULT_TTL_SECONDS = 7L * 24 * 60 * 60

        /** The longest label the owner may type, in characters (`INVITE_LABEL_MAX_LENGTH`). */
        const val LABEL_MAX_LENGTH = 80

        /** A label's own seal context, bound to the tenant and the invite. */
        fun labelContext(tenantId: String, inviteId: String) = Context("member-invite-label", "scopeId" to tenantId, "inviteId" to inviteId)

        /** A label as it is stored: trimmed, at most [LABEL_MAX_LENGTH] characters (code points), or null for none. */
        fun normalizeLabel(label: String?): String? {
            val trimmed = (label ?: "").trim()
            if (trimmed.isEmpty()) return null
            var out = ""
            var n = 0
            var i = 0
            while (i < trimmed.length && n < LABEL_MAX_LENGTH) {
                val c = trimmed[i]
                val pair = c.isHighSurrogate() && i + 1 < trimmed.length && trimmed[i + 1].isLowSurrogate()
                out += if (pair) trimmed.substring(i, i + 2) else c.toString()
                i += if (pair) 2 else 1
                n++
            }
            return out
        }

        internal fun epochMs(iso: String): Long = runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrDefault(0L)
    }
}
