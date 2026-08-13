#pragma once

#include "Components/SceneComponent.h"
#include "GahyeonInteractionPointComponent.generated.h"

/**
 * Data-driven local anchor for a semantic World object interaction.
 * Place this component at the exact feet/root alignment transform.
 */
UCLASS(ClassGroup = (Gahyeon), meta = (BlueprintSpawnableComponent))
class GAHYEONSTAGE_API UGahyeonInteractionPointComponent final : public USceneComponent
{
    GENERATED_BODY()

public:
    virtual void BeginPlay() override;
    virtual void EndPlay(const EEndPlayReason::Type EndPlayReason) override;

    UFUNCTION(BlueprintPure, Category = "Gahyeon|World")
    FName GetSemanticId() const { return SemanticId; }

    UFUNCTION(BlueprintPure, Category = "Gahyeon|World")
    FName GetRoomId() const { return RoomId; }

    UFUNCTION(BlueprintPure, Category = "Gahyeon|World")
    bool SupportsActivity(FName Activity) const;

    UFUNCTION(BlueprintPure, Category = "Gahyeon|World")
    bool IsRegistered() const { return bRegistered; }

    /** Constructor-time configuration for source-authored prototype World actors. */
    void Configure(
        FName InSemanticId,
        FName InRoomId,
        TArray<FName> InSupportedActivities);

private:
    /** Stable Core-facing ID, for example desk, reading-chair or bed. */
    UPROPERTY(EditAnywhere, Category = "Gahyeon|World")
    FName SemanticId;

    /** Stable Core-facing room ID containing this point. */
    UPROPERTY(EditAnywhere, Category = "Gahyeon|World")
    FName RoomId;

    /** Empty is invalid: every point explicitly declares allowed activities. */
    UPROPERTY(EditAnywhere, Category = "Gahyeon|World")
    TArray<FName> SupportedActivities;

    bool bRegistered = false;
};
