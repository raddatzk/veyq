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

    /** The source could not be read or answered something malformed; nothing changed but the log. */
    data class Failed(val reason: String) : ReleaseCheckRun

    /** The source was read; [newNotices] notices were found for the first time. */
    data class Checked(val newNotices: Int) : ReleaseCheckRun
}

/**
 * The daily look for new releases (ADR 0097 decision 1, uc.admin.learn-of-new-releases). It reads the published
 * releases from the Server Owner's chosen source ([ReleaseFeed]), compares them with this instance's own release
 * **locally** ([ReleaseNoticePolicy]) and stores what it found as notices, each once ([ReleaseNoticeStore.recordFound]).
 *
 * **Nothing waits on it.** It runs on the scheduler's own thread, never in a request or at startup; a failed run —
 * unreachable, slow past `veyq.release-check.timeout`, a status other than 200, an oversized or malformed answer — is
 * logged and the next run tries again (A2). Nothing it reads can make it act: a notice is all it ever writes.
 *
 * **Who checks: a stamped release only.** An unstamped build (no `VEYQ_VERSION`) and a `-SNAPSHOT` are on no release
 * line, so [check] returns before it reads its own setting — every local image, `quarkusDev` run and test lane stays
 * quiet by construction, which ADR 0097's "offline runs" consequence needs, with no switch of its own.
 */
@ApplicationScoped
class ReleaseCheck(
    private val identity: ReleaseIdentity,
    private val store: ReleaseNoticeStore,
    config: VeyqConfig,
) {
    private val log = Logger.getLogger(ReleaseCheck::class.java)
    private val http = BoundedGet(config.releaseCheck().timeout())

    @Scheduled(every = "{veyq.release-check.every}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun run() {
        try {
            check(identity.version, Instant.now())
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
            log.warnf("The release check could not read %s: %s. It tries again at its next run.", sourceName(setting.source), e.message)
            return ReleaseCheckRun.Failed(e.message ?: "unreadable")
        }
        val new = store.recordFound(found, now)
        store.recordSuccess(now)
        if (new > 0) log.infof("The release check found %d new notice(s).", new)
        return ReleaseCheckRun.Checked(new)
    }

    private fun feedFor(source: String?): ReleaseFeed =
        if (source == null) {
            ReleaseFeed.GitHub(ReleaseFeed.GitHub.DEFAULT_LISTING, ReleaseFeed.GitHub.DEFAULT_DOWNLOADS, http)
        } else {
            ReleaseFeed.Mirror(URI.create(source), http)
        }

    private fun sourceName(source: String?): String = source ?: "the GitHub Releases"
}
