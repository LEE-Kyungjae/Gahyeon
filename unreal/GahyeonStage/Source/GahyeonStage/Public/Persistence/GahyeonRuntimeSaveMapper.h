#pragma once

#include "CoreMinimal.h"
#include "Gahyeon/ClientRuntimeSaveState.h"
#include "Persistence/GahyeonRuntimeSaveGame.h"

/** Lossless UE SaveGame mapping for the engine-neutral RuntimeCore codec. */
class GAHYEONSTAGE_API FGahyeonRuntimeSaveMapper final
{
public:
    static bool ToRuntime(
        const UGahyeonRuntimeSaveGame& Source,
        Gahyeon::ClientRuntimeSaveState& OutState,
        FString& OutError);

    static void ToSaveGame(
        const Gahyeon::ClientRuntimeSaveState& Source,
        UGahyeonRuntimeSaveGame& OutState);
};
