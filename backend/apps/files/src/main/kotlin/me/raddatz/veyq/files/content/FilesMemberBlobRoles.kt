package me.raddatz.veyq.files.content

import me.raddatz.veyq.core.blob.MemberBlobRoles
import jakarta.enterprise.context.ApplicationScoped

/** The role a member uploads a Files blob in, for core's member start (inbox #366): a file's bytes, nothing else. */
@ApplicationScoped
class FilesMemberBlobRoles : MemberBlobRoles {
    override fun roles(): Set<String> = setOf(FilesBlobRoles.FILE_CONTENT)
}
