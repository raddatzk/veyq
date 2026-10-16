package me.raddatz.veyq.core.admin.instance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A source that could not be read, or answered something that is not a release list — a failed check, never a notice. */
class ReleaseSourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One entry of a source's list: the release, its UTC day, whether it is a prerelease, and the tag to fetch it by. */
data class ListedRelease(val tag: String, val release: PublishedRelease, val prerelease: Boolean)

/**
 * Where the published releases are read (ADR 0096 decision 3, ADR 0097 decision 1). Two questions only: which
 * releases exist, and whether one was an unscheduled major. Both are anonymous GETs that name no release of this
 * instance — the tag asked about is one the source just listed — and carry no header of the instance's own (the JDK
 * client's `User-Agent` names the Java runtime, nothing else).
 *
 * Every answer is size-capped and parsed strictly: a body over its cap, a status other than 200, JSON of the wrong
 * shape or a manifest that names another release is a [ReleaseSourceException], so a malformed source fails the check
 * rather than producing a notice. A forged but well-formed list can at worst produce a false notice (decision 2).
 */
sealed class ReleaseFeed(protected val http: BoundedGet) {

    /** Every release the source lists, prereleases included (the caller skips them). */
    abstract fun list(): List<ListedRelease>

    /** Whether [tag]'s manifest marks it an unscheduled major (ADR 0085 decision 4). */
    abstract fun unscheduled(tag: String): Boolean

    /**
     * A manifest (`release_assets.py`: `{release, date, images, unscheduled, assets}`), held to the tag it was fetched
     * by. Only `release` and `unscheduled` are read; the rest is `veyqctl`'s.
     */
    protected fun parseManifest(tag: String, body: ByteArray): Boolean {
        val doc = http.json(body, "manifest of $tag")
        if (!doc.isObject) throw ReleaseSourceException("the manifest of $tag is not an object")
        val release = doc.get("release")
        if (release == null || !release.isTextual || "v${release.asText()}" != tag) {
            throw ReleaseSourceException("the manifest of $tag names another release")
        }
        val unscheduled = doc.get("unscheduled")
        if (unscheduled == null || !unscheduled.isBoolean) throw ReleaseSourceException("the manifest of $tag has no boolean `unscheduled`")
        return unscheduled.booleanValue()
    }

    /**
     * A mirror in the layout `scripts/release_assets.py` writes: `<base>/releases.json` =
     * `{"releases": [{tag, date, prerelease, manifest}]}` and `<base>/<tag>/manifest.json`.
     */
    class Mirror(base: URI, http: BoundedGet) : ReleaseFeed(http) {
        private val base: URI = URI.create(base.toString().trimEnd('/') + "/")

        override fun list(): List<ListedRelease> {
            val doc = http.json(http.get(base.resolve("releases.json"), MAX_LISTING_BYTES), "releases.json")
            val releases = doc.get("releases")
            if (!doc.isObject || releases == null || !releases.isArray) throw ReleaseSourceException("releases.json has no `releases` array")
            return releases.mapNotNull { entry ->
                if (!entry.isObject) throw ReleaseSourceException("releases.json lists something that is not a release")
                val tag = entry.text("tag")
                val prerelease = entry.get("prerelease")?.takeIf { it.isBoolean }?.booleanValue()
                    ?: throw ReleaseSourceException("releases.json: $tag has no boolean `prerelease`")
                val date = day(entry.text("date"), tag)
                ReleaseVersion.parse(tag.removePrefix("v").takeIf { tag.startsWith("v") })
                    ?.let { ListedRelease(tag, PublishedRelease(it, date), prerelease) }
            }
        }

        override fun unscheduled(tag: String): Boolean =
            parseManifest(tag, http.get(base.resolve("${checkedTag(tag)}/manifest.json"), MAX_MANIFEST_BYTES))
    }

    /**
     * The published GitHub Releases: the REST API's list (newest first, one page) and each release's `manifest.json`
     * asset, which the download URL redirects to. The default source; [GitHub.DEFAULT] is veyq's own repository.
     */
    class GitHub(private val listing: URI, private val downloads: URI, http: BoundedGet) : ReleaseFeed(http) {

        override fun list(): List<ListedRelease> {
            val doc = http.json(http.get(listing, MAX_LISTING_BYTES, GITHUB_ACCEPT), "the GitHub release list")
            if (!doc.isArray) throw ReleaseSourceException("the GitHub release list is not an array")
            return doc.mapNotNull { entry ->
                if (!entry.isObject) throw ReleaseSourceException("the GitHub release list holds something that is not a release")
                val tag = entry.text("tag_name")
                val draft = entry.get("draft")?.takeIf { it.isBoolean }?.booleanValue() ?: false
                val prerelease = entry.get("prerelease")?.takeIf { it.isBoolean }?.booleanValue()
                    ?: throw ReleaseSourceException("the GitHub release $tag has no boolean `prerelease`")
                val published = entry.get("published_at")?.takeIf { it.isTextual }?.asText()
                if (draft || published == null) return@mapNotNull null
                val date = try {
                    Instant.parse(published).atZone(ZoneOffset.UTC).toLocalDate()
                } catch (e: DateTimeParseException) {
                    throw ReleaseSourceException("the GitHub release $tag has no readable `published_at`", e)
                }
                ReleaseVersion.parse(tag.removePrefix("v").takeIf { tag.startsWith("v") })
                    ?.let { ListedRelease(tag, PublishedRelease(it, date), prerelease) }
            }
        }

