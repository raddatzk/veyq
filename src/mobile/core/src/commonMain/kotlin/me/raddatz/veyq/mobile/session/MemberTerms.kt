package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.api.apis.TenantsApi
import me.raddatz.veyq.mobile.api.models.AcceptTermsRequest
import me.raddatz.veyq.mobile.sharing.ShareResolve

/**
 * A tenant's terms as a member reads and accepts them (uc.account.accept-terms; ADR 0035 decision 2 and § The apps' gate;
 * the web's `TermsAcceptView`, `TenantTermsPanel` and `activeSpace.termsStale`): the space's own text, the operator
 * section after it (decision 6) and the guest-write record's pointer to the privacy notice, the pair a member accepts —
 * the space's revision and the section's —, and whether the caller accepted the current pair.
 *
 * The gate's rule is [Read.stale]: terms set (revision above 0) and the current pair not accepted. Where the terms do not
 * read the gate **fails open**, as the web's does (`refreshTenantTerms`): a hiccup never locks a member out; the space's
 * terms are an operational gate, never a confidentiality control (ADR 0035 § The apps' gate, our reading).
 * The terms are public operational content: nothing here is sealed, nothing is a key.
 */
// Not `TenantTerms`: the generated model of that name made Swift see this object as `TenantTerms_`, and a nested `Shown`
// renamed the bridge's other `Shown` types in Rust and C# (their names are a type's last part unless it is taken twice).
object MemberTerms {
    /** The record's passage in the instance privacy notice (the web's `PRIVACY_ANCHOR.guestWriteRecord`). */
    const val RECORD_ANCHOR = "guest-write-record"

    /** The server's privacy notice at the guest-write record's passage, under the tenant's [origin] (the web's `privacyLink`). */
    fun privacyUrl(origin: String): String = "${origin.trimEnd('/')}/privacy#$RECORD_ANCHOR"

    /** The terms as read, every language the server carries; [shown] picks one. */
    class Read(
        val revision: Int,
        val operatorRevision: Int,
        val accepted: Boolean,
        val terms: Map<String, String>,
        val section: Map<String, String>,
        val record: Map<String, String>,
        val origin: String,
    ) {
        /** The member gate's condition (the web's `termsStale`): terms set and the current pair not accepted. */
        val stale: Boolean get() = revision > 0 && !accepted

        /** What a screen shows in [language], each text in the other language where this one is empty. */
        fun shown(language: String): TermsShown = TermsShown(
            revision = revision,
            operatorRevision = operatorRevision,
            hasTerms = revision > 0,
            terms = ShareResolve.pickLocaleText(terms, language),
            operatorSection = ShareResolve.pickLocaleText(section, language),
            record = ShareResolve.pickLocaleText(record, language),
            privacyUrl = privacyUrl(origin),
        )
    }

    /**
     * The terms as the gate and Settings' page draw them, in the viewer's language: the space's text ([terms], markdown;
     * empty where the space has none — [hasTerms] false), the operator section ([operatorSection]) and the record's pointer
     * ([record]) with its link ([privacyUrl]). [revision] and [operatorRevision] are the pair an accept names.
     */
    @Serializable
    data class TermsShown(
        val revision: Int,
        val operatorRevision: Int,
        val hasTerms: Boolean,
        val terms: String,
        val operatorSection: String,
        val record: String,
        val privacyUrl: String,
    )

    /** How an accept ended. */
    enum class Answer {
        /** The pair is recorded: the gate opens. */
        ACCEPTED,

        /** `409 terms_revision_changed`: the pair moved while it was shown; read anew and shown again. */
        CHANGED,

        /** Anything else: not reached, refused. The gate stays, saying so. */
        FAILED,
    }

    /** The generated model as [Read], under the tenant's [origin]. */
    fun of(t: me.raddatz.veyq.mobile.api.models.TenantTerms, origin: String): Read = Read(
        revision = t.termsRevision,
        operatorRevision = t.operatorSection.revision,
        accepted = t.accepted,
        terms = ShareResolve.localeMapOf(t.terms?.de, t.terms?.en),
        section = ShareResolve.localeMapOf(t.operatorSection.text.de, t.operatorSection.text.en),
        record = ShareResolve.localeMapOf(t.operatorSection.record.de, t.operatorSection.record.en),
        origin = origin,
    )

    /** The error code a moved pair is refused with. */
    internal const val REVISION_CHANGED = "terms_revision_changed"

    /** The tenant's terms read over [tenant]'s session, or null where they did not read (the gate then fails open). */
    suspend fun read(tenant: TenantSession): Read? = try {
        of(tenant.account.call { TenantsApi(tenant.base, tenant.account.http).getTenantTerms(tenant.tenant.tenantId) }, tenant.origin)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** The member's acceptance of the pair [revision], [operatorRevision] it was shown, recorded by the server. */
    suspend fun accept(tenant: TenantSession, revision: Int, operatorRevision: Int): Answer = try {
        val r = tenant.account.respond {
            TenantsApi(tenant.base, tenant.account.http).acceptTenantTerms(tenant.tenant.tenantId, AcceptTermsRequest(revision, operatorRevision))
        }
        when {
            r.success -> Answer.ACCEPTED
            r.status == 409 && tenant.account.errorCode(r) == REVISION_CHANGED -> Answer.CHANGED
            else -> Answer.FAILED
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Answer.FAILED
    }

    /** Settings' page (the web's `TenantTermsPanel`, read-only): the terms of [tenant] in [language], or null where they did not read. */
    suspend fun page(tenant: TenantSession, language: String): TermsShown? = read(tenant)?.shown(language)
}
