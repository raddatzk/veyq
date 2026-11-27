package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.account.KitRecord
import me.raddatz.veyq.mobile.api.apis.AccountKitRecordApi
import me.raddatz.veyq.mobile.api.models.KitRecordUpdate

/**
 * The account's kit record on its server (`GET`/`PUT /api/account/kit-record`, ADR 0157 decisions 3-4): read and opened
 * with the Master Key this account holds; a throw where the read failed (the status then says it could not be read).
 */
internal suspend fun VeyqAccount.readKitLayout(): Int {
    val blob = call { AccountKitRecordApi(server, http).getKitRecord() }.blob
    val mk = masterKeyCopy() ?: throw IllegalStateException("the account closed")
    return try {
        KitRecord.layoutOf(suite, mk, blob)
    } finally {
        mk.fill(0)
    }
}

/**
 * A record [sealed] by [KitRecord.seal] written (last writer wins), and the account's kit current from then on; whether
 * it was written.
 */
internal suspend fun VeyqAccount.writeKitRecord(sealed: String): Boolean {
    val r = respond { AccountKitRecordApi(server, http).putKitRecord(KitRecordUpdate(sealed)) }
    if (r.success) kitStatus.remember()
    return r.success
}
