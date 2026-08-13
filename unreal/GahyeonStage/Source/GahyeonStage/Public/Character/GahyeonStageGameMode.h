#pragma once

#include "GameFramework/GameModeBase.h"
#include "UObject/SoftObjectPath.h"
#include "GahyeonStageGameMode.generated.h"

class AGahyeonCharacterPawn;

/** Minimal source-only entry point; avatar/runtime details remain replaceable components. */
UCLASS()
class GAHYEONSTAGE_API AGahyeonStageGameMode final : public AGameModeBase
{
    GENERATED_BODY()

public:
    AGahyeonStageGameMode();
    virtual void StartPlay() override;

    /** Loads only subclasses of the source shell so Behavior/Presentation component contracts survive. */
    static TSubclassOf<AGahyeonCharacterPawn> ResolveHeroPawnClass(
        const FSoftClassPath& HeroClassPath,
        FString& OutError);
    static bool ValidateHeroRuntimeContract(
        TSubclassOf<AGahyeonCharacterPawn> HeroClass,
        FString& OutError);
};
