package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import jakarta.enterprise.context.ApplicationScoped
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant

/** The release check as the Server Owner reads it: whether this build checks at all, its setting, and the notices. */
data class ReleaseNoticesView(
    val releaseLine: Boolean,
    val enabled: Boolean,
    val source: String?,
    val lastSuccessAt: Instant?,
    val notices: List<StoredNotice>,
)

sealed interface ReleaseCheckSetOutcome {
    data class Stored(val view: ReleaseNoticesView) : ReleaseCheckSetOutcome

    /** The source is not an `http`/`https` URL with a host; nothing is stored. */
    data object InvalidSource : ReleaseCheckSetOutcome, HttpFailure by Failures.invalidRequest
}

/**
 * What `/api/instance/release/notices` and `/api/instance/release/check` do (ADR 0097, uc.admin.learn-of-new-releases).
 *
 * The read shows a notice while its release is still newer than the running one — a notice found once stays, and an
 * upgrade retires it at once rather than at the next check. An unstamped build has nothing to compare, so it shows
 * whatever is stored (it never writes any: [ReleaseCheck] makes no request without a release line).
 */
@ApplicationScoped
class ReleaseNoticesHandler(
    private val identity: ReleaseIdentity,
    private val store: ReleaseNoticeStore,
) {
    fun read(): ReleaseNoticesView {
        val running = ReleaseVersion.parse(identity.version)
        val setting = store.setting()
        val notices = store.notices()
            .filter { running == null || it.release > running }
            .sortedWith(compareByDescending<StoredNotice> { it.release }.thenBy { it.kind }.thenBy { it.daysAhead ?: 0 })
        return ReleaseNoticesView(running != null, setting.enabled, setting.source, setting.lastSuccessAt, notices)
    }

    fun set(enabled: Boolean, source: String?): ReleaseCheckSetOutcome {
        val normalized = source?.trim()?.ifEmpty { null }
        if (normalized != null && !isHttpUrl(normalized)) return ReleaseCheckSetOutcome.InvalidSource
        store.saveSetting(enabled, normalized)
        return ReleaseCheckSetOutcome.Stored(read())
    }

    /** An `http`/`https` URL with a host and nothing a base URL cannot carry (credentials, a query, a fragment). */
    private fun isHttpUrl(text: String): Boolean = try {
        val uri = URI(text)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null
    } catch (e: URISyntaxException) {
        false
    }
}
