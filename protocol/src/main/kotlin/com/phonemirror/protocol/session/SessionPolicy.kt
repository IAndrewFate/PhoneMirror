package com.phonemirror.protocol.session

import com.phonemirror.protocol.control.ControlMessage
import com.phonemirror.protocol.wire.FrameKind

enum class SessionState {
    IDLE,
    AWAITING_HELLO,
    CONFIGURING,
    STREAMING,
    STOPPED
}

enum class Role {
    SENDER,
    RECEIVER
}

enum class Requirement {
    SEND_VIDEO_CONFIG,
    SEND_AUDIO_CONFIG,
    SEND_KEYFRAME_REQUEST,
    RECONFIGURE_DECODER,
    AWAIT_KEYFRAME,
    RESET_DECODER,
    INCREMENT_GEN
}

data class Transition(
    val state: SessionState,
    val violations: List<String> = emptyList(),
    val requirements: List<Requirement> = emptyList(),
    val isDroppable: Boolean = false
)

sealed class SessionEvent {
    data class HelloSent(val hello: ControlMessage.Hello) : SessionEvent()
    data class HelloReceived(val hello: ControlMessage.Hello) : SessionEvent()
    data object HelloOkSent : SessionEvent()
    data object HelloOkReceived : SessionEvent()
    data object VideoConfigSent : SessionEvent()
    data object VideoConfigReceived : SessionEvent()
    data object AudioConfigSent : SessionEvent()
    data object AudioConfigReceived : SessionEvent()
    data object KeyframeSent : SessionEvent()
    data object KeyframeReceived : SessionEvent()
    data class ResolutionChanged(val width: Int, val height: Int, val dpi: Int) : SessionEvent()
    data class ResolutionChangeReceived(val width: Int, val height: Int, val dpi: Int) : SessionEvent()
    data object ConnectionLost : SessionEvent()
    data object Reconnected : SessionEvent()
    data object StopRequested : SessionEvent()
    data class FrameReceived(val kind: FrameKind, val keyframe: Boolean) : SessionEvent()
}

