#pragma once

#include "CoreMinimal.h"
#include "GameFramework/SaveGame.h"
#include "GahyeonRuntimeSaveGame.generated.h"

USTRUCT(BlueprintType)
struct GAHYEONSTAGE_API FGahyeonSavedActionCompletion
{
    GENERATED_BODY()

    UPROPERTY(SaveGame, BlueprintReadOnly, Category = "Gahyeon|Persistence")
    FString ActionId;

    UPROPERTY(SaveGame, BlueprintReadOnly, Category = "Gahyeon|Persistence")
    int64 ExpectedRevision = 0;

    UPROPERTY(SaveGame, BlueprintReadOnly, Category = "Gahyeon|Persistence")
    FString Outcome;

    UPROPERTY(SaveGame, BlueprintReadOnly, Category = "Gahyeon|Persistence")
    FString Reason;

    UPROPERTY(SaveGame, BlueprintReadOnly, Category = "Gahyeon|Persistence")
    FVector FinalPosition = FVector::ZeroVector;

    UPROPERTY(SaveGame, BlueprintReadOnly, Category = "Gahyeon|Persistence")
    int32 Attempts = 0;

    UPROPERTY(SaveGame, BlueprintReadOnly, Category = "Gahyeon|Persistence")
    int64 RetryAfterMs = 0;
};

USTRUCT(BlueprintType)
struct GAHYEONSTAGE_API FGahyeonSavedActionRejection
{
    GENERATED_BODY()

    UPROPERTY(SaveGame, BlueprintReadOnly, Category = "Gahyeon|Persistence")
    FGahyeonSavedActionCompletion Completion;

    UPROPERTY(SaveGame, BlueprintReadOnly, Category = "Gahyeon|Persistence")
    FString BackendResult;
};

/** Versioned disk boundary matching RuntimeCore ClientRuntimeSaveState v2. */
UCLASS()
class GAHYEONSTAGE_API UGahyeonRuntimeSaveGame final : public USaveGame
{
    GENERATED_BODY()

public:
    static constexpr int32 CurrentSchemaVersion = 2;
    static constexpr int32 MaximumPendingActions = 64;
    static constexpr int32 MaximumRejections = 64;

    UPROPERTY(SaveGame, BlueprintReadOnly, Category = "Gahyeon|Persistence")
    int32 SchemaVersion = CurrentSchemaVersion;

    UPROPERTY(SaveGame, BlueprintReadOnly, Category = "Gahyeon|Persistence")
    int64 DurableSequence = 0;

    /** Monotonic stale-result watermark; v1 saves migrate this field to zero. */
    UPROPERTY(SaveGame, BlueprintReadOnly, Category = "Gahyeon|Persistence")
    int64 InteractionGeneration = 0;

    UPROPERTY(SaveGame, BlueprintReadOnly, Category = "Gahyeon|Persistence")
    TArray<FGahyeonSavedActionCompletion> PendingActions;

    UPROPERTY(SaveGame, BlueprintReadOnly, Category = "Gahyeon|Persistence")
    TArray<FGahyeonSavedActionRejection> Rejections;

    static bool Validate(const UGahyeonRuntimeSaveGame& State, FString& OutError);
};
