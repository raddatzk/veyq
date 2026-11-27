package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.api.apis.TenantsApi
import me.raddatz.veyq.mobile.api.apis.ScopesApi
import me.raddatz.veyq.mobile.api.models.AcceptTermsRequest
import me.raddatz.veyq.mobile.api.models.LocalizedMarkdown
import me.raddatz.veyq.mobile.api.models.MyTenant
import me.raddatz.veyq.mobile.api.models.TenantTermsUpdate
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
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

    /**
     * The languages terms are written in, in the web's order (`SUPPORTED_LOCALES`), each by its own name whatever the
     * app's language (the web's `LOCALE_LABELS`): a language's name for itself is no catalogue word.
     */
    val LANGUAGES: List<Pair<String, String>> = listOf("en" to "English", "de" to "Deutsch")

    /** A language's own name; an unknown code is itself, as the web's `localeLabel`. */
    fun languageName(lang: String): String = LANGUAGES.firstOrNull { it.first == lang }?.second ?: lang

    /** One language of a draft: its code, its text, and what its row offers. */
    @Serializable
    data class LangText(val lang: String, val text: String) {
        // carried by the bridge as they are (its JSON encodes defaults), so a host decides none of them
        /** The language's own name ("English", "Deutsch"). */
        val name: String = languageName(lang)

        /** *Insert template* is offered: the starter template has this language. */
        val templated: Boolean = lang in TermsTemplate.byLanguage

        /** The template's question: an offer into an empty text, a replacement over a written one (the web's two asks). */
        val templateAsk: Msg = if (text.trim().isNotEmpty()) Msg.TERMS_INSERT_TEMPLATE_OVERWRITE_ASK else Msg.TERMS_INSERT_TEMPLATE_ASK
    }

    /**
     * The owner's editor's draft (the web's `LocaleMap` in `TenantTermsPanel` and `LocaleMarkdownEditor`): **a language's
     * presence means it exists** — an added and still empty one is an editing state the server does not keep (it drops a
     * blank language on save), which [blank] says. Each change is a new draft from [withText], [withLanguage],
     * [without] or [withTemplate]; a host keeps the one it was handed and draws it.
     */
    @Serializable
    data class TermsDraft(val revision: Int, val texts: List<LangText>) {
        /** The languages *Add language* offers: those of [LANGUAGES] not in the draft. */
        val addable: List<LangText> = LANGUAGES.filter { (code, _) -> texts.none { it.lang == code } }.map { LangText(it.first, "") }

        /** The languages present and still blank, by name, joined as the web joins them; null where none is. */
        val blankNames: String? = texts.filter { it.text.trim().isEmpty() }.joinToString(", ") { it.name }.ifEmpty { null }

        /** The warning under the editor while a language is blank ([Msg.TERMS_BLANK_LOCALE_WARNING] with [blankNames]). */
        val blankWarning: Msg? = blankNames?.let { Msg.TERMS_BLANK_LOCALE_WARNING }

        /** The revision's badge (`terms.revision`), shown above 0. */
        val showsRevision: Boolean = revision > 0

        fun withText(lang: String, text: String): TermsDraft = copy(texts = texts.map { if (it.lang == lang) LangText(lang, text) else it })

        /** [lang] added, empty, in the web's order; a language already present or unknown changes nothing. */
        fun withLanguage(lang: String): TermsDraft =
            if (texts.any { it.lang == lang } || LANGUAGES.none { it.first == lang }) this else copy(texts = ordered(texts + LangText(lang, "")))

        /** [lang] removed (asked first: [Msg.TERMS_REMOVE_LANG_ASK]). */
        fun without(lang: String): TermsDraft = copy(texts = texts.filter { it.lang != lang })

        /** [lang]'s text replaced by the starter template (asked first: [LangText.templateAsk]). */
        fun withTemplate(lang: String): TermsDraft = TermsTemplate.byLanguage[lang]?.let { withText(lang, it) } ?: this

        /** The preview's text in [language], the other language where this one is empty (the web's `previewBody`). */
        fun preview(language: String): String {
            fun of(l: String) = texts.firstOrNull { it.lang == l }?.text.orEmpty()
            return of(language).ifEmpty { of("en") }.ifEmpty { of("de") }
        }

        internal companion object {
            fun ordered(texts: List<LangText>): List<LangText> =
                LANGUAGES.mapNotNull { (code, _) -> texts.firstOrNull { it.lang == code } } + texts.filter { t -> LANGUAGES.none { it.first == t.lang } }

            fun of(revision: Int, terms: Map<String, String>): TermsDraft = TermsDraft(revision, ordered(terms.map { LangText(it.key, it.value) }))
        }
    }

    /**
     * Settings' Terms as the caller may use them: [owner] where the caller owns the tenant (the editor, over [draft]), else
     * the read-only page ([shown], as [page]); the operator section below either way.
     */
    @Serializable
    data class TermsEditing(val owner: Boolean, val shown: TermsShown, val draft: TermsDraft)

    /** How a save ended: [draft] the server's answer as the editor shows it from now on, null where it failed; [notice] said either way. */
    class Saved(val draft: TermsDraft?, val notice: Notice)

    /** The terms of [tenant] for Settings, and whether the caller owns the tenant (`listMyTenants`' role, as the web's panel reads it); null where they did not read. */
    suspend fun editing(tenant: TenantSession, language: String): TermsEditing? = try {
        val t = tenant.account.call { TenantsApi(tenant.base, tenant.account.http).getTenantTerms(tenant.tenant.tenantId) }
        val mine = tenant.account.call { ScopesApi(tenant.base, tenant.account.http).listMyTenants() }.firstOrNull { it.tenantId == tenant.tenant.tenantId }
        val read = of(t, tenant.origin)
        TermsEditing(mine?.role == MyTenant.Role.OWNER, read.shown(language), TermsDraft.of(read.revision, read.terms))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * Saves [draft] as the tenant's terms (`updateTenantTerms`, owner only): a language the draft does not carry is sent
     * absent, so a removed one is gone; the server drops a blank one and bumps the revision on a change. The answer is
     * what the editor re-draws from, the server's truth. `403` says only the owner edits them.
     */
    suspend fun save(tenant: TenantSession, draft: TermsDraft): Saved = try {
        fun text(l: String) = draft.texts.firstOrNull { it.lang == l }?.text
        val r = tenant.account.respond {
            TenantsApi(tenant.base, tenant.account.http).updateTenantTerms(tenant.tenant.tenantId, TenantTermsUpdate(LocalizedMarkdown(de = text("de"), en = text("en"))))
        }
        when {
            r.success -> {
                val t = r.body()
                Saved(TermsDraft.of(t.termsRevision, ShareResolve.localeMapOf(t.terms?.de, t.terms?.en)), Notice(Msg.TERMS_SAVED, t.termsRevision))
            }
            r.status == 403 -> Saved(null, Notice(Msg.TERMS_NOT_OWNER))
            else -> Saved(null, Notice(Msg.TERMS_SAVE_FAILED))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Saved(null, Notice(Msg.TERMS_SAVE_FAILED))
    }
}
