package com.dronelink.app.net

import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Minimal listening-socket abstraction so the app can force a genuine IPv4
 * (AF_INET) listener on device while JVM unit tests keep using java.net.
 *
 * Why: on Android (verified on-device 2026-11-19), java.net.ServerSocket
 * creates a dual-stack AF_INET6 wildcard socket even when bound to an explicit
 * Inet4Address — the listener lands in /proc/net/tcp6 only and the IPv4
 * tailnet path stalls. android.system.Os.socket(AF_INET, ...) produces a real
 * IPv4 listener (visible in /proc/net/tcp as state 0A).
 */
interface ListeningSocket {
    val boundPort: Int
    fun accept(): AcceptedSocket
    fun close()
}

interface AcceptedSocket : AutoCloseable {
    fun inputStream(): InputStream
    fun outputStream(): OutputStream
    fun setSoTimeout(timeoutMs: Int)
    override fun close()
}

fun interface ServerSocketFactory {
    fun listenV4(port: Int, backlog: Int): ListeningSocket
}

/** Production: raw AF_INET socket via libcore Os. */
object OsServerSocketFactory : ServerSocketFactory {
    override fun listenV4(port: Int, backlog: Int): ListeningSocket = OsListeningSocket(port, backlog)
}

private class OsListeningSocket(port: Int, backlog: Int) : ListeningSocket {
    private val fd: FileDescriptor = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0).also {
        Os.setsockoptInt(it, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1)
        Os.bind(it, InetSocketAddress(Inet4Address.getByAddress(byteArrayOf(0, 0, 0, 0)), port))
        Os.listen(it, backlog)
    }

    override val boundPort: Int
        get() = (Os.getsockname(fd) as InetSocketAddress).port

    override fun accept(): AcceptedSocket = OsAcceptedSocket(Os.accept(fd, InetSocketAddress(0)))

    override fun close() {
        runCatching { Os.close(fd) }
    }
}

private class OsAcceptedSocket(private val fd: FileDescriptor) : AcceptedSocket {
    override fun inputStream(): InputStream = FileInputStream(fd)
    override fun outputStream(): OutputStream = FileOutputStream(fd)

    override fun setSoTimeout(timeoutMs: Int) {
        Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(timeoutMs.toLong()))
    }

    override fun close() {
        runCatching { Os.close(fd) }
    }
}

/** JVM/unit-test: classic java.net server socket (binds IPv4 wildcard). */
object JavaServerSocketFactory : ServerSocketFactory {
    override fun listenV4(port: Int, backlog: Int): ListeningSocket = JavaListeningSocket(port, backlog)
}

private class JavaListeningSocket(port: Int, backlog: Int) : ListeningSocket {
    private val ss = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(Inet4Address.getByAddress(byteArrayOf(0, 0, 0, 0)), port), backlog)
    }

    override val boundPort: Int
        get() = ss.localPort

    override fun accept(): AcceptedSocket = JavaAcceptedSocket(ss.accept())

    override fun close() {
        runCatching { ss.close() }
    }
}

private class JavaAcceptedSocket(private val s: Socket) : AcceptedSocket {
    override fun inputStream(): InputStream = s.getInputStream()
    override fun outputStream(): OutputStream = s.getOutputStream()
    override fun setSoTimeout(timeoutMs: Int) {
        s.soTimeout = timeoutMs
    }

    override fun close() {
        runCatching { s.close() }
    }
}
