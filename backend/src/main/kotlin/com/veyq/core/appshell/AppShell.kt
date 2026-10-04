package com.veyq.core.appshell

/**
 * What the backend found of the web client on its classpath, decided **once at boot** (ADR 0069). The release
 * image puts `web/dist` into `META-INF/resources`; dev (Vite serves the app) and the CI e2e jar put nothing
 * there, and are [Absent]: the backend then serves the API only, with the plain headers and no fallback.
 *
 * Pure on purpose — no classpath, no Vert.x — so the decision about *what* is served is unit-testable apart from
 * the route that serves it ([AppShellRoutes]).
 */
sealed interface AppShell {
    data object Absent : AppShell

    /** [csp] is the emitted policy with the store origin already appended — never absent (see [from]). */
    class Present(val indexHtml: ByteArray, val csp: String) : AppShell

    companion object {
        /** The emitted policy's name inside `dist`, next to `index.html`. `web/vite.config.ts` writes it. */
        const val POLICY_FILE = "content-security-policy.txt"
        const val INDEX_FILE = "index.html"

        /**
         * **Fails closed** (ADR 0069 Decision 3): a shell whose policy is missing, or whose `index.html` still
         * carries a CSP `<meta>`, refuses to start rather than boot quietly. The first would serve an
         * operator-blind client with no backstop at all; the second would intersect with the header and block
         * every store fetch on its own origin again (the Kubernetes target), which no CI run would notice.
         * `npm run verify:csp` checks the same two properties of `dist` before an image is built, so this is
         * the last line, not the first.
         */
        fun from(indexHtml: ByteArray?, policy: String?, storeOrigin: String?): AppShell {
            if (indexHtml == null) return Absent
            check(!policy.isNullOrBlank()) {
                "app shell: $INDEX_FILE is on the classpath but $POLICY_FILE is missing or empty — refusing to " +
                    "serve the web client without its Content-Security-Policy (ADR 0069)"
            }
            check(!CSP_META.containsMatchIn(indexHtml.toString(Charsets.UTF_8))) {
                "app shell: $INDEX_FILE carries a Content-Security-Policy <meta>, which would intersect with the " +
                    "header and block the store — refusing to serve it (ADR 0069)"
            }
            return Present(indexHtml, withStoreOrigin(policy, storeOrigin))
        }

        /**
         * [policy] with [storeOrigin] appended to its `connect-src`, the one directive a store needs: every
         * client fetches objects and decrypts them, and nothing a store returns is ever rendered (ADR 0013 §2).
         *
         * A policy with no `connect-src` is refused rather than given one: `connect-src` would then fall back to
         * `default-src`, and a directive written here from scratch would replace that fallback instead of
         * extending it — a narrowing or a widening nobody reviewed, depending on what `default-src` says.
         */
        fun withStoreOrigin(policy: String, storeOrigin: String?): String {
            val directives = policy.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            if (storeOrigin == null) return directives.joinToString("; ")
            check(directives.any { it.split(' ').first() == CONNECT_SRC }) {
                "the emitted policy has no $CONNECT_SRC, so the store origin $storeOrigin cannot be appended to it"
            }
            return directives.joinToString("; ") {
                if (it.split(' ').first() == CONNECT_SRC) "$it $storeOrigin" else it
            }
        }

        private const val CONNECT_SRC = "connect-src"

        /** The same shape `web/scripts/assert-csp-policy-in-dist.mjs` refuses. */
        private val CSP_META = Regex("""<meta[^>]*http-equiv\s*=\s*["']?content-security-policy""", RegexOption.IGNORE_CASE)
    }
}
