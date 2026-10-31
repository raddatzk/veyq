package me.raddatz.veyq.core.moderation.statement

/** One text in each language the template carries. */
data class Texts(val en: String, val de: String) {
    fun of(lang: StatementLang): String = if (lang == StatementLang.EN) en else de
}

enum class StatementLang { EN, DE }

/**
 * Where the affected object (ADR 0093 § 7): in veyq, once, for a moderator's measure ([IN_APP]); to the instance contact
 * for a Server Owner's own step, whose recipient cannot sign in or enter the tenant ([BY_CONTACT]).
 */
enum class ObjectionChannel { IN_APP, BY_CONTACT }

/**
 * A measure's words and its reach; [byRole] words it for the reader's relation to it (inbox #821) — the first entry, in its
 * order, whose role the reader holds — and a reader who holds none reads [measure] and [reach], the neutral words.
 */
data class MeasureTexts(val measure: Texts, val reach: Texts, val byRole: Map<String, MeasureTexts> = emptyMap()) {
    /** The words a reader told as [roles] reads. */
    fun forRoles(roles: List<StatementRole>): MeasureTexts =
        byRole.entries.firstOrNull { (role, _) -> roles.any { it.name == role } }?.value ?: this
}

/** One passage of the statement, rendered only when its [condition] holds (there is one form, ADR 0093 § 14). */
data class Passage(val id: String, val condition: String?, val text: Texts)

/**
 * The clear fields one statement is rendered from (ADR 0049): the template's own texts for the clear enums, the
 * [provision] the moderator or the Server Owner named, the instance [contact] (null renders as the template's gap).
 * [mailFacts] is set only for a Server
 * Owner's step — their own words, which its mail carries (ADR 0093 § 7); a moderator's
 * measure's facts never reach a mail.
 */
data class StatementFields(
    val measureKind: String,
    val roles: List<StatementRole>,
    val basis: StatementBasis,
    val ground: StatementGround,
    val date: String,
    val contact: String?,
    val hasFacts: Boolean,
    val provision: String? = null,
    val channel: ObjectionChannel = ObjectionChannel.IN_APP,
    val mailFacts: String? = null,
)

/**
 * The statement of reasons' one template (ADR 0093 § 7), pinned from
 * `contracts/moderation/statement-of-reasons.json` — src/web/ pins the same file (`features/moderation/statement/template.ts`)
 * and `StatementTemplateConformanceTest` holds these literals equal to it, so the mail and the placeholder cannot drift.
 * The server renders it for the mail ([renderMail]); a moderator's measure's facts never go into a mail: their passage
 * becomes "it is in veyq" (`mail.factsInVeyq`). A Server Owner's step's mail carries its facts after that passage. It
 * names the host, never the moderator. [REPORTER_DECISION] is a reporter's redress (DSA Art. 16(5)), rendered after the
 * decision's outcome ([renderRedress]).
 */
