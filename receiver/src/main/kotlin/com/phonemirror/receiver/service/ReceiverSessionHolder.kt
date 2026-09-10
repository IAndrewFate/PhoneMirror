package com.phonemirror.receiver.service

import com.phonemirror.receiver.decode.AudioDecodePipeline
import com.phonemirror.receiver.decode.VideoDecodePipeline
import com.phonemirror.receiver.server.MirrorServer
import com.phonemirror.receiver.server.StreamDispatcher
import com.phonemirror.receiver.sync.AvSyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ReceiverSessionHolder {
    var activeService: ReceiverService? = null
    var activeServer: MirrorServer? = null
    var activeDispatcher: StreamDispatcher? = null
    var videoPipeline: VideoDecodePipeline? = null
    var audioPipeline: AudioDecodePipeline? = null
    var avSyncEngine: AvSyncEngine? = null

    private val _rttMs = MutableStateFlow(0L)
    val rttMs: StateFlow<Long> = _rttMs.asStateFlow()

    fun setRttMs(rtt: Long) {
        _rttMs.value = rtt
    }

    fun clear() {
        activeService = null
        activeServer = null
        activeDispatcher = null
        videoPipeline = null
        audioPipeline = null
        avSyncEngine = null
        _rttMs.value = 0L
    }
}
