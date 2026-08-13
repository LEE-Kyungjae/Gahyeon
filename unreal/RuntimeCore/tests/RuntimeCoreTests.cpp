#include "Gahyeon/IntentRuntime.h"
#include "Gahyeon/IntentMailbox.h"
#include "Gahyeon/LipSyncRuntime.h"
#include "Gahyeon/LatencyTrace.h"
#include "Gahyeon/MockCognitionRuntime.h"
#include "Gahyeon/PcmUtteranceBuffer.h"
#include "Gahyeon/RealtimeCharacterCoordinator.h"
#include "Gahyeon/ReplayCursorRuntime.h"
#include "Gahyeon/ProtocolMessageTranslator.h"
#include "Gahyeon/ProtocolEventRuntime.h"
#include "Gahyeon/ProtocolGameThreadDispatcher.h"
#include "Gahyeon/ProtocolIngressMailbox.h"
#include "Gahyeon/ProtocolNetworkBridge.h"
#include "Gahyeon/SpeechPlaybackCoordinator.h"
#include "Gahyeon/VoiceActivityDetector.h"
#include "Gahyeon/VoiceInteractionController.h"
#include "Gahyeon/WorldStateRuntime.h"
#include "Gahyeon/WorldActionRuntime.h"
#include "Gahyeon/WorldActionCompletionOutbox.h"
#include "Gahyeon/WorldActionCommandBridge.h"
#include "Gahyeon/AmbientMotionRuntime.h"
#include "Gahyeon/AttentionRuntime.h"
#include "Gahyeon/ConnectionConvergenceRuntime.h"
#include "Gahyeon/ClientRuntimeSaveState.h"
#include "Gahyeon/EmotionRuntime.h"
#include "Gahyeon/GestureRuntime.h"
#include "Gahyeon/StreamingSttClientRuntime.h"

#include <cstdlib>
#include <iostream>
#include <string>
#include <thread>
#include <vector>
#include <cmath>
#include <algorithm>

