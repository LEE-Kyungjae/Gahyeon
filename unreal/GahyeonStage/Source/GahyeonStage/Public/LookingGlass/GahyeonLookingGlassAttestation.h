#pragma once

#include "CoreMinimal.h"
#include "Features/IModularFeature.h"

/** Runtime-derived capture settings expected by one physical presentation run. */
struct FGahyeonLookingGlassCaptureRequest
{
    FString MeasurementRunId;
    FString Mode;
    int32 Views = 0;
    int32 QuiltWidth = 0;
    int32 QuiltHeight = 0;
};

/**
 * State returned by the optional Looking Glass adapter.
 *
 * CaptureArtifactPath is intentionally process-local. The Stage benchmark copies the file into
 * its immutable run directory and publishes only its relative URI and SHA-256.
 */
struct FGahyeonLookingGlassRuntimeAttestation
{
    int32 SchemaVersion = 1;
    FString Source = TEXT("GahyeonLookingGlassAdapter");
    FString PluginRelease = TEXT("2.1.1");
    FString RuntimeModule = TEXT("LookingGlassRuntime");
    FString MeasurementRunId;
    FString Mode;
    FString DeviceClass;
    FString CaptureArtifactPath;
    int32 Views = 0;
    int32 QuiltWidth = 0;
    int32 QuiltHeight = 0;
    bool bPlayerActive = false;
    bool bPhysicalDeviceActive = false;
    bool bCaptureComponentActive = false;
    bool bQuiltFrameObserved = false;

    bool IsPhysicalPresentationReady() const
    {
        return SchemaVersion == 1
            && Source == TEXT("GahyeonLookingGlassAdapter")
            && PluginRelease == TEXT("2.1.1")
            && RuntimeModule == TEXT("LookingGlassRuntime")
            && !MeasurementRunId.IsEmpty()
            && !Mode.IsEmpty()
            && DeviceClass == TEXT("Looking Glass Go")
            && !CaptureArtifactPath.IsEmpty()
            && Views >= 2 && QuiltWidth >= 512 && QuiltHeight >= 512
            && bPlayerActive && bPhysicalDeviceActive
            && bCaptureComponentActive && bQuiltFrameObserved;
    }
};

/** Optional renderer boundary implemented only by GahyeonLookingGlassAdapter. */
class GAHYEONSTAGE_API IGahyeonLookingGlassAttestationProvider : public IModularFeature
{
public:
    virtual ~IGahyeonLookingGlassAttestationProvider() = default;

    static FName GetModularFeatureName()
    {
        static const FName FeatureName(TEXT("GahyeonLookingGlassAttestation"));
        return FeatureName;
    }

    virtual bool BeginAttestedCapture(
        const FGahyeonLookingGlassCaptureRequest& Request,
        FString& OutFailure) = 0;
    virtual bool ReadAttestation(
        const FString& MeasurementRunId,
        FGahyeonLookingGlassRuntimeAttestation& OutAttestation,
        FString& OutFailure) = 0;
    virtual void EndAttestedCapture(const FString& MeasurementRunId) = 0;
};
