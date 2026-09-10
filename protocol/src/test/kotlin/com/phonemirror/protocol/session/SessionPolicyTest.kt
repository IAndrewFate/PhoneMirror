package com.phonemirror.protocol.session

import com.google.common.truth.Truth.assertThat
import com.phonemirror.protocol.control.AudioParams
import com.phonemirror.protocol.control.ControlMessage
import com.phonemirror.protocol.control.VideoParams
import com.phonemirror.protocol.wire.FrameKind
import org.junit.Test
import kotlin.random.Random

class SessionPolicyTest {

    private fun createHello(audioEnabled: Boolean = true, gen: Long = 1L): ControlMessage.Hello {
        return ControlMessage.Hello(
            v = 1,
            gen = gen,
            deviceId = "dev-1",
            deviceName = "Phone",
            pin = "1234",
            video = VideoParams(1920, 1080, 400, 60, 8_000_000),
            audio = AudioParams(enabled = audioEnabled)
        )
    }

    @Test
    fun full_happy_sender_transcript_with_audio() {
        val policy = SessionPolicy(Role.SENDER, gen = 10L)
        val hello = createHello(audioEnabled = true, gen = 10L)

        policy.onEvent(SessionEvent.HelloSent(hello))
        val t1 = policy.onEvent(SessionEvent.HelloOkReceived)
        assertThat(t1.state).isEqualTo(SessionState.CONFIGURING)
        assertThat(t1.requirements).containsExactly(Requirement.SEND_VIDEO_CONFIG)

        val t2 = policy.onEvent(SessionEvent.VideoConfigSent)
        assertThat(t2.requirements).containsExactly(Requirement.SEND_AUDIO_CONFIG)

        val t3 = policy.onEvent(SessionEvent.AudioConfigSent)
        assertThat(t3.requirements).containsExactly(Requirement.SEND_KEYFRAME_REQUEST)

        val t4 = policy.onEvent(SessionEvent.KeyframeSent)
        assertThat(t4.state).isEqualTo(SessionState.STREAMING)
        assertThat(t4.violations).isEmpty()
    }

    @Test
    fun full_happy_sender_transcript_without_audio() {
        val policy = SessionPolicy(Role.SENDER, gen = 10L)
        val hello = createHello(audioEnabled = false, gen = 10L)

        policy.onEvent(SessionEvent.HelloSent(hello))
        val t1 = policy.onEvent(SessionEvent.HelloOkReceived)
        assertThat(t1.requirements).containsExactly(Requirement.SEND_VIDEO_CONFIG)

        val t2 = policy.onEvent(SessionEvent.VideoConfigSent)
        // Audio is disabled, so directly request keyframe!
        assertThat(t2.requirements).containsExactly(Requirement.SEND_KEYFRAME_REQUEST)

        val t3 = policy.onEvent(SessionEvent.KeyframeSent)
        assertThat(t3.state).isEqualTo(SessionState.STREAMING)
        assertThat(t3.violations).isEmpty()
    }

    @Test
    fun full_happy_receiver_transcript() {
        val policy = SessionPolicy(Role.RECEIVER)
        val hello = createHello(audioEnabled = true, gen = 5L)

        val t1 = policy.onEvent(SessionEvent.HelloReceived(hello))
        assertThat(t1.state).isEqualTo(SessionState.CONFIGURING)
        assertThat(policy.gen).isEqualTo(5L)

        val t2 = policy.onEvent(SessionEvent.VideoConfigReceived)
        assertThat(t2.requirements).containsExactly(Requirement.AWAIT_KEYFRAME)

        policy.onEvent(SessionEvent.AudioConfigReceived)
        val t3 = policy.onEvent(SessionEvent.KeyframeReceived)
        assertThat(t3.state).isEqualTo(SessionState.STREAMING)
        assertThat(t3.violations).isEmpty()
    }

