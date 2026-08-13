#pragma once

#include "CoreMinimal.h"
#include "Gahyeon/WorldStateRuntime.h"

/**
 * Converts the platform-neutral world convention into Unreal's coordinate system.
 *
 * Core positions are metres in (X horizontal, Y elevation, Z horizontal-depth).
 * Unreal positions are centimetres in (X horizontal, Y horizontal-depth, Z elevation).
 * Keeping this mapping at the adapter boundary prevents Core state from depending on
 * renderer units or axis conventions.
 */
struct GAHYEONSTAGE_API FGahyeonWorldCoordinateAdapter final
{
    static constexpr double UnrealCentimetersPerCoreMeter = 100.0;

    static FVector ToUnrealCentimeters(const Gahyeon::WorldPosition& Position);
    static Gahyeon::WorldPosition ToCoreMeters(const FVector& Position);
};
