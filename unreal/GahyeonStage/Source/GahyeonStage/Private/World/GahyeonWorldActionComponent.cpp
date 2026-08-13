#include "World/GahyeonWorldActionComponent.h"

#include "GameFramework/Controller.h"
#include "GameFramework/Pawn.h"
#include "HAL/PlatformTime.h"
#include "Animation/AnimInstance.h"
#include "Animation/AnimMontage.h"
#include "Components/SkeletalMeshComponent.h"
#include "Engine/AssetManager.h"
#include "Engine/StreamableManager.h"
#include "Navigation/PathFollowingComponent.h"
#include "NavigationSystem.h"
#include "Presentation/GahyeonCharacterPresentationComponent.h"
#include "World/GahyeonInteractionPointComponent.h"
#include "World/GahyeonInteractionRegistrySubsystem.h"

UGahyeonWorldActionComponent::UGahyeonWorldActionComponent()
{
    PrimaryComponentTick.bCanEverTick = true;
    PrimaryComponentTick.bStartWithTickEnabled = true;
    PrimaryComponentTick.TickGroup = TG_PrePhysics;
}

EGahyeonNavigationReadiness UGahyeonWorldActionComponent::ClassifyNavigationReadiness(
    bool bUsesAutomaticNavigation,
    bool bHasPawn,
    bool bHasController,
    bool bHasNavigationSystem,
    bool bHasNavigationData)
{
    if (!bUsesAutomaticNavigation) return EGahyeonNavigationReadiness::CustomLocomotion;
    if (!bHasPawn) return EGahyeonNavigationReadiness::PawnUnavailable;
    if (!bHasController) return EGahyeonNavigationReadiness::ControllerUnavailable;
    if (!bHasNavigationSystem) return EGahyeonNavigationReadiness::NavigationSystemUnavailable;
    if (!bHasNavigationData) return EGahyeonNavigationReadiness::NavigationDataUnavailable;
    return EGahyeonNavigationReadiness::Ready;
}

FString UGahyeonWorldActionComponent::GetNavigationReadinessLabel() const
{
    UWorld* World = GetWorld();
    UNavigationSystemV1* NavigationSystem =
        FNavigationSystem::GetCurrent<UNavigationSystemV1>(World);
    const EGahyeonNavigationReadiness Readiness = ClassifyNavigationReadiness(
        bAutoNavigate,
        PawnOwner != nullptr,
        PawnOwner != nullptr && PawnOwner->GetController() != nullptr,
        NavigationSystem != nullptr,
        NavigationSystem != nullptr && NavigationSystem->GetDefaultNavDataInstance(
            FNavigationSystem::DontCreate) != nullptr);
    switch (Readiness)
    {
    case EGahyeonNavigationReadiness::CustomLocomotion: return TEXT("custom");
    case EGahyeonNavigationReadiness::PawnUnavailable: return TEXT("no_pawn");
    case EGahyeonNavigationReadiness::ControllerUnavailable: return TEXT("no_controller");
    case EGahyeonNavigationReadiness::NavigationSystemUnavailable: return TEXT("no_nav_system");
    case EGahyeonNavigationReadiness::NavigationDataUnavailable: return TEXT("no_nav_data");
    case EGahyeonNavigationReadiness::Ready: return TEXT("ready");
    }
    return TEXT("unknown");
}

void UGahyeonWorldActionComponent::BeginPlay()
{
    Super::BeginPlay();
    PawnOwner = Cast<APawn>(GetOwner());
    RefreshPresentation();
    SynchronizeAction();
}

void UGahyeonWorldActionComponent::EndPlay(const EEndPlayReason::Type EndPlayReason)
{
    StopLocalMotion(TEXT("component_ended"));
    Presentation = nullptr;
    PawnOwner = nullptr;
    Super::EndPlay(EndPlayReason);
}

