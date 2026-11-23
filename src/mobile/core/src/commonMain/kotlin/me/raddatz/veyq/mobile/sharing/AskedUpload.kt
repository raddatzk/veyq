package me.raddatz.veyq.mobile.sharing

/**
 * What an upload that asks a link's guest its name first came to (every `uploadAsking`; #1091, ADR 0154 decision 1): the
 * [queued] files, or none — [declined] where the guest declined to name itself (or its name was not taken, which the
 * naming flow said itself): nothing was sent and nothing is said, as a declined move says nothing
 * (`NodeMove.Outcome.DECLINED`, #1063); else refused (no right, no queue, nothing to send), which a platform says as a
 * failed upload. A platform tells the two apart here, never by reading the guest's session or the link's role itself.
 */
data class AskedUpload(val queued: Int, val declined: Boolean = false) {
    companion object {
        /** None queued: the upload was refused (no right here, no queue, nothing given). */
        val REFUSED = AskedUpload(0)

        /** None queued: the guest declined to name itself at the write — nothing to say. */
        val DECLINED = AskedUpload(0, declined = true)

        /** Where [GuestNaming.request] gave no session for [access]: declined on a link that writes, refused on one that does not. */
        fun unnamed(access: GuestAccess): AskedUpload = if (access.writes) DECLINED else REFUSED
    }
}
