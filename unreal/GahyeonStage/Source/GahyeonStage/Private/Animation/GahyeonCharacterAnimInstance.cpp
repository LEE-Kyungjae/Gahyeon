#include "Animation/GahyeonCharacterAnimInstance.h"

#include "GameFramework/Actor.h"
#include "Misc/MD5.h"
#include "Presentation/GahyeonCharacterPresentationComponent.h"

namespace
{
FString FacialCurveDigest(const TMap<FName, float>& Curves)
{
    TArray<FName> Names;
    Curves.GetKeys(Names);
    Names.Sort(FNameLexicalLess());
    FString Canonical;
    for (const FName Name : Names)
    {
        Canonical += FString::Printf(TEXT("%s=%.6f\n"),
            *Name.ToString(), Curves.FindRef(Name));
    }
    return FMD5::HashAnsiString(*Canonical);
}
}

bool UGahyeonCharacterAnimInstance::ShouldOpenPoseConfirmation(
    const FString& Phase,
    int64 Generation,
    int64 RuntimeEpoch,
    const FString& PreviousPhase,
    int64 PreviousGeneration,
    int64 PreviousRuntimeEpoch)
{
    const bool bConfirmable = Phase == TEXT("listening") || Phase == TEXT("thinking");
    return bConfirmable && RuntimeEpoch > 0
        && (Phase != PreviousPhase || Generation != PreviousGeneration
            || RuntimeEpoch != PreviousRuntimeEpoch);
}

bool UGahyeonCharacterAnimInstance::IsVisemeConfirmationCandidate(
    bool bLipSyncActive,
    const FString& Semantic,
    double Weight,
    int64 Generation,
    int64 RuntimeEpoch)
{
    return bLipSyncActive && !Semantic.IsEmpty() && FMath::IsFinite(Weight)
        && Weight > 0.0 && Generation >= 0 && RuntimeEpoch > 0;
}

bool UGahyeonCharacterAnimInstance::ApplyFacialControlRigCurves_Implementation(
    const TMap<FName, float>& ActiveCurves,
    const TArray<FName>& CurvesToReset)
{
    for (const TPair<FName, float>& Item : ActiveCurves)
    {
        if (Item.Key.IsNone() || !FMath::IsFinite(Item.Value)
            || Item.Value < 0.0f || Item.Value > 1.0f)
        {
            return false;
        }
    }
    TSet<FName> ResetNames;
    for (const FName Curve : CurvesToReset)
    {
        if (Curve.IsNone() || ActiveCurves.Contains(Curve)
            || ResetNames.Contains(Curve))
        {
            return false;
        }
        ResetNames.Add(Curve);
    }
    for (const FName Curve : ResetNames)
    {
        GahyeonFacialControlRigCurves.Remove(Curve);
    }
    for (const TPair<FName, float>& Item : ActiveCurves)
    {
        GahyeonFacialControlRigCurves.Add(Item.Key, Item.Value);
    }
    PendingFacialControlRigToken++;
    if (PendingFacialControlRigToken <= 0) PendingFacialControlRigToken = 1;
    PendingFacialControlRigDigest = FacialCurveDigest(GahyeonFacialControlRigCurves);
    return true;
}

TMap<FName, float> UGahyeonCharacterAnimInstance::GetFacialControlRigCurveWeights_Implementation() const
{
    return ConsumedFacialControlRigCurves;
}

bool UGahyeonCharacterAnimInstance::ConfirmFacialControlRigConsumed(int64 Token)
{
    if (Token <= 0 || Token != PendingFacialControlRigToken
        || PendingFacialControlRigDigest.IsEmpty())
    {
        return false;
    }
    ConsumedFacialControlRigToken = Token;
    ConsumedFacialControlRigDigest = PendingFacialControlRigDigest;
    ConsumedFacialControlRigCurves = GahyeonFacialControlRigCurves;
    return true;
}

void UGahyeonCharacterAnimInstance::NativeInitializeAnimation()
{
    Super::NativeInitializeAnimation();
    RefreshPresentation();
}

void UGahyeonCharacterAnimInstance::NativeUninitializeAnimation()
{
    Presentation = nullptr;
    GahyeonFrame = {};
    GahyeonProceduralPose = {};
    GahyeonFacialControlRigCurves.Reset();
    ConsumedFacialControlRigCurves.Reset();
    PendingFacialControlRigToken = 0;
    ConsumedFacialControlRigToken = 0;
    PendingFacialControlRigDigest.Reset();
    ConsumedFacialControlRigDigest.Reset();
    bConversationPoseConfirmationPending = false;
    bVisemeConfirmationPending = false;
    PendingPhase.Reset();
    PendingGeneration = 0;
    PendingRuntimeEpoch = 0;
    PendingVisemeSemantic.Reset();
    PendingVisemeGeneration = 0;
    PendingVisemeRuntimeEpoch = 0;
    Super::NativeUninitializeAnimation();
}