void UGahyeonWorldActionComponent::TickComponent(
    float DeltaTime,
    ELevelTick TickType,
    FActorComponentTickFunction* ThisTickFunction)
{
    Super::TickComponent(DeltaTime, TickType, ThisTickFunction);
    if (Presentation == nullptr) RefreshPresentation();
    SynchronizeAction();
    if (!bNavigationIssued || CurrentPhase != TEXT("navigating")
        || PawnOwner == nullptr)
    {
        return;
    }

    if (FVector::DistSquared(PawnOwner->GetActorLocation(), CurrentTarget)
        <= FMath::Square(AcceptanceRadiusCm))
    {
        MarkNavigationArrived();
        return;
    }

    AController* Controller = PawnOwner->GetController();
    const UPathFollowingComponent* PathFollowing = Controller != nullptr
        ? Controller->FindComponentByClass<UPathFollowingComponent>()
        : nullptr;
    if (PathFollowing != nullptr
        && PathFollowing->GetStatus() == EPathFollowingStatus::Idle
        && FPlatformTime::Seconds() - NavigationStartedAtSeconds
            >= PathFailureGraceSeconds)
    {
        FinishCurrentAction(TEXT("failed"), TEXT("navigation_failed"));
    }
}

bool UGahyeonWorldActionComponent::MarkNavigationArrived()
{
    if (Presentation == nullptr || CurrentActionId.IsEmpty()
        || CurrentPhase != TEXT("navigating"))
    {
        return false;
    }
    if (PawnOwner != nullptr && PawnOwner->GetController() != nullptr)
    {
        PawnOwner->GetController()->StopMovement();
    }
    if (!Presentation->NotifyWorldNavigationArrived()) return false;
    CurrentPhase = TEXT("interacting");
    bNavigationIssued = false;
    EnterInteraction();
    return true;
}

bool UGahyeonWorldActionComponent::FinishCurrentAction(
    const FString& Outcome,
    const FString& Reason)
{
    if (Presentation == nullptr || PawnOwner == nullptr || CurrentActionId.IsEmpty())
    {
        return false;
    }
    const FString FinishedActionId = CurrentActionId;
    const bool bAccepted = Presentation->NotifyWorldActionFinished(
        Outcome, Reason, PawnOwner->GetActorLocation());
    if (bAccepted)
    {
        PendingCompletionActionId = FinishedActionId;
        StopLocalMotion(Outcome);
        OnActionStopped.Broadcast(FinishedActionId, Outcome);
        CurrentActionId.Reset();
        CurrentPhase.Reset();
        CurrentTargetRoom.Reset();
    }
    return bAccepted;
}

void UGahyeonWorldActionComponent::RefreshPresentation()
{
    AActor* Owner = GetOwner();
    Presentation = Owner != nullptr
        ? Owner->FindComponentByClass<UGahyeonCharacterPresentationComponent>()
        : nullptr;
    if (Presentation != nullptr)
    {
        AddTickPrerequisiteComponent(Presentation);
    }
}

void UGahyeonWorldActionComponent::SynchronizeAction()
{
    if (Presentation == nullptr) return;
    const FGahyeonRuntimeFrameSnapshot Frame = Presentation->GetFrame();
    if (Frame.RuntimeEpoch != CurrentRuntimeEpoch)
    {
        if (!CurrentActionId.IsEmpty())
        {
            const FString StoppedActionId = CurrentActionId;
            StopLocalMotion(TEXT("runtime_replaced"));
            OnActionStopped.Broadcast(StoppedActionId, TEXT("runtime_replaced"));
        }
        CurrentActionId.Reset();
        PendingCompletionActionId.Reset();
        CurrentPhase.Reset();
        CurrentTargetRoom.Reset();
        CurrentRuntimeEpoch = Frame.RuntimeEpoch;
    }
    if (!Frame.bWorldActionActive)
    {
        PendingCompletionActionId.Reset();
        if (!CurrentActionId.IsEmpty())
        {
            const FString StoppedActionId = CurrentActionId;
            StopLocalMotion(TEXT("authoritative_stop"));
            OnActionStopped.Broadcast(StoppedActionId, TEXT("authoritative_stop"));
            CurrentActionId.Reset();
            CurrentPhase.Reset();
            CurrentTargetRoom.Reset();
        }
        return;
    }

    if (Frame.ActiveWorldActionId == PendingCompletionActionId)
    {
        return;
    }
    PendingCompletionActionId.Reset();

    if (Frame.ActiveWorldActionId != CurrentActionId)
    {
        if (!CurrentActionId.IsEmpty()) StopLocalMotion(TEXT("superseded"));
        CurrentActionId = Frame.ActiveWorldActionId;
        CurrentPhase = Frame.WorldActionPhase;
        CurrentTarget = Frame.WorldActionTargetPosition;
        CurrentTargetRoom = Frame.WorldActionTargetRoom;
        CurrentActivity = Frame.WorldActionTargetActivity;
        CurrentInteractionTarget = Frame.WorldActionInteractionTarget;
        bInteractionAnnounced = false;
        if (!ResolveInteractionPoint())
        {
            FinishCurrentAction(TEXT("failed"), TEXT("interaction_point_invalid"));
            return;
        }
        if (CurrentPhase == TEXT("navigating")) BeginNavigation();
        else if (CurrentPhase == TEXT("interacting")) EnterInteraction();
        return;
    }

    if (Frame.WorldActionPhase != CurrentPhase)
    {
        CurrentPhase = Frame.WorldActionPhase;
        if (CurrentPhase == TEXT("interacting")) EnterInteraction();
    }
}

