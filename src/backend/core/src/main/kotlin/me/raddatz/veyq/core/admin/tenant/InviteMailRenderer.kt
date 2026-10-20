package me.raddatz.veyq.core.admin.tenant

import jakarta.enterprise.context.ApplicationScoped

/**
 * The immutable, **closed** render context for an invite mail — EXACTLY the three non-secret values a mail may
 * carry (ADR 0049): the accept [link] (secret in the URL fragment), the tenant [domain], and the [expiry]. It has
 * no other field, so the renderer is handed nothing sealed and can reach nothing sealed. This closedness is the
 * mechanical half of the ADR 0049 boundary — enforced *by construction*, not by a fence around a general engine.
 */
data class MailContext(val link: String, val domain: String, val expiry: String)

/**
 * Renders an invite mail's subject + body from an owner-authored (or default) template via a minimal, logic-less
 * `{{variable}}` substitution over a **closed, non-secret variable set** — `{{link}}`, `{{domain}}`, `{{expiry}}`
 * only (ADR 0048 §6, ADR 0049). No sections, partials, lambdas or reflection: the only values that can enter a
 * mail are the three in [MailContext], so veyq's machinery can never inject a sealed field (scope/group/node
 * name, member list, real name, content). An owner's literal prose *between* the variables is a consented,
 * operator/provider-visible disclosure (ADR 0049 §2) — outside this mechanical guarantee by decision, not oversight.
 *
 * A template that references any `{{tag}}` outside the closed set is rejected at write time ([unknownTags]) and
 * never stored, so an invalid template can never reach [render] — which, as a defensive backstop, leaves an
 * out-of-set tag untouched rather than resolving it.
 */
@ApplicationScoped
class InviteMailRenderer {

    /** Which `{{tag}}`s in [template] fall **outside** the closed set — empty means valid. The write path rejects
     *  a non-empty result (`400 invalid_mail_template`) so an out-of-set template is never persisted. */
    fun unknownTags(template: String?): Set<String> =
        TAG.findAll(template ?: "").map { it.groupValues[1] }.filterNot { it in VARIABLES }.toSet()

    /** Substitute the closed set into [template]. Every substituted value comes ONLY from [ctx] (the three
     *  non-secret fields); an out-of-set tag — which write-time validation already forbids — is left verbatim,
     *  never resolved, so there is no path by which a sealed field could be interpolated. */
    fun render(template: String, ctx: MailContext): String =
        TAG.replace(template) { m ->
            when (m.groupValues[1]) {
                "link" -> ctx.link
                "domain" -> ctx.domain
                "expiry" -> ctx.expiry
                else -> m.value
            }
        }

    /** veyq's shipped default subject (content-free) — used when the owner set no custom subject template. */
    fun defaultSubject(): String = DEFAULT_SUBJECT

    /** veyq's shipped default body (content-free, bilingual EN+DE) — used when the owner set no custom body. */
    fun defaultBody(): String = DEFAULT_BODY

    companion object {
        /** The closed, non-secret variable set — the whole of what a mail may interpolate (ADR 0049). */
        val VARIABLES = setOf("link", "domain", "expiry")

        /** `{{ name }}` with optional inner whitespace; `name` is a bare identifier — no logic, no dotted paths. */
        private val TAG = Regex("""\{\{\s*([A-Za-z0-9_]+)\s*}}""")

        private const val DEFAULT_SUBJECT = "You're invited to {{domain}} · Einladung zu {{domain}}"

        private val DEFAULT_BODY = """
            You've been invited to {{domain}}.
            Open this link to accept (expires {{expiry}}):
            {{link}}

            —

            Du wurdest zu {{domain}} eingeladen.
            Öffne diesen Link zum Annehmen (gültig bis {{expiry}}):
            {{link}}
        """.trimIndent()
    }
}
