package me.raddatz.veyq.core.blob

import jakarta.enterprise.context.ApplicationScoped

/**
 * The one application stand-in of `:core`'s test application: the roles core's own slice tests start a member's
 * upload in, spelled as the wire spells Memories' (`TestBlob`'s default `ORIGINAL`, and a `COVER`). With no
 * application in `:core`, [MemberBlobRoles] declares nothing and every member start is refused (inbox #366, fail
 * closed) — so the tests of what core does with a started upload (the quota, the windows, the presign domain) need a
 * declared role, and this is it, by the test sources only.
 *
 * `CoreAloneSliceTest` excludes it (`CoreAloneProfile`, `quarkus.arc.exclude-types`), so the core-alone pin still boots
 * core with nothing declared and shows the refusal.
 */
@ApplicationScoped
class CoreTestMemberBlobRoles : MemberBlobRoles {
    override fun roles(): Set<String> = setOf("ORIGINAL", "COVER")
}
