#pragma once

#include "Components/ActorComponent.h"
#include "Presentation/GahyeonCharacterPresentationProfile.h"
#include "Runtime/GahyeonRuntimeSubsystem.h"
#include "GahyeonCharacterPresentationComponent.generated.h"

class UGahyeonCharacterPresentationProfile;
class USkeletalMeshComponent;
class UAnimInstance;
class UAnimMontage;
class FStreamableHandle;

DECLARE_DYNAMIC_MULTICAST_DELEGATE_TwoParams(
    FGahyeonConversationPhaseChanged,
    const FString&,
    PreviousPhase,
    const FString&,
    CurrentPhase);

DECLARE_DYNAMIC_MULTICAST_DELEGATE_FourParams(
    FGahyeonDominantEmotionChanged,
    const FString&,
    PreviousEmotion,
    double,
    PreviousIntensity,
    const FString&,
    CurrentEmotion,
    double,
    CurrentIntensity);

DECLARE_DYNAMIC_MULTICAST_DELEGATE_FourParams(
    FGahyeonGestureChanged,
    const FString&,
    PreviousSemantic,
    const FString&,
    PreviousVariantId,
    const FString&,
    CurrentSemantic,
    const FString&,
    CurrentVariantId);

DECLARE_DYNAMIC_MULTICAST_DELEGATE_TwoParams(
    FGahyeonWorldActionChanged,
    const FString&,
    PreviousActionId,
    const FString&,
    CurrentActionId);

DECLARE_DYNAMIC_MULTICAST_DELEGATE_TwoParams(
    FGahyeonPostureChanged,
    const FString&,
    PreviousPosture,
    const FString&,
    CurrentPosture);

/**
 * Semantic presentation port for a visible character.
 *
 * Anim Blueprint polls the immutable frame values; change delegates are for
 * state transitions only. This component never chooses animation asset IDs.
 */
