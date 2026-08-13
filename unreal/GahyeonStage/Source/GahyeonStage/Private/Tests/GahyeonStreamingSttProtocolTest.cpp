#if WITH_DEV_AUTOMATION_TESTS

#include "Misc/AutomationTest.h"
#include "Dom/JsonObject.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"
#include "Voice/GahyeonStreamingSttWebSocketClient.h"

IMPLEMENT_SIMPLE_AUTOMATION_TEST(
    FGahyeonStreamingSttProtocolTest,
    "Gahyeon.StreamingSTT.ControlWireFormat",
    EAutomationTestFlags::EditorContext | EAutomationTestFlags::EngineFilter)

bool FGahyeonStreamingSttProtocolTest::RunTest(const FString& Parameters)
{
    TestTrue(
        TEXT("current socket callback is accepted"),
        FGahyeonStreamingSttWebSocketClient::IsCurrentCallback(7, 7));
    TestFalse(
        TEXT("previous socket callback is rejected after reconnect"),
        FGahyeonStreamingSttWebSocketClient::IsCurrentCallback(6, 7));

    (void)Parameters;
    Gahyeon::StreamingSttCommand Start;
    Start.Type = Gahyeon::StreamingSttCommandType::Start;
    Start.Generation = 17;
    Start.StreamId = "stream-17";
    Start.ObservedAtMs = 1234;
    Start.Format = {48'000, 2, 480};
    const FString Json = FGahyeonStreamingSttWebSocketClient::SerializeControlJson(
        TEXT("session-1"), Start);
    TSharedPtr<FJsonObject> Root;
    const TSharedRef<TJsonReader<>> StartReader = TJsonReaderFactory<>::Create(Json);
    TestTrue(TEXT("start JSON parses"), FJsonSerializer::Deserialize(
        StartReader, Root));
    TestTrue(TEXT("start root exists"), Root.IsValid());
    if (!Root.IsValid()) return false;
    TestEqual(TEXT("schema"), Root->GetIntegerField(TEXT("schemaVersion")), 1);
    TestEqual(TEXT("type"), Root->GetStringField(TEXT("type")),
        FString(TEXT("stt.stream.start")));
    TestEqual(TEXT("session"), Root->GetStringField(TEXT("sessionId")),
        FString(TEXT("session-1")));
    TestEqual(TEXT("generation"), Root->GetIntegerField(TEXT("generation")), 17);
    const TSharedPtr<FJsonObject>* Format = nullptr;
    TestTrue(TEXT("format object"), Root->TryGetObjectField(TEXT("format"), Format));
    if (Format == nullptr || !Format->IsValid()) return false;
    TestEqual(TEXT("encoding"), (*Format)->GetStringField(TEXT("encoding")),
        FString(TEXT("float32le")));
    TestEqual(TEXT("sample rate"), (*Format)->GetIntegerField(TEXT("sampleRate")), 48'000);
    TestEqual(TEXT("channels"), (*Format)->GetIntegerField(TEXT("channels")), 2);

    Gahyeon::StreamingSttCommand End;
    End.Type = Gahyeon::StreamingSttCommandType::End;
    End.Generation = 17;
    End.StreamId = "stream-17";
    End.ObservedAtMs = 2000;
    End.Sequence = 42;
    const FString EndJson = FGahyeonStreamingSttWebSocketClient::SerializeControlJson(
        TEXT("session-1"), End);
    Root.Reset();
    const TSharedRef<TJsonReader<>> EndReader = TJsonReaderFactory<>::Create(EndJson);
    TestTrue(TEXT("end JSON parses"), FJsonSerializer::Deserialize(
        EndReader, Root));
    TestEqual(TEXT("end type"), Root->GetStringField(TEXT("type")),
        FString(TEXT("stt.stream.end")));
    TestEqual(TEXT("last sequence"),
        Root->GetIntegerField(TEXT("lastAudioSequence")), 42);

    Gahyeon::StreamingSttCommand Cancel;
    Cancel.Type = Gahyeon::StreamingSttCommandType::Cancel;
    Cancel.Generation = 17;
    Cancel.StreamId = "stream-17";
    Cancel.CancelReason = Gahyeon::StreamingSttCancelReason::CaptureError;
    const FString CancelJson = FGahyeonStreamingSttWebSocketClient::SerializeControlJson(
        TEXT("session-1"), Cancel);
    Root.Reset();
    const TSharedRef<TJsonReader<>> CancelReader = TJsonReaderFactory<>::Create(CancelJson);
    TestTrue(TEXT("cancel JSON parses"), FJsonSerializer::Deserialize(
        CancelReader, Root));
    TestEqual(TEXT("cancel type"), Root->GetStringField(TEXT("type")),
        FString(TEXT("stt.stream.cancel")));
    TestEqual(TEXT("cancel reason"), Root->GetStringField(TEXT("reason")),
        FString(TEXT("capture_error")));
    return true;
}

#endif
