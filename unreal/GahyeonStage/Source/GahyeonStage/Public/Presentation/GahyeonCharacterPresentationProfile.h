#pragma once

#include "Engine/DataAsset.h"
#include "GahyeonCharacterPresentationProfile.generated.h"

class UAnimMontage;

UENUM(BlueprintType)
enum class EGahyeonFacialTarget : uint8
{
    MorphTarget,
    ControlRig
};

USTRUCT(BlueprintType)
struct GAHYEONSTAGE_API FGahyeonResolvedProceduralPose
{
    GENERATED_BODY()

    UPROPERTY(BlueprintReadOnly, Category = "Motion")
    double Breath = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Motion")
    double Blink = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Attention")
    double EyeYawDegrees = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Attention")
    double EyePitchDegrees = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Attention")
    double HeadYawDegrees = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Attention")
    double HeadPitchDegrees = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Attention")
    double TrackingWeight = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Motion")
    double WeightShift = 0.0;
};

USTRUCT(BlueprintType)
struct GAHYEONSTAGE_API FGahyeonFacialCurveBinding
{
    GENERATED_BODY()

    /** Emotion or viseme semantic owned by Core/protocol. */
    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Face")
    FName Semantic;

    /** Character-local curve/morph target name, e.g. a MetaHuman control curve. */
    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Face")
    FName CurveName;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Face")
    EGahyeonFacialTarget Target = EGahyeonFacialTarget::MorphTarget;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Face")
    double Scale = 1.0;
};

USTRUCT(BlueprintType)
struct GAHYEONSTAGE_API FGahyeonGesturePresentationDefinition
{
    GENERATED_BODY()

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Gesture")
    FName Semantic;

    /** Local stable key selected by RuntimeCore; never sent by the Backend. */
    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Gesture")
    FName VariantId;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Gesture")
    FName RequiredPosture;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Gesture", meta = (ClampMin = "0", ClampMax = "1"))
    double MinIntensity = 0.0;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Gesture", meta = (ClampMin = "0", ClampMax = "1"))
    double MaxIntensity = 1.0;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Gesture", meta = (ClampMin = "1"))
    int64 DurationMs = 1000;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Gesture", meta = (ClampMin = "0"))
    int64 CooldownMs = 0;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Gesture")
    bool bInterruptible = true;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Gesture", meta = (ClampMin = "0.001"))
    double SelectionWeight = 1.0;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Gesture")
    TSoftObjectPtr<UAnimMontage> Montage;
};

USTRUCT(BlueprintType)
struct GAHYEONSTAGE_API FGahyeonInteractionPresentationDefinition
{
    GENERATED_BODY()

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Interaction")
    FName Activity;

    /** Optional exact semantic point ID. None is an activity-wide fallback. */
    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Interaction")
    FName InteractionTarget;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Interaction")
    FName ResultPosture;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Interaction", meta = (ClampMin = "0.01"))
    double PlayRate = 1.0;

    /** Must be preloaded asynchronously; interaction execution never uses LoadSynchronous. */
    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Interaction")
    TSoftObjectPtr<UAnimMontage> Montage;
};

/** Character-specific mapping/scales; Core remains independent of MetaHuman or animation assets. */
UCLASS(BlueprintType)
class GAHYEONSTAGE_API UGahyeonCharacterPresentationProfile final : public UPrimaryDataAsset
{
    GENERATED_BODY()

public:
    bool Validate(FString& OutError) const;
    /** Resolve semantic face state into character-local morph curve weights. */
    void ResolveFacialCurveWeights(
        const TMap<FName, double>& EmotionDimensions,
        FName PrimaryViseme,
        double PrimaryVisemeWeight,
        FName SecondaryViseme,
        double SecondaryVisemeWeight,
        double JawOpen,
        double Blink,
        TMap<FName, float>& OutWeights) const;
    /** Resolve one explicit QA/runtime semantic, including asymmetric blinks. */
    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Presentation|QA")
    bool ResolveFacialSemanticWeights(
        FName Group,
        FName Semantic,
        double Weight,
        UPARAM(ref) TMap<FName, float>& OutWeights) const;
    EGahyeonFacialTarget ResolveFacialTarget(FName CurveName) const;
    FGahyeonResolvedProceduralPose ResolveProceduralPose(
        double Breath,
        double Blink,
        double AmbientEyeYaw,
        double AmbientEyePitch,
        double MicroHeadYaw,
        double MicroHeadPitch,
        double WeightShift,
        double AttentionEyeYaw,
        double AttentionEyePitch,
        double AttentionHeadYaw,
        double AttentionHeadPitch,
        double AttentionTrackingWeight) const;
    const FGahyeonInteractionPresentationDefinition* FindInteraction(
        FName Activity,
        FName InteractionTarget) const;
    const FGahyeonGesturePresentationDefinition* FindGesture(
        FName Semantic,
        FName VariantId,
        FName Posture,
        double Intensity) const;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Motion")
    double BreathScale = 1.0;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Motion")
    double BlinkScale = 1.0;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Attention")
    double EyeYawDegrees = 35.0;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Attention")
    double EyePitchDegrees = 20.0;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Attention")
    double HeadYawDegrees = 55.0;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Attention")
    double HeadPitchDegrees = 30.0;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Motion")
    double MicroHeadDegrees = 2.0;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Motion")
    double WeightShiftScale = 1.0;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Face")
    FName LeftBlinkCurve;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Face")
    EGahyeonFacialTarget LeftBlinkTarget = EGahyeonFacialTarget::MorphTarget;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Face")
    FName RightBlinkCurve;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Face")
    EGahyeonFacialTarget RightBlinkTarget = EGahyeonFacialTarget::MorphTarget;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Face")
    FName JawOpenCurve;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Face")
    EGahyeonFacialTarget JawOpenTarget = EGahyeonFacialTarget::MorphTarget;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Face")
    TArray<FGahyeonFacialCurveBinding> EmotionCurves;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Face")
    TArray<FGahyeonFacialCurveBinding> VisemeCurves;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Gesture")
    TArray<FGahyeonGesturePresentationDefinition> Gestures;

    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category = "Interaction")
    TArray<FGahyeonInteractionPresentationDefinition> Interactions;
};
