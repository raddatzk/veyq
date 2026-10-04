package com.veyq.core.identity

import com.veyq.core.generated.model.AddWrapRequest
import com.veyq.core.generated.model.MasterKeyWrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins [WrapMethod] to the contract. A Kotlin test and not a script because the compiler already links the
 * generated enums: a method added to `MasterKeyWrap.method` (in `_common.openapi.yaml`) turns this red until
 * somebody classifies it — which, since [WrapMethod] has no defaults, means deciding whether it survives a
 * credential change.
 */
class WrapMethodTest {

    @Test
    fun everyContractMethodIsClassifiedAndNothingElseIs() {
        assertEquals(
            MasterKeyWrap.Method.entries.map { it.value }.toSet(),
            WrapMethod.entries.map { it.name }.toSet(),
            "the classification and the contract's MasterKeyWrap.method name the same methods",
        )
    }

    @Test
    fun theDeviceLocalMethodsAreExactlyTheOnesAddWrapAccepts() {
        assertEquals(
            AddWrapRequest.Method.entries.map { it.value }.toSet(),
            WrapMethod.DEVICE_LOCAL,
            "addWrap creates, and the untrust endpoint deletes, the same device-local set",
        )
    }
}
