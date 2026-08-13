#pragma once

#include "GameFramework/Actor.h"
#include "GahyeonPresentationHost.generated.h"

class UGahyeonSpeechAudioComponent;
class UGahyeonLookingGlassBenchmarkComponent;
class UGahyeonRealtimeBenchmarkComponent;
class USceneComponent;

/** Transient, non-visual owner for presentation services that must exist before an avatar does. */
UCLASS(NotBlueprintable, Transient)
class GAHYEONSTAGE_API AGahyeonPresentationHost final : public AActor
{
    GENERATED_BODY()

public:
    AGahyeonPresentationHost();

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Presentation")
    UGahyeonSpeechAudioComponent* GetSpeechAudio() const { return SpeechAudio; }

private:
    UPROPERTY(VisibleAnywhere, Category = "Gahyeon|Presentation")
    TObjectPtr<USceneComponent> SceneRoot;

    UPROPERTY(VisibleAnywhere, Category = "Gahyeon|Presentation")
    TObjectPtr<UGahyeonSpeechAudioComponent> SpeechAudio;

    UPROPERTY(VisibleAnywhere, Category = "Gahyeon|Debug")
    TObjectPtr<UGahyeonLookingGlassBenchmarkComponent> LookingGlassBenchmark;

    UPROPERTY(VisibleAnywhere, Category = "Gahyeon|Debug")
    TObjectPtr<UGahyeonRealtimeBenchmarkComponent> RealtimeBenchmark;
};
