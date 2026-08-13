#include "LookingGlass/GahyeonLookingGlassAttestation.h"

#include "Game/LookingGlassSceneCaptureComponent2D.h"
#include "ILookingGlassRuntime.h"
#include "LookingGlassSettings.h"
#include "Misc/LookingGlassHelpers.h"
#include "Render/LookingGlassViewportClient.h"
#include "Render/SLookingGlassViewport.h"

#include "Features/IModularFeatures.h"
#include "HAL/PlatformFileManager.h"
#include "Misc/Paths.h"
#include "Modules/ModuleManager.h"

namespace
{
FString CurrentPerformanceMode()
{
#if WITH_EDITOR
    switch (GetDefault<ULookingGlassSettings>()->LookingGlassEditorSettings.PerformanceMode)
    {
    case ELookingGlassPerformanceMode::Realtime:
        return TEXT("Realtime");
    case ELookingGlassPerformanceMode::RealtimeAdaptive:
        return TEXT("RealtimeAdaptive");
    case ELookingGlassPerformanceMode::NonRealtime:
        return TEXT("NonRealtime");
    default:
        return FString();
    }
#else
    return TEXT("Realtime");
#endif
}
}

class FGahyeonLookingGlassAdapterModule final
    : public IModuleInterface
    , public IGahyeonLookingGlassAttestationProvider
{
public:
    virtual void StartupModule() override
    {
        IModularFeatures::Get().RegisterModularFeature(GetModularFeatureName(), this);
    }

    virtual void ShutdownModule() override
    {
        IModularFeatures::Get().UnregisterModularFeature(GetModularFeatureName(), this);
        ActiveRunId.Reset();
        Attestation = {};
        ActiveCapture.Reset();
    }

    virtual bool BeginAttestedCapture(
        const FGahyeonLookingGlassCaptureRequest& Request,
        FString& OutFailure) override
    {
        check(IsInGameThread());
        OutFailure.Reset();
        if (!ActiveRunId.IsEmpty())
        {
            if (ActiveRunId == Request.MeasurementRunId)
            {
                return true;
            }
            OutFailure = TEXT("another Looking Glass attestation run is active");
            return false;
        }
        if (!ILookingGlassRuntime::IsAvailable())
        {
            OutFailure = TEXT("LookingGlassRuntime is not loaded");
            return false;
        }

        ILookingGlassRuntime& Runtime = ILookingGlassRuntime::Get();
        if (!Runtime.IsPlaying() || !Runtime.IsRenderingOnDevice())
        {
            OutFailure = TEXT("Looking Glass player is not rendering on a physical display");
            return false;
        }
        const TWeakObjectPtr<ULookingGlassSceneCaptureComponent2D> Capture =
            LookingGlass::GetGameLookingGlassCaptureComponent();
        if (!Capture.IsValid() || !Capture->IsRegistered() || !Capture->HasBegunPlay())
        {
            OutFailure = TEXT("no begun and registered Looking Glass capture component is active");
            return false;
        }
        const FLookingGlassTilingQuality& Tiling = Capture->GetTilingValues();
        if (!Tiling.Name.Contains(TEXT("Go"), ESearchCase::IgnoreCase))
        {
            OutFailure = TEXT("active physical capture is not using the Go tiling profile");
            return false;
        }
        const FString ActualMode = CurrentPerformanceMode();
        const int32 ActualViews = Tiling.GetNumTiles();
        if (ActualMode != Request.Mode || ActualViews != Request.Views
            || Tiling.QuiltW != Request.QuiltWidth || Tiling.QuiltH != Request.QuiltHeight)
        {
            OutFailure = TEXT("CLI benchmark profile does not match active plugin capture settings");
            return false;
        }
        if (GetDefault<ULookingGlassSettings>()->LookingGlassScreenshotQuiltSettings.UseJPG)
        {
            OutFailure = TEXT("attested quilt evidence requires PNG screenshots");
            return false;
        }
        const TSharedPtr<SLookingGlassViewport> Viewport = Runtime.GetLookingGlassViewport();
        if (!Viewport.IsValid())
        {
            OutFailure = TEXT("Looking Glass player has no active viewport");
            return false;
        }

        ActiveRunId = Request.MeasurementRunId;
        ActiveCapture = Capture;
        Attestation = {};
        Attestation.MeasurementRunId = Request.MeasurementRunId;
        Attestation.Mode = ActualMode;
        Attestation.DeviceClass = TEXT("Looking Glass Go");
        Attestation.Views = ActualViews;
        Attestation.QuiltWidth = Tiling.QuiltW;
        Attestation.QuiltHeight = Tiling.QuiltH;
        Attestation.bPlayerActive = true;
        Attestation.bPhysicalDeviceActive = true;
        Attestation.bCaptureComponentActive = true;

        const FString CallbackRunId = ActiveRunId;
        Viewport->GetLookingGlassViewportClient()->TakeQuiltScreenshot(
            [this, CallbackRunId](const FString& Filename)
            {
                check(IsInGameThread());
                if (ActiveRunId != CallbackRunId) return;
                const FString FullPath = FPaths::ConvertRelativePathToFull(Filename);
                IPlatformFile& Files = FPlatformFileManager::Get().GetPlatformFile();
                if (FPaths::GetExtension(FullPath).Equals(TEXT("png"), ESearchCase::IgnoreCase)
                    && Files.FileExists(*FullPath) && Files.FileSize(*FullPath) > 0)
                {
                    Attestation.CaptureArtifactPath = FullPath;
                    Attestation.bQuiltFrameObserved = true;
                }
            });
        return true;
    }

    virtual bool ReadAttestation(
        const FString& MeasurementRunId,
        FGahyeonLookingGlassRuntimeAttestation& OutAttestation,
        FString& OutFailure) override
    {
        check(IsInGameThread());
        OutFailure.Reset();
        if (ActiveRunId != MeasurementRunId)
        {
            OutFailure = TEXT("Looking Glass attestation run ID is not active");
            return false;
        }
        if (!ILookingGlassRuntime::IsAvailable())
        {
            OutFailure = TEXT("LookingGlassRuntime unloaded during measurement");
            return false;
        }
        ILookingGlassRuntime& Runtime = ILookingGlassRuntime::Get();
        const bool bCaptureStillActive = ActiveCapture.IsValid()
            && ActiveCapture->IsRegistered() && ActiveCapture->HasBegunPlay()
            && LookingGlass::GetGameLookingGlassCaptureComponent() == ActiveCapture;
        Attestation.bPlayerActive = Runtime.IsPlaying();
        Attestation.bPhysicalDeviceActive = Runtime.IsRenderingOnDevice();
        Attestation.bCaptureComponentActive = bCaptureStillActive;
        if (!Attestation.IsPhysicalPresentationReady())
        {
            OutFailure = TEXT("physical player, capture, or quilt screenshot attestation is incomplete");
            return false;
        }
        OutAttestation = Attestation;
        return true;
    }

    virtual void EndAttestedCapture(const FString& MeasurementRunId) override
    {
        check(IsInGameThread());
        if (ActiveRunId != MeasurementRunId) return;
        ActiveRunId.Reset();
        Attestation = {};
        ActiveCapture.Reset();
    }

private:
    FString ActiveRunId;
    FGahyeonLookingGlassRuntimeAttestation Attestation;
    TWeakObjectPtr<ULookingGlassSceneCaptureComponent2D> ActiveCapture;
};

IMPLEMENT_MODULE(FGahyeonLookingGlassAdapterModule, GahyeonLookingGlassAdapter)
