package me.raddatz.veyq.core.testing

import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.node.rekey.ScopeRekeyCommit
import me.raddatz.veyq.core.node.rekey.ScopeRekeyField
import me.raddatz.veyq.core.scope.ScopeEntity
import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction
import java.util.UUID

/**
 * An application's part of [TestScopeRekey.stageAll]: stages its own scope-level rows of a scope, as its rotator's client
 * would, under the re-key id given. A CDI bean in the application's test fixtures, found through `Arc` (the way core's
 * `ScopeAccessWorld` finds a `WorldContent`), so core's fixtures stage what core alone cannot name.
 */
interface ScopeRekeyTestStager {
    fun stageAll(scopeId: String, rekeyId: String)
}

/**
 * A rotation's scope-level re-seal for backend tests (ADR 0115 decision 2). The server never opens what it re-seals, so
 * a test stages every row sealed under the scope key with the ciphertext it holds now — what the commit checks is the
 * digest of the row it replaces and that every row has a value, both real here. The succession statement is two
 * well-formed public values the server stores and never verifies (decision 5).
 */
object TestScopeRekey {
    /** 32 bytes of base64 — the length of a real Ed25519 public key. */
    const val SUCCESSION_PUBLIC_KEY = "c3VjY2Vzc2lvbi1wdWJsaWMta2V5LTMyLWJ5dGVzLTE="

    /** The succession statement as JSON members of a rotation's body. */
    const val SUCCESSION_JSON = """"successionPublicKey":"$SUCCESSION_PUBLIC_KEY","successionSignature":"${TestGrants.SIGNATURE}""""

    /** Stage every scope-level row of [scopeId] — core's and each application's ([ScopeRekeyTestStager]) — as it stands,
     *  under a fresh re-key id, and answer the id. */
    fun stageAll(scopeId: String, rekeyId: String = UUID.randomUUID().toString()): String {
        QuarkusTransaction.requiringNew().run {
            val commit = Arc.container().instance(ScopeRekeyCommit::class.java).get()
            val scope = ScopeEntity.findById(scopeId)!!
            val page = commit.parts(scope, null, ALL)
            val fields = buildList {
                page.groupName?.let { add(ScopeRekeyField(ScopeRekeyCommit.GROUP_ITEM, ScopeRekeyCommit.GROUP_NAME, it.first)) }
                page.icon?.let { add(ScopeRekeyField(ScopeRekeyCommit.ICON_ITEM, ScopeRekeyCommit.ICON, it.first)) }
                page.rootNodes.forEach { n ->
                    add(ScopeRekeyField(n.id, ScopeRekeyCommit.ROOT_WRAP, n.wrappedContentKey, n.placementRevision))
                    add(ScopeRekeyField(n.id, ScopeRekeyCommit.ROOT_NAME, n.encryptedName, n.placementRevision))
                    if (n.slugToken != n.id) add(ScopeRekeyField(n.id, ScopeRekeyCommit.ROOT_SLUG, n.slugToken, n.placementRevision))
                }
                page.invitees.forEach { m ->
                    add(ScopeRekeyField(m.userId, ScopeRekeyCommit.INVITEE_KEY, m.sealedNameKey!!))
                    add(ScopeRekeyField(m.userId, ScopeRekeyCommit.INVITEE_RECIPIENT, TestGrants.digestOf(m.userId)))
                }
                page.shares.forEach { add(ScopeRekeyField(it.id, ScopeRekeyCommit.SHARE_KEY, it.sealedShareKey!!)) }
                page.members.forEach { p ->
                    p.sealedName?.let { add(ScopeRekeyField(p.userId, ScopeRekeyCommit.MEMBER_NAME, it)) }
                    p.sealedAvatar?.let { add(ScopeRekeyField(p.userId, ScopeRekeyCommit.MEMBER_AVATAR, it)) }
                    p.sealedJoinRecord?.let { add(ScopeRekeyField(p.userId, ScopeRekeyCommit.MEMBER_JOIN, it)) }
                }
                page.verifications.forEach { v ->
                    add(ScopeRekeyField("${v.verifierUserId}|${v.subjectUserId}", ScopeRekeyCommit.VERIFICATION, v.sealedStatement))
                }
            }
            check(!page.more) { "a test scope with more than $ALL scope-level rows" }
            commit.stage(rekeyId, scope, fields)
        }
        Arc.container().select(ScopeRekeyTestStager::class.java).forEach { it.stageAll(scopeId, rekeyId) }
        return rekeyId
    }

    /** What a rotation of [scopeId] carries besides its epoch and grants: the succession statement and the re-key id
     *  every scope-level row is staged under ([stageAll]) — call it right before the rotation, after the scope's rows. */
    fun rotationJson(scopeId: String): String = """$SUCCESSION_JSON,"rekeyId":"${stageAll(scopeId)}""""

    private const val ALL = 10_000
}
