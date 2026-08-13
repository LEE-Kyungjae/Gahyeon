#include "Presentation/GahyeonCharacterPresentationComponent.h"

#include "Engine/GameInstance.h"
#include "Engine/AssetManager.h"
#include "Engine/StreamableManager.h"
#include "Engine/World.h"
#include "GameFramework/Actor.h"
#include "GameFramework/PlayerController.h"
#include "Components/SkeletalMeshComponent.h"
#include "Animation/AnimInstance.h"
#include "Animation/AnimMontage.h"
#include "GameFramework/Character.h"
#include "Presentation/GahyeonCharacterPresentationProfile.h"
#include "Presentation/GahyeonFacialControlRigBridge.h"

UGahyeonCharacterPresentationComponent::UGahyeonCharacterPresentationComponent()
{
    PrimaryComponentTick.bCanEverTick = true;
    PrimaryComponentTick.bStartWithTickEnabled = true;
    PrimaryComponentTick.TickGroup = TG_PrePhysics;
}

bool UGahyeonCharacterPresentationComponent::IsPoseConfirmationCurrent(
    const FString& RequestedPhase,
    int64 RequestedGeneration,
    int64 RequestedRuntimeEpoch,
    const FString& CurrentPhase,
    int64 CurrentGeneration,
    int64 CurrentRuntimeEpoch)
{
    return RequestedRuntimeEpoch > 0
        && RequestedRuntimeEpoch == CurrentRuntimeEpoch
        && RequestedGeneration == CurrentGeneration
        && RequestedPhase == CurrentPhase
        && (RequestedPhase == TEXT("listening") || RequestedPhase == TEXT("thinking"));
}

void UGahyeonCharacterPresentationComponent::BeginPlay()
{
    Super::BeginPlay();
    RefreshRuntime();
}

void UGahyeonCharacterPresentationComponent::EndPlay(
    const EEndPlayReason::Type EndPlayReason)
{
    ClearUserTarget();
    if (IsValid(FaceMesh))
    {
        for (const FName Curve : AppliedFacialCurves)
        {
            FaceMesh->SetMorphTarget(Curve, 0.0f, false);
        }
        UAnimInstance* FaceAnimInstance = FaceMesh->GetAnimInstance();
        if (FaceAnimInstance != nullptr && !AppliedControlRigCurves.IsEmpty()
            && FaceAnimInstance->GetClass()->ImplementsInterface(
                UGahyeonFacialControlRigBridge::StaticClass()))
        {
            IGahyeonFacialControlRigBridge::Execute_ApplyFacialControlRigCurves(
                FaceAnimInstance, {}, AppliedControlRigCurves.Array());
        }
    }
    AppliedFacialCurves.Reset();
    AppliedControlRigCurves.Reset();
    StopGestureMontage(0.0f);
    if (ProfilePreloadHandle.IsValid()) ProfilePreloadHandle->CancelHandle();
    ProfilePreloadHandle.Reset();
    Runtime = nullptr;
    bProfileApplied = false;
    bProfileLoadAttempted = false;
    bHasFrame = false;
    Super::EndPlay(EndPlayReason);
}

