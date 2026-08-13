#include "Presentation/GahyeonFacialControlRigBridge.h"

bool IGahyeonFacialControlRigBridge::ApplyFacialControlRigCurves_Implementation(
    const TMap<FName, float>& ActiveCurves,
    const TArray<FName>& CurvesToReset)
{
    return false;
}

TMap<FName, float> IGahyeonFacialControlRigBridge::GetFacialControlRigCurveWeights_Implementation() const
{
    return {};
}
