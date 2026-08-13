#if WITH_DEV_AUTOMATION_TESTS

#include "Gahyeon/MockCognitionRuntime.h"
#include "Gahyeon/RealtimeCharacterCoordinator.h"
#include "Misc/AutomationTest.h"

IMPLEMENT_SIMPLE_AUTOMATION_TEST(
    FGahyeonMockCognitionRuntimeTest,
    "Gahyeon.Runtime.MockCognitionDelayFailureAndReordering",
    EAutomationTestFlags::EditorContext | EAutomationTestFlags::EngineFilter)

bool FGahyeonMockCognitionRuntimeTest::RunTest(const FString& Parameters)
{
    (void)Parameters;
    Gahyeon::MockCognitionRuntime Harness(4);
    Gahyeon::RealtimeCharacterCoordinator Character(0, 10'000);

    const Gahyeon::Generation OldGeneration = Character.VoiceStarted(0);
    Character.VoiceEnded(OldGeneration, 100);
    TestTrue(TEXT("ten-second completion scheduled"),
        Harness.Schedule(OldGeneration, "slow-old", 100, 10'000)
            == Gahyeon::MockCognitionScheduleResult::Accepted);

    const Gahyeon::Generation CurrentGeneration = Character.VoiceStarted(200);
    Character.VoiceEnded(CurrentGeneration, 300);
    TestTrue(TEXT("half-second failure scheduled"),
        Harness.Schedule(
            CurrentGeneration,
            "fast-failure",
            300,
            500,
            Gahyeon::MockCognitionOutcome::Failed)
            == Gahyeon::MockCognitionScheduleResult::Accepted);

    TestTrue(TEXT("no early completion"), Harness.TakeDue(799).empty());
    const std::vector<Gahyeon::MockCognitionCompletion> First = Harness.TakeDue(800);
    TestTrue(TEXT("later short request finishes first"),
        First.size() == 1
            && First.front().RequestId == "fast-failure"
            && First.front().Outcome == Gahyeon::MockCognitionOutcome::Failed);

    const std::vector<Gahyeon::MockCognitionCompletion> Late = Harness.TakeDue(10'100);
    TestTrue(TEXT("old delayed completion remains observable"),
        Late.size() == 1 && Late.front().RequestId == "slow-old");
    TestFalse(TEXT("old completion cannot start current speaking"),
        Character.SpeechStarted(
            Late.front().GenerationId,
            10'100,
            Late.front().RequestId));
    TestTrue(TEXT("current generation remains authoritative"),
        Character.Intents().CurrentGeneration() == CurrentGeneration);
    return true;
}

#endif
