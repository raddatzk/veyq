package me.raddatz.veyq.mobile.session

import org.junit.Assume.assumeTrue
import java.io.File

/**
 * The throwaway account a destructive live case runs as (ADR 0148 decision 12): `VEYQ_LIVE_THROWAWAY_ENV` names the env
 * file `src/web`'s `pnpm run throwaway:account -- --out <file> [--with-member]` wrote (`VEYQ_EMAIL`, `VEYQ_PASSWORD`,
 * `VEYQ_SECRET_KEY`, `VEYQ_TENANT_DOMAIN`, and the member's as `VEYQ_USER2_*`). No such case signs in as the seeded
 * principal or the seeded member: a file naming either's address — read from `VEYQ_ENV_FILE`, else the checkout's
 * `.run/.env` — FAILS the case, never skips it, so a mistyped path cannot change the shared test accounts. Without
 * `VEYQ_LIVE` or `VEYQ_LIVE_THROWAWAY_ENV` the case is skipped (an assumption), never passed.
 */
object Throwaway {
    class Accounts(val file: File, val env: Map<String, String>) {
        fun email(who: String = "VEYQ") = env.getValue("${who}_EMAIL")
        val hasMember: Boolean get() = env["VEYQ_USER2_EMAIL"] != null
    }

    fun origin(): String {
        val origin = System.getenv("VEYQ_LIVE")
        assumeTrue("VEYQ_LIVE is not set: a live case", origin != null)
        return origin!!
    }

    fun accounts(seeded: File? = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: System.getProperty("veyq.repoRoot")?.let { File(it, ".run/.env") }): Accounts {
        val path = System.getenv("VEYQ_LIVE_THROWAWAY_ENV")
        assumeTrue("VEYQ_LIVE_THROWAWAY_ENV is not set: a throwaway's live case", path != null)
        return accountsOf(File(path!!), seeded)
    }

    /** [file]'s accounts, refused (a failure) where it names an address of [seeded]'s. */
    fun accountsOf(file: File, seeded: File?): Accounts {
        val env = read(file)
        val forbidden = seeded?.takeIf { it.isFile }?.let(::read)?.let { listOfNotNull(it["VEYQ_EMAIL"], it["VEYQ_USER2_EMAIL"]) }.orEmpty()
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val named = listOfNotNull(env["VEYQ_EMAIL"], env["VEYQ_USER2_EMAIL"]).map { it.trim().lowercase() }
        if (named.isEmpty()) throw AssertionError("$file names no account")
        if (named.any { it in forbidden }) throw AssertionError("$file names a seeded account (the principal or its member): a destructive live case runs as a throwaway only")
        return Accounts(file, env)
    }

    private fun read(file: File): Map<String, String> = file.readLines()
        .filterNot { it.trimStart().startsWith("#") }
        .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
        .toMap()
}
