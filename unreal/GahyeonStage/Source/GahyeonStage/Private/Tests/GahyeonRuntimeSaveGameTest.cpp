#if WITH_DEV_AUTOMATION_TESTS

#include "Misc/AutomationTest.h"
#include "Persistence/GahyeonRuntimeSaveGame.h"
#include "Persistence/GahyeonRuntimeSaveMapper.h"

IMPLEMENT_SIMPLE_AUTOMATION_TEST(
    FGahyeonRuntimeSaveGameValidationTest,
    "Gahyeon.Persistence.ValidatesRuntimeState",
    EAutomationTestFlags::EditorContext | EAutomationTestFlags::EngineFilter)

bool FGahyeonRuntimeSaveGameValidationTest::RunTest(const FString& Parameters)
{
    (void)Parameters;
    UGahyeonRuntimeSaveGame* State = NewObject<UGahyeonRuntimeSaveGame>();
    State->DurableSequence = 17;
    State->InteractionGeneration = 9;
    FGahyeonSavedActionCompletion Completion;
    Completion.ActionId = TEXT("action-desk-18");
    Completion.ExpectedRevision = 17;
    Completion.Outcome = TEXT("completed");
    Completion.FinalPosition = FVector(7.0, 0.0, -2.0);
    State->PendingActions.Add(Completion);

    FString Error;
    TestTrue(TEXT("valid state is accepted"),
        UGahyeonRuntimeSaveGame::Validate(*State, Error));

    Gahyeon::ClientRuntimeSaveState RuntimeState;
    TestTrue(TEXT("state maps into RuntimeCore"),
        FGahyeonRuntimeSaveMapper::ToRuntime(*State, RuntimeState, Error));
    UGahyeonRuntimeSaveGame* RoundTrip = NewObject<UGahyeonRuntimeSaveGame>();
    FGahyeonRuntimeSaveMapper::ToSaveGame(RuntimeState, *RoundTrip);
    TestEqual(TEXT("cursor survives round trip"), RoundTrip->DurableSequence, int64{17});
    TestEqual(TEXT("generation survives round trip"),
        RoundTrip->InteractionGeneration, int64{9});
    TestEqual(TEXT("action survives round trip"), RoundTrip->PendingActions.Num(), 1);
    TestEqual(TEXT("action id survives round trip"),
        RoundTrip->PendingActions[0].ActionId, FString(TEXT("action-desk-18")));

    State->PendingActions.Add(Completion);
    TestFalse(TEXT("duplicate action is rejected"),
        UGahyeonRuntimeSaveGame::Validate(*State, Error));
    TestTrue(TEXT("duplicate reason is reported"), Error.Contains(TEXT("duplicate")));

    UGahyeonRuntimeSaveGame* Legacy = NewObject<UGahyeonRuntimeSaveGame>();
    Legacy->SchemaVersion = 1;
    Legacy->DurableSequence = 4;
    Legacy->InteractionGeneration = -1;
    TestTrue(TEXT("v1 save ignores the field that did not exist"),
        UGahyeonRuntimeSaveGame::Validate(*Legacy, Error));
    TestTrue(TEXT("v1 maps into RuntimeCore"),
        FGahyeonRuntimeSaveMapper::ToRuntime(*Legacy, RuntimeState, Error));
    TestEqual(TEXT("v1 generation migrates to zero"),
        RuntimeState.InteractionGeneration, Gahyeon::Generation{0});
    return true;
}

#endif
