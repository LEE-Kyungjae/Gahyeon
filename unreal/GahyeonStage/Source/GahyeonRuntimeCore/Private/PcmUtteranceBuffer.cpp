#include "Gahyeon/PcmUtteranceBuffer.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace Gahyeon {
namespace {

void Append16(std::vector<std::uint8_t>& Output, std::uint16_t Value) {
    Output.push_back(static_cast<std::uint8_t>(Value & 0xff));
    Output.push_back(static_cast<std::uint8_t>((Value >> 8) & 0xff));
}

void Append32(std::vector<std::uint8_t>& Output, std::uint32_t Value) {
    Output.push_back(static_cast<std::uint8_t>(Value & 0xff));
    Output.push_back(static_cast<std::uint8_t>((Value >> 8) & 0xff));
    Output.push_back(static_cast<std::uint8_t>((Value >> 16) & 0xff));
    Output.push_back(static_cast<std::uint8_t>((Value >> 24) & 0xff));
}

void AppendTag(std::vector<std::uint8_t>& Output, const char* Tag) {
    Output.insert(Output.end(), Tag, Tag + 4);
}

} // namespace

PcmUtteranceBuffer::PcmUtteranceBuffer(PcmUtteranceConfig InConfig)
    : Config(InConfig) {
    if (Config.PreRollMs < 0 || Config.MaximumDurationMs <= 0
        || Config.PreRollMs >= Config.MaximumDurationMs
        || Config.MaximumChannels <= 0) {
        throw std::invalid_argument("invalid PCM utterance buffer config");
    }
}

PcmBufferResult PcmUtteranceBuffer::Observe(
    std::span<const float> InterleavedSamples,
    std::int32_t NumFrames,
    std::int32_t InNumChannels,
    std::int32_t InSampleRate) {
    if (NumFrames <= 0 || InNumChannels <= 0 || InNumChannels > Config.MaximumChannels
        || InSampleRate < 8'000 || InSampleRate > 384'000
        || InterleavedSamples.size() != static_cast<std::size_t>(NumFrames) * InNumChannels) {
        return PcmBufferResult::Invalid;
    }
    for (const float Sample : InterleavedSamples) {
        if (!std::isfinite(Sample)) return PcmBufferResult::Invalid;
    }
    if (NumChannels != 0 && (NumChannels != InNumChannels || SampleRate != InSampleRate)) {
        if (ActiveGeneration.has_value()) {
            FormatFailed = true;
            return PcmBufferResult::FormatChangedDuringUtterance;
        }
        ResetFormat(InNumChannels, InSampleRate);
    } else if (NumChannels == 0) {
        ResetFormat(InNumChannels, InSampleRate);
    }

    if (!ActiveGeneration.has_value()) {
        PreRoll.insert(PreRoll.end(), InterleavedSamples.begin(), InterleavedSamples.end());
        const std::size_t Limit = SampleLimit(Config.PreRollMs);
        if (PreRoll.size() > Limit) {
            PreRoll.erase(PreRoll.begin(), PreRoll.end() - static_cast<std::ptrdiff_t>(Limit));
        }
        return PcmBufferResult::Accepted;
    }

    const std::size_t Limit = SampleLimit(Config.MaximumDurationMs);
    const std::size_t Remaining = Samples.size() < Limit ? Limit - Samples.size() : 0;
    const std::size_t Accepted = std::min(Remaining, InterleavedSamples.size());
    Samples.insert(Samples.end(), InterleavedSamples.begin(), InterleavedSamples.begin() +
        static_cast<std::ptrdiff_t>(Accepted));
    if (Accepted != InterleavedSamples.size()) {
        Truncated = true;
        return PcmBufferResult::DurationLimitReached;
    }
    return PcmBufferResult::Accepted;
}

bool PcmUtteranceBuffer::VoiceStarted(std::int64_t GenerationId) {
    if (GenerationId < 0 || ActiveGeneration.has_value()) return false;
    ActiveGeneration = GenerationId;
    Samples = PreRoll;
    PreRoll.clear();
    Truncated = false;
    FormatFailed = false;
    return true;
}

