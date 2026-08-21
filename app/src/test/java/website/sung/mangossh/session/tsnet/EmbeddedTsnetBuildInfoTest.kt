package website.sung.mangossh.session.tsnet

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the generated-source wiring in `app/build.gradle.kts`: the
 * [EmbeddedTsnetBuildInfo.TAILSCALE_VERSION] constant shown in Settings must
 * always match the vendored module's own VERSION.txt, since that is the
 * source gomobile actually compiles into the embedded tsnet bridge.
 */
class EmbeddedTsnetBuildInfoTest {
    @Test
    fun matchesTheVendoredTailscaleVersionFile() {
        val versionFile = locateRepositoryRoot()
            .resolve("native/tsnetbridge/vendor/tailscale.com/VERSION.txt")
        val vendoredVersion = Files.readString(versionFile).trim()

        assertEquals(vendoredVersion, EmbeddedTsnetBuildInfo.TAILSCALE_VERSION)
    }

    private fun locateRepositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(5) {
            if (Files.isRegularFile(current.resolve("native/tsnetbridge/vendor/modules.txt"))) {
                return current
            }
            current = current.parent ?: return@repeat
        }
        error("Unable to locate the repository root from ${System.getProperty("user.dir")}")
    }
}
