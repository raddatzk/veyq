package me.raddatz.veyq.mobile.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.crypto.EmergencyKit
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.SecretKeyProblem
import me.raddatz.veyq.mobile.crypto.SecretKeyText
import me.raddatz.veyq.mobile.qr.QrDecoder
import me.raddatz.veyq.mobile.qr.QrFrame
import me.raddatz.veyq.mobile.qr.QrScan
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.MsgKey

/**
 * The Secret Key control's logic, the sign-in's and every flow's that asks for the key (ADR 0151 decision 1, the web's
 * `SecretKeyField.vue`): a key typed or pasted ([typed] — a `veyqkit:` token first, then a bare key), or a kit's key held
 * here — read from a file ([read]), scanned ([scan]) or taken as read ([take]) — until it is cleared ([clear]), taken by
 * a call that succeeded ([spent]) or its owner closes. [key] is what an action uses: the typed text's key, else a COPY of
 * the held one, else 32 zero bytes (which every floor refuses as a wrong key); the caller zeroes it.
 *
 * Given the [account] it is for (a flow's, never the sign-in's), a kit of another address warns ([HeldKit.otherAccount],
 * decision 2) and a kit made at another server informs ([HeldKit.otherServer]); neither blocks nor clears the key — only
 * the wrap can judge a key. Nothing here leaves the core: [shown] carries the kit's address and server, never its key.
 */
class KeyIntake(private val primitives: Primitives, private val account: Account? = null) {
    /** The account a flow's intake is for: what a kit's address and server are compared with. */
    class Account(val email: String, val server: String)

    /**
     * What a screen shows of the held kit: the Secret Key row reads "from your Emergency Kit" with *Use another*. [server]
     * is the kit's (null where neither its token nor its first address field gave one) and [serverFrom] where it came
     * from; [otherServer] the different server the kit replaced (the sign-in's form) or is not (a flow's account) — the
     * notice that the kit was made at another address, information only; [otherAccount] the account's address where the
     * kit carries another — the warn-only `kit_email_mismatch`.
     */
    @Serializable
    data class HeldKit(
        val email: String,
        val server: String?,
        val serverFrom: KitImport.ServerFrom,
        val otherServer: String? = null,
        val otherAccount: String? = null,
    )

    /** The control's state: the kit held ([kit]), a file being read ([reading]), a file in which no kit was read ([problem]). */
    @Serializable
    data class IntakeState(
        val kit: HeldKit? = null,
        val reading: Boolean = false,
        @Serializable(with = MsgKey::class) val problem: Msg? = null,
    )

    private val _state = MutableStateFlow(IntakeState())
    val state: StateFlow<IntakeState> = _state.asStateFlow()
    val current: IntakeState get() = _state.value

    /** The held kit's Secret Key; null while none is held. */
    private var held: ByteArray? = null

    /** The 32 bytes of a typed or pasted Secret Key — a `veyqkit:` token's key first (the web's paste swap), then a bare
     *  key —, a fresh array the caller zeroes; null where the text reads as neither. */
    fun typed(text: String): ByteArray? = EmergencyKit.read(primitives, text)?.secretKey ?: SecretKeyText.read(primitives, text)

    /**
     * Why the field's [text] is no Secret Key as it is typed — the web's `SecretKeyField` typo line (ADR 0154 decision
     * 1: typed, as a UI tells the two apart): [SecretKeyProblem.TYPO] is said under the field at once ([Msg.SECRET_KEY_TYPO]),
     * [SecretKeyProblem.MALFORMED] says nothing while it is typed (the action refuses it as a key that does not match).
     * Null for a `veyqkit:` token that reads, or a key; a key read here is zeroed at once. An empty field is
     * `MALFORMED`, as the reader and `v1.json#secret_key_text.refused` have it — and says nothing either.
     */
    fun problem(text: String): SecretKeyProblem? {
        val kit = EmergencyKit.read(primitives, text)
        if (kit != null) {
            kit.wipe()
            return null
        }
        return SecretKeyText.problem(primitives, text)
    }

