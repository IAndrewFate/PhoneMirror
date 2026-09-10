package com.phonemirror.sender.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.phonemirror.sender.MainActivity
import com.phonemirror.sender.net.ClientState
import com.phonemirror.sender.net.DiscoveredDevice
import com.phonemirror.sender.net.EndpointStore
import com.phonemirror.sender.net.InMemoryEndpointStore

@RequiresApi(Build.VERSION_CODES.N)
class MirrorTileService : TileService() {

    var endpointStore: EndpointStore = InMemoryEndpointStore()

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return

        val state = Companion.sharedClientState
        val isStreaming = Companion.sharedIsStreaming
        val (tileState, subtitle) = computeTileState(state, isStreaming)

        tile.state = tileState
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val lastEndpoint = endpointStore.loadEndpoint()
        val intent = createTileClickIntent(this, lastEndpoint)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        const val EXTRA_QUICK_CONNECT = "com.phonemirror.sender.EXTRA_QUICK_CONNECT"

        var sharedClientState: ClientState = ClientState.Idle
        var sharedIsStreaming: Boolean = false

        fun computeTileState(clientState: ClientState, isStreaming: Boolean): Pair<Int, String> {
            return when {
                isStreaming -> Tile.STATE_ACTIVE to "Streaming"
                clientState is ClientState.Connected -> Tile.STATE_ACTIVE to "Connected"
                clientState is ClientState.Connecting -> Tile.STATE_ACTIVE to "Connecting"
                clientState is ClientState.Reconnecting -> Tile.STATE_ACTIVE to "Reconnecting"
                clientState is ClientState.Failed -> Tile.STATE_UNAVAILABLE to "Failed"
                clientState is ClientState.PairingFailure -> Tile.STATE_UNAVAILABLE to "Error"
                else -> Tile.STATE_INACTIVE to "Off"
            }
        }

        fun createTileClickIntent(context: Context, lastEndpoint: DiscoveredDevice?): Intent {
            return Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (lastEndpoint != null) {
                    putExtra(EXTRA_QUICK_CONNECT, true)
                }
            }
        }
    }
}
