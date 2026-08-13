#pragma once

#include "CoreMinimal.h"
#include "UObject/StrongObjectPtr.h"
#include "Subsystems/GameInstanceSubsystem.h"
#include "Persistence/GahyeonRuntimeSaveGame.h"
#include "GahyeonRuntimePersistenceSubsystem.generated.h"

/** Serializes async saves so each egress confirmation maps to a durable write. */
UCLASS()
class GAHYEONSTAGE_API UGahyeonRuntimePersistenceSubsystem final
    : public UGameInstanceSubsystem
{
    GENERATED_BODY()

public:
    using FSaveCompletion = TFunction<void(bool)>;
    using FLoadCompletion = TFunction<void(UGahyeonRuntimeSaveGame*, const FString&)>;

    virtual void Deinitialize() override;

    void LoadAsync(FLoadCompletion Completion);
    void SaveAsync(UGahyeonRuntimeSaveGame* State, FSaveCompletion Completion);

private:
    struct FQueuedSave
    {
        TStrongObjectPtr<UGahyeonRuntimeSaveGame> State;
        FSaveCompletion Completion;
    };

    void StartNextSave();

    static constexpr TCHAR SaveSlot[] = TEXT("GahyeonRuntimeV1");
    TArray<FQueuedSave> SaveQueue;
    TArray<FLoadCompletion> LoadQueue;
    UPROPERTY(Transient)
    TObjectPtr<UGahyeonRuntimeSaveGame> InFlightState;
    FSaveCompletion InFlightCompletion;
    bool bSaveInFlight = false;
    bool bLoadInFlight = false;
};
