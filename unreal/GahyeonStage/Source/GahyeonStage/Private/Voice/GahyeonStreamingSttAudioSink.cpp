#include "Voice/GahyeonStreamingSttAudioSink.h"

#include "Misc/Guid.h"
#include "Voice/GahyeonBatchSttAudioSink.h"

#include <cstring>

FGahyeonStreamingSttAudioSink::FGahyeonStreamingSttAudioSink()
    : Runtime(96),
      BatchFallback(MakeShared<FGahyeonBatchSttAudioSink, ESPMode::ThreadSafe>())
{
}

FGahyeonStreamingSttAudioSink::~FGahyeonStreamingSttAudioSink() = default;

bool FGahyeonStreamingSttAudioSink::TryEnqueuePcm(
    const float* InAudio,
    int32 NumFrames,
    int32 NumChannels,
    int32 SampleRate,
    int64 ObservedAtMs)
{
    if (InAudio == nullptr || NumFrames <= 0 || NumChannels <= 0 || NumChannels > 8
        || SampleRate < 8'000 || SampleRate > 192'000 || ObservedAtMs < 0)
    {
        return false;
    }
    const std::size_t SampleCount = static_cast<std::size_t>(NumFrames)
        * static_cast<std::size_t>(NumChannels);
    const std::size_t ByteCount = SampleCount * sizeof(float);
    if (ByteCount == 0 || ByteCount > 131'072) return false;
    const std::uint64_t Write = PcmWrite.load(std::memory_order_relaxed);
    const std::uint64_t Read = PcmRead.load(std::memory_order_acquire);
    if (Write - Read >= MaximumPendingPcmChunks || SampleCount > MaximumSamplesPerChunk)
    {
        return false;
    }
    const std::size_t SlotIndex = static_cast<std::size_t>(Write % MaximumPendingPcmChunks);
    FPcmSlot& Slot = PcmSlots[SlotIndex];
    Slot.ObservedAtMs = ObservedAtMs;
    Slot.NumFrames = NumFrames;
    Slot.Format = {SampleRate, NumChannels, NumFrames};
    Slot.SampleCount = static_cast<int32>(SampleCount);
    std::memcpy(
        PcmSamples.data() + SlotIndex * MaximumSamplesPerChunk,
        InAudio,
        ByteCount);
    PcmWrite.store(Write + 1, std::memory_order_release);
    return true;
}

bool FGahyeonStreamingSttAudioSink::VoiceActivityStarted(
    int64 GenerationId,
    int64 ObservedAtMs)
{
    if (GenerationId < 0 || ObservedAtMs < 0) return false;
    std::scoped_lock Lock(Mutex);
    DrainCaptureLocked(ObservedAtMs);
    if (PreRoll.empty()) return false;
    const Gahyeon::StreamingSttFormat Format = PreRoll.back().Format;
    while (!PreRoll.empty()
        && (PreRoll.front().Format.SampleRate != Format.SampleRate
            || PreRoll.front().Format.Channels != Format.Channels))
    {
        PreRoll.pop_front();
    }
    const FString Guid = FGuid::NewGuid().ToString(EGuidFormats::DigitsWithHyphensLower);
    ActiveMode = Runtime.Begin(
        GenerationId,
        ObservedAtMs,
        TCHAR_TO_UTF8(*Guid),
        Format);
    if (!ActiveMode.has_value()) return false;
    ActiveFormat = Format;
    ActiveGeneration = GenerationId;
    const bool bAccepted = *ActiveMode == Gahyeon::StreamingSttMode::Streaming
        ? ReplayPreRollToStreaming(Format)
        : ReplayPreRollToBatch(GenerationId, ObservedAtMs);
    PreRoll.clear();
    return bAccepted;
}

bool FGahyeonStreamingSttAudioSink::VoiceActivityEnded(
    int64 GenerationId,
    int64 ObservedAtMs)
{
    std::scoped_lock Lock(Mutex);
    DrainCaptureLocked(ObservedAtMs);
    if (!ActiveMode.has_value() || GenerationId != ActiveGeneration) return false;
    const bool bAccepted = *ActiveMode == Gahyeon::StreamingSttMode::Streaming
        ? Runtime.End(GenerationId, ObservedAtMs)
        : BatchFallback->VoiceActivityEnded(GenerationId, ObservedAtMs);
    if (*ActiveMode == Gahyeon::StreamingSttMode::BatchFallback)
    {
        ActiveGeneration = -1;
    }
    ActiveMode.reset();
    ActiveFormat.reset();
    while (const auto Failure = Runtime.TakeFailure())
    {
        Failures.emplace_back(GenerationId, *Failure);
    }
    return bAccepted;
}

bool FGahyeonStreamingSttAudioSink::VoiceActivityCancelled(
    int64 GenerationId,
    int64 ObservedAtMs)
{
    if (GenerationId < 0 || ObservedAtMs < 0) return false;
    std::scoped_lock Lock(Mutex);
    // PCM already published by the capture callback belongs to the truncated utterance.
    PcmRead.store(PcmWrite.load(std::memory_order_acquire), std::memory_order_release);
    PreRoll.clear();
    if (!ActiveMode.has_value()) return true;
    if (GenerationId != ActiveGeneration) return false;
    const bool bBatchAccepted = *ActiveMode != Gahyeon::StreamingSttMode::BatchFallback
        || BatchFallback->VoiceActivityCancelled(GenerationId, ObservedAtMs);
    const bool bRuntimeAccepted = Runtime.Cancel(
        GenerationId,
        Gahyeon::StreamingSttCancelReason::CaptureError);
    ActiveMode.reset();
    ActiveFormat.reset();
    ActiveGeneration = -1;
    return bBatchAccepted && bRuntimeAccepted;
}

void FGahyeonStreamingSttAudioSink::SetTransportAvailable(bool bAvailable)
{
    std::scoped_lock Lock(Mutex);
    Runtime.SetStreamingAvailable(bAvailable);
}

void FGahyeonStreamingSttAudioSink::TickGameThread()
{
    std::scoped_lock Lock(Mutex);
    DrainCaptureLocked();
}

void FGahyeonStreamingSttAudioSink::TransportFailed()
{
    std::scoped_lock Lock(Mutex);
    Runtime.TransportFailed();
    while (const auto Failure = Runtime.TakeFailure())
    {
        Failures.emplace_back(ActiveGeneration, *Failure);
    }
}

bool FGahyeonStreamingSttAudioSink::ResultIngressBackpressured()
{
    std::scoped_lock Lock(Mutex);
    if (!Runtime.ResultIngressBackpressured()) return false;
    while (const auto Failure = Runtime.TakeFailure())
    {
        Failures.emplace_back(ActiveGeneration, *Failure);
    }
    return true;
}

bool FGahyeonStreamingSttAudioSink::ProviderFailed(
    int64 GenerationId,
    const FString& StreamId)
{
    std::scoped_lock Lock(Mutex);
    if (!Runtime.ProviderFailed(GenerationId, TCHAR_TO_UTF8(*StreamId))) return false;
    while (const auto Failure = Runtime.TakeFailure())
    {
        Failures.emplace_back(ActiveGeneration, *Failure);
    }
    return true;
}

std::optional<Gahyeon::StreamingSttCommand>
FGahyeonStreamingSttAudioSink::TakeCommand()
{
    std::scoped_lock Lock(Mutex);
    return Runtime.TakeCommand();
}

std::optional<TPair<int64, Gahyeon::StreamingSttFailure>>
FGahyeonStreamingSttAudioSink::TakeFailure()
{
    std::scoped_lock Lock(Mutex);
    if (Failures.empty()) return std::nullopt;
    TPair<int64, Gahyeon::StreamingSttFailure> Failure = Failures.front();
    Failures.pop_front();
    return Failure;
}

Gahyeon::StreamingSttResult FGahyeonStreamingSttAudioSink::AcceptPartial(
    int64 GenerationId,
    const FString& StreamId,
    uint64 ResultSequence,
    const FString& Text)
{
    std::scoped_lock Lock(Mutex);
    return Runtime.AcceptPartial(
        GenerationId, TCHAR_TO_UTF8(*StreamId), ResultSequence, TCHAR_TO_UTF8(*Text));
}

Gahyeon::StreamingSttResult FGahyeonStreamingSttAudioSink::AcceptFinal(
    int64 GenerationId,
    const FString& StreamId,
    uint64 ResultSequence,
    const FString& Text)
{
    std::scoped_lock Lock(Mutex);
    const Gahyeon::StreamingSttResult Result = Runtime.AcceptFinal(
        GenerationId, TCHAR_TO_UTF8(*StreamId), ResultSequence, TCHAR_TO_UTF8(*Text));
    if (Result == Gahyeon::StreamingSttResult::Accepted) ActiveGeneration = -1;
    return Result;
}

std::optional<Gahyeon::EncodedPcmUtterance>
FGahyeonStreamingSttAudioSink::TakeCompletedBatch()
{
    return BatchFallback->TakeCompleted();
}

std::optional<int64> FGahyeonStreamingSttAudioSink::TakeFailedBatchGeneration()
{
    return BatchFallback->TakeFailedGeneration();
}

void FGahyeonStreamingSttAudioSink::Reset()
{
    std::scoped_lock Lock(Mutex);
    Runtime = Gahyeon::StreamingSttClientRuntime(96);
    BatchFallback->Reset();
    PcmRead.store(PcmWrite.load(std::memory_order_acquire), std::memory_order_release);
    PreRoll.clear();
    Failures.clear();
    ActiveMode.reset();
    ActiveFormat.reset();
    ActiveGeneration = -1;
}

void FGahyeonStreamingSttAudioSink::DrainCaptureLocked(std::optional<int64> ThroughMs)
{
    while (true)
    {
        const std::uint64_t Read = PcmRead.load(std::memory_order_relaxed);
        const std::uint64_t Write = PcmWrite.load(std::memory_order_acquire);
        if (Read == Write) return;
        const std::size_t SlotIndex = static_cast<std::size_t>(Read % MaximumPendingPcmChunks);
        const FPcmSlot& Slot = PcmSlots[SlotIndex];
        if (ThroughMs.has_value() && Slot.ObservedAtMs > *ThroughMs) return;
        const float* Samples = PcmSamples.data() + SlotIndex * MaximumSamplesPerChunk;
        RoutePcmLocked(Slot, Samples);
        PcmRead.store(Read + 1, std::memory_order_release);
    }
}

bool FGahyeonStreamingSttAudioSink::RoutePcmLocked(
    const FPcmSlot& Slot,
    const float* Samples)
{
    if (!ActiveMode.has_value())
    {
        FPcmChunk Chunk;
        Chunk.ObservedAtMs = Slot.ObservedAtMs;
        Chunk.NumFrames = Slot.NumFrames;
        Chunk.Format = Slot.Format;
        Chunk.Bytes.resize(static_cast<std::size_t>(Slot.SampleCount) * sizeof(float));
        std::memcpy(Chunk.Bytes.data(), Samples, Chunk.Bytes.size());
        RetainPreRoll(MoveTemp(Chunk));
        return true;
    }
    if (*ActiveMode == Gahyeon::StreamingSttMode::BatchFallback)
    {
        return BatchFallback->TryEnqueuePcm(
            Samples,
            Slot.NumFrames,
            Slot.Format.Channels,
            Slot.Format.SampleRate,
            Slot.ObservedAtMs);
    }
    if (!ActiveFormat.has_value()
        || Slot.Format.SampleRate != ActiveFormat->SampleRate
        || Slot.Format.Channels != ActiveFormat->Channels)
    {
        Runtime.OfferFloat32Le(
            reinterpret_cast<const std::uint8_t*>(Samples),
            static_cast<std::size_t>(Slot.SampleCount) * sizeof(float),
            Slot.Format);
        while (const auto Failure = Runtime.TakeFailure())
        {
            Failures.emplace_back(ActiveGeneration, *Failure);
        }
        return false;
    }
    const bool bAccepted = Runtime.OfferFloat32Le(
        reinterpret_cast<const std::uint8_t*>(Samples),
        static_cast<std::size_t>(Slot.SampleCount) * sizeof(float),
        *ActiveFormat);
    if (!bAccepted)
    {
        while (const auto Failure = Runtime.TakeFailure())
        {
            Failures.emplace_back(ActiveGeneration, *Failure);
        }
    }
    return bAccepted;
}

void FGahyeonStreamingSttAudioSink::RetainPreRoll(FPcmChunk Chunk)
{
    const int64 LatestAt = Chunk.ObservedAtMs;
    PreRoll.push_back(MoveTemp(Chunk));
    while (PreRoll.size() > MaximumPreRollChunks
        || (!PreRoll.empty() && LatestAt - PreRoll.front().ObservedAtMs > MaximumPreRollMillis))
    {
        PreRoll.pop_front();
    }
}

bool FGahyeonStreamingSttAudioSink::ReplayPreRollToStreaming(
    const Gahyeon::StreamingSttFormat& Format)
{
    for (const FPcmChunk& Chunk : PreRoll)
    {
        if (Chunk.Format.SampleRate != Format.SampleRate
            || Chunk.Format.Channels != Format.Channels
            || !Runtime.OfferFloat32Le(
                Chunk.Bytes.data(), Chunk.Bytes.size(), Format))
        {
            while (const auto Failure = Runtime.TakeFailure())
            {
                Failures.emplace_back(ActiveGeneration, *Failure);
            }
            return false;
        }
    }
    return true;
}

bool FGahyeonStreamingSttAudioSink::ReplayPreRollToBatch(
    int64 GenerationId,
    int64 ObservedAtMs)
{
    for (const FPcmChunk& Chunk : PreRoll)
    {
        if (!BatchFallback->TryEnqueuePcm(
            reinterpret_cast<const float*>(Chunk.Bytes.data()),
            Chunk.NumFrames,
            Chunk.Format.Channels,
            Chunk.Format.SampleRate,
            Chunk.ObservedAtMs))
        {
            return false;
        }
    }
    return BatchFallback->VoiceActivityStarted(GenerationId, ObservedAtMs);
}
