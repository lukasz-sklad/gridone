package one.lmk.gridcraft

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight token-gated local HTTP server serving live PNG snapshots for Google Cast receivers.
 * Serves only `/cast/<ephemeral-token>/<rev>.png` and rejects all unauthenticated requests with 404.
 */
class LocalCastHttpServer {
    companion object {
        private const val MAX_CONCURRENT_CLIENTS = 8
        private const val MAX_PNG_BYTES = 16 * 1024 * 1024
    }

    data class FrameSnapshot(
        val revision: Long,
        val width: Int,
        val height: Int,
        val png: ByteArray,
    )

    private val running = AtomicBoolean(false)
    private val activeClients = AtomicInteger(0)
    private val latestFrame = AtomicReference<FrameSnapshot?>(null)
    private val socketRef = AtomicReference<ServerSocket?>(null)

    @Volatile
    private var token: String = CastHttpProtocol.generateToken()
    private var serverThread: Thread? = null

    val port: Int
        get() = socketRef.get()?.localPort ?: 0

    fun ensureStarted(): Int {
        val existing = socketRef.get()
        if (existing != null && !existing.isClosed && running.get()) {
            return existing.localPort
        }
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddressPlaceholder.anyEphemeral())
        token = CastHttpProtocol.generateToken()
        socketRef.set(server)
        running.set(true)
        val thread = Thread(::acceptLoop, "GridCastHttp").apply {
            isDaemon = true
            start()
        }
        serverThread = thread
        return server.localPort
    }

    fun updateFrame(pngBytes: ByteArray, width: Int, height: Int): Long {
        if (pngBytes.isEmpty() || pngBytes.size > MAX_PNG_BYTES) {
            return latestFrame.get()?.revision ?: 0L
        }
        val nextRev = (latestFrame.get()?.revision ?: 0L) + 1L
        latestFrame.set(FrameSnapshot(nextRev, width, height, pngBytes))
        return nextRev
    }

    fun clearFrame() {
        latestFrame.set(null)
    }

    fun currentFrameUrl(): String? {
        val snap = latestFrame.get() ?: return null
        val p = port
        if (p <= 0) return null
        val ip = resolveLanIpv4() ?: return null
        return CastHttpProtocol.buildFrameUrl(ip, p, token, snap.revision)
    }

    fun stop() {
        running.set(false)
        token = CastHttpProtocol.generateToken()
        latestFrame.set(null)
        try {
            socketRef.getAndSet(null)?.close()
        } catch (_: Exception) {
        }
        serverThread = null
    }

    private fun acceptLoop() {
        while (running.get()) {
            val server = socketRef.get() ?: break
            val client = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            if (activeClients.incrementAndGet() > MAX_CONCURRENT_CLIENTS) {
                activeClients.decrementAndGet()
                runCatching { client.close() }
                continue
            }
            val worker = Thread(
                {
                    try {
                        handleClient(client)
                    } finally {
                        activeClients.decrementAndGet()
                    }
                },
                "GridCastReq",
            ).apply {
                isDaemon = true
            }
            worker.start()
        }
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 4000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.US_ASCII))
            val out = sock.getOutputStream()

            val reqLineRes = CastHttpProtocol.readLineBounded(reader, CastHttpProtocol.MAX_REQUEST_LINE_CHARS)
            val reqLine = when (reqLineRes) {
                CastHttpProtocol.LineReadResult.Eof -> return
                CastHttpProtocol.LineReadResult.TooLong -> {
                    val body = "Bad Request".toByteArray(Charsets.UTF_8)
                    out.write(CastHttpProtocol.buildResponseHeader(400, "Bad Request", "text/plain; charset=utf-8", body.size))
                    out.write(body)
                    out.flush()
                    return
                }
                is CastHttpProtocol.LineReadResult.Line -> reqLineRes.text
            }

            if (!CastHttpProtocol.drainHeadersBounded(reader)) {
                val body = "Request Header Fields Too Large".toByteArray(Charsets.UTF_8)
                out.write(CastHttpProtocol.buildResponseHeader(431, "Request Header Fields Too Large", "text/plain; charset=utf-8", body.size))
                out.write(body)
                out.flush()
                return
            }

            when (CastHttpProtocol.parseRequestAction(reqLine, token)) {
                CastHttpProtocol.CastHttpAction.BAD_REQUEST -> {
                    val body = "Bad Request".toByteArray(Charsets.UTF_8)
                    out.write(CastHttpProtocol.buildResponseHeader(400, "Bad Request", "text/plain; charset=utf-8", body.size))
                    out.write(body)
                }
                CastHttpProtocol.CastHttpAction.NOT_FOUND -> {
                    val body = "Not Found".toByteArray(Charsets.UTF_8)
                    out.write(CastHttpProtocol.buildResponseHeader(404, "Not Found", "text/plain; charset=utf-8", body.size))
                    out.write(body)
                }
                CastHttpProtocol.CastHttpAction.OPTIONS -> {
                    out.write(
                        CastHttpProtocol.buildResponseHeader(
                            204,
                            "No Content",
                            "text/plain; charset=utf-8",
                            0,
                            allowOptions = true,
                        ),
                    )
                }
                CastHttpProtocol.CastHttpAction.HEAD_PNG,
                CastHttpProtocol.CastHttpAction.GET_PNG -> {
                    val snap = latestFrame.get()
                    if (snap == null) {
                        out.write(CastHttpProtocol.buildResponseHeader(404, "Not Found", "text/plain; charset=utf-8", 0))
                    } else {
                        out.write(
                            CastHttpProtocol.buildResponseHeader(
                                200,
                                "OK",
                                "image/png",
                                snap.png.size,
                                etag = snap.revision.toString(),
                            ),
                        )
                        if (reqLine.trim().startsWith("GET", ignoreCase = true)) {
                            out.write(snap.png)
                        }
                    }
                }
            }
            out.flush()
        }
    }

    private fun resolveLanIpv4(): String? {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            val candidates = mutableListOf<InetAddress>()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    candidates.add(addrs.nextElement())
                }
            }
            CastHttpProtocol.selectPreferredLanIpv4(candidates)
        } catch (_: Exception) {
            null
        }
    }
}

private object InetSocketAddressPlaceholder {
    fun anyEphemeral() = java.net.InetSocketAddress(0)
}
