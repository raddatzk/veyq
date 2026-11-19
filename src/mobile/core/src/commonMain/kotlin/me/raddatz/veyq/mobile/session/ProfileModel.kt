package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.raddatz.veyq.mobile.api.apis.AccountProfileApi
import me.raddatz.veyq.mobile.api.models.MemberProfile
import me.raddatz.veyq.mobile.api.models.ProfileUpdateRequest
import me.raddatz.veyq.mobile.api.models.SetAvatarRequest
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.Pad
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.crypto.utf8
import me.raddatz.veyq.mobile.media.WebpEncoder
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.MsgKey

/**
 * One's own per-tenant profile (uc.spaces.edit-profile, uc.spaces.set-profile-picture; ADR 0084 decision 6, ADR 0148
 * decision 2): the web's `useNameAndPicture` with `ProfilePanel`'s save, one model behind Settings' *Your profile* and
 * *Edit profile* on one's own person page.
 *
 * It reads `getMyProfile` for the current tenant and opens the real name with the tenant key (a value-key seal,
 * `member-name`, ADR 0115); it saves as the web does: `updateMyProfile` with the username, the name sealed as the fields
 * hold it (an empty name included — that is how a member removes it) with the tenant epoch its value key is wrapped under,
 * and the account-global group-add consent; then the picture, a request of its own (`setMyAvatar`, `clearMyAvatar`).
 * A `409 stale_epoch` (a rotation replaced the tenant key since the model opened) opens the tenant key anew and seals once
 * more; `409 username_taken` is a refusal of that field. Without a tenant grant the name and the picture are disabled and
 * only the username saves. After a save the shell's people are read again, so every name and picture follows.
 *
 * The picture: the host renders the crop (the existing cropper with a square frame) to [EDGE] × [EDGE] RGBA; the core
 * encodes it to WebP through [WebpEncoder], stepping the quality down until it fits the pad's budget, pads it to
 * [PADDED_LEN] and seals it (`member-avatar`). The model's copy of the tenant key is zeroed at [close].
 */
