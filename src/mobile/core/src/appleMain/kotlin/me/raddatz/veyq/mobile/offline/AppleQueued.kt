package me.raddatz.veyq.mobile.offline

import me.raddatz.veyq.mobile.navigation.ShellModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.blob.FileSink
import me.raddatz.veyq.mobile.words.Notice

/**
 * The writes made offline as the Swift app observes them (ADR 0153 decision 5): each of
 * the shell's flows held as a state Swift reads by `value` (`AppleMemories.watch` takes a `StateFlow`), as the bridge's
 * `QueuedDoor` hands the desktops the same core functions — nothing decided here. [said] keeps the reconnect's notices
 * until the app said them ([saidSeen]); [saveCopy] writes a refused write's file into the path the save panel gave
 * (core's [FileSink], which leaves nothing there when it did not finish). [close] with the shell.
 */
class AppleQueued(private val shell: ShellModel) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The tray's own model: `ask`, `discard`, `opens`, `copyName`. */
    val tray: QueuedTray = shell.queuedTray()

    /** The tray's items ([QueuedTray.items]). */
    val items: StateFlow<List<QueuedItem>> = tray.items.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The banner under the offline one ([ShellModel.queuedBanner]); null while nothing waits. */
    val banner: StateFlow<Notice?> = shell.queuedBanner.stateIn(scope, SharingStarted.Eagerly, null)

    /** Beside the banners, the one-time "a clearer Emergency Kit exists" notice ([ShellModel.kitNotice], ADR 0157). */
    val kitNotice: StateFlow<Notice?> = shell.kitNotice.stateIn(scope, SharingStarted.Eagerly, null)

    /** The kit notice dismissed ([ShellModel.dismissKitNotice]). */
    fun dismissKitNotice() = shell.dismissKitNotice()

    /** The level shown's writes not sent yet ([ShellModel.queuedHere]). */
    val here: StateFlow<List<QueuedItem>> = shell.queuedHere.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _said = MutableStateFlow<List<Notice>>(emptyList())

    /** The reconnects' notices ([ShellModel.queuedSent]) not said yet. */
    val said: StateFlow<List<Notice>> = _said.asStateFlow()

    init {
        scope.launch { shell.queuedSent.collect { n -> if (n.isNotEmpty()) _said.update { it + n } } }
    }

    /** The notices [said] holds were said: not again. */
    fun saidSeen() {
        _said.value = emptyList()
    }

    /** File [index] of refused write [id] written at [path] ([QueuedTray.saveCopy]); false where nothing was written. */
    @Throws(Exception::class)
    suspend fun saveCopy(id: String, index: Int, path: String): Boolean = withContext(Dispatchers.Default) {
        runCatching { FileSink.write(path, kept = { it }) { sink -> tray.saveCopy(id, index) { sink(it) } } }.getOrDefault(false)
    }

    fun close() = scope.cancel()
}
