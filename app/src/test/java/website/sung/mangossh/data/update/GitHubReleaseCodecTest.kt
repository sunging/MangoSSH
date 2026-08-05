package website.sung.mangossh.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import website.sung.mangossh.domain.AppVersion

class GitHubReleaseCodecTest {
    @Test
    fun happyPathDecodesBothAssetsAndFields() {
        val body = """
            {
              "tag_name": "v0.2.0",
              "name": "MangoSSH 0.2.0",
              "body": "- Added things\n- Fixed things",
              "html_url": "https://github.com/sunging/MangoSSH/releases/tag/v0.2.0",
              "published_at": "2026-01-02T03:04:05Z",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "MangoSSH-v0.2.0.apk",
                  "browser_download_url": "https://github.com/sunging/MangoSSH/releases/download/v0.2.0/MangoSSH-v0.2.0.apk",
                  "size": 123456
                },
                {
                  "name": "SHA256SUMS",
                  "browser_download_url": "https://github.com/sunging/MangoSSH/releases/download/v0.2.0/SHA256SUMS",
                  "size": 200
                }
              ]
            }
        """.trimIndent()

        val release = GitHubReleaseCodec.decodeRelease(body)

        assertEquals(AppVersion(0, 2, 0), release.version)
        assertEquals("v0.2.0", release.tag)
        assertEquals("MangoSSH 0.2.0", release.title)
        assertEquals("- Added things\n- Fixed things", release.notes)
        assertEquals("https://github.com/sunging/MangoSSH/releases/tag/v0.2.0", release.htmlUrl)
        assertEquals("MangoSSH-v0.2.0.apk", release.apkAsset.name)
        assertEquals(123456L, release.apkAsset.sizeBytes)
        assertEquals("SHA256SUMS", release.checksumAsset?.name)
    }

    @Test
    fun missingAssetsArrayFailsWithAssetMissing() {
        val body = """{ "tag_name": "v0.2.0" }"""

        val error = expectFailure(body)
        assertEquals(AppUpdateFailureReason.ASSET_MISSING, error.reason)
    }

    @Test
    fun emptyAssetsArrayFailsWithAssetMissing() {
        val body = """{ "tag_name": "v0.2.0", "assets": [] }"""

        val error = expectFailure(body)
        assertEquals(AppUpdateFailureReason.ASSET_MISSING, error.reason)
    }

    @Test
    fun apkPresentWithoutChecksumAssetDecodesWithNullChecksumAsset() {
        val body = """
            {
              "tag_name": "v0.2.0",
              "assets": [
                {
                  "name": "MangoSSH-v0.2.0.apk",
                  "browser_download_url": "https://github.com/sunging/MangoSSH/releases/download/v0.2.0/MangoSSH-v0.2.0.apk",
                  "size": 123456
                }
              ]
            }
        """.trimIndent()

        val release = GitHubReleaseCodec.decodeRelease(body)

        assertNull(release.checksumAsset)
    }

    @Test
    fun draftReleaseIsRejected() {
        val body = """{ "tag_name": "v0.2.0", "draft": true, "assets": [] }"""

        val error = expectFailure(body)
        assertEquals(AppUpdateFailureReason.NO_RELEASE, error.reason)
    }

    @Test
    fun prereleaseIsRejected() {
        val body = """{ "tag_name": "v0.2.0", "prerelease": true, "assets": [] }"""

        val error = expectFailure(body)
        assertEquals(AppUpdateFailureReason.NO_RELEASE, error.reason)
    }

    @Test
    fun malformedTagNameFailsWithInvalidResponse() {
        val body = """{ "tag_name": "not-a-version", "assets": [] }"""

        val error = expectFailure(body)
        assertEquals(AppUpdateFailureReason.INVALID_RESPONSE, error.reason)
    }

    @Test
    fun overlyLongBodyIsClampedAndControlCharactersAreStrippedButNewlinesKept() {
        val longBody = "a".repeat(10_000) + "\nend"
        val body = """
            {
              "tag_name": "v0.2.0",
              "body": ${org.json.JSONObject.quote(longBody)},
              "assets": [
                { "name": "MangoSSH-v0.2.0.apk", "browser_download_url": "https://example", "size": 1 }
              ]
            }
        """.trimIndent()

        val release = GitHubReleaseCodec.decodeRelease(body)

        assertTrue(release.notes.length <= 8_000)
        assertTrue(release.notes.none { it.isISOControl() && it != '\n' && it != '\t' })
    }

    @Test
    fun untrustedHtmlUrlHostIsDropped() {
        val body = """
            {
              "tag_name": "v0.2.0",
              "html_url": "https://evil.example/v0.2.0",
              "assets": [
                { "name": "MangoSSH-v0.2.0.apk", "browser_download_url": "https://example", "size": 1 }
              ]
            }
        """.trimIndent()

        assertNull(GitHubReleaseCodec.decodeRelease(body).htmlUrl)
    }

    @Test
    fun cleartextHtmlUrlIsDropped() {
        val body = """
            {
              "tag_name": "v0.2.0",
              "html_url": "http://github.com/sunging/MangoSSH/releases/tag/v0.2.0",
              "assets": [
                { "name": "MangoSSH-v0.2.0.apk", "browser_download_url": "https://example", "size": 1 }
              ]
            }
        """.trimIndent()

        assertNull(GitHubReleaseCodec.decodeRelease(body).htmlUrl)
    }

    @Test
    fun unparsablePublishedAtIsNullNotAFailure() {
        val body = """
            {
              "tag_name": "v0.2.0",
              "published_at": "not-a-date",
              "assets": [
                { "name": "MangoSSH-v0.2.0.apk", "browser_download_url": "https://example", "size": 1 }
              ]
            }
        """.trimIndent()

        assertNull(GitHubReleaseCodec.decodeRelease(body).publishedAtEpochMillis)
    }

    @Test
    fun unknownExtraFieldsAreIgnored() {
        val body = """
            {
              "tag_name": "v0.2.0",
              "unexpected_field": { "nested": true },
              "assets": [
                { "name": "MangoSSH-v0.2.0.apk", "browser_download_url": "https://example", "size": 1, "extra": 1 }
              ]
            }
        """.trimIndent()

        val release = GitHubReleaseCodec.decodeRelease(body)
        assertEquals(AppVersion(0, 2, 0), release.version)
    }

    private fun expectFailure(body: String): AppUpdateException {
        return try {
            GitHubReleaseCodec.decodeRelease(body)
            fail("expected AppUpdateException")
            throw IllegalStateException("unreachable")
        } catch (error: AppUpdateException) {
            error
        }
    }
}
