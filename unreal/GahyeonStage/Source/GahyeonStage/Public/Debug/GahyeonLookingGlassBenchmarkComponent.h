#pragma once

#include "Components/ActorComponent.h"
#include "GahyeonLookingGlassBenchmarkComponent.generated.h"

class IGahyeonLookingGlassAttestationProvider;

/** Command-line opt-in raw sample recorder for physical Looking Glass acceptance. */
UCLASS(NotBlueprintable, ClassGroup = "Gahyeon")
class GAHYEONSTAGE_API UGahyeonLookingGlassBenchmarkComponent final : public UActorComponent
{
    GENERATED_BODY()

public:
    UGahyeonLookingGlassBenchmarkComponent();
    virtual void BeginPlay() override;
    virtual void TickComponent(
        float DeltaTime,
        ELevelTick TickType,
        FActorComponentTickFunction* ThisTickFunction) override;
    virtual void EndPlay(const EEndPlayReason::Type EndPlayReason) override;

private:
    bool TryBeginAttestation(FString& OutFailure);
    bool WriteFragment();
    FString MeasurementRunId;
    FString ProfileId;
    FString Mode;
    FString Scenario;
    int32 Views = 0;
    int32 QuiltWidth = 0;
    int32 QuiltHeight = 0;
    double DurationSeconds = 0.0;
    double ElapsedSeconds = 0.0;
    double PendingAttestationSeconds = 0.0;
    bool bAttestationStarted = false;
    bool bWritten = false;
    IGahyeonLookingGlassAttestationProvider* AttestationProvider = nullptr;
    TArray<double> FrameMilliseconds;
};
