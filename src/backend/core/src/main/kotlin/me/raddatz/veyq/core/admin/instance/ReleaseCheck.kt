package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.config.VeyqConfig
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset

/** What one run of the check did. */
sealed interface ReleaseCheckRun {
    /** The running build is unstamped or a snapshot: on no release line, so no request was made. */
    data object NoReleaseLine : ReleaseCheckRun

    /** The Server Owner switched the check off: no request was made. */
    data object Off : ReleaseCheckRun

    /** [source] could not be read or answered something malformed; nothing changed but the log. */
    data class Failed(val reason: String, val source: String = "") : ReleaseCheckRun

    /** The source was read; [newNotices] notices were found for the first time, and [mailed] notices were mailed. */
    data class Checked(val newNotices: Int, val mailed: Int = 0) : ReleaseCheckRun
}

/**
 * The daily look for new releases (ADR 0097 decision 1, uc.admin.learn-of-new-releases). It reads the published
 * releases from the Server Owner's chosen source ([ReleaseFeed]), compares them with this instance's own release
 * **locally** ([ReleaseNoticePolicy]) and stores what it found as notices, each once ([ReleaseNoticeStore.recordFound]),
 * then hands the ones not mailed yet to [InstanceNoticeMailer], which sends only with confirmed instance mail.
 *
 * **Nothing waits on it.** It runs on the scheduler's own thread, never in a request or at startup; a failed run —
 * unreachable, slow past `veyq.release-check.timeout`, a status other than 200, an oversized or malformed answer — is
 * logged and a later run tries again (A2), failures in a row spacing the attempts out ([ReleaseCheckBackoff]). Nothing
 * it reads can make it act: a notice is all it ever writes.
 *
 * **Who checks: a stamped release only.** An unstamped build (no `VEYQ_VERSION`) and a `-SNAPSHOT` are on no release
 * line, so [check] returns before it reads its own setting — every local image, `quarkusDev` run and test lane stays
 * quiet by construction, which ADR 0097's "offline runs" consequence needs, with no switch of its own.
 */
@ApplicationScoped
class ReleaseCheck(
    private val identity: ReleaseIdentity,
    private val store: ReleaseNoticeStore,
    private val mailer: InstanceNoticeMailer,
    config: VeyqConfig,
) {
    private val log = Logger.getLogger(ReleaseCheck::class.java)
    private val http = BoundedGet(config.releaseCheck().timeout())
    private val backoff = ReleaseCheckBackoff(config.releaseCheck().every())

    @Scheduled(every = "{veyq.release-check.every}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun run() {
        try {
            // A build on no release line reads no setting and asks nothing ([check]); only a stamped one backs off.
            if (ReleaseVersion.parse(identity.version) == null) return
            val setting = store.setting().let { it.enabled to it.source }
            if (!backoff.due(setting)) return
            when (val run = check(identity.version, Instant.now())) {
                is ReleaseCheckRun.Failed -> log.warnf(
                    "The release check could not read %s: %s. It tries again in %s.",
                    run.source, run.reason, backoff.failed(setting),
                )
                else -> backoff.succeeded(setting)
            }
        } catch (e: RuntimeException) {
            // A defect here must not reach the scheduler as an exception it reports on every tick; the next run retries.
            log.errorf(e, "The release check failed unexpectedly")
        }
    }

    /**
     * One check for an instance running [running] (the release stamp, as [ReleaseIdentity] reads it) at [now]. The
     * scheduled [run] passes this instance's own; a test passes the release it is about.
     */
    fun check(running: String?, now: Instant): ReleaseCheckRun {
        val version = ReleaseVersion.parse(running) ?: return ReleaseCheckRun.NoReleaseLine
        val setting = store.setting()
        if (!setting.enabled) return ReleaseCheckRun.Off
        val feed = feedFor(setting.source)
        val found = try {
            val listed = feed.list().filterNot { it.prerelease }
            val published = listed.map { it.release }
            val tags = listed.associate { it.release.version to it.tag }
            val unscheduled = ReleaseNoticePolicy.firstReleasesOfNewerMajors(version, published)
                .filter { feed.unscheduled(tags.getValue(it.version)) }
                .map { it.version.major }
                .toSet()
            ReleaseNoticePolicy.notices(version, published, unscheduled, now.atZone(ZoneOffset.UTC).toLocalDate())
        } catch (e: ReleaseSourceException) {
            // Logged by [run], which knows when it tries again; a direct call (a test) reads it off the result.
            return ReleaseCheckRun.Failed(e.message ?: "unreadable", sourceName(setting.source))
        }
        val new = store.recordFound(found, now)
        store.recordSuccess(now)
        if (new > 0) log.infof("The release check found %d new notice(s).", new)
        // Each notice not mailed yet goes to a confirmed Server Owner once (ADR 0097 decision 4). A mail that fails
        // stays for the next check; nothing about the mail can fail the check itself.
        val mailed = try {
            mailer.mailPending(version, now)
        } catch (e: RuntimeException) {
            log.errorf(e, "Mailing the release notices failed; they stay in the console and are tried at the next check")
            0
        }
        return ReleaseCheckRun.Checked(new, mailed)
    }

    private fun feedFor(source: String?): ReleaseFeed =
        if (source == null) {
            ReleaseFeed.GitHub(ReleaseFeed.GitHub.DEFAULT_LISTING, ReleaseFeed.GitHub.DEFAULT_DOWNLOADS, http)
        } else {
            ReleaseFeed.Mirror(URI.create(source), http)
        }

    private fun sourceName(source: String?): String = source ?: "the GitHub Releases"
}
