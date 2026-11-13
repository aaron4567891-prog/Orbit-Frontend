package au.orbit.frontend

/** Future emulator adapters declare capabilities rather than assuming Android can move apps. */
enum class ScreenTransferCapability { LIVE_TRANSFER, RELAUNCH_REQUIRED, UNSUPPORTED }

sealed interface ScreenTransferResult {
    data object Transferred : ScreenTransferResult
    data object RequiresRelaunch : ScreenTransferResult
    data class Unavailable(val reason: String) : ScreenTransferResult
}

interface EmulatorIntegration {
    val screenTransferCapability: ScreenTransferCapability
    /** Must preserve emulation state. Relaunch requires a separate explicit user action. */
    suspend fun transferRunningGame(targetDisplayId: Int): ScreenTransferResult
}

/** ACTION_VIEW grants no control over an external emulator's existing activity or state. */
class AndroidViewIntegration : EmulatorIntegration {
    override val screenTransferCapability = ScreenTransferCapability.UNSUPPORTED
    override suspend fun transferRunningGame(targetDisplayId: Int): ScreenTransferResult =
        ScreenTransferResult.Unavailable("This emulator needs a dedicated screen-transfer integration.")
}
