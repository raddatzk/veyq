package com.veyq.app.shared.http

import com.veyq.app.features.sharing.ShareWriteProof
import com.veyq.app.shared.membership.TestAdmission
import com.veyq.app.features.media.TestBlob
import com.veyq.app.shared.crypto.Tokens
import com.veyq.app.shared.membership.ScopeAccessWorld
import com.veyq.app.shared.membership.ScopeAccessWorld.Companion.B64
import com.veyq.app.shared.membership.ScopeAccessWorld.Companion.JOB_TAG
import com.veyq.app.testing.TestFixtures.bearer
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.function.Executable
import java.io.File
import java.util.UUID
import java.util.jar.JarFile

/**
 * The **enforcement guard** for the scope-access gate (uc.spaces.scope-access, ADR 0042): every operation in the
 * contract is either **probed** here against each state the gate tells apart, or named on a **reasoned exclusion
 * list**. The operation set is not listed — it is **derived** from the generated `com.veyq.app.generated.api`
 * interfaces, i.e. from `contracts/api/`, so an operation nobody classified is red on the day it is added.
 *
 * Each probe runs on its own [ScopeAccessWorld] (a probe the gate lets through may mutate) with five personas:
 *  1. **Stranger** — the real id and an invented one answer identically, with the membership 404. A difference is
 *     an existence oracle: a stranger could enumerate ids by reading it. This is **guard order**, which no grep
 *     can see — do not replace this test with a static check.
 *  2. **Unaccepted proposal** — answered exactly as the stranger (a proposal is not a membership).
 *  3. **Accepted member of a SUSPENDED tenant** — `403 tenant_suspended`, sent on a `Host` **no tenant claims**,
 *     so the answer cannot come from `TenantSuspensionFilter`.
 *  4. **Accepted member of a deleting GROUP** — a write is `409 scope_deleting`; a read is not refused.
 *  5. **Control** — the same call by the owner of an identical live group is refused by none of the above. Every negative owes a
 *     positive control on the same predicate: without it a probe that 404s wholesale (a broken path, a missing
 *     fixture) would satisfy the stranger and proposal checks perfectly.
 *
 * Every check anchors on `(status, code)`, never on "not 2xx": a bare failure is produced as readily by a
 * validation error or a broken body as by the gate.
 */
@QuarkusTest
class OperatorBlindProbeTest {

    /** How an operation names the space it acts on — which id a stranger would have to guess. */
    private enum class Address { SCOPE, NODE, SHARE, JOB, TENANT, HOST }

    private data class Call(val method: String, val path: String, val body: String? = null, val host: String? = null)

    private data class Probe(
        val address: Address,
        /** The status the gate's NotMember answer carries — 404 everywhere but a listing that answers `[]`. */
        val notMemberStatus: Int = 404,
        /** Puts the live spaces into the state the control needs to get past the operation's own checks. */
        val prepare: (ScopeAccessWorld) -> Unit = {},
        /** A listing that answers a non-member `[]`: its answer is told apart by whether rows came back. */
        val rowsTell: Boolean = false,
        val call: (ScopeAccessWorld.Space) -> Call,
    )

    private val slug get() = "c2x1Zy" + UUID.randomUUID().toString().replace("-", "").take(20)
    private val someId = "00000000-0000-4000-8000-000000000001"

