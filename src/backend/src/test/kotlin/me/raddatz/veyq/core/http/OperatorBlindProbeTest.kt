package me.raddatz.veyq.core.http

import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.membership.TestGrants
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.membership.ScopeAccessWorld
import me.raddatz.veyq.core.membership.ScopeAccessWorld.Companion.B64
import me.raddatz.veyq.core.membership.ScopeAccessWorld.Companion.JOB_TAG
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.function.Executable
import java.util.UUID
import me.raddatz.veyq.memories.testing.nodeId
import me.raddatz.veyq.memories.testing.mediaId
import me.raddatz.veyq.memories.testing.blobId
import me.raddatz.veyq.memories.testing.bucketId
import me.raddatz.veyq.memories.testing.shareId
import me.raddatz.veyq.memories.testing.MemoriesWorldContent

/**
 * The **enforcement guard** for the scope-access gate (uc.spaces.scope-access, ADR 0042): every operation in the
 * contract is either **probed** here against each state the gate tells apart, or named on a **reasoned exclusion
 * list**. The operation set is not listed — it is **derived** from the generated API interfaces of every owner
 * (core and each application, as `buildSrc` derives them from `contracts/api/` — [apiPackages]), so an operation
 * nobody classified is red on the day it is added, an application's included.
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

    /** A sealed Pot revision body of the smallest size class (744 base64 characters, 512 bytes of padded plaintext). */
    private val potBody = "A".repeat(744)

    /** Every operation the gate guards, and the request that reaches it. */
    private val probes: Map<String, Probe> = mapOf(
        // --- content ---
        "createNode" to Probe(Address.SCOPE) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/nodes", """{"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"$B64","slugToken":"$slug","orderIndex":0,"wrappedContentKey":"$B64"}""")
        },
        "listNodes" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/nodes") },
        "updateNode" to Probe(Address.SCOPE) { s ->
            // A complete placement write (ADR 0080 Decision 3): a rename at the root, where the world's Memory lives.
            Call(
                "PATCH", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}",
                """{"encryptedName":"$B64","slugToken":"$slug","wrappedContentKey":"$B64","epoch":0,"placementRevision":0}""",
            )
        },
        "deleteNode" to Probe(Address.SCOPE) { s -> Call("DELETE", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}") },
        "restoreNode" to Probe(Address.SCOPE, prepare = { w -> listOf(w.live, w.control).forEach { trashNode(w, it) } }) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/restore", """{"slugToken":"$slug","parentEpoch":0}""")
        },
        "listTrashedNodes" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/trash/nodes") },
        // --- re-key (ADR 0109): the world's nodes are at the current epoch, so the control's stage and commit answer
        // `409 stale_epoch` (nothing to re-key) — past the gate ---
        "listRekeyNodes" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/rekey/nodes") },
        "getNodeRekeyParts" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/rekey/nodes/${s.nodeId}") },
        "stageNodeRekey" to Probe(Address.SCOPE) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/rekey/nodes/${s.nodeId}/${UUID.randomUUID()}/parts", """{"fromEpoch":0}""")
        },
        "commitNodeRekey" to Probe(Address.SCOPE) { s ->
            Call(
                "POST", "/api/scopes/${s.scopeId}/rekey/nodes/${s.nodeId}/${UUID.randomUUID()}",
                """{"nodeEpoch":0,"epoch":0,"placementRevision":0,"metaRevision":0,"encryptedName":"$B64","slugToken":"$slug","wrappedContentKey":"$B64"}""",
            )
        },
        "getFavorites" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/favorites") },
        "putFavorites" to Probe(Address.SCOPE) { s -> Call("PUT", "/api/scopes/${s.scopeId}/favorites", """{"ciphertext":"$B64"}""") },
        // --- reports (uc.moderation.report): a member reports what their space holds ---
        "reportInScope" to Probe(Address.SCOPE) { s ->
            Call(
                "POST", "/api/scopes/${s.scopeId}/reports",
                """{"category":"TERMS_BREACH","epoch":1,"sealedReport":"$B64","items":[{"kind":"NODE","id":"${s.nodeId}","sealedShare":"$B64"}]}""",
            )
        },
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
            Call("POST", "/api/scopes/${s.scopeId}/media-batch", """{"items":[{"nodeId":"${s.nodeId}","mediaId":"${TestBlob.ownerOf(s.uploadId)}","originalUploadId":"${s.uploadId}","derivatives":[],"nodeEpoch":0}]}""")
        },
        "listNodeMedia" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/media") },
        "listNodeBuckets" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/buckets") },
        "listMediaRekey" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/media-rekey") },
        "stageMediaRekey" to Probe(Address.SCOPE) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/media-rekey/${UUID.randomUUID()}", """{"fromEpoch":0}""")
        },
        "createBucket" to Probe(Address.SCOPE) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/buckets", """{"bucketId":"${UUID.randomUUID()}","sealedName":"$B64","nodeEpoch":0}""")
        },
        "renameBucket" to Probe(Address.SCOPE) { s ->
            Call("PATCH", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/buckets/${s.bucketId}", """{"sealedName":"$B64","nodeEpoch":0}""")
        },
        "deleteBucket" to Probe(Address.SCOPE) { s -> Call("DELETE", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/buckets/${s.bucketId}") },
        "setNodeCover" to Probe(Address.SCOPE) { s ->
            Call("PUT", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/cover", """{"uploadId":"${s.uploadId}","expectedBlobId":null,"wrappedMeta":"$B64","metaRevision":0,"nodeEpoch":0}""")
        },
        "removeNodeCover" to Probe(Address.SCOPE) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/cover/removal", """{"blobId":"${UUID.randomUUID()}","wrappedMeta":"$B64","metaRevision":0,"nodeEpoch":0}""")
        },
        // --- a Memory's banner (uc.memory.banner): the cover's gate, Memories' operation ---
        "setMemoryBanner" to Probe(Address.SCOPE) { s ->
            Call(
                "PUT", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/banner",
                """{"uploadId":"${s.uploadId}","expectedBlobId":null,"expectedPosterBlobId":null,"wrappedMeta":"$B64","metaRevision":0,"nodeEpoch":0}""",
            )
        },
        "removeMemoryBanner" to Probe(Address.SCOPE) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/banner/removal", """{"blobId":"${UUID.randomUUID()}","posterBlobId":null,"wrappedMeta":"$B64","metaRevision":0,"nodeEpoch":0}""")
        },
        "moveBucketMedia" to Probe(Address.SCOPE) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/nodes/${s.nodeId}/media-moves", """{"mediaIds":["${s.mediaId}"],"targetBucketId":"${s.bucketId}"}""")
        },
        "getMediaExif" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/media/${s.mediaId}/exif") },
        "updateMediaMeta" to Probe(Address.SCOPE) { s ->
            Call("PUT", "/api/scopes/${s.scopeId}/media/${s.mediaId}/meta", """{"wrappedMeta":"$B64","metaRevision":0,"nodeEpoch":0}""")
        },
        "deleteMedia" to Probe(Address.SCOPE) { s -> Call("DELETE", "/api/scopes/${s.scopeId}/media/${s.mediaId}") },
        "restoreMedia" to Probe(Address.SCOPE) { s -> Call("POST", "/api/scopes/${s.scopeId}/media/${s.mediaId}/restore") },
        "purgeMedia" to Probe(Address.SCOPE, prepare = { w -> listOf(w.live, w.control).forEach { trashMedia(w, it) } }) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/media/${s.mediaId}/purge")
        },
        "presignBlobDownloads" to Probe(Address.SCOPE) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/blob-downloads-batch", """{"items":[{"blobId":"${s.blobId}"}]}""")
        },
        // --- files (the world's node is a Memory, so the control's complete answers `422 illegal_target` — past the gate) ---
        "completeFile" to Probe(Address.SCOPE) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/files/${s.nodeId}/content", """{"uploadId":"${s.uploadId}","nodeEpoch":0}""")
        },
        "listFiles" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/files") },
        // --- Expenses (the world's node is a Memory, so the control's call answers `404 node_not_found` — no Pot of
        // that id, past the gate, whose non-member answer is `scope_not_found`) ---
        "listPotRevisions" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/pots/${s.nodeId}/revisions") },
        "appendPotRevisions" to Probe(Address.SCOPE) { s ->
            Call(
                "POST", "/api/scopes/${s.scopeId}/pots/${s.nodeId}/revisions",
                """{"nodeEpoch":0,"revisions":[{"entryId":"${UUID.randomUUID()}","revision":1,"tombstone":false,"sealedBody":"$potBody","wrappedRevisionKey":"$B64"}]}""",
            )
        },
        "erasePotRecord" to Probe(Address.SCOPE) { s ->
            Call(
                "POST", "/api/scopes/${s.scopeId}/pots/${s.nodeId}/erasures",
                """{"nodeEpoch":0,"entryId":"${UUID.randomUUID()}","revision":2,"sealedBody":"$potBody","wrappedRevisionKey":"$B64"}""",
            )
        },
        "listPotRekey" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/pots/${s.nodeId}/rekey") },
        "listPotReceipts" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/pots/${s.nodeId}/receipts") },
        "completePotReceipt" to Probe(Address.SCOPE) { s ->
            Call(
                "POST", "/api/scopes/${s.scopeId}/pots/${s.nodeId}/receipts",
                """{"nodeEpoch":0,"entryId":"${UUID.randomUUID()}","receiptId":"${UUID.randomUUID()}","uploadId":"${s.uploadId}"}""",
            )
        },
        "stagePotRekey" to Probe(Address.SCOPE) { s ->
            Call(
                "POST", "/api/scopes/${s.scopeId}/pots/${s.nodeId}/rekey/${UUID.randomUUID()}",
                """{"fromEpoch":0,"revisions":[{"entryId":"${UUID.randomUUID()}","revision":1,"wrappedRevisionKey":"$B64"}]}""",
            )
        },
        // --- scopes ---
        "listScopeMembers" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/members") },
        "addGroupMember" to Probe(Address.SCOPE) { s -> Call("POST", "/api/scopes/${s.scopeId}/members", """{"username":"ghost",${TestGrants.grantJson()}}""") },
        "resolveGroupMember" to Probe(Address.SCOPE) { s ->
            Call("POST", "/api/scopes/${s.scopeId}/members/resolve", """{"username":"ghost"}""")
        },
        "setMemberRole" to Probe(Address.SCOPE) { s -> Call("PATCH", "/api/scopes/${s.scopeId}/members/$someId", """{"role":"OWNER"}""") },
        "removeMember" to Probe(Address.SCOPE) { s -> Call("DELETE", "/api/scopes/${s.scopeId}/members/$someId") },
        "listScopeEpochs" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/epochs") },
        // Any member reads a TENANT's open invites (ADR 0111 decision 2); the control's live group holds none: `200 []`.
        "listOpenInvites" to Probe(Address.SCOPE) { s -> Call("GET", "/api/scopes/${s.scopeId}/open-invites") },
        "rotateScope" to Probe(Address.SCOPE) { s -> Call("POST", "/api/scopes/${s.scopeId}/epochs", """{"epoch":1,"wrappedPrev":"$B64","grants":[]}""") },
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
        "createPersonalScope" to Probe(Address.HOST) { s -> Call("POST", "/api/scopes", """{"scopeId":"${UUID.randomUUID()}","wrappedGrant":"$B64",${TestGrants.SIGNATURE_JSON}}""", host = s.domain) },
        "createGroup" to Probe(Address.HOST) { s ->
            Call("POST", "/api/scopes/groups", """{"scopeId":"${UUID.randomUUID()}","encryptedName":"$B64","wrappedGrant":"$B64",${TestGrants.SIGNATURE_JSON}}""", host = s.domain)
        },
        // --- sharing (curation) ---
        "createShare" to Probe(Address.NODE) { s ->
            Call("POST", "/api/shares", """{${ShareWriteProof.newShare()},"nodeId":"${s.nodeId}","role":"READ","wrap":"$B64","sealedName":"$B64",${ShareWriteProof.recovery()},"nodeEpoch":0}""")
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
        "setShareSealedName" to Probe(Address.SHARE) { s -> Call("PUT", "/api/shares/${s.shareId}/sealed-name", """{"sealedName":"$B64"}""") },
        "reissueShare" to Probe(Address.SHARE) { s ->
            Call(
                "POST", "/api/shares/${s.shareId}/reissue",
                """{${ShareWriteProof.newShare()},"wrap":"$B64","sealedName":"$B64","sealedShareKey":"$B64","sealedShareKeyEpoch":0,"nodeEpoch":0}""",
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
            Call("POST", "/api/tenants/${s.tenantId}/member-invites", """{${TestGrants.memberInviteJson(s.tenantId, "invitee-${UUID.randomUUID()}@example.com", epoch = 0)}}""")
        },
        "listMemberInvites" to Probe(Address.TENANT) { s -> Call("GET", "/api/tenants/${s.tenantId}/member-invites") },
        "reissueMemberInvite" to Probe(Address.TENANT) { s ->
            Call("POST", "/api/tenants/${s.tenantId}/member-invites/$someId/reissue", """{${TestGrants.memberInviteJson(s.tenantId, "invitee-${UUID.randomUUID()}@example.com", epoch = 0)}}""")
        },
        "revokeMemberInvite" to Probe(Address.TENANT) { s -> Call("DELETE", "/api/tenants/${s.tenantId}/member-invites/$someId") },
        "removeTenantMember" to Probe(Address.TENANT) { s -> Call("DELETE", "/api/tenants/${s.tenantId}/members/$someId") },
        "setTenantMemberRole" to Probe(Address.TENANT) { s -> Call("PATCH", "/api/tenants/${s.tenantId}/members/$someId", """{"role":"OWNER"}""") },
        "getTenantTerms" to Probe(Address.TENANT) { s -> Call("GET", "/api/tenants/${s.tenantId}/terms") },
        // A member's picture (ADR 0084): tenant-gated like the directory. The control reaches the handler and answers
        // `404 avatar_not_found` for the invented member - a code the gate's own `tenant_not_found` is not.
        "getMemberAvatar" to Probe(Address.TENANT) { s -> Call("GET", "/api/tenants/${s.tenantId}/members/$someId/avatar") },
        "updateTenantTerms" to Probe(Address.TENANT) { s ->
            Call("PUT", "/api/tenants/${s.tenantId}/terms", """{"terms":{"de":"Bedingungen","en":"Terms"}}""")
        },
        // The live tenant publishes no terms, so the control's accept answers `409 terms_revision_changed` — past the gate.
        "acceptTenantTerms" to Probe(Address.TENANT) { s -> Call("POST", "/api/tenants/${s.tenantId}/terms/accept", """{"revision":1,"operatorRevision":1,"variant":"FULL"}""") },
        "getTenantMailSettings" to Probe(Address.TENANT) { s -> Call("GET", "/api/tenants/${s.tenantId}/mail-settings") },
        "updateTenantMailSettings" to Probe(Address.TENANT) { s ->
            Call("PUT", "/api/tenants/${s.tenantId}/mail-settings", """{"tls":false,"enabled":false}""")
        },
        "getTenantCapabilities" to Probe(Address.TENANT) { s -> Call("GET", "/api/tenants/${s.tenantId}/capabilities") },
        "listTenantCapabilitySwitches" to Probe(Address.TENANT) { s -> Call("GET", "/api/tenants/${s.tenantId}/capability-switches") },
        // The control switches an entry back on that is on: it changes nothing and answers 200, past the gate.
        "setTenantCapabilitySwitch" to Probe(Address.TENANT) { s ->
            Call("PUT", "/api/tenants/${s.tenantId}/capability-switches/core.group-space", """{"off":false}""")
        },
        // The control clears a requirement that is not set: it changes nothing and answers 200, past the gate.
        "clearTenantCapabilityRequirement" to Probe(Address.TENANT) { s ->
            Call("DELETE", "/api/tenants/${s.tenantId}/capability-switches/core.public-shares/requirements/linkMaxDays")
        },
        "setTenantCapabilityRequirement" to Probe(Address.TENANT) { s ->
            Call("PUT", "/api/tenants/${s.tenantId}/capability-switches/core.public-shares/requirements/linkMaxDays", """{"value":3650}""")
        },
        "getTenantStorage" to Probe(Address.TENANT) { s -> Call("GET", "/api/tenants/${s.tenantId}/storage") },
        "checkTenantStorageFit" to Probe(Address.TENANT) { s -> Call("POST", "/api/tenants/${s.tenantId}/storage-fit", """{"bytes":1}""") },
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
            "presignShareBlobUploadWindows", "listShareMedia", "completeShareMedia", "deleteShareMedia",
            "createShareNode", "updateShareNode", "abandonShareNode", "listShareFiles", "completeShareFile", "ensureShareDropFolder",
            "listSharePotRevisions", "appendSharePotRevisions", "listSharePotReceipts", "completeSharePotReceipt",
            "reportInShare", "getShareOrderNotice",
            "previewTenantInvite", "acceptTenantInvite", "registerAndAcceptTenantInvite", "acceptTenantOwnership",
        )
        reason(
            "Server-Owner route: @RolesAllowed(SERVER_OWNER) plus its own lifecycle guards; it administers tenants, " +
                "it does not act inside one.",
            "createTenant", "listTenants", "resendTenantInvite", "reassignTenantInvite", "changeTenantDomain",
            "revokeTenant", "suspendTenant", "reactivateTenant", "setTenantStorageQuota", "clearTenantStorageQuota",
            "deleteTenant", "stopTenantDeletion", "inviteTenantOwner",
            // the account erasure's Server Owner door (ADR 0101): it acts on an account across every tenant
            "listAccountErasures", "eraseAccount", "stopAccountErasure",
            "getInstanceRelease",
            "getInstanceStoreProvisioning",
            "getInstanceConfigSecret",
            "getInstanceStore", "setInstanceStore", "discardPendingInstanceStore", "checkInstanceStore",
            "listInstanceCapabilities", "setInstanceCapability",
            "setInstanceCapabilityRequirement", "clearInstanceCapabilityRequirement",
            "listInstanceExternalServices", "setInstanceExternalService", "clearInstanceExternalService",
            "getInstanceReleaseNotices", "setInstanceReleaseCheck",
            "getInstanceMailSettings", "updateInstanceMailSettings", "confirmInstanceMail",
            "getInstanceModeration", "appointModerator", "removeModerator", "mintModerationEpoch",
            "getModerationSteps", "declineModerationProposal", "blockAccountSignIn", "unblockAccountSignIn",
            // an authority's order, the Server Owner's door (ADR 0093 § 12): it acts on ids across the instance
            "enterInstanceModerationOrder",
            "getStoreReconcile", "confirmStoreExclusiveBucket", "withdrawStoreExclusiveBucket", "pauseStoreReconcile",
            "resumeStoreReconcile",
            "getInstanceOperator", "setInstanceOperator", "clearInstanceOperator", "confirmOperatorNotification",
            "getInstanceGuestWriteRecord", "setInstanceGuestWriteRecord", "eraseGuestWriteRecordsOfIdentity",
            // the backups' retention, B (ADR 0056 decision 8): one instance-wide value
            "getInstanceBackupRetention", "setInstanceBackupRetention",
        )
        reason(
            "account-wide: addresses the caller's own account, no space; a rotation job it originates simply waits " +
                "where claims are refused.",
            "getAccountKeyMaterial", "addAccountWrap", "deleteAccountWrap", "getIdentityPins", "writeIdentityPins",
            "listIdentityPinEntries", "getDeviceRegister", "putDeviceRegister", "rotateAccountKeys", "changePassword", "listSessionFamilies",
            "revokeOtherSessionFamilies", "revokeSessionFamily", "delegatePairingSession", "sealPairingChannel",
            "clearDeclinesForAcceptedKey", "getAccountEmail", "confirmEmail", "listRotationScopeStates",
            "getOwnAccountErasurePreview", "eraseOwnAccount",
        )
        reason(
            "Host-resolved and self-scoped: reads or writes only the caller's own rows on the Host's own tenant, so " +
                "the Host filter is a correct suspension gate for exactly these (cr D10).",
            "listMyScopes", "listMyGroupInvitations", "getMyProfile", "updateMyProfile", "requestEmailConfirmation",
            "setMyAvatar", "clearMyAvatar", "requestEmailChange", "confirmEmailChange",
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
        reason(
            "moderator route: MODERATOR role + key proof (ADR 0099 § 2). It acts for the Server Owner across the " +
                "instance, inside no space, so no space's gate applies; who may call it is ModerationProofPostureSweepTest's.",
            "stepDownAsModerator", "listPendingModerationDeliveries", "putModerationDelivery",
            "listModerationCases", "getModerationCase", "dismissModerationCase", "takeModerationMeasure", "liftModerationMeasure",
            "proposeModerationStep", "extendModerationHold", "barModerationReporter",
            // the statement's recipients and an objection's decision (cr-statement-of-reasons-and-objection)
            "getModerationStatementRecipients", "decideModerationObjection",
            // an authority's order, the deferral, the handover and the Art. 18 export (cr-authority-orders-and-art18-reports):
            // an order names a share by id across the instance; the rest act on a case, which outlives its space
            "enterModerationOrder", "listModerationOrders", "deferModerationStatement", "recordModerationHandover",
            "getModerationCaseExport",
            // the review reads (cr-moderation-review-and-measures decision 6): a case's held snapshot, read from the hold —
            // the case outlives its space, and a suspended tenant's case is reviewed all the same (ADR 0093 § 1)
            "getCaseReview", "presignCaseBlobs", "listCaseMedia", "listCaseFiles", "listCasePotRevisions",
        )
        reason(
            "moderator route before the key: the caller's own appointment, its address confirmation, its own grants and " +
                "the proof's challenge — account-wide, inside no space, and carrying no proof because a newcomer holds no " +
                "key yet (ModerationProofPostureSweepTest lists each).",
            "getMyModeratorAppointment", "acceptModeratorAppointment", "sendModeratorAddressCode",
            "confirmModeratorAddress", "listMyModerationGrants", "createModerationChallenge",
        )
        reason(
            "the affected member's own statements of reasons and their objection (ADR 0093 § 7): account-wide, keyed by " +
                "the caller's recipient row — a member the statement does not name reads nothing and objects to nothing " +
                "(StatementsSliceTest); the measure outlives its space, so no space's gate applies.",
            "listMyStatements", "objectToStatement",
        )
        reason(
            "public: the current epoch's public bundle, which every reporter — mostly a guest — seals to (ADR 0099 § 2); " +
                "it names no space and no account.",
            "getModerationKey",
        )
        reason(
            "public: the values the Server Owner published about who runs the instance (ADR 0093 § 11, ADR 0035 " +
                "decision 7) — instance-wide, the same on every host; it names no space and no account.",
            "getOperatorDetails",
        )
        reason(
            "public: the operator section of the terms (ADR 0035 decision 6), fixed text rendered with those published " +
                "values — publicly available as DSA Art. 14(1) asks, instance-wide; it names no space and no account.",
            "getOperatorTerms",
        )
        reason(
            "public: the instance privacy notice (ADR 0035 decision 8, GDPR Art. 13), fixed text rendered with the " +
                "published values and the switches active for the host's space — information owed at collection, so " +
                "before any account; it reads the host's space only to pick its switches and names no account.",
            "getPrivacyNotice",
        )
    }

    /**
     * A POST that reads, and a PUT that takes the READ gate on purpose: a pin (`putFavorites`) is the member's own sealed
     * preference, not a change to the scope, so a group waiting for its deletion still stores it (ADR 0068 §10). A report
     * (`reportInScope`) changes nothing in the space either — whoever can see an item can report it (ADR 0093 § 1), a
     * viewer included, and a group waiting for its deletion still shows what it holds.
     */
    private val readsByException = setOf("resolveGroupMember", "presignBlobDownloads", "putFavorites", "reportInScope")
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
        val dummy = ScopeAccessWorld.Space(someId, "d.example.com", someId, someId, someId, someId, MemoriesWorldContent.NAMES.associateWith { someId })
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
            Address.NODE -> s.withContent(MemoriesWorldContent.NODE_ID, fresh)
            Address.SHARE -> s.withContent(MemoriesWorldContent.SHARE_ID, Tokens.random()) // a share id is 43 base64url chars, not a uuid
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

    private fun contractOperations() = GeneratedApiOperations.contractOperations()
}
