package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.api.models.DeviceRegister
import me.raddatz.veyq.mobile.api.models.DeviceRegisterUpdate
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.crypto.utf8
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * *Start a list over* (ADR 0028 decision 7, the web's `trustReset.ts`, `TrustResetPanel`, `TrustResetDialog`): the panel's
 * rows from what this device refuses, the question's acknowledgement unticked at every open and required before the
 * confirm, the outcome said and the rows read anew; and the device register's start-over over a played server — the empty
 * register at the served revision + 1, the mark lowered to it, an opening register never written.
 */
class TrustResetTest {
    private val p = testPrimitives
    private val suite = V1(p)

    private class Door(var refused: MutableMap<TrustDocument, TrustStateRefused.Code>) : TrustReset.Door {
        val started = mutableListOf<TrustDocument>()
        var fails = false
        var lastChecked: Boolean? = null
        override suspend fun refusal(document: TrustDocument) = refused[document]
        override suspend fun startOver(document: TrustDocument): StartOver {
            if (fails) error("not reached")
            started += document
            return if (refused.remove(document) != null) StartOver.RESET else StartOver.ALREADY_READABLE
        }
        override fun checked(refused: Boolean) { lastChecked = refused }
    }

    @Test
    fun theRowsAreWhatThisDeviceRefusesAndTheConfirmWaitsForTheAcknowledgement() = runTest {
        val door = Door(mutableMapOf(TrustDocument.REGISTER to TrustStateRefused.Code.UNREADABLE, TrustDocument.PINS to TrustStateRefused.Code.ROLLED_BACK))
        val m = TrustReset(door)
        m.load()
        assertEquals(
            listOf(
                TrustReset.RefusedList(TrustDocument.PINS, Msg.TRUST_RESET_DOCUMENT_PINS, Msg.TRUST_RESET_CODE_ROLLED_BACK),
                TrustReset.RefusedList(TrustDocument.REGISTER, Msg.TRUST_RESET_DOCUMENT_REGISTER, Msg.TRUST_RESET_CODE_UNREADABLE),
            ),
            m.current.rows,
        )
        assertEquals(true, door.lastChecked)
        m.ask(TrustDocument.PINS)
        assertEquals(TrustReset.ResetQuestion(TrustDocument.PINS, Msg.TRUST_RESET_CONFIRM_TITLE_PINS, Msg.TRUST_RESET_COST_PINS), m.current.asking)
        m.confirm()
        assertTrue(door.started.isEmpty(), "started over without the acknowledgement")
        m.acknowledge(true)
        m.cancel()
        m.ask(TrustDocument.PINS)
        assertEquals(false, m.current.asking?.acknowledged, "the acknowledgement was kept across opens")
        m.acknowledge(true)
        m.confirm()
        assertEquals(listOf(TrustDocument.PINS), door.started)
        assertNull(m.current.asking)
        assertEquals(Msg.TRUST_RESET_DONE, m.current.said)
        assertEquals(listOf(TrustDocument.REGISTER), m.current.rows.map { it.document }, "the rows were not read anew")
        // a list that opened meanwhile: nothing written, said so
        door.refused.clear()
        m.ask(TrustDocument.REGISTER)
        m.acknowledge(true)
        m.confirm()
        assertEquals(Msg.TRUST_RESET_ALREADY_READABLE, m.current.said)
        assertTrue(m.current.none)
        assertEquals(false, door.lastChecked)
    }

    @Test
    fun aFailedStartOverIsSaidInTheQuestionAndAFailedCheckOnThePage() = runTest {
        val door = Door(mutableMapOf(TrustDocument.PINS to TrustStateRefused.Code.ROLLED_BACK))
        val m = TrustReset(door)
        m.load()
        m.ask(TrustDocument.PINS)
        m.acknowledge(true)
        door.fails = true
        m.confirm()
        assertEquals(Msg.TRUST_RESET_FAILED, m.current.asking?.failed)
        assertEquals(1, m.current.rows.size)
        m.ask(TrustDocument.REGISTER) // no such row: no question
        assertEquals(TrustDocument.PINS, m.current.asking?.document)
        val broken = TrustReset(object : TrustReset.Door {
            override suspend fun refusal(document: TrustDocument): TrustStateRefused.Code? = error("offline")
            override suspend fun startOver(document: TrustDocument) = StartOver.RESET
        })
        broken.load()
        assertEquals(Msg.TRUST_RESET_LOAD_FAILED, broken.current.loadFailed)
        assertTrue(!broken.current.none)
    }

    /** The register as a server serves it: a revision-guarded blob. */
    private class Register : RegisterApi {
        var revision = 0L
        var blob: String? = null
        var writes = 0
        override suspend fun get() = DeviceRegister(revision, blob)
        override suspend fun put(update: DeviceRegisterUpdate): Boolean {
            if (update.revision != revision) return false
            revision++
            blob = update.blob
            writes++
            return true
        }
    }

    @Test
    fun aRefusedRegisterIsStartedOverEmptyAndItsMarkLowered() = runTest {
        val mk = p.randomBytes(32)
        val store = MemoryDeviceStore()
        val marks = TrustMarks(store, p, mk)
        val server = Register()
        val register = DeviceRegisterStore(suite, server, marks, mk)
        register.remember("d1", "Phone", "f1", null, 1)
        assertEquals(StartOver.ALREADY_READABLE, register.startOver(), "a register that opens was started over")
        assertEquals(1, server.writes)
        marks.raiseRegister(7)
        assertEquals(TrustStateRefused.Code.ROLLED_BACK, assertFailsWith<TrustStateRefused> { register.read() }.code)
        assertEquals(StartOver.RESET, register.startOver())
        assertEquals(2L, server.revision)
        assertEquals(2L, marks.register(), "the mark was not set (lowered) to the revision written")
        val plain = suite.open(Context("device-register", "revision" to 2L), mk, unb64(assertNotNull(server.blob))).decodeToString()
        assertEquals("{}", plain, "the register written is not the empty one")
        assertTrue(register.read().devices.isEmpty())
        // a blob that does not open: unreadable, started over too
        server.blob = b64(suite.seal(Context("device-register", "revision" to 2L), p.randomBytes(32), utf8("{}")))
        assertEquals(TrustStateRefused.Code.UNREADABLE, assertFailsWith<TrustStateRefused> { register.read() }.code)
        assertEquals(StartOver.RESET, register.startOver())
        assertEquals(3L, marks.register())
    }
}
