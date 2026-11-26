package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The members' pictures a UI shows, by user id (ADR 0161 decision 2), and when they are read — the core's rule, not an
 * app's: [MemberAvatars] is synced when the tenant opens ([load], over the people read then) and again whenever the
 * people are read anew with another picture to show ([peopleRead]: a member, or a member's revision, changed — the
 * people are read anew by a person's refresh, `ShellModel.refresh(people = true)`); a set read anew unchanged asks nothing. The letter shows for everyone not in [state] — meanwhile, and wherever a picture
 * does not come.
 *
 * One per tenant session ([TenantSession.pictures]). A UI observes [state] (or [watch]) and starts [load] in the
 * background as the tenant opens; the letters show until it ends.
 */
class AvatarsModel internal constructor(
    private val avatars: Avatars,
    private val people: () -> Collection<Person>,
) {
    /** What the model syncs and shows: [MemberAvatars] (a test plays it). */
    internal interface Avatars {
        suspend fun sync(people: Collection<Person>): Boolean
        fun pictures(): Map<String, ByteArray>
    }

    private val _state = MutableStateFlow<Map<String, ByteArray>>(emptyMap())

    /** Every picture this device opened, by user id: the letter for everyone else. */
    val state: StateFlow<Map<String, ByteArray>> = _state.asStateFlow()

    /**
     * The pictures held and their [round]: a number that moves with every sync that changed what is held — a picture
     * replaced under the same user id among it — for a host that keeps pictures by id and asks again only when told (the
     * bridge's `ShellShown.picturesRound`).
     */
    class Held(val pictures: Map<String, ByteArray>, val round: Int)

    private val _held = MutableStateFlow(Held(emptyMap(), 0))

    /** [state] with its round ([Held]). */
    val held: StateFlow<Held> = _held.asStateFlow()

    /** A watch of [state] for a platform without flows: stopped with [stop]. */
    class Watch internal constructor(private val job: kotlinx.coroutines.Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the pictures now and with each change after (on a background thread). */
    fun watch(listener: (Map<String, ByteArray>) -> Unit): Watch =
        Watch(CoroutineScope(Dispatchers.Default).launch { state.collect(listener) })

    private val syncing = Mutex()

    /** Each member's picture revision as last synced; null before the first sync. */
    private var synced: Map<String, Int>? = null

    /** The tenant opened: its pictures synced from the people read then. */
    suspend fun load() = peopleRead(people())

    /**
     * The people were read anew: the pictures are synced again when a member or a member's revision changed since the
     * last sync, and not when the set is the same. A failed fetch is not retried for an unchanged set; the next tenant
     * open asks again.
     */
    suspend fun peopleRead(people: Collection<Person>) = syncing.withLock {
        val set = people.associate { it.userId to it.avatarRevision }
        if (set == synced) return@withLock
        synced = set
        runCatching { avatars.sync(people) }
        val pictures = avatars.pictures()
        // the pictures first: a host told of the new round asks for them at once
        _state.value = pictures
        _held.value = Held(pictures, _held.value.round + 1)
    }

    internal companion object {
        fun of(tenant: TenantSession): AvatarsModel = AvatarsModel(
            object : Avatars {
                override suspend fun sync(people: Collection<Person>) = tenant.avatars.sync(people)
                override fun pictures() = tenant.avatars.pictures()
            },
        ) { tenant.people.values }
    }
}
