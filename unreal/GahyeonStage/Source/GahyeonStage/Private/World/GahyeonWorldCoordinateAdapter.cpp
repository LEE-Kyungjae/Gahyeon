#include "World/GahyeonWorldCoordinateAdapter.h"

FVector FGahyeonWorldCoordinateAdapter::ToUnrealCentimeters(
    const Gahyeon::WorldPosition& Position)
{
    return FVector(
        Position.X * UnrealCentimetersPerCoreMeter,
        Position.Z * UnrealCentimetersPerCoreMeter,
        Position.Y * UnrealCentimetersPerCoreMeter);
}

Gahyeon::WorldPosition FGahyeonWorldCoordinateAdapter::ToCoreMeters(
    const FVector& Position)
{
    return {
        .X = Position.X / UnrealCentimetersPerCoreMeter,
        .Y = Position.Z / UnrealCentimetersPerCoreMeter,
        .Z = Position.Y / UnrealCentimetersPerCoreMeter};
}