void UGahyeonWorldActionComponent::BeginNavigation()
{
    if (CurrentActionId.IsEmpty() || CurrentTarget.ContainsNaN()) return;
    NavigationStartedAtSeconds = FPlatformTime::Seconds();
    bNavigationIssued = false;
    if (bAutoNavigate)
    {
        if (PawnOwner == nullptr || PawnOwner->GetController() == nullptr)
        {
            FinishCurrentAction(TEXT("failed"), TEXT("navigation_controller_unavailable"));
            return;
        }
        UNavigationSystemV1* NavigationSystem =
            FNavigationSystem::GetCurrent<UNavigationSystemV1>(GetWorld());
        if (NavigationSystem == nullptr
            || NavigationSystem->GetDefaultNavDataInstance(
                FNavigationSystem::DontCreate) == nullptr)
        {
            FinishCurrentAction(TEXT("failed"), TEXT("navigation_data_unavailable"));
            return;
        }
        bNavigationIssued = true;
        UNavigationSystemV1::SimpleMoveToLocation(PawnOwner->GetController(), CurrentTarget);
    }
    OnNavigationRequested.Broadcast(
        CurrentActionId, CurrentTarget, CurrentActivity, CurrentInteractionTarget);
}

void UGahyeonWorldActionComponent::EnterInteraction()
{
    if (bInteractionAnnounced || CurrentActionId.IsEmpty()) return;
    bInteractionAnnounced = true;
    BeginInteractionMontage();
    if (CurrentActionId.IsEmpty()) return;
    OnInteractionRequested.Broadcast(
        CurrentActionId,
        CurrentActivity,
        CurrentInteractionTarget,
        CurrentResultPosture);
}

void UGahyeonWorldActionComponent::StopLocalMotion(const FString& Reason)
{
    (void)Reason;
    if (PawnOwner != nullptr && PawnOwner->GetController() != nullptr)
    {
        PawnOwner->GetController()->StopMovement();
    }
    bStoppingInteraction = true;
    if (InteractionAnimInstance.IsValid() && !PendingInteractionMontage.IsNull())
    {
        InteractionAnimInstance->Montage_Stop(0.15f, PendingInteractionMontage.Get());
    }
    bNavigationIssued = false;
    bInteractionAnnounced = false;
    ResolvedInteractionPoint = nullptr;
    if (InteractionLoadHandle.IsValid()) InteractionLoadHandle->CancelHandle();
    InteractionLoadHandle.Reset();
    PendingInteractionMontage.Reset();
    InteractionAnimInstance.Reset();
    InteractionPlayRate = 1.0;
    CurrentResultPosture.Reset();
    bStoppingInteraction = false;
}

bool UGahyeonWorldActionComponent::ResolveInteractionPoint()
{
    ResolvedInteractionPoint = nullptr;
    CurrentFacingRotation = PawnOwner != nullptr
        ? PawnOwner->GetActorRotation()
        : FRotator::ZeroRotator;
    if (CurrentInteractionTarget.IsEmpty()) return true;
    UGahyeonInteractionRegistrySubsystem* Registry = GetWorld() != nullptr
        ? GetWorld()->GetSubsystem<UGahyeonInteractionRegistrySubsystem>()
        : nullptr;
    ResolvedInteractionPoint = Registry != nullptr
        ? Registry->Resolve(FName(*CurrentInteractionTarget))
        : nullptr;
    if (ResolvedInteractionPoint == nullptr
        || ResolvedInteractionPoint->GetRoomId() != FName(*CurrentTargetRoom)
        || !ResolvedInteractionPoint->SupportsActivity(FName(*CurrentActivity)))
    {
        ResolvedInteractionPoint = nullptr;
        return false;
    }
    CurrentTarget = ResolvedInteractionPoint->GetComponentLocation();
    CurrentFacingRotation = ResolvedInteractionPoint->GetComponentRotation();
    return !CurrentTarget.ContainsNaN() && !CurrentFacingRotation.ContainsNaN();
}

