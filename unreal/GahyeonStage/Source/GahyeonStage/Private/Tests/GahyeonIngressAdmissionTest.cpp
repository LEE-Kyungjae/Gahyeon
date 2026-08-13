#if WITH_DEV_AUTOMATION_TESTS

#include "Misc/AutomationTest.h"
#include "Runtime/GahyeonRuntimeSubsystem.h"

IMPLEMENT_SIMPLE_AUTOMATION_TEST(
    FGahyeonIngressAdmissionTest,
    "Gahyeon.Runtime.IngressReservesCapacityForSpeechAndControl",
    EAutomationTestFlags::EditorContext | EAutomationTestFlags::EngineFilter)

bool FGahyeonIngressAdmissionTest::RunTest(const FString& Parameters)
{
    (void)Parameters;
    UGahyeonRuntimeSubsystem* Runtime = NewObject<UGahyeonRuntimeSubsystem>();
    TestNotNull(TEXT("runtime"), Runtime);
    if (Runtime == nullptr) return false;

    FGahyeonProtocolEnvelope LatestState;
    LatestState.Type = TEXT("attention.target");
    LatestState.Delivery = TEXT("ephemeral");
    bool bLatestStateAccepted = true;
    for (int32 Index = 0; Index < 768; ++Index)
    {
        bLatestStateAccepted &= Runtime->EnqueueInbound(LatestState);
    }
    TestTrue(TEXT("latest-state fits within its budget"), bLatestStateAccepted);
    TestTrue(TEXT("replaceable overflow is dropped without forcing reconnect"),
        Runtime->EnqueueInbound(LatestState));

    FGahyeonProtocolEnvelope Speech;
    Speech.Type = TEXT("speech.prepared");
    Speech.Delivery = TEXT("ephemeral");
    bool bCriticalAccepted = true;
    for (int32 Index = 0; Index < 256; ++Index)
    {
        bCriticalAccepted &= Runtime->EnqueueInbound(Speech);
    }
    TestTrue(TEXT("reserved speech/control capacity remains available"), bCriticalAccepted);
    TestFalse(TEXT("critical overflow forces persisted replay instead of silent loss"),
        Runtime->EnqueueInbound(Speech));
    return true;
}

#endif
