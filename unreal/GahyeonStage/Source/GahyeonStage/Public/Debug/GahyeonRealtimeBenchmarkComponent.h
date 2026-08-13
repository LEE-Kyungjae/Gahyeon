#pragma once

#include "Components/ActorComponent.h"
#include "GahyeonRealtimeBenchmarkComponent.generated.h"

/** Opt-in packaged-build recorder for the normal single-view Desktop renderer. */
UCLASS(NotBlueprintable, ClassGroup = "Gahyeon")
class GAHYEONSTAGE_API UGahyeonRealtimeBenchmarkComponent final : public UActorComponent
{
    GENERATED_BODY()

public:
    UGahyeonRealtimeBenchmarkComponent();
    virtual void BeginPlay() override;
    virtual void TickComponent(
        float DeltaTime,
        ELevelTick TickType,
        FActorComponentTickFunction* ThisTickFunction) override;

private:
    bool WriteResult();
    FString MeasurementRunId;
    FString ExplicitOutputPath;
    double DurationSeconds = 0.0;
    double ElapsedSeconds = 0.0;
    int64 InitialReflexUpdates = 0;
    int64 InitialBehaviorUpdates = 0;
    int64 LastReflexUpdates = 0;
    int64 LastBehaviorUpdates = 0;
    double LastReflexAdvanceSeconds = 0.0;
    double LastBehaviorAdvanceSeconds = 0.0;
    double MaxReflexGapSeconds = 0.0;
    double MaxBehaviorGapSeconds = 0.0;
    bool bExitWhenWritten = false;
    bool bWritten = false;
    TArray<double> FrameMilliseconds;
};
