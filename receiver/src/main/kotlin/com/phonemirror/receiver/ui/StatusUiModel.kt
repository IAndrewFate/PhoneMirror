package com.phonemirror.receiver.ui

enum class StatusAction {
    REGENERATE_PIN,
    CLEAR_PAIRED_DEVICES,
    TOGGLE_SERVER,
    EXIT
}

enum class DialogButton {
    CANCEL,
    CONFIRM
}

data class StatusUiState(
    val serverRunning: Boolean = true,
    val isStreaming: Boolean = false,
    val connectedDevice: String? = null,
    val boundPort: Int = 47700,
    val primaryIp: String = "No Wi-Fi",
    val secondaryIps: List<String> = emptyList(),
    val pin: String = "----",
    val pairedCount: Int = 0,
    val isClearPairedDialogVisible: Boolean = false,
    val dialogFocusedButton: DialogButton = DialogButton.CANCEL,
    val focusedAction: StatusAction = StatusAction.REGENERATE_PIN
) {
    val formattedIpPort: String
        get() = if (primaryIp == "No Wi-Fi") "No Wi-Fi" else "$primaryIp:$boundPort"

    val statusText: String
        get() = when {
            !serverRunning -> "Status: Server stopped"
            isStreaming -> "Status: Streaming (${connectedDevice ?: "connected"})"
            else -> "Status: Waiting for phone connection..."
        }
}

class StatusPresenter(
    initialPort: Int = 47700,
    initialPin: String = "----",
    private val onRegeneratePin: () -> String = { initialPin },
    private val onClearPaired: () -> Unit = {},
    private val onToggleServer: (Boolean) -> Unit = {}
) {
    val focusOrder = listOf(
        StatusAction.REGENERATE_PIN,
        StatusAction.CLEAR_PAIRED_DEVICES,
        StatusAction.TOGGLE_SERVER,
        StatusAction.EXIT
    )

    var state: StatusUiState = StatusUiState(
        boundPort = initialPort,
        pin = initialPin
    )
        private set

    fun setNetworkAddresses(addresses: List<String>) {
        val sorted = selectPrimaryIp(addresses)
        state = state.copy(
            primaryIp = sorted.firstOrNull() ?: "No Wi-Fi",
            secondaryIps = if (sorted.size > 1) sorted.drop(1) else emptyList()
        )
    }

    fun updateServerState(running: Boolean, boundPort: Int, pin: String, pairedCount: Int) {
        state = state.copy(
            serverRunning = running,
            boundPort = boundPort,
            pin = pin,
            pairedCount = pairedCount
        )
    }

    fun updateStreaming(isStreaming: Boolean, connectedDevice: String?) {
        state = state.copy(
            isStreaming = isStreaming,
            connectedDevice = connectedDevice
        )
    }

    fun moveFocusNext() {
        val idx = focusOrder.indexOf(state.focusedAction)
        val nextIdx = (idx + 1) % focusOrder.size
        state = state.copy(focusedAction = focusOrder[nextIdx])
    }

    fun moveFocusPrevious() {
        val idx = focusOrder.indexOf(state.focusedAction)
        val prevIdx = if (idx <= 0) focusOrder.size - 1 else idx - 1
        state = state.copy(focusedAction = focusOrder[prevIdx])
    }

    fun triggerRegeneratePin() {
        val newPin = onRegeneratePin()
        state = state.copy(pin = newPin)
    }

    fun requestClearPaired() {
        state = state.copy(
            isClearPairedDialogVisible = true,
            dialogFocusedButton = DialogButton.CANCEL // Default focus on Cancel!
        )
    }

    fun dismissClearPairedDialog() {
        state = state.copy(isClearPairedDialogVisible = false)
    }

    fun confirmClearPaired() {
        onClearPaired()
        state = state.copy(
            pairedCount = 0,
            isClearPairedDialogVisible = false
        )
    }

    fun toggleServer() {
        val newRunning = !state.serverRunning
        onToggleServer(newRunning)
        state = state.copy(serverRunning = newRunning)
    }

    companion object {
        fun selectPrimaryIp(addresses: List<String>): List<String> {
            // Filter out loopback (127.*) and link-local (169.254.*)
            val valid = addresses.filter { ip ->
                !ip.startsWith("127.") && !ip.startsWith("169.254.") && ip.contains(".")
            }
            // Prioritize private home Wi-Fi ranges (192.168.*, 10.*, 172.16-31.*)
            return valid.sortedWith(Comparator { a, b ->
                fun rank(ip: String): Int = when {
                    ip.startsWith("192.168.") -> 1
                    ip.startsWith("10.") -> 2
                    ip.startsWith("172.") -> 3
                    else -> 4
                }
                rank(a).compareTo(rank(b))
            })
        }
    }
}
