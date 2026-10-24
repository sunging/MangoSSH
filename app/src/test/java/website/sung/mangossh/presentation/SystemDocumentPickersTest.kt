package website.sung.mangossh.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemDocumentPickersTest {
    @Test fun platformPickerIsPreferredRegardlessOfTheDefaultHandler() {
        for (defaultPackage in listOf(null, "example.picker", "example.filemanager")) {
            assertEquals("com.android.documentsui", SystemDocumentPickers.preferredPackage(
                defaultPackage, setOf("com.android.documentsui")))
            assertEquals("com.google.android.documentsui", SystemDocumentPickers.preferredPackage(
                defaultPackage, setOf("com.google.android.documentsui")))
            assertEquals("com.android.documentsui", SystemDocumentPickers.preferredPackage(
                defaultPackage, setOf("com.google.android.documentsui", "com.android.documentsui")))
        }
    }

    @Test fun currentPlatformPickerRemainsPreferredWhenEligible() {
        val packages = setOf("com.android.documentsui", "com.google.android.documentsui")
        for (name in packages) {
            assertEquals(name, SystemDocumentPickers.preferredPackage(name, packages))
        }
    }

    @Test fun unavailableDefaultPlatformPickerFallsBackToAnEligibleAlternative() {
        assertEquals("com.android.documentsui", SystemDocumentPickers.preferredPackage(
            "com.google.android.documentsui", setOf("com.android.documentsui")))
        assertEquals("com.google.android.documentsui", SystemDocumentPickers.preferredPackage(
            "com.android.documentsui", setOf("com.google.android.documentsui")))
    }

    @Test fun missingPlatformPickersNeverSelectAnUnrelatedApplication() {
        for (packages in listOf(emptySet(), setOf("example.picker"), setOf("example.filemanager"))) {
            for (name in listOf(null, "example.picker", "com.android.documentsui", "com.google.android.documentsui")) {
                assertNull(SystemDocumentPickers.preferredPackage(name, packages))
            }
        }
    }
}
