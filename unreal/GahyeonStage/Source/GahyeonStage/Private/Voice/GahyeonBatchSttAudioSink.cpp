#include "Voice/GahyeonBatchSttAudioSink.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cstring>
#include <deque>
#include <limits>
#include <mutex>
#include <semaphore>
#include <thread>
#include <vector>

namespace
{
enum class EBatchSttLifecycleType : uint8
{
    Started,
    Ended
};

struct FBatchSttLifecycleEvent
{
    EBatchSttLifecycleType Type = EBatchSttLifecycleType::Started;
    int64 GenerationId = 0;
    int64 ObservedAtMs = 0;
};

struct FBatchPcmSlot
{
    int64 ObservedAtMs = 0;
    int32 NumFrames = 0;
    int32 NumChannels = 0;
    int32 SampleRate = 0;
    int32 SampleCount = 0;
};
}

struct FGahyeonBatchSttAudioSink::FImpl
{
    static constexpr std::size_t MaximumPendingPcmChunks = 256;
    static constexpr std::size_t MaximumSamplesPerChunk = 32'768;
    static constexpr std::size_t MaximumPendingLifecycleEvents = 16;
    static constexpr std::size_t MaximumCompletedUtterances = 4;

    FImpl()
        : PcmSamples(MaximumPendingPcmChunks * MaximumSamplesPerChunk),
          Worker([this] { Run(); })
    {
    }

    ~FImpl()
    {
        bStopping.store(true, std::memory_order_release);
        WorkAvailable.release();
        if (Worker.joinable()) Worker.join();
    }

    bool EnqueuePcm(
        const float* InAudio,
        int32 NumFrames,
        int32 NumChannels,
        int32 SampleRate,
        int64 ObservedAtMs)
    {
        const std::size_t SampleCount = static_cast<std::size_t>(NumFrames)
            * static_cast<std::size_t>(NumChannels);
        if (SampleCount == 0 || SampleCount > MaximumSamplesPerChunk) return false;
        const std::uint64_t Write = PcmWrite.load(std::memory_order_relaxed);
        const std::uint64_t Read = PcmRead.load(std::memory_order_acquire);
        if (Write - Read >= MaximumPendingPcmChunks)
        {
            ++DroppedPcmChunks;
            return false;
        }
        const std::size_t SlotIndex = static_cast<std::size_t>(Write % MaximumPendingPcmChunks);
        FBatchPcmSlot& Slot = PcmSlots[SlotIndex];
        Slot.ObservedAtMs = ObservedAtMs;
        Slot.NumFrames = NumFrames;
        Slot.NumChannels = NumChannels;
        Slot.SampleRate = SampleRate;
        Slot.SampleCount = static_cast<int32>(SampleCount);
        std::memcpy(
            PcmSamples.data() + SlotIndex * MaximumSamplesPerChunk,
            InAudio,
            SampleCount * sizeof(float));
        PcmWrite.store(Write + 1, std::memory_order_release);
        WorkAvailable.release();
        return true;
    }

    bool EnqueueLifecycle(FBatchSttLifecycleEvent Event)
    {
        {
            std::scoped_lock Lock(StateMutex);
            if (Lifecycle.size() >= MaximumPendingLifecycleEvents)
            {
                DroppedLifecycleEvents.fetch_add(
                    static_cast<int32>(Lifecycle.size() + 1), std::memory_order_relaxed);
                Lifecycle.clear();
                bResetRequested.store(true, std::memory_order_release);
                WorkAvailable.release();
                return false;
            }
            Lifecycle.push_back(Event);
        }
        WorkAvailable.release();
        return true;
    }

    std::optional<Gahyeon::EncodedPcmUtterance> TakeCompleted()
    {
        std::scoped_lock Lock(StateMutex);
        if (Completed.empty()) return std::nullopt;
        auto Result = MoveTemp(Completed.front());
        Completed.pop_front();
        return Result;
    }

    std::optional<int64> TakeFailedGeneration()
    {
        std::scoped_lock Lock(StateMutex);
        if (FailedGenerations.empty()) return std::nullopt;
        const int64 Generation = FailedGenerations.front();
        FailedGenerations.pop_front();
        return Generation;
    }

