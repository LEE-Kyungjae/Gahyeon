#if WITH_DEV_AUTOMATION_TESTS

#include "Misc/AutomationTest.h"
#include "Protocol/GahyeonProtocolParser.h"

IMPLEMENT_SIMPLE_AUTOMATION_TEST(
    FGahyeonProtocolParserValidEnvelopeTest,
    "Gahyeon.Protocol.ValidEnvelope",
    EAutomationTestFlags::EditorContext | EAutomationTestFlags::EngineFilter)

bool FGahyeonProtocolParserValidEnvelopeTest::RunTest(const FString& Parameters)
{
    (void)Parameters;
    const FString Json = TEXT(R"({
        "protocol":"gahyeon.unreal.v1",
        "schemaVersion":1,
        "messageId":"event-1",
        "type":"world.snapshot",
        "sentAt":"2026-08-11T03:00:00Z",
        "sessionId":"desktop-local-user",
        "correlationId":"snapshot:gahyeon-home",
        "delivery":"durable",
        "sequence":17,
        "payload":{"worldId":"gahyeon-home"}
    })");

    FGahyeonProtocolEnvelope Envelope;
    FString Error;
    TestTrue(TEXT("valid envelope parses"),
        FGahyeonProtocolParser::ParseInbound(Json, Envelope, Error));
    TestEqual(TEXT("type is preserved"), Envelope.Type, FString(TEXT("world.snapshot")));
    TestEqual(TEXT("sequence is preserved"), Envelope.Sequence, int64{17});
    TestTrue(TEXT("payload is preserved"), Envelope.PayloadJson.Contains(TEXT("worldId")));
    return true;
}

IMPLEMENT_SIMPLE_AUTOMATION_TEST(
    FGahyeonProtocolParserRejectsInvalidEnvelopeTest,
    "Gahyeon.Protocol.RejectsInvalidEnvelope",
    EAutomationTestFlags::EditorContext | EAutomationTestFlags::EngineFilter)

bool FGahyeonProtocolParserRejectsInvalidEnvelopeTest::RunTest(const FString& Parameters)
{
    (void)Parameters;
    const FString Json = TEXT(R"({
        "protocol":"gahyeon.unreal.v1",
        "schemaVersion":1,
        "messageId":"event-1",
        "type":"emotion.target",
        "sentAt":"2026-08-11T03:00:00Z",
        "correlationId":"turn-1",
        "delivery":"ephemeral",
        "sequence":4,
        "payload":{},
        "unexpected":true
    })");

    FGahyeonProtocolEnvelope Envelope;
    FString Error;
    TestFalse(TEXT("invalid envelope is rejected"),
        FGahyeonProtocolParser::ParseInbound(Json, Envelope, Error));
    TestTrue(TEXT("error identifies unknown field"), Error.Contains(TEXT("unknown")));
    return true;
}

IMPLEMENT_SIMPLE_AUTOMATION_TEST(
    FGahyeonProtocolParserRecoversAfterMalformedJsonTest,
    "Gahyeon.Protocol.RecoversAfterMalformedJson",
    EAutomationTestFlags::EditorContext | EAutomationTestFlags::EngineFilter)

bool FGahyeonProtocolParserRecoversAfterMalformedJsonTest::RunTest(
    const FString& Parameters)
{
    (void)Parameters;
    FGahyeonProtocolEnvelope Envelope;
    FString Error;
    TestFalse(TEXT("truncated JSON is isolated"),
        FGahyeonProtocolParser::ParseInbound(
            TEXT(R"({"protocol":"gahyeon.unreal.v1","payload":)"),
            Envelope,
            Error));
    TestEqual(TEXT("failed parse clears output envelope"), Envelope.Type, FString{});

    const FString Following = TEXT(R"({
        "protocol":"gahyeon.unreal.v1",
        "schemaVersion":1,
        "messageId":"event-after-malformed",
        "type":"attention.target",
        "sentAt":"2026-08-12T00:00:00Z",
        "sessionId":"desktop-local-user",
        "correlationId":"attention:after-malformed",
        "delivery":"ephemeral",
        "payload":{"confidence":0.8}
    })");
    TestTrue(TEXT("following valid event still parses"),
        FGahyeonProtocolParser::ParseInbound(Following, Envelope, Error));
    TestEqual(TEXT("following event is preserved"), Envelope.Type,
        FString(TEXT("attention.target")));
    return true;
}

#endif
