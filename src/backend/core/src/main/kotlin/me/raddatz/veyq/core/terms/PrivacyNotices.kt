package me.raddatz.veyq.core.terms

import me.raddatz.veyq.core.admin.operator.OperatorDetailsHandler
import me.raddatz.veyq.core.admin.operator.RecipientSuggestions
import me.raddatz.veyq.core.backup.BackupRetention
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.generated.model.PrivacyNotice
import me.raddatz.veyq.core.generated.model.PrivacyNoticePassage
import me.raddatz.veyq.core.generated.model.PrivacyNoticeText as WireNoticeText
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecords
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.CapabilityRegistry
import me.raddatz.veyq.core.platform.capabilities.CapabilityTexts
import me.raddatz.veyq.core.platform.capabilities.ExternalServiceAddresses
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped

/**
 * The instance privacy notice as `GET /api/operator/privacy-notice` serves it (ADR 0035 decision 8,
 * uc.admin.privacy-notice): the fixed text the jar carries ([PrivacyNoticeText], loaded once at startup — a build
 * without it fails to start rather than serve none), rendered ([PrivacyNoticeRenderer]) in its one form (ADR 0093
 * § 14), with the values as they stand now and the switches of the space a host serves. "It states only what the running instance does": every passage bound to a switch
 * reads [CapabilityRegistry.isActive] for that space (ADR 0088: the instance level along the path), and each outside
 * service is named by the address the instance is set to call, so a mirror replaces the default's name.
 */
@Startup
@ApplicationScoped
class PrivacyNotices(
    private val details: OperatorDetailsHandler,
    private val records: GuestWriteRecords,
    private val capabilities: CapabilityRegistry,
    private val services: ExternalServiceAddresses,
    private val backups: BackupRetention,
    private val suggestions: RecipientSuggestions,
    config: VeyqConfig,
) {
    private val text: PrivacyNoticeText = PrivacyNoticeText.load()

    /** The account erasure's window: the scope trash's retention, on purpose one value (ADR 0101 decision 3). */
    private val erasureWindowDays: Int = config.scope().delete().retention().toDays().toInt()

    /** The notice for the space [tenantId] — a host's — or the instance level (null). */
    fun render(tenantId: String?): RenderedNotice = QuarkusTransaction.joiningExisting().call {
        val v = details.publicValues()
        fun active(id: String) = capabilities.isActive(id, tenantId)
        val configured = services.configured()
        fun label(id: String): String? =
            configured[id]?.takeIf { active(id) }?.let { PrivacyNoticeRenderer.serviceLabel(it.url) }
        val values = NoticeValues(
            providerName = v.providerName,
            providerAddress = v.providerAddress,
            providerEmail = v.providerEmail,
            providerRepresentative = v.providerRepresentative,
            privacyContact = v.statementEmail ?: v.contactEmail,
            dataProtectionOfficer = v.dataProtectionOfficer,
            supervisoryAuthority = v.supervisoryAuthority,
            recipients = v.recipients.map { NoticeRecipient(it.name, it.kind, it.description, it.country) },
            retentionDays = records.retentionDays(),
            backupRetentionDays = backups.retention().days,
            erasureWindowDays = erasureWindowDays,
            breachedPasswordService = label(CapabilityIds.BREACHED_PASSWORD),
            exchangeRateService = label(CapabilityIds.EXPENSES_EXCHANGE_RATES),
            browserServices = CapabilityIds.EXTERNAL_SERVICES
                .filter { it !in OWN_PASSAGE }
                .sorted()
                .mapNotNull { id ->
                    label(id)?.let { l -> CapabilityTexts.BY_ID.getValue(id).title.let { BrowserService(SectionTexts(en = it.en, de = it.de), l) } }
                },
            storeHost = suggestions.storeHost(),
        )
        PrivacyNoticeRenderer.render(text, values) { active(it) }
    }
}

/**
 * The outside services the notice gives a passage of their own (`breached-password-check`, `exchange-rates`), because
 * they run without anyone turning them on and the notice says what each request carries; every other one is a line of
 * `browser-services`. One set, so a service is never named twice.
 */
internal val OWN_PASSAGE = setOf(CapabilityIds.BREACHED_PASSWORD, CapabilityIds.EXPENSES_EXCHANGE_RATES)

/** The wire form of the notice (`PrivacyNotice` in `operator.openapi.yaml`). */
fun RenderedNotice.toWire(): PrivacyNotice = PrivacyNotice(
    revision = revision,
    draftBanner = draftBanner?.toNoticeText(),
    passages = passages.map { PrivacyNoticePassage(id = it.id, separate = it.separate, text = it.text.toNoticeText()) },
)

private fun SectionTexts.toNoticeText() = WireNoticeText(de = de, en = en)

