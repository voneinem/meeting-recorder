package app.meetingrecorder.core.transcription

import kotlin.test.Test
import kotlin.test.assertEquals

class RepetitionGuardTest {

    private fun seg(i: Int, text: String) = WorkSegment(i * 100L, i * 100L + 90, text)

    @Test
    fun `more than three consecutive identical segments collapse to the first`() {
        val run = (0 until 4).map { seg(it, "Thank you.") }
        assertEquals(listOf(WorkSegment(0, 390, "Thank you.")), RepetitionGuard.collapse(run))
    }

    @Test
    fun `three identical segments stay`() {
        val run = (0 until 3).map { seg(it, "Thank you.") }
        assertEquals(run, RepetitionGuard.collapse(run))
    }

    @Test
    fun `identical segments separated by a different one stay`() {
        val list = listOf(seg(0, "a"), seg(1, "a"), seg(2, "b"), seg(3, "a"), seg(4, "a"), seg(5, "b"), seg(6, "a"), seg(7, "a"))
        assertEquals(list, RepetitionGuard.collapse(list))
    }

    @Test
    fun `text is compared trimmed and lower-cased`() {
        val list = listOf(seg(0, "Okay."), seg(1, " okay. "), seg(2, "OKAY."), seg(3, "okay."))
        assertEquals(listOf(WorkSegment(0, 390, "Okay.")), RepetitionGuard.collapse(list))
    }

    @Test
    fun `the collapsed run keeps the start of the first and the end of the last segment`() {
        val list = listOf(
            WorkSegment(10, 20, "x"), WorkSegment(30, 40, "x"), WorkSegment(50, 60, "x"),
            WorkSegment(70, 80, "x"), WorkSegment(90, 150, "x"),
        )
        assertEquals(listOf(WorkSegment(10, 150, "x")), RepetitionGuard.collapse(list))
    }

    @Test
    fun `other segments around a collapsed run are untouched and an empty list stays empty`() {
        val list = listOf(seg(0, "start")) + (1..5).map { seg(it, "loop") } + seg(6, "end")
        assertEquals(listOf(seg(0, "start"), WorkSegment(100, 590, "loop"), seg(6, "end")), RepetitionGuard.collapse(list))
        assertEquals(emptyList(), RepetitionGuard.collapse(emptyList()))
    }
}
