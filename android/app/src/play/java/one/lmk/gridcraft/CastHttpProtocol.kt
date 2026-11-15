package one.lmk.gridcraft

import java.io.BufferedReader
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/** Pure JVM HTTP protocol helpers for the token-gated Chromecast PNG mirror server. */
object CastHttpProtocol {
    const val MAX_REQUEST_LINE_CHARS: Int = 2048
    const val MAX_HEADER_LINE_CHARS: Int = 4096
    const val MAX_HEADERS: Int = 32

    private const val HEX_DIGITS = "0123456789abcdef"
    private val SEQ_REGEX = Regex("^\\d{1,20}$")

    enum class CastHttpAction {
        GET_PNG,
        HEAD_PNG,
        OPTIONS,
        NOT_FOUND,
        BAD_REQUEST,
    }

    sealed class LineReadResult {
        data object Eof : LineReadResult()
        data object TooLong : LineReadResult()
        data class Line(val text: String) : LineReadResult()
    }

    fun generateToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_DIGITS[v ushr 4]
            out[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
        }
        return String(out)
    }

    fun buildFrameUrl(hostIp: String, port: Int, token: String, seq: Long): String? {
        if (hostIp.isBlank() || port !in 1..65535 || token.isEmpty() || seq < 0L) {
            return null
        }
        return "http://$hostIp:$port/cast/$token/$seq.png"
    }

    fun readLineBounded(reader: BufferedReader, maxChars: Int = MAX_HEADER_LINE_CHARS): LineReadResult {
        val sb = StringBuilder()
        while (true) {
            val ch = reader.read()
            if (ch < 0) {
                return if (sb.isEmpty()) LineReadResult.Eof else LineReadResult.Line(sb.toString())
            }
            if (ch == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') {
                    sb.setLength(sb.length - 1)
                }
                return LineReadResult.Line(sb.toString())
            }
            if (ch == 0 || sb.length >= maxChars) {
                return LineReadResult.TooLong
            }
            sb.append(ch.toChar())
        }
    }

    fun drainHeadersBounded(reader: BufferedReader): Boolean {
        var count = 0
        while (true) {
            when (val res = readLineBounded(reader, MAX_HEADER_LINE_CHARS)) {
                LineReadResult.Eof -> return true
                LineReadResult.TooLong -> return false
                is LineReadResult.Line -> {
                    if (res.text.isEmpty()) return true
                    count++
                    if (count > MAX_HEADERS) return false
                }
            }
        }
    }

    fun parseRequestAction(line: String?, expectedToken: String): CastHttpAction {
        if (line.isNullOrBlank() || line.length > MAX_REQUEST_LINE_CHARS || expectedToken.isEmpty()) {
            return CastHttpAction.BAD_REQUEST
        }
        if (line.any { it.code < 0x20 || it.code == 0x7F }) {
            return CastHttpAction.BAD_REQUEST
        }
        val parts = line.trim().split(' ').filter { it.isNotEmpty() }
        if (parts.size < 3 || parts.size > 3 || !parts[2].startsWith("HTTP/1.")) {
            return CastHttpAction.BAD_REQUEST
        }
        val method = parts[0].uppercase(Locale.ROOT)
        val rawTarget = parts[1]

        if (method == "OPTIONS") {
            return if (rawTarget == "*" || isAuthorizedCastPath(rawTarget, expectedToken)) {
                CastHttpAction.OPTIONS
            } else {
                CastHttpAction.NOT_FOUND
            }
        }
        if (method != "GET" && method != "HEAD") {
            return CastHttpAction.BAD_REQUEST
        }
        if (!isAuthorizedCastPath(rawTarget, expectedToken)) {
            return CastHttpAction.NOT_FOUND
        }
        return if (method == "GET") CastHttpAction.GET_PNG else CastHttpAction.HEAD_PNG
    }

    fun isAuthorizedCastPath(rawTarget: String, expectedToken: String): Boolean {
        if (expectedToken.isEmpty()) return false
        if (rawTarget.contains('\\') || rawTarget.contains('#') || rawTarget.contains("..")) {
            return false
        }
        val lowerTarget = rawTarget.lowercase(Locale.ROOT)
        if (lowerTarget.contains("%2e") || lowerTarget.contains("%2f") || lowerTarget.contains("%5c") || lowerTarget.contains('%')) {
            return false
        }
        val cleanPath = rawTarget.substringBefore('?')
        if (!cleanPath.startsWith("/cast/") || !cleanPath.endsWith(".png") || cleanPath.contains("//")) {
            return false
        }
        val trimmed = cleanPath.removePrefix("/cast/")
        val slash = trimmed.indexOf('/')
        if (slash <= 0 || slash == trimmed.lastIndex) {
            return false
        }
        val providedToken = trimmed.substring(0, slash)
        val fileName = trimmed.substring(slash + 1)
        if (fileName.contains('/')) {
            return false
        }
        val seqPart = fileName.removeSuffix(".png")
        if (!SEQ_REGEX.matches(seqPart)) {
            return false
        }
        val expectedBytes = expectedToken.toByteArray(StandardCharsets.UTF_8)
        val providedBytes = providedToken.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(expectedBytes, providedBytes)
    }

    fun sanitizeHeaderValue(value: String): String =
        buildString(value.length) {
            for (ch in value) {
                if (ch == '\r' || ch == '\n' || ch == '\u0000') {
                    break
                }
                if (ch.code >= 0x20 && ch.code != 0x7F && ch != '"') {
                    append(ch)
                }
            }
        }

    fun buildResponseHeader(
        statusCode: Int,
        statusText: String,
        contentType: String,
        contentLength: Int,
        etag: String? = null,
        allowOptions: Boolean = false,
    ): ByteArray {
        val safeStatusText = sanitizeHeaderValue(statusText)
        val safeContentType = sanitizeHeaderValue(contentType)
        val safeLength = maxOf(0, contentLength)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(statusCode).append(' ').append(safeStatusText).append("\r\n")
        sb.append("Content-Type: ").append(safeContentType).append("\r\n")
        sb.append("Content-Length: ").append(safeLength).append("\r\n")
        sb.append("Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("X-Frame-Options: DENY\r\n")
        sb.append("Content-Security-Policy: default-src 'none'; img-src 'self'\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        if (allowOptions) {
            sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        }
        if (!etag.isNullOrEmpty()) {
            val safeEtag = sanitizeHeaderValue(etag)
            if (safeEtag.isNotEmpty()) {
                sb.append("ETag: \"").append(safeEtag).append("\"\r\n")
            }
        }
        sb.append("Connection: close\r\n\r\n")
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    fun isUsableLanIpv4(addr: InetAddress): Boolean =
        addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress && !addr.isMulticastAddress

    fun selectPreferredLanIpv4(candidates: Iterable<InetAddress>): String? {
        var fallback: InetAddress? = null
        for (addr in candidates) {
            if (!isUsableLanIpv4(addr)) continue
            if (addr.isSiteLocalAddress) {
                return addr.hostAddress
            }
            if (fallback == null) {
                fallback = addr
            }
        }
        return fallback?.hostAddress
    }

    fun isSiteLocalIpv4Candidate(hostAddress: String?): Boolean {
        if (hostAddress.isNullOrBlank()) return false
        if (hostAddress.contains(':') || hostAddress.startsWith("127.") || hostAddress.startsWith("169.254.")) return false
        return hostAddress.startsWith("192.168.") ||
            hostAddress.startsWith("10.") ||
            is172Private(hostAddress)
    }

    private fun is172Private(ip: String): Boolean {
        if (!ip.startsWith("172.")) return false
        val second = ip.removePrefix("172.").substringBefore('.').toIntOrNull() ?: return false
        return second in 16..31
    }
}