        override fun unscheduled(tag: String): Boolean =
            parseManifest(tag, http.get(downloads.resolve("${checkedTag(tag)}/manifest.json"), MAX_MANIFEST_BYTES))

        companion object {
            /** veyq's own releases — the default source, as `veyqctl`'s (ADR 0096 decision 3). */
            val DEFAULT_LISTING: URI = URI.create("https://api.github.com/repos/raddatzk/veyq/releases?per_page=100")
            val DEFAULT_DOWNLOADS: URI = URI.create("https://github.com/raddatzk/veyq/releases/download/")
            private const val GITHUB_ACCEPT = "application/vnd.github+json"
        }
    }

    protected fun JsonNode.text(field: String): String =
        get(field)?.takeIf { it.isTextual }?.asText() ?: throw ReleaseSourceException("a listed release has no string `$field`")

    protected fun day(text: String, tag: String): LocalDate = try {
        LocalDate.parse(text)
    } catch (e: DateTimeParseException) {
        throw ReleaseSourceException("$tag has no readable date", e)
    }

    /** A tag goes into a URL path only in the shape a release has (`v<major>.<minor>`), so no listing can steer a path. */
    protected fun checkedTag(tag: String): String =
        tag.takeIf { it.startsWith("v") && ReleaseVersion.parse(it.removePrefix("v")) != null }
            ?: throw ReleaseSourceException("`$tag` is not a release tag")

    companion object {
        /** A list of a few hundred releases is tens of KiB; a MiB is far past any honest one. */
        const val MAX_LISTING_BYTES = 1 shl 20

        /** A manifest is a few hundred bytes. */
        const val MAX_MANIFEST_BYTES = 64 shl 10
    }
}

/**
 * A GET with a deadline and a size cap, for a source the Server Owner did not write (ADR 0097). It follows a redirect
 * (GitHub's asset download is one) but never from `https` to `http` (the JDK's `NORMAL`). The whole exchange — connect,
 * headers and body — must finish within twice the timeout, and the body is collected only up to `cap` bytes: a byte
 * more cancels the stream, so neither a slow nor an endless answer can hold the check longer or cost more than that.
 */
class BoundedGet(private val timeout: Duration) {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val mapper = ObjectMapper()

    fun get(uri: URI, cap: Int, accept: String = "application/json"): ByteArray {
        val request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", accept).GET().build()
        val exchange = client.sendAsync(request) { info ->
            if (info.statusCode() == 200) CappedBody(cap) else HttpResponse.BodySubscribers.replacing(ByteArray(0))
        }
        val response = try {
            exchange.get(timeout.toMillis() * 2, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            exchange.cancel(true)
            throw ReleaseSourceException("$uri did not answer within ${timeout.toMillis() * 2} ms", e)
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is TooLarge) throw ReleaseSourceException("$uri answered more than $cap bytes", cause)
            throw ReleaseSourceException("$uri could not be read: ${cause?.javaClass?.simpleName}", cause)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            exchange.cancel(true)
            throw ReleaseSourceException("reading $uri was interrupted", e)
        }
        if (response.statusCode() != 200) throw ReleaseSourceException("$uri answered ${response.statusCode()}")
        return response.body()
    }

    fun json(body: ByteArray, what: String): JsonNode = try {
        mapper.readTree(body) ?: throw ReleaseSourceException("$what is empty")
    } catch (e: IOException) {
        throw ReleaseSourceException("$what is not JSON", e)
    }

    private class TooLarge : IOException("body over its cap")

    /** Collects a body up to [cap] bytes and refuses the byte after, cancelling the stream rather than draining it. */
    private class CappedBody(private val cap: Int) : HttpResponse.BodySubscriber<ByteArray> {
        private val result = CompletableFuture<ByteArray>()
        private val out = ByteArrayOutputStream()
        private var subscription: Flow.Subscription? = null

        override fun getBody(): CompletionStage<ByteArray> = result

        override fun onSubscribe(subscription: Flow.Subscription) {
            this.subscription = subscription
            subscription.request(Long.MAX_VALUE)
        }

        override fun onNext(item: List<ByteBuffer>) {
            if (result.isDone) return
            for (buffer in item) {
                if (out.size() + buffer.remaining() > cap) {
                    subscription?.cancel()
                    result.completeExceptionally(TooLarge())
                    return
                }
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                out.write(bytes)
            }
        }

        override fun onError(throwable: Throwable) {
            result.completeExceptionally(throwable)
        }

        override fun onComplete() {
            result.complete(out.toByteArray())
        }
    }
}
