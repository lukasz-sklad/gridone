package one.lmk.gridcraft

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSecurityPolicyTest {
    @Test
    fun sanitizeFilenameNeutralizesTraversalNullBytesAndHiddenNames() {
        assertEquals("Raport_Q3__final_.xlsx", AndroidSecurityPolicy.sanitizeFilename("../Raport Q3 (final).xlsx"))
        assertEquals("workbook.xlsx", AndroidSecurityPolicy.sanitizeFilename("///"))
        assertEquals("workbook.xlsx", AndroidSecurityPolicy.sanitizeFilename(".."))
        assertEquals("workbook.xlsx", AndroidSecurityPolicy.sanitizeFilename("foo/.."))
        assertEquals("passwd", AndroidSecurityPolicy.sanitizeFilename("..\\..\\..\\etc\\passwd"))
        assertEquals("bashrc", AndroidSecurityPolicy.sanitizeFilename(".bashrc"))
        assertEquals("evil_name.xlsx", AndroidSecurityPolicy.sanitizeFilename("evil\u0000name.xlsx"))
        assertFalse(AndroidSecurityPolicy.sanitizeFilename("....xlsx").contains(".."))
    }

    @Test
    fun sanitizeFilenameCapsOverlongNamesWhilePreservingExtension() {
        val longName = "a".repeat(300) + ".xlsx"
        val sanitized = AndroidSecurityPolicy.sanitizeFilename(longName)
        assertTrue(sanitized.length <= AndroidSecurityPolicy.MAX_FILENAME_CHARS)
        assertTrue(sanitized.endsWith(".xlsx"))
    }

    @Test
    fun allocateSafeStagingFileStaysInsideCanonicalCacheDirectory() {
        val tempRoot = createTempDirectory("gridone-stage-test").toFile()
        try {
            val stagedDir = File(tempRoot, "saf_staged")
            val staged = AndroidSecurityPolicy.allocateSafeStagingFile(
                stagingDir = stagedDir,
                displayName = "../../secret.xlsx",
                uniquePrefix = "../escape/uuid1",
            )
            assertTrue(staged.canonicalPath.startsWith(stagedDir.canonicalPath + File.separator))
            assertFalse(staged.name.contains(".."))
            assertTrue(staged.name.endsWith("secret.xlsx"))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun externalUrlAllowlistBlocksDangerousSchemesUserinfoAndDiscord() {
        assertTrue(AndroidSecurityPolicy.isAllowedExternalUrl("https://github.com/lukasz-sklad/gridone"))
        assertTrue(AndroidSecurityPolicy.isAllowedExternalUrl("http://example.org/sheet?id=1"))

        assertFalse(AndroidSecurityPolicy.isAllowedExternalUrl(null))
        assertFalse(AndroidSecurityPolicy.isAllowedExternalUrl("   "))
        assertFalse(AndroidSecurityPolicy.isAllowedExternalUrl("javascript:alert(1)"))
        assertFalse(AndroidSecurityPolicy.isAllowedExternalUrl("file:///data/data/one.lmk.gridcraft/files/secret"))
        assertFalse(AndroidSecurityPolicy.isAllowedExternalUrl("content://com.android.providers/item/1"))
        assertFalse(AndroidSecurityPolicy.isAllowedExternalUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(AndroidSecurityPolicy.isAllowedExternalUrl("data:text/html,<script>alert(1)</script>"))
        assertFalse(AndroidSecurityPolicy.isAllowedExternalUrl("https://github.com@evil.example/phish"))
        assertFalse(AndroidSecurityPolicy.isAllowedExternalUrl("https://evil.com\r\nX-Injected: 1"))
        assertFalse(AndroidSecurityPolicy.isAllowedExternalUrl("https://example.com/" + "a".repeat(2100)))

        assertFalse(AndroidSecurityPolicy.isAllowedExternalUrl("https://discord.gg/artcraft"))
        assertFalse(AndroidSecurityPolicy.isAllowedExternalUrl("https://discord.com/invite/abc"))
        assertFalse(AndroidSecurityPolicy.isAllowedExternalUrl("https://ptb.discord.com/channels/1"))
        assertFalse(AndroidSecurityPolicy.isAllowedExternalUrl("https://cdn.discordapp.com/attachments/1/2/a.xlsx"))
    }
}