    @Test
    fun reconnect_mid_stream_gen_unchanged_start_sequence_re_required() {
        val sender = SessionPolicy(Role.SENDER, gen = 7L)
        val hello = createHello(audioEnabled = true, gen = 7L)
        sender.onEvent(SessionEvent.HelloSent(hello))
        sender.onEvent(SessionEvent.HelloOkReceived)
        sender.onEvent(SessionEvent.VideoConfigSent)
        sender.onEvent(SessionEvent.AudioConfigSent)
        sender.onEvent(SessionEvent.KeyframeSent)
        assertThat(sender.state).isEqualTo(SessionState.STREAMING)

        // Connection lost
        sender.onEvent(SessionEvent.ConnectionLost)
        assertThat(sender.gen).isEqualTo(7L) // Gen must NOT change on reconnect

        // Reconnected
        sender.onEvent(SessionEvent.Reconnected)
        sender.onEvent(SessionEvent.HelloSent(hello))
        val tHelloOk = sender.onEvent(SessionEvent.HelloOkReceived)
        // Start sequence re-required!
        assertThat(tHelloOk.requirements).containsExactly(Requirement.SEND_VIDEO_CONFIG)
        assertThat(sender.gen).isEqualTo(7L)
    }

    @Test
    fun receiver_connection_lost_requires_reset_decoder() {
        val receiver = SessionPolicy(Role.RECEIVER)
        val hello = createHello(audioEnabled = true, gen = 3L)
        receiver.onEvent(SessionEvent.HelloReceived(hello))
        receiver.onEvent(SessionEvent.VideoConfigReceived)
        receiver.onEvent(SessionEvent.KeyframeReceived)

        val tLost = receiver.onEvent(SessionEvent.ConnectionLost)
        assertThat(tLost.state).isEqualTo(SessionState.AWAITING_HELLO)
        assertThat(tLost.requirements).containsExactly(Requirement.RESET_DECODER)
    }

    @Test
    fun resolution_change_ordering_sender_and_receiver() {
        val sender = SessionPolicy(Role.SENDER)
        val receiver = SessionPolicy(Role.RECEIVER)

        val sRes = sender.onEvent(SessionEvent.ResolutionChanged(1280, 720, 320))
        assertThat(sRes.requirements).containsExactly(Requirement.SEND_VIDEO_CONFIG, Requirement.AWAIT_KEYFRAME)

        val rRes = receiver.onEvent(SessionEvent.ResolutionChangeReceived(1280, 720, 320))
        assertThat(rRes.requirements).containsExactly(Requirement.RECONFIGURE_DECODER, Requirement.AWAIT_KEYFRAME)
    }

    @Test
    fun non_keyframe_before_keyframe_marked_droppable() {
        val receiver = SessionPolicy(Role.RECEIVER)
        receiver.onEvent(SessionEvent.HelloReceived(createHello()))
        receiver.onEvent(SessionEvent.VideoConfigReceived)

        // Delta frame received while awaiting keyframe -> droppable!
        val tDelta = receiver.onEvent(SessionEvent.FrameReceived(FrameKind.VIDEO_FRAME, keyframe = false))
        assertThat(tDelta.isDroppable).isTrue()

        // Keyframe received -> NOT droppable and transitions to STREAMING!
        val tKey = receiver.onEvent(SessionEvent.FrameReceived(FrameKind.VIDEO_FRAME, keyframe = true))
        assertThat(tKey.isDroppable).isFalse()
        assertThat(tKey.state).isEqualTo(SessionState.STREAMING)

        // Subsequent delta frame in STREAMING is NOT droppable
        val tNormal = receiver.onEvent(SessionEvent.FrameReceived(FrameKind.VIDEO_FRAME, keyframe = false))
        assertThat(tNormal.isDroppable).isFalse()
    }

    @Test
    fun audio_during_configuring_is_violation() {
        val receiver = SessionPolicy(Role.RECEIVER)
        receiver.onEvent(SessionEvent.HelloReceived(createHello()))
        // Still in CONFIGURING
        val t = receiver.onEvent(SessionEvent.FrameReceived(FrameKind.AUDIO_FRAME, keyframe = false))
        assertThat(t.violations).contains("Audio frame received while CONFIGURING")
    }

    @Test
    fun stop_from_every_state_transitions_to_stopped() {
        for (state in listOf(SessionState.IDLE, SessionState.CONFIGURING, SessionState.STREAMING)) {
            val policy = SessionPolicy(Role.SENDER)
            if (state == SessionState.CONFIGURING) {
                policy.onEvent(SessionEvent.HelloOkReceived)
            } else if (state == SessionState.STREAMING) {
                policy.onEvent(SessionEvent.HelloOkReceived)
                policy.onEvent(SessionEvent.VideoConfigSent)
                policy.onEvent(SessionEvent.KeyframeSent)
            }
            val t = policy.onEvent(SessionEvent.StopRequested)
            assertThat(t.state).isEqualTo(SessionState.STOPPED)
        }
    }

