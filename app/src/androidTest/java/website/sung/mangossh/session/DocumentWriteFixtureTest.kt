package website.sung.mangossh.session

import org.junit.Assert.*
import org.junit.Test

/** Controlled provider publication reproduces the close/query race without timing sleeps. */
class DocumentWriteFixtureTest {
    private class DelayedProvider {
        var contents = ByteArray(80_000)
        var metadata = DocumentWriteMetadata(contents.size.toLong(), 100)
        var pending: DocumentWriteMetadata? = null
        var queries = 0
        fun closeWrite(bytes: ByteArray, modifiedMillis: Long) {
            contents = bytes
            pending = DocumentWriteMetadata(bytes.size.toLong(), modifiedMillis)
        }
        fun publishClose() { metadata = checkNotNull(pending).also { pending = null } }
        fun query() = metadata.also { queries++ }
    }

    private fun DocumentWriteMetadata.identity() = SourceIdentity("synthetic", size, modifiedMillis)

    @Test fun emptyWriteWaitsForDelayedSizeAndModificationTimePublication() {
        val provider = DelayedProvider()
        provider.closeWrite(ByteArray(0), 200)
        assertEquals(0, provider.contents.size)
        val prematureBaseline = provider.query().identity()
        val ready = awaitDocumentWritePublication(0, provider::query) { complete ->
            assertFalse("A closed writer can still have the old 80 KB metadata", complete())
            provider.metadata = DocumentWriteMetadata(null, 100)
            assertFalse("Unknown size cannot mean an empty file", complete())
            provider.metadata = DocumentWriteMetadata(0, null)
            assertFalse("A partially published row is not ready", complete())
            provider.publishClose()
            assertTrue(complete())
        }
        val changed = assertThrows(SourceChangedException::class.java) {
            prematureBaseline.requireMatches(provider.query().identity())
        }
        assertEquals(SourceChangeReason.SIZE, changed.reason)
        assertEquals(DocumentWriteMetadata(0, 200), ready)
        ready.identity().requireMatches(provider.query().identity())
    }

    @Test fun realChangesAfterReadinessAreStillRejectedWithoutRebasing() {
        val provider = DelayedProvider()
        provider.closeWrite(ByteArray(0), 200)
        provider.publishClose()
        val ready = awaitDocumentWritePublication(0, provider::query) { assertTrue(it()) }.identity()
        val readsAtReadiness = provider.queries
        provider.closeWrite(ByteArray(17), 300)
        provider.publishClose()
        val changedSize = assertThrows(SourceChangedException::class.java) {
            ready.requireMatches(provider.query().identity())
        }
        assertEquals(SourceChangeReason.SIZE, changedSize.reason)
        provider.closeWrite(ByteArray(0), 400)
        provider.publishClose()
        val changedTime = assertThrows(SourceChangedException::class.java) {
            ready.requireMatches(provider.query().identity())
        }
        assertEquals(SourceChangeReason.MODIFIED, changedTime.reason)
        assertEquals("Only the two production identity checks read after readiness", readsAtReadiness + 2, provider.queries)
    }

    @Test fun unpublishedMetadataPropagatesTheBoundedWaitFailure() {
        val provider = DelayedProvider()
        val timeout = IllegalStateException("synthetic readiness timeout")
        val observed = assertThrows(IllegalStateException::class.java) {
            awaitDocumentWritePublication(0, provider::query) { complete ->
                assertFalse(complete())
                throw timeout
            }
        }
        assertSame(timeout, observed)
        assertEquals(1, provider.queries)
    }
}
