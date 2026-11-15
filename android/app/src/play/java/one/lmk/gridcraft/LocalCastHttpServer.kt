package one.lmk.gridcraft

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight local HTTP server serving live PNG snapshots for Google Cast receivers.
 */
class LocalCastHttpServer {
    data class FrameSnapshot(
        val revision: Long,
        val width: Int,
        val height: Int,
        val png: ByteArray,
    )

    private val running = AtomicBoolean(false)
    private val latestFrame = AtomicReference<FrameSnapshot?>(null)
    private val socketRef = AtomicReference<ServerSocket?>(null)
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
        server.bind(InetSocketAddress(0))
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
        return "http://$ip:$p/frame_${snap.revision}.png"
    }

    fun stop() {
        running.set(false)
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
            val worker = Thread({ handleClient(client) }, "GridCastReq").apply {
                isDaemon = true
            }
            worker.start()
        }
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 4000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.US_ASCII))
            val reqLine = reader.readLine()
            val req = CastHttpProtocol.parseRequestLine(reqLine)
            while (true) {
                val hdr = reader.readLine() ?: break
                if (hdr.isEmpty()) break
            }

            val out = sock.getOutputStream()
            if (req == null || (req.method != "GET" && req.method != "HEAD")) {
                val body = "Bad Request".toByteArray(Charsets.UTF_8)
                out.write(CastHttpProtocol.buildResponseHeader(400, "Bad Request", "text/plain; charset=utf-8", body.size))
                out.write(body)
                out.flush()
                return
            }

            val snap = latestFrame.get()
            if (CastHttpProtocol.isFrameRequest(req.path)) {
                if (snap == null) {
                    val body = ByteArray(0)
                    out.write(CastHttpProtocol.buildResponseHeader(404, "Not Found", "text/plain", 0))
                } else {
                    out.write(
                        CastHttpProtocol.buildResponseHeader(
                            200,
                            "OK",
                            "image/png",
                            snap.png.size,
                            etag = snap.revision.toString(),
                        )
                    )
                    if (req.method == "GET") {
                        out.write(snap.png)
                    }
                }
                out.flush()
                return
            }

            val html = CastHttpProtocol.buildStatusHtml(
                revision = snap?.revision ?: 0L,
                width = snap?.width ?: 0,
                height = snap?.height ?: 0,
            ).toByteArray(Charsets.UTF_8)
            out.write(CastHttpProtocol.buildResponseHeader(200, "OK", "text/html; charset=utf-8", html.size))
            if (req.method == "GET") {
                out.write(html)
            }
            out.flush()
        }
    }

    private fun resolveLanIpv4(): String? {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallback: String? = null
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress
                        if (CastHttpProtocol.isSiteLocalIpv4Candidate(host)) {
                            return host
                        }
                        if (fallback == null) {
                            fallback = host
                        }
                    }
                }
            }
            fallback
        } catch (_: Exception) {
            null
        }
    }
}