class ProfileModel(
    private val door: Door,
    private val suite: V1,
    private val encoder: WebpEncoder?,
) {
    /** What the model reads and writes; the tenant session's in the app, a played one in the tests. */
    interface Door {
        val tenantId: String
        val userId: String
        suspend fun profile(): MemberProfile
        /** A COPY of the tenant key and its epoch (the model zeroes it); [fresh]: opened anew after a `stale_epoch`. Null
         *  without a tenant grant. */
        suspend fun tenantKey(fresh: Boolean): Pair<ByteArray, Long>?
        suspend fun update(body: ProfileUpdateRequest): Reply
        suspend fun setAvatar(body: SetAvatarRequest): Reply
        suspend fun clearAvatar(): Reply
        /** The tenant's people read anew (`readPeople`) and their pictures synced. */
        suspend fun peopleChanged()
        /** One's own picture as the pictures hold it (the encoded image), or null: the letter. */
        fun picture(): ByteArray?
    }

    /** A write's answer: its status and the error's `code`. */
    data class Reply(val status: Int, val code: String? = null) {
        val ok: Boolean get() = status in 200..299
        val staleEpoch: Boolean get() = status == 409 && code == STALE_EPOCH
    }

    enum class PictureDraft { KEEP, SET, CLEAR }

    @Serializable
    data class State(
        /** The profile read (or its read refused, [refusal]). */
        val loaded: Boolean = false,
        val username: String = "",
        val firstName: String = "",
        val lastName: String = "",
        val requireGroupAddConsent: Boolean = false,
        /** This account holds the tenant key here: the name and the picture can be edited; else [Msg.PROFILE_NO_NAME_YET]. */
        val hasKey: Boolean = false,
        /** The server holds a picture (before the draft). */
        val serverHasPicture: Boolean = false,
        val picture: PictureDraft = PictureDraft.KEEP,
        val working: Boolean = false,
        @Serializable(with = MsgKey::class) val refusal: Msg? = null,
        /** The username's own refusal (`username_taken`), shown at the field. */
        @Serializable(with = MsgKey::class) val usernameRefusal: Msg? = null,
        /** The picture's refusal: it did not prepare ([Msg.PROFILE_PICTURE_FAILED]) or did not save after the profile did. */
        @Serializable(with = MsgKey::class) val pictureRefusal: Msg? = null,
        /** How many saves came through; a host says "Profile updated." when it moves. */
        val saves: Int = 0,
    ) {
        /** Whether a picture shows: the draft's, or the server's while the draft keeps it. */
        val hasPicture: Boolean get() = when (picture) {
            PictureDraft.SET -> true
            PictureDraft.CLEAR -> false
            PictureDraft.KEEP -> serverHasPicture
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    class Watch internal constructor(private val job: kotlinx.coroutines.Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch = Watch(CoroutineScope(Dispatchers.Default).launch { state.collect(listener) })

    private val acting = Mutex()
    private var key: ByteArray? = null
    private var epoch: Long = 0
    private var draft: ByteArray? = null
    private var closed = false

    /** Reads the profile and opens the name. */
    suspend fun load() = act {
        val p = attempt { door.profile() }.getOrElse {
            _state.value = current.copy(loaded = true, refusal = Msg.PROFILE_LOAD_FAILED)
            return@act
        }
        hold(attempt { door.tenantKey(fresh = false) }.getOrNull())
        val name = key?.let { k -> openName(k, p) }
        _state.value = current.copy(
            loaded = true,
            username = p.username,
            firstName = name?.first ?: "",
            lastName = name?.second ?: "",
            requireGroupAddConsent = p.requireGroupAddConsent,
            hasKey = key != null,
            serverHasPicture = p.avatarRevision > 0,
            refusal = null,
        )
    }

    fun setUsername(value: String) { _state.value = current.copy(username = value, usernameRefusal = null) }
    fun setFirstName(value: String) { if (current.hasKey) _state.value = current.copy(firstName = value) }
    fun setLastName(value: String) { if (current.hasKey) _state.value = current.copy(lastName = value) }
    fun setRequireGroupAddConsent(value: Boolean) { _state.value = current.copy(requireGroupAddConsent = value) }

    /** The picture to draw: the draft's, none for a removal, else the one the pictures hold. */
    fun picture(): ByteArray? = when (current.picture) {
        PictureDraft.SET -> draft
        PictureDraft.CLEAR -> null
        PictureDraft.KEEP -> door.picture()
    }

    /**
     * A cropped picture, as the host rendered it: [rgba] of [EDGE] × [EDGE] pixels, 4 bytes each. Encoded to WebP here,
     * the quality stepped down until it fits the pad ([BUDGET]); a picture that does not encode is refused
     * ([Msg.PROFILE_PICTURE_FAILED]) and the draft kept as it was. Saved with the profile.
     */
    suspend fun choosePicture(rgba: ByteArray) = act {
        if (!current.hasKey) return@act
        val bytes = attempt { encode(rgba) }.getOrNull()
        if (bytes == null) {
            _state.value = current.copy(pictureRefusal = Msg.PROFILE_PICTURE_FAILED)
            return@act
        }
        draft = bytes
        _state.value = current.copy(picture = PictureDraft.SET, pictureRefusal = null)
    }

    /** Removes the picture when the profile is saved. */
    fun removePicture() {
        draft = null
        _state.value = current.copy(picture = PictureDraft.CLEAR, pictureRefusal = null)
    }

    /** Back to the picture the server holds. */
    fun discardPicture() {
        draft = null
        _state.value = current.copy(picture = PictureDraft.KEEP, pictureRefusal = null)
    }

    /**
     * `ProfilePanel`'s save: the profile first (the username, the name, the consent), then the picture as its own request
     * — a failure there leaves the saved profile saved and says so. Nothing is sent for a blank username.
     */
    suspend fun save() = act {
        val username = current.username.trim().lowercase()
        if (username.isEmpty()) return@act
        _state.value = current.copy(refusal = null, usernameRefusal = null, pictureRefusal = null)
        val profile = attempt {
            staleOnce { k, e ->
                val name = k?.let { sealName(it, e) }
                door.update(
                    ProfileUpdateRequest(
                        username = username,
                        sealedName = name?.sealed?.let(::b64),
                        wrappedNameValueKey = name?.wrappedValueKey?.let(::b64),
                        epoch = if (name != null) e.toInt() else null,
                        requireGroupAddConsent = current.requireGroupAddConsent,
                    ),
                )
            }
        }.getOrNull()
        if (profile == null || !profile.ok) {
            _state.value = if (profile?.status == 409 && profile.code == USERNAME_TAKEN) current.copy(usernameRefusal = Msg.PROFILE_USERNAME_TAKEN)
            else current.copy(refusal = Msg.PROFILE_SAVE_FAILED)
            return@act
        }
        _state.value = current.copy(username = username)
        val picture = when (current.picture) {
            PictureDraft.KEEP -> Reply(204)
            PictureDraft.CLEAR -> attempt { door.clearAvatar() }.getOrDefault(Reply(0))
            PictureDraft.SET -> {
                val bytes = draft
                if (bytes == null || key == null) Reply(0)
                else attempt { staleOnce { k, e -> door.setAvatar(sealAvatar(k!!, e, bytes)) } }.getOrDefault(Reply(0))
            }
        }
        if (!picture.ok) {
            _state.value = current.copy(pictureRefusal = Msg.PROFILE_PICTURE_SAVE_FAILED, saves = current.saves + 1)
            attempt { door.peopleChanged() }
            return@act
        }
        val set = current.picture == PictureDraft.SET
        if (current.picture != PictureDraft.KEEP) {
            draft = null
            _state.value = current.copy(picture = PictureDraft.KEEP, serverHasPicture = set)
        }
        attempt { door.peopleChanged() }
        _state.value = current.copy(saves = current.saves + 1)
    }

    /** Zeroes the tenant key copy and the draft; the model answers nothing after it. */
    fun close() {
        closed = true
        hold(null)
        draft = null
    }

    // ---- the seals ----

    private fun openName(k: ByteArray, p: MemberProfile): Pair<String, String>? {
        val sealed = p.sealedName ?: return null
        val wrapped = p.wrappedNameValueKey ?: return null
        val plain = JoinRecords(suite).openValueField(door.tenantId, epoch, k, People.nameContext(door.tenantId, door.userId), unb64(wrapped), unb64(sealed))
            ?: return null
        val o = runCatching { Json.parseToJsonElement(plain.decodeToString()) as? JsonObject }.getOrNull() ?: return null
        val first = (o["firstName"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val last = (o["lastName"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return first to last
    }

    /** The name as the fields hold it, trimmed — `{"firstName","lastName"}`, the web's `sealName` plaintext. */
    private fun sealName(k: ByteArray, e: Long): JoinRecords.SealedValue {
        val json = buildJsonObject {
            put("firstName", current.firstName.trim())
            put("lastName", current.lastName.trim())
        }.toString()
        return JoinRecords(suite).sealValueField(door.tenantId, e, k, People.nameContext(door.tenantId, door.userId), utf8(json))
    }

    private fun sealAvatar(k: ByteArray, e: Long, bytes: ByteArray): SetAvatarRequest {
        val sealed = JoinRecords(suite).sealValueField(
            door.tenantId, e, k, MemberAvatars.context(door.tenantId, door.userId), Pad.padTo(bytes, PADDED_LEN),
        )
        return SetAvatarRequest(e.toInt(), b64(sealed.sealed), b64(sealed.wrappedValueKey))
    }

    /** The web's `renderSpaceIcon` ladder: the first quality whose encode fits [BUDGET]; null when none does. */
    private suspend fun encode(rgba: ByteArray): ByteArray? {
        require(rgba.size == EDGE * EDGE * 4) { "a picture is $EDGE × $EDGE RGBA" }
        val e = encoder ?: return null
        for (q in QUALITY_STEPS) {
            val bytes = e.encode(rgba, EDGE, EDGE, q) ?: continue
            if (bytes.size <= BUDGET) return bytes
        }
        return null
    }

    // ---- the key ----

    private fun hold(opened: Pair<ByteArray, Long>?) {
        key?.fill(0)
        key = opened?.first
        epoch = opened?.second ?: 0
        if (closed) {
            key?.fill(0)
            key = null
        }
    }

    /** [write] under the held key; a `409 stale_epoch` opens the tenant key anew and runs it once more. */
    private suspend fun staleOnce(write: suspend (ByteArray?, Long) -> Reply): Reply {
        val first = write(key, epoch)
        if (!first.staleEpoch) return first
        val fresh = door.tenantKey(fresh = true) ?: return first
        hold(fresh)
        return write(key, epoch)
    }

    private suspend fun <R> act(block: suspend () -> R): R? = acting.withLock {
        if (closed) return@withLock null
        _state.value = current.copy(working = true)
        try {
            block()
        } finally {
            _state.value = current.copy(working = false)
        }
    }

    private inline fun <R> attempt(block: () -> R): Result<R> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        /** The picture's pixel edge (`AVATAR_EDGE`). */
        const val EDGE = 256
        /** The constant plaintext every picture is padded to (`AVATAR_PADDED_LEN`, the tenant icon's 16 KiB). */
        const val PADDED_LEN = MemberAvatars.PADDED_LEN
        /** The largest encoded picture the pad carries (`AVATAR_ENCODED_BUDGET`). */
        const val BUDGET = PADDED_LEN - Pad.HEADER
        /** `spaceIconImage.ts`'s ladder: stepped down only when an encode overflows [BUDGET]. */
        val QUALITY_STEPS = listOf(0.9, 0.82, 0.72, 0.62, 0.5, 0.4)
        internal const val STALE_EPOCH = "stale_epoch"
        internal const val USERNAME_TAKEN = "username_taken"
    }
}

/** [ProfileModel]'s door over a tenant session (ADR 0148 decision 2). */
internal class TenantProfileDoor(private val tenant: TenantSession) : ProfileModel.Door {
    private val account = tenant.account
    override val tenantId: String get() = tenant.tenant.tenantId
    override val userId: String get() = account.userId
    private fun api() = AccountProfileApi(tenant.base, account.http)

    override suspend fun profile(): MemberProfile = account.call { api().getMyProfile() }

    override suspend fun tenantKey(fresh: Boolean): Pair<ByteArray, Long>? =
        if (fresh) account.reopenScope(tenant.tenant, tenantId)
        else tenant.scopeKey(tenantId)?.let { (k, e) -> k.copyOf() to e.toLong() }

    override suspend fun update(body: ProfileUpdateRequest): ProfileModel.Reply {
        val r = account.respond { api().updateMyProfile(body) }
        return ProfileModel.Reply(r.status, if (r.success) null else account.errorCode(r))
    }

    override suspend fun setAvatar(body: SetAvatarRequest): ProfileModel.Reply {
        val r = account.respond { api().setMyAvatar(body) }
        return ProfileModel.Reply(r.status, if (r.success) null else account.errorCode(r))
    }

    override suspend fun clearAvatar(): ProfileModel.Reply {
        val r = account.respond { api().clearMyAvatar() }
        return ProfileModel.Reply(r.status, if (r.success) null else account.errorCode(r))
    }

    override suspend fun peopleChanged() {
        val people = tenant.readPeople() ?: return
        tenant.avatars.sync(people.values)
    }

    override fun picture(): ByteArray? = tenant.avatars.pictureOf(account.userId)
}
