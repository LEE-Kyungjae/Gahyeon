#pragma once

#include "CoreMinimal.h"
#include "GahyeonProtocolEnvelope.generated.h"

/** Validated, renderer-neutral event normalized by the transport callback. */
USTRUCT(BlueprintType)
struct GAHYEONSTAGE_API FGahyeonProtocolEnvelope
{
    GENERATED_BODY()

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Protocol")
    FString Protocol;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Protocol")
    FString Type;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Protocol")
    int32 ProtocolVersion = 1;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Protocol")
    int64 Sequence = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Protocol")
    FString MessageId;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Protocol")
    FString SentAt;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Protocol")
    FString SessionId;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Protocol")
    FString CorrelationId;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Protocol")
    FString Delivery;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Protocol")
    FString PayloadJson;
};
