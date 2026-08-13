#pragma once

#include "GameFramework/Actor.h"
#include "GahyeonPrototypeRoom.generated.h"

class USceneComponent;
class UStaticMesh;
class UStaticMeshComponent;

/**
 * Asset-free one-room behavior fixture. It exists to exercise navigation and semantic
 * interaction boundaries in PIE; it is not the production environment art.
 */
UCLASS(NotBlueprintable)
class GAHYEONSTAGE_API AGahyeonPrototypeRoom final : public AActor
{
    GENERATED_BODY()

public:
    AGahyeonPrototypeRoom();

private:
    UStaticMeshComponent* AddBox(
        FName Name,
        UStaticMesh* Cube,
        const FVector& Location,
        const FVector& Scale);

    UPROPERTY(VisibleAnywhere)
    TObjectPtr<USceneComponent> RoomRoot;

    UPROPERTY(VisibleAnywhere)
    TArray<TObjectPtr<UStaticMeshComponent>> Geometry;
};