std::optional<EncodedPcmUtterance> PcmUtteranceBuffer::VoiceEnded(
    std::int64_t GenerationId) {
    if (!ActiveGeneration.has_value() || *ActiveGeneration != GenerationId) return std::nullopt;
    ActiveGeneration.reset();
    if (FormatFailed || NumChannels <= 0 || SampleRate <= 0 || Samples.empty()) {
        Samples.clear();
        FormatFailed = false;
        return std::nullopt;
    }
    EncodedPcmUtterance Result;
    Result.GenerationId = GenerationId;
    Result.SampleRate = SampleRate;
    Result.NumChannels = NumChannels;
    Result.DurationMs = static_cast<std::int64_t>(Samples.size() / NumChannels) * 1'000
        / SampleRate;
    Result.Truncated = Truncated;
    Result.Wav = EncodePcm16Wav(Samples, NumChannels, SampleRate);
    Samples.clear();
    Truncated = false;
    return Result;
}

void PcmUtteranceBuffer::Cancel(std::int64_t GenerationId) {
    if (ActiveGeneration.has_value() && *ActiveGeneration == GenerationId) {
        ActiveGeneration.reset();
        Samples.clear();
        Truncated = false;
        FormatFailed = false;
    }
}

std::size_t PcmUtteranceBuffer::SampleLimit(std::int64_t DurationMs) const {
    const std::uint64_t Frames = static_cast<std::uint64_t>(SampleRate)
        * static_cast<std::uint64_t>(DurationMs) / 1'000;
    const std::uint64_t Count = Frames * static_cast<std::uint64_t>(NumChannels);
    return Count > std::numeric_limits<std::size_t>::max()
        ? std::numeric_limits<std::size_t>::max()
        : static_cast<std::size_t>(Count);
}

void PcmUtteranceBuffer::ResetFormat(std::int32_t InNumChannels, std::int32_t InSampleRate) {
    NumChannels = InNumChannels;
    SampleRate = InSampleRate;
    PreRoll.clear();
    Samples.clear();
}

std::vector<std::uint8_t> PcmUtteranceBuffer::EncodePcm16Wav(
    std::span<const float> Input,
    std::int32_t InNumChannels,
    std::int32_t InSampleRate) {
    const std::uint64_t DataBytes64 = Input.size() * sizeof(std::int16_t);
    if (DataBytes64 > std::numeric_limits<std::uint32_t>::max() - 36) {
        throw std::length_error("PCM utterance exceeds WAV RIFF limit");
    }
    const std::uint32_t DataBytes = static_cast<std::uint32_t>(DataBytes64);
    std::vector<std::uint8_t> Output;
    Output.reserve(44 + DataBytes);
    AppendTag(Output, "RIFF");
    Append32(Output, 36 + DataBytes);
    AppendTag(Output, "WAVE");
    AppendTag(Output, "fmt ");
    Append32(Output, 16);
    Append16(Output, 1);
    Append16(Output, static_cast<std::uint16_t>(InNumChannels));
    Append32(Output, static_cast<std::uint32_t>(InSampleRate));
    Append32(Output, static_cast<std::uint32_t>(InSampleRate * InNumChannels * 2));
    Append16(Output, static_cast<std::uint16_t>(InNumChannels * 2));
    Append16(Output, 16);
    AppendTag(Output, "data");
    Append32(Output, DataBytes);
    for (const float Sample : Input) {
        const float Clamped = std::clamp(Sample, -1.0f, 1.0f);
        const auto Pcm = static_cast<std::int16_t>(std::lrint(
            Clamped < 0.0f ? Clamped * 32'768.0f : Clamped * 32'767.0f));
        Append16(Output, static_cast<std::uint16_t>(Pcm));
    }
    return Output;
}

} // namespace Gahyeon
