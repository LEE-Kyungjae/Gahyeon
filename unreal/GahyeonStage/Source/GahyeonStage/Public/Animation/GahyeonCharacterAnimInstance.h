#pragma once

#include "Animation/AnimInstance.h"
#include "Presentation/GahyeonCharacterPresentationProfile.h"
#include "Presentation/GahyeonFacialControlRigBridge.h"
#include "Runtime/GahyeonRuntimeSubsystem.h"
#include "GahyeonCharacterAnimInstance.generated.h"

class UGahyeonCharacterPresentationComponent;

/**
 * Thin presentation bridge for MetaHuman/custom Anim Blueprints.
 *
 * It copies immutable semantic/runtime values before graph evaluation. The graph remains
 * responsible for applying them and explicitly confirming a visible conversation pose.
 */
UCLASS(Blueprintable, Transient)
class GAHYEONSTAGE_API UGahyeonCharacterAnimInstance
    : public UAnimInstance,
      public IGahyeonFacialControlRigBridge
{
    GENERATED_BODY()

public:
    static bool ShouldOpenPoseConfirmation(
        const FString& Phase,
        int64 Generation,
        int64 RuntimeEpoch,
        const FString& PreviousPhase,
        int64 PreviousGeneration,
        int64 PreviousRuntimeEpoch);
    static bool IsVisemeConfirmationCandidate(
        bool bLipSyncActive,
        const FString& Semantic,
        double Weight,
        int64 Generation,
        int64 RuntimeEpoch);

    virtual void NativeInitializeAnimation() override;
    virtual void NativeUninitializeAnimation() override;
    virtual void NativeUpdateAnimation(float DeltaSeconds) override;

    virtual bool ApplyFacialControlRigCurves_Implementation(
        const TMap<FName, float>& ActiveCurves,
        const TArray<FName>& CurvesToReset) override;
    virtual TMap<FName, float> GetFacialControlRigCurveWeights_Implementation() const override;

    /** Call from the Anim Blueprint only after Listening/Thinking pose inputs were consumed. */
    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Latency")
    bool ConfirmCurrentConversationPoseApplied();

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Latency")
    bool IsConversationPoseConfirmationPending() const
    {
        return bConversationPoseConfirmationPending;
    }

    /** Call after the MetaHuman Control Rig consumed the current primary viseme. */
    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Latency")
    bool ConfirmCurrentVisemeApplied();

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Latency")
    bool IsVisemeConfirmationPending() const
    {
        return bVisemeConfirmationPending;
    }

    /** Read by the face Anim Blueprint and wired into its MetaHuman Control Rig graph. */
    UFUNCTION(BlueprintPure, Category = "Gahyeon|Presentation")
    TMap<FName, float> GetFacialControlRigCurves() const
    {
        return GahyeonFacialControlRigCurves;
    }

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Presentation")
    int64 GetPendingFacialControlRigToken() const { return PendingFacialControlRigToken; }

    /** Called by the Anim Blueprint after its Control Rig node consumed the pending map. */
    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Presentation")
    bool ConfirmFacialControlRigConsumed(int64 Token);

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Presentation|QA")
    int64 GetConsumedFacialControlRigToken() const { return ConsumedFacialControlRigToken; }

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Presentation|QA")
    FString GetConsumedFacialControlRigDigest() const { return ConsumedFacialControlRigDigest; }

protected:
    UPROPERTY(BlueprintReadOnly, Transient, Category = "Gahyeon|Runtime")
    FGahyeonRuntimeFrameSnapshot GahyeonFrame;

    UPROPERTY(BlueprintReadOnly, Transient, Category = "Gahyeon|Runtime")
    FGahyeonResolvedProceduralPose GahyeonProceduralPose;

    UPROPERTY(BlueprintReadOnly, Transient, Category = "Gahyeon|Latency")
    bool bConversationPoseConfirmationPending = false;

    UPROPERTY(BlueprintReadOnly, Transient, Category = "Gahyeon|Latency")
    bool bVisemeConfirmationPending = false;

    UPROPERTY(BlueprintReadOnly, Transient, Category = "Gahyeon|Presentation")
    TMap<FName, float> GahyeonFacialControlRigCurves;

private:
    void RefreshPresentation();

    UPROPERTY(Transient)
    TObjectPtr<UGahyeonCharacterPresentationComponent> Presentation;

    FString PendingPhase;
    int64 PendingGeneration = 0;
    int64 PendingRuntimeEpoch = 0;
    FString PendingVisemeSemantic;
    int64 PendingVisemeGeneration = 0;
    int64 PendingVisemeRuntimeEpoch = 0;
    int64 PendingFacialControlRigToken = 0;
    int64 ConsumedFacialControlRigToken = 0;
    FString PendingFacialControlRigDigest;
    FString ConsumedFacialControlRigDigest;
    TMap<FName, float> ConsumedFacialControlRigCurves;
};
