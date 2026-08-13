#include "Presentation/GahyeonCharacterPresentationProfile.h"

namespace
{
void AddBoundWeight(
    const TArray<FGahyeonFacialCurveBinding>& Bindings,
    FName Semantic,
    double Weight,
    TMap<FName, float>& Output)
{
    if (Semantic.IsNone() || !FMath::IsFinite(Weight) || Weight <= 0.0) return;
    for (const FGahyeonFacialCurveBinding& Binding : Bindings)
    {
        if (Binding.Semantic != Semantic) continue;
        const float Value = static_cast<float>(FMath::Clamp(Weight * Binding.Scale, 0.0, 1.0));
        Output.FindOrAdd(Binding.CurveName) = FMath::Max(Output.FindRef(Binding.CurveName), Value);
    }
}
}

bool UGahyeonCharacterPresentationProfile::Validate(FString& OutError) const
{
    OutError.Reset();
    const double PositiveValues[] = {
        BreathScale, BlinkScale, EyeYawDegrees, EyePitchDegrees,
        HeadYawDegrees, HeadPitchDegrees, MicroHeadDegrees, WeightShiftScale};
    for (double Value : PositiveValues)
    {
        if (!FMath::IsFinite(Value) || Value < 0.0)
        {
            OutError = TEXT("presentation scales must be finite and non-negative");
            return false;
        }
    }
    auto ValidateCurves = [&OutError](
        const TArray<FGahyeonFacialCurveBinding>& Curves,
        const TCHAR* Group)
    {
        TSet<FString> Pairs;
        for (const FGahyeonFacialCurveBinding& Binding : Curves)
        {
            const FString Pair = Binding.Semantic.ToString() + TEXT("\n")
                + Binding.CurveName.ToString();
            if (Binding.Semantic.IsNone() || Binding.CurveName.IsNone()
                || !FMath::IsFinite(Binding.Scale) || Binding.Scale < 0.0
                || Pairs.Contains(Pair))
            {
                OutError = FString::Printf(
                    TEXT("invalid or duplicate %s semantic/curve binding"), Group);
                return false;
            }
            Pairs.Add(Pair);
        }
        return true;
    };
    if (!ValidateCurves(EmotionCurves, TEXT("emotion"))
        || !ValidateCurves(VisemeCurves, TEXT("viseme")))
    {
        return false;
    }
    TMap<FName, EGahyeonFacialTarget> CurveTargets;
    auto RegisterTarget = [&CurveTargets, &OutError](
        FName Curve, EGahyeonFacialTarget Target)
    {
        if (Curve.IsNone()) return true;
        const EGahyeonFacialTarget* Existing = CurveTargets.Find(Curve);
        if (Existing != nullptr && *Existing != Target)
        {
            OutError = TEXT("one facial curve cannot mix morph and Control Rig targets");
            return false;
        }
        CurveTargets.Add(Curve, Target);
        return true;
    };
    if (!RegisterTarget(LeftBlinkCurve, LeftBlinkTarget)
        || !RegisterTarget(RightBlinkCurve, RightBlinkTarget)
        || !RegisterTarget(JawOpenCurve, JawOpenTarget)) return false;
    for (const FGahyeonFacialCurveBinding& Binding : EmotionCurves)
    {
        if (!RegisterTarget(Binding.CurveName, Binding.Target)) return false;
    }
    for (const FGahyeonFacialCurveBinding& Binding : VisemeCurves)
    {
        if (!RegisterTarget(Binding.CurveName, Binding.Target)) return false;
    }

    TSet<FName> GestureVariants;
    for (const FGahyeonGesturePresentationDefinition& Gesture : Gestures)
    {
        if (Gesture.Semantic.IsNone() || Gesture.VariantId.IsNone()
            || GestureVariants.Contains(Gesture.VariantId)
            || !FMath::IsFinite(Gesture.MinIntensity)
            || !FMath::IsFinite(Gesture.MaxIntensity)
            || Gesture.MinIntensity < 0.0 || Gesture.MaxIntensity > 1.0
            || Gesture.MinIntensity > Gesture.MaxIntensity
            || Gesture.DurationMs <= 0 || Gesture.CooldownMs < 0
            || !FMath::IsFinite(Gesture.SelectionWeight)
            || Gesture.SelectionWeight <= 0.0)
        {
            OutError = TEXT("invalid or duplicate gesture definition");
            return false;
        }
        GestureVariants.Add(Gesture.VariantId);
    }

    TSet<FString> InteractionKeys;
    for (const FGahyeonInteractionPresentationDefinition& Interaction : Interactions)
    {
        const FString Key = Interaction.Activity.ToString() + TEXT("\n")
            + Interaction.InteractionTarget.ToString();
        if (Interaction.Activity.IsNone() || Interaction.ResultPosture.IsNone()
            || Interaction.Montage.IsNull() || !FMath::IsFinite(Interaction.PlayRate)
            || Interaction.PlayRate <= 0.0 || InteractionKeys.Contains(Key))
        {
            OutError = TEXT("invalid or duplicate interaction definition");
            return false;
        }
        InteractionKeys.Add(Key);
    }
    return true;
}

