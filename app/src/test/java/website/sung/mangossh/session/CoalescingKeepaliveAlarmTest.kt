package website.sung.mangossh.session

import org.junit.Assert.*
import org.junit.Test

class CoalescingKeepaliveAlarmTest {
    @Test fun cancelledGenerationCannotFireNewWaiters() {
        val fires = mutableListOf<() -> Unit>()
        val alarm = CoalescingKeepaliveAlarm(KeepaliveAlarm { _, fire -> fires += fire; {} }, { 0L })
        var observed = 0
        val cancel = alarm.schedule(100) { observed += 1 }
        cancel()
        alarm.schedule(50) { observed += 10 }
        fires[0]()
        assertEquals(0, observed)
        fires[1]()
        assertEquals(10, observed)
        fires[1]()
        assertEquals(10, observed)
    }
    @Test fun oneWakeupServesAllPendingConnections() {
        val fires = mutableListOf<() -> Unit>()
        val alarm = CoalescingKeepaliveAlarm(KeepaliveAlarm { _, fire -> fires += fire; {} }, { 0L })
        var observed = 0
        alarm.schedule(50) { observed++ }
        alarm.schedule(100) { observed++ }
        assertEquals(1, fires.size)
        fires.single()()
        assertEquals(2, observed)
    }
}
