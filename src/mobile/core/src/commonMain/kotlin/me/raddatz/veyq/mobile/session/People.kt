package me.raddatz.veyq.mobile.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.api.models.ScopeMember
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.unb64

/** One person as the interface shows them: the real name when it opens, else the username (ADR 0083 decision 9). */
data class Person(val userId: String, val username: String, val displayName: String)

/**
 * The people of a tenant, a port of `src/web/src/core/people/directory.ts`: a member's real name is sealed under a value
 * key of its own whose wrap the tenant key holds (`member-name`, bound to (tenant scope, member)), so it is read from the
 * TENANT scope's member list whatever scope is on screen. All on the device; the server serves ciphertext.
 */
internal object People {
    fun nameContext(tenantId: String, userId: String) = Context("member-name", "scopeId" to tenantId, "userId" to userId)

    /** The real name of [m] (`firstName lastName`, trimmed), or null when none opens. */
    fun realName(joins: JoinRecords, tenantId: String, tenantKey: ByteArray, epoch: Long, m: ScopeMember): String? {
        val sealed = m.sealedName ?: return null
        val wrapped = m.wrappedNameValueKey ?: return null
        val plain = joins.openValueField(tenantId, epoch, tenantKey, nameContext(tenantId, m.userId), unb64(wrapped), unb64(sealed)) ?: return null
        val o = runCatching { Json.parseToJsonElement(plain.decodeToString()) as? JsonObject }.getOrNull() ?: return null
        val first = (o["firstName"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val last = (o["lastName"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return "$first $last".trim().ifEmpty { null }
    }

    /** Every member of the tenant by user id, named by the display rule. */
    fun of(joins: JoinRecords, tenantId: String, tenantKey: ByteArray, epoch: Long, members: List<ScopeMember>): Map<String, Person> =
        members.associate { m -> m.userId to Person(m.userId, m.username, realName(joins, tenantId, tenantKey, epoch, m) ?: m.username) }
}
