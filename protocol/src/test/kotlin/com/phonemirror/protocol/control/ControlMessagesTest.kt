package com.phonemirror.protocol.control

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ControlMessagesTest {

    private val sampleHello = ControlMessage.Hello(
        v = 1,
        gen = 42L,
        deviceId = "dev-123",
        deviceName = "Pixel 8",
        pin = "1234",
        video = VideoParams(1920, 1080, 420, 60, 8_000_000),
        audio = AudioParams(enabled = true, codec = "opus", sampleRate = 48000, channels = 2, frameMs = 20)
    )

    @Test
    fun roundtrip_hello() {
        val bytes = encodeControl(sampleHello)
        val decoded = decodeControl(bytes)
        assertThat(decoded).isEqualTo(sampleHello)
    }

    @Test
    fun roundtrip_hello_ok() {
        val msg = ControlMessage.HelloOk()
        val bytes = encodeControl(msg)
        val decoded = decodeControl(bytes)
        assertThat(decoded).isEqualTo(msg)
    }

    @Test
    fun roundtrip_error_all_reasons() {
        for (reason in ErrorReason.entries) {
            val msg = ControlMessage.Error(reason)
            val bytes = encodeControl(msg)
            val decoded = decodeControl(bytes)
            assertThat(decoded).isEqualTo(msg)
        }
    }

    @Test
    fun roundtrip_resolution_change() {
        val msg = ControlMessage.ResolutionChange(1280, 720, 320)
        val bytes = encodeControl(msg)
        val decoded = decodeControl(bytes)
        assertThat(decoded).isEqualTo(msg)
    }

    @Test
    fun roundtrip_request_keyframe() {
        val msg = ControlMessage.RequestKeyframe()
        val bytes = encodeControl(msg)
        val decoded = decodeControl(bytes)
        assertThat(decoded).isEqualTo(msg)
    }

    @Test
    fun roundtrip_ping_and_pong() {
        val ping = ControlMessage.Ping(123456789L)
        val pong = ControlMessage.Pong(123456789L)

        assertThat(decodeControl(encodeControl(ping))).isEqualTo(ping)
        assertThat(decodeControl(encodeControl(pong))).isEqualTo(pong)
    }

    @Test
    fun roundtrip_stop() {
        val msg = ControlMessage.Stop()
        val bytes = encodeControl(msg)
        val decoded = decodeControl(bytes)
        assertThat(decoded).isEqualTo(msg)
    }

    @Test
    fun unknown_type_throws_control_parse_exception() {
        val json = """{"type":"unknown_message_type","foo":"bar"}"""
        val ex = assertThrows(ControlParseException::class.java) {
            decodeControl(json.toByteArray(Charsets.UTF_8))
        }
        assertThat(ex).hasMessageThat().contains("Failed to parse control message")
    }

    @Test
    fun missing_required_field_throws_control_parse_exception() {
        // Hello without deviceId
        val invalidHello = """{"type":"hello","v":1,"gen":1,"deviceName":"phone","pin":"0000","video":{"width":100,"height":100,"dpi":100,"fps":30,"bitrate":1000},"audio":{"enabled":false}}"""
        val ex = assertThrows(ControlParseException::class.java) {
            decodeControl(invalidHello.toByteArray(Charsets.UTF_8))
        }
        assertThat(ex).hasMessageThat().contains("Failed to parse control message")
    }

    @Test
    fun extra_field_tolerated() {
        val jsonWithExtra = """{"type":"ping","t":999,"extraField":"extraValue","anotherObject":{"nested":true}}"""
        val decoded = decodeControl(jsonWithExtra.toByteArray(Charsets.UTF_8))
        assertThat(decoded).isInstanceOf(ControlMessage.Ping::class.java)
        assertThat((decoded as ControlMessage.Ping).t).isEqualTo(999L)
    }

    @Test
    fun compatibility_check_v1_true_v2_false() {
        val helloV1 = sampleHello.copy(v = 1)
        val helloV2 = sampleHello.copy(v = 2)
        val ping = ControlMessage.Ping(1L)

        assertThat(helloV1.compatibilityCheck()).isTrue()
        assertThat(helloV2.compatibilityCheck()).isFalse()
        assertThat(ping.compatibilityCheck()).isTrue()
    }

    @Test
    fun error_reason_snake_case_wire_names_asserted() {
        val jsonRejected = encodeControl(ControlMessage.Error(ErrorReason.PIN_REJECTED)).toString(Charsets.UTF_8)
        val jsonBusy = encodeControl(ControlMessage.Error(ErrorReason.BUSY)).toString(Charsets.UTF_8)
        val jsonProto = encodeControl(ControlMessage.Error(ErrorReason.PROTO_MISMATCH)).toString(Charsets.UTF_8)

        assertThat(jsonRejected).contains(""""reason":"pin_rejected"""")
        assertThat(jsonBusy).contains(""""reason":"busy"""")
        assertThat(jsonProto).contains(""""reason":"proto_mismatch"""")
    }

    @Test
    fun empty_body_throws_control_parse_exception() {
        val ex = assertThrows(ControlParseException::class.java) {
            decodeControl(ByteArray(0))
        }
        assertThat(ex).hasMessageThat().contains("cannot be empty")
    }

    @Test
    fun sample_hello_json_contains_type_hello() {
        val json = encodeControl(sampleHello).toString(Charsets.UTF_8)
        assertThat(json).contains(""""type":"hello"""")
        assertThat(json).contains(""""pin":"1234"""")
        assertThat(json).contains(""""deviceId":"dev-123"""")
    }
}