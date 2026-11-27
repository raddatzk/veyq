package me.raddatz.veyq.mobile.sharing

/**
 * A text another app shared to veyq (inbox #1015, ADR 0150 § S): Android's share sheet hands `text/plain`, iOS's share
 * extension a `public.url` or a `public.plain-text`, and either is often more than the link — a chat's "Look at this:
 * https://…", a mail's line with the link in angle brackets. [linkIn] finds the one link in it that [OpenLink] then
 * checks as it checks a pasted one ([ReceivedLink.open]), so a shared link opens exactly as *Open a link* opens it, on
 * every app. Pure: nothing is fetched, kept or logged; the text holds the key, so neither it nor what is found outlives the
 * caller's use.
 *
 * - The whole text, trimmed, a link *Open a link* opens: [Found.One] of it.
 * - Else every word (split at white space) holding `http://` or `https://` is a candidate, read from that scheme on and
 *   without the punctuation a sentence or a mark-up closes it with (`.`, `,`, `)`, `>`, quotes …): no share link ends in
 *   one (its key's last character is a letter or a digit, `&report` and `#moderation` end in a letter). Exactly one
 *   distinct link *Open a link* opens among them: [Found.One] of it, whatever else the text holds (a sender's own homepage
 *   beside it). Two or more distinct ones: [Found.Several] — which one was meant is the person's to say, never guessed.
 * - No candidate opens: the first candidate as [Found.One], so its refusal says why (a foreign link
 *   [ReceivedLink.Problem.NOT_A_SHARE], a moderator's review [ReceivedLink.Problem.MODERATION], a cut-off key …); no
 *   candidate at all: [Found.None].
 */
object SharedText {
    sealed class Found {
        /** The text to check as *Open a link* checks a pasted one: the link found, or the one candidate refused. */
        class One(val text: String) : Found() {
            override fun toString(): String = "One" // the text holds the key
        }

        /** The text holds no link at all. */
        data object None : Found()

        /** The text holds more than one link the app would open. */
        data object Several : Found()
    }

    private const val CLOSERS = ".,;:!?)]}>'\"«»“”‘’‹›"

    fun linkIn(text: String): Found {
        val whole = text.trim()
        if (ReceivedLink.open(whole) is ReceivedLink.Parsed.Ok || ReceivedLink.inviteOf(whole) != null) return Found.One(whole)
        val candidates = whole.split(Regex("\\s+")).mapNotNull(::candidate)
        if (candidates.isEmpty()) return Found.None
        // an invite opens too (its join, not a guest view): counted by its own text, so two invites are two links
        val opened = candidates.mapNotNull { c -> (ReceivedLink.open(c) as? ReceivedLink.Parsed.Ok)?.let { c to it.link.link } ?: ReceivedLink.inviteOf(c)?.let { c to c } }
        return when (opened.distinctBy { it.second }.size) {
            0 -> Found.One(candidates.first())
            1 -> Found.One(opened.first().first)
            else -> Found.Several
        }
    }

    /** [word] from its `http://` or `https://` on, its closing punctuation dropped; null where it holds neither. */
    private fun candidate(word: String): String? {
        val lower = word.lowercase()
        val at = listOf(lower.indexOf("https://"), lower.indexOf("http://")).filter { it >= 0 }.minOrNull() ?: return null
        return word.substring(at).trimEnd { it in CLOSERS }.takeIf { it.isNotEmpty() }
    }
}
