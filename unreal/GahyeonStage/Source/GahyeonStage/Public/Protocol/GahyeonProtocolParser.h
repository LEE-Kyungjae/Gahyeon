#pragma once

#include "CoreMinimal.h"
#include "Protocol/GahyeonProtocolEnvelope.h"

/** Thread-safe JSON envelope normalization. Does not touch UObject state. */
class GAHYEONSTAGE_API FGahyeonProtocolParser final
{
public:
    static bool ParseInbound(
        const FString& Json,
        FGahyeonProtocolEnvelope& OutEnvelope,
        FString& OutError);
};
