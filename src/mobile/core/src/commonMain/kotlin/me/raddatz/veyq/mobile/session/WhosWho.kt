package me.raddatz.veyq.mobile.session

import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.api.models.ScopeMember
import me.raddatz.veyq.mobile.crypto.Trust
import me.raddatz.veyq.mobile.search.Fold
import me.raddatz.veyq.mobile.search.NameOrder
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.ShownText

/**
 * *Who's who* in the open tenant (uc.spaces.enforce-verified-identities main flow 5; ADR 0111 decision 15; inbox #967): a
 * port of the web's `peopleOverview.ts` and of what `PeopleOverviewPanel.vue` shows. For every other member — not this
 * account, not a pending proposal — the one state their key has here, whether this account first saw them under two
 * weeks ago, the members whose verification of their current head counts, a rotation under two weeks old, and whether
 * their invitation replaced a pin that was not theirs. Read by [PersonTrust.whosWho]; display only, nothing here gates
 * anybody's write, and nothing new reaches the server.
 *
 * Either [said] — the one line the page shows in place of a list: the tenant does not open here, the read failed (with
 * [retry]), this device refuses its pin store, or nobody else is in the space — or [people], in the web's order:
 * anomalies first (the one state that asks for action), then by name ([NameOrder] over the folded names), then the id.
 */
@Serializable
data class WhosWho(val people: List<Other>, val said: Msg?, val retry: Boolean) {

    /** The one state a person shows (the web's `OverviewState`). */
    enum class State {
        /** Not anchored here: "Can't confirm this key", with its note. */
        CANT_CONFIRM,
        /** Anchored, and someone's verification of the current head counts. */
        VERIFIED,
        /** Anchored, and nobody's counts. */
        NOT_VERIFIED,
        /** Their check failed for another reason than the key — a request: no state is said, never a false anomaly. */
        UNCHECKED,
    }

    /** One other member as the page lists them. */
    @Serializable
    data class Other(
        val userId: String,
        val username: String,
        /** The display name: the real name the directory seals, else the username. */
        val name: String,
        val state: State,
        /** This account first pinned them less than 14 days ago. Never for an anomaly or an unchecked member. */
        val isNew: Boolean,
        /** The names of the other members whose verification of the current head counts. Empty unless [State.VERIFIED]. */
        val verifiedBy: List<String>,
        /** This account's own verification of the current head counts ("you"). */
        val verifiedByMe: Boolean,
        /** When their chain's newest step — a rotation — was made (epoch ms), while it is under 14 days old; else null. */
        val rotatedAt: Long?,
        /** A join record replaced a pin that was not theirs ("the key you saw before wasn't theirs"). */
        val replaced: Boolean,
    ) {
        // the fields below are carried by the bridge as they are (its JSON encodes defaults), so a host decides none of them
        /** The line under the name: `@username`, as the web's list writes it. */
        val subtitle: String = "@$username"

        /** The state's badge; null for [State.UNCHECKED], which says nothing. */
        val stateWord: Msg? = when (state) {
            State.CANT_CONFIRM -> Msg.TRUST_ANOMALY
            State.VERIFIED -> Msg.SAFETY_STATE_VERIFIED
            State.NOT_VERIFIED -> Msg.SAFETY_STATE_UNVERIFIED
            State.UNCHECKED -> null
        }

        /** The anomaly's note under the row; null otherwise. */
        val note: Msg? = if (state == State.CANT_CONFIRM) Msg.TRUST_ANOMALY_NOTE else null

        /** *Compare safety numbers* — the person's profile, where the compare lives — on every row this account has not verified. */
        val compare: Boolean = !verifiedByMe

        /** [rotatedAt] as a day, as a bridge host shows it (`trust_rotated`'s date, ADR 0154 decision 5). */
        val rotatedText: ShownText? = rotatedAt?.let { ShownText.day(LocalDays.dayOf(it)) }
    }

