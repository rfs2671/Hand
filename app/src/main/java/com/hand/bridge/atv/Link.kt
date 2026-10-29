package com.hand.bridge.atv

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** The TV doesn't know us (or forgot us): pair again. */
class NeedsPairing(msg: String, cause: Throwable? = null) : IOException(msg, cause)

/** The typed pairing code didn't match what the TV shows. */
class WrongCode(msg: String) : IOException(msg)

/** Closing a TLS socket sends a goodbye, which Android forbids on the main thread; do it on a short-lived thread. */
internal fun closeAsync(s: java.io.Closeable?) {
    if (s == null) return
    Thread({ runCatching { s.close() } }, "atv-close").apply { isDaemon = true }.start()
}

internal object Tls {
    fun connect(ctx: SSLContext, host: String, port: Int, connectMs: Int, readMs: Int): SSLSocket {
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(host, port), connectMs)
        // The port is open from here on. If the TV then drops us mid-handshake (TLS 1.3 reports an
        // unrecognised client certificate as a reset or broken pipe) that's a rejection, not a
        // network problem, so report it as an SSL failure.
        try {
            val ssl = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            ssl.tcpNoDelay = true // small key messages must not wait to be combined with later data
            ssl.keepAlive = true
            ssl.soTimeout = readMs
            ssl.startHandshake()
            return ssl
        } catch (e: SSLException) {
            runCatching { raw.close() }
            throw e
        } catch (e: SocketTimeoutException) {
            runCatching { raw.close() }
            throw e
        } catch (e: IOException) {
            runCatching { raw.close() }
            throw SSLException("TV rejected the connection: ${e.message}", e)
        }
    }
}

/** Looks at a TV's pairing port just far enough to read its certificate (name, MAC); doesn't start pairing. */
object TvProbe {
    class Result(val info: TvInfo.Info, val sha256: String)

    fun identify(identity: Identity, host: String, port: Int = 6467, timeoutMs: Int = 4_000): Result? {
        val trust = PinTrust(null)
        return try {
            Tls.connect(identity.sslContext(trust), host, port, timeoutMs, timeoutMs).use { }
            trust.seen?.let { Result(TvInfo.parse(it), PinTrust.fingerprint(it)) }
        } catch (e: IOException) {
            null
        }
    }
}

/**
 * Pairing, step by step (blocking; call from a background thread):
 *   start()  -> TV shows a 6-character code
 *   finish() -> proves we know the code; the TV then trusts our certificate for good
 */
class AtvPairing(
    private val identity: Identity,
    private val host: String,
    private val clientName: String,
    private val port: Int = 6467
) : Closeable {
    private val trust = PinTrust(null)
    private var sock: SSLSocket? = null

    /** The TV's certificate, available after [start]. Pin its fingerprint for later connections. */
    val serverCert: X509Certificate? get() = trust.seen

    fun start() {
        val s = Tls.connect(identity.sslContext(trust), host, port, 5_000, 15_000)
        sock = s
        send(Polo.pairingRequest(clientName))
        while (true) {
            val m = next()
            when (m.kind) {
                Polo.PAIRING_REQUEST_ACK -> send(Polo.options())
                Polo.OPTIONS -> send(Polo.configuration())
                Polo.CONFIGURATION_ACK -> return
                else -> throw IOException("unexpected pairing message ${m.kind}")
            }
        }
    }

    fun finish(code: String) {
        if (!Polo.isValidCode(code)) throw WrongCode("The code is 6 letters or digits (0-9, A-F).")
        val server = trust.seen ?: throw IOException("not started")
        val hash = Polo.secretHash(identity.cert, server, code)
        if (!Polo.codeMatches(hash, code)) throw WrongCode("That code doesn't match. Check the TV and try again.")
        send(Polo.secret(hash))
        val m = next()
        if (m.kind != Polo.SECRET_ACK) throw IOException("unexpected pairing message ${m.kind}")
    }

    private fun send(payload: ByteArray) = Framing.write(sock!!.outputStream, payload)

    private fun next(): Polo.Incoming {
        val raw = try {
            Framing.read(sock!!.inputStream)
        } catch (e: SocketTimeoutException) {
            throw IOException("The TV didn't answer", e)
        } ?: throw IOException("The TV closed the connection (pairing was cancelled?)")
        val m = Polo.parse(raw)
        if (m.status == Polo.BAD_SECRET) throw WrongCode("The TV rejected the code.")
        if (m.status != Polo.STATUS_OK) throw IOException("The TV refused pairing (status ${m.status})")
        return m
    }

    override fun close() {
        closeAsync(sock)
    }
}

