package me.raddatz.veyq.mobile.depot

import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.ShareAnswer

/**
 * What a folder and a space's top take when something is shared to veyq or dropped onto a level (ADR 0138 decision 2):
 * **any file**, with no type and no bound but the upload's own and the quota — the web's level picker has no `accept` —,
 * queued into the app's one upload queue ([ShareAnswer.How.QUEUE], Depot's `DepotUploads.upload`) where the member may
 * upload there: online, not in a guest's identity folder, Depot not switched off for the tenant (the web's
 * `features/depot/rights.ts`, `upload` of a level). A space's top asks as a folder, as the web treats a level without a
 * node and a folder alike. Depot's answer; core asks it through `AppKinds.share`, and *Add here* on a level is core's
 * `navigation/AddHere`.
 */
object FolderShare {
    val answer = ShareAnswer(ShareAnswer.How.QUEUE, { ShareAnswer.Intake { _, _, _ -> true } }) { place ->
        place.online && !place.inIdentityFolder && ApplicationId.DEPOT.id !in place.applicationsOff
    }
}
