#include "Gahyeon/StreamingSttClientRuntime.h"

#include <algorithm>
#include <limits>
#include <stdexcept>

namespace Gahyeon {

namespace {
constexpr std::size_t MaximumPcmBytes = 131'072;

bool ValidId(const std::string& value) {
    if (value.empty() || value.size() > 128) return false;
    return std::all_of(value.begin(), value.end(), [](unsigned char character) {
        return (character >= 'A' && character <= 'Z')
            || (character >= 'a' && character <= 'z')
            || (character >= '0' && character <= '9')
            || character == '.' || character == '_' || character == ':' || character == '-';
    });
}
}

bool StreamingSttFormat::IsValid() const noexcept {
    return SampleRate >= 8'000 && SampleRate <= 192'000
        && Channels >= 1 && Channels <= 8
        && FramesPerChunk >= 64 && FramesPerChunk <= 4'096;
}

StreamingSttClientRuntime::StreamingSttClientRuntime(std::size_t maximumPendingCommands)
    : maximumPendingCommands_(maximumPendingCommands) {
    if (maximumPendingCommands_ < 2) {
        throw std::invalid_argument("Streaming STT queue must hold start and terminal commands");
    }
}

std::optional<StreamingSttMode> StreamingSttClientRuntime::Begin(
    std::int64_t generation,
    std::int64_t observedAtMs,
    std::string streamId,
    StreamingSttFormat format) {
    if ((state_ == State::Streaming || state_ == State::Ending)
        && generation > generation_) {
        Cancel(generation_, StreamingSttCancelReason::BargeIn);
    }
    if (state_ != State::Idle || generation < 0 || observedAtMs < 0
        || !ValidId(streamId) || !format.IsValid()) {
        return std::nullopt;
    }
    generation_ = generation;
    streamId_ = std::move(streamId);
    format_ = format;
    nextAudioSequence_ = 0;
    nextResultSequence_ = 0;
    lastAudioSequence_ = 0;
    hasAudio_ = false;
    if (!streamingAvailable_ || fallbackNext_) {
        fallbackNext_ = false;
        state_ = State::Batch;
        return StreamingSttMode::BatchFallback;
    }
    state_ = State::Streaming;
    StreamingSttCommand start;
    start.Type = StreamingSttCommandType::Start;
    start.Generation = generation_;
    start.StreamId = streamId_;
    start.ObservedAtMs = observedAtMs;
    start.Format = format_;
    if (!Enqueue(std::move(start))) {
        Fail(StreamingSttFailure::Backpressure);
        return std::nullopt;
    }
    return StreamingSttMode::Streaming;
}

bool StreamingSttClientRuntime::OfferFloat32Le(
    const std::uint8_t* pcm,
    std::size_t pcmBytes,
    StreamingSttFormat format) {
    if (state_ == State::Batch) return pcm != nullptr && pcmBytes > 0;
    if (state_ != State::Streaming || pcm == nullptr || pcmBytes == 0
        || pcmBytes > MaximumPcmBytes || format != format_
        || pcmBytes % (static_cast<std::size_t>(format_.Channels) * sizeof(float)) != 0) {
        if (state_ == State::Streaming) Fail(StreamingSttFailure::FormatChanged);
        return false;
    }
    StreamingSttCommand audio;
    audio.Type = StreamingSttCommandType::Audio;
    audio.Generation = generation_;
    audio.StreamId = streamId_;
    audio.Sequence = nextAudioSequence_;
    audio.BinaryFrame.resize(sizeof(std::uint64_t) + pcmBytes);
    for (std::size_t index = 0; index < sizeof(std::uint64_t); ++index) {
        audio.BinaryFrame[index] = static_cast<std::uint8_t>(
            nextAudioSequence_ >> ((sizeof(std::uint64_t) - 1 - index) * 8));
    }
    std::copy(pcm, pcm + pcmBytes, audio.BinaryFrame.begin() + sizeof(std::uint64_t));
    if (!Enqueue(std::move(audio))) {
        Fail(StreamingSttFailure::Backpressure);
        return false;
    }
    hasAudio_ = true;
    lastAudioSequence_ = nextAudioSequence_++;
    return true;
}

bool StreamingSttClientRuntime::End(std::int64_t generation, std::int64_t observedAtMs) {
    if (generation != generation_ || observedAtMs < 0) return false;
    if (state_ == State::Batch || state_ == State::Failed) {
        state_ = State::Idle;
        generation_ = -1;
        streamId_.clear();
        return true;
    }
    if (state_ != State::Streaming || !hasAudio_) {
        if (state_ == State::Streaming) Fail(StreamingSttFailure::InvalidLifecycle);
        return false;
    }
    StreamingSttCommand end;
    end.Type = StreamingSttCommandType::End;
    end.Generation = generation_;
    end.StreamId = streamId_;
    end.ObservedAtMs = observedAtMs;
    end.Sequence = lastAudioSequence_;
    if (!Enqueue(std::move(end))) {
        Fail(StreamingSttFailure::Backpressure);
        return false;
    }
    state_ = State::Ending;
    return true;
}

bool StreamingSttClientRuntime::Cancel(
    std::int64_t generation,
    StreamingSttCancelReason reason) {
    if (generation != generation_ || state_ == State::Idle) return false;
    if (state_ == State::Streaming || state_ == State::Ending) {
        commands_.clear();
        StreamingSttCommand cancel;
        cancel.Type = StreamingSttCommandType::Cancel;
        cancel.Generation = generation_;
        cancel.StreamId = streamId_;
        cancel.CancelReason = reason;
        commands_.push_back(std::move(cancel));
    }
    state_ = State::Idle;
    generation_ = -1;
    streamId_.clear();
    return true;
}

void StreamingSttClientRuntime::TransportFailed() {
    streamingAvailable_ = false;
    if (state_ == State::Streaming || state_ == State::Ending) {
        Fail(StreamingSttFailure::TransportError);
    }
}

bool StreamingSttClientRuntime::ResultIngressBackpressured() {
    if (state_ != State::Streaming && state_ != State::Ending) return false;
    Fail(StreamingSttFailure::Backpressure);
    return true;
}

bool StreamingSttClientRuntime::ProviderFailed(
    std::int64_t generation,
    const std::string& streamId) {
    if ((state_ != State::Streaming && state_ != State::Ending)
        || !Matches(generation, streamId)) return false;
    Fail(StreamingSttFailure::ProviderError);
    return true;
}

void StreamingSttClientRuntime::SetStreamingAvailable(bool available) noexcept {
    streamingAvailable_ = available;
}

std::optional<StreamingSttCommand> StreamingSttClientRuntime::TakeCommand() {
    if (commands_.empty()) return std::nullopt;
    StreamingSttCommand command = std::move(commands_.front());
    commands_.pop_front();
    return command;
}

std::optional<StreamingSttFailure> StreamingSttClientRuntime::TakeFailure() {
    if (failures_.empty()) return std::nullopt;
    const StreamingSttFailure failure = failures_.front();
    failures_.pop_front();
    return failure;
}

StreamingSttResult StreamingSttClientRuntime::AcceptPartial(
    std::int64_t generation,
    const std::string& streamId,
    std::uint64_t resultSequence,
    const std::string& text) {
    if (state_ != State::Streaming && state_ != State::Ending) {
        return generation < generation_ ? StreamingSttResult::Stale : StreamingSttResult::Invalid;
    }
    return AcceptResultIdentity(generation, streamId, resultSequence, text);
}

StreamingSttResult StreamingSttClientRuntime::AcceptFinal(
    std::int64_t generation,
    const std::string& streamId,
    std::uint64_t resultSequence,
    const std::string& text) {
    if (state_ != State::Ending) {
        return generation < generation_ ? StreamingSttResult::Stale : StreamingSttResult::Invalid;
    }
    const StreamingSttResult result =
        AcceptResultIdentity(generation, streamId, resultSequence, text);
    if (result == StreamingSttResult::Accepted) {
        state_ = State::Idle;
        generation_ = -1;
        streamId_.clear();
    }
    return result;
}

bool StreamingSttClientRuntime::IsActive() const noexcept {
    return state_ != State::Idle;
}

bool StreamingSttClientRuntime::IsStreaming() const noexcept {
    return state_ == State::Streaming || state_ == State::Ending;
}

bool StreamingSttClientRuntime::RequiresBatchFallback() const noexcept {
    return fallbackNext_;
}

std::size_t StreamingSttClientRuntime::PendingCommandCount() const noexcept {
    return commands_.size();
}

bool StreamingSttClientRuntime::Enqueue(StreamingSttCommand command) {
    if (commands_.size() >= maximumPendingCommands_) return false;
    commands_.push_back(std::move(command));
    return true;
}

void StreamingSttClientRuntime::Fail(StreamingSttFailure failure) {
    if (state_ == State::Failed) return;
    failures_.push_back(failure);
    fallbackNext_ = true;
    commands_.clear();
    if (!streamId_.empty()) {
        StreamingSttCommand cancel;
        cancel.Type = StreamingSttCommandType::Cancel;
        cancel.Generation = generation_;
        cancel.StreamId = streamId_;
        cancel.CancelReason = CancelReasonFor(failure);
        commands_.push_back(std::move(cancel));
    }
    state_ = State::Failed;
}

StreamingSttCancelReason StreamingSttClientRuntime::CancelReasonFor(
    StreamingSttFailure failure) noexcept {
    switch (failure) {
    case StreamingSttFailure::Backpressure:
        return StreamingSttCancelReason::Backpressure;
    case StreamingSttFailure::InvalidLifecycle:
    case StreamingSttFailure::FormatChanged:
        return StreamingSttCancelReason::CaptureError;
    case StreamingSttFailure::TransportError:
    case StreamingSttFailure::ProviderError:
        return StreamingSttCancelReason::ClientReset;
    }
    return StreamingSttCancelReason::ClientReset;
}

bool StreamingSttClientRuntime::Matches(
    std::int64_t generation,
    const std::string& streamId) const noexcept {
    return generation == generation_ && streamId == streamId_;
}

StreamingSttResult StreamingSttClientRuntime::AcceptResultIdentity(
    std::int64_t generation,
    const std::string& streamId,
    std::uint64_t resultSequence,
    const std::string& text) {
    if (generation < generation_) return StreamingSttResult::Stale;
    if (!Matches(generation, streamId) || text.empty()) return StreamingSttResult::Invalid;
    if (resultSequence < nextResultSequence_) return StreamingSttResult::Duplicate;
    if (resultSequence > nextResultSequence_) {
        Fail(StreamingSttFailure::ProviderError);
        return StreamingSttResult::Invalid;
    }
    ++nextResultSequence_;
    return StreamingSttResult::Accepted;
}

} // namespace Gahyeon
