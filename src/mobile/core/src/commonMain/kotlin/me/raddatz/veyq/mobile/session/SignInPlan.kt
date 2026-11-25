package me.raddatz.veyq.mobile.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.navigation.Plan

/**
 * An entry of the sign-in below its fields (ADR 0154 decision 2): the ways in a [SessionModel.Gate.SignIn] offers, each a
 * button or a row in the platform's look. Their words: [SUBMIT] `signin_submit`, [CANCEL] `signin_cancel`, [LINK_SHOW]
 * `signin_link_show`, [LINK_SCAN] `signin_link_scan`, [KIT_OPEN] `signin_kit_open`, [KIT_SCAN] `signin_kit_scan`,
 * [LOCKED] `signin_locked`, [FORGOT] `signin_forgot`, [OPEN_LINK] `link_open`.
 */
@Plan
@Serializable
enum class SignInEntry {
    /** Signs in with the fields (`SessionModel.signIn`). */
    @SerialName("submit") SUBMIT,
    /** Back to the shell, while another account is being added ([SessionModel.Gate.SignIn.adding]). */
    @SerialName("cancel") CANCEL,
    /**
     * *I don't have my Secret Key with me* (uc.account.sessions E3): signs in without unlocking, to sign every device out
     * (`SessionModel.signInLocked`); the email and the password are needed, the Secret Key is not.
     */
    @SerialName("locked") LOCKED,
    /** This device shows a code another device scans (ADR 0059 §1): on every device. */
    @SerialName("link-show") LINK_SHOW,
    /** This device scans the code a signed-in device shows (ADR 0059 §1): where a camera can be. */
    @SerialName("link-scan") LINK_SCAN,
    /** The Emergency Kit read from a file (a PDF or a picture of its code). */
    @SerialName("kit-open") KIT_OPEN,
    /** The Emergency Kit's code scanned with the camera: where a camera can be. */
    @SerialName("kit-scan") KIT_SCAN,
    /** *Forgot your password?* (uc.account.recover-access): the recovery link asked for (`SessionModel.forgotPassword`). */
    @SerialName("forgot") FORGOT,
    /** *Open a link* (ADR 0150 decision 4): a received link opened as its guest, no session needed. */
    @SerialName("open-link") OPEN_LINK,
}

/**
 * The sign-in's entries, in their order (owner 2026-11-20 on cr-apps-structure-from-core-plans' Q4: the web's
 * `LoginView` — Submit first, then the other ways in): Submit, Cancel, the locked entry (the web's `sessions.lockedEntry`
 * right after its submit), the device links (show this device's code, then scan another's, as the web's
 * `pairing.signInEntry` before `pairing.joinEntry`), the kit (open, then scan), *Forgot your password?* (the web's
 * `recovery.forgot` after the device links) and *Open a link* last. Every platform draws them whole and in this order, under the fields; how each looks — a filled button, a
 * text button, a row — is the platform's.
 */
object SignInPlan {
    /**
     * The entries of [gate]; [camera]: the device may have a camera (the platform's fact — a scan that found none says so,
     * and the plan is asked again without). Cancel only while another account is being added.
     */
    fun entries(gate: SessionModel.Gate.SignIn, camera: Boolean): List<SignInEntry> = entries(gate.adding, camera)

    /**
     * A TV's sign-in (ADR 0159, ADR 0160): the QR code it shows is the only way in — a TV never sees the password or the
     * Secret Key, which is why its PIN level exists — and Cancel back to the picker while another account is added.
     */
    fun tvEntries(adding: Boolean): List<SignInEntry> = listOfNotNull(
        SignInEntry.LINK_SHOW,
        SignInEntry.CANCEL.takeIf { adding },
    )

    /** [entries] by what decides them: [adding] another account, a [camera] possible. */
    fun entries(adding: Boolean, camera: Boolean): List<SignInEntry> = listOfNotNull(
        SignInEntry.SUBMIT,
        SignInEntry.CANCEL.takeIf { adding },
        SignInEntry.LOCKED,
        SignInEntry.LINK_SHOW,
        SignInEntry.LINK_SCAN.takeIf { camera },
        SignInEntry.KIT_OPEN,
        SignInEntry.KIT_SCAN.takeIf { camera },
        SignInEntry.FORGOT,
        SignInEntry.OPEN_LINK,
    )
}

