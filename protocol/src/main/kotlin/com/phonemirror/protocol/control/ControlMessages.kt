package com.phonemirror.protocol.control

import com.phonemirror.protocol.wire.ProtocolException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
val ControlJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    classDiscriminator = "type"
}

class ControlParseException(message: String, cause: Throwable? = null) : ProtocolException(message, cause)

@Serializable
enum class ErrorReason {
    @SerialName("pin_rejected")
    PIN_REJECTED,

    @SerialName("busy")
    BUSY,

    @SerialName("proto_mismatch")
    PROTO_MISMATCH
}

@Serializable
data class VideoParams(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val fps: Int,
    val bitrate: Int
)

@Serializable
data class AudioParams(
    val enabled: Boolean,
    val codec: String = "opus",
    val sampleRate: Int = 48000,
    val channels: Int = 2,
    val frameMs: Int = 20
)

@Serializable
sealed class ControlMessage {

    @Serializable
    @SerialName("hello")
    data class Hello(
        val v: Int = 1,
        val gen: Long,
        val deviceId: String,
        val deviceName: String,
        val pin: String,
        val video: VideoParams,
        val audio: AudioParams
    ) : ControlMessage()

    @Serializable
    @SerialName("hello_ok")
    data class HelloOk(
        val unused: String? = null
    ) : ControlMessage()

    @Serializable
    @SerialName("error")
    data class Error(
        val reason: ErrorReason
    ) : ControlMessage()

    @Serializable
    @SerialName("resolution_change")
    data class ResolutionChange(
        val width: Int,
        val height: Int,
        val dpi: Int
    ) : ControlMessage()

    @Serializable
    @SerialName("request_keyframe")
    data class RequestKeyframe(
        val unused: String? = null
    ) : ControlMessage()

    @Serializable
    @SerialName("ping")
    data class Ping(
        val t: Long
    ) : ControlMessage()

    @Serializable
    @SerialName("pong")
    data class Pong(
        val t: Long
    ) : ControlMessage()

    @Serializable
    @SerialName("stop")
    data class Stop(
        val unused: String? = null
    ) : ControlMessage()
}

fun encodeControl(msg: ControlMessage): ByteArray {
    return ControlJson.encodeToString(ControlMessage.serializer(), msg).toByteArray(Charsets.UTF_8)
}

fun decodeControl(body: ByteArray): ControlMessage {
    if (body.isEmpty()) {
        throw ControlParseException("Control body cannot be empty")
    }
    return try {
        val jsonString = body.toString(Charsets.UTF_8)
        ControlJson.decodeFromString(ControlMessage.serializer(), jsonString)
    } catch (e: Exception) {
        throw ControlParseException("Failed to parse control message: ${e.message}", e)
    }
}

fun ControlMessage.compatibilityCheck(): Boolean {
    return when (this) {
        is ControlMessage.Hello -> this.v == 1
        else -> true
    }
}