void UGahyeonCharacterPresentationProfile::ResolveFacialCurveWeights(
    const TMap<FName, double>& EmotionDimensions,
    FName PrimaryViseme,
    double PrimaryVisemeWeight,
    FName SecondaryViseme,
    double SecondaryVisemeWeight,
    double JawOpen,
    double Blink,
    TMap<FName, float>& OutWeights) const
{
    OutWeights.Reset();
    for (const TPair<FName, double>& Dimension : EmotionDimensions)
    {
        AddBoundWeight(EmotionCurves, Dimension.Key, Dimension.Value, OutWeights);
    }
    AddBoundWeight(VisemeCurves, PrimaryViseme, PrimaryVisemeWeight, OutWeights);
    AddBoundWeight(VisemeCurves, SecondaryViseme, SecondaryVisemeWeight, OutWeights);
    if (!JawOpenCurve.IsNone())
    {
        OutWeights.FindOrAdd(JawOpenCurve) = FMath::Max(
            OutWeights.FindRef(JawOpenCurve),
            static_cast<float>(FMath::Clamp(JawOpen, 0.0, 1.0)));
    }
    const float BlinkWeight = static_cast<float>(FMath::Clamp(Blink * BlinkScale, 0.0, 1.0));
    if (!LeftBlinkCurve.IsNone()) OutWeights.FindOrAdd(LeftBlinkCurve) = BlinkWeight;
    if (!RightBlinkCurve.IsNone()) OutWeights.FindOrAdd(RightBlinkCurve) = BlinkWeight;
}

bool UGahyeonCharacterPresentationProfile::ResolveFacialSemanticWeights(
    FName Group,
    FName Semantic,
    double Weight,
    TMap<FName, float>& OutWeights) const
{
    OutWeights.Reset();
    if (Semantic.IsNone() || !FMath::IsFinite(Weight) || Weight < 0.0 || Weight > 1.0)
    {
        return false;
    }
    if (Group == TEXT("baseline") && Semantic == TEXT("neutral"))
    {
        return true;
    }
    if (Group == TEXT("direct"))
    {
        FName Curve;
        if (Semantic == TEXT("blink-left")) Curve = LeftBlinkCurve;
        else if (Semantic == TEXT("blink-right")) Curve = RightBlinkCurve;
        else if (Semantic == TEXT("jaw-open")) Curve = JawOpenCurve;
        if (Curve.IsNone()) return false;
        OutWeights.Add(Curve, static_cast<float>(Weight));
        return true;
    }
    if (Group == TEXT("viseme"))
    {
        AddBoundWeight(VisemeCurves, Semantic, Weight, OutWeights);
        return !OutWeights.IsEmpty();
    }
    if (Group == TEXT("emotion"))
    {
        AddBoundWeight(EmotionCurves, Semantic, Weight, OutWeights);
        return !OutWeights.IsEmpty();
    }
    return false;
}