void UGahyeonCharacterPresentationComponent::TickComponent(
    float DeltaTime,
    ELevelTick TickType,
    FActorComponentTickFunction* ThisTickFunction)
{
    Super::TickComponent(DeltaTime, TickType, ThisTickFunction);
    if (Runtime == nullptr) RefreshRuntime();
    if (Runtime == nullptr) return;
    UpdateLocalCameraAttention();

    const FGahyeonRuntimeFrameSnapshot Next = Runtime->GetSnapshot();
    const bool bPhaseChanged = bHasFrame
        && Frame.ConversationPhase != Next.ConversationPhase;
    if (bPhaseChanged)
    {
        OnConversationPhaseChanged.Broadcast(
            Frame.ConversationPhase, Next.ConversationPhase);
    }
    if (bHasFrame && (Frame.DominantEmotion != Next.DominantEmotion
        || !FMath::IsNearlyEqual(
            Frame.DominantEmotionIntensity, Next.DominantEmotionIntensity, 0.001)))
    {
        OnDominantEmotionChanged.Broadcast(
            Frame.DominantEmotion,
            Frame.DominantEmotionIntensity,
            Next.DominantEmotion,
            Next.DominantEmotionIntensity);
    }
    if (bHasFrame && (Frame.GestureSemantic != Next.GestureSemantic
        || Frame.GestureVariantId != Next.GestureVariantId))
    {
        OnGestureChanged.Broadcast(
            Frame.GestureSemantic,
            Frame.GestureVariantId,
            Next.GestureSemantic,
            Next.GestureVariantId);
    }
    if (bHasFrame && Frame.ActiveWorldActionId != Next.ActiveWorldActionId)
    {
        OnWorldActionChanged.Broadcast(
            Frame.ActiveWorldActionId, Next.ActiveWorldActionId);
    }
    if (bHasFrame && Frame.Posture != Next.Posture)
    {
        OnPostureChanged.Broadcast(Frame.Posture, Next.Posture);
    }
    Frame = Next;
    bHasFrame = true;
    if (bProfileApplied && Profile != nullptr)
    {
        ProceduralPose = Profile->ResolveProceduralPose(
            Frame.Breath, Frame.Blink, Frame.AmbientEyeYaw, Frame.AmbientEyePitch,
            Frame.MicroHeadYaw, Frame.MicroHeadPitch, Frame.WeightShift,
            Frame.AttentionEyeYaw, Frame.AttentionEyePitch,
            Frame.AttentionHeadYaw, Frame.AttentionHeadPitch,
            Frame.AttentionTrackingWeight);
    }
    else
    {
        ProceduralPose = {};
    }
    ApplyFacialCurves();
    SynchronizeGesture();
}

bool UGahyeonCharacterPresentationComponent::ConfirmConversationPoseApplied(
    const FString& Phase,
    int64 Generation,
    int64 PresentationRuntimeEpoch)
{
    if (Runtime == nullptr) RefreshRuntime();
    if (Runtime == nullptr || !IsPoseConfirmationCurrent(
        Phase, Generation, PresentationRuntimeEpoch,
        Frame.ConversationPhase, Frame.CurrentGeneration,
        static_cast<int64>(Runtime->GetRuntimeEpoch())))
    {
        return false;
    }
    if (Phase == TEXT("listening"))
    {
        return Runtime->NotifyListeningPresented(
            Generation, static_cast<uint64>(PresentationRuntimeEpoch));
    }
    if (Phase == TEXT("thinking"))
    {
        return Runtime->NotifyThinkingPresented(
            Generation, static_cast<uint64>(PresentationRuntimeEpoch));
    }
    return false;
}

bool UGahyeonCharacterPresentationComponent::ConfirmVisemeApplied(
    const FString& Semantic,
    int64 Generation,
    int64 PresentationRuntimeEpoch)
{
    if (Runtime == nullptr) RefreshRuntime();
    if (Runtime == nullptr || Semantic.IsEmpty()
        || PresentationRuntimeEpoch <= 0
        || PresentationRuntimeEpoch != GetRuntimeEpoch()
        || Generation != Frame.CurrentGeneration
        || !Frame.bLipSyncActive
        || (Semantic != Frame.PrimaryViseme && Semantic != Frame.SecondaryViseme))
    {
        return false;
    }
    return Runtime->NotifyVisemePresented(
        Semantic, Generation, static_cast<uint64>(PresentationRuntimeEpoch));
}

USkeletalMeshComponent* UGahyeonCharacterPresentationComponent::ResolveGestureMesh()
{
    if (IsValid(GestureMesh)) return GestureMesh;
    if (ACharacter* Character = Cast<ACharacter>(GetOwner()))
    {
        GestureMesh = Character->GetMesh();
    }
    if (!IsValid(GestureMesh) && GetOwner() != nullptr)
    {
        GestureMesh = GetOwner()->FindComponentByClass<USkeletalMeshComponent>();
    }
    return GestureMesh;
}

