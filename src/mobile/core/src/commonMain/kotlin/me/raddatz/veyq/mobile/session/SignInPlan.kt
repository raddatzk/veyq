package me.raddatz.veyq.mobile.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.navigation.Plan

/**
 * An entry of the sign-in below its fields (ADR 0154 decision 2): the ways in a [SessionModel.Gate.SignIn] offers, each a
 * button or a row in the platform's look. Their words: [SUBMIT] `signin_submit`, [CANCEL] `signin_cancel`, [LINK_SHOW]
 * `signin_link_show`, [LINK_SCAN] `signin_link_scan`, [KIT_OPEN] `signin_kit_open`, [KIT_SCAN] `signin_kit_scan`,
 * [OPEN_LINK] `link_open`.
 */
@Plan
@Serializable
enum class SignInEntry {
    /** Signs in with the fields (`SessionModel.signIn`). */
    @SerialName("submit") SUBMIT,
    /** Back to the shell, while another account is being added ([SessionModel.Gate.SignIn.adding]). */
    @SerialName("cancel") CANCEL,
    /** This device shows a code another device scans (ADR 0059 §1): on every device. */
    @SerialName("link-show") LINK_SHOW,
    /** This device scans the code a signed-in device shows (ADR 0059 §1): where a camera can be. */
    @SerialName("link-scan") LINK_SCAN,
    /** The Emergency Kit read from a file (a PDF or a picture of its code). */
    @SerialName("kit-open") KIT_OPEN,
    /** The Emergency Kit's code scanned with the camera: where a camera can be. */
    @SerialName("kit-scan") KIT_SCAN,
    /** *Open a link* (ADR 0150 decision 4): a received link opened as its guest, no session needed. */
    @SerialName("open-link") OPEN_LINK,
}

/**
 * The sign-in's entries, in their order (owner 2026-11-20 on cr-apps-structure-from-core-plans' Q4: the web's
 * `LoginView` — Submit first, then the other ways in): Submit, Cancel, the device links (show this device's code, then
 * scan another's, as the web's `pairing.signInEntry` before `pairing.joinEntry`), the kit (open, then scan), and *Open a
 * link* last. Every platform draws them whole and in this order, under the fields; how each looks — a filled button, a
 * text button, a row — is the platform's.
 */
object SignInPlan {
    /**
     * The entries of [gate]; [camera]: the device may have a camera (the platform's fact — a scan that found none says so,
     * and the plan is asked again without). Cancel only while another account is being added.
     */
    fun entries(gate: SessionModel.Gate.SignIn, camera: Boolean): List<SignInEntry> = entries(gate.adding, camera)

    /** [entries] by what decides them: [adding] another account, a [camera] possible. */
    fun entries(adding: Boolean, camera: Boolean): List<SignInEntry> = listOfNotNull(
        SignInEntry.SUBMIT,
        SignInEntry.CANCEL.takeIf { adding },
        SignInEntry.LINK_SHOW,
        SignInEntry.LINK_SCAN.takeIf { camera },
        SignInEntry.KIT_OPEN,
        SignInEntry.KIT_SCAN.takeIf { camera },
        SignInEntry.OPEN_LINK,
    )
}
