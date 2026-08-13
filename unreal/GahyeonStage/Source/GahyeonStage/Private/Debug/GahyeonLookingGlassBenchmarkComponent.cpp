#include "Debug/GahyeonLookingGlassBenchmarkComponent.h"

#include "Dom/JsonObject.h"
#include "Engine/GameInstance.h"
#include "Features/IModularFeatures.h"
#include "HAL/PlatformFileManager.h"
#include "HAL/PlatformMisc.h"
#include "LookingGlass/GahyeonLookingGlassAttestation.h"
#include "Misc/CommandLine.h"
#include "Misc/FileHelper.h"
#include "Misc/Parse.h"
#include "Misc/Paths.h"
#include "Runtime/GahyeonRuntimeSubsystem.h"
#include "Serialization/JsonSerializer.h"
#include "Serialization/JsonWriter.h"

namespace
{
bool ReadArgument(const TCHAR* Name, FString& Value)
{
    return FParse::Value(FCommandLine::Get(), Name, Value) && !Value.IsEmpty();
}

bool IsSafeIdentifier(const FString& Value)
{
    if (Value.Len() < 8 || Value.Len() > 64 || !FChar::IsAlnum(Value[0])) return false;
    for (const TCHAR Character : Value)
    {
        if (!(Character >= TEXT('a') && Character <= TEXT('z'))
            && !(Character >= TEXT('0') && Character <= TEXT('9'))
            && Character != TEXT('_') && Character != TEXT('-')) return false;
    }
    return true;
}

TArray<TSharedPtr<FJsonValue>> Numbers(const TArray<int64>& Values)
{
    TArray<TSharedPtr<FJsonValue>> Result;
    Result.Reserve(Values.Num());
    for (const int64 Value : Values) Result.Add(MakeShared<FJsonValueNumber>(Value));
    return Result;
}

bool HashFile(const FString& Path, int64& OutBytes, FString& OutSha256)
{
    TArray<uint8> Bytes;
    if (!FFileHelper::LoadFileToArray(Bytes, *Path) || Bytes.IsEmpty()) return false;
    FSHA256Signature Signature;
    if (!FPlatformMisc::GetSHA256Signature(Bytes.GetData(), Bytes.Num(), Signature)) return false;
    OutBytes = Bytes.Num();
    OutSha256 = Signature.ToString().ToLower();
    return OutSha256.Len() == 64;
}
}

UGahyeonLookingGlassBenchmarkComponent::UGahyeonLookingGlassBenchmarkComponent()
{
    PrimaryComponentTick.bCanEverTick = true;
    PrimaryComponentTick.bStartWithTickEnabled = false;
}

void UGahyeonLookingGlassBenchmarkComponent::BeginPlay()
{
    Super::BeginPlay();
    FString Duration;
    FString ViewCount;
    FString Width;
    FString Height;
    const bool bConfigured =
        ReadArgument(TEXT("GahyeonLgRunId="), MeasurementRunId)
        && ReadArgument(TEXT("GahyeonLgProfile="), ProfileId)
        && ReadArgument(TEXT("GahyeonLgMode="), Mode)
        && ReadArgument(TEXT("GahyeonLgScenario="), Scenario)
        && ReadArgument(TEXT("GahyeonLgDuration="), Duration)
        && ReadArgument(TEXT("GahyeonLgViews="), ViewCount)
        && ReadArgument(TEXT("GahyeonLgQuiltWidth="), Width)
        && ReadArgument(TEXT("GahyeonLgQuiltHeight="), Height);
    DurationSeconds = FCString::Atod(*Duration);
    Views = FCString::Atoi(*ViewCount);
    QuiltWidth = FCString::Atoi(*Width);
    QuiltHeight = FCString::Atoi(*Height);
    const bool bValid = bConfigured && IsSafeIdentifier(MeasurementRunId)
        && IsSafeIdentifier(ProfileId) && DurationSeconds >= 60.0
        && Views >= 2 && Views <= 100 && QuiltWidth >= 512 && QuiltHeight >= 512
        && (Mode == TEXT("Realtime") || Mode == TEXT("RealtimeAdaptive")
            || Mode == TEXT("NonRealtime"))
        && (Scenario == TEXT("idle") || Scenario == TEXT("listening")
            || Scenario == TEXT("thinking") || Scenario == TEXT("speaking"));
    if (!bValid)
    {
        SetComponentTickEnabled(false);
        return;
    }
    const TArray<IGahyeonLookingGlassAttestationProvider*> Providers =
        IModularFeatures::Get().GetModularFeatureImplementations<
            IGahyeonLookingGlassAttestationProvider>(
                IGahyeonLookingGlassAttestationProvider::GetModularFeatureName());
    if (Providers.Num() != 1 || Providers[0] == nullptr)
    {
        UE_LOG(LogTemp, Error,
            TEXT("Looking Glass benchmark rejected: exactly one optional adapter is required"));
        SetComponentTickEnabled(false);
        return;
    }
    AttestationProvider = Providers[0];
    FrameMilliseconds.Reserve(FMath::Min(200000, FMath::CeilToInt(DurationSeconds * 240.0)));
    SetComponentTickEnabled(true);
}