EGahyeonFacialTarget UGahyeonCharacterPresentationProfile::ResolveFacialTarget(
    FName CurveName) const
{
    if (CurveName == LeftBlinkCurve) return LeftBlinkTarget;
    if (CurveName == RightBlinkCurve) return RightBlinkTarget;
    if (CurveName == JawOpenCurve) return JawOpenTarget;
    for (const FGahyeonFacialCurveBinding& Binding : EmotionCurves)
    {
        if (Binding.CurveName == CurveName) return Binding.Target;
    }
    for (const FGahyeonFacialCurveBinding& Binding : VisemeCurves)
    {
        if (Binding.CurveName == CurveName) return Binding.Target;
    }
    return EGahyeonFacialTarget::MorphTarget;
}

FGahyeonResolvedProceduralPose UGahyeonCharacterPresentationProfile::ResolveProceduralPose(
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
    double AttentionTrackingWeight) const
{
    const double Tracking = FMath::Clamp(AttentionTrackingWeight, 0.0, 1.0);
    const double EyeYaw = FMath::Lerp(
        FMath::Clamp(AmbientEyeYaw, -1.0, 1.0),
        FMath::Clamp(AttentionEyeYaw, -1.0, 1.0), Tracking);
    const double EyePitch = FMath::Lerp(
        FMath::Clamp(AmbientEyePitch, -1.0, 1.0),
        FMath::Clamp(AttentionEyePitch, -1.0, 1.0), Tracking);
    FGahyeonResolvedProceduralPose Result;
    Result.Breath = FMath::Clamp(Breath, 0.0, 1.0) * BreathScale;
    Result.Blink = FMath::Clamp(Blink * BlinkScale, 0.0, 1.0);
    Result.EyeYawDegrees = EyeYaw * EyeYawDegrees;
    Result.EyePitchDegrees = EyePitch * EyePitchDegrees;
    Result.HeadYawDegrees = FMath::Clamp(
        AttentionHeadYaw * HeadYawDegrees + MicroHeadYaw * MicroHeadDegrees,
        -HeadYawDegrees, HeadYawDegrees);
    Result.HeadPitchDegrees = FMath::Clamp(
        AttentionHeadPitch * HeadPitchDegrees + MicroHeadPitch * MicroHeadDegrees,
        -HeadPitchDegrees, HeadPitchDegrees);
    Result.TrackingWeight = Tracking;
    Result.WeightShift = FMath::Clamp(WeightShift, -1.0, 1.0) * WeightShiftScale;
    return Result;
}

const FGahyeonInteractionPresentationDefinition*
UGahyeonCharacterPresentationProfile::FindInteraction(
    FName Activity,
    FName InteractionTarget) const
{
    const FGahyeonInteractionPresentationDefinition* Fallback = nullptr;
    for (const FGahyeonInteractionPresentationDefinition& Definition : Interactions)
    {
        if (Definition.Activity != Activity) continue;
        if (Definition.InteractionTarget == InteractionTarget) return &Definition;
        if (Definition.InteractionTarget.IsNone()) Fallback = &Definition;
    }
    return Fallback;
}

const FGahyeonGesturePresentationDefinition*
UGahyeonCharacterPresentationProfile::FindGesture(
    FName Semantic,
    FName VariantId,
    FName Posture,
    double Intensity) const
{
    if (Semantic.IsNone() || VariantId.IsNone() || !FMath::IsFinite(Intensity)) return nullptr;
    for (const FGahyeonGesturePresentationDefinition& Definition : Gestures)
    {
        if (Definition.Semantic == Semantic
            && Definition.VariantId == VariantId
            && (Definition.RequiredPosture.IsNone() || Definition.RequiredPosture == Posture)
            && Intensity >= Definition.MinIntensity
            && Intensity <= Definition.MaxIntensity)
        {
            return &Definition;
        }
    }
    return nullptr;
}