void UGahyeonCharacterPresentationComponent::StopGestureMontage(float BlendOutSeconds)
{
    ++GestureRequestGeneration;
    if (GestureLoadHandle.IsValid()) GestureLoadHandle->CancelHandle();
    GestureLoadHandle.Reset();
    if (GestureAnimInstance.IsValid() && !PendingGestureMontage.IsNull())
    {
        GestureAnimInstance->Montage_Stop(BlendOutSeconds, PendingGestureMontage.Get());
    }
    GestureAnimInstance.Reset();
    PendingGestureMontage.Reset();
    AppliedGestureVariantId.Reset();
}

void UGahyeonCharacterPresentationComponent::SynchronizeGesture()
{
    const FString DesiredVariant = Frame.bGestureActive ? Frame.GestureVariantId : FString{};
    if (DesiredVariant == AppliedGestureVariantId) return;
    StopGestureMontage();
    if (!Frame.bGestureActive || Profile == nullptr || !bProfileApplied) return;
    const FGahyeonGesturePresentationDefinition* Definition = Profile->FindGesture(
        FName(*Frame.GestureSemantic), FName(*Frame.GestureVariantId),
        FName(*Frame.Posture), Frame.GestureIntensity);
    if (Definition == nullptr || Definition->Montage.IsNull()) return;

    USkeletalMeshComponent* Mesh = ResolveGestureMesh();
    UAnimInstance* AnimInstance = Mesh != nullptr ? Mesh->GetAnimInstance() : nullptr;
    if (AnimInstance == nullptr) return;
    AppliedGestureVariantId = Frame.GestureVariantId;
    PendingGestureMontage = Definition->Montage;
    GestureAnimInstance = AnimInstance;
    const uint64 Request = ++GestureRequestGeneration;
    const int64 Epoch = GetRuntimeEpoch();
    GestureLoadHandle = UAssetManager::GetStreamableManager().RequestAsyncLoad(
        PendingGestureMontage.ToSoftObjectPath(),
        FStreamableDelegate::CreateUObject(
            this, &UGahyeonCharacterPresentationComponent::OnGestureMontageLoaded,
            Request, Epoch));
    if (!GestureLoadHandle.IsValid()) StopGestureMontage();
}

void UGahyeonCharacterPresentationComponent::OnGestureMontageLoaded(
    uint64 RequestGeneration,
    int64 RuntimeEpoch)
{
    GestureLoadHandle.Reset();
    if (RequestGeneration != GestureRequestGeneration || RuntimeEpoch != GetRuntimeEpoch()
        || !Frame.bGestureActive || AppliedGestureVariantId != Frame.GestureVariantId
        || !GestureAnimInstance.IsValid()) return;
    UAnimMontage* Montage = PendingGestureMontage.Get();
    if (Montage == nullptr || GestureAnimInstance->Montage_Play(Montage) <= 0.0f)
    {
        StopGestureMontage();
    }
}

USkeletalMeshComponent* UGahyeonCharacterPresentationComponent::ResolveFaceMesh()
{
    if (IsValid(FaceMesh)) return FaceMesh;
    AActor* Owner = GetOwner();
    if (Owner == nullptr) return nullptr;
    FaceMesh = Owner->FindComponentByClass<USkeletalMeshComponent>();
    return FaceMesh;
}

