#pragma once

#include "Subsystems/WorldSubsystem.h"
#include "GahyeonInteractionRegistrySubsystem.generated.h"

class UGahyeonInteractionPointComponent;

/** World-local unique index for semantic interaction points. */
UCLASS()
class GAHYEONSTAGE_API UGahyeonInteractionRegistrySubsystem final : public UWorldSubsystem
{
    GENERATED_BODY()

public:
    bool RegisterPoint(UGahyeonInteractionPointComponent& Point);
    void UnregisterPoint(UGahyeonInteractionPointComponent& Point);
    UGahyeonInteractionPointComponent* Resolve(FName SemanticId) const;
    int32 GetRegisteredPointCount() const;

private:
    UPROPERTY(Transient)
    TMap<FName, TWeakObjectPtr<UGahyeonInteractionPointComponent>> Points;
};