object StatementTemplate {
    val MEASURES: Map<String, MeasureTexts> = linkedMapOf(
        "SHARE" to MeasureTexts(
            measure = Texts("a link was blocked", "ein Link wurde gesperrt"),
            reach = Texts("Everyone who opens it is told the link does not exist; nothing was deleted, and the link cannot be reactivated or issued again while the block stands.", "Wer ihn öffnet, erfährt, dass es den Link nicht gibt; gelöscht wurde nichts, und der Link kann nicht wieder aktiviert oder neu ausgestellt werden, solange die Sperre besteht."),
            byRole = linkedMapOf(
                "SHARE_CREATOR" to MeasureTexts(
                    measure = Texts("a link you created was blocked", "ein von dir erstellter Link wurde gesperrt"),
                    reach = Texts("Everyone who opens it is told the link does not exist; nothing was deleted, and the link cannot be reactivated or issued again while the block stands.", "Wer ihn öffnet, erfährt, dass es den Link nicht gibt; gelöscht wurde nichts, und der Link kann nicht wieder aktiviert oder neu ausgestellt werden, solange die Sperre besteht."),
                ),
                "UPLOADER" to MeasureTexts(
                    measure = Texts("a link to something you uploaded was blocked", "ein Link zu etwas, das du hochgeladen hast, wurde gesperrt"),
                    reach = Texts("Everyone who opens it is told the link does not exist; nothing was deleted.", "Wer ihn öffnet, erfährt, dass es den Link nicht gibt; gelöscht wurde nichts."),
                ),
            ),
        ),
        "CONTENT" to MeasureTexts(
            measure = Texts("content was blocked", "Inhalte wurden gesperrt"),
            reach = Texts("It is no longer shown to anyone, members included, and appears as \"Blocked by the operator\"; nothing was deleted. It can still be deleted, but not replaced or restored.", "Sie werden niemandem mehr angezeigt, auch Mitgliedern nicht, und erscheinen als „Von der Moderation gesperrt“; gelöscht wurde nichts. Sie können weiterhin gelöscht, aber nicht ersetzt oder wiederhergestellt werden."),
        ),
        "UPLOADER_CONTENT" to MeasureTexts(
            measure = Texts("everything one person had uploaded to a space was blocked", "alles, was eine Person in einen Bereich hochgeladen hatte, wurde gesperrt"),
            reach = Texts("None of it is shown to anyone any more, members included, and each item appears as \"Blocked by the operator\"; nothing was deleted. What the person adds later is not affected. It can still be deleted, but not replaced or restored.", "Nichts davon wird jemandem mehr angezeigt, auch Mitgliedern nicht, und jedes Element erscheint als „Von der Moderation gesperrt“; gelöscht wurde nichts. Was die Person später hinzufügt, ist nicht betroffen. Es kann weiterhin gelöscht, aber nicht ersetzt oder wiederhergestellt werden."),
        ),
        "GUEST_IDENTITY" to MeasureTexts(
            measure = Texts("a guest who uploaded through your link was blocked, together with the link", "ein Gast, der über deinen Link hochgeladen hat, wurde gesperrt, zusammen mit dem Link"),
            reach = Texts("The guest can no longer add anything, and the link answers as if it did not exist; nothing was deleted. You can issue a new link.", "Der Gast kann nichts mehr hinzufügen, und der Link antwortet, als gäbe es ihn nicht; gelöscht wurde nichts. Du kannst einen neuen Link ausstellen."),
        ),
        "MEMBER_SIGN_IN" to MeasureTexts(
            measure = Texts("your account was blocked from signing in", "dein Konto wurde für die Anmeldung gesperrt"),
            reach = Texts("You cannot sign in to this veyq instance, and every session of your account was ended; nothing was deleted.", "Du kannst dich auf dieser veyq-Instanz nicht anmelden, und jede Sitzung deines Kontos wurde beendet; gelöscht wurde nichts."),
        ),
        "TENANT_OFFLINE" to MeasureTexts(
            measure = Texts("a space you own, {domain}, was taken offline", "ein Bereich, der dir gehört, {domain}, wurde offline genommen"),
            reach = Texts("Nobody can open it or anything in it, its members included, and its links answer as if they did not exist; nothing was deleted.", "Niemand kann ihn oder etwas darin öffnen, auch seine Mitglieder nicht, und seine Links antworten, als gäbe es sie nicht; gelöscht wurde nichts."),
        ),
        "TENANT_DELETED" to MeasureTexts(
            measure = Texts("a space you own, {domain}, was deleted", "ein Bereich, der dir gehört, {domain}, wurde gelöscht"),
            reach = Texts("Nobody can open it or anything in it from now on, its members included, and its links answer as if they did not exist. Everything in it is erased after a waiting period unless the deletion is stopped before; the accounts of its members stay.", "Ab jetzt kann niemand ihn oder etwas darin öffnen, auch seine Mitglieder nicht, und seine Links antworten, als gäbe es sie nicht. Alles darin wird nach einer Wartezeit gelöscht, wenn die Löschung nicht vorher gestoppt wird; die Konten seiner Mitglieder bleiben."),
        ),
    )
    val ROLES: Map<String, Texts> = linkedMapOf(
        "SHARE_CREATOR" to Texts("the person who created the link", "Person, die den Link erstellt hat"),
        "UPLOADER" to Texts("the person who uploaded it", "Person, die es hochgeladen hat"),
        "SCOPE_OWNER" to Texts("an owner of the space it lies in", "Person, der der Bereich gehört, in dem es liegt"),
        "ACCOUNT_HOLDER" to Texts("the holder of the account", "Person, der das Konto gehört"),
        "TENANT_OWNER" to Texts("an owner of the space", "Person, der der Bereich gehört"),
    )
    val BASIS: Map<String, Texts> = linkedMapOf(
        "REPORT" to Texts("a report by someone who could see it", "einer Meldung von jemandem, der es sehen konnte"),
        "ORDER" to Texts("an order of an authority", "einer Anordnung einer Behörde"),
        "OWN_FINDING" to Texts("their own finding", "eigener Feststellung"),
    )
    val GROUNDS: Map<String, Texts> = linkedMapOf(
        "CHILD_SEXUAL_ABUSE" to Texts("it is illegal: child sexual abuse material", "es ist rechtswidrig: Darstellung sexuellen Missbrauchs von Kindern"),
        "TERRORIST_CONTENT" to Texts("it is illegal: terrorist content", "es ist rechtswidrig: terroristische Inhalte"),
        "THREAT_TO_LIFE" to Texts("it is illegal: a threat to life or safety", "es ist rechtswidrig: eine Gefahr für Leben oder Sicherheit"),
        "ILLEGAL_CONTENT" to Texts("it is illegal under the law that applies", "es ist nach geltendem Recht rechtswidrig"),
        "TERMS_BREACH" to Texts("it breaches the terms of use that apply here", "es verstößt gegen die hier geltenden Nutzungsbedingungen"),
    )
    val ROLES_JOINER = Texts(" and ", " und ")
    val GAP = Texts("not stated by this operator", "von der Betreiberseite nicht angegeben")
    val PASSAGES: List<Passage> = listOf(
        Passage("measure", condition = null, text = Texts("On {date}, the people who run this veyq instance took a measure: {measure}. {reach}", "Am {date} wurde auf dieser veyq-Instanz eine Maßnahme ergriffen: {measure}. {reach}")),
        Passage("roles", condition = null, text = Texts("You are told as {roles}.", "Du erhältst diese Begründung als {roles}.")),
        Passage("facts", condition = "facts", text = Texts("What they found:", "Was sie festgestellt haben:")),
        Passage("basis", condition = null, text = Texts("The measure was taken after {basis}.", "Die Maßnahme wurde ergriffen nach {basis}.")),
        Passage("automation", condition = null, text = Texts("No automated means found or decided it: a person did.", "Kein automatisiertes Verfahren hat das erkannt oder entschieden, sondern ein Mensch.")),
        Passage("ground", condition = null, text = Texts("The ground: {ground}.", "Der Grund: {ground}.")),
        Passage("provision", condition = "provision", text = Texts("It relies on {provision}.", "Sie stützt sich auf {provision}.")),
        Passage("scope", condition = null, text = Texts("It applies wherever this veyq instance is reached, and lasts until it is lifted.", "Sie gilt überall, wo diese veyq-Instanz erreichbar ist, und dauert an, bis sie aufgehoben wird.")),
        Passage("objection", condition = "inApp", text = Texts("If you disagree, you can object once, in veyq, where you see this statement. Another moderator reviews your objection where there is one.", "Wenn du nicht einverstanden bist, kannst du einmal widersprechen, in veyq, dort, wo du diese Begründung siehst. Eine andere Person aus der Moderation prüft deinen Widerspruch, sofern es eine gibt.")),
        Passage("objectionByContact", condition = "byContact", text = Texts("If you disagree, write to {contact}: the people who run this instance look at it again.", "Wenn du nicht einverstanden bist, schreib an {contact}: Wer diese Instanz betreibt, prüft es erneut.")),
        Passage("contact", condition = null, text = Texts("Questions go to {contact}.", "Fragen gehen an {contact}.")),
        Passage("courts", condition = null, text = Texts("You can also go to court; you do not need to object first.", "Du kannst dich auch an ein Gericht wenden; ein Widerspruch vorher ist dafür nicht nötig.")),
    )
    val REPORTER_DECISION: List<Passage> = listOf(
        Passage("again", condition = null, text = Texts("If you have more information, you can report it again.", "Wenn du weitere Informationen hast, kannst du es erneut melden.")),
        Passage("contact", condition = null, text = Texts("Questions go to {contact}.", "Fragen gehen an {contact}.")),
        Passage("courts", condition = null, text = Texts("You can also go to court.", "Du kannst dich auch an ein Gericht wenden.")),
        Passage("coordinator", condition = null, text = Texts("Where the Digital Services Act (DSA) applies to this instance, you can also complain to the Digital Services Coordinator of the Member State where you live or are established.", "Soweit das Gesetz über digitale Dienste (DSA) für diese Instanz gilt, kannst du dich auch beim Koordinator für digitale Dienste des Mitgliedstaats beschweren, in dem du lebst oder niedergelassen bist.")),
    )
    val MAIL_SUBJECT = Texts("veyq: a moderation decision concerns you", "veyq: eine Moderationsentscheidung betrifft dich")
    val MAIL_FACTS_IN_VEYQ = Texts("What they found is in veyq: sign in at {domain} to read it.", "Was sie festgestellt haben, steht in veyq: Melde dich unter {domain} an, um es zu lesen.")

