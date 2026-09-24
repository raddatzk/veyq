package com.veyq.app.shared.appshell

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

    /**
     * [csp] is the emitted policy with the store origin already appended, or null while no emitted policy
     * exists — the web build still ships its `<meta>` then, and a header would only intersect with it.
     */
    class Present(val indexHtml: ByteArray, val csp: String?) : AppShell

    companion object {
        /** The emitted policy's name inside `dist`, next to `index.html`. `web/vite.config.ts` writes it. */
        const val POLICY_FILE = "content-security-policy.txt"
        const val INDEX_FILE = "index.html"

        fun from(indexHtml: ByteArray?, policy: String?, storeOrigin: String?): AppShell {
            if (indexHtml == null) return Absent
            return Present(indexHtml, policy?.let { withStoreOrigin(it, storeOrigin) })
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
    }
}
