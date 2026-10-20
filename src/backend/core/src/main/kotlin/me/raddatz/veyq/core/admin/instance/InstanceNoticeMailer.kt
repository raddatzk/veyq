package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.generated.model.DeliveryResult
import me.raddatz.veyq.core.mail.CalendarAttachment
import me.raddatz.veyq.core.mail.SmtpDelivery
import me.raddatz.veyq.core.mail.SmtpTransport
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Instance mail (ADR 0097 decisions 3–5, uc.admin.learn-of-new-releases): the test mail that carries the confirmation
 * code, and one mail per release notice once the address is confirmed.
 *
 * **Only the instance's own settings, only to the Server Owner.** It reads [InstanceMailSettingsStore] and nothing of a
 * tenant's, and its recipient is always the address of the account that saved the settings, as that account has it
 * now — never an address a request or a notice names. Nothing a tenant configures can make it send, and nothing it
 * holds is a tenant mailer's default or fallback (ADR 0048 §4). The mail carries no user data (ADR 0049): what was
 * found, the date, the Run area's upgrade page, and for the test mail the code.
 *
 * **Each notice at most once.** A notice is claimed for mail (its `mailed_at` set) before the send and released only
 * when the send failed, so a retry at the next check is possible and a second mail is not (decision 4: "No notice is
 * mailed twice"). A crash between the claim and the send loses that one mail; the console still shows the notice.
 */
@ApplicationScoped
class InstanceNoticeMailer(
    private val settings: InstanceMailSettingsStore,
    private val notices: ReleaseNoticeStore,
    private val wire: SmtpDelivery,
) {
    private val log = Logger.getLogger(InstanceNoticeMailer::class.java)

    /** Whether [transport] can put mail on the wire — the shared rule (an auth-less relay, or an openable password). */
    fun sendable(transport: InstanceMailTransport): Boolean = wire.sendable(transport.smtp())

    /** The test mail of a save (decision 5): the code, how long it holds, and where to type it. */
    fun sendCode(transport: InstanceMailTransport, to: String, code: String, expiresAt: Instant): DeliveryResult {
        val from = transport.from ?: return DeliveryResult.FAILED
        if (!sendable(transport)) return DeliveryResult.FAILED
        return wire.deliver(transport.smtp(), from, to, InstanceMailCopy.codeSubject(), InstanceMailCopy.codeBody(code, expiresAt), "the instance mail test")
    }

    /**
     * The code that confirms a moderator's address (ADR 0097 decision 5, ADR 0099 § 7), to [to] — the moderator's own
     * account address, which their caller read from their account row, never from a request. Sent through the instance's
     * settings whenever they can send: the Server Owner's own confirmation is about the Server Owner's address, not about
     * the relay, and a moderator's code is what confirms theirs.
     */
    fun sendModeratorCode(to: String, code: String, expiresAt: Instant): DeliveryResult {
        val transport = settings.find()?.transport?.takeIf { it.host != null } ?: return DeliveryResult.FAILED
        val from = transport.from ?: return DeliveryResult.FAILED
        if (!sendable(transport)) return DeliveryResult.FAILED
        return wire.deliver(
            transport.smtp(), from, to, InstanceMailCopy.moderatorCodeSubject(),
            InstanceMailCopy.moderatorCodeBody(code, expiresAt), "a moderator's address code",
        )
    }

    /**
     * Whether instance mail may carry anything beyond the Server Owner's own test mail: configured, its address
     * confirmed (ADR 0097 decision 5), and able to send. The instance contact's notification address gets its code only
     * then (owner 2026-10-17), and admin-web says so until it holds (`instanceMailReady`).
     */
    fun ready(now: Instant): Boolean {
        val row = settings.find() ?: return false
        val owner = InstanceMailConfirmation.addressOf(row.ownerUserId) ?: return false
        return InstanceMailConfirmation.state(row, owner, now) == InstanceMailConfirmationState.CONFIRMED && sendable(row.transport)
    }

    /**
     * The code that confirms the instance contact's notification address (ADR 0097 decision 5, ADR 0093 § 11) to [to] —
     * the address the Server Owner stored, read from the stored row by the caller, never from a request body. Only while
     * [ready]: an unconfirmed relay would be a code nobody can trust reached its address.
     */
    fun sendOperatorCode(to: String, code: String, expiresAt: Instant, now: Instant): DeliveryResult {
        if (!ready(now)) return DeliveryResult.FAILED
        val transport = settings.find()?.transport ?: return DeliveryResult.FAILED
        val from = transport.from ?: return DeliveryResult.FAILED
        return wire.deliver(
            transport.smtp(), from, to, InstanceMailCopy.operatorCodeSubject(),
            InstanceMailCopy.operatorCodeBody(code, expiresAt), "the instance contact's notification code",
        )
    }

    /**
     * A moderation notice (ADR 0097 decision 3, ADR 0099 § 7) to [to] — a confirmed moderator's own account address,
     * which the caller (`ModeratorNotices`) read from the roster and the account row, never from a request. Through the
     * instance's settings whenever they can send.
     */
    fun mailTo(to: String, subject: String, body: String, what: String): DeliveryResult {
        val transport = settings.find()?.transport?.takeIf { it.host != null } ?: return DeliveryResult.FAILED
        val from = transport.from ?: return DeliveryResult.FAILED
        if (!sendable(transport)) return DeliveryResult.FAILED
        return wire.deliver(transport.smtp(), from, to, subject, body, what)
    }

    /**
     * One instance notice of another kind than a release (ADR 0097 decision 6: rotation due, ADR 0099 § 4) to the
     * confirmed Server Owner's address — under the same conditions [mailPending] has. Null when it could not be tried
     * (no confirmed settings, or settings that cannot send); the caller keeps its claim open then.
     */
    fun mailServerOwner(subject: String, body: String, what: String, now: Instant): DeliveryResult? {
        val row = settings.find() ?: return null
        val owner = InstanceMailConfirmation.addressOf(row.ownerUserId) ?: return null
        if (InstanceMailConfirmation.state(row, owner, now) != InstanceMailConfirmationState.CONFIRMED) return null
        val from = row.transport.from ?: return null
        if (!sendable(row.transport)) return null
        return wire.deliver(row.transport.smtp(), from, owner, subject, body, what)
    }

    /**
     * Mail every notice that still applies to [running] and was not mailed yet — only with confirmed settings that can
     * send, and only to the confirmed Server Owner's address. Returns how many went out.
     */
    fun mailPending(running: ReleaseVersion, now: Instant): Int {
        val row = settings.find() ?: return 0
        val owner = InstanceMailConfirmation.addressOf(row.ownerUserId) ?: return 0
        if (InstanceMailConfirmation.state(row, owner, now) != InstanceMailConfirmationState.CONFIRMED) return 0
        val from = row.transport.from ?: return 0
        if (!sendable(row.transport)) {
            log.warn("Instance mail is confirmed but cannot send (the config-secret key cannot open its password); notices stay in the console.")
            return 0
        }
        var sent = 0
        notices.notices().filter { it.mailedAt == null && it.release > running }.sortedBy { it.foundAt }.forEach { notice ->
            if (!notices.claimForMail(notice.id, now)) return@forEach
            val result = wire.deliver(
                row.transport.smtp(), from, owner,
                InstanceMailCopy.noticeSubject(notice), InstanceMailCopy.noticeBody(notice),
                "an instance release notice",
                notice.date?.let { CalendarAttachment("veyq-${notice.release}.ics", NoticeCalendar.event(notice, it, now)) },
            )
            if (result == DeliveryResult.SENT) sent++ else notices.releaseClaim(notice.id)
        }
        return sent
    }

    private fun InstanceMailTransport.smtp() =
        SmtpTransport(host, port, username, passwordCiphertext, passwordNonce, passwordFingerprint, tls)
}

/**
 * The words of instance mail. Bilingual EN+DE in one message, as the recovery mail is: the instance does not know which
 * language its Server Owner reads. Fixed copy, no template — nothing here is authorable, and nothing is about a user.
 */
object InstanceMailCopy {
    /** The Run area's upgrade page on the published docs site (ADR 0087). */
    const val UPGRADE_PAGE = "https://raddatzk.github.io/veyq/run/upgrade/"

    private val EXPIRY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
    private val DAY: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun codeSubject() = "Confirm your veyq instance mail / Instanz-E-Mail von veyq bestätigen"

    fun codeBody(code: String, expiresAt: Instant) = """
        Your veyq instance can now send you its notices about new releases.

        To confirm that this address reaches you, type this code into the admin console, Server → Mail:

            $code

        It stops working after ${EXPIRY.format(expiresAt)}. Until it is entered, no notice is mailed.

        —

        Deine veyq-Instanz kann dir jetzt ihre Hinweise auf neue Releases schicken.

        Um zu bestätigen, dass diese Adresse dich erreicht, gib diesen Code in der Admin-Konsole unter Server → E-Mail ein:

            $code

        Er wird nach ${EXPIRY.format(expiresAt)} ungültig. Bis er eingegeben ist, wird kein Hinweis gemailt.
    """.trimIndent()

    fun moderatorCodeSubject() = "Confirm your address for moderating veyq / Deine Adresse für die Moderation bei veyq bestätigen"

    fun moderatorCodeBody(code: String, expiresAt: Instant) = """
        You accepted to moderate this veyq instance. Its notices about new cases reach you only at a confirmed address.

        To confirm that this address reaches you, type this code into the moderation surface:

            $code

        It stops working after ${EXPIRY.format(expiresAt)}. In the moderation surface you can ask for a new one.

        —

        Du hast zugestimmt, diese veyq-Instanz zu moderieren. Ihre Hinweise auf neue Fälle erreichen dich nur an einer bestätigten Adresse.

        Um zu bestätigen, dass diese Adresse dich erreicht, gib diesen Code in der Moderation ein:

            $code

        Er wird nach ${EXPIRY.format(expiresAt)} ungültig. In der Moderation kannst du einen neuen anfordern.
    """.trimIndent()

    fun operatorCodeSubject() = "Confirm the notification address of a veyq instance / Benachrichtigungsadresse einer veyq-Instanz bestätigen"

    fun operatorCodeBody(code: String, expiresAt: Instant) = """
        This address was entered as the notification address of a veyq instance's contact. Notices about new
        moderation cases reach it only once it is confirmed.

        To confirm it, type this code into the admin console, Server → Operator:

            $code

        It stops working after ${EXPIRY.format(expiresAt)}. Saving the address again sends a new one.

        —

        Diese Adresse wurde als Benachrichtigungsadresse der Kontaktstelle einer veyq-Instanz eingetragen. Hinweise auf
        neue Moderationsfälle erreichen sie erst, wenn sie bestätigt ist.

        Um sie zu bestätigen, gib diesen Code in der Admin-Konsole unter Server → Betreiber ein:

            $code

        Er wird nach ${EXPIRY.format(expiresAt)} ungültig. Wenn du die Adresse erneut speicherst, kommt ein neuer.
    """.trimIndent()

    fun noticeSubject(n: StoredNotice): String = when (n.kind) {
        NoticeKind.RELEASE -> "veyq ${n.release} is available / ist verfügbar"
        NoticeKind.PLANNED_MAJOR -> "veyq ${n.release.major} is out / ist erschienen"
        NoticeKind.UNSCHEDULED_MAJOR -> "URGENT: veyq ${n.release.major} — upgrade now / DRINGEND: jetzt aktualisieren"
        NoticeKind.WINDOW_REMINDER -> "Reminder: upgrade veyq before ${DAY.format(n.date)} / Erinnerung: vor dem ${DAY.format(n.date)} aktualisieren"
    }

    fun noticeBody(n: StoredNotice): String {
        val (en, de) = when (n.kind) {
            NoticeKind.RELEASE ->
                "A newer release of veyq is published: ${n.release}." to
                    "Ein neueres Release von veyq ist veröffentlicht: ${n.release}."
            NoticeKind.PLANNED_MAJOR ->
                ("veyq ${n.release.major} is out, the planned new major (first release ${n.release}). The major your " +
                    "instance runs stays in the apps' window until ${DAY.format(n.date)}; upgrade before then.") to
                    ("veyq ${n.release.major} ist erschienen, die geplante neue Hauptversion (erstes Release ${n.release}). " +
                        "Die Hauptversion deiner Instanz bleibt bis zum ${DAY.format(n.date)} im Fenster der Apps; aktualisiere vorher.")
            NoticeKind.UNSCHEDULED_MAJOR ->
                ("URGENT: veyq ${n.release.major} (release ${n.release}) is an unscheduled major — a security fix that " +
                    "could not be made without a removal. Upgrade now.") to
                    ("DRINGEND: veyq ${n.release.major} (Release ${n.release}) ist eine ungeplante Hauptversion — eine " +
                        "Sicherheitskorrektur, die nur mit einer Entfernung möglich war. Aktualisiere jetzt.")
            NoticeKind.WINDOW_REMINDER ->
                ("The major your instance runs leaves the apps' window on ${DAY.format(n.date)}, in ${n.daysAhead} days. " +
                    "Upgrade to veyq ${n.release.major} or later before then.") to
                    ("Die Hauptversion deiner Instanz verlässt am ${DAY.format(n.date)} das Fenster der Apps, in ${n.daysAhead} Tagen. " +
                        "Aktualisiere vorher auf veyq ${n.release.major} oder neuer.")
        }
        val dated = n.date != null
        return listOfNotNull(
            en,
            "The date is attached as a calendar entry.".takeIf { dated },
            "How to upgrade: $UPGRADE_PAGE",
            "—",
            de,
            "Das Datum hängt als Kalendereintrag an.".takeIf { dated },
            "So aktualisierst du: $UPGRADE_PAGE",
        ).joinToString("\n\n")
    }
}

/**
 * The calendar entry a dated notice carries (ADR 0097 decision 4): one all-day `VEVENT` on the notice's date, written
 * to RFC 5545 — CRLF line ends, lines folded at 75 octets, text escaped. Its `UID` is the notice's id, so the same
 * notice is the same event however often it is imported. No attendee and no organizer: it is an entry for the Server
 * Owner's own calendar, not an invitation anyone answers.
 */
object NoticeCalendar {
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val DAY: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

    fun event(n: StoredNotice, date: LocalDate, now: Instant): String {
        val summary = when (n.kind) {
            NoticeKind.WINDOW_REMINDER, NoticeKind.PLANNED_MAJOR -> "Upgrade veyq to ${n.release.major} or later"
            else -> "veyq ${n.release}"
        }
        val lines = listOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//veyq//instance notices//EN",
            "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH",
            "BEGIN:VEVENT",
            "UID:${n.id}@veyq",
            "DTSTAMP:${STAMP.format(now)}",
            "DTSTART;VALUE=DATE:${DAY.format(date)}",
            "DTEND;VALUE=DATE:${DAY.format(date.plusDays(1))}",
            "SUMMARY:${text(summary)}",
            "DESCRIPTION:${text(InstanceMailCopy.noticeBody(n))}",
            "URL:${InstanceMailCopy.UPGRADE_PAGE}",
            "TRANSP:TRANSPARENT",
            "END:VEVENT",
            "END:VCALENDAR",
        )
        return lines.joinToString("") { fold(it) + "\r\n" }
    }

    /** RFC 5545 §3.3.11: a backslash, a semicolon, a comma and a newline are escaped in a TEXT value. */
    fun text(value: String): String =
        value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n")

    /** RFC 5545 §3.1: a content line longer than 75 octets continues on the next line after CRLF and one space. */
    fun fold(line: String): String {
        val out = StringBuilder()
        var octets = 0
        var limit = 75
        // By code point, so a fold never splits a character (a surrogate pair, or one UTF-8 sequence).
        line.codePoints().forEach { cp ->
            val ch = String(Character.toChars(cp))
            val size = ch.toByteArray(Charsets.UTF_8).size
            if (octets + size > limit) {
                out.append("\r\n ")
                octets = 0
                limit = 74 // the leading space counts
            }
            out.append(ch)
            octets += size
        }
        return out.toString()
    }
}
