#include "World/GahyeonInteractionRegistrySubsystem.h"

#include "World/GahyeonInteractionPointComponent.h"

bool UGahyeonInteractionRegistrySubsystem::RegisterPoint(
    UGahyeonInteractionPointComponent& Point)
{
    const FName SemanticId = Point.GetSemanticId();
    if (SemanticId.IsNone()) return false;
    if (TWeakObjectPtr<UGahyeonInteractionPointComponent>* Existing = Points.Find(SemanticId))
    {
        if (Existing->IsValid()) return Existing->Get() == &Point;
        Points.Remove(SemanticId);
    }
    Points.Add(SemanticId, &Point);
    return true;
}

void UGahyeonInteractionRegistrySubsystem::UnregisterPoint(
    UGahyeonInteractionPointComponent& Point)
{
    if (TWeakObjectPtr<UGahyeonInteractionPointComponent>* Existing =
        Points.Find(Point.GetSemanticId()); Existing != nullptr && Existing->Get() == &Point)
    {
        Points.Remove(Point.GetSemanticId());
    }
}

UGahyeonInteractionPointComponent* UGahyeonInteractionRegistrySubsystem::Resolve(
    FName SemanticId) const
{
    const TWeakObjectPtr<UGahyeonInteractionPointComponent>* Found = Points.Find(SemanticId);
    return Found != nullptr ? Found->Get() : nullptr;
}

int32 UGahyeonInteractionRegistrySubsystem::GetRegisteredPointCount() const
{
    int32 Count = 0;
    for (const auto& Entry : Points)
    {
        if (Entry.Value.IsValid()) ++Count;
    }
    return Count;
}
