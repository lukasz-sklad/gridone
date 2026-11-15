package one.lmk.gridcraft

/** Pure JVM HTTP protocol helpers for the local Chromecast PNG mirror server. */
object CastHttpProtocol {
    data class HttpRequest(
        val method: String,
        val path: String,
    )

    fun parseRequestLine(line: String?): HttpRequest? {
        if (line.isNullOrBlank()) return null
        val parts = line.trim().split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val rawPath = parts[1]
        val cleanPath = rawPath.substringBefore('?')
        return HttpRequest(method = parts[0].uppercase(), path = cleanPath)
    }

    fun isFrameRequest(path: String): Boolean =
        path == "/frame.png" || (path.startsWith("/frame_") && path.endsWith(".png"))

    fun buildResponseHeader(
        statusCode: Int,
        statusText: String,
        contentType: String,
        contentLength: Int,
        etag: String? = null,
    ): ByteArray {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(statusCode).append(' ').append(statusText).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(contentLength).append("\r\n")
        sb.append("Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        if (!etag.isNullOrEmpty()) {
            sb.append("ETag: \"").append(etag).append("\"\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    fun buildStatusHtml(revision: Long, width: Int, height: Int): String =
        """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width,initial-scale=1" />
          <title>GridO Cast Mirror</title>
          <style>
            body { margin: 0; background: #18181B; color: #F4F4F5; font-family: sans-serif; display: grid; place-items: center; min-height: 100vh; }
            img { max-width: 100vw; max-height: 100vh; object-fit: contain; }
          </style>
        </head>
        <body>
          <img src="/frame_${revision}.png" alt="GridO (${width}x${height})" />
        </body>
        </html>
        """.trimIndent()

    fun isSiteLocalIpv4Candidate(hostAddress: String?): Boolean {
        if (hostAddress.isNullOrBlank()) return false
        if (hostAddress.contains(':') || hostAddress.startsWith("127.")) return false
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
