#pragma once

#include "Components/ActorComponent.h"
#include "Runtime/GahyeonRuntimeSubsystem.h"
#include "GahyeonRuntimeDebugComponent.generated.h"

class UGahyeonCharacterPresentationComponent;
class UGahyeonTransportSubsystem;
class UGahyeonVoiceInputComponent;
class UGahyeonWorldActionComponent;

/** Low-frequency observable status adapter; it never drives character behavior. */
UCLASS(ClassGroup = (Gahyeon), meta = (BlueprintSpawnableComponent))
class GAHYEONSTAGE_API UGahyeonRuntimeDebugComponent final : public UActorComponent
{
    GENERATED_BODY()

public:
    UGahyeonRuntimeDebugComponent();

    virtual void BeginPlay() override;
    virtual void EndPlay(const EEndPlayReason::Type EndPlayReason) override;
    virtual void TickComponent(
        float DeltaTime,
        ELevelTick TickType,
        FActorComponentTickFunction* ThisTickFunction) override;

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Debug")
    FString BuildStatusText() const;

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Debug")
    bool IsRuntimeStalled() const { return bRuntimeStalled; }

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Debug")
    FGahyeonRuntimeFrameSnapshot GetObservedFrame() const { return ObservedFrame; }

    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Debug")
    void SetDrawOnScreen(bool bEnabled) { bDrawOnScreen = bEnabled; }

private:
    void RefreshPresentation();

    UPROPERTY(Transient)
    TObjectPtr<UGahyeonCharacterPresentationComponent> Presentation;

    UPROPERTY(Transient)
    TObjectPtr<UGahyeonVoiceInputComponent> VoiceInput;

    UPROPERTY(Transient)
    TObjectPtr<UGahyeonTransportSubsystem> Transport;

    UPROPERTY(Transient)
    TObjectPtr<UGahyeonWorldActionComponent> WorldActions;

    UPROPERTY(Transient)
    FGahyeonRuntimeFrameSnapshot ObservedFrame;

    UPROPERTY(EditAnywhere, Category = "Gahyeon|Debug")
    bool bDrawOnScreen = false;

    UPROPERTY(EditAnywhere, Category = "Gahyeon|Debug", meta = (ClampMin = "0.05"))
    double UpdateIntervalSeconds = 0.25;

    UPROPERTY(EditAnywhere, Category = "Gahyeon|Debug", meta = (ClampMin = "0.1"))
    double StallThresholdSeconds = 0.75;

    double NextUpdateAtSeconds = 0.0;
    double LastFrameProgressAtSeconds = 0.0;
    int64 LastPresentationFrame = -1;
    bool bRuntimeStalled = false;
};
