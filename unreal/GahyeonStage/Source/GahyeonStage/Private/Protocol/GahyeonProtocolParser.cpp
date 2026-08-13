#include "Protocol/GahyeonProtocolParser.h"

#include "Dom/JsonObject.h"
#include "Misc/DateTime.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"
#include "Serialization/JsonWriter.h"

namespace
{
constexpr TCHAR ExpectedProtocol[] = TEXT("gahyeon.unreal.v1");
constexpr double MaximumSafeJsonInteger = 9007199254740991.0;

bool ReadRequiredString(
    const TSharedPtr<FJsonObject>& Object,
    const TCHAR* Field,
    FString& Out,
    FString& Error)
{
    if (!Object->TryGetStringField(Field, Out) || Out.IsEmpty())
    {
        Error = FString::Printf(TEXT("missing or empty field: %s"), Field);
        return false;
    }
    return true;
}

bool IsAllowedTopLevelField(const FString& Field)
{
    static const TSet<FString> Allowed = {
        TEXT("protocol"), TEXT("schemaVersion"), TEXT("messageId"), TEXT("type"),
        TEXT("sentAt"), TEXT("sessionId"), TEXT("correlationId"), TEXT("delivery"),
        TEXT("sequence"), TEXT("payload")};
    return Allowed.Contains(Field);
}
}

bool FGahyeonProtocolParser::ParseInbound(
    const FString& Json,
    FGahyeonProtocolEnvelope& OutEnvelope,
    FString& OutError)
{
    OutEnvelope = {};
    OutError.Reset();

    TSharedPtr<FJsonObject> Root;
    const TSharedRef<TJsonReader<>> Reader = TJsonReaderFactory<>::Create(Json);
    if (!FJsonSerializer::Deserialize(Reader, Root) || !Root.IsValid())
    {
        OutError = TEXT("invalid JSON object");
        return false;
    }

    for (const TPair<FString, TSharedPtr<FJsonValue>>& Entry : Root->Values)
    {
        if (!IsAllowedTopLevelField(Entry.Key))
        {
            OutError = FString::Printf(TEXT("unknown top-level field: %s"), *Entry.Key);
            return false;
        }
    }

    if (!ReadRequiredString(Root, TEXT("protocol"), OutEnvelope.Protocol, OutError)
        || OutEnvelope.Protocol != ExpectedProtocol)
    {
        if (OutError.IsEmpty())
        {
            OutError = TEXT("unsupported protocol");
        }
        return false;
    }

    double SchemaVersion = 0.0;
    if (!Root->TryGetNumberField(TEXT("schemaVersion"), SchemaVersion)
        || SchemaVersion != 1.0)
    {
        OutError = TEXT("schemaVersion must be 1");
        return false;
    }
    OutEnvelope.ProtocolVersion = 1;

    if (!ReadRequiredString(Root, TEXT("messageId"), OutEnvelope.MessageId, OutError)
        || !ReadRequiredString(Root, TEXT("type"), OutEnvelope.Type, OutError)
        || !ReadRequiredString(Root, TEXT("sentAt"), OutEnvelope.SentAt, OutError)
        || !ReadRequiredString(Root, TEXT("correlationId"), OutEnvelope.CorrelationId, OutError)
        || !ReadRequiredString(Root, TEXT("delivery"), OutEnvelope.Delivery, OutError))
    {
        return false;
    }

    FDateTime ParsedSentAt;
    if (!FDateTime::ParseIso8601(*OutEnvelope.SentAt, ParsedSentAt))
    {
        OutError = TEXT("sentAt must be ISO-8601");
        return false;
    }

    Root->TryGetStringField(TEXT("sessionId"), OutEnvelope.SessionId);
    if (Root->HasField(TEXT("sessionId")) && OutEnvelope.SessionId.IsEmpty())
    {
        OutError = TEXT("sessionId must be non-empty when present");
        return false;
    }

    const bool bDurable = OutEnvelope.Delivery == TEXT("durable");
    if (!bDurable
        && OutEnvelope.Delivery != TEXT("command")
        && OutEnvelope.Delivery != TEXT("ephemeral"))
    {
        OutError = TEXT("invalid delivery");
        return false;
    }

    const bool bHasSequence = Root->HasField(TEXT("sequence"));
    if (bDurable != bHasSequence)
    {
        OutError = bDurable
            ? TEXT("durable event requires sequence")
            : TEXT("non-durable event cannot contain sequence");
        return false;
    }
    if (bHasSequence)
    {
        double Sequence = 0.0;
        if (!Root->TryGetNumberField(TEXT("sequence"), Sequence)
            || Sequence < 1.0
            || Sequence != FMath::FloorToDouble(Sequence)
            || Sequence > MaximumSafeJsonInteger)
        {
            OutError = TEXT("sequence must be a positive integer");
            return false;
        }
        OutEnvelope.Sequence = static_cast<int64>(Sequence);
    }

    const TSharedPtr<FJsonObject>* Payload = nullptr;
    if (!Root->TryGetObjectField(TEXT("payload"), Payload) || Payload == nullptr)
    {
        OutError = TEXT("payload must be an object");
        return false;
    }

    const TSharedRef<TJsonWriter<>> Writer =
        TJsonWriterFactory<>::Create(&OutEnvelope.PayloadJson);
    if (!FJsonSerializer::Serialize(Payload->ToSharedRef(), Writer))
    {
        OutError = TEXT("failed to normalize payload");
        return false;
    }
    return true;
}