void UGahyeonCharacterPresentationComponent::ApplyFacialCurves()
{
    if (!bApplyFacialMorphTargets || !bProfileApplied || Profile == nullptr) return;
    USkeletalMeshComponent* Mesh = ResolveFaceMesh();
    if (Mesh == nullptr || Mesh->GetSkeletalMeshAsset() == nullptr) return;

    TMap<FName, float> Weights;
    Profile->ResolveFacialCurveWeights(
        Frame.EmotionDimensions,
        FName(*Frame.PrimaryViseme),
        Frame.PrimaryVisemeWeight,
        FName(*Frame.SecondaryViseme),
        Frame.SecondaryVisemeWeight,
        Frame.JawOpen,
        Frame.Blink,
        Weights);
    TMap<FName, float> MorphWeights;
    TMap<FName, float> ControlRigWeights;
    for (const TPair<FName, float>& Weight : Weights)
    {
        TMap<FName, float>& Target = Profile->ResolveFacialTarget(Weight.Key)
            == EGahyeonFacialTarget::ControlRig ? ControlRigWeights : MorphWeights;
        Target.Add(Weight.Key, Weight.Value);
    }
    for (const FName Curve : AppliedFacialCurves)
    {
        if (!MorphWeights.Contains(Curve)) Mesh->SetMorphTarget(Curve, 0.0f, false);
    }
    for (const TPair<FName, float>& Weight : MorphWeights)
    {
        Mesh->SetMorphTarget(Weight.Key, Weight.Value, false);
    }
    AppliedFacialCurves.Reset();
    for (const TPair<FName, float>& Weight : MorphWeights)
    {
        AppliedFacialCurves.Add(Weight.Key);
    }
    UAnimInstance* FaceAnimInstance = Mesh->GetAnimInstance();
    if (!ControlRigWeights.IsEmpty() || !AppliedControlRigCurves.IsEmpty())
    {
        if (FaceAnimInstance == nullptr
            || !FaceAnimInstance->GetClass()->ImplementsInterface(
                UGahyeonFacialControlRigBridge::StaticClass()))
        {
            return;
        }
        TArray<FName> ToReset;
        for (const FName Curve : AppliedControlRigCurves)
        {
            if (!ControlRigWeights.Contains(Curve)) ToReset.Add(Curve);
        }
        const bool bApplied = IGahyeonFacialControlRigBridge::Execute_ApplyFacialControlRigCurves(
            FaceAnimInstance, ControlRigWeights, ToReset);
        if (!bApplied) return;
        AppliedControlRigCurves.Reset();
        for (const TPair<FName, float>& Weight : ControlRigWeights)
        {
            AppliedControlRigCurves.Add(Weight.Key);
        }
    }

    if (!Frame.PrimaryViseme.IsEmpty() && Frame.PrimaryVisemeWeight > 0.0)
    {
        const FName Semantic(*Frame.PrimaryViseme);
        const bool bAppliedBoundViseme = Profile->VisemeCurves.ContainsByPredicate(
            [&MorphWeights, &ControlRigWeights, this, Semantic](
                const FGahyeonFacialCurveBinding& Binding)
            {
                if (Binding.Semantic != Semantic) return false;
                const float* AppliedWeight = Binding.Target == EGahyeonFacialTarget::ControlRig
                    ? ControlRigWeights.Find(Binding.CurveName)
                    : MorphWeights.Find(Binding.CurveName);
                return AppliedWeight != nullptr && *AppliedWeight > KINDA_SMALL_NUMBER
                    && (Binding.Target != EGahyeonFacialTarget::ControlRig
                        || AppliedControlRigCurves.Contains(Binding.CurveName));
            });
        if (bAppliedBoundViseme && !Profile->VisemeCurves.ContainsByPredicate(
            [Semantic](const FGahyeonFacialCurveBinding& Binding)
            {
                return Binding.Semantic == Semantic
                    && Binding.Target == EGahyeonFacialTarget::ControlRig;
            }))
        {
            ConfirmVisemeApplied(
                Frame.PrimaryViseme, Frame.CurrentGeneration, GetRuntimeEpoch());
        }
    }
}

bool UGahyeonCharacterPresentationComponent::UpdateUserWorldTarget(
    FVector WorldTarget,
    double Confidence)
{
    const bool bApplied = ApplyWorldAttentionTarget(WorldTarget, Confidence);
    if (bApplied)
    {
        if (const UWorld* World = GetWorld())
        {
            LastExternalTrackerUpdateSeconds = World->GetTimeSeconds();
        }
    }
    return bApplied;
}

bool UGahyeonCharacterPresentationComponent::ApplyWorldAttentionTarget(
    FVector WorldTarget,
    double Confidence)
{
    if (Runtime == nullptr) RefreshRuntime();
    const AActor* Owner = GetOwner();
    if (Runtime == nullptr || Owner == nullptr || WorldTarget.ContainsNaN()
        || !FMath::IsFinite(Confidence))
    {
        return false;
    }
    const FVector Local = Owner->GetActorTransform().InverseTransformPositionNoScale(WorldTarget)
        - AttentionOriginLocal;
    return Runtime->SetLocalAttentionTarget(Local, Confidence);
}