/** A live control connection to a paired TV. */
class AtvRemote(
    private val identity: Identity,
    private val host: String,
    private val pinSha256: String?,
    private val listener: Listener,
    private val port: Int = 6466,
    /** No message from the TV for this long (it pings every 5 s) means the link is probably dead. */
    private val staleMs: Long = STALE_MS
) : Closeable {

    interface Listener {
        fun onPower(on: Boolean) {}
        fun onVolume(level: Int, max: Int, muted: Boolean) {}
        fun onClosed(reason: String) {}
    }

    @Volatile var ready = false
        private set
    @Volatile var powered = false
        private set
    @Volatile var closed = false
        private set
    @Volatile private var lastRx = System.currentTimeMillis()
    @Volatile private var notified = false
    @Volatile private var everReady = false
    /** How long the latest key waited between being queued and being written, in ms. */
    @Volatile var lastSendDelayMs = -1.0
        private set

    private var sock: SSLSocket? = null
    private val writeLock = Any()
    private var active = Remote.WANTED
    private val started = CountDownLatch(1)
    @Volatile private var failure: Throwable? = null

    /** Blocks until the TV is ready for commands. Throws [NeedsPairing] if it doesn't recognise us. */
    fun connect(timeoutMs: Long = 10_000) {
        val s = try {
            Tls.connect(identity.sslContext(PinTrust(pinSha256)), host, port, 5_000, READ_TIMEOUT_MS)
        } catch (e: SSLException) {
            throw NeedsPairing("The TV doesn't know this phone yet.", e)
        }
        sock = s
        Thread({ readLoop(s) }, "atv-remote").apply { isDaemon = true }.start()
        if (!started.await(timeoutMs, TimeUnit.MILLISECONDS) || !ready) {
            val f = failure
            close()
            if (f is SSLException) throw NeedsPairing("The TV doesn't know this phone yet.", f)
            throw IOException("The TV didn't become ready", f)
        }
    }

    private fun readLoop(s: SSLSocket) {
        var reason = "closed"
        try {
            val input = s.inputStream
            while (!closed) {
                val raw = Framing.read(input) ?: break
                lastRx = System.currentTimeMillis()
                handle(Remote.parse(raw))
            }
        } catch (e: SocketTimeoutException) {
            reason = "no messages from the TV (the server pings every 5 s)"
            failure = e
        } catch (e: IOException) {
            reason = e.message ?: e.javaClass.simpleName
            failure = e
        } catch (e: RuntimeException) {
            reason = "unexpected ${e.javaClass.simpleName}: ${e.message}"
            failure = IOException(reason, e)
        } finally {
            ready = false
            started.countDown()
            if (!closed) {
                closed = true
                runCatching { s.close() }
                if (everReady) notifyClosed(reason)
            }
        }
    }

    private fun handle(m: PbMessage) {
        when {
            m.has(Remote.CONFIGURE) -> {
                val supported = m.message(Remote.CONFIGURE)?.int(1) ?: 0
                active = Remote.WANTED and supported
                write(Remote.configure(active))
            }
            m.has(Remote.SET_ACTIVE) -> write(Remote.setActive(active))
            m.has(Remote.PING_REQUEST) -> write(Remote.pingResponse(m.message(Remote.PING_REQUEST)?.int(1) ?: 0))
            m.has(Remote.START) -> {
                powered = (m.message(Remote.START)?.int(1) ?: 0) != 0
                val firstTime = !ready
                ready = true
                everReady = true
                if (firstTime) started.countDown()
                listener.onPower(powered)
            }
            m.has(Remote.SET_VOLUME) -> m.message(Remote.SET_VOLUME)?.let {
                listener.onVolume(it.int(7), it.int(6), it.int(8) != 0)
            }
        }
    }

    /** Name of the thread that last wrote to the socket (checked by tests: it must never be the caller's). */
    @Volatile var lastWriteThread: String? = null
        private set

    private fun write(payload: ByteArray) {
        val s = sock ?: throw IOException("not connected")
        synchronized(writeLock) {
            lastWriteThread = Thread.currentThread().name
            Framing.write(s.outputStream, payload)
        }
    }

    // Callers (a broadcast receiver, a button) run on Android's main thread, where network writes are forbidden
    // (NetworkOnMainThreadException) and a slow TV would freeze the screen. Writes go through one background thread.
    private val sender = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "atv-send").apply { isDaemon = true } }
    private val pending = java.util.concurrent.atomic.AtomicInteger()

    /**
     * A write into a dead connection succeeds locally and the key is simply lost, so before queueing
     * check that the TV has been heard from recently. If not, drop the link so the caller can reconnect.
     */
    private fun alive(): Boolean {
        if (!ready) return false
        val silent = System.currentTimeMillis() - lastRx
        if (silent > staleMs) {
            ready = false
            closed = true
            closeAsync(sock)
            notifyClosed("no word from the TV for ${silent / 1000} s")
            return false
        }
        return true
    }

    /** Tells the owner once that the link is gone (not when the owner itself closed it). */
    private fun notifyClosed(reason: String) {
        if (notified) return
        notified = true
        listener.onClosed(reason)
    }

    private fun enqueue(payload: ByteArray, count: Int = 1, intervalMs: Long = 0, repeatDirection: Int? = null): Boolean {
        if (!alive()) return false
        if (pending.get() > MAX_PENDING) return false // the TV isn't keeping up; drop rather than lag behind
        pending.incrementAndGet()
        val queuedAt = System.nanoTime()
        return try {
            sender.execute {
                try {
                    repeat(count) { n ->
                        if (n > 0 && intervalMs > 0) Thread.sleep(intervalMs)
                        write(payload)
                        if (n == 0) lastSendDelayMs = (System.nanoTime() - queuedAt) / 1e6
                    }
                }
                catch (e: InterruptedException) { /* closing */ }
                catch (e: IOException) { close() }
                catch (e: RuntimeException) { close() }
                finally { pending.decrementAndGet() }
            }
            true
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            pending.decrementAndGet()
            false
        }
    }

    /** Queues a key press (in order) and returns at once. False if not connected. Safe to call from any thread. */
    fun key(code: Int, direction: Int = Remote.DIR_SHORT): Boolean = enqueue(Remote.key(code, direction))

    /**
     * [count] presses of one key, [intervalMs] apart. A fast slide used to fire all its presses in the same
     * instant, which a TV interface can't animate; spaced out, each step is seen and scrolling looks smooth.
     */
    fun keys(code: Int, count: Int, intervalMs: Long, direction: Int = Remote.DIR_SHORT): Boolean =
        enqueue(Remote.key(code, direction), count, intervalMs)

    fun launch(link: String): Boolean = enqueue(Remote.launchApp(link))

    override fun close() {
        closed = true
        ready = false
        closeAsync(sock)
        sender.shutdown()
    }

    companion object {
        /** The TV pings every 5 s; no message for this long means the link is dead. */
        const val READ_TIMEOUT_MS = 20_000
        /** The TV pings every 5 s: one missed ping is normal jitter, more means a dead link. */
        const val STALE_MS = 8_000L
        private const val MAX_PENDING = 200
    }
}
