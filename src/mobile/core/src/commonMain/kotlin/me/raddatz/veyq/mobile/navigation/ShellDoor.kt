package me.raddatz.veyq.mobile.navigation


/**
 * What an application's model reads and asks of the shell it was opened from ([ShellModel], kept to these few members so
 * a model holds no shell's state): its lists read anew, where the person is, what it lists, an object opened there. Expenses' Pot copies and creates through it (`PotModel.duplicate`, `PotModel.createSheet`).
 */
interface ShellDoor {
    /** The shell's lists read anew. */
    suspend fun reread()

    /** Where the person is; null before the shell is read. */
    fun place(): Shell?

    /** The spaces listed; empty before the shell is read. */
    fun spaces(): List<SpaceRef>

    /** The objects listed; empty before the shell is read. */
    fun objects(): List<ObjectRef>

    /** Object [ref] opened in the shell. */
    fun openObject(ref: ObjectRef)
}