    @Test
    fun sender_order_violations_keyframe_before_video_config() {
        val sender = SessionPolicy(Role.SENDER)
        sender.onEvent(SessionEvent.HelloSent(createHello(audioEnabled = true)))
        sender.onEvent(SessionEvent.HelloOkReceived)

        // Sending keyframe directly before video config
        val t = sender.onEvent(SessionEvent.KeyframeSent)
        assertThat(t.violations).isNotEmpty()
        assertThat(t.violations[0]).contains("KeyframeSent before VideoConfigSent")
    }

    @Test
    fun sender_order_violations_audio_config_before_video_config() {
        val sender = SessionPolicy(Role.SENDER)
        sender.onEvent(SessionEvent.HelloSent(createHello(audioEnabled = true)))
        sender.onEvent(SessionEvent.HelloOkReceived)

        val t = sender.onEvent(SessionEvent.AudioConfigSent)
        assertThat(t.violations).contains("AudioConfigSent before VideoConfigSent")
    }

    @Test
    fun gen_increments_only_on_new_capture_session_after_stop() {
        val sender = SessionPolicy(Role.SENDER, gen = 1L)
        val hello1 = createHello(gen = 1L)

        sender.onEvent(SessionEvent.HelloSent(hello1))
        assertThat(sender.gen).isEqualTo(1L)

        // Stop capture session
        val tStop = sender.onEvent(SessionEvent.StopRequested)
        assertThat(tStop.requirements).contains(Requirement.INCREMENT_GEN)
        assertThat(sender.gen).isEqualTo(1L) // Gen increments when next session starts

        // New capture session starts
        val hello2 = createHello(gen = 2L)
        val tNewSession = sender.onEvent(SessionEvent.HelloSent(hello2))
        assertThat(sender.gen).isEqualTo(2L)
        assertThat(tNewSession.requirements).contains(Requirement.INCREMENT_GEN)
    }

    @Test
    fun interleaved_stress_1k_random_legal_events() {
        val rng = Random(42)
        val sender = SessionPolicy(Role.SENDER, gen = 1L)
        val receiver = SessionPolicy(Role.RECEIVER)

        val hello = createHello(audioEnabled = true, gen = 1L)
        sender.onEvent(SessionEvent.HelloSent(hello))
        receiver.onEvent(SessionEvent.HelloReceived(hello))

        for (i in 0 until 1000) {
            val pick = rng.nextInt(6)
            when (pick) {
                0 -> {
                    sender.onEvent(SessionEvent.VideoConfigSent)
                    receiver.onEvent(SessionEvent.VideoConfigReceived)
                }
                1 -> {
                    sender.onEvent(SessionEvent.AudioConfigSent)
                    receiver.onEvent(SessionEvent.AudioConfigReceived)
                }
                2 -> {
                    sender.onEvent(SessionEvent.KeyframeSent)
                    receiver.onEvent(SessionEvent.KeyframeReceived)
                }
                3 -> {
                    val droppable = receiver.onEvent(SessionEvent.FrameReceived(FrameKind.VIDEO_FRAME, keyframe = false))
                    assertThat(droppable.state).isNotNull()
                }
                4 -> {
                    sender.onEvent(SessionEvent.ResolutionChanged(1920, 1080, 400))
                    receiver.onEvent(SessionEvent.ResolutionChangeReceived(1920, 1080, 400))
                }
                5 -> {
                    // Reconnect sequence
                    sender.onEvent(SessionEvent.ConnectionLost)
                    receiver.onEvent(SessionEvent.ConnectionLost)
                    sender.onEvent(SessionEvent.Reconnected)
                    receiver.onEvent(SessionEvent.Reconnected)
                    sender.onEvent(SessionEvent.HelloSent(hello))
                    receiver.onEvent(SessionEvent.HelloReceived(hello))
                    sender.onEvent(SessionEvent.HelloOkReceived)
                    receiver.onEvent(SessionEvent.HelloOkSent)
                }
            }
        }
    }
}