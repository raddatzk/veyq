package me.raddatz.veyq.core.moderation.statement

/** One text in each language the template carries. */
data class Texts(val en: String, val de: String) {
    fun of(lang: StatementLang): String = if (lang == StatementLang.EN) en else de
}

enum class StatementLang { EN, DE }

/** The form a statement takes by the operating mode (ADR 0093 § 14): `FULL` (organisation) or `REDUCED` (private). */
enum class StatementForm { FULL, REDUCED }

data class MeasureTexts(val measure: Texts, val reach: Texts)

/** One passage of the statement: left out of the reduced form when [full], and rendered only when its [condition] holds. */
data class Passage(val id: String, val full: Boolean, val condition: String?, val text: Texts)

/** The clear fields one statement is rendered from — never user data (ADR 0049). */
data class StatementFields(
    val measureKind: String,
    val roles: List<StatementRole>,
    val basis: StatementBasis,
    val ground: StatementGround,
    val date: String,
    val contact: String?,
    val hasFacts: Boolean,
)

/**
 * The statement of reasons' one template (ADR 0093 § 7, decision 1 of cr-statement-of-reasons-and-objection), pinned from
 * `contracts/moderation/statement-of-reasons.json` — web/ pins the same file (`features/moderation/statement/template.ts`)
 * and `StatementTemplateConformanceTest` holds these literals equal to it, so the mail and the placeholder cannot drift.
 * The server renders it for the mail ([renderMail]); the facts never go into a mail: their passage becomes "it is in
 * veyq" (`mail.factsInVeyq`). It names the host, never the moderator.
 */
object StatementTemplate {
    val MEASURES: Map<String, MeasureTexts> = linkedMapOf(
        "SHARE" to MeasureTexts(
            measure = Texts("a link you created was blocked", "ein von dir erstellter Link wurde gesperrt"),
            reach = Texts("Everyone who opens it is told the link does not exist; nothing was deleted, and the link cannot be reactivated or issued again while the block stands.", "Wer ihn öffnet, erfährt, dass es den Link nicht gibt; gelöscht wurde nichts, und der Link kann nicht wieder aktiviert oder neu ausgestellt werden, solange die Sperre besteht."),
        ),
        "CONTENT" to MeasureTexts(
            measure = Texts("content was blocked", "Inhalte wurden gesperrt"),
            reach = Texts("It is no longer shown to anyone, members included, and appears as \"Blocked by the operator\"; nothing was deleted. It can still be deleted, but not replaced or restored.", "Sie werden niemandem mehr angezeigt, auch Mitgliedern nicht, und erscheinen als „Von der Moderation gesperrt“; gelöscht wurde nichts. Sie können weiterhin gelöscht, aber nicht ersetzt oder wiederhergestellt werden."),
        ),
        "GUEST_IDENTITY" to MeasureTexts(
            measure = Texts("a guest who uploaded through your link was blocked, together with the link", "ein Gast, der über deinen Link hochgeladen hat, wurde gesperrt, zusammen mit dem Link"),
            reach = Texts("The guest can no longer add anything, and the link answers as if it did not exist; nothing was deleted. You can issue a new link.", "Der Gast kann nichts mehr hinzufügen, und der Link antwortet, als gäbe es ihn nicht; gelöscht wurde nichts. Du kannst einen neuen Link ausstellen."),
        ),
    )
    val ROLES: Map<String, Texts> = linkedMapOf(
        "SHARE_CREATOR" to Texts("the person who created the link", "Person, die den Link erstellt hat"),
        "UPLOADER" to Texts("the person who uploaded it", "Person, die es hochgeladen hat"),
        "SCOPE_OWNER" to Texts("an owner of the space it lies in", "Person, der der Bereich gehört, in dem es liegt"),
    )
    val BASIS: Map<String, Texts> = linkedMapOf(
        "REPORT" to Texts("a report by someone who could see it", "eine Meldung von jemandem, der es sehen konnte"),
        "ORDER" to Texts("an order of an authority", "eine Anordnung einer Behörde"),
    )
    val GROUNDS: Map<String, Texts> = linkedMapOf(
        "CHILD_SEXUAL_ABUSE" to Texts("it is illegal: child sexual abuse material", "es ist rechtswidrig: Darstellung sexuellen Missbrauchs von Kindern"),
        "TERRORIST_CONTENT" to Texts("it is illegal: terrorist content", "es ist rechtswidrig: terroristische Inhalte"),
        "THREAT_TO_LIFE" to Texts("it is illegal: a threat to life or safety", "es ist rechtswidrig: eine Gefahr für Leben oder Sicherheit"),
        "ILLEGAL_CONTENT" to Texts("it is illegal under the law that applies", "es ist nach geltendem Recht rechtswidrig"),
        "TERMS_BREACH" to Texts("it breaches the terms of use that apply here", "es verstößt gegen die hier geltenden Nutzungsbedingungen"),
    )
    val ROLES_JOINER = Texts(" and ", " und ")
    val PASSAGES: List<Passage> = listOf(
        Passage("measure", full = false, condition = null, text = Texts("On {date}, the people who run this veyq instance took a measure: {measure}. {reach}", "Am {date} wurde auf dieser veyq-Instanz eine Maßnahme ergriffen: {measure}. {reach}")),
        Passage("roles", full = false, condition = null, text = Texts("You are told as {roles}.", "Du erhältst diese Begründung als {roles}.")),
        Passage("facts", full = false, condition = "facts", text = Texts("What they found:", "Was sie festgestellt haben:")),
        Passage("basis", full = true, condition = null, text = Texts("The measure was taken after {basis}.", "Die Maßnahme wurde ergriffen nach {basis}.")),
        Passage("automation", full = true, condition = null, text = Texts("No automated means found or decided it: a person did.", "Kein automatisiertes Verfahren hat das erkannt oder entschieden, sondern ein Mensch.")),
        Passage("ground", full = false, condition = null, text = Texts("The ground: {ground}.", "Der Grund: {ground}.")),
        Passage("objection", full = false, condition = null, text = Texts("If you disagree, you can object once, in veyq, where you see this statement. Another moderator reviews your objection where there is one.", "Wenn du nicht einverstanden bist, kannst du einmal widersprechen, in veyq, dort, wo du diese Begründung siehst. Eine andere Person aus der Moderation prüft deinen Widerspruch, sofern es eine gibt.")),
        Passage("contact", full = false, condition = "contact", text = Texts("Questions go to {contact}.", "Fragen gehen an {contact}.")),
        Passage("courts", full = true, condition = null, text = Texts("You can also go to court; you do not need to object first.", "Du kannst dich auch an ein Gericht wenden; ein Widerspruch vorher ist dafür nicht nötig.")),
    )
    val MAIL_SUBJECT = Texts("veyq: a moderation decision concerns you", "veyq: eine Moderationsentscheidung betrifft dich")
    val MAIL_FACTS_IN_VEYQ = Texts("What they found is in veyq: sign in at {domain} to read it.", "Was sie festgestellt haben, steht in veyq: Melde dich unter {domain} an, um es zu lesen.")

