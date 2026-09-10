package com.phonemirror.protocol.wire

data class Frame(
    val kind: FrameKind,
    val body: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Frame
        if (kind != other.kind) return false
        if (!body.contentEquals(other.body)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }
}