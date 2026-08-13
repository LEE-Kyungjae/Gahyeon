#pragma once

#include <cstdint>
#include <optional>
#include <span>
#include <vector>

namespace Gahyeon {

struct PcmUtteranceConfig {
    std::int64_t PreRollMs = 300;
    std::int64_t MaximumDurationMs = 30'000;
    std::int32_t MaximumChannels = 8;
};

enum class PcmBufferResult {
    Accepted,
    Invalid,
    FormatChangedDuringUtterance,
    DurationLimitReached
};

struct EncodedPcmUtterance {
    std::int64_t GenerationId = 0;
    std::int32_t SampleRate = 0;
    std::int32_t NumChannels = 0;
    std::int64_t DurationMs = 0;
    bool Truncated = false;
    std::vector<std::uint8_t> Wav;
};

/** Worker-owned bounded utterance assembler. It is intentionally not internally locking. */
class GAHYEON_RUNTIME_CORE_API PcmUtteranceBuffer final {
public:
    explicit PcmUtteranceBuffer(PcmUtteranceConfig Config = {});

    PcmBufferResult Observe(
        std::span<const float> InterleavedSamples,
        std::int32_t NumFrames,
        std::int32_t NumChannels,
        std::int32_t SampleRate);
    bool VoiceStarted(std::int64_t GenerationId);
    std::optional<EncodedPcmUtterance> VoiceEnded(std::int64_t GenerationId);
    void Cancel(std::int64_t GenerationId);

    bool IsActive() const { return ActiveGeneration.has_value(); }
    std::size_t RetainedSampleCount() const { return Samples.size() + PreRoll.size(); }

private:
    static std::vector<std::uint8_t> EncodePcm16Wav(
        std::span<const float> Samples,
        std::int32_t NumChannels,
        std::int32_t SampleRate);
    std::size_t SampleLimit(std::int64_t DurationMs) const;
    void ResetFormat(std::int32_t NumChannels, std::int32_t SampleRate);

    PcmUtteranceConfig Config;
    std::optional<std::int64_t> ActiveGeneration;
    std::int32_t NumChannels = 0;
    std::int32_t SampleRate = 0;
    std::vector<float> PreRoll;
    std::vector<float> Samples;
    bool Truncated = false;
    bool FormatFailed = false;
};

} // namespace Gahyeon