namespace {

void Require(bool condition, const std::string& message) {
    if (!condition) {
        std::cerr << "FAILED: " << message << '\n';
        std::exit(1);
    }
}

std::string Value(
    const Gahyeon::ResolvedIntents& state,
    Gahyeon::IntentChannel channel) {
    const Gahyeon::CharacterIntent* intent = state.Find(channel);
    return intent == nullptr ? "" : intent->Value;
}

void SlowCognitionDoesNotFreezeAmbientBehavior() {
    Gahyeon::RealtimeCharacterCoordinator character(0);
    const auto generation = character.VoiceStarted(10);
    Require(Value(character.Intents().Resolve(10), Gahyeon::IntentChannel::Phase) == "listening",
        "VAD start must synchronously enter listening");
    character.VoiceEnded(generation, 200);

    const auto waiting = character.Intents().Resolve(10'200);
    Require(Value(waiting, Gahyeon::IntentChannel::Phase) == "thinking",
        "character must remain in thinking while cognition is pending");
    Require(Value(waiting, Gahyeon::IntentChannel::Posture) == "ambient_alive",
        "ambient life motion must survive a ten-second cognition delay");
}

void StreamingSttClientFramesExactOrderedAudio() {
    Gahyeon::StreamingSttClientRuntime runtime(8);
    runtime.SetStreamingAvailable(true);
    const Gahyeon::StreamingSttFormat format{48'000, 2, 480};
    const auto mode = runtime.Begin(7, 100, "stream-7", format);
    Require(mode == Gahyeon::StreamingSttMode::Streaming,
        "healthy utterance must select streaming mode");
    const auto start = runtime.TakeCommand();
    Require(start.has_value() && start->Type == Gahyeon::StreamingSttCommandType::Start,
        "streaming utterance must begin with start control");
    Require(start->Generation == 7 && start->Format == format,
        "start must retain exact generation and capture format");

    std::vector<std::uint8_t> pcm(480 * 2 * sizeof(float), 0x5a);
    Require(runtime.OfferFloat32Le(pcm.data(), pcm.size(), format),
        "valid first PCM chunk should enter bounded egress");
    const auto audio = runtime.TakeCommand();
    Require(audio.has_value() && audio->Type == Gahyeon::StreamingSttCommandType::Audio,
        "accepted PCM must become a binary audio command");
    Require(audio->BinaryFrame.size() == pcm.size() + 8,
        "binary frame must have an eight-byte sequence header");
    Require(std::all_of(audio->BinaryFrame.begin(), audio->BinaryFrame.begin() + 8,
            [](std::uint8_t value) { return value == 0; }),
        "first network-order audio sequence must be zero");
    Require(std::equal(pcm.begin(), pcm.end(), audio->BinaryFrame.begin() + 8),
        "binary frame must preserve little-endian float payload bytes");
    Require(runtime.End(7, 200), "matching VAD end should commit streamed audio");
    const auto end = runtime.TakeCommand();
    Require(end.has_value() && end->Type == Gahyeon::StreamingSttCommandType::End
            && end->Sequence == 0,
        "end must identify the last fully accepted audio sequence");
    Require(runtime.AcceptPartial(7, "stream-7", 0, "안녕")
            == Gahyeon::StreamingSttResult::Accepted,
        "first ordered partial should be accepted");
    Require(runtime.AcceptFinal(7, "stream-7", 1, "안녕하세요")
            == Gahyeon::StreamingSttResult::Accepted,
        "ordered final after end should terminate the utterance");
    Require(!runtime.IsActive(), "accepted final must release the client utterance");
}

void StreamingSttBackpressureFailsWholeUtteranceAndFallsBackNextOnly() {
    Gahyeon::StreamingSttClientRuntime runtime(2);
    runtime.SetStreamingAvailable(true);
    const Gahyeon::StreamingSttFormat format{24'000, 1, 240};
    Require(runtime.Begin(1, 10, "stream-1", format)
            == Gahyeon::StreamingSttMode::Streaming,
        "first utterance should initially stream");
    std::vector<std::uint8_t> pcm(240 * sizeof(float));
    Require(runtime.OfferFloat32Le(pcm.data(), pcm.size(), format),
        "queue should accept start plus first PCM");
    Require(!runtime.OfferFloat32Le(pcm.data(), pcm.size(), format),
        "queue overflow must reject rather than silently drop PCM");
    Require(runtime.TakeFailure() == Gahyeon::StreamingSttFailure::Backpressure,
        "overflow must expose a typed utterance failure");
    const auto cancel = runtime.TakeCommand();
    Require(cancel.has_value() && cancel->Type == Gahyeon::StreamingSttCommandType::Cancel,
        "overflow must replace partial egress with an explicit cancel");
    Require(cancel->CancelReason == Gahyeon::StreamingSttCancelReason::Backpressure,
        "overflow cancellation must preserve its backpressure reason on the wire command");
    Require(runtime.End(1, 20), "failed utterance end should close its lifecycle");

    Require(runtime.Begin(2, 30, "stream-2", format)
            == Gahyeon::StreamingSttMode::BatchFallback,
        "only the next utterance should select batch fallback");
    Require(runtime.PendingCommandCount() == 0,
        "batch fallback must not race streaming commands for the same utterance");
    Require(runtime.End(2, 40), "batch fallback utterance should end cleanly");
    Require(runtime.Begin(3, 50, "stream-3", format)
            == Gahyeon::StreamingSttMode::Streaming,
        "streaming may be retried after one explicit batch fallback utterance");
}

void StreamingSttResultIngressBackpressureFailsWithoutSilentTranscriptLoss() {
    Gahyeon::StreamingSttClientRuntime runtime(8);
    runtime.SetStreamingAvailable(true);
    const Gahyeon::StreamingSttFormat format{24'000, 1, 240};
    Require(runtime.Begin(5, 100, "result-overflow", format)
            == Gahyeon::StreamingSttMode::Streaming,
        "result ingress test must begin in streaming mode");
    runtime.TakeCommand();

    Require(runtime.ResultIngressBackpressured(),
        "active result ingress overflow must fail the whole utterance");
    Require(runtime.TakeFailure() == Gahyeon::StreamingSttFailure::Backpressure,
        "result ingress overflow must retain the bounded backpressure reason");
    const auto cancel = runtime.TakeCommand();
    Require(cancel.has_value()
            && cancel->Type == Gahyeon::StreamingSttCommandType::Cancel
            && cancel->CancelReason == Gahyeon::StreamingSttCancelReason::Backpressure,
        "result overflow must emit an explicit provider cancellation");
    Require(!runtime.ResultIngressBackpressured(),
        "an already failed utterance must not emit duplicate failures");
}

void StreamingSttRejectsFormatDriftAndProviderSequenceGap() {
    Gahyeon::StreamingSttClientRuntime formatRuntime(8);
    formatRuntime.SetStreamingAvailable(true);
    const Gahyeon::StreamingSttFormat format{48'000, 2, 480};
    const Gahyeon::StreamingSttFormat changed{44'100, 2, 480};
    Require(formatRuntime.Begin(1, 0, "format-stream", format).has_value(),
        "valid stream should start");
    formatRuntime.TakeCommand();
    std::vector<std::uint8_t> pcm(480 * 2 * sizeof(float));
    Require(!formatRuntime.OfferFloat32Le(pcm.data(), pcm.size(), changed),
        "mid-stream capture format drift must fail closed");
    Require(formatRuntime.TakeFailure() == Gahyeon::StreamingSttFailure::FormatChanged,
        "format drift must remain observable");

    Gahyeon::StreamingSttClientRuntime resultRuntime(8);
    resultRuntime.SetStreamingAvailable(true);
    Require(resultRuntime.Begin(2, 0, "result-stream", format).has_value(),
        "result-order stream should start");
    resultRuntime.TakeCommand();
    Require(resultRuntime.OfferFloat32Le(pcm.data(), pcm.size(), format),
        "result-order stream needs audio");
    resultRuntime.TakeCommand();
    Require(resultRuntime.End(2, 10), "result-order stream should end");
    resultRuntime.TakeCommand();
    Require(resultRuntime.AcceptPartial(2, "result-stream", 1, "gap")
            == Gahyeon::StreamingSttResult::Invalid,
        "provider result sequence gap must be rejected");
    Require(resultRuntime.TakeFailure() == Gahyeon::StreamingSttFailure::ProviderError,
        "provider result gap must fail the whole utterance");
}

void StreamingSttUsesBatchUntilTransportIsReadyAndPreemptsOldGeneration() {
    Gahyeon::StreamingSttClientRuntime runtime(8);
    const Gahyeon::StreamingSttFormat format{48'000, 1, 480};
    Require(runtime.Begin(1, 0, "offline", format)
            == Gahyeon::StreamingSttMode::BatchFallback,
        "unavailable transport must choose batch before any streaming command is emitted");
    Require(runtime.PendingCommandCount() == 0,
        "offline batch mode must not enqueue socket commands");
    Require(runtime.End(1, 10), "offline batch lifecycle should end");
    runtime.SetStreamingAvailable(true);
    Require(runtime.Begin(2, 20, "old", format)
            == Gahyeon::StreamingSttMode::Streaming,
        "connected transport should enable streaming on a later utterance");
    runtime.TakeCommand();
    std::vector<std::uint8_t> pcm(480 * sizeof(float));
    Require(runtime.OfferFloat32Le(pcm.data(), pcm.size(), format),
        "old generation should accept audio");
    runtime.TakeCommand();
    Require(runtime.End(2, 30), "old generation should enter final wait");
    runtime.TakeCommand();
    Require(runtime.Begin(3, 40, "new", format)
            == Gahyeon::StreamingSttMode::Streaming,
        "new VAD generation must preempt an old provider final wait");
    const auto cancel = runtime.TakeCommand();
    const auto start = runtime.TakeCommand();
    Require(cancel.has_value() && cancel->Type == Gahyeon::StreamingSttCommandType::Cancel
            && cancel->Generation == 2
            && cancel->CancelReason == Gahyeon::StreamingSttCancelReason::BargeIn,
        "preemption must cancel the old provider stream first");
    Require(start.has_value() && start->Type == Gahyeon::StreamingSttCommandType::Start
            && start->Generation == 3,
        "new generation start must follow the old cancellation");
}

void StreamingSttCancelReleasesStreamingAndBatchLifecycles() {
    const Gahyeon::StreamingSttFormat format{48'000, 1, 480};
    Gahyeon::StreamingSttClientRuntime streaming(8);
    streaming.SetStreamingAvailable(true);
    Require(streaming.Begin(10, 0, "cancel-streaming", format)
            == Gahyeon::StreamingSttMode::Streaming,
        "cancel test must establish a streaming lifecycle");
    Require(streaming.Cancel(10, Gahyeon::StreamingSttCancelReason::CaptureError)
            && !streaming.IsActive(),
        "streaming cancel must release the active lifecycle");
    const auto cancel = streaming.TakeCommand();
    Require(cancel.has_value() && cancel->Type == Gahyeon::StreamingSttCommandType::Cancel
            && cancel->Generation == 10
            && cancel->CancelReason == Gahyeon::StreamingSttCancelReason::CaptureError,
        "streaming cancel must replace unsent audio with an explicit provider cancel");
    Require(streaming.Begin(11, 10, "after-stream-cancel", format).has_value(),
        "a fresh utterance must start after streaming cancellation");

    Gahyeon::StreamingSttClientRuntime batch(8);
    Require(batch.Begin(20, 0, "cancel-batch", format)
            == Gahyeon::StreamingSttMode::BatchFallback,
        "offline provider must establish a batch lifecycle");
    Require(batch.Cancel(20) && !batch.IsActive(),
        "batch cancellation must release RuntimeCore as well as its PCM worker");
    Require(batch.Begin(21, 10, "after-batch-cancel", format).has_value(),
        "a fresh utterance must start after batch cancellation");
}

void PartialTranscriptRefreshesAttentionOnlyWhileListening() {
    Gahyeon::RealtimeCharacterCoordinator character;
    const auto generation = character.VoiceStarted(0);
    Require(character.PartialTranscriptObserved(generation, 0.7, 700)
            == Gahyeon::PublishResult::Accepted,
        "current listening partial should refresh the local attention reflex");
    Require(Value(character.Intents().Resolve(1'400), Gahyeon::IntentChannel::Attention)
            == "user",
        "partial transcript should keep user attention alive without Backend latency");
    character.VoiceEnded(generation, 1'410);
    Require(character.PartialTranscriptObserved(generation, 0.9, 1'420)
            == Gahyeon::PublishResult::Invalid,
        "partial transcript after VAD end must not revive a listening reflex");
    const auto next = character.VoiceStarted(1'500);
    Require(character.PartialTranscriptObserved(generation, 0.9, 1'510)
            == Gahyeon::PublishResult::Stale,
        "stale provider callbacks must not affect the newer generation");
    Require(character.PartialTranscriptObserved(next, 2.0, 1'520)
            == Gahyeon::PublishResult::Invalid,
        "invalid provider stability must be rejected");
}

void BargeInRejectsLateCognition() {
    Gahyeon::RealtimeCharacterCoordinator character;
    const auto first = character.VoiceStarted(0);
    character.VoiceEnded(first, 100);
    Require(character.SpeechStarted(first, 1'000, "old-utterance"),
        "current started speech should be accepted");

    const auto second = character.VoiceStarted(1'100);
    Require(!character.SpeechStarted(first, 1'200, "late-old-utterance"),
        "previous generation started speech must be rejected after barge-in");
    const auto state = character.Intents().Resolve(1'200);
    Require(state.CurrentGeneration == second, "new generation must remain authoritative");
    Require(Value(state, Gahyeon::IntentChannel::Phase) == "listening",
        "barge-in must keep the listening reflex");
    Require(Value(state, Gahyeon::IntentChannel::Speech).empty(),
        "stale speech must not be presented");
}

void ReflexExpiresBackToAmbientBehavior() {
    Gahyeon::IntentRuntime runtime;
    runtime.Publish(Gahyeon::CharacterIntent{
        .Id = "ambient-look",
        .Layer = Gahyeon::IntentLayer::Behavior,
        .Channel = Gahyeon::IntentChannel::Attention,
        .GenerationId = std::nullopt,
        .Priority = 10,
        .CreatedAtMs = 0,
        .ExpiresAfterMs = std::nullopt,
        .Value = "window",
    });
    runtime.Publish(Gahyeon::CharacterIntent{
        .Id = "slow-cognition-look",
        .Layer = Gahyeon::IntentLayer::Cognition,
        .Channel = Gahyeon::IntentChannel::Attention,
        .GenerationId = 0,
        .Priority = 1'000'000,
        .CreatedAtMs = 50,
        .ExpiresAfterMs = 250,
        .Value = "memory",
    });
    runtime.Publish(Gahyeon::CharacterIntent{
        .Id = "sound-reflex",
        .Layer = Gahyeon::IntentLayer::Reflex,
        .Channel = Gahyeon::IntentChannel::Attention,
        .GenerationId = 0,
        .Priority = -100,
        .CreatedAtMs = 100,
        .ExpiresAfterMs = 200,
        .Value = "sound",
    });
    Require(Value(runtime.Resolve(250), Gahyeon::IntentChannel::Attention) == "sound",
        "reflex layer must temporarily win even while high-priority Cognition is pending");
    Require(Value(runtime.Resolve(300), Gahyeon::IntentChannel::Attention) == "window",
        "expired Reflex and Cognition should reveal ambient Behavior");
}

void ConcurrentProducersHandOffWithoutTouchingTheArbiter() {
    Gahyeon::IntentMailbox mailbox(512);
    std::vector<std::thread> producers;
    for (int producer = 0; producer < 4; ++producer) {
        producers.emplace_back([producer, &mailbox]() {
            for (int item = 0; item < 100; ++item) {
                const bool accepted = mailbox.TryPush(Gahyeon::CharacterIntent{
                    .Id = "producer-" + std::to_string(producer) + "-" + std::to_string(item),
                    .Layer = Gahyeon::IntentLayer::Cognition,
                    .Channel = Gahyeon::IntentChannel::Expression,
                    .GenerationId = 0,
                    .Priority = producer,
                    .CreatedAtMs = item,
                    .ExpiresAfterMs = std::nullopt,
                    .Value = "value",
                });
                Require(accepted, "mailbox should accept events within capacity");
            }
        });
    }
    for (auto& producer : producers) {
        producer.join();
    }

    auto intents = mailbox.Drain();
    Require(intents.size() == 400, "all concurrent producer intents must be drained once");
    Require(mailbox.Size() == 0, "drain must empty the mailbox");
    Require(mailbox.DroppedCount() == 0, "mailbox must not silently drop within capacity");
}

void ReflexPreemptsLowerLayerWhenMailboxIsFull() {
    Gahyeon::IntentMailbox mailbox(2);
    const auto cognition = [](std::string id) {
        return Gahyeon::CharacterIntent{
            .Id = std::move(id),
            .Layer = Gahyeon::IntentLayer::Cognition,
            .Channel = Gahyeon::IntentChannel::Speech,
            .GenerationId = 0,
            .Priority = 1,
            .CreatedAtMs = 0,
            .ExpiresAfterMs = std::nullopt,
            .Value = "queued",
        };
    };
    Require(mailbox.TryPush(cognition("cognition-1")), "first cognition should fit");
    Require(mailbox.TryPush(cognition("cognition-2")), "second cognition should fit");
    Require(mailbox.TryPush(Gahyeon::CharacterIntent{
        .Id = "vad-reflex",
        .Layer = Gahyeon::IntentLayer::Reflex,
        .Channel = Gahyeon::IntentChannel::Phase,
        .GenerationId = 1,
        .Priority = 100,
        .CreatedAtMs = 1,
        .ExpiresAfterMs = std::nullopt,
        .Value = "listening",
    }), "reflex must preempt lower-layer work under saturation");

    const auto drained = mailbox.Drain();
    bool foundReflex = false;
    for (const auto& intent : drained) {
        foundReflex = foundReflex || intent.Layer == Gahyeon::IntentLayer::Reflex;
    }
    Require(foundReflex, "saturated mailbox must retain the reflex");
    Require(mailbox.DroppedCount() == 1, "preempted work must remain observable");
}

void CognitionCompletionDoesNotStartSpeakingBeforeAudio() {
    Gahyeon::ProtocolMessageTranslator translator;
    const auto cognition = translator.Translate(Gahyeon::ProtocolMessage{
        .Type = "cognition.response.completed",
        .GenerationId = 1,
    }, 100);
    Require(cognition.Status == Gahyeon::TranslationStatus::Ignored,
        "LLM completion alone must not start speaking");
    Require(cognition.Intents.empty(), "LLM completion must not emit presentation intents");

    const auto speech = translator.Translate(Gahyeon::ProtocolMessage{
        .Type = "speech.prepared",
        .GenerationId = 1,
        .UtteranceId = "utterance-1",
        .AudioUrl = "/speech/utterance-1",
        .MimeType = "audio/wav",
        .Visemes = {{"aa", 0, 80, 1.0}},
    }, 200);
    Require(speech.Status == Gahyeon::TranslationStatus::Translated,
        "prepared audio should translate to presentation intents");
    Require(speech.Intents.empty(), "prepared speech must not overwrite latest-value intent channels");
    Require(speech.Speech.has_value(), "prepared speech should enter the ordered audio queue");
    Require(speech.Speech->Visemes.size() == 1,
        "normalized viseme timeline must travel with its prepared audio segment");
    Require(speech.Speech->AudioUrl == "/speech/utterance-1"
            && speech.Speech->MimeType == "audio/wav",
        "audio retrieval metadata must survive protocol normalization");
}

void GenerationAdvanceInterruptsOwnedAudioWithoutWaitingForSpeechPayload() {
    Gahyeon::RealtimeCharacterCoordinator character;
    const auto generation = character.VoiceStarted(0);
    Gahyeon::SpeechPlaybackCoordinator playback(character);
    playback.SetGeneration(generation);
    playback.Prepared({generation, "old-audio", 0, 0, true, {}, {}, {}});
    playback.AcquireNext();
    playback.PlaybackStarted("old-audio", 10);
    Gahyeon::ProtocolEventRuntime protocol(character, playback);

    const auto advanced = protocol.Apply(Gahyeon::ProtocolMessage{
        .Type = "generation.advanced",
        .GenerationId = generation + 1,
        .Outcome = "cognition_timeout",
    }, 20);
    Require(advanced.Status == Gahyeon::ProtocolApplyStatus::Ignored,
        "generation acknowledgement needs no presentation intent");
    Require(advanced.InterruptedUtteranceId.value_or("") == "old-audio",
        "generation acknowledgement must identify audio for immediate device stop");
    Require(!playback.HasActiveAudio(),
        "old playback ownership must be released before the stop callback returns");
}

void LatePreparedSpeechIsRejectedByCurrentGeneration() {
    Gahyeon::IntentRuntime runtime;
    runtime.BeginGeneration();
    runtime.BeginGeneration();
    Gahyeon::ProtocolMessageTranslator translator;
    const auto translated = translator.Translate(Gahyeon::ProtocolMessage{
        .Type = "speech.prepared",
        .GenerationId = 1,
        .UtteranceId = "late-audio",
    }, 500);
    Gahyeon::SpeechQueue queue;
    queue.SetGeneration(runtime.CurrentGeneration());
    Require(queue.Enqueue(translated.Speech.value()) == Gahyeon::SpeechEnqueueResult::Stale,
        "late TTS audio must be rejected by generation-aware speech queue");
}

void UnknownProtocolMessageIsForwardCompatible() {
    Gahyeon::ProtocolMessageTranslator translator;
    const auto result = translator.Translate(Gahyeon::ProtocolMessage{
        .Type = "future.semantic.event",
    }, 0);
    Require(result.Status == Gahyeon::TranslationStatus::Ignored,
        "unknown semantic events should be ignored without failing the runtime");
}

void ReplayedFutureGenerationConvergesIntentAndSpeechAtomically() {
    Gahyeon::RealtimeCharacterCoordinator character;
    const auto oldGeneration = character.VoiceStarted(0);
    character.VoiceEnded(oldGeneration, 20);
    Gahyeon::SpeechPlaybackCoordinator playback(character);
    playback.SetGeneration(oldGeneration);
    playback.Prepared({oldGeneration, "old-audio", 0, 0, true, {}, {}, {}});
    playback.AcquireNext();
    playback.PlaybackStarted("old-audio", 30);
    Gahyeon::ProtocolEventRuntime protocol(character, playback);

    const auto result = protocol.Apply(Gahyeon::ProtocolMessage{
        .Type = "character.state.target",
        .GenerationId = 12,
        .Semantic = "thinking",
        .Priority = 70,
    }, 100);

    Require(result.Status == Gahyeon::ProtocolApplyStatus::Applied,
        "future durable state should apply during reconnect replay");
    Require(result.InterruptedUtteranceId.value_or("") == "old-audio",
        "generation convergence must identify old audio for immediate stop");
    Require(character.Intents().CurrentGeneration() == 12,
        "replayed Backend generation must become locally authoritative");
    Require(Value(character.Intents().Resolve(100), Gahyeon::IntentChannel::Phase) == "thinking",
        "future-generation target must not remain invisible after replay");
    Require(!playback.PlaybackFinished("old-audio", 110),
        "late audio callback from pre-reconnect generation must be ignored");

    const auto speech = protocol.Apply(Gahyeon::ProtocolMessage{
        .Type = "speech.prepared",
        .GenerationId = 12,
        .UtteranceId = "current-audio",
        .SegmentIndex = 0,
        .UtteranceIndex = 0,
        .FinalSegment = true,
    }, 120);
    Require(speech.Status == Gahyeon::ProtocolApplyStatus::Applied,
        "speech for the synchronized generation should enter the queue");
    Require(playback.AcquireNext()->UtteranceId == "current-audio",
        "only current replay generation audio should remain playable");
}

void StaleReplayCannotRewindGenerationOrReplacePresentation() {
    Gahyeon::RealtimeCharacterCoordinator character;
    Require(character.SynchronizeGeneration(9) == Gahyeon::GenerationSyncResult::Advanced,
        "explicit reconnect generation should advance monotonically");
    Gahyeon::SpeechPlaybackCoordinator playback(character);
    playback.SetGeneration(9);
    Gahyeon::ProtocolEventRuntime protocol(character, playback);

    const auto stale = protocol.Apply(Gahyeon::ProtocolMessage{
        .Type = "character.state.target",
        .GenerationId = 8,
        .Semantic = "speaking",
        .Priority = 100,
    }, 200);
    Require(stale.Status == Gahyeon::ProtocolApplyStatus::Stale,
        "out-of-order replay must be rejected before intent publication");
    Require(character.Intents().CurrentGeneration() == 9,
        "stale replay must never rewind the cancellation watermark");
    Require(Value(character.Intents().Resolve(200), Gahyeon::IntentChannel::Phase).empty(),
        "stale phase must not become visible");
    Require(character.SynchronizeGeneration(8) == Gahyeon::GenerationSyncResult::Stale,
        "direct generation synchronization must also reject rewind");
}

void UnknownFutureEventCannotAdvanceCancellationGeneration() {
    Gahyeon::RealtimeCharacterCoordinator character;
    Gahyeon::SpeechPlaybackCoordinator playback(character);
    Gahyeon::ProtocolEventRuntime protocol(character, playback);
    const auto ignored = protocol.Apply(Gahyeon::ProtocolMessage{
        .Type = "future.semantic.event",
        .GenerationId = 999,
    }, 0);
    Require(ignored.Status == Gahyeon::ProtocolApplyStatus::Ignored,
        "unknown protocol extension should remain forward-compatible");
    Require(character.Intents().CurrentGeneration() == 0,
        "unknown events must not control the local cancellation watermark");
}

void ProtocolSpeechBackpressureIsObservable() {
    Gahyeon::RealtimeCharacterCoordinator character;
    Gahyeon::SpeechPlaybackCoordinator playback(character, 1);
    Gahyeon::ProtocolEventRuntime protocol(character, playback);
    const auto prepared = [&](const std::string& id, int utteranceIndex) {
        return protocol.Apply(Gahyeon::ProtocolMessage{
            .Type = "speech.prepared",
            .GenerationId = 0,
            .UtteranceId = id,
            .SegmentIndex = 0,
            .UtteranceIndex = utteranceIndex,
            .FinalSegment = true,
        }, 0);
    };
    Require(prepared("queued", 0).Status == Gahyeon::ProtocolApplyStatus::Applied,
        "speech should fill the bounded queue");
    Require(prepared("overflow", 1).Status == Gahyeon::ProtocolApplyStatus::Backpressured,
        "queue saturation must be surfaced to the Unreal adapter");
}

void ReplayCursorAdvancesAcrossScopedSequenceGaps() {
    Gahyeon::ReplayCursorRuntime cursor(40);
    cursor.BeginConnection();
    Require(cursor.CompleteDurable(41) == Gahyeon::ReplayCursorResult::HandshakeRequired,
        "durable replay must wait for the welcome handshake");
    Require(cursor.Welcome(40) == Gahyeon::ReplayCursorResult::Advanced,
        "welcome must confirm the requested resume cursor");
    Require(cursor.CompleteDurable(43) == Gahyeon::ReplayCursorResult::Advanced,
        "visible durable events may skip sequences owned by another scope");
    Require(cursor.ObserveScanCursor(50) == Gahyeon::ReplayCursorResult::Advanced,
        "scan cursor must safely advance across invisible scoped events");
    Require(cursor.SafeAcknowledgement() == 50 && cursor.PersistedSequence() == 50,
        "safe cursor must be persisted before acknowledgement transmission");
    Require(cursor.AckPending(), "advanced replay cursor should request an ack");
    Require(cursor.MarkAcknowledged(50) == Gahyeon::ReplayCursorResult::Advanced,
        "latest safe sequence should be acknowledged");
    Require(!cursor.AckPending(), "sent ack should clear pending state");
}

void ReplayCursorSurvivesDisconnectAndDuplicateReplay() {
    Gahyeon::ReplayCursorRuntime cursor(7);
    cursor.BeginConnection();
    Require(cursor.Welcome(6) == Gahyeon::ReplayCursorResult::Invalid,
        "server must not silently resume from a different cursor");
    Require(cursor.Welcome(7) == Gahyeon::ReplayCursorResult::Advanced,
        "matching welcome should establish replay");
    cursor.CompleteDurable(8);

    cursor.BeginConnection();
    Require(cursor.PersistedSequence() == 8,
        "connection loss must preserve the last completely handled durable event");
    Require(cursor.Welcome(8) == Gahyeon::ReplayCursorResult::Advanced,
        "reconnect should resume from persisted application state");
    Require(cursor.CompleteDurable(8) == Gahyeon::ReplayCursorResult::Duplicate,
        "duplicate replay must not be applied twice");
    Require(cursor.ObserveScanCursor(7) == Gahyeon::ReplayCursorResult::Invalid,
        "regressing server cursor must be rejected without rewinding state");
    Require(cursor.MarkAcknowledged(9) == Gahyeon::ReplayCursorResult::Invalid,
        "client must never ack beyond completely handled data");
}

Gahyeon::WorldStateSnapshot WorldSnapshot(Gahyeon::Generation revision) {
    return Gahyeon::WorldStateSnapshot{
        .WorldId = "gahyeon-home",
        .Revision = revision,
        .CurrentRoom = "workspace",
        .Position = {1.0, 2.0, 3.0},
        .Activity = "work",
        .Outfit = "casual",
        .Emotion = "focused",
        .EmotionIntensity = 0.65,
        .InteractionTarget = "desk",
    };
}

void WorldSnapshotAppliesAtomicallyAndRejectsRevisionRegression() {
    Gahyeon::WorldStateRuntime world;
    const auto revision7 = WorldSnapshot(7);
    Require(world.ApplySnapshot(revision7) == Gahyeon::WorldStateApplyResult::Applied,
        "first authoritative snapshot should establish presentation world state");
    Require(world.ApplySnapshot(revision7) == Gahyeon::WorldStateApplyResult::Duplicate,
        "identical snapshot replay should be idempotent");

    auto stale = WorldSnapshot(6);
    stale.CurrentRoom = "bedroom";
    Require(world.ApplySnapshot(stale) == Gahyeon::WorldStateApplyResult::Stale,
        "older durable replay must not regress the snapshot revision");
    Require(world.Current()->CurrentRoom == "workspace"
            && world.Current()->Revision == 7,
        "stale snapshot must leave every world field unchanged");

    auto revision8 = WorldSnapshot(8);
    revision8.CurrentRoom = "living_room";
    revision8.Activity = "relax";
    Require(world.ApplySnapshot(revision8) == Gahyeon::WorldStateApplyResult::Applied,
        "newer snapshot should replace all presentation world fields together");
    Require(world.Current()->CurrentRoom == "living_room"
            && world.Current()->Activity == "relax"
            && world.Current()->Revision == 8,
        "new revision must be visible as one coherent state");
}

void InvalidOrConflictingWorldSnapshotCannotPartiallyMutateState() {
    Gahyeon::WorldStateRuntime world;
    world.ApplySnapshot(WorldSnapshot(3));

    auto invalid = WorldSnapshot(4);
    invalid.Position.X = std::nan("");
    invalid.CurrentRoom = "corrupt-room";
    Require(world.ApplySnapshot(invalid) == Gahyeon::WorldStateApplyResult::Invalid,
        "non-finite snapshot coordinates must reject the complete snapshot");
    Require(world.Current()->Revision == 3
            && world.Current()->CurrentRoom == "workspace",
        "invalid snapshot must not leak partially updated fields");

    auto conflict = WorldSnapshot(3);
    conflict.Emotion = "happy";
    Require(world.ApplySnapshot(conflict) == Gahyeon::WorldStateApplyResult::Conflict,
        "same revision with different content must expose split-brain state");
    auto wrongWorld = WorldSnapshot(4);
    wrongWorld.WorldId = "other-world";
    Require(world.ApplySnapshot(wrongWorld) == Gahyeon::WorldStateApplyResult::Conflict,
        "a connection cannot silently switch authoritative worlds");
    Require(world.Current()->Emotion == "focused",
        "conflicts must preserve the last coherent snapshot");
}

void ReconnectHarnessConvergesToSnapshotWithinTwoSeconds() {
    Gahyeon::ReplayCursorRuntime cursor(100);
    Gahyeon::WorldStateRuntime world;
    Gahyeon::ConnectionConvergenceRuntime convergence(2'000);

    cursor.BeginConnection();
    Require(convergence.BeginConnection(10'000)
            == Gahyeon::ConnectionConvergenceResult::Accepted,
        "reconnect measurement should start from a monotonic local timestamp");
    Require(cursor.Welcome(100) == Gahyeon::ReplayCursorResult::Advanced
            && convergence.Welcome(10'120)
                == Gahyeon::ConnectionConvergenceResult::Accepted,
        "welcome should establish cursor and convergence state without blocking");
    Require(world.ApplySnapshot(WorldSnapshot(20))
            == Gahyeon::WorldStateApplyResult::Applied,
        "hello snapshot should establish authoritative World State");
    Require(convergence.SnapshotApplied(11'900)
            == Gahyeon::ConnectionConvergenceResult::Accepted,
        "snapshot applied at 1900ms must satisfy the two-second acceptance budget");
    Require(convergence.LastConvergenceMs().value_or(-1) == 1'900,
        "reconnect convergence duration must remain directly observable");

    auto replayedOlderWorldEvent = WorldSnapshot(19);
    replayedOlderWorldEvent.CurrentRoom = "bedroom";
    Require(world.ApplySnapshot(replayedOlderWorldEvent)
            == Gahyeon::WorldStateApplyResult::Stale,
        "durable replay older than hello snapshot must not regress current world state");
    Require(cursor.CompleteDurable(104) == Gahyeon::ReplayCursorResult::Advanced
            && cursor.ObserveScanCursor(110) == Gahyeon::ReplayCursorResult::Advanced,
        "replay cursor should continue independently after world convergence");
}

void ReconnectHarnessExposesDeadlineAndOrderingFailures() {
    Gahyeon::ConnectionConvergenceRuntime convergence(2'000);
    Require(convergence.SnapshotApplied(0)
            == Gahyeon::ConnectionConvergenceResult::InvalidOrder,
        "snapshot before hello must not claim convergence");
    convergence.BeginConnection(1'000);
    convergence.Welcome(1'100);
    Require(!convergence.Advance(3'000),
        "exact deadline boundary should still be accepted");
    Require(convergence.Advance(3'001),
        "missing snapshot beyond two seconds must become an observable timeout");
    Require(convergence.State() == Gahyeon::ConnectionConvergenceState::TimedOut,
        "timeout state should be available to latency overlay and reconnect policy");
    Require(convergence.BeginConnection(2'999)
            == Gahyeon::ConnectionConvergenceResult::InvalidTime,
        "non-monotonic reconnect timestamps must be rejected");
}

void LipSyncFallsBackToSmoothedAmplitudeWithoutProviderTiming() {
    Gahyeon::LipSyncRuntime lipSync;
    lipSync.SetGeneration(4);
    Require(lipSync.BeginPlayback(Gahyeon::PreparedSpeech{
            .GenerationId = 4,
            .UtteranceId = "fallback-audio",
        }) == Gahyeon::LipSyncPrepareResult::Accepted,
        "audio without visemes should enter provider-independent fallback");
    const auto initial = lipSync.Sample(0, 0.5);
    const auto opened = lipSync.Sample(35, 0.5);
    const auto releasing = lipSync.Sample(70, 0.0);
    Require(initial.Active && !initial.UsingTimeline && initial.JawOpen == 0.0,
        "fallback must start from the closed mouth at actual playback position zero");
    Require(opened.JawOpen > 0.6 && opened.JawOpen <= 1.0,
        "amplitude attack should open the jaw quickly without snapping");
    Require(releasing.JawOpen > 0.0 && releasing.JawOpen < opened.JawOpen,
        "release smoothing should prevent chatter during short waveform gaps");
    Require(lipSync.EndPlayback("fallback-audio"),
        "matching audio completion should clear fallback motion");
    Require(!lipSync.Sample(100, 1.0).Active,
        "mouth motion must stop after the audio device completion callback");
}

void TimedVisemesUseAudioPlaybackPositionAndBlendOverlaps() {
    Gahyeon::LipSyncRuntime lipSync;
    lipSync.SetGeneration(2);
    Gahyeon::PreparedSpeech speech{
        .GenerationId = 2,
        .UtteranceId = "timed-audio",
        .Visemes = {
            {"aa", 100, 100, 1.0},
            {"ih", 160, 100, 0.8},
        },
    };
    Require(lipSync.BeginPlayback(speech) == Gahyeon::LipSyncPrepareResult::Accepted,
        "valid ordered viseme timeline should be accepted");
    const auto before = lipSync.Sample(99, 1.0);
    const auto onset = lipSync.Sample(125, 1.0);
    const auto overlap = lipSync.Sample(175, 1.0);
    Require(before.UsingTimeline && before.JawOpen == 0.0
            && before.PrimaryWeight == 0.0,
        "authoritative timeline must suppress amplitude fallback before a cue");
    Require(onset.PrimaryViseme == "aa" && onset.PrimaryWeight > 0.9,
        "viseme should reach its target within 25ms of audio cursor onset");
    Require(overlap.PrimaryWeight > 0.0 && overlap.SecondaryWeight > 0.0
            && overlap.PrimaryViseme != overlap.SecondaryViseme,
        "overlapping cues should expose two weights for coarticulation");
}

void LipSyncRejectsStaleMalformedAndBargeInAudio() {
    Gahyeon::LipSyncRuntime lipSync;
    lipSync.SetGeneration(8);
    Require(lipSync.BeginPlayback(Gahyeon::PreparedSpeech{
            .GenerationId = 7,
            .UtteranceId = "late",
        }) == Gahyeon::LipSyncPrepareResult::Stale,
        "old generation audio must never move the mouth");
    Require(lipSync.BeginPlayback(Gahyeon::PreparedSpeech{
            .GenerationId = 8,
            .UtteranceId = "malformed",
            .Visemes = {{"aa", 100, 50, 1.0}, {"ih", 90, 50, 1.0}},
        }) == Gahyeon::LipSyncPrepareResult::Invalid,
        "non-monotonic cue timeline must be rejected as one unit");
    Require(lipSync.BeginPlayback(Gahyeon::PreparedSpeech{
            .GenerationId = 8,
            .UtteranceId = "current",
        }) == Gahyeon::LipSyncPrepareResult::Accepted,
        "current audio should start lip sync");
    lipSync.Sample(20, 0.8);
    lipSync.SetGeneration(9);
    Require(!lipSync.IsActive() && !lipSync.Sample(30, 1.0).Active,
        "barge-in generation must clear mouth ownership immediately");
    Require(lipSync.BeginPlayback(Gahyeon::PreparedSpeech{
            .GenerationId = 8,
            .UtteranceId = "late-callback",
        }) == Gahyeon::LipSyncPrepareResult::Stale,
        "late audio callback after barge-in must remain unable to restart lip sync");
}

void PlaybackCoordinatorOwnsLipSyncAtTheAudioDeviceBoundary() {
    Gahyeon::RealtimeCharacterCoordinator character;
    const auto generation = character.VoiceStarted(0);
    character.VoiceEnded(generation, 50);
    Gahyeon::LipSyncRuntime lipSync;
    Gahyeon::SpeechPlaybackCoordinator playback(character, 4, &lipSync);
    playback.SetGeneration(generation);
    Require(playback.Prepared(Gahyeon::PreparedSpeech{
            .GenerationId = generation,
            .UtteranceId = "device-owned",
            .FinalSegment = true,
            .Visemes = {{"aa", 0, 100, 1.0}},
        }) == Gahyeon::SpeechEnqueueResult::Accepted,
        "prepared audio and timeline should share queue ownership");
    playback.AcquireNext();
    Require(!lipSync.IsActive(),
        "acquiring or decoding audio must not start mouth motion early");
    Require(playback.PlaybackStarted("device-owned", 100),
        "actual audio callback should start speech and lip sync together");
    Require(lipSync.IsActive() && lipSync.Sample(25, 0.0).PrimaryWeight > 0.9,
        "audio playback cursor should directly drive the prepared viseme");
    Require(!playback.SetGeneration(generation).has_value()
            && !playback.SetGeneration(generation - 1).has_value()
            && playback.IsPlaying() && lipSync.IsActive(),
        "duplicate or regressing watermark must not interrupt current audio ownership");
    Require(playback.PlaybackFinished("device-owned", 200),
        "audio completion should finish the shared presentation ownership");
    Require(!lipSync.IsActive(),
        "audio completion must close the mouth without waiting for Backend state");

    playback.SetGeneration(generation + 1);
    Require(lipSync.CurrentGeneration() == generation + 1,
        "barge-in generation must advance speech and lip sync atomically");
}

void ProtocolRejectsMalformedVisemeTimelineBeforeAudioQueueing() {
    Gahyeon::ProtocolMessageTranslator translator;
    const auto malformed = translator.Translate(Gahyeon::ProtocolMessage{
        .Type = "speech.prepared",
        .GenerationId = 1,
        .UtteranceId = "bad-visemes",
        .Visemes = {{"aa", 100, 50, 1.0}, {"ih", 90, 50, 1.0}},
    }, 0);
    Require(malformed.Status == Gahyeon::TranslationStatus::Invalid,
        "unsorted wire timeline must be rejected before audio queue admission");
    Require(!malformed.Speech.has_value(),
        "malformed timeline must not leave playable audio without valid lip-sync ownership");
}

void TenMinuteVisemeLoopStaysWithinTheEightyMillisecondSyncBudget() {
    Gahyeon::LipSyncRuntime lipSync;
    lipSync.SetGeneration(1);
    Gahyeon::Millis worstOnsetDelay = 0;
    for (int segment = 0; segment < 60; ++segment) {
        std::vector<Gahyeon::VisemeCue> cues;
        for (Gahyeon::Millis at = 100; at < 10'000; at += 100) {
            cues.push_back({"aa", at, 70, 1.0});
        }
        Require(lipSync.BeginPlayback(Gahyeon::PreparedSpeech{
                .GenerationId = 1,
                .UtteranceId = "segment-" + std::to_string(segment),
                .Visemes = cues,
            }) == Gahyeon::LipSyncPrepareResult::Accepted,
            "each streaming segment timeline should start independently");
        std::size_t nextCue = 0;
        for (Gahyeon::Millis position = 0; position < 10'000; position += 16) {
            const auto sample = lipSync.Sample(position, 0.0);
            if (nextCue < cues.size() && position >= cues[nextCue].AtMs
                && sample.PrimaryWeight > 0.0) {
                worstOnsetDelay = std::max(
                    worstOnsetDelay, position - cues[nextCue].AtMs);
                ++nextCue;
            }
        }
        Require(nextCue == cues.size(),
            "every cue in the ten-minute loop must become observable");
        lipSync.EndPlayback("segment-" + std::to_string(segment));
    }
    Require(worstOnsetDelay <= 32 && worstOnsetDelay < 80,
        "audio-position sampling must stay inside RT-08 without accumulated clock drift");
}

void EmotionBlendsDimensionsAndReleasesAfterHold() {
    Gahyeon::EmotionRuntime emotion;
    Require(emotion.ApplyTarget(Gahyeon::EmotionTarget{
            .Dimensions = {{"curiosity", 0.8}, {"amusement", 0.4}},
            .Valence = 0.5,
            .Arousal = 0.6,
            .Dominance = 0.1,
            .BlendMs = 200,
            .HoldMs = 300,
        }, 0) == Gahyeon::EmotionApplyResult::Applied,
        "valid multidimensional emotion target should be accepted");
    const auto halfway = emotion.Sample(100);
    Require(std::abs(halfway.Dimensions.at("curiosity") - 0.4) < 0.001
            && std::abs(halfway.Valence - 0.25) < 0.001,
        "emotion dimensions and VAD axes should blend continuously");
    const auto held = emotion.Sample(500);
    Require(std::abs(held.Dimensions.at("curiosity") - 0.8) < 0.001,
        "target should remain stable through blend plus hold boundary");
    const auto releasing = emotion.Sample(600);
    Require(releasing.Releasing
            && releasing.Dimensions.at("curiosity") < 0.8
            && releasing.Dimensions.at("curiosity") > 0.0,
        "expired emotion should release instead of snapping neutral");
    const auto neutral = emotion.Sample(800);
    Require(neutral.Dimensions.empty() && neutral.Valence == 0.0,
        "release completion should return to semantic neutral");
}

void EmotionRetargetsFromCurrentBlendAndSurvivesFrameGaps() {
    Gahyeon::EmotionRuntime emotion;
    emotion.ApplyTarget(Gahyeon::EmotionTarget{
        .Dimensions = {{"curiosity", 1.0}},
        .BlendMs = 1'000,
    }, 0);
    emotion.Sample(400);
    Require(emotion.ApplyTarget(Gahyeon::EmotionTarget{
            .Dimensions = {{"calm", 0.6}},
            .BlendMs = 600,
        }, 400) == Gahyeon::EmotionApplyResult::Applied,
        "retarget should capture the current interpolated pose without discontinuity");
    const auto sameFrame = emotion.Sample(400);
    Require(std::abs(sameFrame.Dimensions.at("curiosity") - 0.4) < 0.001,
        "same-frame retarget must preserve the visible source emotion");
    const auto complete = emotion.Sample(1'000);
    Require(!complete.Dimensions.contains("curiosity")
            && std::abs(complete.Dimensions.at("calm") - 0.6) < 0.001,
        "new semantic should replace the old dimension after blending");
    const auto longGap = emotion.Sample(86'400'000);
    Require(std::isfinite(longGap.Dimensions.at("calm")),
        "long frame gaps must keep emotion output finite");
    const auto rewind = emotion.Sample(900);
    Require(rewind.Dimensions == longGap.Dimensions,
        "non-monotonic frames must not rewind facial emotion");
}

void ProtocolPreservesEmotionWhileSpeechAndAttentionRunInParallel() {
    Gahyeon::RealtimeCharacterCoordinator character;
    const auto generation = character.VoiceStarted(0);
    character.VoiceEnded(generation, 10);
    Gahyeon::LipSyncRuntime lipSync;
    Gahyeon::SpeechPlaybackCoordinator playback(character, 4, &lipSync);
    playback.SetGeneration(generation);
    Gahyeon::EmotionRuntime emotion;
    Gahyeon::ProtocolEventRuntime protocol(character, playback, &emotion);
    const auto applied = protocol.Apply(Gahyeon::ProtocolMessage{
        .Type = "emotion.target",
        .GenerationId = generation,
        .Priority = 50,
        .EmotionDimensions = {{"curiosity", 0.7}, {"amusement", 0.2}},
        .Valence = 0.35,
        .Arousal = 0.42,
        .Dominance = 0.1,
        .BlendMs = 100,
        .HoldMs = 2'000,
    }, 20);
    Require(applied.Status == Gahyeon::ProtocolApplyStatus::Applied,
        "protocol runtime should preserve the full emotion target");

    playback.Prepared(Gahyeon::PreparedSpeech{
        .GenerationId = generation,
        .UtteranceId = "parallel-audio",
        .FinalSegment = true,
    });
    playback.AcquireNext();
    playback.PlaybackStarted("parallel-audio", 30);
    const auto face = emotion.Sample(120);
    Require(face.Dimensions.size() == 2
            && std::abs(face.Dimensions.at("curiosity") - 0.7) < 0.001,
        "speaking phase must not collapse multidimensional facial emotion");
    Require(Value(character.Intents().Resolve(120), Gahyeon::IntentChannel::Phase) == "speaking",
        "emotion layer must remain independent from conversation phase");
    character.VoiceStarted(130);
    playback.SetGeneration(generation + 1);
    Require(emotion.Sample(140).Dimensions.contains("curiosity"),
        "barge-in should stop speech without erasing continuous emotion state");
}

std::vector<Gahyeon::GestureDefinition> GestureDefinitions() {
    return {
        {
            .Semantic = "explain_small",
            .VariantId = "gesture.explain.left",
            .RequiredPosture = "standing",
            .MinIntensity = 0.2,
            .MaxIntensity = 0.8,
            .DurationMs = 700,
            .CooldownMs = 500,
            .Interruptible = true,
            .SelectionWeight = 1.0,
        },
        {
            .Semantic = "explain_small",
            .VariantId = "gesture.explain.right",
            .RequiredPosture = "standing",
            .MinIntensity = 0.2,
            .MaxIntensity = 0.8,
            .DurationMs = 700,
            .CooldownMs = 500,
            .Interruptible = true,
            .SelectionWeight = 1.0,
        },
        {
            .Semantic = "thinking",
            .VariantId = "gesture.thinking.chin",
            .RequiredPosture = std::nullopt,
            .DurationMs = 1'000,
            .CooldownMs = 0,
            .Interruptible = false,
            .SelectionWeight = 1.0,
        },
    };
}

void GestureSelectionIsDataDrivenAndDeterministic() {
    Gahyeon::GestureRuntime first(GestureDefinitions(), 42);
    Gahyeon::GestureRuntime second(GestureDefinitions(), 42);
    const Gahyeon::GestureIntent request{
        .Semantic = "explain_small",
        .Intensity = 0.6,
        .CurrentPosture = "standing",
        .Priority = 20,
    };
    Require(first.Request(request, 0) == Gahyeon::GestureRequestResult::Selected
            && second.Request(request, 0) == Gahyeon::GestureRequestResult::Selected,
        "semantic gesture should select a local profile variant");
    Require(first.Active()->VariantId == second.Active()->VariantId,
        "same profile seed and event order must reproduce gesture selection");
    Require(first.Active()->VariantId == "gesture.explain.left"
            || first.Active()->VariantId == "gesture.explain.right",
        "Backend semantic must resolve only to a local data definition asset key");

    Gahyeon::GestureRuntime constrained(GestureDefinitions(), 42);
    auto wrongPosture = request;
    wrongPosture.CurrentPosture = "sitting";
    Require(constrained.Request(wrongPosture, 0)
            == Gahyeon::GestureRequestResult::NoCandidate,
        "posture constraints should be enforced locally, not by the LLM");
    auto wrongIntensity = request;
    wrongIntensity.Intensity = 0.95;
    Require(constrained.Request(wrongIntensity, 1)
            == Gahyeon::GestureRequestResult::NoCandidate,
        "profile intensity range should reject unsuitable animation variants");
}

void GestureInterruptionCooldownAndGenerationAreExplicit() {
    Gahyeon::GestureRuntime gestures(GestureDefinitions(), 7);
    gestures.SetGeneration(3);
    Require(gestures.Request(Gahyeon::GestureIntent{
            .Semantic = "thinking",
            .Intensity = 0.7,
            .CurrentPosture = "standing",
            .Priority = 10,
            .GenerationId = 3,
        }, 0) == Gahyeon::GestureRequestResult::Selected,
        "thinking behavior should select its noninterruptible local variant");
    Require(gestures.Request(Gahyeon::GestureIntent{
            .Semantic = "explain_small",
            .Intensity = 0.5,
            .CurrentPosture = "standing",
            .Priority = 100,
            .GenerationId = 3,
        }, 100) == Gahyeon::GestureRequestResult::Busy,
        "noninterruptible animation must finish even when a higher intent arrives");
    Require(!gestures.Advance(999) && gestures.Advance(1'000),
        "gesture lifetime should end from monotonic Behavior time without an LLM callback");
    Require(gestures.Request(Gahyeon::GestureIntent{
            .Semantic = "explain_small",
            .Intensity = 0.5,
            .CurrentPosture = "standing",
            .Priority = 20,
            .GenerationId = 3,
        }, 1'001) == Gahyeon::GestureRequestResult::Selected,
        "available semantic gesture should run after the active animation finishes");
    const std::string activeVariant = gestures.Active()->VariantId;
    Require(gestures.SetGeneration(4).value_or("") == activeVariant,
        "barge-in should return the generation-bound animation key to stop/blend out");
    Require(!gestures.Active().has_value(),
        "new generation should clear stale conversational gesture ownership");
    Require(gestures.Request(Gahyeon::GestureIntent{
            .Semantic = "thinking",
            .Intensity = 0.5,
            .CurrentPosture = "standing",
            .Priority = 10,
            .GenerationId = 3,
        }, 1'100) == Gahyeon::GestureRequestResult::Stale,
        "late Cognition gesture must not re-enter after barge-in");
}

void EmptyGestureProfileIsAValidNoAssetStartupState() {
    Gahyeon::GestureRuntime gestures({});
    gestures.SetGeneration(1);
    Require(gestures.Request(Gahyeon::GestureIntent{
            .Semantic = "explain_small",
            .Intensity = 0.5,
            .GenerationId = 1,
        }, 0) == Gahyeon::GestureRequestResult::NoCandidate,
        "Stage must boot before a character-specific gesture profile is loaded");
    Require(!gestures.Active().has_value(),
        "missing presentation assets must not invent or own an animation");
    Require(gestures.ConfigureDefinitions(GestureDefinitions())
            && gestures.Request(Gahyeon::GestureIntent{
                .Semantic = "explain_small",
                .Intensity = 0.5,
                .CurrentPosture = "standing",
                .GenerationId = 1,
            }, 1) == Gahyeon::GestureRequestResult::Selected,
        "a character DataAsset profile should become selectable without rebuilding Core");
    const std::string activeBeforeInvalid = gestures.Active()->VariantId;
    auto invalid = GestureDefinitions();
    invalid.push_back(invalid.front());
    Require(!gestures.ConfigureDefinitions(std::move(invalid))
            && gestures.Active().has_value()
            && gestures.Active()->VariantId == activeBeforeInvalid,
        "invalid hot profile must fail atomically without disturbing the active gesture");
}

void ProtocolGestureSemanticSelectsLocalVariantWithoutAssetIds() {
    Gahyeon::RealtimeCharacterCoordinator character;
    Gahyeon::SpeechPlaybackCoordinator playback(character);
    Gahyeon::GestureRuntime gestures(GestureDefinitions(), 99);
    Gahyeon::ProtocolEventRuntime protocol(character, playback, nullptr, &gestures);

    const auto applied = protocol.Apply(Gahyeon::ProtocolMessage{
        .Type = "gesture.intent",
        .GenerationId = 2,
        .Semantic = "explain_small",
        .Intensity = 0.65,
        .Priority = 40,
        .ExpiresAfterMs = 1'500,
        .CurrentPosture = "standing",
    }, 100);
    Require(applied.Status == Gahyeon::ProtocolApplyStatus::Applied,
        "wire gesture semantic should apply through the game-thread protocol boundary");
    Require(character.Intents().CurrentGeneration() == 2
            && gestures.CurrentGeneration() == 2,
        "future gesture generation should converge intent and behavior watermarks together");
    Require(gestures.Active().has_value()
            && gestures.Active()->Semantic == "explain_small"
            && gestures.Active()->VariantId.starts_with("gesture.explain."),
        "wire payload must contain semantics while local definitions own asset selection");
    Require(Value(character.Intents().Resolve(100), Gahyeon::IntentChannel::Gesture)
            == "explain_small",
        "resolved debug channel should expose semantic intent rather than variant asset ID");
}

void LatencyTraceComputesBoundedAcceptancePercentiles() {
    Gahyeon::LatencyTrace trace(100, 4);
    for (Gahyeon::Millis duration = 1; duration <= 100; ++duration) {
        Require(trace.Record(Gahyeon::LatencyMetric::VadToListening, duration)
                == Gahyeon::LatencyTraceResult::Recorded,
            "direct latency samples should be accepted");
    }
    const auto summary = trace.Summary(Gahyeon::LatencyMetric::VadToListening);
    Require(summary.TotalCount == 100 && summary.RetainedCount == 100,
        "latency summary should expose total and retained sample counts");
    Require(summary.P50Ms == 50 && summary.P95Ms == 95
            && summary.P99Ms == 99 && summary.WorstMs == 100,
        "nearest-rank p50/p95/p99 and worst must be deterministic");
    Require(summary.PassesP95 && summary.BudgetMs == 100,
        "VAD p95 at 95ms should pass the RT-02 budget");

    for (int index = 0; index < 10'000; ++index) {
        trace.Record(Gahyeon::LatencyMetric::VisemeOnsetOffset, index % 120);
    }
    const auto bounded = trace.Summary(Gahyeon::LatencyMetric::VisemeOnsetOffset);
    Require(bounded.TotalCount == 10'000 && bounded.RetainedCount == 100,
        "long-running trace must overwrite a fixed ring instead of growing memory");
    Require(bounded.BudgetViolations > 0,
        "samples beyond the viseme budget must remain observable across ring overwrite");

    Gahyeon::LatencyTrace exportTrace(3, 1);
    for (const auto value : {10, 20, 30, 40}) {
        exportTrace.Record(Gahyeon::LatencyMetric::VadToListening, value);
    }
    Require(exportTrace.Samples(Gahyeon::LatencyMetric::VadToListening)
            == std::vector<Gahyeon::Millis>({20, 30, 40}),
        "physical acceptance export must preserve chronological retained ring order");
    exportTrace.ClearSamples(Gahyeon::LatencyMetric::VadToListening);
    Require(exportTrace.Samples(Gahyeon::LatencyMetric::VadToListening).empty(),
        "a new benchmark run must be able to isolate its own latency samples");
}

void LatencyTraceBoundsPendingSpansAndRejectsClockRewind() {
    Gahyeon::LatencyTrace trace(8, 2);
    Require(trace.Begin(Gahyeon::LatencyMetric::VadToListening, 1, 100)
            == Gahyeon::LatencyTraceResult::Started,
        "first span should start");
    Require(trace.Begin(Gahyeon::LatencyMetric::BargeInToAudioStop, 2, 100)
            == Gahyeon::LatencyTraceResult::Started,
        "second concurrent span should start");
    Require(trace.Begin(Gahyeon::LatencyMetric::ReconnectToSnapshot, 3, 100)
            == Gahyeon::LatencyTraceResult::Full,
        "pending span storage must remain bounded under missing callbacks");
    Require(trace.Begin(Gahyeon::LatencyMetric::VadToListening, 1, 100)
            == Gahyeon::LatencyTraceResult::Duplicate,
        "duplicate span IDs must not overwrite their original start");
    Require(trace.End(1, 99) == Gahyeon::LatencyTraceResult::NonMonotonic,
        "clock rewind must not create a negative latency sample");
    Require(trace.End(1, 140) == Gahyeon::LatencyTraceResult::Recorded,
        "valid end should record the elapsed span");
    Require(trace.Cancel(2) == Gahyeon::LatencyTraceResult::Recorded
            && trace.PendingCount() == 0,
        "disconnect/cancellation must reclaim pending capacity without a sample");
}

void LocalPresentationCallbacksFeedAcceptanceMetrics() {
    Gahyeon::LatencyTrace trace;
    Gahyeon::RealtimeCharacterCoordinator character;
    const auto oldGeneration = character.VoiceStarted(0);
    character.VoiceEnded(oldGeneration, 20);
    Gahyeon::LipSyncRuntime lipSync({}, &trace);
    Gahyeon::SpeechPlaybackCoordinator playback(character, 4, &lipSync);
    playback.SetGeneration(oldGeneration);
    playback.Prepared(Gahyeon::PreparedSpeech{
        .GenerationId = oldGeneration,
        .UtteranceId = "measured-audio",
        .FinalSegment = true,
    });
    playback.AcquireNext();
    playback.PlaybackStarted("measured-audio", 100);
    Gahyeon::VoiceInteractionController voice(
        character,
        playback,
        Gahyeon::VoiceActivityConfig{
            .StartThreshold = 0.05,
            .StopThreshold = 0.02,
            .AttackMs = 30,
            .ReleaseMs = 100,
        },
        &trace);
    voice.Observe(0.08, 200);
    const auto started = voice.Observe(0.08, 230);
    Require(started.Event == Gahyeon::VoiceActivityEvent::Started
            && started.InterruptedUtteranceId.has_value(),
        "measured barge-in should create visual and audio-stop spans");
    Require(voice.MarkListeningPresented(started.GenerationId, 280)
            == Gahyeon::LatencyTraceResult::Recorded,
        "animation bridge should close VAD-to-listening at actual presentation time");
    Require(voice.MarkInterruptedAudioStopped(started.GenerationId, 330)
            == Gahyeon::LatencyTraceResult::Recorded,
        "audio device callback should close interruption latency independently");
    Require(trace.Summary(Gahyeon::LatencyMetric::VadToListening).P95Ms == 50
            && trace.Summary(Gahyeon::LatencyMetric::BargeInToAudioStop).P95Ms == 100,
        "local callback metrics should reflect actual 50ms/100ms boundaries");

    Gahyeon::ConnectionConvergenceRuntime convergence(2'000, &trace);
    convergence.BeginConnection(400);
    convergence.Welcome(450);
    convergence.SnapshotApplied(1'800);
    Require(trace.Summary(Gahyeon::LatencyMetric::ReconnectToSnapshot).P95Ms == 1'400,
        "snapshot application should close reconnect convergence span");

    Gahyeon::LipSyncRuntime measuredLip({}, &trace);
    measuredLip.SetGeneration(1);
    measuredLip.BeginPlayback(Gahyeon::PreparedSpeech{
        .GenerationId = 1,
        .UtteranceId = "viseme-measured",
        .Visemes = {{"aa", 100, 80, 1.0}},
    });
    measuredLip.Sample(112, 0.0);
    Require(trace.Summary(Gahyeon::LatencyMetric::VisemeOnsetOffset).TotalCount == 0,
        "sampling a desired cue must not claim that the face rendered it");
    Require(!measuredLip.ConfirmVisemePresented("aa", 111),
        "presentation acknowledgement cannot precede the sampled audio cursor");
    Require(measuredLip.ConfirmVisemePresented("aa", 112),
        "the physical presentation bridge should acknowledge the active cue");
    Require(!measuredLip.ConfirmVisemePresented("aa", 112),
        "one physical cue must produce at most one latency sample");
    Require(trace.Summary(Gahyeon::LatencyMetric::VisemeOnsetOffset).P95Ms == 12,
        "face application should record first presented viseme onset offset");
}

Gahyeon::WorldActionTarget DeskAction(
    std::string actionId,
    Gahyeon::Generation revision,
    std::optional<Gahyeon::Generation> generation = std::nullopt) {
    return Gahyeon::WorldActionTarget{
        .ActionId = std::move(actionId),
        .WorldId = "gahyeon-home",
        .ExpectedRevision = revision,
        .Room = "workspace",
        .Position = {7.0, 0.0, -2.0},
        .Activity = "work",
        .InteractionTarget = "desk",
        .GenerationId = generation,
        .TimeoutMs = 5'000,
    };
}

void WorldActionCommitsOnlyAfterLocalArrivalAndInteraction() {
    Gahyeon::WorldStateRuntime world;
    auto initial = WorldSnapshot(7);
    initial.CurrentRoom = "bedroom";
    initial.Position = {0.0, 0.0, 0.0};
    initial.Activity = "idle";
    world.ApplySnapshot(initial);
    Gahyeon::WorldActionRuntime actions;

    Require(actions.Start(DeskAction("action-desk", 7), world, 100)
            == Gahyeon::WorldActionResult::Accepted,
        "matching authoritative revision should start local navigation");
    Require(actions.Active()->Phase == Gahyeon::WorldActionPhase::Navigating,
        "target should begin in local navigation phase");
    Require(world.Current()->CurrentRoom == "bedroom"
            && world.Current()->Revision == 7,
        "starting an action must not pretend Backend World State already moved");
    Require(!actions.Complete(
            "action-desk", "completed", "", {7.0, 0.0, -2.0}, 200).has_value(),
        "success completion before arrival/interaction must be rejected");
    Require(actions.NavigationArrived("action-desk", 300)
            == Gahyeon::WorldActionResult::Accepted,
        "navigation callback should enter interaction phase");
    const auto completed = actions.Complete(
        "action-desk", "completed", "", {7.0, 0.0, -2.0}, 500);
    Require(completed.has_value() && completed->ExpectedRevision == 7
            && completed->Outcome == "completed",
        "interaction completion should produce a revision-guarded Backend command");
    Require(world.Current()->CurrentRoom == "bedroom",
        "local completion still must wait for Backend commit/replayed state");
    Require(!actions.Complete(
            "action-desk", "completed", "", {7.0, 0.0, -2.0}, 600).has_value(),
        "duplicate engine callback must not emit a second completion command");
    Require(actions.Start(DeskAction("action-desk", 7), world, 700)
            == Gahyeon::WorldActionResult::Duplicate,
        "replayed target already completed locally must remain idempotent");
}

void WorldActionRejectsStaleTargetsAndReportsTimeoutOrBargeIn() {
    Gahyeon::WorldStateRuntime world;
    world.ApplySnapshot(WorldSnapshot(10));
    Gahyeon::WorldActionRuntime actions;
    actions.SetGeneration(3, 0, {1.0, 2.0, 3.0});
    Require(actions.Start(DeskAction("stale-revision", 9, 3), world, 10)
            == Gahyeon::WorldActionResult::Stale,
        "target based on an older World revision must not move the character");
    Require(actions.Start(DeskAction("future-revision", 11, 3), world, 20)
            == Gahyeon::WorldActionResult::Invalid,
        "target from an unknown future revision should require snapshot convergence");

    auto timeoutTarget = DeskAction("timeout-action", 10, 3);
    timeoutTarget.TimeoutMs = 100;
    Require(actions.Start(timeoutTarget, world, 30) == Gahyeon::WorldActionResult::Accepted,
        "current action should start");
    const auto timeout = actions.Advance(131, {2.0, 0.0, -1.0});
    Require(timeout.has_value() && timeout->Outcome == "failed"
            && timeout->Reason == "timeout",
        "local timeout should report failure without freezing Behavior");

    Require(actions.Start(DeskAction("conversation-action", 10, 3), world, 200)
            == Gahyeon::WorldActionResult::Accepted,
        "second current action should start after timeout cleanup");
    const auto cancelled = actions.SetGeneration(4, 250, {3.0, 0.0, -1.0});
    Require(cancelled.has_value() && cancelled->Outcome == "cancelled"
            && cancelled->Reason == "superseded_generation",
        "new conversation generation should cancel only its stale bound action");
    Require(!actions.Active().has_value(),
        "barge-in cancellation must release local navigation ownership");
}

void ProtocolWorldTargetStartsLocalActionWithoutPrecommittingWorldState() {
    Gahyeon::RealtimeCharacterCoordinator character;
    Gahyeon::SpeechPlaybackCoordinator playback(character);
    Gahyeon::WorldStateRuntime world;
    Gahyeon::WorldActionRuntime actions;
    auto initial = WorldSnapshot(7);
    initial.CurrentRoom = "bedroom";
    initial.Position = {0.0, 0.0, 0.0};
    initial.Activity = "idle";
    world.ApplySnapshot(initial);
    Gahyeon::ProtocolEventRuntime runtime(
        character, playback, nullptr, nullptr, &world, &actions);

    Gahyeon::ProtocolMessage target;
    target.Type = "world.transition.target";
    target.ActionId = "protocol-desk";
    target.WorldId = "gahyeon-home";
    target.ExpectedRevision = 7;
    target.Room = "workspace";
    target.TargetPosition = {7.0, 0.0, -2.0};
    target.Activity = "work";
    target.InteractionTarget = "desk";
    target.ActionTimeoutMs = 5'000;

    Require(runtime.Apply(target, 100).Status == Gahyeon::ProtocolApplyStatus::Applied,
        "durable world target should enter the local execution boundary");
    Require(actions.Active().has_value()
            && actions.Active()->Phase == Gahyeon::WorldActionPhase::Navigating,
        "protocol target should start navigation, not mutate authoritative state");
    Require(world.Current()->Revision == 7 && world.Current()->CurrentRoom == "bedroom",
        "protocol target must wait for Backend completion commit and replay");
    Require(Value(character.Intents().Resolve(101), Gahyeon::IntentChannel::Phase)
            == "executing_action",
        "local Behavior presentation should expose action execution immediately");

    Gahyeon::ProtocolMessage coreResult;
    coreResult.Type = "character.action.result";
    coreResult.ActionId = "protocol-desk";
    coreResult.Result = "committed";
    Require(runtime.Apply(coreResult, 150).Status == Gahyeon::ProtocolApplyStatus::Applied,
        "durable Core completion should converge a connected renderer");
    Require(!actions.Active().has_value(),
        "Core-owned Headless completion must stop late local navigation");
    Require(Value(character.Intents().Resolve(151), Gahyeon::IntentChannel::Phase) == "idle",
        "authoritative action result should release executing_action presentation");

    Gahyeon::WorldActionRuntime staleActions;
    Gahyeon::ProtocolEventRuntime staleRuntime(
        character, playback, nullptr, nullptr, &world, &staleActions);
    target.ActionId = "stale-protocol-desk";
    target.ExpectedRevision = 6;
    Require(staleRuntime.Apply(target, 200).Status == Gahyeon::ProtocolApplyStatus::Stale,
        "replayed target from an older World revision must be rejected");
}

void ProtocolGenerationAdvanceReturnsCancelledWorldActionForDurableEgress() {
    Gahyeon::RealtimeCharacterCoordinator character;
    Gahyeon::SpeechPlaybackCoordinator playback(character);
    Gahyeon::WorldStateRuntime world;
    Gahyeon::WorldActionRuntime actions;
    world.ApplySnapshot(WorldSnapshot(7));
    Gahyeon::ProtocolEventRuntime runtime(
        character, playback, nullptr, nullptr, &world, &actions);

    Gahyeon::ProtocolMessage target;
    target.Type = "world.transition.target";
    target.ActionId = "generation-bound-action";
    target.WorldId = "gahyeon-home";
    target.ExpectedRevision = 7;
    target.Room = "workspace";
    target.TargetPosition = {7.0, 0.0, -2.0};
    target.Activity = "work";
    target.GenerationId = 0;
    target.ActionTimeoutMs = 5'000;
    Require(runtime.Apply(target, 100).Status == Gahyeon::ProtocolApplyStatus::Applied,
        "generation-bound action should start at the current generation");

    const auto advanced = runtime.Apply(Gahyeon::ProtocolMessage{
        .Type = "generation.advanced",
        .GenerationId = 1,
        .Outcome = "client_reset",
    }, 200);
    Require(advanced.Status == Gahyeon::ProtocolApplyStatus::Ignored,
        "generation control event need not emit a presentation intent");
    Require(advanced.ActionCompletion.has_value()
            && advanced.ActionCompletion->ActionId == "generation-bound-action"
            && advanced.ActionCompletion->Outcome == "cancelled"
            && advanced.ActionCompletion->Reason == "superseded_generation",
        "barge-in generation advance must return a durable world-action cancellation");
    Require(!actions.Active().has_value(),
        "generation cancellation must release navigation ownership immediately");
}

void ProtocolBackendCancellationStopsRendererOwnedAction() {
    Gahyeon::RealtimeCharacterCoordinator character;
    Gahyeon::SpeechPlaybackCoordinator playback(character);
    Gahyeon::WorldStateRuntime world;
    Gahyeon::WorldActionRuntime actions;
    world.ApplySnapshot(WorldSnapshot(31));
    Gahyeon::ProtocolEventRuntime runtime(
        character, playback, nullptr, nullptr, &world, &actions);

    Gahyeon::ProtocolMessage target;
    target.Type = "world.transition.target";
    target.ActionId = "conversation-cancelled-action";
    target.WorldId = "gahyeon-home";
    target.ExpectedRevision = 31;
    target.Room = "workspace";
    target.TargetPosition = {7.0, 0.0, -2.0};
    target.Activity = "work";
    target.InteractionTarget = "desk";
    target.ActionTimeoutMs = 60'000;
    Require(runtime.Apply(target, 100).Status == Gahyeon::ProtocolApplyStatus::Applied
            && actions.Active().has_value(),
        "renderer-owned action should be active before conversation cancellation");

    Gahyeon::ProtocolMessage cancelled;
    cancelled.Type = "character.action.result";
    cancelled.ActionId = target.ActionId;
    cancelled.Result = "recorded_failure";
    Require(runtime.Apply(cancelled, 110).Status == Gahyeon::ProtocolApplyStatus::Applied,
        "Backend cancellation result should be accepted as authoritative");
    Require(!actions.Active().has_value(),
        "Backend cancellation must release renderer navigation and interaction ownership");
    Require(Value(character.Intents().Resolve(111), Gahyeon::IntentChannel::Phase) == "idle",
        "Backend cancellation must release executing_action presentation immediately");
}

void WorldActionCompletionOutboxRetriesOfflineUntilBackendAcknowledges() {
    Gahyeon::WorldActionCompletionOutbox outbox(2, 250, 1'000);
    Gahyeon::WorldActionCompletion completion{
        .ActionId = "offline-desk",
        .ExpectedRevision = 7,
        .Outcome = "completed",
        .Reason = "",
        .FinalPosition = {7.0, 0.0, -2.0},
    };
    Require(outbox.Enqueue(completion, 100) == Gahyeon::CompletionOutboxResult::Accepted,
        "local completion should enter the command outbox before network send");
    Require(outbox.Enqueue(completion, 100) == Gahyeon::CompletionOutboxResult::Duplicate,
        "duplicate engine callbacks must not create duplicate commands");
    Require(outbox.Due(100).has_value() && outbox.Due(100)->Attempts == 0,
        "new completion should be immediately sendable");
    Require(outbox.MarkAttempt("offline-desk", 100)
            == Gahyeon::CompletionOutboxResult::Accepted,
        "failed/disconnected send should schedule retry");
    Require(!outbox.Due(349).has_value() && outbox.Due(350).has_value(),
        "first retry should honor the 250ms backoff without losing the command");
    Require(outbox.MarkAttempt("offline-desk", 350)
            == Gahyeon::CompletionOutboxResult::Accepted,
        "second disconnected attempt should remain retryable");
    Require(!outbox.Due(849).has_value() && outbox.Due(850)->Attempts == 2,
        "retry delay should back off exponentially while offline");
    const auto saved = outbox.Snapshot(600);
    Gahyeon::WorldActionCompletionOutbox restored(2, 250, 1'000);
    Require(restored.Restore(saved, 25) == Gahyeon::CompletionOutboxResult::Accepted,
        "SaveGame adapter should be able to restore unacknowledged completions");
    Require(!restored.Due(274).has_value() && restored.Due(275)->Attempts == 2,
        "restart should preserve remaining backoff without persisting monotonic timestamps");
    Require(restored.Acknowledge("offline-desk") && restored.Empty(),
        "Backend acknowledged/duplicate response should be the only removal boundary");
    Require(!restored.Acknowledge("offline-desk"),
        "replayed acknowledgement must remain idempotent");
}

void WorldActionCompletionOutboxIsBoundedAndRejectsClockRewind() {
    Gahyeon::WorldActionCompletionOutbox outbox(1);
    auto first = Gahyeon::WorldActionCompletion{
        "a", 1, "failed", "timeout", {0.0, 0.0, 0.0}};
    auto second = Gahyeon::WorldActionCompletion{
        "b", 1, "cancelled", "barge_in", {0.0, 0.0, 0.0}};
    Require(outbox.Enqueue(first, 10) == Gahyeon::CompletionOutboxResult::Accepted,
        "bounded outbox should accept available capacity");
    Require(outbox.Enqueue(second, 11) == Gahyeon::CompletionOutboxResult::Full,
        "bounded outbox must apply backpressure instead of dropping an older completion");
    Require(outbox.MarkAttempt("a", 9) == Gahyeon::CompletionOutboxResult::NonMonotonic,
        "clock rewind must not shorten retry deadlines");
}

void WorldActionCommandBridgeRemovesOnlyTerminalAcknowledgements() {
    Gahyeon::WorldActionCommandBridge bridge;
    auto completion = Gahyeon::WorldActionCompletion{
        "bridge-desk", 7, "completed", "", {7.0, 0.0, -2.0}};
    Require(bridge.Queue(completion, 0) == Gahyeon::CompletionOutboxResult::Accepted,
        "bridge must queue completion before exposing a network command");
    Require(bridge.NextCommand(0).has_value(),
        "queued command should be visible to the socket adapter");
    Require(bridge.CommandSent("bridge-desk", 0)
            == Gahyeon::CompletionOutboxResult::Accepted,
        "socket send must advance retry scheduling, not remove the command");
    Require(bridge.ApplyAcknowledgement({
                "bridge-desk", "committed", false, true, false})
            == Gahyeon::WorldActionAckResult::Deferred,
        "non-terminal progress acknowledgement must retain the outbox entry");
    Require(bridge.Outbox().Size() == 1,
        "command should remain until a terminal Backend decision");
    Require(bridge.ApplyAcknowledgement({
                "bridge-desk", "committed", true, true, false})
            == Gahyeon::WorldActionAckResult::Acknowledged,
        "terminal accepted acknowledgement should clear the command");
    Require(bridge.Outbox().Empty(),
        "accepted terminal acknowledgement should release bounded capacity");
}

void WorldActionCommandBridgeQuarantinesPermanentBackendRejection() {
    Gahyeon::WorldActionCommandBridge bridge(2, 2);
    auto completion = Gahyeon::WorldActionCompletion{
        "unknown-after-restore", 3, "completed", "", {1.0, 0.0, 1.0}};
    bridge.Queue(completion, 10);
    Require(bridge.ApplyAcknowledgement({
                "unknown-after-restore", "stale", true, false, false})
            == Gahyeon::WorldActionAckResult::Rejected,
        "terminal stale response must become observable instead of retrying forever");
    Require(bridge.Outbox().Empty() && bridge.Rejections().size() == 1,
        "permanent rejection should move to a bounded dead-letter collection");
    Require(bridge.Rejections().front().Completion.ActionId == "unknown-after-restore",
        "dead-letter must retain the exact action for diagnostics/recovery");
    Require(bridge.ApplyAcknowledgement({
                "missing", "committed", true, true, false})
            == Gahyeon::WorldActionAckResult::Unknown,
        "late duplicate ACK for an absent command must remain harmless");
}

void ClientSaveStateRestoresCursorOutboxAndDeadLettersAcrossProcessRestart() {
    Gahyeon::ReplayCursorRuntime cursor(42);
    Gahyeon::WorldActionCommandBridge actions(4, 4);
    actions.Queue({"pending-save", 7, "completed", "", {7.0, 0.0, -2.0}}, 100);
    actions.CommandSent("pending-save", 100);
    actions.Queue({"rejected-save", 6, "completed", "", {1.0, 0.0, 1.0}}, 100);
    actions.ApplyAcknowledgement({"rejected-save", "stale", true, false, false});

    const auto saved = Gahyeon::ClientRuntimeSaveStateCodec::Capture(
        cursor, actions, 37, 200);
    Gahyeon::WorldActionCommandBridge restored(4, 4);
    const auto result = Gahyeon::ClientRuntimeSaveStateCodec::Restore(
        saved, restored, 10);

    Require(result.Result == Gahyeon::ClientSaveStateResult::Restored
            && result.DurableSequence == 42
            && result.InteractionGeneration == 37,
        "SaveGame state should restore cursor and stale-result watermark exactly");
    Require(!restored.NextCommand(159).has_value()
            && restored.NextCommand(160)->Completion.ActionId == "pending-save",
        "SaveGame state should preserve remaining retry delay across monotonic reset");
    Require(restored.Rejections().size() == 1
            && restored.Rejections().front().Completion.ActionId == "rejected-save",
        "dead-letter diagnostics must survive client process restart");
}

void ClientSaveStateMigratesV1GenerationToZero() {
    Gahyeon::ClientRuntimeSaveState legacy;
    legacy.SchemaVersion = 1;
    legacy.DurableSequence = 8;
    legacy.InteractionGeneration = 99; // field did not exist in v1 and must be ignored
    Gahyeon::WorldActionCommandBridge actions;
    const auto restored = Gahyeon::ClientRuntimeSaveStateCodec::Restore(
        legacy, actions, 0);
    Require(restored.Result == Gahyeon::ClientSaveStateResult::Restored
            && restored.DurableSequence == 8
            && restored.InteractionGeneration == 0,
        "v1 SaveGame must migrate without trusting a field absent from that schema");
}

void ClientSaveStateRejectsFutureSchemaWithoutMutatingRuntime() {
    Gahyeon::WorldActionCommandBridge actions(2, 2);
    actions.Queue({"existing", 1, "failed", "timeout", {0.0, 0.0, 0.0}}, 0);
    Gahyeon::ClientRuntimeSaveState future;
    future.SchemaVersion = 99;
    future.DurableSequence = 100;

    const auto result = Gahyeon::ClientRuntimeSaveStateCodec::Restore(
        future, actions, 10);

    Require(result.Result == Gahyeon::ClientSaveStateResult::UnsupportedVersion,
        "unknown future SaveGame schema must fail closed");
    Require(actions.Outbox().Find("existing").has_value(),
        "failed SaveGame restore must not destroy the active runtime state");
}

void ProtocolIngressMailboxProtectsDurableReplayUnderSaturation() {
    Gahyeon::ProtocolIngressMailbox mailbox(2);
    Gahyeon::ProtocolMessage pose;
    pose.Type = "attention.target";
    pose.Semantic = "user";
    Require(mailbox.TryPush({pose, false, 0}) == Gahyeon::ProtocolIngressResult::Accepted,
        "ephemeral observation should enter available ingress capacity");
    Gahyeon::ProtocolMessage durableOne;
    durableOne.Type = "future.one";
    Gahyeon::ProtocolMessage durableTwo;
    durableTwo.Type = "future.two";
    Require(mailbox.TryPush({durableOne, true, 10})
            == Gahyeon::ProtocolIngressResult::Accepted,
        "durable replay should enter available ingress capacity");
    Require(mailbox.TryPush({durableTwo, true, 11})
            == Gahyeon::ProtocolIngressResult::EvictedEphemeral,
        "durable replay should evict stale ephemeral data under saturation");
    Require(mailbox.DroppedEphemeralCount() == 1 && mailbox.Size() == 2,
        "ephemeral eviction must be observable and bounded");
    Gahyeon::ProtocolMessage durableThree;
    durableThree.Type = "future.three";
    Require(mailbox.TryPush({durableThree, true, 12})
            == Gahyeon::ProtocolIngressResult::Full,
        "all-durable saturation must reject network admission instead of losing replay");
    Require(mailbox.RejectedDurableCount() == 1,
        "socket adapter must be able to reconnect when durable admission is rejected");
}

void GameThreadDispatcherRetriesBackpressureBeforeAdvancingDurableCursor() {
    Gahyeon::RealtimeCharacterCoordinator character;
    Gahyeon::SpeechPlaybackCoordinator playback(character, 1);
    Gahyeon::WorldStateRuntime world;
    Gahyeon::WorldActionRuntime actions;
    Gahyeon::ProtocolEventRuntime runtime(
        character, playback, nullptr, nullptr, &world, &actions);
    Gahyeon::ReplayCursorRuntime cursor(0);
    cursor.BeginConnection();
    Require(cursor.Welcome(0) == Gahyeon::ReplayCursorResult::Advanced,
        "dispatcher harness requires completed welcome handshake");
    Gahyeon::ProtocolIngressMailbox ingress(4);
    Gahyeon::ProtocolGameThreadDispatcher dispatcher(ingress, runtime, cursor);

    Gahyeon::ProtocolMessage first;
    first.Type = "speech.prepared";
    first.GenerationId = 1;
    first.UtteranceId = "dispatcher-1";
    Gahyeon::ProtocolMessage second = first;
    second.UtteranceId = "dispatcher-2";
    second.UtteranceIndex = 1;
    ingress.TryPush({first, true, 1});
    ingress.TryPush({second, true, 2});

    const auto saturated = dispatcher.Drain(2, 100);
    Require(saturated.Backpressured && saturated.SafeAcknowledgement == 1,
        "backpressured durable event must remain unacknowledged for replay safety");
    Require(ingress.Size() == 1,
        "backpressured event must be returned to the game-thread ingress head");
    Require(playback.AcquireNext()->UtteranceId == "dispatcher-1",
        "audio adapter should release queue capacity without touching the socket thread");
    const auto resumed = dispatcher.Drain(1, 101);
    Require(!resumed.Backpressured && resumed.SafeAcknowledgement == 2
            && resumed.AckReady,
        "retry should advance cursor only after the isolated event applies");
}

void NetworkBridgeRequiresSaveBeforeAckAndKeepsActionUntilTerminalAck() {
    Gahyeon::ReplayCursorRuntime cursor(0);
    cursor.BeginConnection();
    cursor.Welcome(0);
    cursor.CompleteDurable(5);
    Gahyeon::WorldActionCommandBridge actions;
    actions.Queue({"network-action", 4, "completed", "", {2.0, 0.0, 1.0}}, 100);
    Gahyeon::ProtocolNetworkEgressRuntime egress(cursor, actions);

    Require(!egress.Next(100).has_value(),
        "neither cursor nor action may be sent before SaveGame persistence succeeds");
    Require(egress.PersistenceConfirmed(5),
        "SaveGame callback should confirm the exact safe durable cursor");
    Require(egress.ActionPersistenceConfirmed("network-action"),
        "SaveGame callback should confirm the pending action outbox item");
    const auto ack = egress.Next(100);
    Require(ack->Type == Gahyeon::OutboundProtocolCommandType::ClientAck
            && ack->Sequence == 5,
        "persisted cursor acknowledgement should precede retryable commands");
    Require(egress.MarkSent(ack.value(), 100) == Gahyeon::NetworkEgressResult::Sent,
        "non-blocking socket success should mark the cursor acknowledged");

    const auto command = egress.Next(100);
    Require(command->Type == Gahyeon::OutboundProtocolCommandType::ActionCompletion
            && command->Completion->ActionId == "network-action",
        "action completion should flow through the normalized socket command boundary");
    Require(egress.MarkSent(command.value(), 100) == Gahyeon::NetworkEgressResult::Sent,
        "socket send should schedule action retry without removing the outbox item");
    Require(actions.Outbox().Size() == 1,
        "send success alone must not lose an unacknowledged action command");
    Require(egress.ActionAcknowledged({
                "network-action", "committed", true, true, false})
            == Gahyeon::WorldActionAckResult::Acknowledged,
        "terminal Backend ACK should release the action outbox");
}

void NetworkIngressAdapterRequestsReconnectInsteadOfDroppingDurableEvent() {
    Gahyeon::ProtocolIngressMailbox ingress(1);
    Gahyeon::ProtocolNetworkIngressAdapter adapter(ingress);
    Gahyeon::ProtocolMessage first;
    first.Type = "future.first";
    Gahyeon::ProtocolMessage second;
    second.Type = "future.second";
    Require(adapter.OnEvent({first, true, 1})
            == Gahyeon::NetworkIngressDirective::Accepted,
        "socket callback should enqueue durable event without applying game state");
    Require(adapter.OnEvent({second, true, 2})
            == Gahyeon::NetworkIngressDirective::ReconnectRequired,
        "durable saturation must close/reconnect so Backend replay can recover it");
}

void DurableWorldSnapshotFlowsFromSocketMailboxToGameThreadAtomically() {
    Gahyeon::RealtimeCharacterCoordinator character;
    Gahyeon::SpeechPlaybackCoordinator playback(character);
    Gahyeon::WorldStateRuntime world;
    Gahyeon::ProtocolEventRuntime runtime(
        character, playback, nullptr, nullptr, &world, nullptr);
    Gahyeon::ReplayCursorRuntime cursor(8);
    cursor.BeginConnection();
    cursor.Welcome(8);
    Gahyeon::ProtocolIngressMailbox ingress(2);
    Gahyeon::ProtocolNetworkIngressAdapter network(ingress);
    Gahyeon::ProtocolGameThreadDispatcher gameThread(ingress, runtime, cursor);
    Gahyeon::ProtocolMessage message;
    message.Type = "world.snapshot";
    message.Snapshot = WorldSnapshot(9);

    Require(network.OnEvent({message, true, 9})
            == Gahyeon::NetworkIngressDirective::Accepted,
        "network thread should only normalize and enqueue a durable snapshot");
    Require(!world.Current().has_value(),
        "network callback must never mutate game-thread-owned World State");
    const auto applied = gameThread.Drain(1, 500);
    Require(world.Current().has_value() && world.Current()->Revision == 9,
        "game thread should atomically apply the complete authoritative snapshot");
    Require(applied.SafeAcknowledgement == 9,
        "durable snapshot cursor should advance only after atomic World apply");
}

void DisconnectRestartHarnessReplaysCursorAndDeliversSavedCompletion() {
    Gahyeon::ReplayCursorRuntime cursor(0);
    cursor.BeginConnection();
    cursor.Welcome(0);
    cursor.CompleteDurable(10);
    Gahyeon::WorldActionCommandBridge actions(4, 4);
    actions.Queue({"restart-delivery", 9, "completed", "", {4.0, 0.0, 2.0}}, 100);
    const auto disk = Gahyeon::ClientRuntimeSaveStateCodec::Capture(
        cursor, actions, 11, 100);

    Gahyeon::WorldActionCommandBridge restoredActions(4, 4);
    const auto restored = Gahyeon::ClientRuntimeSaveStateCodec::Restore(
        disk, restoredActions, 0);
    Require(restored.Result == Gahyeon::ClientSaveStateResult::Restored,
        "simulated process restart should restore a validated client SaveGame");
    Require(restored.InteractionGeneration == 11,
        "process restart must not rewind the interaction generation watermark");
    Gahyeon::ReplayCursorRuntime restoredCursor(restored.DurableSequence.value());
    restoredCursor.BeginConnection();
    Require(restoredCursor.Welcome(10) == Gahyeon::ReplayCursorResult::Advanced,
        "reconnect hello should resume exactly after the disk cursor");
    Gahyeon::ProtocolNetworkEgressRuntime egress(restoredCursor, restoredActions);
    Require(egress.ActionPersistenceConfirmed("restart-delivery"),
        "restored SaveGame item is already durable and may be admitted to egress");
    const auto command = egress.Next(0);
    Require(command.has_value()
            && command->Type == Gahyeon::OutboundProtocolCommandType::ActionCompletion,
        "saved completion should be retried immediately after reconnect");
    Require(egress.MarkSent(command.value(), 0) == Gahyeon::NetworkEgressResult::Sent,
        "reconnected socket send should preserve the completion until ACK");
    Require(egress.ActionAcknowledged({
                "restart-delivery", "duplicate", true, true, true})
            == Gahyeon::WorldActionAckResult::Acknowledged,
        "Backend idempotent duplicate ACK should safely finish crash recovery");
    Require(restoredActions.Outbox().Empty(),
        "restart harness should end with no lost or indefinitely pending completion");
}

void TenSecondCognitionHarnessKeepsBehaviorAndReflexCadence() {
    Gahyeon::RealtimeCharacterCoordinator character(0, 20'000);
    const auto generation = character.VoiceStarted(0);
    character.VoiceEnded(generation, 100);
    Gahyeon::AmbientMotionRuntime ambient(0, 77);
    Gahyeon::AttentionRuntime attention;
    std::size_t behaviorFrames = 0;
    bool reflexObserved = false;
    for (Gahyeon::Millis now = 100; now <= 10'100; now += 16) {
        const auto motion = ambient.Sample(now);
        Require(std::isfinite(motion.Breath) && std::isfinite(motion.MicroHeadYaw),
            "slow Cognition must not corrupt per-frame secondary motion");
        if (now >= 5'000 && now < 5'016) {
            Require(attention.SetUserTarget({1.0, 0.5, 0.1}, 1.0, now),
                "local user reflex should be admitted while Cognition is pending");
        }
        const auto look = attention.Sample(now);
        if (now >= 5'016 && look.TrackingWeight > 0.0) reflexObserved = true;
        ++behaviorFrames;
    }
    Require(behaviorFrames == 626 && reflexObserved,
        "10-second Cognition delay must miss zero 60Hz Behavior/Reflex evaluations");
    Require(Value(character.Intents().Resolve(10'100), Gahyeon::IntentChannel::Phase)
            == "thinking",
        "independent local cadence must not fabricate a Cognition completion");
}

void MockCognitionHarnessExercisesDelayFailureAndReordering() {
    Gahyeon::MockCognitionRuntime harness(4);
    Gahyeon::RealtimeCharacterCoordinator character(0, 10'000);
    const auto oldGeneration = character.VoiceStarted(0);
    character.VoiceEnded(oldGeneration, 100);
    Require(harness.Schedule(oldGeneration, "slow-old", 100, 10'000)
            == Gahyeon::MockCognitionScheduleResult::Accepted,
        "VS-5 harness must schedule a ten-second Cognition completion");

    const auto currentGeneration = character.VoiceStarted(200);
    character.VoiceEnded(currentGeneration, 300);
    Require(harness.Schedule(
                currentGeneration,
                "fast-failure",
                300,
                500,
                Gahyeon::MockCognitionOutcome::Failed)
            == Gahyeon::MockCognitionScheduleResult::Accepted,
        "VS-5 harness must schedule an independent half-second failure");
    Require(harness.TakeDue(799).empty(),
        "mock completion must not arrive before its declared latency");
    const auto first = harness.TakeDue(800);
    Require(first.size() == 1 && first.front().RequestId == "fast-failure"
            && first.front().Outcome == Gahyeon::MockCognitionOutcome::Failed,
        "shorter later request must complete first without blocking on old Cognition");
    Require(Value(character.Intents().Resolve(800), Gahyeon::IntentChannel::Phase)
            == "thinking",
        "mock failure must not fabricate Speaking presentation");

    const auto late = harness.TakeDue(10'100);
    Require(late.size() == 1 && late.front().RequestId == "slow-old",
        "delayed old completion must remain observable for stale-result testing");
    Require(!character.SpeechStarted(
                late.front().GenerationId, 10'100, late.front().RequestId),
        "out-of-order old Cognition completion must fail generation admission");
    Require(Value(character.Intents().Resolve(10'100), Gahyeon::IntentChannel::Phase)
            == "thinking",
        "stale completion must not replace current presentation");
}

void MockCognitionHarnessIsBoundedAndMonotonic() {
    Gahyeon::MockCognitionRuntime harness(1);
    Require(harness.Schedule(1, "one", 100, 1'000)
            == Gahyeon::MockCognitionScheduleResult::Accepted,
        "bounded harness should accept available capacity");
    Require(harness.Schedule(1, "overflow", 100, 1'000)
            == Gahyeon::MockCognitionScheduleResult::Full,
        "fault injection must backpressure instead of growing without bound");
    Require(harness.TakeDue(1'100).size() == 1,
        "due completion should release bounded capacity");
    Require(harness.TakeDue(1'099).empty() && harness.RejectedCount() == 2,
        "clock rewind must be rejected and observable");
}

void MalformedDurableEventIsIsolatedAndFollowingEventStillApplies() {
    Gahyeon::RealtimeCharacterCoordinator character;
    Gahyeon::SpeechPlaybackCoordinator playback(character);
    Gahyeon::ProtocolEventRuntime runtime(character, playback);
    Gahyeon::ReplayCursorRuntime cursor(0);
    cursor.BeginConnection();
    cursor.Welcome(0);
    Gahyeon::ProtocolIngressMailbox ingress(4);
    Gahyeon::ProtocolGameThreadDispatcher dispatcher(ingress, runtime, cursor);
    Gahyeon::ProtocolMessage malformed;
    malformed.Type = "emotion.target";
    malformed.Semantic = "happy";
    malformed.Intensity = 2.0;
    Gahyeon::ProtocolMessage valid;
    valid.Type = "attention.target";
    valid.Semantic = "user";
    ingress.TryPush({malformed, true, 1});
    ingress.TryPush({valid, true, 2});

    const auto batch = dispatcher.Drain(2, 100);

    Require(batch.IsolatedFailures == 1 && batch.Applied == 1,
        "malformed event should fail in isolation while the next event applies");
    Require(batch.SafeAcknowledgement == 2,
        "isolated malformed replay must not create an infinite reconnect loop");
    Require(Value(character.Intents().Resolve(100), Gahyeon::IntentChannel::Attention)
            == "user",
        "runtime must remain operational immediately after malformed input");
}

void GameThreadDispatcherBoundsPerFrameReplayWork() {
    Gahyeon::RealtimeCharacterCoordinator character;
    Gahyeon::SpeechPlaybackCoordinator playback(character);
    Gahyeon::ProtocolEventRuntime runtime(character, playback);
    Gahyeon::ReplayCursorRuntime cursor(0);
    cursor.BeginConnection();
    cursor.Welcome(0);
    Gahyeon::ProtocolIngressMailbox ingress(128);
    Gahyeon::ProtocolGameThreadDispatcher dispatcher(ingress, runtime, cursor);
    for (Gahyeon::Generation sequence = 1; sequence <= 100; ++sequence) {
        Gahyeon::ProtocolMessage extension;
        extension.Type = "future.extension";
        Require(ingress.TryPush({extension, true, sequence})
                == Gahyeon::ProtocolIngressResult::Accepted,
            "replay harness should enqueue its bounded durable backlog");
    }

    const auto firstFrame = dispatcher.Drain(8, 100);

    Require(firstFrame.Applied == 8 && firstFrame.SafeAcknowledgement == 8,
        "Game Thread must process no more than the explicit per-frame event budget");
    Require(ingress.Size() == 92,
        "remaining replay work should stay queued for later frames without blocking render");
}

void PreparedSpeechSegmentsRemainOrderedAndClearOnBargeIn() {
    Gahyeon::SpeechQueue queue(4);
    queue.SetGeneration(3);
    Require(queue.Enqueue({3, "segment-0", 0, 0, false, {}, {}, {}}) == Gahyeon::SpeechEnqueueResult::Accepted,
        "first speech segment should enqueue");
    Require(queue.Enqueue({3, "segment-1", 1, 0, true, {}, {}, {}}) == Gahyeon::SpeechEnqueueResult::Accepted,
        "second speech segment should enqueue without replacing the first");
    Require(queue.Pop()->UtteranceId == "segment-0", "speech queue must preserve segment order");
    Require(queue.MarkSequenceEnded({3, 2, "completed"})
            == Gahyeon::SpeechEnqueueResult::Accepted,
        "sequence end should be accepted for the current generation");
    Require(queue.SequenceEnded(), "sequence end must be observable before playback drains");
    Require(queue.ExpectedUtterances() == 2, "sequence should retain its utterance count");
    Require(!queue.SequenceDrained(), "queued audio must drain before the sequence is complete");
    Require(queue.Pop()->UtteranceId == "segment-1", "second segment should remain queued");
    Require(queue.SequenceDrained(), "ended sequence should complete after its audio drains");
    queue.SetGeneration(4);
    Require(queue.Size() == 0, "barge-in generation must clear queued old audio");
    Require(!queue.SequenceEnded(), "barge-in must reset the previous sequence end marker");
    Require(queue.Enqueue({3, "late-segment", 2, 0, true, {}, {}, {}}) == Gahyeon::SpeechEnqueueResult::Stale,
        "old-generation audio must not re-enter after barge-in");
}

void SpeechSequenceEndTranslatesWithoutStartingPlayback() {
    Gahyeon::ProtocolMessageTranslator translator;
    const auto translated = translator.Translate(Gahyeon::ProtocolMessage{
        .Type = "speech.sequence.ended",
        .GenerationId = 7,
        .UtteranceCount = 2,
        .Outcome = "completed",
    }, 800);
    Require(translated.Status == Gahyeon::TranslationStatus::Translated,
        "speech sequence end should be recognized");
    Require(!translated.Speech.has_value(), "sequence end must not create playable audio");
    Require(translated.SpeechEnd.has_value(), "sequence end metadata should reach the queue");
}

void PlaybackStateStartsOnlyFromTheAudioDeviceCallback() {
    Gahyeon::RealtimeCharacterCoordinator character;
    const auto generation = character.VoiceStarted(0);
    character.VoiceEnded(generation, 100);
    Gahyeon::SpeechPlaybackCoordinator playback(character);
    playback.SetGeneration(generation);
    Require(playback.Prepared({generation, "audio-1", 0, 0, true, {}, {}, {}})
            == Gahyeon::SpeechEnqueueResult::Accepted,
        "prepared audio should enter the playback queue");
    const auto acquired = playback.AcquireNext();
    Require(acquired.has_value(), "audio adapter should acquire prepared audio");
    Require(Value(character.Intents().Resolve(150), Gahyeon::IntentChannel::Phase) == "thinking",
        "prepared or acquired audio must not claim that playback started");
    Require(playback.PlaybackStarted("audio-1", 200),
        "matching audio-device start should enter speaking");
    Require(Value(character.Intents().Resolve(200), Gahyeon::IntentChannel::Phase) == "speaking",
        "actual audio start should enter speaking");
}

void TemporaryStreamingQueueGapDoesNotEndSpeaking() {
    Gahyeon::RealtimeCharacterCoordinator character;
    const auto generation = character.VoiceStarted(0);
    character.VoiceEnded(generation, 100);
    Gahyeon::SpeechPlaybackCoordinator playback(character);
    playback.SetGeneration(generation);
    playback.Prepared({generation, "audio-1", 0, 0, true, {}, {}, {}});
    playback.AcquireNext();
    playback.PlaybackStarted("audio-1", 200);
    playback.PlaybackFinished("audio-1", 300);
    Require(Value(character.Intents().Resolve(300), Gahyeon::IntentChannel::Phase) == "speaking",
        "an empty queue before sequence end is only a streaming gap");

    playback.Prepared({generation, "audio-2", 1, 0, true, {}, {}, {}});
    playback.AcquireNext();
    playback.PlaybackStarted("audio-2", 400);
    playback.SequenceEnded({generation, 2, "completed"}, 450);
    Require(Value(character.Intents().Resolve(450), Gahyeon::IntentChannel::Phase) == "speaking",
        "sequence end must wait for active audio");
    playback.PlaybackFinished("audio-2", 500);
    const auto ended = character.Intents().Resolve(500);
    Require(Value(ended, Gahyeon::IntentChannel::Phase) == "idle",
        "ended and drained sequence should return to idle");
    Require(Value(ended, Gahyeon::IntentChannel::Speech).empty(),
        "completed playback must clear the active utterance");
}

void BargeInStopsOwningOldPlaybackImmediately() {
    Gahyeon::RealtimeCharacterCoordinator character;
    const auto oldGeneration = character.VoiceStarted(0);
    character.VoiceEnded(oldGeneration, 100);
    Gahyeon::SpeechPlaybackCoordinator playback(character);
    playback.SetGeneration(oldGeneration);
    playback.Prepared({oldGeneration, "old-audio", 0, 0, true, {}, {}, {}});
    playback.AcquireNext();
    playback.PlaybackStarted("old-audio", 200);

    const auto newGeneration = character.VoiceStarted(250);
    const auto interrupted = playback.SetGeneration(newGeneration);
    Require(interrupted.value_or("") == "old-audio",
        "audio adapter must be told which active sound to stop");
    Require(!playback.PlaybackFinished("old-audio", 300),
        "late device callback from old generation must be ignored");
    Require(Value(character.Intents().Resolve(300), Gahyeon::IntentChannel::Phase) == "listening",
        "barge-in listening reflex must remain authoritative");
}

void ConsumedAudioCannotBeReenqueuedWithinTheSameGeneration() {
    Gahyeon::SpeechQueue queue;
    queue.SetGeneration(9);
    Require(queue.Enqueue({9, "audio-once", 0, 0, true, {}, {}, {}})
            == Gahyeon::SpeechEnqueueResult::Accepted,
        "first delivery should enqueue");
    queue.Pop();
    Require(queue.Enqueue({9, "audio-once", 0, 0, true, {}, {}, {}})
            == Gahyeon::SpeechEnqueueResult::Duplicate,
        "late duplicate must not replay already consumed audio");
}

void VoiceActivityHysteresisRejectsNoiseAndUsesReleaseHangover() {
    Gahyeon::VoiceActivityDetector vad(Gahyeon::VoiceActivityConfig{
        .StartThreshold = 0.05,
        .StopThreshold = 0.02,
        .AttackMs = 30,
        .ReleaseMs = 350,
    });
    Require(vad.Observe(0.06, 0) == Gahyeon::VoiceActivityEvent::None,
        "one loud frame must not trigger voice start");
    Require(vad.Observe(0.01, 20) == Gahyeon::VoiceActivityEvent::None,
        "short noise spike should reset attack");
    Require(vad.Observe(0.06, 100) == Gahyeon::VoiceActivityEvent::None,
        "sustained voice should begin its attack window");
    Require(vad.Observe(0.07, 130) == Gahyeon::VoiceActivityEvent::Started,
        "voice should start after the configured attack");
    Require(vad.Observe(0.01, 200) == Gahyeon::VoiceActivityEvent::None,
        "brief silence must not end speech");
    Require(vad.Observe(0.01, 549) == Gahyeon::VoiceActivityEvent::None,
        "release should retain speech through the hangover");
    Require(vad.Observe(0.01, 550) == Gahyeon::VoiceActivityEvent::Ended,
        "speech should end at the configured release boundary");
    Require(vad.Observe(2.0, 560) == Gahyeon::VoiceActivityEvent::Invalid,
        "invalid normalized levels must be rejected locally");
    Require(vad.Observe(0.0, 540) == Gahyeon::VoiceActivityEvent::Invalid,
        "non-monotonic timestamps must be rejected");
}

void LocalVadBargeInPreemptsPlaybackWithoutWaitingForBackend() {
    Gahyeon::RealtimeCharacterCoordinator character;
    const auto oldGeneration = character.VoiceStarted(0);
    character.VoiceEnded(oldGeneration, 50);
    Gahyeon::SpeechPlaybackCoordinator playback(character);
    playback.SetGeneration(oldGeneration);
    playback.Prepared({oldGeneration, "old-audio", 0, 0, true, {}, {}, {}});
    playback.AcquireNext();
    playback.PlaybackStarted("old-audio", 100);
    Gahyeon::VoiceInteractionController voice(
        character,
        playback,
        Gahyeon::VoiceActivityConfig{
            .StartThreshold = 0.05,
            .StopThreshold = 0.02,
            .AttackMs = 30,
            .ReleaseMs = 100,
        });

    voice.Observe(0.08, 200);
    const auto started = voice.Observe(0.08, 230);
    Require(started.Event == Gahyeon::VoiceActivityEvent::Started,
        "local VAD should report voice start");
    Require(started.InterruptedUtteranceId.value_or("") == "old-audio",
        "barge-in must identify audio to stop without a backend round trip");
    Require(started.GenerationId == oldGeneration + 1,
        "barge-in must establish a new cancellation generation");
    Require(Value(character.Intents().Resolve(230), Gahyeon::IntentChannel::Phase) == "listening",
        "local VAD should enter listening within the attack budget");

    voice.Observe(0.0, 300);
    const auto ended = voice.Observe(0.0, 400);
    Require(ended.Event == Gahyeon::VoiceActivityEvent::Ended,
        "release window should report voice end");
    Require(Value(character.Intents().Resolve(400), Gahyeon::IntentChannel::Phase) == "thinking",
        "voice end should enter thinking while ambient motion continues");
}

void CognitionTimeoutReturnsToAmbientAndInvalidatesLateResults() {
    Gahyeon::RealtimeCharacterCoordinator character(0, 500);
    const auto generation = character.VoiceStarted(0);
    character.VoiceEnded(generation, 100);
    Gahyeon::SpeechPlaybackCoordinator playback(character);
    playback.SetGeneration(generation);
    Gahyeon::VoiceInteractionController voice(character, playback);

    Require(!voice.Tick(599).has_value(), "thinking must remain before its deadline");
    const auto timeout = voice.Tick(600);
    Require(timeout.has_value(), "thinking deadline should advance cancellation generation");
    Require(timeout->GenerationId == generation + 1,
        "timeout must invalidate the pending backend generation");
    const auto state = character.Intents().Resolve(600);
    Require(Value(state, Gahyeon::IntentChannel::Phase) == "idle",
        "timed-out cognition should return to local idle");
    Require(Value(state, Gahyeon::IntentChannel::Posture) == "ambient_alive",
        "timeout must preserve autonomous ambient behavior");
    Require(!character.SpeechStarted(generation, 700, "late-audio"),
        "late speech from the timed-out generation must be rejected");
    Require(playback.Prepared({generation, "late-audio", 0, 0, true, {}, {}, {}})
            == Gahyeon::SpeechEnqueueResult::Stale,
        "late prepared audio must also be rejected after timeout");
}

void RecognitionFailureImmediatelyReturnsToIdleAndRejectsLateAudio() {
    Gahyeon::RealtimeCharacterCoordinator character(0, 10'000);
    const auto generation = character.VoiceStarted(0);
    character.VoiceEnded(generation, 100);
    Gahyeon::SpeechPlaybackCoordinator playback(character);
    playback.SetGeneration(generation);
    Gahyeon::VoiceInteractionController voice(character, playback);

    const auto cancelled = voice.FailRecognition(generation, 250);
    Require(cancelled.has_value(), "current STT failure should cancel the interaction");
    Require(cancelled->GenerationId == generation + 1,
        "STT failure must advance the stale-result watermark");
    Require(Value(character.Intents().Resolve(250), Gahyeon::IntentChannel::Phase) == "idle",
        "STT failure should not leave the character thinking until watchdog timeout");
    Require(!voice.FailRecognition(generation, 300).has_value(),
        "duplicate late STT failure must not advance generation twice");
    Require(playback.Prepared({generation, "late-stt-audio", 0, 0, true, {}, {}, {}})
            == Gahyeon::SpeechEnqueueResult::Stale,
        "audio prepared for the failed recognition generation must be rejected");
}

void CaptureAbortCancelsOnlyActiveSpeechAndResetsVadForRestart() {
    Gahyeon::RealtimeCharacterCoordinator character(0, 10'000);
    Gahyeon::SpeechPlaybackCoordinator playback(character);
    Gahyeon::VoiceInteractionController voice(
        character,
        playback,
        Gahyeon::VoiceActivityConfig{
            .StartThreshold = 0.05,
            .StopThreshold = 0.02,
            .AttackMs = 30,
            .ReleaseMs = 100,
        });

    voice.Observe(0.08, 0);
    const auto started = voice.Observe(0.08, 30);
    Require(started.Event == Gahyeon::VoiceActivityEvent::Started
            && voice.Detector().Active(),
        "test capture must enter active listening before abort");
    const auto aborted = voice.AbortActiveCapture(40);
    Require(aborted.has_value() && aborted->GenerationId == started.GenerationId + 1,
        "capture abort must advance the stale-result generation exactly once");
    Require(!voice.Detector().Active(),
        "capture abort must reset VAD instead of waiting for nonexistent silence frames");
    Require(Value(character.Intents().Resolve(40), Gahyeon::IntentChannel::Phase) == "idle",
        "capture abort must immediately return listening character to idle");
    Require(!character.SpeechStarted(started.GenerationId, 50, "truncated-result"),
        "late result for a truncated capture must be rejected");
    Require(!voice.AbortActiveCapture(60).has_value(),
        "duplicate stop while capture is idle must not advance generation twice");

    voice.Observe(0.08, 100);
    const auto restarted = voice.Observe(0.08, 130);
    Require(restarted.Event == Gahyeon::VoiceActivityEvent::Started,
        "reset VAD must accept a later microphone restart with fresh timestamps");
    voice.Observe(0.0, 140);
    voice.Observe(0.0, 240);
    Require(!voice.Detector().Active(), "completed restarted speech must close normally");
    const auto thinkingGeneration = character.Intents().CurrentGeneration();
    Require(!voice.AbortActiveCapture(250).has_value()
            && character.Intents().CurrentGeneration() == thinkingGeneration,
        "stopping an idle microphone must not cancel already completed cognition");
    Require(Value(character.Intents().Resolve(250), Gahyeon::IntentChannel::Phase)
            == "thinking",
        "idle capture stop must preserve an utterance already awaiting cognition");
}

void ActualPlaybackCancelsTheThinkingWatchdog() {
    Gahyeon::RealtimeCharacterCoordinator character(0, 500);
    const auto generation = character.VoiceStarted(0);
    character.VoiceEnded(generation, 100);
    Require(character.SpeechStarted(generation, 300, "audio"),
        "current playback should start before timeout");

    Require(!character.Advance(1'000).has_value(),
        "active playback must cancel the thinking watchdog");
    Require(Value(character.Intents().Resolve(1'000), Gahyeon::IntentChannel::Phase) == "speaking",
        "watchdog must not interrupt actual audio");
}

void FailedEmptySpeechSequenceReturnsThinkingCharacterToIdle() {
    Gahyeon::RealtimeCharacterCoordinator character(0, 10'000);
    const auto generation = character.VoiceStarted(0);
    character.VoiceEnded(generation, 100);
    Gahyeon::SpeechPlaybackCoordinator playback(character);
    playback.SetGeneration(generation);

    Require(playback.SequenceEnded({generation, 0, "failed"}, 250)
            == Gahyeon::SpeechEnqueueResult::Accepted,
        "failed empty speech sequence should be accepted for the current generation");
    Require(Value(character.Intents().Resolve(250), Gahyeon::IntentChannel::Phase) == "idle",
        "failed TTS sequence must return to idle without waiting for cognition watchdog");
}

void AmbientMotionRemainsFiniteAndAliveForThirtyOfflineMinutes() {
    Gahyeon::AmbientMotionRuntime ambient(0, 42);
    double minimumBreath = 1.0;
    double maximumBreath = 0.0;
    double maximumBlink = 0.0;
    double minimumWeight = 1.0;
    double maximumWeight = -1.0;
    for (Gahyeon::Millis now = 0; now <= 30 * 60 * 1'000; now += 16) {
        const auto sample = ambient.Sample(now);
        Require(std::isfinite(sample.Breath) && sample.Breath >= 0.0 && sample.Breath <= 1.0,
            "breathing must remain normalized and finite offline");
        Require(std::isfinite(sample.Blink) && sample.Blink >= 0.0 && sample.Blink <= 1.0,
            "blink must remain normalized and finite offline");
        Require(std::abs(sample.EyeYaw) <= 1.0 && std::abs(sample.EyePitch) <= 1.0,
            "saccades must remain inside normalized eye limits");
        Require(std::abs(sample.MicroHeadYaw) <= 1.0
                && std::abs(sample.MicroHeadPitch) <= 1.0,
            "micro head motion must remain inside normalized limits");
        Require(std::abs(sample.WeightShift) <= 1.0,
            "weight shift must remain normalized");
        minimumBreath = std::min(minimumBreath, sample.Breath);
        maximumBreath = std::max(maximumBreath, sample.Breath);
        maximumBlink = std::max(maximumBlink, sample.Blink);
        minimumWeight = std::min(minimumWeight, sample.WeightShift);
        maximumWeight = std::max(maximumWeight, sample.WeightShift);
    }
    Require(maximumBreath - minimumBreath > 0.9,
        "offline breathing must continue across its full cycle");
    Require(maximumBlink > 0.9, "offline runtime must keep blinking");
    Require(maximumWeight - minimumWeight > 1.8,
        "offline weight shifting must remain active");
}

void AmbientMotionIsDeterministicAndSafeAcrossFrameDrops() {
    Gahyeon::AmbientMotionRuntime first(0, 1234);
    Gahyeon::AmbientMotionRuntime second(0, 1234);
    const std::vector<Gahyeon::Millis> frames{0, 16, 33, 500, 5'000, 60'000, 86'400'000};
    for (const auto now : frames) {
        const auto left = first.Sample(now);
        const auto right = second.Sample(now);
        Require(left.Breath == right.Breath && left.Blink == right.Blink
                && left.EyeYaw == right.EyeYaw && left.EyePitch == right.EyePitch
                && left.WeightShift == right.WeightShift,
            "same seed and timestamps must reproduce secondary motion exactly");
        Require(std::isfinite(left.EyeYaw) && std::isfinite(left.MicroHeadYaw),
            "large frame gaps must not produce invalid motion");
    }
    const auto before = first.Sample(86'400'000);
    const auto nonMonotonic = first.Sample(1'000);
    Require(before.Breath == nonMonotonic.Breath
            && before.EyeYaw == nonMonotonic.EyeYaw,
        "non-monotonic engine timestamps must not rewind procedural motion");
}

void AttentionMovesEyesBeforeHeadWithinTheReflexBudget() {
    Gahyeon::AttentionRuntime attention;
    Require(attention.SetUserTarget({1.0, 1.0, 0.25}, 1.0, 0),
        "valid character-local user target should be accepted");
    const auto at50 = attention.Sample(50);
    const auto at100 = attention.Sample(100);
    Require(at50.EyeYaw > at50.HeadYaw,
        "eyes should lead the slower head follow motion");
    Require(at100.EyeYaw > 0.7,
        "eye reflex should visibly converge within 100ms");
    Require(at100.TrackingWeight == 1.0,
        "fresh high-confidence target should fully override ambient saccade");
    Require(std::abs(at100.EyeYaw) <= 1.0 && std::abs(at100.HeadYaw) <= 1.0,
        "look-at output must remain normalized");
}

void AttentionFadesBackToAmbientWhenTrackingBecomesStale() {
    Gahyeon::AttentionRuntime attention;
    attention.SetUserTarget({1.0, -0.7, 0.2}, 0.8, 0);
    attention.Sample(300);
    const auto fading = attention.Sample(650);
    const auto stale = attention.Sample(900);
    const auto settled = attention.Sample(2'000);
    Require(fading.TrackingWeight > 0.0 && fading.TrackingWeight < 0.8,
        "stale target should fade instead of snapping away");
    Require(stale.TrackingWeight == 0.0,
        "expired target should release control to ambient saccade");
    Require(std::abs(settled.EyeYaw) < 0.01 && std::abs(settled.HeadYaw) < 0.01,
        "look-at offsets should smoothly settle after tracking loss");
}

void AttentionRejectsInvalidTargetsAndSurvivesLargeFrameGaps() {
    Gahyeon::AttentionRuntime attention;
    Require(!attention.SetUserTarget({0.0, 0.0, 0.0}, 1.0, 0),
        "zero-length target vector must be rejected");
    Require(!attention.SetUserTarget({1.0, 0.0, 0.0}, 1.5, 0),
        "invalid confidence must be rejected");
    Require(attention.SetUserTarget({-1.0, 100.0, 100.0}, 1.0, 0),
        "extreme but finite target should be clamped");
    const auto sample = attention.Sample(86'400'000);
    Require(std::isfinite(sample.EyeYaw) && std::isfinite(sample.HeadPitch),
        "large frame gap must remain finite");
    Require(std::abs(sample.EyeYaw) <= 1.0 && std::abs(sample.HeadPitch) <= 1.0,
        "extreme look-at target must remain normalized");
    Require(!attention.SetUserTarget({1.0, 0.0, 0.0}, 1.0, 10),
        "non-monotonic target timestamps must be ignored");
}

void PcmUtteranceIncludesBoundedPreRollAndEncodesValidWav() {
    Gahyeon::PcmUtteranceBuffer buffer({
        .PreRollMs = 100,
        .MaximumDurationMs = 1'000,
        .MaximumChannels = 2});
    std::vector<float> preRoll(800, 0.25f);
    Require(buffer.Observe(preRoll, 800, 1, 8'000)
            == Gahyeon::PcmBufferResult::Accepted,
        "valid pre-roll PCM should be accepted");
    Require(buffer.VoiceStarted(7), "voice start should claim the generation");
    std::vector<float> speech(1'600, -0.5f);
    Require(buffer.Observe(speech, 1'600, 1, 8'000)
            == Gahyeon::PcmBufferResult::Accepted,
        "active utterance PCM should be accepted");
    const auto encoded = buffer.VoiceEnded(7);
    Require(encoded.has_value() && encoded->GenerationId == 7,
        "matching voice end should produce one generation-bound utterance");
    Require(encoded->DurationMs == 300 && !encoded->Truncated,
        "encoded duration should include exactly 100ms pre-roll and 200ms speech");
    Require(encoded->Wav.size() == 44 + 2'400 * 2,
        "PCM16 WAV should contain a 44-byte header and every sample");
    Require(std::string(encoded->Wav.begin(), encoded->Wav.begin() + 4) == "RIFF"
            && std::string(encoded->Wav.begin() + 8, encoded->Wav.begin() + 12) == "WAVE",
        "encoded audio must expose a canonical RIFF/WAVE header");
}

void PcmUtteranceRejectsStaleEndFormatChangesAndBoundsDuration() {
    Gahyeon::PcmUtteranceBuffer buffer({
        .PreRollMs = 100,
        .MaximumDurationMs = 500,
        .MaximumChannels = 2});
    std::vector<float> preRoll(1'600, 0.1f);
    buffer.Observe(preRoll, 1'600, 1, 8'000);
    Require(buffer.RetainedSampleCount() == 800,
        "idle pre-roll must retain only its configured duration");
    Require(buffer.VoiceStarted(11), "generation 11 should become active");
    Require(!buffer.VoiceEnded(10).has_value() && buffer.IsActive(),
        "stale voice end must not close the current utterance");
    std::vector<float> longSpeech(4'000, 0.2f);
    Require(buffer.Observe(longSpeech, 4'000, 1, 8'000)
            == Gahyeon::PcmBufferResult::DurationLimitReached,
        "utterance must report its hard duration bound");
    const auto bounded = buffer.VoiceEnded(11);
    Require(bounded.has_value() && bounded->Truncated && bounded->DurationMs == 500,
        "bounded utterance must be marked truncated at exactly the configured maximum");

    buffer.Observe(preRoll, 1'600, 1, 8'000);
    Require(buffer.VoiceStarted(12), "next generation should start after completion");
    std::vector<float> stereo(1'600, 0.0f);
    Require(buffer.Observe(stereo, 800, 2, 8'000)
            == Gahyeon::PcmBufferResult::FormatChangedDuringUtterance,
        "device format changes must invalidate rather than corrupt an active WAV");
    Require(!buffer.VoiceEnded(12).has_value(),
        "format-invalid utterance must never be submitted to STT");
}

void VoiceEndToTranscriptLatencyUsesExplicitBatchBudget() {
    Gahyeon::LatencyTrace trace;
    trace.Record(Gahyeon::LatencyMetric::VoiceEndToFinalTranscript, 2'400);
    trace.Record(Gahyeon::LatencyMetric::VoiceEndToFinalTranscript, 3'200);
    const auto summary = trace.Summary(
        Gahyeon::LatencyMetric::VoiceEndToFinalTranscript);
    Require(summary.BudgetMs == 3'000 && summary.TotalCount == 2,
        "batch STT latency must expose its explicit three-second acceptance budget");
    Require(summary.P95Ms == 3'200 && summary.BudgetViolations == 1
            && !summary.PassesP95,
        "batch STT p95 and budget violations must remain observable");
}

} // namespace

int main() {
    StreamingSttClientFramesExactOrderedAudio();
    StreamingSttBackpressureFailsWholeUtteranceAndFallsBackNextOnly();
    StreamingSttResultIngressBackpressureFailsWithoutSilentTranscriptLoss();
    StreamingSttRejectsFormatDriftAndProviderSequenceGap();
    StreamingSttUsesBatchUntilTransportIsReadyAndPreemptsOldGeneration();
    StreamingSttCancelReleasesStreamingAndBatchLifecycles();
    SlowCognitionDoesNotFreezeAmbientBehavior();
    PartialTranscriptRefreshesAttentionOnlyWhileListening();
    BargeInRejectsLateCognition();
    ReflexExpiresBackToAmbientBehavior();
    ConcurrentProducersHandOffWithoutTouchingTheArbiter();
    ReflexPreemptsLowerLayerWhenMailboxIsFull();
    CognitionCompletionDoesNotStartSpeakingBeforeAudio();
    GenerationAdvanceInterruptsOwnedAudioWithoutWaitingForSpeechPayload();
    LatePreparedSpeechIsRejectedByCurrentGeneration();
    UnknownProtocolMessageIsForwardCompatible();
    ReplayedFutureGenerationConvergesIntentAndSpeechAtomically();
    StaleReplayCannotRewindGenerationOrReplacePresentation();
    UnknownFutureEventCannotAdvanceCancellationGeneration();
    ProtocolSpeechBackpressureIsObservable();
    ReplayCursorAdvancesAcrossScopedSequenceGaps();
    ReplayCursorSurvivesDisconnectAndDuplicateReplay();
    WorldSnapshotAppliesAtomicallyAndRejectsRevisionRegression();
    InvalidOrConflictingWorldSnapshotCannotPartiallyMutateState();
    ReconnectHarnessConvergesToSnapshotWithinTwoSeconds();
    ReconnectHarnessExposesDeadlineAndOrderingFailures();
    LipSyncFallsBackToSmoothedAmplitudeWithoutProviderTiming();
    TimedVisemesUseAudioPlaybackPositionAndBlendOverlaps();
    LipSyncRejectsStaleMalformedAndBargeInAudio();
    PlaybackCoordinatorOwnsLipSyncAtTheAudioDeviceBoundary();
    ProtocolRejectsMalformedVisemeTimelineBeforeAudioQueueing();
    TenMinuteVisemeLoopStaysWithinTheEightyMillisecondSyncBudget();
    EmotionBlendsDimensionsAndReleasesAfterHold();
    EmotionRetargetsFromCurrentBlendAndSurvivesFrameGaps();
    ProtocolPreservesEmotionWhileSpeechAndAttentionRunInParallel();
    GestureSelectionIsDataDrivenAndDeterministic();
    GestureInterruptionCooldownAndGenerationAreExplicit();
    EmptyGestureProfileIsAValidNoAssetStartupState();
    ProtocolGestureSemanticSelectsLocalVariantWithoutAssetIds();
    LatencyTraceComputesBoundedAcceptancePercentiles();
    LatencyTraceBoundsPendingSpansAndRejectsClockRewind();
    LocalPresentationCallbacksFeedAcceptanceMetrics();
    WorldActionCommitsOnlyAfterLocalArrivalAndInteraction();
    WorldActionRejectsStaleTargetsAndReportsTimeoutOrBargeIn();
    ProtocolWorldTargetStartsLocalActionWithoutPrecommittingWorldState();
    ProtocolGenerationAdvanceReturnsCancelledWorldActionForDurableEgress();
    ProtocolBackendCancellationStopsRendererOwnedAction();
    WorldActionCompletionOutboxRetriesOfflineUntilBackendAcknowledges();
    WorldActionCompletionOutboxIsBoundedAndRejectsClockRewind();
    WorldActionCommandBridgeRemovesOnlyTerminalAcknowledgements();
    WorldActionCommandBridgeQuarantinesPermanentBackendRejection();
    ClientSaveStateRestoresCursorOutboxAndDeadLettersAcrossProcessRestart();
    ClientSaveStateMigratesV1GenerationToZero();
    ClientSaveStateRejectsFutureSchemaWithoutMutatingRuntime();
    ProtocolIngressMailboxProtectsDurableReplayUnderSaturation();
    GameThreadDispatcherRetriesBackpressureBeforeAdvancingDurableCursor();
    NetworkBridgeRequiresSaveBeforeAckAndKeepsActionUntilTerminalAck();
    NetworkIngressAdapterRequestsReconnectInsteadOfDroppingDurableEvent();
    DurableWorldSnapshotFlowsFromSocketMailboxToGameThreadAtomically();
    DisconnectRestartHarnessReplaysCursorAndDeliversSavedCompletion();
    TenSecondCognitionHarnessKeepsBehaviorAndReflexCadence();
    MockCognitionHarnessExercisesDelayFailureAndReordering();
    MockCognitionHarnessIsBoundedAndMonotonic();
    MalformedDurableEventIsIsolatedAndFollowingEventStillApplies();
    GameThreadDispatcherBoundsPerFrameReplayWork();
    PreparedSpeechSegmentsRemainOrderedAndClearOnBargeIn();
    SpeechSequenceEndTranslatesWithoutStartingPlayback();
    PlaybackStateStartsOnlyFromTheAudioDeviceCallback();
    TemporaryStreamingQueueGapDoesNotEndSpeaking();
    BargeInStopsOwningOldPlaybackImmediately();
    ConsumedAudioCannotBeReenqueuedWithinTheSameGeneration();
    VoiceActivityHysteresisRejectsNoiseAndUsesReleaseHangover();
    LocalVadBargeInPreemptsPlaybackWithoutWaitingForBackend();
    CognitionTimeoutReturnsToAmbientAndInvalidatesLateResults();
    RecognitionFailureImmediatelyReturnsToIdleAndRejectsLateAudio();
    CaptureAbortCancelsOnlyActiveSpeechAndResetsVadForRestart();
    ActualPlaybackCancelsTheThinkingWatchdog();
    FailedEmptySpeechSequenceReturnsThinkingCharacterToIdle();
    AmbientMotionRemainsFiniteAndAliveForThirtyOfflineMinutes();
    AmbientMotionIsDeterministicAndSafeAcrossFrameDrops();
    AttentionMovesEyesBeforeHeadWithinTheReflexBudget();
    AttentionFadesBackToAmbientWhenTrackingBecomesStale();
    AttentionRejectsInvalidTargetsAndSurvivesLargeFrameGaps();
    PcmUtteranceIncludesBoundedPreRollAndEncodesValidWav();
    PcmUtteranceRejectsStaleEndFormatChangesAndBoundsDuration();
    VoiceEndToTranscriptLatencyUsesExplicitBatchBudget();
    std::cout << "Gahyeon RuntimeCore tests passed\n";
    return 0;
}