/**
 * An entry of the locked sign-in ([SessionModel.Gate.Locked], uc.account.sessions E3): signed in, deliberately not
 * unlocked. Their words: [SIGN_OUT_EVERYWHERE] `locked_action` (asked first: `locked_confirm_title`, `locked_confirm`),
 * [BACK] `locked_back` with `locked_back_hint` under it.
 */
@Plan
@Serializable
enum class LockedEntry {
    /** Every device signed out, this one included (`SessionModel.signOutEverywhere`), in the platform's destructive style. */
    @SerialName("sign-out-everywhere") SIGN_OUT_EVERYWHERE,
    /** *I have my Secret Key after all*: back to the sign-in, which then keeps nothing on this device (`SessionModel.lockedBack`). */
    @SerialName("back") BACK,
}

/** The locked sign-in's entries, in the web's order (`LockedSignOut.vue`): the one action, then the way back. */
object LockedPlan {
    fun entries(): List<LockedEntry> = listOf(LockedEntry.SIGN_OUT_EVERYWHERE, LockedEntry.BACK)
}

/**
 * An entry of *Reset your password* ([SessionModel.Gate.Recover], uc.account.recover-access steps 1–2). Their words: [SEND]
 * `recovery_send`, [BACK] `back`.
 */
@Plan
@Serializable
enum class RecoverEntry {
    /** The link asked for (`SessionModel.requestRecovery`); gone once it was sent. */
    @SerialName("send") SEND,
    /** Back to the sign-in (`SessionModel.backToSignIn`). */
    @SerialName("back") BACK,
}

/** *Reset your password*'s entries: Send while no link was asked for ([sent] false), and Back to the sign-in. */
object RecoverPlan {
    fun entries(sent: Boolean): List<RecoverEntry> = listOfNotNull(RecoverEntry.SEND.takeUnless { sent }, RecoverEntry.BACK)
}

/**
 * An entry of the unlock below its account (ADR 0154 decision 2): the ways a [SessionModel.Gate.Unlock] offers. Their
 * words: [SUBMIT] `unlock_submit` (`unlock_retry` for a remembered account at the login level, whose unlock shows only
 * after a resume that failed), [OTHER_ACCOUNT] `unlock_other`, [OPEN_LINK] `link_open`, [FORGET] `manage_forget_device`.
 */
@Plan
@Serializable
enum class UnlockEntry {
    /** Unlocks the account shown (`SessionModel.unlock`): its password, or the person's authentication again. */
    @SerialName("submit") SUBMIT,
    /** The full sign-in, for another account (`SessionModel.otherAccount`). */
    @SerialName("other-account") OTHER_ACCOUNT,
    /** *Open a link* (ADR 0150 decision 4): a received link opened as its guest, no session needed. */
    @SerialName("open-link") OPEN_LINK,
    /**
     * *Stop trusting this device* for the account shown (`SessionModel.forgetUnlocked`, inbox #1052): forgotten here, always
     * asked first, the writes made offline that wait named in that one question (`QueuedShown.forgetAsk`); the start then falls through to the next account.
     */
    @SerialName("forget") FORGET,
}

/**
 * The unlock's entries, in their order: Unlock, *Use another account*, *Open a link* — the ways in, as the sign-in lists
 * its own — and *Stop trusting this device* last, the one that forgets (owner 2026-11-22 on inbox #1052: on every app).
 * Every platform draws them whole and in this order under the account; how each looks is the platform's, the forget in
 * its destructive style.
 */
object UnlockPlan {
    /**
     * The entries of the unlock; [inExtension]: the share extension's (ADR 0138), which unlocks but leaves this device's
     * trust to the app — no forget there.
     */
    fun entries(inExtension: Boolean): List<UnlockEntry> = listOfNotNull(
        UnlockEntry.SUBMIT,
        UnlockEntry.OTHER_ACCOUNT,
        UnlockEntry.OPEN_LINK,
        UnlockEntry.FORGET.takeUnless { inExtension },
    )
}
