package me.raddatz.veyq.core.terms

import me.raddatz.veyq.core.admin.operator.OperatorDetailsHandler
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecords
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped

/**
 * The operator section of the terms as an endpoint serves it (ADR 0035 decision 6): the fixed text the jar carries
 * ([OperatorSectionText], loaded once at startup — a build without it fails to start rather than serve terms without
 * it), rendered ([OperatorSectionRenderer]) in its one form (ADR 0093 § 14), with the values as they stand now — a
 * changed value renders at once and reopens no gate, since only [revision] is part of what a gate compares.
 */
@Startup
@ApplicationScoped
class OperatorSections(
    private val details: OperatorDetailsHandler,
    private val records: GuestWriteRecords,
) {
    private val shipped: OperatorSectionText = OperatorSectionText.load()

    @Volatile
    private var text: OperatorSectionText = shipped

    /**
     * Serve [replacement] instead of the shipped text, or the shipped one again for null — what a release that changes
     * the section does, for the tests that pin "a revision bump reopens the gate" (`TenantTermsSliceTest`). Production
     * never calls it: the text moves only with the jar.
     */
    internal fun serve(replacement: OperatorSectionText?) {
        text = replacement ?: shipped
    }

    /** The shipped text, for a test that derives a replacement from it. */
    internal fun shippedText(): OperatorSectionText = shipped

    /** The section's revision: it moves with its text, that is with a release. */
    val revision: Int get() = text.revision

    /**
     * The section as every space's terms and the public page (`GET /api/operator/terms`) carry it: the same on every
     * host, the record's pointer included (ADR 0093 § 10: the record is kept wherever a guest may write).
     */
    fun render(): RenderedSection {
        val v = details.publicValues()
        val values = SectionValues(
            providerName = v.providerName,
            providerAddress = v.providerAddress,
            providerEmail = v.providerEmail,
            providerRepresentative = v.providerRepresentative,
            supervisoryAuthority = v.supervisoryAuthority,
            contactEmail = v.contactEmail,
            contactLanguages = v.contactLanguages,
            privacyContact = v.statementEmail ?: v.contactEmail,
            retentionDays = QuarkusTransaction.joiningExisting().call { records.retentionDays() },
        )
        return OperatorSectionRenderer.render(text, values)
    }
}
