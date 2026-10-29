package me.raddatz.veyq.core.admin.operator

import me.raddatz.veyq.core.admin.instance.InstanceMailSettingsStore
import me.raddatz.veyq.core.blobstore.StoreSettingStore
import me.raddatz.veyq.core.terms.RecipientKind
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.net.URI

/**
 * A recipient row the console offers for the Server Owner to complete — never stored, never published by itself. [kind]
 * is the row's recipient kind: [RecipientKind.OBJECT_STORAGE] or [RecipientKind.MAIL_RELAY], the wire enum's two values.
 */
data class RecipientSuggestion(val kind: RecipientKind, val host: String)

/**
 * The recipients the instance itself knows of (ADR 0035 decision 8, uc.admin.privacy-notice): an external object store's host
 * (browsers fetch from it, so its provider sees their addresses) and the instance mail relay's host. The bundled store
 * runs on the operator's own host and is no recipient of its own; a space's mail relay is its owner's, named generically
 * by the notice, so it is never suggested.
 */
@ApplicationScoped
class RecipientSuggestions(
    private val stores: StoreSettingStore,
    private val mail: InstanceMailSettingsStore,
) {
    fun list(): List<RecipientSuggestion> = listOfNotNull(
        storeHost()?.let { RecipientSuggestion(RecipientKind.OBJECT_STORAGE, it) },
        mail.find()?.transport?.host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { RecipientSuggestion(RecipientKind.MAIL_RELAY, it) },
    )

    /** The external store's host as browsers reach it — its public endpoint, else its endpoint; null for the bundled one. */
    fun storeHost(): String? {
        val row = QuarkusTransaction.joiningExisting().call { stores.find() } ?: return null
        if (row.bundled) return null
        return (row.publicEndpoint ?: row.endpoint)?.let(::hostOf)
    }

    private fun hostOf(url: String): String? =
        runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()?.takeIf { it.isNotBlank() }
}
