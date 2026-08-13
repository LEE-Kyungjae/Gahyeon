#include "Persistence/GahyeonRuntimeSaveGame.h"

namespace
{
constexpr int64 MaximumSafeJsonInteger = 9007199254740991LL;

bool ValidateCompletion(const FGahyeonSavedActionCompletion& Completion, FString& Error)
{
    if (Completion.ActionId.IsEmpty())
    {
        Error = TEXT("actionId is required");
        return false;
    }
    if (Completion.ExpectedRevision < 0
        || Completion.ExpectedRevision > MaximumSafeJsonInteger)
    {
        Error = TEXT("expectedRevision is outside the JSON-safe integer range");
        return false;
    }
    if (Completion.Outcome != TEXT("completed")
        && Completion.Outcome != TEXT("failed")
        && Completion.Outcome != TEXT("cancelled"))
    {
        Error = TEXT("invalid action outcome");
        return false;
    }
    if (Completion.FinalPosition.ContainsNaN()
        || Completion.Attempts < 0
        || Completion.RetryAfterMs < 0)
    {
        Error = TEXT("invalid action retry or position data");
        return false;
    }
    return true;
}
}

bool UGahyeonRuntimeSaveGame::Validate(
    const UGahyeonRuntimeSaveGame& State,
    FString& OutError)
{
    OutError.Reset();
    if (State.SchemaVersion < 1 || State.SchemaVersion > CurrentSchemaVersion)
    {
        OutError = TEXT("unsupported save schema");
        return false;
    }
    if (State.DurableSequence < 0 || State.DurableSequence > MaximumSafeJsonInteger)
    {
        OutError = TEXT("durable sequence is outside the JSON-safe integer range");
        return false;
    }
    if (State.SchemaVersion >= 2
        && (State.InteractionGeneration < 0
            || State.InteractionGeneration > MaximumSafeJsonInteger))
    {
        OutError = TEXT("interaction generation is outside the JSON-safe integer range");
        return false;
    }
    if (State.PendingActions.Num() > MaximumPendingActions
        || State.Rejections.Num() > MaximumRejections)
    {
        OutError = TEXT("save state exceeds bounded capacity");
        return false;
    }

    TSet<FString> ActionIds;
    for (const FGahyeonSavedActionCompletion& Completion : State.PendingActions)
    {
        if (!ValidateCompletion(Completion, OutError) || ActionIds.Contains(Completion.ActionId))
        {
            if (OutError.IsEmpty())
            {
                OutError = TEXT("duplicate pending actionId");
            }
            return false;
        }
        ActionIds.Add(Completion.ActionId);
    }
    for (const FGahyeonSavedActionRejection& Rejection : State.Rejections)
    {
        if (!ValidateCompletion(Rejection.Completion, OutError)
            || Rejection.BackendResult.IsEmpty())
        {
            if (OutError.IsEmpty())
            {
                OutError = TEXT("rejection result is required");
            }
            return false;
        }
    }
    return true;
}
