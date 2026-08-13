#include "Debug/GahyeonRealtimeBenchmarkComponent.h"

#include "Dom/JsonObject.h"
#include "Engine/GameInstance.h"
#include "HAL/PlatformFileManager.h"
#include "HAL/PlatformMisc.h"
#include "Misc/CommandLine.h"
#include "Misc/FileHelper.h"
#include "Misc/Parse.h"
#include "Misc/Paths.h"
#include "Runtime/GahyeonRuntimeSubsystem.h"
#include "Serialization/JsonSerializer.h"
#include "Serialization/JsonWriter.h"

namespace
{
bool SafeRunId(const FString& Value)
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
}

UGahyeonRealtimeBenchmarkComponent::UGahyeonRealtimeBenchmarkComponent()
{
    PrimaryComponentTick.bCanEverTick = true;
    PrimaryComponentTick.bStartWithTickEnabled = false;
}

void UGahyeonRealtimeBenchmarkComponent::BeginPlay()
{
    Super::BeginPlay();
    FString Duration;
    const bool bConfigured = FParse::Value(
        FCommandLine::Get(), TEXT("GahyeonRtRunId="), MeasurementRunId)
        && FParse::Value(FCommandLine::Get(), TEXT("GahyeonRtDuration="), Duration);
    FParse::Value(FCommandLine::Get(), TEXT("GahyeonRtOutput="), ExplicitOutputPath);
    DurationSeconds = FCString::Atod(*Duration);
    bExitWhenWritten = FParse::Param(FCommandLine::Get(), TEXT("GahyeonRtExit"));
    if (!bConfigured || !SafeRunId(MeasurementRunId) || DurationSeconds < 600.0
        || DurationSeconds > 3'600.0)
    {
        SetComponentTickEnabled(false);
        return;
    }
    UGameInstance* GameInstance = GetWorld() ? GetWorld()->GetGameInstance() : nullptr;
    UGahyeonRuntimeSubsystem* Runtime = GameInstance
        ? GameInstance->GetSubsystem<UGahyeonRuntimeSubsystem>() : nullptr;
    if (Runtime == nullptr)
    {
        SetComponentTickEnabled(false);
        return;
    }
    const FGahyeonRuntimeFrameSnapshot Frame = Runtime->GetSnapshot();
    InitialReflexUpdates = LastReflexUpdates = Frame.ReflexUpdates;
    InitialBehaviorUpdates = LastBehaviorUpdates = Frame.BehaviorUpdates;
    FrameMilliseconds.Reserve(FMath::Min(900000, FMath::CeilToInt(DurationSeconds * 240.0)));
    Runtime->ResetLookingGlassAcceptanceLatencySamples();
    SetComponentTickEnabled(true);
}

void UGahyeonRealtimeBenchmarkComponent::TickComponent(
    float DeltaTime,
    ELevelTick TickType,
    FActorComponentTickFunction* ThisTickFunction)
{
    Super::TickComponent(DeltaTime, TickType, ThisTickFunction);
    if (bWritten || DeltaTime < 0.0f || !FMath::IsFinite(DeltaTime)) return;
    ElapsedSeconds += DeltaTime;
    if (FrameMilliseconds.Num() < 900000) FrameMilliseconds.Add(DeltaTime * 1000.0);
    UGameInstance* GameInstance = GetWorld() ? GetWorld()->GetGameInstance() : nullptr;
    UGahyeonRuntimeSubsystem* Runtime = GameInstance
        ? GameInstance->GetSubsystem<UGahyeonRuntimeSubsystem>() : nullptr;
    if (Runtime == nullptr) return;
    const FGahyeonRuntimeFrameSnapshot Frame = Runtime->GetSnapshot();
    if (Frame.ReflexUpdates != LastReflexUpdates)
    {
        MaxReflexGapSeconds = FMath::Max(
            MaxReflexGapSeconds, ElapsedSeconds - LastReflexAdvanceSeconds);
        LastReflexAdvanceSeconds = ElapsedSeconds;
        LastReflexUpdates = Frame.ReflexUpdates;
    }
    if (Frame.BehaviorUpdates != LastBehaviorUpdates)
    {
        MaxBehaviorGapSeconds = FMath::Max(
            MaxBehaviorGapSeconds, ElapsedSeconds - LastBehaviorAdvanceSeconds);
        LastBehaviorAdvanceSeconds = ElapsedSeconds;
        LastBehaviorUpdates = Frame.BehaviorUpdates;
    }
    if (ElapsedSeconds >= DurationSeconds)
    {
        MaxReflexGapSeconds = FMath::Max(
            MaxReflexGapSeconds, ElapsedSeconds - LastReflexAdvanceSeconds);
        MaxBehaviorGapSeconds = FMath::Max(
            MaxBehaviorGapSeconds, ElapsedSeconds - LastBehaviorAdvanceSeconds);
        bWritten = WriteResult();
        SetComponentTickEnabled(false);
        if (bWritten && bExitWhenWritten) FPlatformMisc::RequestExit(false);
    }
}

bool UGahyeonRealtimeBenchmarkComponent::WriteResult()
{
    UGameInstance* GameInstance = GetWorld() ? GetWorld()->GetGameInstance() : nullptr;
    UGahyeonRuntimeSubsystem* Runtime = GameInstance
        ? GameInstance->GetSubsystem<UGahyeonRuntimeSubsystem>() : nullptr;
    if (Runtime == nullptr) return false;
    TArray<int64> Vad;
    TArray<int64> Barge;
    TArray<int64> Viseme;
    Runtime->CopyLookingGlassAcceptanceLatencySamples(Vad, Barge, Viseme);
    const FGahyeonRuntimeFrameSnapshot Frame = Runtime->GetSnapshot();

    TSharedRef<FJsonObject> Root = MakeShared<FJsonObject>();
    Root->SetNumberField(TEXT("schemaVersion"), 1);
    Root->SetStringField(TEXT("measurementRunId"), MeasurementRunId);
    Root->SetStringField(TEXT("renderer"), TEXT("desktop-single-view"));
    Root->SetStringField(TEXT("latencyBoundary"), TEXT("physical-presentation-v1"));
    Root->SetNumberField(TEXT("durationSeconds"), ElapsedSeconds);
    Root->SetNumberField(TEXT("reflexUpdates"), Frame.ReflexUpdates - InitialReflexUpdates);
    Root->SetNumberField(TEXT("behaviorUpdates"), Frame.BehaviorUpdates - InitialBehaviorUpdates);
    Root->SetNumberField(TEXT("maxReflexGapMs"), MaxReflexGapSeconds * 1000.0);
    Root->SetNumberField(TEXT("maxBehaviorGapMs"), MaxBehaviorGapSeconds * 1000.0);
    Root->SetStringField(TEXT("os"), FPlatformMisc::GetOSVersion());
    Root->SetStringField(TEXT("gpu"), FPlatformMisc::GetPrimaryGPUBrand());
    TArray<TSharedPtr<FJsonValue>> Frames;
    Frames.Reserve(FrameMilliseconds.Num());
    for (const double Value : FrameMilliseconds) Frames.Add(MakeShared<FJsonValueNumber>(Value));
    Root->SetArrayField(TEXT("frameMs"), Frames);
    Root->SetArrayField(TEXT("vadToListeningMs"), Numbers(Vad));
    Root->SetArrayField(TEXT("bargeInToAudioStopMs"), Numbers(Barge));
    Root->SetArrayField(TEXT("audioToVisemeMs"), Numbers(Viseme));

    FString Json;
    const TSharedRef<TJsonWriter<>> Writer = TJsonWriterFactory<>::Create(&Json);
    if (!FJsonSerializer::Serialize(Root, Writer)) return false;
    const FString DefaultDirectory = FPaths::Combine(
        FPaths::ProjectSavedDir(), TEXT("GahyeonBenchmarks"), MeasurementRunId);
    const FString Path = ExplicitOutputPath.IsEmpty()
        ? FPaths::Combine(DefaultDirectory, TEXT("desktop-realtime.json"))
        : FPaths::ConvertRelativePathToFull(ExplicitOutputPath);
    if (!Path.EndsWith(TEXT(".json"), ESearchCase::IgnoreCase)) return false;
    const FString Directory = FPaths::GetPath(Path);
    IPlatformFile& Files = FPlatformFileManager::Get().GetPlatformFile();
    if (!Files.CreateDirectoryTree(*Directory)) return false;
    if (Files.FileExists(*Path)) return false;
    const FString Temporary = Path + TEXT(".tmp");
    if (!FFileHelper::SaveStringToFile(
        Json, *Temporary, FFileHelper::EEncodingOptions::ForceUTF8WithoutBOM)) return false;
    if (!Files.MoveFile(*Path, *Temporary))
    {
        Files.DeleteFile(*Temporary);
        return false;
    }
    return true;
}
