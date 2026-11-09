package me.raddatz.veyq.mobile

import me.raddatz.veyq.mobile.crypto.Naming
import me.raddatz.veyq.mobile.expenses.PotCreate
import me.raddatz.veyq.mobile.expenses.PotRekey
import me.raddatz.veyq.mobile.expenses.PotSession
import me.raddatz.veyq.mobile.session.NodeRekey

/**
 * Every node kind this build knows, as core's writes need it: the composition root of kinds (the web's registry, filled by
 * each application's `register.ts`), the one place core's session meets an application. Core's session never names a
 * kind; the apps hand it these.
 */
object AppKinds {
    /**
     * The input a kind folds a node's name to for its slug token (the web's `registry.tokenInputFor`: the kind's own slug
     * rule — a file's stricter `fileNameSlug`, else `slugify` — behind its application's route slug where it claimed one,
     * pinned by `v1.json#name_folding`); null for a kind this build does not know, whose names it then does not write.
     */
    fun tokenInput(kind: String, name: String): String? = when (kind) {
        FOLDER, DROPBOX, SHARE_BOOKMARK -> Naming.slugify(name)
        FILE -> Naming.fileNameSlug(name)
        MEMORY, CHAPTER -> "memories/" + Naming.slugify(name)
        PotSession.KIND -> PotCreate.tokenInput(name)
        else -> null
    }

    /**
     * How the app re-keys a node of a kind (ADR 0114): core's kinds and Depot's with nothing of their own under the node's
     * key (their blobs are core's, the re-key moves them), a Pot its revisions' keys (`PotRekey`); Memories' media rows are
     * the web's re-key, so a Memory and a chapter stay due for a web device.
     */
    val rekeys = NodeRekey.Kinds { kind ->
        when (kind) {
            FOLDER, FILE, DROPBOX, SHARE_BOOKMARK -> NodeRekey.ContentRekey.NONE
            PotSession.KIND -> PotRekey.content
            else -> null
        }
    }

    /** What a tenant session re-keys and folds with (`TenantSession.rekeyRules`). */
    val rules = NodeRekey.Rules(rekeys, ::tokenInput)

    private const val FOLDER = "FOLDER"
    private const val FILE = "FILE"
    private const val DROPBOX = "DEPOT_DROPBOX"
    private const val SHARE_BOOKMARK = "SHARE_BOOKMARK"
    private const val MEMORY = "MEMORY"
    private const val CHAPTER = "CHAPTER"
}
