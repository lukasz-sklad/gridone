package one.lmk.gridcraft

import java.io.File
import java.net.URI
import java.util.Locale
import java.util.UUID

/** Pure JVM security validators for SAF file staging and outbound URL launching. */
object AndroidSecurityPolicy {
    const val DEFAULT_FILENAME: String = "workbook.xlsx"
    const val MAX_FILENAME_CHARS: Int = 128
    const val MAX_URL_LENGTH: Int = 2048

    private val BLOCKED_DISCORD_HOSTS = setOf(
        "discord.gg",
        "discord.com",
        "discordapp.com",
        "discordapp.net",
    )

    private val ALLOWED_FILENAME_REGEX = Regex("[^A-Za-z0-9._\\-]")

    /**
     * Normalizes an untrusted SAF display name into a safe single path component inside
     * the app's private cache directory.
     */
    fun sanitizeFilename(raw: String?): String {
        if (raw.isNullOrBlank()) return DEFAULT_FILENAME
        val segment = raw
            .replace('\u0000', '_')
            .trimEnd('/', '\\', ' ', '\t', '\r', '\n')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        if (segment.isEmpty() || segment == "." || segment == "..") {
            return DEFAULT_FILENAME
        }

        var cleaned = ALLOWED_FILENAME_REGEX.replace(segment, "_")
        while (cleaned.contains("..")) {
            cleaned = cleaned.replace("..", "_")
        }
        cleaned = cleaned.trimStart('.', '_', '-')
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
            return DEFAULT_FILENAME
        }

        return if (cleaned.length <= MAX_FILENAME_CHARS) {
            cleaned
        } else {
            truncatePreservingExtension(cleaned, MAX_FILENAME_CHARS)
        }
    }

    /**
     * Allocates a staging file inside [stagingDir] and verifies via canonical paths that the
     * target cannot escape [stagingDir].
     */
    fun allocateSafeStagingFile(
        stagingDir: File,
        displayName: String?,
        uniquePrefix: String = UUID.randomUUID().toString(),
    ): File {
        stagingDir.mkdirs()
        val safePrefix = uniquePrefix
            .replace(Regex("[^A-Za-z0-9_-]"), "")
            .ifEmpty { UUID.randomUUID().toString() }
        val safeName = sanitizeFilename(displayName)
        val candidate = File(stagingDir, "${safePrefix}_$safeName")

        val canonicalDir = stagingDir.canonicalFile
        val canonicalTarget = candidate.canonicalFile
        val dirPrefix = if (canonicalDir.path.endsWith(File.separator)) {
            canonicalDir.path
        } else {
            canonicalDir.path + File.separator
        }
        if (!canonicalTarget.path.startsWith(dirPrefix)) {
            throw SecurityException("Staged path escapes cache directory")
        }
        return canonicalTarget
    }

    /**
     * Parses and validates an outbound URL before handing it to Android `ACTION_VIEW`.
     * Rejects non-HTTP(S) schemes, control/whitespace characters, userinfo (`user@host`),
     * blocked Discord domains, and overly long strings.
     */
    fun parseAllowedExternalUri(rawUrl: String?): URI? {
        if (rawUrl.isNullOrBlank() || rawUrl.length > MAX_URL_LENGTH) return null
        if (rawUrl.any { it.isWhitespace() || it.code <= 0x1F || it.code == 0x7F }) {
            return null
        }
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "https" && scheme != "http") return null
        if (!uri.userInfo.isNullOrEmpty() || !uri.rawUserInfo.isNullOrEmpty()) return null
        val rawAuth = uri.rawAuthority ?: return null
        if (rawAuth.contains('@')) return null

        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        if (host.isEmpty()) return null
        if (isBlockedHost(host)) return null
        return uri
    }

    fun isAllowedExternalUrl(rawUrl: String?): Boolean = parseAllowedExternalUri(rawUrl) != null

    private fun isBlockedHost(host: String): Boolean =
        BLOCKED_DISCORD_HOSTS.any { blocked ->
            host == blocked || host.endsWith(".$blocked")
        }

    private fun truncatePreservingExtension(name: String, maxLen: Int): String {
        val dot = name.lastIndexOf('.')
        if (dot > 0 && dot < name.length - 1) {
            val ext = name.substring(dot)
            if (ext.length in 2..12 && ext.length < maxLen) {
                val stemMax = maxLen - ext.length
                val stem = name.substring(0, stemMax).trimEnd('.', '_', '-')
                if (stem.isNotEmpty()) {
                    return stem + ext
                }
            }
        }
        return name.take(maxLen)
    }
}
