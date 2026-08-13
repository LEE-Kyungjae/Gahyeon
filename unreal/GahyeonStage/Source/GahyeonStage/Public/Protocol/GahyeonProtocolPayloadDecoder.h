#pragma once

#include "CoreMinimal.h"
#include "Gahyeon/ProtocolMessageTranslator.h"
#include "Protocol/GahyeonProtocolEnvelope.h"

enum class EGahyeonPayloadDecodeStatus : uint8
{
    Decoded,
    Unsupported,
    Invalid
};

/** Strict wire-payload decoder into engine-independent RuntimeCore messages. */
class GAHYEONSTAGE_API FGahyeonProtocolPayloadDecoder final
{
public:
    static EGahyeonPayloadDecodeStatus Decode(
        const FGahyeonProtocolEnvelope& Envelope,
        Gahyeon::ProtocolMessage& OutMessage,
        FString& OutError);
};
