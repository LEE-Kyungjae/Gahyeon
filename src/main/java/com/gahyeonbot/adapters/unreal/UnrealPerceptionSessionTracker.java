package com.gahyeonbot.adapters.unreal;

import java.util.concurrent.ConcurrentHashMap;

/** Process-local lifecycle gate for non-durable VAD/transcript traffic. */
public final class UnrealPerceptionSessionTracker {
    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    public Admission admit(String sessionId, String type, long generation) {
        if (sessionId == null || sessionId.isBlank() || type == null || type.isBlank() || generation < 0) {
            return Admission.INVALID_LIFECYCLE;
        }
        return sessions.computeIfAbsent(sessionId, ignored -> new SessionState())
                .admit(type, generation);
    }

    public void rollbackFinal(String sessionId, long generation) {
        SessionState state = sessions.get(sessionId);
        if (state != null) state.rollbackFinal(generation);
    }

    public void advanceGeneration(String sessionId, long generation) {
        if (sessionId == null || sessionId.isBlank() || generation < 0) return;
        sessions.computeIfAbsent(sessionId, ignored -> new SessionState())
                .advanceGeneration(generation);
    }

    public void abortVoice(String sessionId, long generation) {
        SessionState state = sessions.get(sessionId);
        if (state != null) state.abortVoice(generation);
    }

    public void releaseSession(String sessionId) {
        if (sessionId != null) sessions.remove(sessionId);
    }

    int sessionCount() {
        return sessions.size();
    }

    public enum Admission {
        ACCEPTED,
        DUPLICATE,
        STALE,
        INVALID_LIFECYCLE
    }

    private static final class SessionState {
        private long generation = -1;
        private boolean voiceSeen;
        private boolean voiceActive;
        private boolean voiceAborted;
        private boolean finalSubmitted;

        synchronized Admission admit(String type, long candidate) {
            if (candidate < generation) return Admission.STALE;
            return switch (type) {
                case "perception.voice.started" -> voiceStarted(candidate);
                case "perception.voice.ended" -> voiceEnded(candidate);
                case "perception.transcript.partial" -> partial(candidate);
                case "perception.transcript.final" -> finalTranscript(candidate);
                case "perception.user.pose" -> Admission.ACCEPTED;
                default -> Admission.INVALID_LIFECYCLE;
            };
        }

        private Admission voiceStarted(long candidate) {
            if (candidate > generation) {
                generation = candidate;
                voiceSeen = true;
                voiceActive = true;
                voiceAborted = false;
                finalSubmitted = false;
                return Admission.ACCEPTED;
            }
            if (voiceActive) return Admission.DUPLICATE;
            return Admission.INVALID_LIFECYCLE;
        }

        private Admission voiceEnded(long candidate) {
            if (candidate > generation || !voiceSeen) return Admission.INVALID_LIFECYCLE;
            if (!voiceActive) return Admission.DUPLICATE;
            voiceActive = false;
            return Admission.ACCEPTED;
        }

        private Admission partial(long candidate) {
            if (candidate > generation || !voiceSeen || !voiceActive || finalSubmitted) {
                return Admission.INVALID_LIFECYCLE;
            }
            return Admission.ACCEPTED;
        }

        private Admission finalTranscript(long candidate) {
            if (candidate > generation || !voiceSeen || voiceActive || voiceAborted) {
                return Admission.INVALID_LIFECYCLE;
            }
            if (finalSubmitted) return Admission.DUPLICATE;
            finalSubmitted = true;
            voiceActive = false;
            return Admission.ACCEPTED;
        }

        private synchronized void rollbackFinal(long candidate) {
            if (candidate == generation) finalSubmitted = false;
        }

        private synchronized void advanceGeneration(long candidate) {
            if (candidate <= generation) return;
            generation = candidate;
            voiceSeen = false;
            voiceActive = false;
            voiceAborted = false;
            finalSubmitted = false;
        }

        private synchronized void abortVoice(long candidate) {
            if (candidate != generation || finalSubmitted) return;
            voiceActive = false;
            voiceAborted = true;
        }
    }
}
