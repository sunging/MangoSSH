package website.sung.mangossh.data.tsnet

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidTsnetStateStoreInstrumentedTest {
    @Test
    fun encryptsStateRecoversFromTamperingAndRetainsInstallName() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val directory = File(base.cacheDir, "tsnet-state-$id").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        val stateName = "state.bin"
        val store = AndroidTsnetStateStore(
            context = context,
            keyAlias = "website.sung.mangossh.test-tsnet-$id",
            stateFileName = stateName,
            nodeNameFileName = "node-name",
        )

        try {
            val nodeName = store.nodeName()
            store.writeState("profile", byteArrayOf(1, 2, 3, 4))
            store.markEnrolled()
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), store.readState("profile"))
            assertTrue(store.hasEnrolledIdentity())

            val encrypted = File(directory, stateName)
            val plaintext = encrypted.readBytes().decodeToString()
            assertTrue(!plaintext.contains("profile"))
            RandomAccessFile(encrypted, "rw").use { file ->
                file.seek(file.length() - 1)
                val lastByte = file.read()
                file.seek(file.length() - 1)
                file.write(lastByte xor 0x01)
            }

            assertEquals(0, store.readState("profile").size)
            assertTrue(!store.hasEnrolledIdentity())
            assertEquals(nodeName, store.nodeName())
        } finally {
            store.clearIdentity()
            directory.deleteRecursively()
        }
    }
}