    void Reset()
    {
        ResetSequence.fetch_add(1, std::memory_order_acq_rel);
        // The capture callback is the sole producer. Moving read to the current
        // published write position makes all already-published PCM unreachable.
        PcmRead.store(PcmWrite.load(std::memory_order_acquire), std::memory_order_release);
        {
            std::scoped_lock Lock(StateMutex);
            Lifecycle.clear();
            Completed.clear();
            FailedGenerations.clear();
        }
        bResetRequested.store(true, std::memory_order_release);
        WorkAvailable.release();
    }

    bool HasPcm() const
    {
        return PcmRead.load(std::memory_order_relaxed)
            != PcmWrite.load(std::memory_order_acquire);
    }

    bool HasLifecycle() const
    {
        std::scoped_lock Lock(StateMutex);
        return !Lifecycle.empty();
    }

    bool PopPcm(Gahyeon::PcmUtteranceBuffer& Buffer, std::optional<int64> ThroughMs = std::nullopt)
    {
        const std::uint64_t Read = PcmRead.load(std::memory_order_relaxed);
        const std::uint64_t Write = PcmWrite.load(std::memory_order_acquire);
        if (Read == Write) return false;
        const std::size_t SlotIndex = static_cast<std::size_t>(Read % MaximumPendingPcmChunks);
        const FBatchPcmSlot& Slot = PcmSlots[SlotIndex];
        if (ThroughMs.has_value() && Slot.ObservedAtMs > *ThroughMs) return false;
        const float* Samples = PcmSamples.data() + SlotIndex * MaximumSamplesPerChunk;
        Buffer.Observe(
            std::span<const float>(Samples, static_cast<std::size_t>(Slot.SampleCount)),
            Slot.NumFrames,
            Slot.NumChannels,
            Slot.SampleRate);
        PcmRead.store(Read + 1, std::memory_order_release);
        return true;
    }

    bool PopLifecycle(FBatchSttLifecycleEvent& OutEvent)
    {
        std::scoped_lock Lock(StateMutex);
        if (Lifecycle.empty()) return false;
        OutEvent = Lifecycle.front();
        Lifecycle.pop_front();
        return true;
    }

    void CompleteIfCurrent(
        Gahyeon::EncodedPcmUtterance Utterance,
        std::uint64_t WorkSequence)
    {
        std::scoped_lock Lock(StateMutex);
        if (WorkSequence != ResetSequence.load(std::memory_order_acquire)) return;
        if (Completed.size() >= MaximumCompletedUtterances)
        {
            Completed.pop_front();
            ++DroppedCompletedUtterances;
        }
        Completed.push_back(MoveTemp(Utterance));
    }

    void Run()
    {
        Gahyeon::PcmUtteranceBuffer Buffer;
        while (true)
        {
            WorkAvailable.acquire();
            if (bResetRequested.exchange(false, std::memory_order_acq_rel))
            {
                Buffer = Gahyeon::PcmUtteranceBuffer();
            }
            if (bStopping.load(std::memory_order_acquire)
                && !HasPcm() && !HasLifecycle()) return;

            FBatchSttLifecycleEvent LifecycleEvent;
            if (PopLifecycle(LifecycleEvent))
            {
                const std::uint64_t WorkSequence =
                    ResetSequence.load(std::memory_order_acquire);
                // Preserve the audio boundary even though the Game Thread observes
                // VAD later than the capture callback that produced the PCM.
                while (PopPcm(Buffer, LifecycleEvent.ObservedAtMs))
                {
                }
                if (LifecycleEvent.Type == EBatchSttLifecycleType::Started)
                {
                    Buffer.VoiceStarted(LifecycleEvent.GenerationId);
                }
                else if (std::optional<Gahyeon::EncodedPcmUtterance> Result =
                    Buffer.VoiceEnded(LifecycleEvent.GenerationId))
                {
                    CompleteIfCurrent(MoveTemp(*Result), WorkSequence);
                }
                else
                {
                    std::scoped_lock Lock(StateMutex);
                    if (WorkSequence != ResetSequence.load(std::memory_order_acquire))
                    {
                        continue;
                    }
                    if (FailedGenerations.size() >= MaximumPendingLifecycleEvents)
                    {
                        FailedGenerations.pop_front();
                    }
                    FailedGenerations.push_back(LifecycleEvent.GenerationId);
                }
                continue;
            }
            PopPcm(Buffer);
        }
    }

