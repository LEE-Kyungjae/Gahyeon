#pragma once

#include "Gahyeon/SpeechQueue.h"
#include "Gahyeon/LatencyTrace.h"

#include <optional>
#include <string>
#include <vector>

namespace Gahyeon {

struct LipSyncConfig {
    double NoiseFloor = 0.015;
    double AmplitudeGain = 7.5;
    Millis AttackMs = 35;
    Millis ReleaseMs = 80;
    Millis VisemeAttackMs = 25;
    Millis VisemeReleaseMs = 35;
    std::size_t MaxVisemeCues = 256;
};

struct LipSyncSample {
    bool Active = false;
    bool UsingTimeline = false;
    std::string PrimaryViseme;
    double PrimaryWeight = 0.0;
    std::string SecondaryViseme;
    double SecondaryWeight = 0.0;
    double JawOpen = 0.0;
};

enum class LipSyncPrepareResult {
    Accepted,
    Stale,
    Invalid,
};

/** Audio-device-position-driven viseme sampler with an amplitude-only fallback. */
class GAHYEON_RUNTIME_CORE_API LipSyncRuntime {
public:
    explicit LipSyncRuntime(
        LipSyncConfig config = {},
        LatencyTrace* latency = nullptr);

    void SetGeneration(Generation generation);
    LipSyncPrepareResult BeginPlayback(const PreparedSpeech& speech);
    LipSyncSample Sample(Millis audioPositionMs, double normalizedRms);
    /** Records a cue only after the renderer has applied its visible face control. */
    bool ConfirmVisemePresented(
        const std::string& semantic,
        Millis audioPositionMs);
    bool EndPlayback(const std::string& utteranceId);

    Generation CurrentGeneration() const;
    bool IsActive() const;

private:
    static bool ValidCue(const VisemeCue& cue);
    static double Clamp01(double value);
    double CueInfluence(const VisemeCue& cue, Millis audioPositionMs) const;

    LipSyncConfig config_;
    Generation generation_ = 0;
    std::string utteranceId_;
    std::vector<VisemeCue> cues_;
    std::optional<Millis> lastAudioPositionMs_;
    double smoothedAmplitude_ = 0.0;
    LipSyncSample lastSample_;
    LatencyTrace* latency_ = nullptr;
    std::vector<bool> observedCues_;
};

} // namespace Gahyeon
