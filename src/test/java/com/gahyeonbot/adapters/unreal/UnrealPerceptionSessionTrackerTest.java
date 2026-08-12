package com.gahyeonbot.adapters.unreal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnrealPerceptionSessionTrackerTest {
    private final UnrealPerceptionSessionTracker tracker = new UnrealPerceptionSessionTracker();

    @Test
    void acceptsOnlyTheActiveVoiceGenerationAndOneFinalTranscript() {
        assertThat(tracker.admit("session-1", "perception.voice.started", 3))
                .isEqualTo(UnrealPerceptionSessionTracker.Admission.ACCEPTED);
        assertThat(tracker.admit("session-1", "perception.transcript.partial", 3))
                .isEqualTo(UnrealPerceptionSessionTracker.Admission.ACCEPTED);
        assertThat(tracker.admit("session-1", "perception.voice.ended", 3))
                .isEqualTo(UnrealPerceptionSessionTracker.Admission.ACCEPTED);
        assertThat(tracker.admit("session-1", "perception.transcript.partial", 3))
                .isEqualTo(UnrealPerceptionSessionTracker.Admission.INVALID_LIFECYCLE);
        assertThat(tracker.admit("session-1", "perception.transcript.final", 3))
                .isEqualTo(UnrealPerceptionSessionTracker.Admission.ACCEPTED);
        assertThat(tracker.admit("session-1", "perception.transcript.final", 3))
                .isEqualTo(UnrealPerceptionSessionTracker.Admission.DUPLICATE);
    }

    @Test
    void rejectsLateEventsAfterANewerVoiceGenerationStarts() {
        tracker.admit("session-1", "perception.voice.started", 4);
        tracker.admit("session-1", "perception.voice.started", 5);

        assertThat(tracker.admit("session-1", "perception.transcript.partial", 4))
                .isEqualTo(UnrealPerceptionSessionTracker.Admission.STALE);
        assertThat(tracker.admit("session-1", "perception.transcript.final", 4))
                .isEqualTo(UnrealPerceptionSessionTracker.Admission.STALE);
    }

    @Test
    void allowsFinalSubmissionToRetryAfterQueueAdmissionRollsBack() {
        tracker.admit("session-1", "perception.voice.started", 6);
        tracker.admit("session-1", "perception.voice.ended", 6);
        assertThat(tracker.admit("session-1", "perception.transcript.final", 6))
                .isEqualTo(UnrealPerceptionSessionTracker.Admission.ACCEPTED);

        tracker.rollbackFinal("session-1", 6);

        assertThat(tracker.admit("session-1", "perception.transcript.final", 6))
                .isEqualTo(UnrealPerceptionSessionTracker.Admission.ACCEPTED);
    }

    @Test
    void abortMakesSameGenerationPartialAndFinalPermanentlyInvalid() {
        tracker.admit("session-1", "perception.voice.started", 9);

        tracker.abortVoice("session-1", 9);

        assertThat(tracker.admit("session-1", "perception.transcript.partial", 9))
                .isEqualTo(UnrealPerceptionSessionTracker.Admission.INVALID_LIFECYCLE);
        assertThat(tracker.admit("session-1", "perception.transcript.final", 9))
                .isEqualTo(UnrealPerceptionSessionTracker.Admission.INVALID_LIFECYCLE);
        assertThat(tracker.admit("session-1", "perception.voice.ended", 9))
                .isEqualTo(UnrealPerceptionSessionTracker.Admission.DUPLICATE);
    }

    @Test
    void aNewTextGenerationInvalidatesThePreviousVoiceLifecycle() {
        tracker.admit("session-1", "perception.voice.started", 7);
        tracker.advanceGeneration("session-1", 8);

        assertThat(tracker.admit("session-1", "perception.transcript.partial", 7))
                .isEqualTo(UnrealPerceptionSessionTracker.Admission.STALE);
        assertThat(tracker.admit("session-1", "perception.transcript.final", 7))
                .isEqualTo(UnrealPerceptionSessionTracker.Admission.STALE);
    }
}
