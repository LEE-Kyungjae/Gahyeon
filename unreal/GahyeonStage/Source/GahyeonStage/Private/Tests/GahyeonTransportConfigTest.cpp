#if WITH_DEV_AUTOMATION_TESTS

#include "Misc/AutomationTest.h"
#include "Network/GahyeonTransportSubsystem.h"

IMPLEMENT_SIMPLE_AUTOMATION_TEST(
    FGahyeonTransportConfigTest,
    "Gahyeon.Network.DerivesSameOriginHttpAudioEndpoint",
    EAutomationTestFlags::EditorContext | EAutomationTestFlags::EngineFilter)

IMPLEMENT_SIMPLE_AUTOMATION_TEST(
    FGahyeonTransportCallbackEpochTest,
    "Gahyeon.Network.RejectsCallbacksFromOldConnectionOrRuntimeEpoch",
    EAutomationTestFlags::EditorContext | EAutomationTestFlags::EngineFilter)

IMPLEMENT_SIMPLE_AUTOMATION_TEST(
    FGahyeonTransportHeartbeatContractTest,
    "Gahyeon.Network.ValidatesServerHeartbeatContract",
    EAutomationTestFlags::EditorContext | EAutomationTestFlags::EngineFilter)

IMPLEMENT_SIMPLE_AUTOMATION_TEST(
    FGahyeonTransportHeartbeatPongTest,
    "Gahyeon.Network.AcceptsOnlyCorrelatedHeartbeatPong",
    EAutomationTestFlags::EditorContext | EAutomationTestFlags::EngineFilter)

bool FGahyeonTransportConfigTest::RunTest(const FString& Parameters)
{
    (void)Parameters;
    UGahyeonTransportSubsystem* Secure = NewObject<UGahyeonTransportSubsystem>();
    Secure->Configure(
        TEXT("wss://gahyeon.example:8443/api/gahyeon/unreal/v1"),
        TEXT("session"), TEXT("world"), TEXT("install"), TEXT("user"), TEXT("token"));
    TestEqual(TEXT("wss maps to same-origin https"), Secure->GetHttpOrigin(),
        FString(TEXT("https://gahyeon.example:8443")));
    TestEqual(TEXT("audio request reuses transport credential"), Secure->GetBearerToken(),
        FString(TEXT("token")));

    UGahyeonTransportSubsystem* Local = NewObject<UGahyeonTransportSubsystem>();
    Local->Configure(
        TEXT("ws://127.0.0.1:8080/socket"),
        TEXT("session"), TEXT("world"), TEXT("install"), TEXT("user"), TEXT(""));
    TestEqual(TEXT("ws maps to same-origin http"), Local->GetHttpOrigin(),
        FString(TEXT("http://127.0.0.1:8080")));

    UGahyeonTransportSubsystem* Invalid = NewObject<UGahyeonTransportSubsystem>();
    Invalid->Configure(
        TEXT("file:///tmp/audio"),
        TEXT("session"), TEXT("world"), TEXT("install"), TEXT("user"), TEXT(""));
    TestTrue(TEXT("non-http transport cannot become an audio origin"),
        Invalid->GetHttpOrigin().IsEmpty());
    return true;
}

bool FGahyeonTransportCallbackEpochTest::RunTest(const FString& Parameters)
{
    (void)Parameters;
    TestTrue(TEXT("exact socket/runtime owner is current"),
        UGahyeonTransportSubsystem::ClassifyCallback(4, 9, 4, 9)
            == EGahyeonTransportCallbackDisposition::Current);
    TestTrue(TEXT("old socket is a harmless no-op"),
        UGahyeonTransportSubsystem::ClassifyCallback(3, 9, 4, 9)
            == EGahyeonTransportCallbackDisposition::OldConnection);
    TestTrue(TEXT("pre-restore callback requires current socket reconnect"),
        UGahyeonTransportSubsystem::ClassifyCallback(4, 8, 4, 9)
            == EGahyeonTransportCallbackDisposition::ReplacedRuntime);
    TestTrue(TEXT("old socket stays old even when runtime epoch also differs"),
        UGahyeonTransportSubsystem::ClassifyCallback(3, 8, 4, 9)
            == EGahyeonTransportCallbackDisposition::OldConnection);
    return true;
}

bool FGahyeonTransportHeartbeatContractTest::RunTest(const FString& Parameters)
{
    (void)Parameters;
    TestEqual(TEXT("minimum heartbeat is accepted"),
        UGahyeonTransportSubsystem::NormalizeHeartbeatIntervalMillis(1'000.0), 1'000);
    TestEqual(TEXT("welcome heartbeat is accepted"),
        UGahyeonTransportSubsystem::NormalizeHeartbeatIntervalMillis(10'000.0), 10'000);
    TestEqual(TEXT("maximum heartbeat is accepted"),
        UGahyeonTransportSubsystem::NormalizeHeartbeatIntervalMillis(60'000.0), 60'000);
    TestEqual(TEXT("too-fast heartbeat is rejected"),
        UGahyeonTransportSubsystem::NormalizeHeartbeatIntervalMillis(999.0), INDEX_NONE);
    TestEqual(TEXT("fractional heartbeat is rejected"),
        UGahyeonTransportSubsystem::NormalizeHeartbeatIntervalMillis(10'000.5), INDEX_NONE);
    TestEqual(TEXT("too-slow heartbeat is rejected"),
        UGahyeonTransportSubsystem::NormalizeHeartbeatIntervalMillis(60'001.0), INDEX_NONE);
    return true;
}

bool FGahyeonTransportHeartbeatPongTest::RunTest(const FString& Parameters)
{
    (void)Parameters;
    TestTrue(TEXT("matching pong completes pending heartbeat"),
        UGahyeonTransportSubsystem::IsExpectedHeartbeatPong(
            TEXT("heartbeat:expected"), TEXT("heartbeat:expected")));
    TestFalse(TEXT("stale pong cannot complete a newer heartbeat"),
        UGahyeonTransportSubsystem::IsExpectedHeartbeatPong(
            TEXT("heartbeat:new"), TEXT("heartbeat:old")));
    TestFalse(TEXT("unsolicited pong is ignored"),
        UGahyeonTransportSubsystem::IsExpectedHeartbeatPong(
            FString{}, TEXT("heartbeat:old")));
    return true;
}

#endif
