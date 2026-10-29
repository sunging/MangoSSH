package website.sung.mangossh.security

import org.junit.Assert.*
import org.junit.Test

/** Exercises grant ownership without Android lifecycle or wall-clock timing. */
class DocumentPickerRequestsTest {
    @Test fun onlyAnUnlockedLaunchCanSuspendLocking() {
        val access = AppAccessState(true)
        val requests = DocumentPickerRequests(access)
        assertNull(requests.begin())
        assertFalse(requests.active)
        access.setLocked(false)
        val token = requests.begin()
        assertTrue(requests.active)
        assertTrue(requests.finish(token))
        assertFalse(requests.active)
        assertFalse(requests.finish(token))
    }

    @Test fun independentRequestsCannotReleaseEachOthersExemption() {
        val requests = DocumentPickerRequests(AppAccessState(false))
        val first = requests.begin()
        val second = requests.begin()
        requests.abandon(first)
        assertTrue(requests.active)
        assertFalse(requests.finish(first))
        assertTrue(requests.finish(second))
        assertFalse(requests.active)
    }

    @Test fun lockingAndUnlockingCannotReviveAnOldResult() {
        val access = AppAccessState(false)
        val requests = DocumentPickerRequests(access)
        val token = requests.begin()
        access.setLocked(true)
        access.setLocked(false)
        assertFalse(requests.active)
        assertFalse(requests.finish(token))
    }

    @Test fun restoredTokensHaveNoAuthorityInANewProcess() {
        val access = AppAccessState(false)
        val token = DocumentPickerRequests(access).begin()
        val recreated = DocumentPickerRequests(access)
        assertFalse(recreated.finish(token))
        assertFalse(recreated.active)
    }

    @Test fun failureAndInvalidationReleaseAllGrants() {
        val requests = DocumentPickerRequests(AppAccessState(false))
        requests.abandon(requests.begin())
        assertFalse(requests.active)
        val first = requests.begin()
        val second = requests.begin()
        requests.invalidate()
        assertFalse(requests.active)
        assertFalse(requests.finish(first))
        assertFalse(requests.finish(second))
    }
}