void UGahyeonCharacterAnimInstance::NativeUpdateAnimation(float DeltaSeconds)
{
    Super::NativeUpdateAnimation(DeltaSeconds);
    (void)DeltaSeconds;
    if (Presentation == nullptr) RefreshPresentation();
    if (Presentation == nullptr)
    {
        bConversationPoseConfirmationPending = false;
        bVisemeConfirmationPending = false;
        return;
    }

    const FGahyeonRuntimeFrameSnapshot Next = Presentation->GetFrame();
    GahyeonFrame = Next;
    GahyeonProceduralPose = Presentation->GetProceduralPose();
    const bool bConfirmable = Next.ConversationPhase == TEXT("listening")
        || Next.ConversationPhase == TEXT("thinking");
    const bool bOpenConfirmation = ShouldOpenPoseConfirmation(
        Next.ConversationPhase, Next.CurrentGeneration, Next.RuntimeEpoch,
        PendingPhase, PendingGeneration, PendingRuntimeEpoch);
    if (!bConfirmable)
    {
        bConversationPoseConfirmationPending = false;
        PendingPhase.Reset();
        PendingGeneration = Next.CurrentGeneration;
        PendingRuntimeEpoch = Next.RuntimeEpoch;
    }
    else if (bOpenConfirmation)
    {
        PendingPhase = Next.ConversationPhase;
        PendingGeneration = Next.CurrentGeneration;
        PendingRuntimeEpoch = Next.RuntimeEpoch;
        bConversationPoseConfirmationPending = true;
    }


    if (IsVisemeConfirmationCandidate(
        Next.bLipSyncActive, Next.PrimaryViseme, Next.PrimaryVisemeWeight,
        Next.CurrentGeneration, Next.RuntimeEpoch))
    {
        PendingVisemeSemantic = Next.PrimaryViseme;
        PendingVisemeGeneration = Next.CurrentGeneration;
        PendingVisemeRuntimeEpoch = Next.RuntimeEpoch;
        bVisemeConfirmationPending = true;
    }
    else
    {
        PendingVisemeSemantic.Reset();
        PendingVisemeGeneration = Next.CurrentGeneration;
        PendingVisemeRuntimeEpoch = Next.RuntimeEpoch;
        bVisemeConfirmationPending = false;
    }
}

bool UGahyeonCharacterAnimInstance::ConfirmCurrentConversationPoseApplied()
{
    if (!bConversationPoseConfirmationPending || Presentation == nullptr) return false;
    const bool bConfirmed = Presentation->ConfirmConversationPoseApplied(
        PendingPhase, PendingGeneration, PendingRuntimeEpoch);
    if (bConfirmed) bConversationPoseConfirmationPending = false;
    return bConfirmed;
}

bool UGahyeonCharacterAnimInstance::ConfirmCurrentVisemeApplied()
{
    if (!bVisemeConfirmationPending || Presentation == nullptr) return false;
    const UGahyeonCharacterPresentationProfile* Profile = Presentation->GetProfile();
    const FName Semantic(*PendingVisemeSemantic);
    const bool bRequiresConsumedControlRig = Profile != nullptr
        && Profile->VisemeCurves.ContainsByPredicate(
            [Semantic](const FGahyeonFacialCurveBinding& Binding)
            {
                return Binding.Semantic == Semantic
                    && Binding.Target == EGahyeonFacialTarget::ControlRig;
            });
    if (bRequiresConsumedControlRig
        && ConsumedFacialControlRigToken != PendingFacialControlRigToken)
    {
        return false;
    }
    const bool bConfirmed = Presentation->ConfirmVisemeApplied(
        PendingVisemeSemantic, PendingVisemeGeneration, PendingVisemeRuntimeEpoch);
    if (bConfirmed) bVisemeConfirmationPending = false;
    return bConfirmed;
}

void UGahyeonCharacterAnimInstance::RefreshPresentation()
{
    AActor* Owner = GetOwningActor();
    Presentation = Owner != nullptr
        ? Owner->FindComponentByClass<UGahyeonCharacterPresentationComponent>()
        : nullptr;
}
