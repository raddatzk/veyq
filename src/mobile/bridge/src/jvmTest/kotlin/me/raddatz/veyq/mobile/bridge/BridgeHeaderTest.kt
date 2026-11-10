package me.raddatz.veyq.mobile.bridge

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * bridge.h, written by hand, against the header Kotlin/Native writes for the library it links (`libveyq_api.h`): the
 * same six functions, each with the same parameters and return, by their C ABI — K/N writes every pointer as `void*`
 * and its own integer typedefs, so both sides are read down to that (a pointer, a 32- or 64-bit integer; `size_t` is
 * 64 bits on both Linux targets). And the Rust the generator writes declares each of them.
 */
class BridgeHeaderTest {
    private val ours = File(System.getProperty("veyq.bridgeHeader")).readText()
    private val kn = File(System.getProperty("veyq.knHeader")).readText()

    @Test
    fun bridgeHIsTheLibrarysSurface() {
        val (typedefs, functions) = Bindings.parseHeader(ours)
        val pointers = typedefs.map { it.name }.toSet()
        val mine = functions.associate { it.name to abi(it.returns, pointers) + abi(it.params, pointers) }
        val theirs = Regex("""extern ([\w*]+) (veyq_\w+)\(([^)]*)\);""").findAll(kn).associate { m ->
            val params = m.groupValues[3].takeIf { it.isNotBlank() }?.split(',')?.map { p -> p.trim().substringBeforeLast(' ') }.orEmpty()
            m.groupValues[2] to abi(m.groupValues[1], emptySet()) + params.map { abi(it, emptySet()) }
        }
        assertEquals(setOf("veyq_init", "veyq_call", "veyq_watch", "veyq_unwatch", "veyq_reply", "veyq_shutdown"), mine.keys)
        assertEquals(mine, theirs)
    }

    @Test
    fun theGeneratedRustDeclaresEachFunction() {
        val rust = Bindings.rust(Schema(Roots.all), ours)
        for (f in Bindings.parseHeader(ours).second) assertTrue("pub fn ${f.name}(" in rust, f.name)
        assertTrue("pub fn veyq_init(config: *const std::ffi::c_char, host: VeyqHost, host_ctx: *mut std::ffi::c_void) -> i32;" in rust)
        assertTrue("pub type VeyqAnswer = unsafe extern \"C\" fn(ctx: *mut std::ffi::c_void, json: *const std::ffi::c_char, bytes: *const u8, len: usize);" in rust)
    }

    /** Rust needs a box where a type holds itself directly (a shell's back), and nowhere else (a list is already one). */
    @Test
    fun theGeneratedRustBoxesOnlyWhatHoldsItselfDirectly() {
        val rust = Bindings.rust(Schema(Roots.all), ours)
        assertEquals(listOf("    pub back: Option<Box<Shell>>,"), rust.lines().filter { "Box<" in it })
        assertTrue("pub args: Vec<NoticeArg>," in rust)
    }

    private fun abi(params: List<Pair<String, String>>, pointers: Set<String>) = params.map { abi(it.first, pointers) }

    private fun abi(c: String, pointers: Set<String>): String {
        val t = c.replace("const ", "").replace(" ", "")
        return when {
            t.endsWith("*") || t in pointers -> "ptr"
            t in setOf("int32_t", "libveyq_KInt") -> "i32"
            t in setOf("uint64_t", "size_t", "libveyq_KULong") -> "u64"
            t == "void" -> "void"
            else -> error("no ABI for `$c`")
        }
    }

    private operator fun String.plus(rest: List<String>) = listOf(this) + rest
}