void UGahyeonLookingGlassBenchmarkComponent::TickComponent(
    float DeltaTime,
    ELevelTick TickType,
    FActorComponentTickFunction* ThisTickFunction)
{
    Super::TickComponent(DeltaTime, TickType, ThisTickFunction);
    if (bWritten || DeltaTime < 0.0f || !FMath::IsFinite(DeltaTime)) return;
    if (!bAttestationStarted)
    {
        PendingAttestationSeconds += DeltaTime;
        FString Failure;
        if (!TryBeginAttestation(Failure))
        {
            if (PendingAttestationSeconds >= 15.0)
            {
                UE_LOG(LogTemp, Error,
                    TEXT("Looking Glass benchmark rejected before sampling: %s"), *Failure);
                SetComponentTickEnabled(false);
            }
            return;
        }
        bAttestationStarted = true;
        if (UGameInstance* GameInstance = GetWorld() ? GetWorld()->GetGameInstance() : nullptr)
        {
            if (UGahyeonRuntimeSubsystem* Runtime =
                GameInstance->GetSubsystem<UGahyeonRuntimeSubsystem>())
            {
                Runtime->ResetLookingGlassAcceptanceLatencySamples();
            }
        }
        return;
    }
    ElapsedSeconds += DeltaTime;
    if (FrameMilliseconds.Num() < 200000) FrameMilliseconds.Add(DeltaTime * 1000.0);
    if (ElapsedSeconds >= DurationSeconds)
    {
        bWritten = WriteFragment();
        if (AttestationProvider != nullptr)
        {
            AttestationProvider->EndAttestedCapture(MeasurementRunId);
            AttestationProvider = nullptr;
        }
        if (!bWritten)
        {
            UE_LOG(LogTemp, Error,
                TEXT("Looking Glass benchmark produced no fragment because attestation failed"));
        }
        SetComponentTickEnabled(false);
    }
}

void UGahyeonLookingGlassBenchmarkComponent::EndPlay(
    const EEndPlayReason::Type EndPlayReason)
{
    if (AttestationProvider != nullptr && bAttestationStarted)
    {
        AttestationProvider->EndAttestedCapture(MeasurementRunId);
        AttestationProvider = nullptr;
    }
    Super::EndPlay(EndPlayReason);
}

bool UGahyeonLookingGlassBenchmarkComponent::TryBeginAttestation(FString& OutFailure)
{
    if (AttestationProvider == nullptr)
    {
        OutFailure = TEXT("GahyeonLookingGlassAdapter is unavailable");
        return false;
    }
    FGahyeonLookingGlassCaptureRequest Request;
    Request.MeasurementRunId = MeasurementRunId;
    Request.Mode = Mode;
    Request.Views = Views;
    Request.QuiltWidth = QuiltWidth;
    Request.QuiltHeight = QuiltHeight;
    return AttestationProvider->BeginAttestedCapture(Request, OutFailure);
}

