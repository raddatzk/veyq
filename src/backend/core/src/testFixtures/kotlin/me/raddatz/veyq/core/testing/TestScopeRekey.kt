package me.raddatz.veyq.core.testing

import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.node.rekey.ScopeRekeyCommit
import me.raddatz.veyq.core.node.rekey.ScopeRekeyStage
import me.raddatz.veyq.core.node.rekey.ScopeRekeyStage.Key
import me.raddatz.veyq.core.node.rekey.ScopeRekeyStage.Value
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
 * A rotation's scope-level move for backend tests (ADR 0115 decision 2). The server never opens what it re-wraps, so a
 * test stages every value key's wrap (and every scope-root entry) under the scope key with the ciphertext it holds now —
 * what the commit checks is the wrap it replaces (the placement revision, for a root entry) and that every row has one,
 * both real here. Wraps only,
 * as a rotator's client stages: never a value's ciphertext. The succession statement is two well-formed public values
 * the server stores and never verifies (decision 5).
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
            val values = buildMap {
                // The same wrap as the one it replaces: what the commit checks is only that it replaces the row's own.
                fun wrap(itemId: String, field: String, wrapped: String?) = wrapped?.let { put(Key(itemId, field), Value(it, replaced = it)) }
                page.groupName?.let { wrap(ScopeRekeyCommit.GROUP_ITEM, ScopeRekeyCommit.GROUP_NAME_WRAP, it.first) }
                page.icon?.let { wrap(ScopeRekeyCommit.ICON_ITEM, ScopeRekeyCommit.ICON_WRAP, it.first) }
                page.rootNodes.forEach { n ->
                    fun entry(field: String, value: String) = put(Key(n.id, field), Value(value, revision = n.placementRevision))
                    entry(ScopeRekeyCommit.ROOT_WRAP, n.wrappedContentKey)
                    entry(ScopeRekeyCommit.ROOT_NAME, n.encryptedName)
                    if (n.slugToken != n.id) entry(ScopeRekeyCommit.ROOT_SLUG, n.slugToken)
                }
                page.shares.forEach { wrap(it.id, ScopeRekeyCommit.SHARE_WRAP, it.wrappedRecoveryValueKey) }
                page.members.forEach { p ->
                    wrap(p.userId, ScopeRekeyCommit.MEMBER_NAME_WRAP, p.wrappedNameValueKey)
                    wrap(p.userId, ScopeRekeyCommit.MEMBER_AVATAR_WRAP, p.wrappedAvatarValueKey)
                    wrap(p.userId, ScopeRekeyCommit.MEMBER_JOIN_WRAP, p.wrappedJoinRecordValueKey)
                }
                page.verifications.forEach { v ->
                    wrap("${v.verifierUserId}|${v.subjectUserId}", ScopeRekeyCommit.VERIFICATION_WRAP, v.wrappedValueKey)
                }
            }
            check(!page.more) { "a test scope with more than $ALL scope-level rows" }
            Arc.container().instance(ScopeRekeyStage::class.java).get().put(rekeyId, scopeId, ScopeRekeyStage.CORE, values)
        }
        Arc.container().select(ScopeRekeyTestStager::class.java).forEach { it.stageAll(scopeId, rekeyId) }
        return rekeyId
    }

    /** What a rotation of [scopeId] carries besides its epoch and grants: the succession statement and the re-key id
     *  every scope-level row is staged under ([stageAll]) — call it right before the rotation, after the scope's rows. */
    fun rotationJson(scopeId: String): String = """$SUCCESSION_JSON,"rekeyId":"${stageAll(scopeId)}""""

    private const val ALL = 10_000
}