UCLASS(ClassGroup = (Gahyeon), meta = (BlueprintSpawnableComponent))
class GAHYEONSTAGE_API UGahyeonCharacterPresentationComponent final
    : public UActorComponent
{
    GENERATED_BODY()

public:
    UGahyeonCharacterPresentationComponent();

    static bool IsPoseConfirmationCurrent(
        const FString& RequestedPhase,
        int64 RequestedGeneration,
        int64 RequestedRuntimeEpoch,
        const FString& CurrentPhase,
        int64 CurrentGeneration,
        int64 CurrentRuntimeEpoch);

    virtual void BeginPlay() override;
    virtual void EndPlay(const EEndPlayReason::Type EndPlayReason) override;
    virtual void TickComponent(
        float DeltaTime,
        ELevelTick TickType,
        FActorComponentTickFunction* ThisTickFunction) override;

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Presentation")
    FGahyeonRuntimeFrameSnapshot GetFrame() const { return Frame; }

    /** Character-local, profile-scaled pose inputs for Anim Blueprint / Control Rig. */
    UFUNCTION(BlueprintPure, Category = "Gahyeon|Presentation")
    FGahyeonResolvedProceduralPose GetProceduralPose() const { return ProceduralPose; }

    /** Immediate ownership token; unlike Frame this does not wait for Presentation tick. */
    int64 GetRuntimeEpoch() const
    {
        return Runtime != nullptr ? static_cast<int64>(Runtime->GetRuntimeEpoch()) : 0;
    }

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Presentation")
    UGahyeonCharacterPresentationProfile* GetProfile() const { return Profile; }

    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Presentation")
    bool ApplyProfile();

    /** Anim Blueprint/Control Rig calls this only after the requested pose is visible. */
    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Latency")
    bool ConfirmConversationPoseApplied(
        const FString& Phase,
        int64 Generation,
        int64 PresentationRuntimeEpoch);

    /** Control Rig/Anim BP acknowledgement for profiles that do not use direct morph targets. */
    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Latency")
    bool ConfirmVisemeApplied(
        const FString& Semantic,
        int64 Generation,
        int64 PresentationRuntimeEpoch);

    /** Camera/tracker target in World space; transformed locally before Reflex input. */
    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Attention")
    bool UpdateUserWorldTarget(FVector WorldTarget, double Confidence);

    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Attention")
    void ClearUserTarget();

    /** Reports NavMesh/path-following arrival; interaction animation may then begin. */
    UFUNCTION(BlueprintCallable, Category = "Gahyeon|World")
    bool NotifyWorldNavigationArrived();

    /** Reports completed/failed/cancelled after the physical animation actually finishes. */
    UFUNCTION(BlueprintCallable, Category = "Gahyeon|World")
    bool NotifyWorldActionFinished(
        const FString& Outcome,
        const FString& Reason,
        FVector FinalPosition);

    UPROPERTY(BlueprintAssignable, Category = "Gahyeon|Presentation")
    FGahyeonConversationPhaseChanged OnConversationPhaseChanged;

    UPROPERTY(BlueprintAssignable, Category = "Gahyeon|Presentation")
    FGahyeonDominantEmotionChanged OnDominantEmotionChanged;

    UPROPERTY(BlueprintAssignable, Category = "Gahyeon|Presentation")
    FGahyeonGestureChanged OnGestureChanged;

    UPROPERTY(BlueprintAssignable, Category = "Gahyeon|World")
    FGahyeonWorldActionChanged OnWorldActionChanged;

    UPROPERTY(BlueprintAssignable, Category = "Gahyeon|Presentation")
    FGahyeonPostureChanged OnPostureChanged;

private:
    void RefreshRuntime();
    void PreloadProfileAssets();
    bool ApplyWorldAttentionTarget(FVector WorldTarget, double Confidence);
    void UpdateLocalCameraAttention();
    void ApplyFacialCurves();
    USkeletalMeshComponent* ResolveFaceMesh();
    USkeletalMeshComponent* ResolveGestureMesh();
    void SynchronizeGesture();
    void StopGestureMontage(float BlendOutSeconds = 0.12f);
    void OnGestureMontageLoaded(uint64 RequestGeneration, int64 RuntimeEpoch);

    UPROPERTY(Transient)
    TObjectPtr<UGahyeonRuntimeSubsystem> Runtime;

    UPROPERTY(Transient)
    FGahyeonRuntimeFrameSnapshot Frame;

    UPROPERTY(Transient)
    FGahyeonResolvedProceduralPose ProceduralPose;

    UPROPERTY(EditAnywhere, Category = "Gahyeon|Presentation")
    TObjectPtr<UGahyeonCharacterPresentationProfile> Profile;

    /** Optional dedicated MetaHuman face mesh; falls back to the owner's first skeletal mesh. */
    UPROPERTY(EditAnywhere, Category = "Gahyeon|Presentation")
    TObjectPtr<USkeletalMeshComponent> FaceMesh;

    /** Optional body mesh used for upper-body gesture montages. */
    UPROPERTY(EditAnywhere, Category = "Gahyeon|Presentation")
    TObjectPtr<USkeletalMeshComponent> GestureMesh;

    /** Applies profile-bound emotion, viseme and blink values directly as morph targets. */
    UPROPERTY(EditAnywhere, Category = "Gahyeon|Presentation")
    bool bApplyFacialMorphTargets = true;

    /** Reflex fallback only; a recent camera/tracker target always takes priority. */
    UPROPERTY(EditAnywhere, Category = "Gahyeon|Attention")
    bool bTrackLocalPlayerCamera = true;

    UPROPERTY(EditAnywhere, Category = "Gahyeon|Attention", meta = (ClampMin = "0.01"))
    double LocalCameraUpdateIntervalSeconds = 0.05;

    UPROPERTY(EditAnywhere, Category = "Gahyeon|Attention", meta = (ClampMin = "0.0", ClampMax = "1.0"))
    double LocalCameraConfidence = 1.0;

    UPROPERTY(EditAnywhere, Category = "Gahyeon|Attention", meta = (ClampMin = "0.0"))
    double ExternalTrackerPrioritySeconds = 0.75;

    /** Approximate eye/head origin in owner-local centimeters; configure per avatar Blueprint. */
    UPROPERTY(EditAnywhere, Category = "Gahyeon|Attention")
    FVector AttentionOriginLocal = FVector(0.0, 0.0, 75.0);

    TSharedPtr<FStreamableHandle> ProfilePreloadHandle;
    TSharedPtr<FStreamableHandle> GestureLoadHandle;
    TSoftObjectPtr<UAnimMontage> PendingGestureMontage;
    TWeakObjectPtr<UAnimInstance> GestureAnimInstance;

    bool bProfileApplied = false;
    bool bProfileLoadAttempted = false;
    bool bHasFrame = false;
    TSet<FName> AppliedFacialCurves;
    TSet<FName> AppliedControlRigCurves;
    FString AppliedGestureVariantId;
    uint64 GestureRequestGeneration = 0;
    double NextLocalCameraUpdateSeconds = 0.0;
    double LastExternalTrackerUpdateSeconds = -1.0e9;
};
