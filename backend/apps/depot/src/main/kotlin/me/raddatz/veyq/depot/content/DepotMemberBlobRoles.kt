package me.raddatz.veyq.depot.content

import me.raddatz.veyq.core.blob.MemberBlobRoles
import jakarta.enterprise.context.ApplicationScoped

/** The role a member uploads a Depot blob in, for core's member start (inbox #366): a file's bytes, nothing else. */
@ApplicationScoped
class DepotMemberBlobRoles : MemberBlobRoles {
    override fun roles(): Set<String> = setOf(DepotBlobRoles.FILE_CONTENT)
}
