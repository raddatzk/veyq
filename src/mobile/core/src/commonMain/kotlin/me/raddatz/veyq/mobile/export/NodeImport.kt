package me.raddatz.veyq.mobile.export

import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * What a kind supplies so core can import it (ADR 0152 decision 3; the web's `KindDescriptor.importContent`,
 * `importsAsFolder`, `validateContent` and `contentSchema`): core walks the tree and creates the folders; every other kind
 * is its application's [Hook], reached through `AppKinds.imports` — core never reads a kind's content but its payload
 * references.
 */
object NodeImport {
    /** What is wrong with a content (the web's `ContentProblem`): `invalid_manifest` or `too_large`. */
    class Problem(val refusal: ImportRefused.Refusal, val message: String)

    /**
     * What a hook is handed for one node: where to create it ([parentId] null: the space's top) and under which [name] —
     * the exported one, made free among the target's siblings —, the node as the manifest has it, and its payloads, each
     * checked before it is handed out ([payload]).
     */
    class Context(
        /** The importer's session (null only where a test plays the target and its kinds). */
        val tenant: TenantSession?,
        val scopeId: String,
        val parentId: String?,
        val name: String,
        val node: Manifest.Node,
        private val payloads: SubtreeImport.Payloads,
        /** Fraction of THIS node's payload bytes uploaded. */
        val progress: (Double) -> Unit,
        /** Scratch that lives for one import: a hook keeps what it reads once here. */
        val cache: MutableMap<String, Any>,
    ) {
        /** The importer's session, which every real hook writes through. */
        fun session(): TenantSession = tenant ?: throw Failed("no_session")

        /** [ref] checked — path, size, SHA-256, a ZIP entry's CRC-32 — and readable by offset; throws [ImportRefused]. */
        suspend fun payload(ref: Manifest.Payload): SubtreeImport.Checked = payloads.take(ref)
    }

    /**
     * What one node's import came to: the node made ([nodeId]); none because the name is held there ([nameTaken] — core
     * tries the next free name, the web's `slug_taken`); or none for [failure] (a code), which stops the import.
     */
    class Made(val nodeId: String?, val nameTaken: Boolean = false, val failure: String? = null)

    /** A kind's import failed: [code] says how; core rolls back what the import made. */
    class Failed(val code: String) : Exception("import_failed: $code")

    /**
     * A kind's import: the [application] its content belongs to and the content [schema] it reads (newer content is left
     * out, `newer_content`), its content's check ([validate], run on every node before anything is written), and either
     * [asFolder] (it imports as a plain folder: a drop box, the web's `importsAsFolder`) or its own [import].
     */
    class Hook(
        val application: String,
        val schema: Int,
        val validate: (JsonObject) -> Problem? = { null },
        val asFolder: Boolean = false,
        val import: (suspend (Context) -> Made)? = null,
    )
}