    /**
     * The key an action uses, a fresh array the caller zeroes: [text]'s where something is typed ([typed]; 32 zero bytes
     * where it reads as no key), else the held kit's copy, else 32 zero bytes. A typed key wins over a held one.
     */
    fun key(text: String): ByteArray =
        if (text.isNotBlank()) typed(text) ?: ByteArray(32) else held?.copyOf() ?: ByteArray(32)

    /** Whether a kit's key is held (the action's field may then be empty). */
    val holds: Boolean get() = held != null

    /** A COPY of the held key, or null (the sign-in's door takes it as bytes). */
    internal fun heldCopy(): ByteArray? = held?.copyOf()

    /**
     * A kit file the person picked, read ([KitImport.read]: [pictures] draws its pages, the core decides) and taken as
     * [take] takes it; [typedServer] is the sign-in form's server as typed now. [bytes] are zeroed once read. Null — the
     * state saying no kit was read, a kit held before kept — where none was found.
     */
    suspend fun read(bytes: ByteArray, pictures: KitPictures, typedServer: String? = null): HeldKit? {
        _state.value = current.copy(reading = true, problem = null)
        val found = try {
            KitImport.read(primitives, bytes, pictures)
        } catch (e: CancellationException) {
            _state.value = current.copy(reading = false)
            throw e
        } catch (e: Exception) {
            null
        } finally {
            bytes.fill(0)
        }
        if (found == null) {
            _state.value = current.copy(reading = false, problem = Msg.SIGNIN_KIT_UNREADABLE)
            return null
        }
        return take(found, typedServer)
    }

    /**
     * A kit read (a file's or a scanned code's): its key held here in place of any held before — this intake owns
     * [found]'s kit from here and zeroes its key when it ends. [typedServer] is the server the kit is compared with where
     * no [account] is (the sign-in's form); a flow compares with its account's.
     */
    fun take(found: KitImport.Found, typedServer: String? = null): HeldKit {
        val kit = found.kit
        val before = account?.server ?: typedServer?.takeIf { it.isNotBlank() }
        val otherServer = before?.takeIf { kit.server != null && SessionModel.origin(it).lowercase() != kit.server }?.let { SessionModel.origin(it) }
        val otherAccount = account?.email?.takeIf { it.trim().lowercase() != kit.email.trim().lowercase() }
        drop()
        held = kit.secretKey
        val shown = HeldKit(kit.email, kit.server, found.serverFrom, otherServer, otherAccount)
        _state.value = IntakeState(kit = shown)
        return shown
    }

    /**
     * "Scan Emergency Kit": a QR scan whose `accepts` is the kit's parser ([EmergencyKit.read]) — a code that is not a v3
     * kit is skipped and the scan goes on — and whose kit is taken here ([take]). The code's text and the key never leave
     * the core: the scan's `Found` says only where the server came from. A QR carries no form, so its server is the
     * token's or none. [accept] decides whether the kit is still wanted when it is read (the sign-in's: still shown).
     */
    fun scan(
        typedServer: String? = null,
        decode: suspend (QrFrame) -> String? = { QrDecoder.decode(it) },
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        accept: (KitImport.Found) -> Boolean = { take(it, typedServer); true },
    ): QrScan<KitImport.ServerFrom> = QrScan(
        accepts = { text ->
            val kit = EmergencyKit.read(primitives, text)
            if (kit == null) null else {
                val from = if (kit.server != null) KitImport.ServerFrom.TOKEN else KitImport.ServerFrom.NONE
                if (accept(KitImport.Found(kit, from))) from else null
            }
        },
        decode = decode,
        scope = scope,
    )

    /** *Use another*: the held key ends, zeroed. */
    fun clear() {
        drop()
        _state.value = IntakeState()
    }

    /** A call that took the held key succeeded: the key is spent, zeroed; what the field shows goes with it. */
    fun spent() = clear()

    private fun drop() {
        held?.fill(0)
        held = null
    }
}
