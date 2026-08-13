#pragma once

#include "UObject/Interface.h"
#include "GahyeonFacialControlRigBridge.generated.h"

UINTERFACE(BlueprintType)
class GAHYEONSTAGE_API UGahyeonFacialControlRigBridge : public UInterface
{
    GENERATED_BODY()
};

/** Implemented by the MetaHuman face AnimInstance that owns the Control Rig graph. */
class GAHYEONSTAGE_API IGahyeonFacialControlRigBridge
{
    GENERATED_BODY()

public:
    UFUNCTION(BlueprintNativeEvent, BlueprintCallable, Category = "Gahyeon|Presentation")
    bool ApplyFacialControlRigCurves(
        const TMap<FName, float>& ActiveCurves,
        const TArray<FName>& CurvesToReset);
    virtual bool ApplyFacialControlRigCurves_Implementation(
        const TMap<FName, float>& ActiveCurves,
        const TArray<FName>& CurvesToReset);

    /** Returns values actually held by the Anim Blueprint bridge for QA observation. */
    UFUNCTION(BlueprintNativeEvent, BlueprintCallable, Category = "Gahyeon|Presentation|QA")
    TMap<FName, float> GetFacialControlRigCurveWeights() const;
    virtual TMap<FName, float> GetFacialControlRigCurveWeights_Implementation() const;
};