    internal companion object {
        /** The tenant does not open or list on this device: nobody is listed. */
        val UNAVAILABLE = WhosWho(emptyList(), Msg.WHOS_WHO_UNAVAILABLE, retry = false)

        /** The read failed for a request: said, with *Try again*. */
        val FAILED = WhosWho(emptyList(), Msg.WHOS_WHO_LOAD_FAILED, retry = true)

        /** This device refuses its own pin store: said, never read as nobody (the web's `refusalMessageKey` for the pins). */
        fun refused(code: TrustStateRefused.Code) = WhosWho(
            emptyList(),
            when (code) {
                TrustStateRefused.Code.ROLLED_BACK -> Msg.VERIFY_TRUST_ROLLED_BACK
                TrustStateRefused.Code.UNREADABLE -> Msg.VERIFY_TRUST_UNREADABLE
            },
            retry = false,
        )

        /** How each listed member's anchor check came out. */
        enum class Check { ANOMALY, ANCHORED, UNCHECKED }

        /**
         * The page from what [PersonTrust.whosWho] read (the web's `overviewOf` after its reads): [members] as the directory
         * lists them, [checks] each one's anchor check, [pins] this account's pins, [counted] subject id → the verifier ids
         * whose verification of the current head counts, [rotations] each anchored member's newest step's time, [names]
         * the directory's display names. **Load-bearing:** only an ANCHORED member takes a count, a pin's *new* and
         * *replaced* or a rotation; an anomaly's row is built without any, so it never reads as verified whatever rows the
         * server serves for it.
         */
        fun assemble(
            selfUserId: String,
            members: List<ScopeMember>,
            checks: Map<String, Check>,
            pins: Map<String, Trust.Pin>,
            counted: Map<String, List<String>>,
            rotations: Map<String, Long?>,
            names: Map<String, String>,
            now: Long,
        ): WhosWho {
            val rows = listed(selfUserId, members).map { m ->
                val name = names[m.userId] ?: m.username
                when (checks[m.userId] ?: Check.UNCHECKED) {
                    Check.ANOMALY -> Other(m.userId, m.username, name, State.CANT_CONFIRM, false, emptyList(), false, null, false)
                    Check.UNCHECKED -> Other(m.userId, m.username, name, State.UNCHECKED, false, emptyList(), false, null, false)
                    Check.ANCHORED -> {
                        val pin = pins[m.userId]
                        val by = counted[m.userId].orEmpty()
                        val rotated = rotations[m.userId]
                        Other(
                            userId = m.userId,
                            username = m.username,
                            name = name,
                            state = if (by.isNotEmpty()) State.VERIFIED else State.NOT_VERIFIED,
                            isNew = pin != null && now - pin.firstSeenAt < PersonTrust.NEW_FOR_MS,
                            verifiedBy = by.filter { it != selfUserId }.map { names[it] ?: members.firstOrNull { x -> x.userId == it }?.username ?: it },
                            verifiedByMe = selfUserId in by,
                            rotatedAt = rotated?.takeIf { now - it < PersonTrust.NEW_FOR_MS },
                            replaced = pin?.replaced == true,
                        )
                    }
                }
            }
            if (rows.isEmpty()) return WhosWho(emptyList(), Msg.WHOS_WHO_EMPTY, retry = false)
            return WhosWho(rows.sortedWith(ORDER), null, retry = false)
        }

        /** Who the page lists: every member but this account and a pending proposal. */
        fun listed(selfUserId: String, members: List<ScopeMember>): List<ScopeMember> =
            members.filter { it.userId != selfUserId && it.pendingConsent != true }

        /** Anomalies first, then by name as a person reads it, then the id. */
        private val ORDER: Comparator<Other> = Comparator { a, b ->
            (b.state == State.CANT_CONFIRM).compareTo(a.state == State.CANT_CONFIRM).takeIf { it != 0 }
                ?: NameOrder.compare(Fold.fold(a.name), Fold.fold(b.name)).takeIf { it != 0 }
                ?: a.userId.compareTo(b.userId)
        }
    }
}
