package website.sung.mangossh.session

import java.io.File
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class StagingBudgetTest {
    @Test fun reservationsCannotDoubleSpendAndReleasedReservationsCanBeReused() {
        val budget = StagingBudget({ 100 }, 10)
        val first = File("synthetic-first")
        val second = File("synthetic-second")
        budget.reserve(first, 60)
        assertThrows(StagingSpaceException::class.java) { budget.reserve(second, 40) }
        budget.release(first)
        budget.reserve(second, 90)
    }
    @Test fun writesConvertReservationsToDiskUsageWithoutDoubleCounting() {
        var free = 100L
        val budget = StagingBudget({ free }, 10)
        val first = File("synthetic-first")
        budget.reserve(first, 60)
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) { super.write(bytes, offset, length); free -= length }
        }
        budget.output(first, output).write(ByteArray(20))
        budget.reserve(File("synthetic-second"), 30)
        assertEquals(20, output.size())
    }
    @Test fun shrinkingFreeSpaceIsDetectedBeforeWriting() {
        var free = 100L
        val budget = StagingBudget({ free }, 10)
        val file = File("synthetic-first")
        budget.reserve(file, 60)
        free = 30
        val output = ByteArrayOutputStream()
        assertThrows(StagingSpaceException::class.java) { budget.output(file, output).write(ByteArray(10)) }
        assertEquals(0, output.size())
    }
}
