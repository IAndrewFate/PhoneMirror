package com.phonemirror.protocol.wire

open class ProtocolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class FrameTooLargeException(message: String) : ProtocolException(message)