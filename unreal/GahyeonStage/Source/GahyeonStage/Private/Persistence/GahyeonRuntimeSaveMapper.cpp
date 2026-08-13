#include "Persistence/GahyeonRuntimeSaveMapper.h"

namespace
{
std::string ToUtf8(const FString& Value)
{
    return std::string(TCHAR_TO_UTF8(*Value));
}

FString FromUtf8(const std::string& Value)
{
    return FString(UTF8_TO_TCHAR(Value.c_str()));
}

Gahyeon::WorldActionCompletion ToRuntimeCompletion(
    const FGahyeonSavedActionCompletion& Source)
{
    return {
        .ActionId = ToUtf8(Source.ActionId),
        .ExpectedRevision = Source.ExpectedRevision,
        .Outcome = ToUtf8(Source.Outcome),
        .Reason = ToUtf8(Source.Reason),
        .FinalPosition = {
            .X = Source.FinalPosition.X,
            .Y = Source.FinalPosition.Y,
            .Z = Source.FinalPosition.Z}};
}

FGahyeonSavedActionCompletion ToSavedCompletion(
    const Gahyeon::WorldActionCompletion& Source,
    int32 Attempts,
    int64 RetryAfterMs)
{
    FGahyeonSavedActionCompletion Result;
    Result.ActionId = FromUtf8(Source.ActionId);
    Result.ExpectedRevision = Source.ExpectedRevision;
    Result.Outcome = FromUtf8(Source.Outcome);
    Result.Reason = FromUtf8(Source.Reason);
    Result.FinalPosition = FVector(
        Source.FinalPosition.X,
        Source.FinalPosition.Y,
        Source.FinalPosition.Z);
    Result.Attempts = Attempts;
    Result.RetryAfterMs = RetryAfterMs;
    return Result;
}
}

bool FGahyeonRuntimeSaveMapper::ToRuntime(
    const UGahyeonRuntimeSaveGame& Source,
    Gahyeon::ClientRuntimeSaveState& OutState,
    FString& OutError)
{
    if (!UGahyeonRuntimeSaveGame::Validate(Source, OutError))
    {
        return false;
    }

    OutState = {};
    OutState.SchemaVersion = Source.SchemaVersion;
    OutState.DurableSequence = Source.DurableSequence;
    OutState.InteractionGeneration = Source.SchemaVersion >= 2
        ? Source.InteractionGeneration
        : 0;
    OutState.WorldActions.Pending.reserve(Source.PendingActions.Num());
    for (const FGahyeonSavedActionCompletion& Pending : Source.PendingActions)
    {
        OutState.WorldActions.Pending.push_back({
            .Completion = ToRuntimeCompletion(Pending),
            .Attempts = Pending.Attempts,
            .RetryAfterMs = Pending.RetryAfterMs});
    }
    OutState.WorldActions.Rejections.reserve(Source.Rejections.Num());
    for (const FGahyeonSavedActionRejection& Rejection : Source.Rejections)
    {
        OutState.WorldActions.Rejections.push_back({
            .Completion = ToRuntimeCompletion(Rejection.Completion),
            .BackendResult = ToUtf8(Rejection.BackendResult)});
    }
    return true;
}

void FGahyeonRuntimeSaveMapper::ToSaveGame(
    const Gahyeon::ClientRuntimeSaveState& Source,
    UGahyeonRuntimeSaveGame& OutState)
{
    OutState.SchemaVersion = Source.SchemaVersion;
    OutState.DurableSequence = Source.DurableSequence;
    OutState.InteractionGeneration = Source.InteractionGeneration;
    OutState.PendingActions.Reset(static_cast<int32>(Source.WorldActions.Pending.size()));
    for (const Gahyeon::WorldActionOutboxSnapshotEntry& Pending : Source.WorldActions.Pending)
    {
        OutState.PendingActions.Add(ToSavedCompletion(
            Pending.Completion, Pending.Attempts, Pending.RetryAfterMs));
    }
    OutState.Rejections.Reset(static_cast<int32>(Source.WorldActions.Rejections.size()));
    for (const Gahyeon::RejectedWorldActionCompletion& Rejection : Source.WorldActions.Rejections)
    {
        FGahyeonSavedActionRejection Saved;
        Saved.Completion = ToSavedCompletion(Rejection.Completion, 0, 0);
        Saved.BackendResult = FromUtf8(Rejection.BackendResult);
        OutState.Rejections.Add(MoveTemp(Saved));
    }
}
