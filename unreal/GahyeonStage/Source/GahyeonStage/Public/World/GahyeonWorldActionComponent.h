#pragma once

#include "Components/ActorComponent.h"
#include "GahyeonWorldActionComponent.generated.h"

class AController;
class APawn;
class UAnimMontage;
class UAnimInstance;
class UGahyeonCharacterPresentationComponent;
class UGahyeonInteractionPointComponent;
class FStreamableHandle;

enum class EGahyeonNavigationReadiness : uint8
{
    CustomLocomotion,
    PawnUnavailable,
    ControllerUnavailable,
    NavigationSystemUnavailable,
    NavigationDataUnavailable,
    Ready,
};

DECLARE_DYNAMIC_MULTICAST_DELEGATE_FourParams(
    FGahyeonNavigationRequested,
    const FString&,
    ActionId,
    FVector,
    TargetPosition,
    const FString&,
    Activity,
    const FString&,
    InteractionTarget);

DECLARE_DYNAMIC_MULTICAST_DELEGATE_FourParams(
    FGahyeonInteractionRequested,
    const FString&,
    ActionId,
    const FString&,
    Activity,
    const FString&,
    InteractionTarget,
    const FString&,
    ResultPosture);

DECLARE_DYNAMIC_MULTICAST_DELEGATE_TwoParams(
    FGahyeonWorldActionStopped,
    const FString&,
    ActionId,
    const FString&,
    Reason);

/**
 * Physical execution adapter for semantic World actions.
 *
 * Core owns intent/revision; this component owns local path following and reports
 * only observed arrival or animation completion. It never commits World State.
 */
UCLASS(ClassGroup = (Gahyeon), meta = (BlueprintSpawnableComponent))
class GAHYEONSTAGE_API UGahyeonWorldActionComponent final : public UActorComponent
{
    GENERATED_BODY()

public:
    UGahyeonWorldActionComponent();

    virtual void BeginPlay() override;
    virtual void EndPlay(const EEndPlayReason::Type EndPlayReason) override;
    virtual void TickComponent(
        float DeltaTime,
        ELevelTick TickType,
        FActorComponentTickFunction* ThisTickFunction) override;

    /** Custom navigation implementations call this only after physical arrival. */
    UFUNCTION(BlueprintCallable, Category = "Gahyeon|World")
    bool MarkNavigationArrived();

    /** Complete interaction using the owner's actual Unreal World position. */
    UFUNCTION(BlueprintCallable, Category = "Gahyeon|World")
    bool FinishCurrentAction(const FString& Outcome, const FString& Reason);

    UFUNCTION(BlueprintPure, Category = "Gahyeon|World")
    FString GetCurrentActionId() const { return CurrentActionId; }

    UFUNCTION(BlueprintPure, Category = "Gahyeon|World")
    FRotator GetCurrentFacingRotation() const { return CurrentFacingRotation; }

    UFUNCTION(BlueprintPure, Category = "Gahyeon|World")
    FString GetCurrentResultPosture() const { return CurrentResultPosture; }

    UFUNCTION(BlueprintPure, Category = "Gahyeon|World")
    FString GetNavigationReadinessLabel() const;

    static EGahyeonNavigationReadiness ClassifyNavigationReadiness(
        bool bUsesAutomaticNavigation,
        bool bHasPawn,
        bool bHasController,
        bool bHasNavigationSystem,
        bool bHasNavigationData);

    UPROPERTY(BlueprintAssignable, Category = "Gahyeon|World")
    FGahyeonNavigationRequested OnNavigationRequested;

    UPROPERTY(BlueprintAssignable, Category = "Gahyeon|World")
    FGahyeonInteractionRequested OnInteractionRequested;

    UPROPERTY(BlueprintAssignable, Category = "Gahyeon|World")
    FGahyeonWorldActionStopped OnActionStopped;

private:
    void RefreshPresentation();
    void SynchronizeAction();
    void BeginNavigation();
    void EnterInteraction();
    void StopLocalMotion(const FString& Reason);
    bool ResolveInteractionPoint();
    void BeginInteractionMontage();
    void OnInteractionMontageLoaded(FString ActionId, int64 RuntimeEpoch);
    void OnInteractionMontageEnded(UAnimMontage* Montage, bool bInterrupted);
    void OnInteractionMontageEndedForOwner(
        UAnimMontage* Montage,
        bool bInterrupted,
        FString ActionId,
        int64 RuntimeEpoch);

    UPROPERTY(Transient)
    TObjectPtr<UGahyeonCharacterPresentationComponent> Presentation;

    UPROPERTY(Transient)
    TObjectPtr<APawn> PawnOwner;

    UPROPERTY(Transient)
    TObjectPtr<UGahyeonInteractionPointComponent> ResolvedInteractionPoint;

    UPROPERTY(EditAnywhere, Category = "Gahyeon|World")
    bool bAutoNavigate = true;

    UPROPERTY(EditAnywhere, Category = "Gahyeon|World", meta = (ClampMin = "1.0"))
    float AcceptanceRadiusCm = 20.0f;

    UPROPERTY(EditAnywhere, Category = "Gahyeon|World", meta = (ClampMin = "0.0"))
    float PathFailureGraceSeconds = 0.25f;

    FString CurrentActionId;
    FString PendingCompletionActionId;
    FString CurrentPhase;
    FString CurrentActivity;
    FString CurrentTargetRoom;
    FString CurrentInteractionTarget;
    FString CurrentResultPosture;
    FVector CurrentTarget = FVector::ZeroVector;
    FRotator CurrentFacingRotation = FRotator::ZeroRotator;
    TSoftObjectPtr<UAnimMontage> PendingInteractionMontage;
    TWeakObjectPtr<UAnimInstance> InteractionAnimInstance;
    TSharedPtr<FStreamableHandle> InteractionLoadHandle;
    double InteractionPlayRate = 1.0;
    bool bStoppingInteraction = false;
    double NavigationStartedAtSeconds = 0.0;
    bool bNavigationIssued = false;
    bool bInteractionAnnounced = false;
    int64 CurrentRuntimeEpoch = 0;
};
