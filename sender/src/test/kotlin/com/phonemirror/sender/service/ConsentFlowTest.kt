package com.phonemirror.sender.service

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ConsentFlowTest {

    @Test
    fun ordering_state_machine_legal_sequence() {
        val flow = ConsentFlow()
        assertThat(flow.currentStep).isEqualTo(ConsentFlow.Step.IDLE)
        assertThat(flow.canCreateVirtualDisplay()).isFalse()

        // 1. Request consent
        flow.requestConsent(1001L)
        assertThat(flow.currentStep).isEqualTo(ConsentFlow.Step.AWAITING_CONSENT)

        // 2. Consent granted
        flow.onConsentGranted(1001L)
        assertThat(flow.currentStep).isEqualTo(ConsentFlow.Step.CONSENT_GRANTED)

        // 3. FGS started BEFORE getMediaProjection
        flow.onForegroundServiceStarted()
        assertThat(flow.currentStep).isEqualTo(ConsentFlow.Step.FGS_STARTED)

        // 4. getMediaProjection inside service
        flow.onMediaProjectionAcquired()
        assertThat(flow.currentStep).isEqualTo(ConsentFlow.Step.PROJECTION_ACQUIRED)

        // 5. registerCallback BEFORE createVirtualDisplay
        flow.onCallbackRegistered()
        assertThat(flow.currentStep).isEqualTo(ConsentFlow.Step.CALLBACK_REGISTERED)

        // Now VirtualDisplay is allowed!
        assertThat(flow.canCreateVirtualDisplay()).isTrue()
    }

    @Test
    fun violation_get_media_projection_before_start_foreground() {
        val flow = ConsentFlow()
        flow.requestConsent(1002L)
        flow.onConsentGranted(1002L)

        // Intentionally skip onForegroundServiceStarted()!
        val ex = assertThrows(IllegalStateException::class.java) {
            flow.onMediaProjectionAcquired()
        }
        assertThat(ex.message).contains("getMediaProjection called before startForeground")
    }

    @Test
    fun violation_start_foreground_before_consent() {
        val flow = ConsentFlow()
        val ex = assertThrows(IllegalStateException::class.java) {
            flow.onForegroundServiceStarted()
        }
        assertThat(ex.message).contains("Foreground service must be started after consent is granted")
    }

    @Test
    fun no_reuse_rule_second_start_with_stale_session() {
        val flow = ConsentFlow()
        flow.requestConsent(2001L)
        flow.onConsentGranted(2001L)
        flow.onForegroundServiceStarted()
        flow.onMediaProjectionAcquired()
        flow.onCallbackRegistered()
        flow.onStop()

        // Attempting to reuse same session ID 2001L
        val ex = assertThrows(IllegalStateException::class.java) {
            flow.requestConsent(2001L)
        }
        assertThat(ex.message).contains("Cannot reuse session ID")
    }

    @Test
    fun teardown_idempotency_and_revoked() {
        val flow = ConsentFlow()
        flow.requestConsent(3001L)
        flow.onConsentGranted(3001L)
        flow.onForegroundServiceStarted()
        flow.onMediaProjectionAcquired()
        flow.onCallbackRegistered()

        flow.onRevoked()
        assertThat(flow.canCreateVirtualDisplay()).isFalse()
        assertThat(flow.currentStep).isEqualTo(ConsentFlow.Step.TERMINATED)

        // Calling onStop after revoked is idempotent
        flow.onStop()
        assertThat(flow.currentStep).isEqualTo(ConsentFlow.Step.TERMINATED)
    }

    @Test
    fun projection_holder_lifecycle_and_cleanup_hooks() {
        ProjectionHolder.reset()
        assertThat(ProjectionHolder.state.value).isEqualTo(ProjectionState.Idle)

        var hookCalled = false
        ProjectionHolder.cleanupHooks.add { hookCalled = true }

        ProjectionHolder.setAwaitingConsent()
        assertThat(ProjectionHolder.state.value).isEqualTo(ProjectionState.AwaitingConsent)

        ProjectionHolder.setActive(null)
        assertThat(ProjectionHolder.state.value).isInstanceOf(ProjectionState.Active::class.java)

        ProjectionHolder.setStopped()
        assertThat(ProjectionHolder.state.value).isEqualTo(ProjectionState.Stopped)
        assertThat(hookCalled).isTrue()
        assertThat(ProjectionHolder.cleanupHooks).isEmpty()
    }

    @Test
    fun notification_channel_id_const_and_action() {
        assertThat(MirrorService.CHANNEL_ID).isEqualTo("phonemirror_sender_mirroring")
        assertThat(MirrorService.NOTIFICATION_ID).isEqualTo(34567)
        assertThat(MirrorService.ACTION_STOP).isEqualTo("com.phonemirror.sender.ACTION_STOP")
    }
}