    /** Whether [p] is rendered where [has] answers its condition. */
    private fun shown(p: Passage, has: (String) -> Boolean): Boolean = p.condition == null || has(p.condition)

    /**
     * The statement as the mail carries it, in [lang]: every passage whose condition holds, the facts passage
     * replaced by the line that they are in veyq at [domain] (the tenant's host) — or, for a Server Owner's step, followed
     * by the facts themselves ([StatementFields.mailFacts]).
     */
    fun renderMail(lang: StatementLang, fields: StatementFields, domain: String): String =
        PASSAGES.filter { p ->
            shown(p) { c ->
                when (c) {
                    "facts" -> fields.hasFacts
                    "provision" -> !fields.provision.isNullOrBlank()
                    "inApp" -> fields.channel == ObjectionChannel.IN_APP
                    "byContact" -> fields.channel == ObjectionChannel.BY_CONTACT
                    else -> error("a condition the template does not know: $c")
                }
            }
        }.joinToString(" ") { p ->
            when {
                p.id != "facts" -> fill(p.text.of(lang), lang, fields, domain)
                fields.mailFacts != null -> p.text.of(lang) + " " + fields.mailFacts
                else -> MAIL_FACTS_IN_VEYQ.of(lang).replace("{domain}", domain)
            }
        }

