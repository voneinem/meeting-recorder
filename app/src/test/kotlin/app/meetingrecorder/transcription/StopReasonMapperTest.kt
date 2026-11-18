package app.meetingrecorder.transcription

import app.meetingrecorder.core.model.FailureReason
import kotlin.test.Test
import kotlin.test.assertEquals

class StopReasonMapperTest {
    @Test
    fun theForegroundServiceTimeoutOfAndroid15IsAnInterruptionThatCanResume() {
        assertEquals(StopOutcome.Fail(FailureReason.INTERRUPTED, resumable = true), StopReasonMapper.map(StopReasonMapper.FOREGROUND_SERVICE_TIMEOUT))
    }

    @Test
    fun dozeQuotaAndConstraintStopsAreInterruptionsThatCanResume() {
        for (reason in listOf(
            StopReasonMapper.CONSTRAINT_CHARGING, StopReasonMapper.QUOTA, StopReasonMapper.BACKGROUND_RESTRICTION,
            StopReasonMapper.DEVICE_STATE, StopReasonMapper.TIMEOUT, StopReasonMapper.PREEMPT,
        )) {
            assertEquals(StopOutcome.Fail(FailureReason.INTERRUPTED, resumable = true), StopReasonMapper.map(reason), "reason $reason")
        }
    }

    @Test
    fun aProcessKillThatLeavesNoReasonIsAnInterruption() {
        assertEquals(StopOutcome.Fail(FailureReason.INTERRUPTED, resumable = true), StopReasonMapper.map(StopReasonMapper.NOT_STOPPED))
    }

    @Test
    fun aCancelByTheAppMeansDiscard() {
        assertEquals(StopOutcome.Discard, StopReasonMapper.map(StopReasonMapper.CANCELLED_BY_APP))
    }

    @Test
    fun aRecordingStartMapsToRecordingStarted() {
        assertEquals(StopOutcome.Fail(FailureReason.RECORDING_STARTED, resumable = true), StopReasonMapper.forRecordingStart())
    }

    @Test
    fun anUnknownReasonIsAnInterruption() {
        assertEquals(StopOutcome.Fail(FailureReason.INTERRUPTED, resumable = true), StopReasonMapper.map(-12345))
    }

    @Test
    fun theStopReasonsOfTheJobMapToTheSameOutcomes() {
        assertEquals(StopOutcome.Discard, StopReasonMapper.of(StopReason.USER_CANCEL))
        assertEquals(StopOutcome.Fail(FailureReason.RECORDING_STARTED, true), StopReasonMapper.of(StopReason.RECORDING_STARTED))
        assertEquals(StopOutcome.Fail(FailureReason.INTERRUPTED, true), StopReasonMapper.of(StopReason.SYSTEM))
    }
}