class SessionPolicy(
    val role: Role,
    var gen: Long = 0
) {
    var state: SessionState = if (role == Role.RECEIVER) SessionState.AWAITING_HELLO else SessionState.IDLE
        private set

    var audioEnabled: Boolean = false
        private set

    var hasVideoConfig: Boolean = false
        private set

    var hasAudioConfig: Boolean = false
        private set

    var awaitingKeyframe: Boolean = false
        private set

    private var sessionEnded: Boolean = false

    fun onEvent(event: SessionEvent): Transition {
        val violations = mutableListOf<String>()
        val requirements = mutableListOf<Requirement>()
        var isDroppable = false

        if (event is SessionEvent.StopRequested) {
            state = SessionState.STOPPED
            sessionEnded = true
            hasVideoConfig = false
            hasAudioConfig = false
            awaitingKeyframe = false
            if (role == Role.RECEIVER) {
                requirements.add(Requirement.RESET_DECODER)
            } else {
                requirements.add(Requirement.INCREMENT_GEN)
            }
            return Transition(state, violations, requirements, isDroppable)
        }

        if (role == Role.SENDER) {
            when (event) {
                is SessionEvent.HelloSent -> {
                    if (sessionEnded) {
                        gen++
                        requirements.add(Requirement.INCREMENT_GEN)
                        sessionEnded = false
                    }
                    audioEnabled = event.hello.audio.enabled
                    hasVideoConfig = false
                    hasAudioConfig = false
                }
                is SessionEvent.HelloOkReceived -> {
                    state = SessionState.CONFIGURING
                    hasVideoConfig = false
                    hasAudioConfig = false
                    awaitingKeyframe = true
                    requirements.add(Requirement.SEND_VIDEO_CONFIG)
                }
                is SessionEvent.VideoConfigSent -> {
                    if (state != SessionState.CONFIGURING && state != SessionState.STREAMING) {
                        violations.add("VideoConfigSent in unexpected state: $state")
                    }
                    hasVideoConfig = true
                    if (audioEnabled && !hasAudioConfig) {
                        requirements.add(Requirement.SEND_AUDIO_CONFIG)
                    } else {
                        requirements.add(Requirement.SEND_KEYFRAME_REQUEST)
                    }
                }
                is SessionEvent.AudioConfigSent -> {
                    if (state != SessionState.CONFIGURING && state != SessionState.STREAMING) {
                        violations.add("AudioConfigSent in unexpected state: $state")
                    }
                    if (!audioEnabled) {
                        violations.add("AudioConfigSent but audio is not enabled in session")
                    }
                    if (!hasVideoConfig) {
                        violations.add("AudioConfigSent before VideoConfigSent")
                    }
                    hasAudioConfig = true
                    requirements.add(Requirement.SEND_KEYFRAME_REQUEST)
                }
                is SessionEvent.KeyframeSent -> {
                    if (!hasVideoConfig) {
                        violations.add("KeyframeSent before VideoConfigSent")
                    }
                    if (audioEnabled && !hasAudioConfig) {
                        violations.add("KeyframeSent before AudioConfigSent when audio is enabled")
                    }
                    state = SessionState.STREAMING
                    awaitingKeyframe = false
                }
                is SessionEvent.ResolutionChanged -> {
                    hasVideoConfig = false
                    awaitingKeyframe = true
                    requirements.add(Requirement.SEND_VIDEO_CONFIG)
                    requirements.add(Requirement.AWAIT_KEYFRAME)
                }
                is SessionEvent.ConnectionLost -> {
                    hasVideoConfig = false
                    hasAudioConfig = false
                    awaitingKeyframe = true
                    // gen does NOT increment on reconnect
                }
                is SessionEvent.Reconnected -> {
                    // Waiting for HelloOk to start sequence
                }
                else -> {}
            }
        } else { // Role.RECEIVER
            when (event) {
                is SessionEvent.HelloReceived -> {
                    audioEnabled = event.hello.audio.enabled
                    gen = event.hello.gen
                    state = SessionState.CONFIGURING
                    hasVideoConfig = false
                    hasAudioConfig = false
                    awaitingKeyframe = true
                }
                is SessionEvent.HelloOkSent -> {
                    // Receiver ready to receive configs
                }
                is SessionEvent.VideoConfigReceived -> {
                    hasVideoConfig = true
                    awaitingKeyframe = true
                    requirements.add(Requirement.AWAIT_KEYFRAME)
                }
                is SessionEvent.AudioConfigReceived -> {
                    if (!hasVideoConfig) {
                        violations.add("AudioConfigReceived before VideoConfigReceived")
                    }
                    hasAudioConfig = true
                }
                is SessionEvent.KeyframeReceived -> {
                    if (!hasVideoConfig) {
                        violations.add("KeyframeReceived before VideoConfigReceived")
                    }
                    state = SessionState.STREAMING
                    awaitingKeyframe = false
                }
                is SessionEvent.ResolutionChangeReceived -> {
                    hasVideoConfig = false
                    awaitingKeyframe = true
                    requirements.add(Requirement.RECONFIGURE_DECODER)
                    requirements.add(Requirement.AWAIT_KEYFRAME)
                }
                is SessionEvent.FrameReceived -> {
                    if (state == SessionState.CONFIGURING) {
                        if (event.kind == FrameKind.AUDIO_FRAME) {
                            violations.add("Audio frame received while CONFIGURING")
                        }
                    }
                    if (awaitingKeyframe) {
                        if (event.kind == FrameKind.VIDEO_FRAME) {
                            if (!event.keyframe) {
                                isDroppable = true
                            } else {
                                awaitingKeyframe = false
                                state = SessionState.STREAMING
                                isDroppable = false
                            }
                        }
                    }
                }
                is SessionEvent.ConnectionLost -> {
                    state = SessionState.AWAITING_HELLO
                    hasVideoConfig = false
                    hasAudioConfig = false
                    awaitingKeyframe = true
                    requirements.add(Requirement.RESET_DECODER)
                }
                is SessionEvent.Reconnected -> {
                    // Receiver awaiting hello
                    state = SessionState.AWAITING_HELLO
                }
                else -> {}
            }
        }

        return Transition(state, violations, requirements, isDroppable)
    }
}