    /** The mail's body: the English statement, a rule, the German one. */
    fun mailBody(fields: StatementFields, domain: String): String =
        renderMail(StatementLang.EN, fields, domain) + "\n\n—\n\n" + renderMail(StatementLang.DE, fields, domain)

    /** A reporter's redress in [lang] (DSA Art. 16(5)); an instance without a contact shows the gap in its place. */
    fun renderRedress(lang: StatementLang, contact: String?): String =
        REPORTER_DECISION.joinToString(" ") { it.text.of(lang).replace("{contact}", contact ?: GAP.of(lang)) }

    private fun fill(text: String, lang: StatementLang, f: StatementFields, domain: String): String {
        // The words of the reader's relation to the measure: each recipient row records its roles (inbox #821).
        val measure = MEASURES.getValue(f.measureKind).forRoles(f.roles)
        val roles = f.roles.map { ROLES.getValue(it.name).of(lang) }
        val joined = if (roles.size <= 1) roles.joinToString("") else roles.dropLast(1).joinToString(", ") + ROLES_JOINER.of(lang) + roles.last()
        return text
            .replace("{date}", f.date)
            .replace("{measure}", measure.measure.of(lang))
            .replace("{reach}", measure.reach.of(lang))
            .replace("{roles}", joined)
            .replace("{basis}", BASIS.getValue(f.basis.name).of(lang))
            .replace("{ground}", GROUNDS.getValue(f.ground.name).of(lang))
            .replace("{provision}", f.provision?.trim().orEmpty())
            .replace("{contact}", f.contact ?: GAP.of(lang))
            .replace("{domain}", domain)
    }
}
