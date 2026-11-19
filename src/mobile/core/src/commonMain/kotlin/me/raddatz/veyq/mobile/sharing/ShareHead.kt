package me.raddatz.veyq.mobile.sharing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.raddatz.veyq.mobile.api.apis.SharingApi
import me.raddatz.veyq.mobile.api.models.PublicShare
import me.raddatz.veyq.mobile.api.models.ShareTerms
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.b64url
import me.raddatz.veyq.mobile.crypto.unb64

/**
 * The kind-independent half of a resolved link (the web's `resolvePublicShare.ts` `PublicShareHead`): the unlocked
 * answer, `K_share`, the root's name and the note opened under it, the role and the root's kind. [kShare] is key material:
 * it lives in the open link's model only ([ShareGates]) and is zeroed when it closes; [toString] names nothing of it.
 */
class ShareHead internal constructor(
    val origin: String,
    val shareId: String,
    val share: PublicShare,
    internal val kShare: ByteArray,
    val scopeId: String,
    val name: String,
    /** The curator's note per locale ([pickLocaleText] picks the viewer's). */
    val noteByLocale: Map<String, String>,
    /** The digest of the note's plaintext where it asks to be accepted before the share opens; null otherwise. */
    val noteAcceptance: String?,
    val role: String,
    /** The root's kind (`PublicShare.rootKind`); null when the server names none (a root in the trash). */
    val rootKind: String?,
) {
    /** The epoch the root's key was minted at, which a guest's write under it states (0 where unserved). */
    val rootEpoch: Int get() = share.rootEpoch ?: 0
    override fun toString(): String = "ShareHead($origin, $role, $rootKind)"
}

/** Why a resolve did not yield a head (the web's three error classes). */
sealed class ShareRefused(message: String) : Exception(message) {
    /** The link asks for its password: the form, with [pwSalt] for the token. */
    class PasswordRequired(val pwSalt: String) : ShareRefused("password_required")
    /** The token given was wrong: the form again. */
    class PasswordIncorrect : ShareRefused("password_incorrect")
    /** Unknown, revoked, expired, re-keyed away or a key that opens nothing — or, [unreachable], no answer at all. */
    class Unavailable(val unreachable: Boolean) : ShareRefused(if (unreachable) "unreachable" else "not_found")
}

/** The resolve and what it opens (the web's `resolvePublicShare`, `openShareNote`, `fetchShareTerms`). */
object ShareResolve {
    /** The seals under `K_share` name their purpose only (ADR 0072 decision 2): `K_share` is unique to one share. */
    internal val SHARE_MEMORY_NAME = Context("share-memory-name")
    internal val SHARE_NOTE = Context("share-note")

    /**
     * The head of link [shareId] at [client]'s instance under [kShare] (a copy is kept: the caller keeps its own), with
     * the password gate's [pwToken]. Throws [ShareRefused].
     */
    suspend fun resolve(client: GuestClient, shareId: String, kShare: ByteArray, pwToken: String?): ShareHead {
        val share = try {
            val r = SharingApi(client.origin, client.http).resolveShare(shareId, pwToken)
            when {
                r.status == 403 -> throw ShareRefused.PasswordIncorrect()
                !r.success -> throw ShareRefused.Unavailable(unreachable = false)
                else -> r.body()
            }
        } catch (e: ShareRefused) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ShareRefused.Unavailable(unreachable = true)
        }
        return headOf(client, shareId, kShare, share)
    }

    private fun headOf(client: GuestClient, shareId: String, kShare: ByteArray, share: PublicShare): ShareHead {
        // a locked share serves only the challenge; locked is "no sealed name", never "no wrap" (WRITE_ONLY has none)
        if (share.passwordRequired && share.sealedName == null) throw ShareRefused.PasswordRequired(share.pwSalt ?: "")
        val scopeId = share.scopeId ?: throw ShareRefused.Unavailable(false)
        val sealedName = share.sealedName ?: throw ShareRefused.Unavailable(false)
        val name = try {
            client.suite.openField(SHARE_MEMORY_NAME, kShare, unb64(sealedName)).decodeToString()
        } catch (e: Exception) {
            throw ShareRefused.Unavailable(false) // the id was valid, the key in the link does not match
        }
        val note = share.sealedNote?.let { openNote(client, kShare, it) }
        val acceptance = note?.takeIf { it.mustAccept && hasText(it.texts) }?.digest
        return ShareHead(
            client.origin, shareId, share, kShare.copyOf(), scopeId, name, note?.texts ?: emptyMap(), acceptance, share.role.value, share.rootKind,
        )
    }

    /** A curator note opened under `K_share` (the web's `openShareNote` + `decodeShareNote`): null for a seal or shape that does not open. */
    internal class Note(val texts: Map<String, String>, val mustAccept: Boolean, val digest: String)

    internal fun openNote(client: GuestClient, kShare: ByteArray, sealed: String): Note? {
        val plaintext = try {
            client.suite.openField(SHARE_NOTE, kShare, unb64(sealed))
        } catch (e: Exception) {
            return null
        }
        val parsed = runCatching { Json.parseToJsonElement(plaintext.decodeToString()) }.getOrNull() as? JsonObject ?: return null
        val digest = b64url(client.suite.p.sha256(plaintext))
        if ("texts" !in parsed) return localeMap(parsed)?.let { Note(it, false, digest) }
        val texts = localeMap(parsed["texts"] as? JsonObject ?: return null) ?: return null
        val must = (parsed["mustAccept"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return null
        if (parsed.keys.any { it != "texts" && it != "mustAccept" }) return null
        return Note(texts, must, digest)
    }

    private fun localeMap(o: JsonObject): Map<String, String>? =
        o.mapValues { (_, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null }

    private fun hasText(map: Map<String, String>) = map.values.any { it.isNotBlank() }

    /** The base64 password token `Argon2id(password, pwSalt)` the guest presents in `X-Share-Password` (heavy: once per unlock). */
    fun passwordToken(client: GuestClient, password: String, pwSalt: String): String =
        b64(client.suite.sharePasswordToken(password, unb64(pwSalt)))

    /** The share's tenant's public terms, or null for any failure (the gate fails open, as the web's). */
    suspend fun terms(client: GuestClient, shareId: String): ShareTerms? = try {
        val r = SharingApi(client.origin, client.http).getShareTerms(shareId)
        if (r.success) r.body() else null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** The best per-locale text for [locale] (exact → base language → en → first present); "" when empty. */
    fun pickLocaleText(map: Map<String, String>, locale: String): String {
        val entries = map.entries.filter { it.value.isNotBlank() }
        if (entries.isEmpty()) return ""
        val req = locale.lowercase()
        val base = req.substringBefore('-')
        return (entries.firstOrNull { it.key.lowercase() == req } ?: entries.firstOrNull { it.key.lowercase() == base }
            ?: entries.firstOrNull { it.key.lowercase() == "en" } ?: entries.first()).value
    }

    /** A localized markdown as a locale map. */
    fun localeMapOf(de: String?, en: String?): Map<String, String> = buildMap {
        de?.let { put("de", it) }
        en?.let { put("en", it) }
    }
}
