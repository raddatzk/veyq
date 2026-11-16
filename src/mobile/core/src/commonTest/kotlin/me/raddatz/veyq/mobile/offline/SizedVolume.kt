package me.raddatz.veyq.mobile.offline

import me.raddatz.veyq.mobile.blob.DeviceFiles
import me.raddatz.veyq.mobile.blob.deviceFiles

/**
 * [inner]'s files on a volume the test sizes: [freeBytes] and [totalBytes] answer [free] and [total], never the host's.
 * The free-space floor (ADR 0140 decision 8) reads them, so a test that keeps anything must not depend on how full the
 * machine running it is — on a host volume under 5 % free every keep would be refused. The default is roomy (half of
 * 1 TiB free); a floor test sets [free] itself.
 */
internal class SizedVolume(
    private val inner: DeviceFiles = deviceFiles,
    val total: Long = 1L shl 40,
    var free: Long = total / 2,
) : DeviceFiles by inner {
    override fun freeBytes(path: String): Long? = free

    override fun totalBytes(path: String): Long? = total
}
