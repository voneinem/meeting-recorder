package app.meetingrecorder.transcription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadPolicyTest {
    private val big = 2_800_000L
    private val little = 1_800_000L

    @Test
    fun fourBigAndFourLittleCoresGiveFour() {
        assertEquals(4, ThreadPolicy.threadCount(List(4) { big } + List(4) { little }))
    }

    @Test
    fun aSingleFrequencySocWithEightCoresIsClampedToSix() {
        assertEquals(6, ThreadPolicy.threadCount(List(8) { 2_000_000L }))
    }

    @Test
    fun coresWithin95PercentOfTheHighestCount() {
        // 0.95 * 2_800_000 = 2_660_000: the 2_700_000 core counts, the 2_600_000 and 1_800_000 cores do not.
        assertEquals(3, ThreadPolicy.threadCount(listOf(big, big, 2_700_000L, 2_600_000L, little, little)))
        // Exactly at the border counts.
        assertEquals(3, ThreadPolicy.threadCount(listOf(1_000_000L, 950_000L, 950_000L, 940_000L)))
    }

    @Test
    fun theReferencePhoneGetsTwoThreads() {
        // Gigaset E940 (MT6877): six cores at 2.0 GHz and two at 2.4 GHz. The spike measured 2 threads best.
        assertEquals(2, ThreadPolicy.threadCount(List(6) { 2_000_000L } + List(2) { 2_400_000L }))
    }

    @Test
    fun theResultIsAtLeastTwo() {
        assertEquals(2, ThreadPolicy.threadCount(listOf(big, little, little, little)))
        assertEquals(2, ThreadPolicy.threadCount(listOf(big)))
    }

    @Test
    fun aMissingFrequencyFileCountsAsOneCore() {
        assertEquals(2, ThreadPolicy.threadCount(emptyList()))
        assertEquals(2, ThreadPolicy.threadCount(listOf(null, null, null, null)))
        // Unreadable entries next to readable ones are ignored.
        assertEquals(4, ThreadPolicy.threadCount(List(4) { big } + listOf(null, null, null, null)))
    }

    @Test
    fun theSpikeOverrideIsUnsetByDefault() {
        assertNull(ThreadPolicy.SPIKE_THREAD_OVERRIDE)
    }

    @Test
    fun anOverrideWinsAndIsClampedLikeTheComputedValue() {
        assertEquals(5, ThreadPolicy.threadCount(List(8) { big }, override = 5))
        assertEquals(6, ThreadPolicy.threadCount(List(8) { big }, override = 12))
        assertEquals(2, ThreadPolicy.threadCount(List(8) { big }, override = 1))
    }

    @Test
    fun theFrequenciesAreReadFromThePerCoreFiles() {
        val files = mapOf("cpu0" to "1800000\n", "cpu1" to "2800000\n", "cpu2" to "garbage", "cpu3" to null)
        val frequencies = ThreadPolicy.readFrequencies(cpuNames = listOf("cpu0", "cpu1", "cpu2", "cpu3", "cpufreq", "idle")) { files[it] }

        assertEquals(listOf(1_800_000L, 2_800_000L, null, null), frequencies)
    }
}
