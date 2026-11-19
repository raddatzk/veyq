package me.raddatz.veyq.mobile.export

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json

/**
 * Import a download (ADR 0082 decisions 6–10, ADR 0152 decision 2), the web's `core/export/importSubtree.ts`: a member's
 * flow, and everything lands under the importer. Nothing of the source is carried but names, order, content and plaintext
 * metadata: ids, keys and wraps are made fresh under the target's keys, and a name taken among the target's siblings gets
 * `Name (2)`. Before anything is written, [open] refuses an archive that is not sound — the ZIP's structure, the manifest
 * against the contract and its caps, every payload present at its declared size.
 */
object SubtreeImport {
    /** An opened download: where its files are read and its checked manifest. */
    class Opened(val source: ImportSource, val manifest: Manifest)

    /** Any refusal of the ZIP layer as the import names it: a structure the reader does not accept is a damaged archive. */
    internal fun refusalOf(e: ZipReader.ZipError, detail: String? = null): ImportRefused = when (e.code) {
        ZipReader.ZipError.Code.CRC_MISMATCH -> ImportRefused(ImportRefused.Refusal.CHECKSUM, e.message ?: "checksum", detail)
        ZipReader.ZipError.Code.MISSING -> ImportRefused(ImportRefused.Refusal.PAYLOAD_MISSING, e.message ?: "missing", detail)
        else -> ImportRefused(ImportRefused.Refusal.DAMAGED_ARCHIVE, e.message ?: "damaged", detail)
    }

