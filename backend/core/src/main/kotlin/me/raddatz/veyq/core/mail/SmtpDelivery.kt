package me.raddatz.veyq.core.mail

import me.raddatz.veyq.core.crypto.ConfigSecretKeyGuard
import me.raddatz.veyq.core.crypto.ConfigSecretKeyState
import me.raddatz.veyq.core.crypto.ConfigSecretSeal
import me.raddatz.veyq.core.crypto.SealedSecret
import me.raddatz.veyq.core.generated.model.DeliveryResult
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.ext.mail.LoginOption
import io.vertx.ext.mail.MailAttachment
import io.vertx.ext.mail.MailClient
import io.vertx.ext.mail.MailConfig
import io.vertx.ext.mail.MailMessage
import io.vertx.ext.mail.SMTPException
import io.vertx.ext.mail.StartTLSOptions
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.util.concurrent.TimeUnit

/**
 * One SMTP relay as a sender needs it: where it is, how to log in, and the password sealed under the config-secret key
 * with the fingerprint of the key that sealed it (ADR 0050, ADR 0089 decision 4). A tenant's settings and the
 * instance's own (ADR 0097 decision 3) each map their row to one; neither row knows the other, and nothing here
 * chooses between them — the caller hands over the one transport it owns.
 */
data class SmtpTransport(
    val host: String?,
    val port: Int?,
    val username: String?,
    val passwordCiphertext: String?,
    val passwordNonce: String?,
    val passwordFingerprint: String?,
    val tls: Boolean,
)

/**
 * The SMTP wire, shared by tenant mail ([me.raddatz.veyq.core.admin.tenant.TenantMailSender]) and instance mail
 * (`InstanceNoticeMailer`, ADR 0097) so both build their `MailClient` the same way. It decides whether a transport can
 * send and puts one message on the wire; it knows nothing about whose transport it is or what the message says.
 *
 * Best-effort by design: a failure is logged secret-free and without the recipient, and reported as [DeliveryResult.FAILED], never thrown.
 * Callers send **after** their own transaction commits, so the bounded [SEND_TIMEOUT_SECONDS] block never holds a DB
 * connection (cr-email-invite-send-hardening).
 */
@ApplicationScoped
class SmtpDelivery(
    private val seal: ConfigSecretSeal,
    private val keyState: ConfigSecretKeyGuard,
    private val vertx: Vertx,
) {
    private val log = Logger.getLogger(SmtpDelivery::class.java)

    /**
     * Whether [t] can put mail on the wire. SMTP auth is keyed on `username`, so an auth-less relay (a trusted LAN
     * MTA) sends with no login and needs neither a stored password nor an openable key. With a username set, both
     * are required: a state other than `OK` (the key ABSENT, or not the one that sealed this password) means the
     * password cannot be unsealed (ADR 0050).
     */
    fun sendable(t: SmtpTransport?): Boolean =
        t != null && !t.host.isNullOrBlank() &&
            (t.username.isNullOrBlank() || (t.passwordCiphertext != null && keyState.stateOf(t.passwordFingerprint) == ConfigSecretKeyState.OK))

    /**
     * Build a client for [t] and send one plain-text message, with [calendar] — an iCalendar document — attached as
     * `text/calendar` when given (the one attachment kind there is, ADR 0097 decision 4). [what] names the mail kind
     * for the failure log; it must never carry a token, a code, a link or anything else secret.
     */
    fun deliver(
        t: SmtpTransport,
        from: String,
        to: String,
        subject: String,
        body: String,
        what: String,
        calendar: CalendarAttachment? = null,
    ): DeliveryResult {
        val cfg = MailConfig()
            .setHostname(t.host)
            .setPort(t.port ?: DEFAULT_SMTP_PORT)
            .setStarttls(if (t.tls) StartTLSOptions.REQUIRED else StartTLSOptions.DISABLED)
        if (!t.username.isNullOrBlank()) {
            cfg.setUsername(t.username)
                .setPassword(seal.unseal(SealedSecret(t.passwordCiphertext!!, t.passwordNonce!!)))
                .setLogin(LoginOption.REQUIRED)
        }
        val client = MailClient.create(vertx, cfg)
        return try {
            val message = MailMessage().setFrom(from).setTo(to).setSubject(subject).setText(body)
            calendar?.let {
                message.setAttachment(
                    listOf(
                        MailAttachment.create()
                            .setName(it.fileName)
                            .setContentType(CalendarAttachment.CONTENT_TYPE)
                            .setDisposition("attachment")
                            .setData(Buffer.buffer(it.ics.toByteArray(Charsets.UTF_8))),
                    ),
                )
            }
            client.sendMail(message).toCompletionStage().toCompletableFuture().get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            DeliveryResult.SENT
        } catch (e: Exception) {
            // The kind and a cause that names no person (ADR 0092 § 3): never a token, a code or a link, and never
            // the recipient — neither as a parameter nor inside a message, since an SMTP server's reply text
            // routinely echoes the address it refused ("550 5.1.1 <someone@example.org>: …"). The caller knows
            // whose mail it was. SmtpDeliveryLogTest pins it.
            log.warnf("SMTP delivery of %s failed: %s", what, cause(e))
            DeliveryResult.FAILED
        } finally {
            client.close()
        }
    }

    companion object {
        const val DEFAULT_SMTP_PORT = 587

        /**
         * What a failed send logs as its cause: the SMTP reply code when the server answered, else the innermost
         * exception's class. Never a message — the reply lines and a transport error's text can both carry the
         * recipient.
         */
        internal fun cause(e: Throwable): String {
            val chain = generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
            chain.firstNotNullOfOrNull { it as? SMTPException }?.let { return "SMTP reply ${it.replyCode}" }
            return chain.last().javaClass.simpleName
        }

        private const val MAX_CAUSE_DEPTH = 8

        /** Bounded, and outside any transaction — long enough for a healthy MTA handshake, short enough that a hung
         *  server does not hold the calling thread. */
        const val SEND_TIMEOUT_SECONDS = 10L
    }
}

/** An iCalendar document to attach, and the file name a mail client shows for it. */
data class CalendarAttachment(val fileName: String, val ics: String) {
    companion object {
        /** `method=PUBLISH`: an event to add to a calendar, with nobody to answer (RFC 5546) — no attendee, no RSVP. */
        const val CONTENT_TYPE = "text/calendar; charset=UTF-8; method=PUBLISH"
    }
}