    std::array<FBatchPcmSlot, MaximumPendingPcmChunks> PcmSlots;
    std::vector<float> PcmSamples;
    std::atomic<std::uint64_t> PcmWrite{0};
    std::atomic<std::uint64_t> PcmRead{0};
    mutable std::mutex StateMutex;
    std::deque<FBatchSttLifecycleEvent> Lifecycle;
    std::deque<Gahyeon::EncodedPcmUtterance> Completed;
    std::deque<int64> FailedGenerations;
    std::atomic<int32> DroppedPcmChunks{0};
    std::atomic<int32> DroppedLifecycleEvents{0};
    std::atomic<int32> DroppedCompletedUtterances{0};
    std::atomic<bool> bStopping{false};
    std::atomic<bool> bResetRequested{false};
    std::atomic<std::uint64_t> ResetSequence{0};
    std::counting_semaphore<std::numeric_limits<std::ptrdiff_t>::max()> WorkAvailable{0};
    std::thread Worker;
};

FGahyeonBatchSttAudioSink::FGahyeonBatchSttAudioSink()
    : Impl(std::make_unique<FImpl>())
{
}

FGahyeonBatchSttAudioSink::~FGahyeonBatchSttAudioSink() = default;

bool FGahyeonBatchSttAudioSink::TryEnqueuePcm(
    const float* InAudio,
    int32 NumFrames,
    int32 NumChannels,
    int32 SampleRate,
    int64 ObservedAtMs)
{
    if (InAudio == nullptr || NumFrames <= 0 || NumChannels <= 0 || NumChannels > 8
        || SampleRate < 8'000 || SampleRate > 384'000 || ObservedAtMs < 0)
    {
        return false;
    }
    return Impl->EnqueuePcm(
        InAudio, NumFrames, NumChannels, SampleRate, ObservedAtMs);
}

bool FGahyeonBatchSttAudioSink::VoiceActivityStarted(
    int64 GenerationId,
    int64 ObservedAtMs)
{
    if (GenerationId < 0 || ObservedAtMs < 0) return false;
    return Impl->EnqueueLifecycle({
        .Type = EBatchSttLifecycleType::Started,
        .GenerationId = GenerationId,
        .ObservedAtMs = ObservedAtMs});
}

bool FGahyeonBatchSttAudioSink::VoiceActivityEnded(
    int64 GenerationId,
    int64 ObservedAtMs)
{
    if (GenerationId < 0 || ObservedAtMs < 0) return false;
    return Impl->EnqueueLifecycle({
        .Type = EBatchSttLifecycleType::Ended,
        .GenerationId = GenerationId,
        .ObservedAtMs = ObservedAtMs});
}

bool FGahyeonBatchSttAudioSink::VoiceActivityCancelled(
    int64 GenerationId,
    int64 ObservedAtMs)
{
    if (GenerationId < 0 || ObservedAtMs < 0) return false;
    Impl->Reset();
    return true;
}

std::optional<Gahyeon::EncodedPcmUtterance> FGahyeonBatchSttAudioSink::TakeCompleted()
{
    return Impl->TakeCompleted();
}

std::optional<int64> FGahyeonBatchSttAudioSink::TakeFailedGeneration()
{
    return Impl->TakeFailedGeneration();
}

void FGahyeonBatchSttAudioSink::Reset()
{
    Impl->Reset();
}

int32 FGahyeonBatchSttAudioSink::GetDroppedPcmChunkCount() const
{
    return Impl->DroppedPcmChunks.load();
}

int32 FGahyeonBatchSttAudioSink::GetDroppedLifecycleEventCount() const
{
    return Impl->DroppedLifecycleEvents.load();
}

int32 FGahyeonBatchSttAudioSink::GetDroppedCompletedUtteranceCount() const
{
    return Impl->DroppedCompletedUtterances.load();
}
