#include "Persistence/GahyeonRuntimePersistenceSubsystem.h"

#include "Kismet/GameplayStatics.h"

void UGahyeonRuntimePersistenceSubsystem::Deinitialize()
{
    for (FQueuedSave& Save : SaveQueue)
    {
        if (Save.Completion)
        {
            Save.Completion(false);
        }
    }
    SaveQueue.Reset();
    for (FLoadCompletion& Load : LoadQueue)
    {
        if (Load)
        {
            Load(nullptr, TEXT("persistence subsystem shutting down"));
        }
    }
    LoadQueue.Reset();
    if (InFlightCompletion)
    {
        InFlightCompletion(false);
    }
    InFlightState = nullptr;
    InFlightCompletion = {};
    bSaveInFlight = false;
    bLoadInFlight = false;
    Super::Deinitialize();
}

void UGahyeonRuntimePersistenceSubsystem::LoadAsync(FLoadCompletion Completion)
{
    check(IsInGameThread());
    if (!Completion)
    {
        return;
    }
    LoadQueue.Add(MoveTemp(Completion));
    StartNextSave();
}

void UGahyeonRuntimePersistenceSubsystem::SaveAsync(
    UGahyeonRuntimeSaveGame* State,
    FSaveCompletion Completion)
{
    check(IsInGameThread());
    FString Error;
    if (State == nullptr || !UGahyeonRuntimeSaveGame::Validate(*State, Error))
    {
        if (Completion)
        {
            Completion(false);
        }
        return;
    }
    SaveQueue.Add({TStrongObjectPtr<UGahyeonRuntimeSaveGame>(State), MoveTemp(Completion)});
    StartNextSave();
}

void UGahyeonRuntimePersistenceSubsystem::StartNextSave()
{
    check(IsInGameThread());
    if (bSaveInFlight || bLoadInFlight)
    {
        return;
    }

    if (SaveQueue.IsEmpty())
    {
        if (LoadQueue.IsEmpty())
        {
            return;
        }
        FLoadCompletion Completion = MoveTemp(LoadQueue[0]);
        LoadQueue.RemoveAt(0, 1, EAllowShrinking::No);
        if (!UGameplayStatics::DoesSaveGameExist(SaveSlot, 0))
        {
            Completion(NewObject<UGahyeonRuntimeSaveGame>(this), FString{});
            StartNextSave();
            return;
        }

        bLoadInFlight = true;
        FAsyncLoadGameFromSlotDelegate LoadDelegate;
        LoadDelegate.BindWeakLambda(this,
            [this, Completion = MoveTemp(Completion)](
                const FString& SlotName,
                const int32 UserIndex,
                USaveGame* Loaded) mutable
            {
                (void)SlotName;
                (void)UserIndex;
                bLoadInFlight = false;
                UGahyeonRuntimeSaveGame* State = Cast<UGahyeonRuntimeSaveGame>(Loaded);
                FString Error;
                if (State == nullptr || !UGahyeonRuntimeSaveGame::Validate(*State, Error))
                {
                    Completion(nullptr, Error.IsEmpty() ? TEXT("invalid save object") : Error);
                }
                else
                {
                    Completion(State, FString{});
                }
                StartNextSave();
            });
        UGameplayStatics::AsyncLoadGameFromSlot(SaveSlot, 0, LoadDelegate);
        return;
    }

    FQueuedSave Next = MoveTemp(SaveQueue[0]);
    SaveQueue.RemoveAt(0, 1, EAllowShrinking::No);
    InFlightState = Next.State.Get();
    InFlightCompletion = MoveTemp(Next.Completion);
    bSaveInFlight = true;

    FAsyncSaveGameToSlotDelegate Delegate;
    Delegate.BindWeakLambda(this,
        [this](const FString& SlotName, const int32 UserIndex, const bool bSuccess)
        {
            (void)SlotName;
            (void)UserIndex;
            FSaveCompletion Completion = MoveTemp(InFlightCompletion);
            InFlightState = nullptr;
            bSaveInFlight = false;
            if (Completion)
            {
                Completion(bSuccess);
            }
            StartNextSave();
        });
    UGameplayStatics::AsyncSaveGameToSlot(InFlightState, SaveSlot, 0, Delegate);
}