    /**
     * Recognise and open a download by its CONTENT (never its name): its source read and checked ([ZipReader.open]), the
     * manifest found (`.veyq/manifest.json`; version 1's `manifest.json` in a ZIP only), held to its cap while read, parsed
     * ([Manifest.parse]), and every payload it names present at the size it says. Throws [ImportRefused].
     */
    @Throws(Exception::class)
    suspend fun open(input: ImportInput): Opened {
        val source = when (input) {
            is ImportInput.Zip -> try {
                ImportSource.zip(ZipReader.open(input.file.size, input.file::read))
            } catch (e: ZipReader.ZipError) {
                if (e.code == ZipReader.ZipError.Code.NOT_ZIP) throw ImportRefused(ImportRefused.Refusal.NOT_AN_EXPORT, "not a ZIP archive")
                throw refusalOf(e)
            }
            is ImportInput.Folder -> ImportSource.folder(input.files)
        }
        // version 2 keeps it beside the tree, version 1 at the root (a ZIP only: version 1 was never unpacked by veyq)
        val at = when {
            source.has(Manifest.PATH) -> Manifest.PATH
            source.isZip && source.has(Manifest.PATH_V1) -> Manifest.PATH_V1
            else -> throw ImportRefused(ImportRefused.Refusal.NOT_AN_EXPORT, "no manifest")
        }
        if ((source.size(at) ?: 0) > Manifest.MAX_BYTES) throw ImportRefused(ImportRefused.Refusal.TOO_LARGE, "the manifest is too large")
        val bytes = try {
            val parts = ArrayList<ByteArray>()
            var length = 0L
            source.stream(at) { chunk ->
                length += chunk.size
                if (length > Manifest.MAX_BYTES) throw ImportRefused(ImportRefused.Refusal.TOO_LARGE, "the manifest is too large")
                parts += chunk
            }
            val out = ByteArray(length.toInt())
            var o = 0
            for (p in parts) {
                p.copyInto(out, o)
                o += p.size
            }
            out
        } catch (e: ZipReader.ZipError) {
            throw refusalOf(e)
        }
        val text = bytes.decodeToString()
        if (!text.encodeToByteArray().contentEquals(bytes)) throw ImportRefused(ImportRefused.Refusal.INVALID_MANIFEST, "the manifest is not UTF-8")
        val json = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw ImportRefused(ImportRefused.Refusal.INVALID_MANIFEST, "the manifest is not JSON")
        }
        val manifest = Manifest.parse(json)
        if ((at == Manifest.PATH) != (manifest.version >= 2)) {
            throw ImportRefused(ImportRefused.Refusal.INVALID_MANIFEST, "a version ${manifest.version} manifest at $at")
        }
        // every file the manifest names is there, at the size it says - before the dry run
        fun walk(n: Manifest.Node) {
            for (p in n.payloads) {
                val size = source.size(p.path) ?: throw ImportRefused(ImportRefused.Refusal.PAYLOAD_MISSING, "the download lacks ${p.path}", p.path)
                if (size != p.size) throw ImportRefused(ImportRefused.Refusal.SIZE_MISMATCH, "${p.path} is not the size the manifest says", p.path)
            }
            n.children.forEach(::walk)
        }
        walk(manifest.root)
        return Opened(source, manifest)
    }

    /** A payload whose bytes were read once and found to be what the manifest says: its digest, and its bytes by offset. */
    class Checked(val ref: Manifest.Payload, val sha256: ByteArray, private val source: ImportSource) {
        /** Up to [length] bytes from [from] (fewer only at its end), as an upload reads a picked file. */
        suspend fun read(from: Long, length: Int): ByteArray = source.read(ref.path, from, length)
    }

    /**
     * The payloads of a download, each checked before a hook takes it (decision 10; the web's `archivePayload`): its path
     * safe, its size the manifest's, and its bytes read once — the SHA-256 the manifest names compared, a ZIP entry's CRC-32
     * checked by the reader at the end ([ZipReader.stream]). One payload ahead (the web's inbox #557): once a payload's own
     * check is done, the check of the payload after it in the walk's order starts on [scope], so it runs while the hook
     * uploads this one. Only ever one ahead; a refusal found ahead is thrown when — and only if — a hook asks for that payload,
     * after that request's own path and size checks, so what an import refuses and in which order is unchanged.
     */
    class Payloads(private val opened: Opened, private val scope: CoroutineScope? = null) {
        private val source = opened.source
        private val order: List<Manifest.Payload> = run {
            val out = ArrayList<Manifest.Payload>()
            fun walk(n: Manifest.Node) {
                out += n.payloads
                n.children.forEach(::walk)
            }
            walk(opened.manifest.root)
            out
        }
        private val ahead = HashMap<String, Deferred<Checked>>()

        private fun refusal(ref: Manifest.Payload): ImportRefused? {
            val safe = if (opened.manifest.version == 1) Manifest.isSafePayloadPathV1(ref.path) else ArchiveName.isPath(ref.path)
            if (!safe) return ImportRefused(ImportRefused.Refusal.UNSAFE_PATH, "unsafe payload path", ref.path)
            val size = source.size(ref.path) ?: return ImportRefused(ImportRefused.Refusal.PAYLOAD_MISSING, "the download lacks ${ref.path}", ref.path)
            if (size != ref.size) return ImportRefused(ImportRefused.Refusal.SIZE_MISMATCH, "${ref.path} is not the size the manifest says", ref.path)
            return null
        }

        /** One pass over [ref]'s bytes: its SHA-256, and a ZIP entry's CRC-32 checked by the reader at the end. */
        private suspend fun check(ref: Manifest.Payload): Checked {
            val hasher = Sha256()
            try {
                source.stream(ref.path) { hasher.update(it) }
            } catch (e: ZipReader.ZipError) {
                throw refusalOf(e, ref.path)
            }
            val digest = hasher.digest()
            if (ref.sha256 != null && hex(digest) != ref.sha256) {
                throw ImportRefused(ImportRefused.Refusal.CHECKSUM, "${ref.path} does not match its checksum", ref.path)
            }
            return Checked(ref, digest, source)
        }

        /** The check of the payload after [ref] in the walk's order, started once [ref]'s own is done; a sound one only. */
        private fun checkNext(ref: Manifest.Payload) {
            val s = scope ?: return
            val next = order.getOrNull(order.indexOfFirst { it.path == ref.path } + 1) ?: return
            if (next.path in ahead || refusal(next) != null) return
            ahead[next.path] = s.async(start = CoroutineStart.DEFAULT) { check(next) }
        }

        /** [ref] checked, for a hook to take: throws [ImportRefused] where it is not what the manifest says. */
        suspend fun take(ref: Manifest.Payload): Checked {
            refusal(ref)?.let { throw it }
            val pending = ahead.remove(ref.path)
            val checked = pending?.await() ?: check(ref)
            checkNext(ref)
            return checked
        }

        /** Stops a check under way ahead (a cancel, a failure): nobody will ask for it. */
        fun drop() {
            ahead.values.forEach { it.cancel() }
            ahead.clear()
        }
    }


    internal fun hex(b: ByteArray): String = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
