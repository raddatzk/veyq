package me.raddatz.veyq.core.admin.operator

import me.raddatz.veyq.core.admin.instance.InstanceMailSettingsStore
import me.raddatz.veyq.core.blobstore.StoreSettingStore
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.net.URI

/** What kind of recipient the instance knows of by its own configuration. The wire enum's two values. */
enum class SuggestionKind { OBJECT_STORE, MAIL_RELAY }

/** A recipient row the console offers for the Server Owner to complete — never stored, never published by itself. */
data class RecipientSuggestion(val kind: SuggestionKind, val host: String)

/**
 * The recipients the instance itself knows of (cr-instance-privacy-notice decision 3): an external object store's host
 * (browsers fetch from it, so its provider sees their addresses) and the instance mail relay's host. The bundled store
 * runs on the operator's own host and is no recipient of its own; a space's mail relay is its owner's, named generically
 * by the notice (decision 4), so it is never suggested.
 */
@ApplicationScoped
class RecipientSuggestions(
    private val stores: StoreSettingStore,
    private val mail: InstanceMailSettingsStore,
) {
    fun list(): List<RecipientSuggestion> = listOfNotNull(
        storeHost()?.let { RecipientSuggestion(SuggestionKind.OBJECT_STORE, it) },
        mail.find()?.transport?.host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { RecipientSuggestion(SuggestionKind.MAIL_RELAY, it) },
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
