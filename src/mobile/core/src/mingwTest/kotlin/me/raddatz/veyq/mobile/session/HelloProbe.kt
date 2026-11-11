@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.session

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.crypto.unb64
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.sleep
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * The Hello half of [WindowsDeviceKeys], which no unattended test can run: a Hello prompt needs the person at the
 * console, never an agent (the CR's D3, O2). `run.py hello-probe` starts it in the logged-on session (the scheduled task
 * `veyq-app`) with `VEYQ_HELLO_PROBE` naming its outcome file; without that variable (`run.py test`) it does nothing.
 *
 * It seals under a Passport key of its own (`<SID>//veyq//probe`, never the app's), then, as a new start would, opens
 * (Hello asks: the person enters the PIN), opens again at once (the 15 s window: no prompt), waits out the window and
 * opens once more (Hello asks: the person cancels — `cancelled = true`), then deletes the key and opens (`false`), and
 * shows the Passport key itself gone.
 * Every step is written as one line `<step> <outcome> <ms>` as it ends, beside what the prompt said, so the run can be
 * followed; the file's last line is `done`. With `VEYQ_HELLO_PROBE_CHECK` set it stops before the first key: no prompt.
 */
class HelloProbe {
    @Test
    fun helloProbe() {
        val out = getenv("VEYQ_HELLO_PROBE")?.toKString() ?: return
        val log = Outcome(out)
        log.line("hello-available ${Hello.available()}")
        val window = Hello.consoleWindow()
        log.line("window 0x${window.toString(16)}")
        // `run.py hello-probe --check`: the launch, the session and Hello's availability there, no key and no prompt
        if (getenv("VEYQ_HELLO_PROBE_CHECK") != null) return log.line("done")
        val store = MemoryDeviceStore()
        val plaintext = "veyq hello probe".encodeToByteArray()
        val aad = "probe".encodeToByteArray()
        runBlocking {
            val sealing = WindowsDeviceKeys(store, window, "veyq probe 1: if asked, enter your PIN", PROBE_KEY)
            val (iv, ct) = log.step("seal", "if asked: enter your PIN") { sealing.sealUnlockable(plaintext, aad) } ?: return@runBlocking
            log.line("path ${sealing.unlockPath}")
            val started = WindowsDeviceKeys(store, window, "veyq probe 2: enter your PIN", PROBE_KEY)
            log.step("open", "enter your PIN") { check(started.openUnlockable(iv, ct, aad).contentEquals(plaintext)) { "opened to other bytes" } }
            log.step("open-in-window", "no prompt") { check(started.openUnlockable(iv, ct, aad).contentEquals(plaintext)) { "opened to other bytes" } }
            sleep(16u)
            started.prompt = "veyq probe 3: press Cancel"
            log.step("open-after-window", "press Cancel") { started.openUnlockable(iv, ct, aad) }
            val blob = store.read(WindowsDeviceKeys.UNLOCK)
            started.deleteUnlockKey()
            log.step("open-after-delete", "no prompt") { started.openUnlockable(iv, ct, aad) }
            // the Passport key itself is gone, not only the store's record of it
            if (blob != null) log.step("passport-key-after-delete", "no prompt") { PassportGuard(PROBE_KEY, window).unprotect(unb64(blob)) }
        }
        log.line("done")
    }

    private class Outcome(private val path: String) {
        fun line(text: String) {
            val f = fopen(path, "a") ?: return
            fputs(text + "\n", f)
            fflush(f)
            fclose(f)
            println("veyq hello-probe: $text")
        }

        /** Runs [block], says before it what the person is asked to do, and after it how it ended. */
        suspend fun <T> step(name: String, ask: String, block: suspend () -> T): T? {
            line("$name asking: $ask")
            val start = TimeSource.Monotonic.markNow()
            return try {
                block().also { line("$name ok ${start.elapsedNow().inWholeMilliseconds}") }
            } catch (e: DeviceAuthFailed) {
                line("$name DeviceAuthFailed(cancelled=${e.cancelled}) ${e.message} ${start.elapsedNow().inWholeMilliseconds}")
                null
            } catch (e: Throwable) {
                line("$name ${e::class.simpleName} ${e.message} ${start.elapsedNow().inWholeMilliseconds}")
                null
            }
        }
    }

    private companion object {
        const val PROBE_KEY = "probe"
    }
}
