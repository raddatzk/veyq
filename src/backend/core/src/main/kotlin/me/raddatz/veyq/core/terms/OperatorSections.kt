package me.raddatz.veyq.core.terms

import me.raddatz.veyq.core.admin.operator.OperatingMode
import me.raddatz.veyq.core.admin.operator.OperatorDetailsHandler
import me.raddatz.veyq.core.admin.operator.OperatorDetailsStore
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecords
import me.raddatz.veyq.core.platform.capabilities.CapabilityRegistry
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped

/**
 * The operator section of the terms as an endpoint serves it (ADR 0035 decision 6): the fixed text the jar carries
 * ([OperatorSectionText], loaded once at startup — a build without it fails to start rather than serve terms without
 * it), rendered ([OperatorSectionRenderer]) in the variant the operating mode picks (ADR 0093 § 14: FULL unless the
 * Server Owner chose PRIVATE), with the values as they stand now — a changed value renders at once and reopens no gate,
 * since only [revision] and the variant are part of what a gate compares.
 */
@Startup
@ApplicationScoped
class OperatorSections(
    private val details: OperatorDetailsHandler,
    private val store: OperatorDetailsStore,
    private val records: GuestWriteRecords,
    private val capabilities: CapabilityRegistry,
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

    /** The variant the operating mode picks now. */
    fun variant(): SectionVariant =
        if (QuarkusTransaction.joiningExisting().call { store.find()?.mode } == OperatingMode.PRIVATE) SectionVariant.REDUCED
        else SectionVariant.FULL

    /**
     * The section for [tenantId]'s terms, or the instance's (null: the public page, `GET /api/operator/terms`). The
     * record's passages ride exactly while `core.public-shares.guest-write-record` is on there (ADR 0088: a tenant's
     * answer includes the switches above it; the instance level alone for null).
     */
    fun render(tenantId: String?): RenderedSection {
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
        val recordOn = QuarkusTransaction.joiningExisting().call { capabilities.isActive(GuestWriteRecords.SWITCH, tenantId) }
        return OperatorSectionRenderer.render(text, variant(), values, recordOn)
    }
}
