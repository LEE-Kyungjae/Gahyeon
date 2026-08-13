#pragma once

#include "Engine/DeveloperSettings.h"
#include "UObject/SoftObjectPath.h"
#include "GahyeonHeroRuntimeSettings.generated.h"

/** Project-level soft boundary between the source runtime and an installed G5 Hero pawn. */
UCLASS(Config=Game, DefaultConfig, meta=(DisplayName="Gahyeon Hero Runtime"))
class GAHYEONSTAGE_API UGahyeonHeroRuntimeSettings final : public UDeveloperSettings
{
    GENERATED_BODY()

public:
    /** Generated Blueprint class, e.g. /Game/GahyeonGenerated/Characters/BP_Gahyeon.BP_Gahyeon_C. */
    UPROPERTY(Config, EditAnywhere, Category="Hero")
    FSoftClassPath HeroPawnClass;

    /** Production builds may fail at startup instead of silently rendering the source shell. */
    UPROPERTY(Config, EditAnywhere, Category="Hero")
    bool bRequireHeroAsset = false;
};