    /** Every operation the gate guards, and the request that reaches it. */
    private val probes: Map<String, Probe> = mapOf(
        // --- content ---
        "createNode" to Probe(Address.SCOPE) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/nodes", """{"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"$B64","slugToken":"$slug","orderIndex":0}""")
        },
        "listNodes" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/nodes") },
        "updateNode" to Probe(Address.SCOPE) { s ->
            Call("PATCH", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}", """{"encryptedName":"$B64"}""")
        },
        "deleteNode" to Probe(Address.SCOPE) { s -> Call("DELETE", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}") },
        "restoreNode" to Probe(Address.SCOPE, prepare = { w -> listOf(w.live, w.control).forEach { trashNode(w, it) } }) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/restore", """{"slugToken":"$slug"}""")
        },
        "listTrashedNodes" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/trash/nodes") },
        "getFavorites" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/favorites") },
        "putFavorites" to Probe(Address.SCOPE) { s -> Call("PUT", "/api/scopes/${s.scopeId}/favorites", """{"ciphertext":"$B64"}""") },
        "getItemFavorites" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/item-favorites") },
        "putItemFavorites" to Probe(Address.SCOPE) { s ->
            Call("PUT", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/item-favorites", """{"ciphertext":"$B64"}""")
        },
        // --- media ---
        "startBlobUploads" to Probe(Address.SCOPE) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/blob-uploads-batch", """{"items":[${TestBlob.SMALL.declare(B64)}]}""")
        },
        "presignBlobUploadWindows" to Probe(Address.SCOPE) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/blob-upload-windows", """{"items":[{"uploadId":"${s.uploadId}","fromChunk":0}]}""")
        },
        "completeMedia" to Probe(Address.SCOPE) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/media-batch", """{"items":[{"nodeId":"${s.nodeId}","mediaId":"${TestBlob.ownerOf(s.uploadId)}","originalUploadId":"${s.uploadId}","derivatives":[]}]}""")
        },
        "listNodeMedia" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/media") },
        "listNodeBuckets" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/buckets") },
        "createBucket" to Probe(Address.SCOPE) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/buckets", """{"bucketId":"${UUID.randomUUID()}","sealedName":"$B64"}""")
        },
        "renameBucket" to Probe(Address.SCOPE) { s ->
            Call("PATCH", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/buckets/${s.bucketId}", """{"sealedName":"$B64"}""")
        },
        "deleteBucket" to Probe(Address.SCOPE) { s -> Call("DELETE", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/buckets/${s.bucketId}") },
        "moveBucketMedia" to Probe(Address.SCOPE) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/media-moves", """{"mediaIds":["${s.mediaId}"],"targetBucketId":"${s.bucketId}"}""")
        },
        "commitCoverBlob" to Probe(Address.SCOPE) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/cover-blob", """{"uploadId":"${s.uploadId}"}""")
        },
        "deleteCoverBlob" to Probe(Address.SCOPE) { s -> Call("DELETE", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/cover-blob") },
        "getMediaExif" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/media/${s.mediaId}/exif") },
        "updateMediaMeta" to Probe(Address.SCOPE) { s ->
            Call("PUT", "/api/scopes/${s.scopeId}/media/${s.mediaId}/meta", """{"wrappedMeta":"$B64"}""")
        },
        "deleteMedia" to Probe(Address.SCOPE) { s -> Call("DELETE", "/api/scopes/${s.scopeId}/media/${s.mediaId}") },
        "restoreMedia" to Probe(Address.SCOPE) { s -> Call("POST", "/api/scopes/${s.scopeId}/media/${s.mediaId}/restore") },
        "purgeMedia" to Probe(Address.SCOPE, prepare = { w -> listOf(w.live, w.control).forEach { trashMedia(w, it) } }) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/media/${s.mediaId}/purge")
        },
        "presignBlobDownloads" to Probe(Address.SCOPE) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/blob-downloads-batch", """{"items":[{"blobId":"${s.blobId}"}]}""")
        },
        // --- scopes ---
        "listScopeMembers" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/members") },
        "addGroupMember" to Probe(Address.SCOPE) { s -> Call("POST", "/api/scopes/${s.scopeId}/members", """{"username":"ghost",${TestAdmission.json()}}""") },
        "resolveGroupMember" to Probe(Address.SCOPE) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/members/resolve", """{"username":"ghost"}""")
        },
        "setMemberRole" to Probe(Address.SCOPE) { s -> Call("PATCH", "/api/scopes/${s.scopeId}/members/$someId", """{"role":"OWNER"}""") },
        "removeMember" to Probe(Address.SCOPE) { s -> Call("DELETE", "/api/scopes/${s.scopeId}/members/$someId") },
        "listScopeEpochs" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/epochs") },
        "rotateScope" to Probe(Address.SCOPE) { s -> Call("POST", "/api/scopes/${s.scopeId}/epochs", """{"epoch":1,"wrappedPrev":"$B64","grants":[],"admissions":[]}""") },
        "deleteGroup" to Probe(Address.SCOPE) { s -> Call("DELETE", "/api/scopes/${s.scopeId}") },
        "restoreGroup" to Probe(Address.SCOPE) { s -> Call("POST", "/api/scopes/${s.scopeId}/restore") },
        "revokeScopeShares" to Probe(Address.SCOPE) { s -> Call("POST", "/api/scopes/${s.scopeId}/shares/revoke") },
        "renameGroup" to Probe(Address.SCOPE) { s -> Call("POST", "/api/scopes/${s.scopeId}/name", """{"encryptedName":"$B64","nameEpoch":0}""") },
        "setScopeIcon" to Probe(Address.SCOPE) { s -> Call("POST", "/api/scopes/${s.scopeId}/icon", """{"sealedIcon":"$B64","iconEpoch":0}""") },
        "clearScopeIcon" to Probe(Address.SCOPE) { s -> Call("DELETE", "/api/scopes/${s.scopeId}/icon") },
        // The control's live group carries no compromise rotation, so it answers `409 rotation_not_failed` — past the gate.
        "requeueScopeRotation" to Probe(Address.SCOPE) { s -> Call("POST", "/api/scopes/${s.scopeId}/rotation/requeue") },
        // An account-rotation route that names a scope: the control's owner never rotated, so `404 rotation_not_found`
        // — a code of its own, answered past the gate, never the gate's `scope_not_found`.
        "requeueRotationScope" to Probe(Address.SCOPE) { s -> Call("POST", "/api/account/rotation/scopes/${s.scopeId}/requeue") },
        // Host-resolved: the Host names the tenant, so its root is what the gate checks. The suspended persona
        // cannot reach the gate here — the tenant's own domain is refused by TenantSuspensionFilter first.
        "createPersonalScope" to Probe(Address.HOST) { s -> Call("POST", "/api/scopes", """{"scopeId":"${UUID.randomUUID()}","wrappedGrant":"$B64",${TestAdmission.SIGNATURE_JSON}}""", host = s.domain) },
        "createGroup" to Probe(Address.HOST) { s ->
            Call("POST", "/api/scopes/groups", """{"scopeId":"${UUID.randomUUID()}","encryptedName":"$B64","wrappedGrant":"$B64",${TestAdmission.SIGNATURE_JSON},${TestAdmission.json()}}""", host = s.domain)
        },
        // --- sharing (curation) ---
        "createShare" to Probe(Address.NODE) { s ->
            Call("POST", "/api/shares", """{${ShareWriteProof.newShare()},"nodeId":"${s.nodeId}","role":"VIEWER","wrap":"$B64","sealedName":"$B64"}""")
        },
        // With `nodeId` only; without it the listing is account-addressed and filters instead of refusing
        // (ScopeAccessSliceTest). A non-member gets `[]`, so every answer is told apart by its rows.
        "listShares" to Probe(Address.NODE, notMemberStatus = 200, rowsTell = true) { s ->
            Call("GET", "/api/shares?nodeId=${s.nodeId}")
        },
        "revokeShare" to Probe(Address.SHARE) { s -> Call("POST", "/api/shares/${s.shareId}/revoke") },
        "reactivateShare" to Probe(Address.SHARE) { s -> Call("POST", "/api/shares/${s.shareId}/reactivate") },
        "updateShare" to Probe(Address.SHARE) { s -> Call("PATCH", "/api/shares/${s.shareId}", "{}") },
        "deleteShare" to Probe(Address.SHARE) { s -> Call("DELETE", "/api/shares/${s.shareId}") },
        "reissueShare" to Probe(Address.SHARE) { s ->
            Call(
                "POST", "/api/shares/${s.shareId}/reissue",
                """{${ShareWriteProof.newShare()},"wrap":"$B64","sealedName":"$B64","sealedShareKey":"$B64","sealedShareKeyEpoch":0}""",
            )
        },
        // --- tasks ---
        "enqueueTask" to Probe(Address.SCOPE) { s ->
            Call("POST", "/api/tasks/enqueue", """{"scopeId":"${s.scopeId}","capabilityTag":"$JOB_TAG","sealedBody":"$B64"}""")
        },
        "heartbeatTask" to Probe(Address.JOB) { s -> Call("POST", "/api/tasks/${s.jobId}/heartbeat") },
        "completeTask" to Probe(Address.JOB) { s -> Call("POST", "/api/tasks/${s.jobId}/complete", "{}") },
        "failTask" to Probe(Address.JOB) { s -> Call("POST", "/api/tasks/${s.jobId}/fail", "{}") },
        "declineTask" to Probe(Address.JOB) { s -> Call("POST", "/api/tasks/${s.jobId}/decline", "{}") },
        // --- tenant-addressed (the gate on the tenant's root scope) ---
        "inviteTenantMember" to Probe(Address.TENANT) { s ->
            Call("POST", "/api/tenants/${s.tenantId}/member-invites", """{"email":"invitee-${UUID.randomUUID()}@example.com",${TestAdmission.json()}}""")
        },
        "listMemberInvites" to Probe(Address.TENANT) { s -> Call("GET", "/api/tenants/${s.tenantId}/member-invites") },
        "reissueMemberInvite" to Probe(Address.TENANT) { s -> Call("POST", "/api/tenants/${s.tenantId}/member-invites/$someId/reissue") },
        "revokeMemberInvite" to Probe(Address.TENANT) { s -> Call("DELETE", "/api/tenants/${s.tenantId}/member-invites/$someId") },
        "removeTenantMember" to Probe(Address.TENANT) { s -> Call("DELETE", "/api/tenants/${s.tenantId}/members/$someId") },
        "getTenantTerms" to Probe(Address.TENANT) { s -> Call("GET", "/api/tenants/${s.tenantId}/terms") },
        "updateTenantTerms" to Probe(Address.TENANT) { s ->
            Call("PUT", "/api/tenants/${s.tenantId}/terms", """{"terms":{"de":"Bedingungen","en":"Terms"}}""")
        },
        // The live tenant publishes no terms, so the control's accept answers `409 terms_version_changed` — past the gate.
        "acceptTenantTerms" to Probe(Address.TENANT) { s -> Call("POST", "/api/tenants/${s.tenantId}/terms/accept", """{"version":1}""") },
        "getTenantMailSettings" to Probe(Address.TENANT) { s -> Call("GET", "/api/tenants/${s.tenantId}/mail-settings") },
        "updateTenantMailSettings" to Probe(Address.TENANT) { s ->
            Call("PUT", "/api/tenants/${s.tenantId}/mail-settings", """{"tls":false,"enabled":false}""")
        },
        "getTenantStorage" to Probe(Address.TENANT) { s -> Call("GET", "/api/tenants/${s.tenantId}/storage") },
        "updateTenantStorage" to Probe(Address.TENANT) { s ->
            Call("PUT", "/api/tenants/${s.tenantId}/storage", """{"shareLinkLimitBytes":10737418240}""")
        },
    )

    /** Every operation the gate does not guard, and why. A reason is required — "not scope-addressed" is one. */
    private val exclusions: Map<String, String> = buildMap {
        fun reason(why: String, vararg ops: String) = ops.forEach { put(it, why) }
        reason(
            "public or token-gated: the caller reaches the resource by holding an unguessable secret or none is needed, " +
                "so there is no id to enumerate (ADR 0042). Share and invite routes learn the gate's states through " +
                "the space's liveness instead (ScopeAccessSliceTest).",
            "requestLoginChallenge", "verifyLogin", "refreshSession", "adoptSession", "logout",
            "requestPasswordRecovery", "releaseRecoveryMaterial", "completePasswordRecovery",
            "openPairingChannel", "answerPairingChannel", "collectPairingChannel",
            "getSetupStatus", "createServerOwner",
            "resolveShare", "getShareTerms", "resolveShareBlob", "createShareIdentity", "startShareBlobUploads",
            "presignShareBlobUploadWindows", "completeShareMedia", "deleteShareMedia",
            "previewTenantInvite", "acceptTenantInvite", "registerAndAcceptTenantInvite",
        )
        reason(
            "Server-Owner route: @RolesAllowed(SERVER_OWNER) plus its own lifecycle guards; it administers tenants, " +
                "it does not act inside one.",
            "createTenant", "listTenants", "resendTenantInvite", "reassignTenantInvite", "changeTenantDomain",
            "revokeTenant", "suspendTenant", "reactivateTenant", "setTenantStorageQuota", "getInstanceRelease",
            "getInstanceStoreProvisioning",
        )
        reason(
            "account-wide: addresses the caller's own account, no space; a rotation job it originates simply waits " +
                "where claims are refused.",
            "getAccountKeyMaterial", "addAccountWrap", "deleteAccountWrap", "getIdentityPins", "putIdentityPins",
            "getDeviceRegister", "putDeviceRegister", "rotateAccountKeys", "changePassword", "listSessionFamilies",
            "revokeOtherSessionFamilies", "revokeSessionFamily", "delegatePairingSession", "sealPairingChannel",
            "clearDeclinesForAcceptedKey", "getAccountEmail", "confirmEmail", "listRotationScopeStates",
        )
        reason(
            "Host-resolved and self-scoped: reads or writes only the caller's own rows on the Host's own tenant, so " +
                "the Host filter is a correct suspension gate for exactly these (cr D10).",
            "listMyScopes", "listMyGroupInvitations", "getMyProfile", "updateMyProfile", "requestEmailConfirmation",
        )
        reason(
            "addressed by the caller's own unaccepted proposal, which the gate by definition does not count as a " +
                "membership — gating it would refuse the only caller who may answer it (uc.spaces.consent-group-add).",
            "acceptGroupInvitation", "declineGroupInvitation",
        )
        reason(
            "a filter, not an answer: it refuses nothing, it leaves out what the caller may not see — pinned by its " +
                "own test in ScopeAccessSliceTest.",
            "claimTask", "listMyTenants",
        )
        reason(
            "a filter, not an answer, over the caller's OWN rows across every scope: it leaves out a row of a scope " +
                "the gate would refuse (left, proposed-only, suspended tenant) by asking the gate's own READ answer — " +
                "pinned by ItemFavoritesSliceTest.listOwnLeavesOutEveryScopeTheGateRefuses.",
            "listOwnItemFavorites",
        )
    }

    /** A POST that reads, and the one write whose `Deleting` is its precondition rather than a refusal. */
    private val readsByException = setOf("resolveGroupMember", "presignBlobDownloads")
    private val deletingIsThePrecondition = setOf("restoreGroup")

    // --- the classification guard --------------------------------------------------------------------------

    @Test
    fun everyContractOperationIsProbedOrExcludedWithAReason() {
        val operations = contractOperations()
        val ids = operations.map { it.id }.toSet()
        val unclassified = ids - probes.keys - exclusions.keys
        val both = probes.keys intersect exclusions.keys
        val stale = (probes.keys + exclusions.keys) - ids
        assertAll(
            Executable {
                assertTrue(unclassified.isEmpty(), "unclassified operations — probe them against the scope-access gate, or add them to the exclusion map with the reason they need no gate: ${unclassified.sorted()}")
            },
            Executable { assertTrue(both.isEmpty(), "operations both probed and excluded: ${both.sorted()}") },
            Executable { assertTrue(stale.isEmpty(), "classified operations the contract no longer has: ${stale.sorted()}") },
        )
        // A probe must fire the route its label names — else a probe could guard some other operation under this
        // one's name. The placeholder space never reaches the server; only the shape is compared.
        val routes = operations.associateBy { it.id }
        val dummy = ScopeAccessWorld.Space(someId, "d.example.com", someId, someId, someId, someId, someId, someId, someId, someId, someId)
        probes.forEach { (id, probe) ->
            val call = probe.call(dummy)
            val route = routes[id] ?: return@forEach
            assertEquals(route.verb, call.method, "$id fires ${call.method}, the contract says ${route.verb}")
            assertTrue(route.matches(call.path.substringBefore('?')), "$id fires ${call.path}, which is not its route ${route.path}")
        }
    }

    // --- the four personas + control -----------------------------------------------------------------------

    @TestFactory
    fun everyGatedOperationAnswersEachStateOfTheGate(): List<DynamicTest> {
        val verbs = contractOperations().associate { it.id to it.verb }
        return probes.toSortedMap().map { (id, probe) ->
            DynamicTest.dynamicTest(id) {
                val write = (verbs.getValue(id) != "GET") && id !in readsByException
                probeOne(id, probe, write)
            }
        }
    }

    private fun probeOne(id: String, probe: Probe, write: Boolean) {
        val w = ScopeAccessWorld().build()
        probe.prepare(w)
        val checks = mutableListOf<Executable>()

        // 1. stranger: a real id and an invented one answer the same, with the membership status
        fun Response.answer() = answer(probe.rowsTell)
        val onReal = fire(w, w.stranger, probe.call(w.live))
        val onInvented = fire(w, w.stranger, probe.call(invented(w.live, probe.address)))
        val notMember = onReal.answer()
        checks += Executable {
            assertEquals(notMember, onInvented.answer(), "[stranger] $id is an existence oracle: a real id and an invented one answer differently, so ids are enumerable — the gate must run before anything else (ADR 0042)")
        }
        checks += Executable {
            assertEquals(probe.notMemberStatus, notMember.first, "[stranger] $id answered $notMember, not the gate's ${probe.notMemberStatus}; a 400 means the probe never reached the gate (fix its body)")
        }

        // 2. an unaccepted proposal is a stranger
        val proposer = if (probe.address == Address.TENANT || probe.address == Address.HOST) w.proposalOnly else w.proposal
        val asProposal = fire(w, proposer, probe.call(w.live)).answer()
        checks += Executable { assertEquals(notMember, asProposal, "[proposal] $id lets an unaccepted proposal in: it answers $asProposal, a stranger $notMember") }

        // 3. a member of a suspended tenant, on a Host no tenant claims
        if (probe.address != Address.HOST) {
            val asSuspended = fire(w, w.suspended.ownerId, probe.call(w.suspended)).answer()
            checks += Executable { assertEquals(403 to "tenant_suspended", asSuspended, "[suspended] $id serves a member of a suspended tenant from another domain") }
        }

        // 4. a member of a deleting group
        val refusals = setOf(notMember, 403 to "tenant_suspended", 409 to "scope_deleting")
        if (probe.address != Address.HOST && probe.address != Address.TENANT) {
            val asDeleting = fire(w, w.deleting.ownerId, probe.call(w.deleting)).answer()
            if (write && id !in deletingIsThePrecondition) {
                checks += Executable { assertEquals(409 to "scope_deleting", asDeleting, "[deleting] $id writes into a group that is being deleted") }
            } else {
                checks += Executable { assertTrue(asDeleting !in refusals && asDeleting.first != 400, "[deleting] $id refuses a ${if (write) "restore" else "read"} of a deleting group: $asDeleting") }
            }
        }

        // 5. control — the same call by the owner of an identical live group (its own space, so a negative the gate
        // wrongly let through cannot have changed the state the control needs)
        val asControl = fire(w, w.control.ownerId, probe.call(w.control)).answer()
        checks += Executable {
            assertTrue(asControl !in refusals && asControl.first !in setOf(400, 401),
                "[control] $id refuses the live group's owner: $asControl — the negatives above prove nothing while this is refused")
        }
        assertAll(id, checks)
    }

    private fun invented(s: ScopeAccessWorld.Space, address: Address): ScopeAccessWorld.Space {
        val fresh = UUID.randomUUID().toString()
        return when (address) {
            Address.SCOPE -> s.copy(scopeId = fresh)
            Address.NODE -> s.copy(nodeId = fresh)
            Address.SHARE -> s.copy(shareId = Tokens.random()) // a share id is 43 base64url chars, not a uuid
            Address.JOB -> s.copy(jobId = fresh)
            Address.TENANT -> s.copy(tenantId = fresh)
            Address.HOST -> s.copy(domain = "unclaimed-$fresh.invalid")
        }
    }

    private fun fire(w: ScopeAccessWorld, userId: String, call: Call): Response {
        val spec = given().header("Authorization", bearer(userId)).header("X-Forwarded-Host", call.host ?: w.unclaimedHost)
        call.body?.let { spec.contentType(ContentType.JSON).body(it) }
        return when (call.method) {
            "GET" -> spec.`when`().get(call.path)
            "POST" -> spec.`when`().post(call.path)
            "PUT" -> spec.`when`().put(call.path)
            "PATCH" -> spec.`when`().patch(call.path)
            "DELETE" -> spec.`when`().delete(call.path)
            else -> error("unsupported method ${call.method}")
        }
    }

    /** `(status, code)` — the code of an error envelope, null for a success (or, where [rowsTell], `[]` vs rows). */
    private fun Response.answer(rowsTell: Boolean): Pair<Int, String?> = statusCode() to when {
        statusCode() !in 200..299 -> runCatching { jsonPath().getString("code") }.getOrNull()
        rowsTell -> if (body().asString().trim() == "[]") "[]" else "rows"
        else -> null
    }

    private fun trashNode(w: ScopeAccessWorld, s: ScopeAccessWorld.Space) {
        given().header("Authorization", bearer(s.ownerId)).`when`().delete("/api/scopes/${s.scopeId}/nodes/${s.nodeId}").then().statusCode(204)
    }

    private fun trashMedia(w: ScopeAccessWorld, s: ScopeAccessWorld.Space) {
        given().header("Authorization", bearer(s.ownerId)).`when`().delete("/api/scopes/${s.scopeId}/media/${s.mediaId}").then().statusCode(204)
    }

    // --- the operation set, derived from the generated API interfaces ---------------------------------------

    private data class Operation(val id: String, val verb: String, val path: String) {
        private val pattern = Regex("^" + path.replace(Regex("\\{[^}]+}"), "[^/]+") + "$")
        fun matches(concrete: String) = pattern.matches(concrete)
    }

    /**
     * Every method of every interface in `com.veyq.app.generated.api` carrying an HTTP verb — the method name is
     * the operationId. Found on the test classpath rather than listed, which is the point: a listed set is exactly
     * what the hand-kept probe rows were, and they missed `restoreNode` and `listTrashedNodes`.
     */
    private fun contractOperations(): List<Operation> {
        val names = generatedApiClassNames()
        check(names.any { it.endsWith(".ContentApi") }) { "found no generated API interfaces on the classpath: $names" }
        return names.flatMap { name ->
            val type = Class.forName(name, false, javaClass.classLoader)
            val base = type.getAnnotation(Path::class.java)?.value.orEmpty()
            type.declaredMethods.mapNotNull { m ->
                val verb = VERBS.entries.firstOrNull { m.isAnnotationPresent(it.value) }?.key ?: return@mapNotNull null
                Operation(m.name, verb, (base + (m.getAnnotation(Path::class.java)?.value ?: "")).replace("//", "/"))
            }
        }
    }

    private fun generatedApiClassNames(): List<String> {
        val dir = API_PACKAGE.replace('.', '/')
        return System.getProperty("java.class.path").split(File.pathSeparator).map(::File).flatMap { entry ->
            when {
                entry.isDirectory -> File(entry, dir).listFiles().orEmpty().map { it.name }
                entry.isFile && entry.name.endsWith(".jar") -> JarFile(entry).use { jar ->
                    jar.entries().asSequence().map { it.name }.filter { it.startsWith("$dir/") && it.count { c -> c == '/' } == dir.count { c -> c == '/' } + 1 }
                        .map { it.substringAfterLast('/') }.toList()
                }
                else -> emptyList()
            }
        }.filter { it.endsWith(".class") && '$' !in it }.map { "$API_PACKAGE.${it.removeSuffix(".class")}" }.distinct()
    }

    private companion object {
        const val API_PACKAGE = "com.veyq.app.generated.api"
        val VERBS = linkedMapOf(
            "GET" to GET::class.java, "POST" to POST::class.java, "PUT" to PUT::class.java,
            "PATCH" to PATCH::class.java, "DELETE" to DELETE::class.java,
        )
    }
}
