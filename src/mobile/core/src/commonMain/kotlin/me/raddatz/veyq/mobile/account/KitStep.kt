package me.raddatz.veyq.mobile.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.MsgKey

/**
 * The step that saves an Emergency Kit (ADR 0148 decision 5), the web's `EmergencyKitSave.vue`: *Save your Emergency Kit*
 * → the platform's save dialog → the core writes the kit straight into the file chosen ([save]) → "I saved it"
 * ([confirm]) enabled only once a file was written → only then the flow's next step ([ready]). The Secret Key is never on
 * screen: the step holds its 32 bytes and hands nothing of them out — no state field, no word, no bytes over a bridge;
 * [close] zeroes them. A save that fails removed what it wrote ([KitWriter.write]) and leaves the step not saved.
 */
class KitStep internal constructor(
    private val primitives: Primitives,
    /** The address the kit names. */
    val email: String,
    /** The server's origin, the kit's first address field and its token's `srv`. */
    private val server: String?,
    secretKey: ByteArray,
) {
    private val key: ByteArray = secretKey.copyOf().also { secretKey.fill(0) }
    private var closed = false

    @Serializable
    data class State(
        /** The name the save dialog proposes (`veyq-emergency-kit-<email>.pdf`). */
        val fileName: String,
        /** A file was written where the person chose. */
        val saved: Boolean = false,
        /** "I saved it" is checked (only once [saved]). */
        val confirmed: Boolean = false,
        @Serializable(with = MsgKey::class) val refusal: Msg? = null,
    ) {
        /** The flow may go on past the kit. */
        val ready: Boolean get() = saved && confirmed
    }

    private val _state = MutableStateFlow(State(KitWriter.fileName(email)))
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    /**
     * Writes the kit into [path], the file the platform's save dialog handed over, in [language] (the person's), dated
     * [createdEpochDay] (their local date). True once written. A failed write leaves no file and the step not saved.
     */
    fun save(path: String, language: String, createdEpochDay: Long): Boolean = written {
        KitWriter.write(path, primitives, email, key, server, KitWords.of(language), createdEpochDay)
    }

    /** [save] for a platform whose dialog hands a stream, not a path (Android's `CreateDocument`): [write] gets the PDF's
     *  bytes, writes them into that stream and returns; the bytes are zeroed after. */
    fun saveInto(language: String, createdEpochDay: Long, write: (ByteArray) -> Unit): Boolean = written {
        val bytes = KitWriter.pdf(primitives, email, key, server, KitWords.of(language), createdEpochDay)
        try {
            write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun written(f: () -> Unit): Boolean {
        check(!closed) { "the kit step is closed" }
        return try {
            f()
            _state.value = current.copy(saved = true, refusal = null)
            true
        } catch (e: Exception) {
            _state.value = current.copy(refusal = Msg.KIT_SAVE_FAILED)
            false
        }
    }

    /** "I saved it": takes only once a file was written. */
    fun confirm(checked: Boolean) {
        _state.value = current.copy(confirmed = checked && current.saved)
    }

    /** The key, for the flow that installs it (the rotation): a copy the caller zeroes. */
    internal fun keyCopy(): ByteArray {
        check(!closed) { "the kit step is closed" }
        return key.copyOf()
    }

    fun close() {
        closed = true
        key.fill(0)
    }
}
