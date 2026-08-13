#if WITH_DEV_AUTOMATION_TESTS

#include "Misc/AutomationTest.h"
#include "Protocol/GahyeonProtocolPayloadDecoder.h"

IMPLEMENT_SIMPLE_AUTOMATION_TEST(
    FGahyeonDurablePayloadDecoderTest,
    "Gahyeon.Protocol.DecodesDurablePayloads",
    EAutomationTestFlags::EditorContext | EAutomationTestFlags::EngineFilter)

bool FGahyeonDurablePayloadDecoderTest::RunTest(const FString& Parameters)
{
    (void)Parameters;
    struct FCase
    {
        const TCHAR* Type;
        const TCHAR* Delivery;
        const TCHAR* Payload;
    };
    const FCase Cases[] = {
        {TEXT("world.snapshot"), TEXT("durable"), TEXT(R"({
            "worldId":"gahyeon-home","revision":18,"currentRoom":"workspace",
            "position":{"x":7,"y":0,"z":-2},"activity":"work",
            "activityStartedAt":"2026-08-11T03:00:08Z","outfit":"casual",
            "worldTime":"2026-08-11T03:00:08Z",
            "emotion":{"name":"focused","intensity":0.65},
            "interactionTarget":"desk","updatedAt":"2026-08-11T03:00:08Z",
            "capturedAt":"2026-08-11T03:00:08Z"})")},
        {TEXT("character.state.target"), TEXT("durable"), TEXT(R"({
            "generation":13,"state":"thinking","priority":60,
            "expiresAfterMs":1500})")},
        {TEXT("emotion.target"), TEXT("durable"),
            TEXT(R"({"revision":7,"dimensions":{"curiosity":0.7},"blendSeconds":0.25})")},
        {TEXT("world.transition.target"), TEXT("durable"), TEXT(R"({
            "actionId":"action-desk-18","worldId":"gahyeon-home",
            "expectedRevision":17,"room":"workspace","position":{"x":7,"y":0,"z":-2},
            "activity":"work","interactionTarget":"desk","timeoutMs":60000})")},
        {TEXT("character.action.result"), TEXT("durable"), TEXT(R"({
            "actionId":"action-desk-18","expectedRevision":17,"outcome":"completed",
            "result":"committed","finalPosition":{"x":7,"y":0,"z":-2},
            "reason":"core_headless_execution"})")},
        {TEXT("generation.advanced"), TEXT("ephemeral"),
            TEXT(R"({"generation":13,"reason":"cognition_timeout"})")},
        {TEXT("speech.prepared"), TEXT("ephemeral"), TEXT(R"({
            "generation":13,"utteranceId":"audio-13-0","utteranceIndex":0,
            "segmentIndex":0,"segmentCount":1,"finalSegment":true,
            "audio":{"url":"/api/speech/audio-13-0","mimeType":"audio/wav"},
            "visemes":[{"semantic":"aa","atMs":0,"durationMs":90,"weight":1.0}]})")},
        {TEXT("speech.sequence.ended"), TEXT("ephemeral"),
            TEXT(R"({"generation":13,"utteranceCount":1,"outcome":"completed"})")},
        {TEXT("gesture.intent"), TEXT("ephemeral"), TEXT(R"({
            "generation":13,"semantic":"explain_small","intensity":0.65,
            "priority":40,"expiresAfterMs":1500,"handPreference":"either",
            "durationHintMs":800})")},
        {TEXT("attention.target"), TEXT("ephemeral"), TEXT(R"({
            "kind":"user","targetId":"local-user","priority":100,
            "expiresAfterMs":500,"headWeight":0.65,"eyeWeight":1.0})")},
        {TEXT("cognition.request.started"), TEXT("durable"), TEXT(R"({
            "generation":13,"source":"desktop","modality":"voice",
            "actorId":"local-user"})")},
        {TEXT("cognition.response.completed"), TEXT("durable"), TEXT(R"({
            "generation":13,"runId":"run-13","content":"응답",
            "tools":[],"durationMillis":640})")},
        {TEXT("cognition.request.cancelled"), TEXT("durable"), TEXT(R"({
            "generation":12,"errorType":"CancellationException",
            "message":"superseded"})")},
        {TEXT("cognition.response.failed"), TEXT("durable"), TEXT(R"({
            "generation":14,"errorType":"ProviderException",
            "message":"unavailable"})")}
    };

    for (const FCase& TestCase : Cases)
    {
        FGahyeonProtocolEnvelope Envelope;
        Envelope.Type = TestCase.Type;
        Envelope.Delivery = TestCase.Delivery;
        Envelope.Sequence = 1;
        Envelope.PayloadJson = TestCase.Payload;
        Gahyeon::ProtocolMessage Message;
        FString Error;
        TestTrue(FString::Printf(TEXT("%s decodes"), TestCase.Type),
            FGahyeonProtocolPayloadDecoder::Decode(Envelope, Message, Error)
                == EGahyeonPayloadDecodeStatus::Decoded);
        if (Envelope.Type == TEXT("speech.prepared"))
        {
            TestEqual(TEXT("audio URL survives decoding"),
                FString(UTF8_TO_TCHAR(Message.AudioUrl.c_str())),
                FString(TEXT("/api/speech/audio-13-0")));
            TestEqual(TEXT("viseme timeline survives decoding"),
                static_cast<int32>(Message.Visemes.size()), 1);
        }
        if (Envelope.Type == TEXT("character.state.target"))
        {
            TestEqual(TEXT("state generation survives decoding"),
                static_cast<int64>(Message.GenerationId.value_or(0)), 13LL);
            TestEqual(TEXT("state semantic survives decoding"),
                FString(UTF8_TO_TCHAR(Message.Semantic.c_str())),
                FString(TEXT("thinking")));
        }
    }

    FGahyeonProtocolEnvelope Invalid;
    Invalid.Type = TEXT("emotion.target");
    Invalid.Delivery = TEXT("durable");
    Invalid.Sequence = 2;
    Invalid.PayloadJson =
        TEXT(R"({"dimensions":{"curiosity":0.7},"blendSeconds":0.25,"unknown":true})");
    Gahyeon::ProtocolMessage Message;
    FString Error;
    TestTrue(TEXT("unknown payload fields fail closed"),
        FGahyeonProtocolPayloadDecoder::Decode(Invalid, Message, Error)
            == EGahyeonPayloadDecodeStatus::Invalid);

    FGahyeonProtocolEnvelope InvalidState;
    InvalidState.Type = TEXT("character.state.target");
    InvalidState.Delivery = TEXT("durable");
    InvalidState.Sequence = 3;
    InvalidState.PayloadJson =
        TEXT(R"({"generation":13,"state":"waiting_for_llm"})");
    TestTrue(TEXT("unknown character phase fails closed"),
        FGahyeonProtocolPayloadDecoder::Decode(InvalidState, Message, Error)
            == EGahyeonPayloadDecodeStatus::Invalid);
    return true;
}

#endif
