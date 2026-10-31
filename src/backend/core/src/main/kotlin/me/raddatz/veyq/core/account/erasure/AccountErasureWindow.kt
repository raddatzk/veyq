package me.raddatz.veyq.core.account.erasure

import me.raddatz.veyq.core.config.VeyqConfig
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.time.Duration

/**
 * `veyq.account.erasure.window` as [AccountErasureHandler] reads it (ADR 0101 decision 3): an account is erased this long
 * after the request, by the purge that erases its own scopes with it — 14 days unless the Server Owner sets another.
 * A bean of its own so a slice can state another window for one test (`QuarkusMock`) instead of a `@TestProfile`: one
 * more profile is one more boot in `:core`'s test JVM, whose heap is near its ceiling (src/backend/CLAUDE.md).
 *
 * **The boot refuses a window that cannot be one** ([refusal]), in every mode, as `BlobStoreStartupCheck` refuses its
 * values: not positive (an erasure with no window is final at once, which decision 3 rejects for a mistaken or coerced
 * request), or long enough that the erasure — at the first purge tick at or after its due date — can complete past
 * 28 days, the shortest calendar month. The 30 days were rejected for not fitting every month; a configured window
 * must not bring them back.
 */
@ApplicationScoped
class AccountErasureWindow(private val config: VeyqConfig) {
    fun current(): Duration = config.account().erasure().window()

    fun onStart(@Observes ev: StartupEvent) {
        refusal(current(), config.account().erasure().purge().every())?.let { error(it) }
    }

    companion object {
        /** The shortest calendar month: a window that ends within it ends within one month of any request date. */
        val LONGEST: Duration = Duration.ofDays(28)

        /** Null when [window] is one, else what the operator reads when the boot aborts, naming the key to fix. */
        fun refusal(window: Duration, purgeEvery: Duration): String? = when {
            window.isNegative || window.isZero ->
                "veyq.account.erasure.window=$window must be positive: an erasure with no window is final at the request, " +
                    "with no time to stop a mistaken or coerced one (ADR 0101 decision 3)."
            window.plus(purgeEvery) > LONGEST ->
                "veyq.account.erasure.window=$window plus veyq.account.erasure.purge.every=$purgeEvery must not exceed " +
                    "$LONGEST, the shortest calendar month: the erasure completes at the first purge after its due date, " +
                    "and a longer window does not fit every month (ADR 0101 decision 3)."
            else -> null
        }
    }
}