bool UGahyeonLookingGlassBenchmarkComponent::WriteFragment()
{
    if (AttestationProvider == nullptr) return false;
    FGahyeonLookingGlassRuntimeAttestation Attestation;
    FString AttestationFailure;
    if (!AttestationProvider->ReadAttestation(
            MeasurementRunId, Attestation, AttestationFailure)
        || !Attestation.IsPhysicalPresentationReady()
        || Attestation.Mode != Mode || Attestation.Views != Views
        || Attestation.QuiltWidth != QuiltWidth || Attestation.QuiltHeight != QuiltHeight)
    {
        UE_LOG(LogTemp, Error, TEXT("Looking Glass physical attestation failed: %s"),
            *AttestationFailure);
        return false;
    }
    UGameInstance* GameInstance = GetWorld() ? GetWorld()->GetGameInstance() : nullptr;
    UGahyeonRuntimeSubsystem* Runtime = GameInstance
        ? GameInstance->GetSubsystem<UGahyeonRuntimeSubsystem>() : nullptr;
    if (Runtime == nullptr) return false;
    TArray<int64> Vad;
    TArray<int64> Barge;
    TArray<int64> Viseme;
    Runtime->CopyLookingGlassAcceptanceLatencySamples(Vad, Barge, Viseme);

    const FString Directory = FPaths::Combine(
        FPaths::ProjectSavedDir(), TEXT("GahyeonBenchmarks"), MeasurementRunId);
    IPlatformFile& Files = FPlatformFileManager::Get().GetPlatformFile();
    if (!Files.CreateDirectoryTree(*Directory)) return false;
    const FString FragmentName = ProfileId + TEXT("--") + Scenario;
    const FString Path = FPaths::Combine(Directory, FragmentName + TEXT(".json"));
    const FString CaptureName = FragmentName + TEXT("--quilt.png");
    const FString CapturePath = FPaths::Combine(Directory, CaptureName);
    if (Files.FileExists(*Path) || Files.FileExists(*CapturePath)
        || !Files.CopyFile(*CapturePath, *Attestation.CaptureArtifactPath)) return false;
    int64 CaptureBytes = 0;
    FString CaptureSha256;
    if (!HashFile(CapturePath, CaptureBytes, CaptureSha256))
    {
        Files.DeleteFile(*CapturePath);
        return false;
    }

    TSharedRef<FJsonObject> CaptureEvidence = MakeShared<FJsonObject>();
    CaptureEvidence->SetStringField(TEXT("uri"), CaptureName);
    CaptureEvidence->SetNumberField(TEXT("bytes"), CaptureBytes);
    CaptureEvidence->SetStringField(TEXT("sha256"), CaptureSha256);
    TSharedRef<FJsonObject> PresentationAttestation = MakeShared<FJsonObject>();
    PresentationAttestation->SetNumberField(TEXT("schemaVersion"), Attestation.SchemaVersion);
    PresentationAttestation->SetStringField(TEXT("source"), Attestation.Source);
    PresentationAttestation->SetStringField(TEXT("pluginRelease"), Attestation.PluginRelease);
    PresentationAttestation->SetStringField(TEXT("runtimeModule"), Attestation.RuntimeModule);
    PresentationAttestation->SetStringField(TEXT("measurementRunId"), Attestation.MeasurementRunId);
    PresentationAttestation->SetStringField(TEXT("mode"), Attestation.Mode);
    PresentationAttestation->SetStringField(TEXT("deviceClass"), Attestation.DeviceClass);
    PresentationAttestation->SetNumberField(TEXT("views"), Attestation.Views);
    PresentationAttestation->SetNumberField(TEXT("quiltWidth"), Attestation.QuiltWidth);
    PresentationAttestation->SetNumberField(TEXT("quiltHeight"), Attestation.QuiltHeight);
    PresentationAttestation->SetBoolField(TEXT("playerActive"), Attestation.bPlayerActive);
    PresentationAttestation->SetBoolField(
        TEXT("physicalDeviceActive"), Attestation.bPhysicalDeviceActive);
    PresentationAttestation->SetBoolField(
        TEXT("captureComponentActive"), Attestation.bCaptureComponentActive);
    PresentationAttestation->SetBoolField(
        TEXT("quiltFrameObserved"), Attestation.bQuiltFrameObserved);
    PresentationAttestation->SetObjectField(TEXT("captureEvidence"), CaptureEvidence);

    TSharedRef<FJsonObject> Root = MakeShared<FJsonObject>();
    Root->SetNumberField(TEXT("schemaVersion"), 2);
    Root->SetStringField(TEXT("latencyBoundary"), TEXT("physical-presentation-v1"));
    Root->SetStringField(TEXT("attestationPolicy"), TEXT("runtime-quilt-capture-v1"));
    Root->SetStringField(TEXT("measurementRunId"), MeasurementRunId);
    Root->SetStringField(TEXT("id"), ProfileId);
    Root->SetStringField(TEXT("mode"), Mode);
    Root->SetStringField(TEXT("name"), Scenario);
    Root->SetNumberField(TEXT("views"), Views);
    Root->SetNumberField(TEXT("quiltWidth"), QuiltWidth);
    Root->SetNumberField(TEXT("quiltHeight"), QuiltHeight);
    Root->SetNumberField(TEXT("durationSeconds"), ElapsedSeconds);
    TArray<TSharedPtr<FJsonValue>> Frames;
    Frames.Reserve(FrameMilliseconds.Num());
    for (const double Value : FrameMilliseconds) Frames.Add(MakeShared<FJsonValueNumber>(Value));
    Root->SetArrayField(TEXT("frameMs"), Frames);
    Root->SetArrayField(TEXT("vadToListeningMs"), Numbers(Vad));
    Root->SetArrayField(TEXT("bargeInToAudioStopMs"), Numbers(Barge));
    Root->SetArrayField(TEXT("audioToVisemeMs"), Numbers(Viseme));
    Root->SetObjectField(TEXT("presentationAttestation"), PresentationAttestation);

    FString Json;
    const TSharedRef<TJsonWriter<>> Writer = TJsonWriterFactory<>::Create(&Json);
    if (!FJsonSerializer::Serialize(Root, Writer))
    {
        Files.DeleteFile(*CapturePath);
        return false;
    }
    const FString Temporary = Path + TEXT(".tmp");
    if (Files.FileExists(*Temporary) || !FFileHelper::SaveStringToFile(
        Json, *Temporary, FFileHelper::EEncodingOptions::ForceUTF8WithoutBOM))
    {
        Files.DeleteFile(*CapturePath);
        return false;
    }
    if (!Files.MoveFile(*Path, *Temporary))
    {
        Files.DeleteFile(*Temporary);
        Files.DeleteFile(*CapturePath);
        return false;
    }
    return true;
}
