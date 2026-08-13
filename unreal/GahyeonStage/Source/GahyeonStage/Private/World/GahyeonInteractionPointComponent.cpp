#include "World/GahyeonInteractionPointComponent.h"

#include "Engine/World.h"
#include "GameFramework/Actor.h"
#include "World/GahyeonInteractionRegistrySubsystem.h"

void UGahyeonInteractionPointComponent::Configure(
    FName InSemanticId,
    FName InRoomId,
    TArray<FName> InSupportedActivities)
{
    check(!HasBegunPlay());
    SemanticId = InSemanticId;
    RoomId = InRoomId;
    SupportedActivities = MoveTemp(InSupportedActivities);
}

void UGahyeonInteractionPointComponent::BeginPlay()
{
    Super::BeginPlay();
    UGahyeonInteractionRegistrySubsystem* Registry = GetWorld() != nullptr
        ? GetWorld()->GetSubsystem<UGahyeonInteractionRegistrySubsystem>()
        : nullptr;
    bRegistered = Registry != nullptr && !RoomId.IsNone() && !SupportedActivities.IsEmpty()
        && !SupportedActivities.Contains(NAME_None) && Registry->RegisterPoint(*this);
    if (!bRegistered)
    {
        UE_LOG(LogTemp, Error,
            TEXT("Invalid or duplicate Gahyeon interaction point '%s' in room '%s' on '%s'"),
            *SemanticId.ToString(), *RoomId.ToString(), GetOwner() != nullptr
                ? *GetOwner()->GetName() : TEXT("<no-owner>"));
    }
}

void UGahyeonInteractionPointComponent::EndPlay(
    const EEndPlayReason::Type EndPlayReason)
{
    if (bRegistered && GetWorld() != nullptr)
    {
        if (UGahyeonInteractionRegistrySubsystem* Registry =
            GetWorld()->GetSubsystem<UGahyeonInteractionRegistrySubsystem>())
        {
            Registry->UnregisterPoint(*this);
        }
    }
    bRegistered = false;
    Super::EndPlay(EndPlayReason);
}

bool UGahyeonInteractionPointComponent::SupportsActivity(FName Activity) const
{
    return bRegistered && !Activity.IsNone() && SupportedActivities.Contains(Activity);
}
