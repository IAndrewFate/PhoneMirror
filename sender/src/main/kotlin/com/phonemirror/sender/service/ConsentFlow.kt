package com.phonemirror.sender.service

import android.media.projection.MediaProjection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

sealed class ProjectionState {
    data object Idle : ProjectionState()
    data object AwaitingConsent : ProjectionState()
    data class Active(val projection: MediaProjection?) : ProjectionState()
    data object Stopped : ProjectionState()
    data object Revoked : ProjectionState()
}

object ProjectionHolder {
    private val _state = MutableStateFlow<ProjectionState>(ProjectionState.Idle)
    val state: StateFlow<ProjectionState> = _state.asStateFlow()

    val cleanupHooks = CopyOnWriteArrayList<() -> Unit>()

    fun setAwaitingConsent() {
        _state.value = ProjectionState.AwaitingConsent
    }

    fun setActive(projection: MediaProjection?) {
        _state.value = ProjectionState.Active(projection)
    }

    fun setStopped() {
        runCleanupHooks()
        _state.value = ProjectionState.Stopped
    }

    fun setRevoked() {
        runCleanupHooks()
        _state.value = ProjectionState.Revoked
    }

    fun reset() {
        runCleanupHooks()
        _state.value = ProjectionState.Idle
    }

    private fun runCleanupHooks() {
        for (hook in cleanupHooks) {
            try { hook() } catch (_: Exception) {}
        }
        cleanupHooks.clear()
    }
}

class ConsentFlow {
    enum class Step {
        IDLE,
        AWAITING_CONSENT,
        CONSENT_GRANTED,
        FGS_STARTED,
        PROJECTION_ACQUIRED,
        CALLBACK_REGISTERED,
        TERMINATED
    }

    var currentStep: Step = Step.IDLE
        private set

    var currentSessionId: Long = 0L
        private set

    private var usedSessionIds = mutableSetOf<Long>()

    fun requestConsent(sessionId: Long) {
        if (usedSessionIds.contains(sessionId)) {
            throw IllegalStateException("Cannot reuse session ID $sessionId: violates Android 14 per-session consent rule")
        }
        currentSessionId = sessionId
        currentStep = Step.AWAITING_CONSENT
    }

    fun onConsentGranted(sessionId: Long) {
        if (currentStep != Step.AWAITING_CONSENT) {
            throw IllegalStateException("Cannot grant consent when not awaiting: current step is $currentStep")
        }
        if (sessionId != currentSessionId || usedSessionIds.contains(sessionId)) {
            throw IllegalStateException("Stale or reused consent token for session $sessionId")
        }
        usedSessionIds.add(sessionId)
        currentStep = Step.CONSENT_GRANTED
    }

    fun onForegroundServiceStarted() {
        if (currentStep != Step.CONSENT_GRANTED) {
            throw IllegalStateException("Foreground service must be started after consent is granted, but step is $currentStep")
        }
        currentStep = Step.FGS_STARTED
    }

    fun onMediaProjectionAcquired() {
        if (currentStep != Step.FGS_STARTED) {
            throw IllegalStateException("getMediaProjection called before startForeground! Violates Android 14 requirement. Current step: $currentStep")
        }
        currentStep = Step.PROJECTION_ACQUIRED
    }

    fun onCallbackRegistered() {
        if (currentStep != Step.PROJECTION_ACQUIRED) {
            throw IllegalStateException("Callback must be registered immediately after acquiring projection, current step: $currentStep")
        }
        currentStep = Step.CALLBACK_REGISTERED
    }

    fun onStop() {
        currentStep = Step.TERMINATED
    }

    fun onRevoked() {
        currentStep = Step.TERMINATED
    }

    fun canCreateVirtualDisplay(): Boolean {
        return currentStep == Step.CALLBACK_REGISTERED
    }
}