    /**
     * The statement as the mail carries it, in [lang]: every passage of [form] whose condition holds, the facts passage
     * replaced by the line that they are in veyq at [domain] (the tenant's host).
     */
    fun renderMail(lang: StatementLang, form: StatementForm, fields: StatementFields, domain: String): String =
        PASSAGES.filter { p -> !(p.full && form == StatementForm.REDUCED) }
            .filter { p ->
                when (p.condition) {
                    "facts" -> fields.hasFacts
                    "contact" -> fields.contact != null
                    else -> true
                }
            }
            .joinToString(" ") { p ->
                if (p.id == "facts") MAIL_FACTS_IN_VEYQ.of(lang).replace("{domain}", domain) else fill(p.text.of(lang), lang, fields)
            }

    /** The mail's body: the English statement, a rule, the German one. */
    fun mailBody(form: StatementForm, fields: StatementFields, domain: String): String =
        renderMail(StatementLang.EN, form, fields, domain) + "\n\n—\n\n" + renderMail(StatementLang.DE, form, fields, domain)

    private fun fill(text: String, lang: StatementLang, f: StatementFields): String {
        val measure = MEASURES.getValue(f.measureKind)
        val roles = f.roles.map { ROLES.getValue(it.name).of(lang) }
        val joined = if (roles.size <= 1) roles.joinToString("") else roles.dropLast(1).joinToString(", ") + ROLES_JOINER.of(lang) + roles.last()
        return text
            .replace("{date}", f.date)
            .replace("{measure}", measure.measure.of(lang))
            .replace("{reach}", measure.reach.of(lang))
            .replace("{roles}", joined)
            .replace("{basis}", BASIS.getValue(f.basis.name).of(lang))
            .replace("{ground}", GROUNDS.getValue(f.ground.name).of(lang))
            .replace("{contact}", f.contact ?: "")
    }
}
