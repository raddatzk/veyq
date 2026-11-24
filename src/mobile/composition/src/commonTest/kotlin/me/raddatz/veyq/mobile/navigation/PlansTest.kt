package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.navigation.ShareSheet
import me.raddatz.veyq.mobile.navigation.LinkAction

import me.raddatz.veyq.mobile.session.LinkRow
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.ShareLinks
import me.raddatz.veyq.mobile.session.ShareWords
import me.raddatz.veyq.mobile.session.SignInEntry
import me.raddatz.veyq.mobile.session.SignInPlan
import kotlin.test.Test
import kotlin.test.assertEquals

/** The plans a UI draws whole and in order (ADR 0154 decision 2): the sign-in's entries and a share link row's menu. */
class PlansTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    @Test
    fun theSignInSubmitsFirstThenCancelsThenTheOtherWaysIn() {
        // owner 2026-11-20 (the CR's Q4): the web's LoginView — Submit first, then the locked entry, the links, the kit,
        // Forgot your password?, Open a link (ADR 0154 P9)
        assertEquals(
            listOf(
                SignInEntry.SUBMIT, SignInEntry.CANCEL, SignInEntry.LOCKED, SignInEntry.LINK_SHOW, SignInEntry.LINK_SCAN,
                SignInEntry.KIT_OPEN, SignInEntry.KIT_SCAN, SignInEntry.FORGOT, SignInEntry.OPEN_LINK,
            ),
            SignInPlan.entries(SessionModel.Gate.SignIn(adding = true), camera = true),
        )
        // no camera: no scan; not adding: no cancel
        assertEquals(
            listOf(SignInEntry.SUBMIT, SignInEntry.LOCKED, SignInEntry.LINK_SHOW, SignInEntry.KIT_OPEN, SignInEntry.FORGOT, SignInEntry.OPEN_LINK),
            SignInPlan.entries(SessionModel.Gate.SignIn(), camera = false),
        )
    }

    private fun row(enabled: Boolean, mine: Boolean, deletable: Boolean = false, url: String? = "https://t/s/x#k=y") =
        LinkRow(ShareLinks.Link("x", ShareLinks.READ, enabled, mine, "2026-11-01T10:00:00Z", null, "", false, false, url, false, false, deletable), false, ShareWords.of("FOLDER"))

    @Test
    fun aLinkRowsMenuIsTheWebsCopyEditRenewSwitchOnSwitchOffDelete() {
        assertEquals(
            listOf(LinkAction.COPY, LinkAction.EDIT, LinkAction.RENEW, LinkAction.SWITCH_OFF),
            ShareSheet.rowActions(row(enabled = true, mine = true)),
        )
        assertEquals(listOf(LinkAction.COPY), ShareSheet.rowActions(row(enabled = true, mine = false)))
        assertEquals(
            listOf(LinkAction.RENEW, LinkAction.REACTIVATE, LinkAction.DELETE),
            ShareSheet.rowActions(row(enabled = false, mine = false, deletable = true)),
        )
    }
}
