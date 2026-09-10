package com.phonemirror.protocol.integration

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class SocketPair(
    val clientInput: InputStream,
    val clientOutput: OutputStream,
    val serverInput: InputStream,
    val serverOutput: OutputStream,
    private val closeables: List<Closeable>
) : Closeable {
    override fun close() {
        for (c in closeables) {
            try { c.close() } catch (_: Exception) {}
        }
    }
}

object SocketPairHarness {

    fun createPipedPair(): SocketPair {
        val clientToServerOut = PipedOutputStream()
        val clientToServerIn = PipedInputStream(clientToServerOut, 64 * 1024)

        val serverToClientOut = PipedOutputStream()
        val serverToClientIn = PipedInputStream(serverToClientOut, 64 * 1024)

        return SocketPair(
            clientInput = serverToClientIn,
            clientOutput = clientToServerOut,
            serverInput = clientToServerIn,
            serverOutput = serverToClientOut,
            closeables = listOf(clientToServerIn, clientToServerOut, serverToClientIn, serverToClientOut)
        )
    }

    fun createRealSocketPair(): SocketPair {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort

        val client = Socket("127.0.0.1", port)
        client.tcpNoDelay = true
        val accepted = server.accept()
        accepted.tcpNoDelay = true

        return SocketPair(
            clientInput = client.getInputStream(),
            clientOutput = client.getOutputStream(),
            serverInput = accepted.getInputStream(),
            serverOutput = accepted.getOutputStream(),
            closeables = listOf(client, accepted, server)
        )
    }
}