void UGahyeonCharacterPresentationComponent::UpdateLocalCameraAttention()
{
    UWorld* World = GetWorld();
    if (!bTrackLocalPlayerCamera || World == nullptr || World->GetNetMode() == NM_DedicatedServer)
    {
        return;
    }
    const double Now = World->GetTimeSeconds();
    if (Now < NextLocalCameraUpdateSeconds
        || Now - LastExternalTrackerUpdateSeconds < ExternalTrackerPrioritySeconds)
    {
        return;
    }
    NextLocalCameraUpdateSeconds = Now + FMath::Max(0.01, LocalCameraUpdateIntervalSeconds);
    APlayerController* Controller = World->GetFirstPlayerController();
    if (Controller == nullptr) return;
    FVector CameraLocation;
    FRotator CameraRotation;
    Controller->GetPlayerViewPoint(CameraLocation, CameraRotation);
    (void)CameraRotation;
    ApplyWorldAttentionTarget(CameraLocation, FMath::Clamp(LocalCameraConfidence, 0.0, 1.0));
}

void UGahyeonCharacterPresentationComponent::ClearUserTarget()
{
    LastExternalTrackerUpdateSeconds = -1.0e9;
    if (Runtime != nullptr) Runtime->ClearAttentionTarget();
}

bool UGahyeonCharacterPresentationComponent::NotifyWorldNavigationArrived()
{
    if (Runtime == nullptr) RefreshRuntime();
    return Runtime != nullptr && Frame.bWorldActionActive
        && Runtime->NotifyWorldNavigationArrived(Frame.ActiveWorldActionId);
}

bool UGahyeonCharacterPresentationComponent::NotifyWorldActionFinished(
    const FString& Outcome,
    const FString& Reason,
    FVector FinalPosition)
{
    if (Runtime == nullptr) RefreshRuntime();
    return Runtime != nullptr && Frame.bWorldActionActive
        && Runtime->NotifyWorldActionFinished(
            Frame.ActiveWorldActionId, Outcome, Reason, FinalPosition);
}

bool UGahyeonCharacterPresentationComponent::ApplyProfile()
{
    bProfileLoadAttempted = true;
    if (Runtime == nullptr) RefreshRuntime();
    FString Error;
    bProfileApplied = Runtime != nullptr && Profile != nullptr
        && Profile->Validate(Error)
        && Runtime->ConfigurePresentationProfile(*Profile);
    if (bProfileApplied) PreloadProfileAssets();
    return bProfileApplied;
}

void UGahyeonCharacterPresentationComponent::PreloadProfileAssets()
{
    if (ProfilePreloadHandle.IsValid()) ProfilePreloadHandle->CancelHandle();
    ProfilePreloadHandle.Reset();
    if (Profile == nullptr) return;
    TArray<FSoftObjectPath> Assets;
    for (const FGahyeonGesturePresentationDefinition& Gesture : Profile->Gestures)
    {
        if (!Gesture.Montage.IsNull()) Assets.AddUnique(Gesture.Montage.ToSoftObjectPath());
    }
    for (const FGahyeonInteractionPresentationDefinition& Interaction : Profile->Interactions)
    {
        if (!Interaction.Montage.IsNull())
        {
            Assets.AddUnique(Interaction.Montage.ToSoftObjectPath());
        }
    }
    if (!Assets.IsEmpty())
    {
        ProfilePreloadHandle = UAssetManager::GetStreamableManager().RequestAsyncLoad(Assets);
    }
}

void UGahyeonCharacterPresentationComponent::RefreshRuntime()
{
    if (UGameInstance* GameInstance = GetWorld() != nullptr
        ? GetWorld()->GetGameInstance() : nullptr)
    {
        Runtime = GameInstance->GetSubsystem<UGahyeonRuntimeSubsystem>();
        if (Runtime != nullptr && Profile != nullptr && !bProfileLoadAttempted)
        {
            ApplyProfile();
        }
    }
}