void UGahyeonWorldActionComponent::BeginInteractionMontage()
{
    if (Presentation == nullptr || CurrentActionId.IsEmpty()) return;
    UGahyeonCharacterPresentationProfile* Profile = Presentation->GetProfile();
    const FGahyeonInteractionPresentationDefinition* Definition = Profile != nullptr
        ? Profile->FindInteraction(FName(*CurrentActivity), FName(*CurrentInteractionTarget))
        : nullptr;
    CurrentResultPosture = Definition != nullptr
        ? Definition->ResultPosture.ToString()
        : CurrentActivity;
    if (Definition == nullptr) return;
    PendingInteractionMontage = Definition->Montage;
    InteractionPlayRate = Definition->PlayRate;
    const FString RequestedActionId = CurrentActionId;
    const int64 RequestedRuntimeEpoch = CurrentRuntimeEpoch;
    InteractionLoadHandle = UAssetManager::GetStreamableManager().RequestAsyncLoad(
        PendingInteractionMontage.ToSoftObjectPath(),
        FStreamableDelegate::CreateUObject(
            this,
            &UGahyeonWorldActionComponent::OnInteractionMontageLoaded,
            RequestedActionId,
            RequestedRuntimeEpoch));
    if (!InteractionLoadHandle.IsValid())
    {
        FinishCurrentAction(TEXT("failed"), TEXT("interaction_asset_load_failed"));
    }
}

void UGahyeonWorldActionComponent::OnInteractionMontageLoaded(
    FString ActionId,
    int64 RuntimeEpoch)
{
    InteractionLoadHandle.Reset();
    if (ActionId != CurrentActionId || RuntimeEpoch != CurrentRuntimeEpoch
        || Presentation == nullptr || Presentation->GetRuntimeEpoch() != RuntimeEpoch
        || CurrentPhase != TEXT("interacting")) return;
    UAnimMontage* Montage = PendingInteractionMontage.Get();
    USkeletalMeshComponent* Mesh = PawnOwner != nullptr
        ? PawnOwner->FindComponentByClass<USkeletalMeshComponent>()
        : nullptr;
    UAnimInstance* AnimInstance = Mesh != nullptr ? Mesh->GetAnimInstance() : nullptr;
    if (Montage == nullptr || AnimInstance == nullptr
        || AnimInstance->Montage_Play(Montage, InteractionPlayRate) <= 0.0f)
    {
        FinishCurrentAction(TEXT("failed"), TEXT("interaction_montage_failed"));
        return;
    }
    InteractionAnimInstance = AnimInstance;
    FOnMontageEnded EndDelegate;
    EndDelegate.BindUObject(
        this,
        &UGahyeonWorldActionComponent::OnInteractionMontageEndedForOwner,
        ActionId,
        RuntimeEpoch);
    AnimInstance->Montage_SetEndDelegate(EndDelegate, Montage);
}

void UGahyeonWorldActionComponent::OnInteractionMontageEnded(
    UAnimMontage* Montage,
    bool bInterrupted)
{
    if (bStoppingInteraction || Montage != PendingInteractionMontage.Get()
        || CurrentActionId.IsEmpty()) return;
    FinishCurrentAction(
        bInterrupted ? TEXT("failed") : TEXT("completed"),
        bInterrupted ? TEXT("interaction_interrupted") : TEXT(""));
}

void UGahyeonWorldActionComponent::OnInteractionMontageEndedForOwner(
    UAnimMontage* Montage,
    bool bInterrupted,
    FString ActionId,
    int64 RuntimeEpoch)
{
    if (ActionId != CurrentActionId || RuntimeEpoch != CurrentRuntimeEpoch
        || Presentation == nullptr || Presentation->GetRuntimeEpoch() != RuntimeEpoch) return;
    OnInteractionMontageEnded(Montage, bInterrupted);
}
