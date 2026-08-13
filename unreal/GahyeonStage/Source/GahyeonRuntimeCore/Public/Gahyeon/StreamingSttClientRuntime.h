#pragma once

#include <cstddef>
#include <cstdint>
#include <deque>
#include <optional>
#include <string>
#include <vector>

namespace Gahyeon {

enum class StreamingSttMode : std::uint8_t {
    Streaming,
    BatchFallback
};

enum class StreamingSttCommandType : std::uint8_t {
    Start,
    Audio,
    End,
    Cancel
};

enum class StreamingSttCancelReason : std::uint8_t {
    BargeIn,
    ClientReset,
    Backpressure,
    CaptureError,
    Timeout
};

enum class StreamingSttFailure : std::uint8_t {
    InvalidLifecycle,
    FormatChanged,
    Backpressure,
    TransportError,
    ProviderError
};

enum class StreamingSttResult : std::uint8_t {
    Accepted,
    Stale,
    Duplicate,
    Invalid
};

struct StreamingSttFormat {
    std::int32_t SampleRate = 0;
    std::int32_t Channels = 0;
    std::int32_t FramesPerChunk = 0;

    bool IsValid() const noexcept;
    bool operator==(const StreamingSttFormat&) const = default;
};

struct StreamingSttCommand {
    StreamingSttCommandType Type = StreamingSttCommandType::Cancel;
    std::int64_t Generation = -1;
    std::string StreamId;
    std::int64_t ObservedAtMs = 0;
    std::uint64_t Sequence = 0;
    StreamingSttFormat Format;
    StreamingSttCancelReason CancelReason = StreamingSttCancelReason::ClientReset;
    std::vector<std::uint8_t> BinaryFrame;
};

/**
 * Engine-independent client-side lifecycle and bounded egress queue.
 * Capture threads only offer copied PCM; an Unreal adapter drains commands on the Game Thread.
 */
class GAHYEON_RUNTIME_CORE_API StreamingSttClientRuntime final {
public:
    explicit StreamingSttClientRuntime(std::size_t maximumPendingCommands = 64);

    std::optional<StreamingSttMode> Begin(
        std::int64_t generation,
        std::int64_t observedAtMs,
        std::string streamId,
        StreamingSttFormat format);

    bool OfferFloat32Le(
        const std::uint8_t* pcm,
        std::size_t pcmBytes,
        StreamingSttFormat format);

    bool End(std::int64_t generation, std::int64_t observedAtMs);
    bool Cancel(
        std::int64_t generation,
        StreamingSttCancelReason reason = StreamingSttCancelReason::ClientReset);
    void TransportFailed();
    bool ResultIngressBackpressured();
    bool ProviderFailed(std::int64_t generation, const std::string& streamId);
    void SetStreamingAvailable(bool available) noexcept;

    std::optional<StreamingSttCommand> TakeCommand();
    std::optional<StreamingSttFailure> TakeFailure();

    StreamingSttResult AcceptPartial(
        std::int64_t generation,
        const std::string& streamId,
        std::uint64_t resultSequence,
        const std::string& text);
    StreamingSttResult AcceptFinal(
        std::int64_t generation,
        const std::string& streamId,
        std::uint64_t resultSequence,
        const std::string& text);

    bool IsActive() const noexcept;
    bool IsStreaming() const noexcept;
    bool RequiresBatchFallback() const noexcept;
    std::size_t PendingCommandCount() const noexcept;

private:
    enum class State : std::uint8_t {
        Idle,
        Streaming,
        Ending,
        Batch,
        Failed
    };

    bool Enqueue(StreamingSttCommand command);
    void Fail(StreamingSttFailure failure);
    static StreamingSttCancelReason CancelReasonFor(StreamingSttFailure failure) noexcept;
    bool Matches(std::int64_t generation, const std::string& streamId) const noexcept;
    StreamingSttResult AcceptResultIdentity(
        std::int64_t generation,
        const std::string& streamId,
        std::uint64_t resultSequence,
        const std::string& text);

    std::size_t maximumPendingCommands_;
    std::deque<StreamingSttCommand> commands_;
    std::deque<StreamingSttFailure> failures_;
    State state_ = State::Idle;
    bool fallbackNext_ = false;
    bool streamingAvailable_ = false;
    std::int64_t generation_ = -1;
    std::string streamId_;
    StreamingSttFormat format_;
    std::uint64_t nextAudioSequence_ = 0;
    std::uint64_t lastAudioSequence_ = 0;
    bool hasAudio_ = false;
    std::uint64_t nextResultSequence_ = 0;
};

} // namespace Gahyeon
