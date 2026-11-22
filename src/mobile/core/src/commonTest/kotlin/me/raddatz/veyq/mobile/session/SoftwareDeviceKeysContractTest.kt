package me.raddatz.veyq.mobile.session

// The device keys' contract (`DeviceKeysContract`, the shared test support's) over the fake every flow test uses.

/** The contract over the fake every flow test uses. */
class SoftwareDeviceKeysContractTest : DeviceKeysContract() {
    override fun keys(): DeviceKeys = SoftwareDeviceKeys(testPrimitives)
}

/** The contract over the fake as a store with the login level alone (Linux's shape): the presence level is refused. */
class LoginOnlySoftwareDeviceKeysContractTest : DeviceKeysContract() {
    override fun keys(): DeviceKeys = SoftwareDeviceKeys(testPrimitives).apply { staySignedIn = setOf(StaySignedIn.LOGIN) }
}
