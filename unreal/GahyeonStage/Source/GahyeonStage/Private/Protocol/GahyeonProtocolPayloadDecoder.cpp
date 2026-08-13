#include "Protocol/GahyeonProtocolPayloadDecoder.h"

#include "Dom/JsonObject.h"
#include "Misc/DateTime.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"

#include <cfloat>
#include <initializer_list>

namespace
{
constexpr double MaximumSafeJsonInteger = 9007199254740991.0;

std::string ToUtf8(const FString& Value)
{
    return std::string(TCHAR_TO_UTF8(*Value));
}

bool AllowedFields(
    const TSharedPtr<FJsonObject>& Object,
    std::initializer_list<const TCHAR*> Allowed,
    FString& Error)
{
    TSet<FString> Names;
    for (const TCHAR* Name : Allowed)
    {
        Names.Add(Name);
    }
    for (const TPair<FString, TSharedPtr<FJsonValue>>& Entry : Object->Values)
    {
        if (!Names.Contains(Entry.Key))
        {
            Error = TEXT("unknown payload field: ") + Entry.Key;
            return false;
        }
    }
    return true;
}

bool RequiredString(
    const TSharedPtr<FJsonObject>& Object,
    const TCHAR* Name,
    FString& Out,
    FString& Error)
{
    if (!Object->TryGetStringField(Name, Out) || Out.IsEmpty())
    {
        Error = FString::Printf(TEXT("missing or empty payload field: %s"), Name);
        return false;
    }
    return true;
}

bool OptionalString(
    const TSharedPtr<FJsonObject>& Object,
    const TCHAR* Name,
    TOptional<FString>& Out,
    FString& Error)
{
    if (!Object->HasField(Name))
    {
        Out.Reset();
        return true;
    }
    FString Value;
    if (!RequiredString(Object, Name, Value, Error))
    {
        return false;
    }
    Out = MoveTemp(Value);
    return true;
}

bool Integer(
    const TSharedPtr<FJsonObject>& Object,
    const TCHAR* Name,
    double Minimum,
    double Maximum,
    int64& Out,
    FString& Error,
    bool bRequired = true)
{
    if (!Object->HasField(Name) && !bRequired)
    {
        return true;
    }
    double Value = 0.0;
    if (!Object->TryGetNumberField(Name, Value)
        || !FMath::IsFinite(Value)
        || Value != FMath::FloorToDouble(Value)
        || Value < Minimum
        || Value > FMath::Min(Maximum, MaximumSafeJsonInteger))
    {
        Error = FString::Printf(TEXT("invalid integer payload field: %s"), Name);
        return false;
    }
    Out = static_cast<int64>(Value);
    return true;
}

bool Number(
    const TSharedPtr<FJsonObject>& Object,
    const TCHAR* Name,
    double Minimum,
    double Maximum,
    double& Out,
    FString& Error,
    bool bRequired = true)
{
    if (!Object->HasField(Name) && !bRequired)
    {
        return true;
    }
    if (!Object->TryGetNumberField(Name, Out)
        || !FMath::IsFinite(Out)
        || Out < Minimum
        || Out > Maximum)
    {
        Error = FString::Printf(TEXT("invalid numeric payload field: %s"), Name);
        return false;
    }
    return true;
}

bool Position(
    const TSharedPtr<FJsonObject>& Object,
    const TCHAR* Name,
    Gahyeon::WorldPosition& Out,
    FString& Error)
{
    const TSharedPtr<FJsonObject>* PositionObject = nullptr;
    if (!Object->TryGetObjectField(Name, PositionObject)
        || PositionObject == nullptr
        || !AllowedFields(*PositionObject, {TEXT("x"), TEXT("y"), TEXT("z")}, Error)
        || !Number(*PositionObject, TEXT("x"), -DBL_MAX, DBL_MAX, Out.X, Error)
        || !Number(*PositionObject, TEXT("y"), -DBL_MAX, DBL_MAX, Out.Y, Error)
        || !Number(*PositionObject, TEXT("z"), -DBL_MAX, DBL_MAX, Out.Z, Error))
    {
        if (Error.IsEmpty())
        {
            Error = FString::Printf(TEXT("invalid position payload field: %s"), Name);
        }
        return false;
    }
    return true;
}

bool Iso8601(const TSharedPtr<FJsonObject>& Object, const TCHAR* Name, FString& Error)
{
    FString Value;
    FDateTime Parsed;
    if (!RequiredString(Object, Name, Value, Error)
        || !FDateTime::ParseIso8601(*Value, Parsed))
    {
        Error = FString::Printf(TEXT("invalid ISO-8601 payload field: %s"), Name);
        return false;
    }
    return true;
}

bool OptionalGeneration(
    const TSharedPtr<FJsonObject>& Object,
    Gahyeon::ProtocolMessage& Message,
    FString& Error)
{
    if (!Object->HasField(TEXT("generation")))
    {
        return true;
    }
    int64 Generation = 0;
    if (!Integer(Object, TEXT("generation"), 0.0, MaximumSafeJsonInteger,
        Generation, Error))
    {
        return false;
    }
    Message.GenerationId = static_cast<Gahyeon::Generation>(Generation);
    return true;
}

bool DecodeCharacterState(
    const TSharedPtr<FJsonObject>& Payload,
    Gahyeon::ProtocolMessage& Message,
    FString& Error)
{
    if (!AllowedFields(Payload, {
        TEXT("generation"), TEXT("state"), TEXT("priority"),
        TEXT("expiresAfterMs")}, Error)
        || !OptionalGeneration(Payload, Message, Error)
        || !Message.GenerationId.has_value())
    {
        if (Error.IsEmpty()) Error = TEXT("character state requires generation");
        return false;
    }
    FString State;
    int64 Priority = 0;
    int64 Expires = 0;
    if (!RequiredString(Payload, TEXT("state"), State, Error)
        || (State != TEXT("idle") && State != TEXT("listening")
            && State != TEXT("thinking") && State != TEXT("speaking")
            && State != TEXT("reacting") && State != TEXT("executing_action"))
        || !Integer(Payload, TEXT("priority"), INT32_MIN, INT32_MAX,
            Priority, Error, false)
        || !Integer(Payload, TEXT("expiresAfterMs"), 1.0, INT32_MAX,
            Expires, Error, false))
    {
        if (Error.IsEmpty()) Error = TEXT("invalid character state target");
        return false;
    }
    Message.Semantic = ToUtf8(State);
    Message.Priority = static_cast<int>(Priority);
    if (Payload->HasField(TEXT("expiresAfterMs"))) Message.ExpiresAfterMs = Expires;
    return true;
}

bool DecodeGenerationAdvanced(
    const TSharedPtr<FJsonObject>& Payload,
    Gahyeon::ProtocolMessage& Message,
    FString& Error)
{
    if (!AllowedFields(Payload, {TEXT("generation"), TEXT("reason")}, Error)
        || !OptionalGeneration(Payload, Message, Error)
        || !Message.GenerationId.has_value())
    {
        return false;
    }
    FString Reason;
    if (!RequiredString(Payload, TEXT("reason"), Reason, Error)
        || (Reason != TEXT("cognition_timeout") && Reason != TEXT("client_reset")))
    {
        Error = TEXT("invalid generation advance reason");
        return false;
    }
    Message.Outcome = ToUtf8(Reason);
    return true;
}

bool DecodeSpeechPrepared(
    const TSharedPtr<FJsonObject>& Payload,
    Gahyeon::ProtocolMessage& Message,
    FString& Error)
{
    if (!AllowedFields(Payload, {
        TEXT("generation"), TEXT("utteranceId"), TEXT("utteranceIndex"),
        TEXT("segmentIndex"), TEXT("segmentCount"), TEXT("finalSegment"),
        TEXT("audio"), TEXT("visemes")}, Error)
        || !OptionalGeneration(Payload, Message, Error)
        || !Message.GenerationId.has_value())
    {
        return false;
    }
    FString UtteranceId;
    int64 UtteranceIndex = 0;
    int64 SegmentIndex = 0;
    int64 SegmentCount = 0;
    bool bFinalSegment = false;
    const TSharedPtr<FJsonObject>* Audio = nullptr;
    FString AudioUrl;
    FString MimeType;
    const TArray<TSharedPtr<FJsonValue>>* Visemes = nullptr;
    if (!RequiredString(Payload, TEXT("utteranceId"), UtteranceId, Error)
        || !Integer(Payload, TEXT("utteranceIndex"), 0.0, INT32_MAX,
            UtteranceIndex, Error)
        || !Integer(Payload, TEXT("segmentIndex"), 0.0, INT32_MAX,
            SegmentIndex, Error)
        || !Integer(Payload, TEXT("segmentCount"), 1.0, INT32_MAX,
            SegmentCount, Error)
        || SegmentIndex >= SegmentCount
        || !Payload->TryGetBoolField(TEXT("finalSegment"), bFinalSegment)
        || !Payload->TryGetObjectField(TEXT("audio"), Audio) || Audio == nullptr
        || !AllowedFields(*Audio, {TEXT("url"), TEXT("mimeType")}, Error)
        || !RequiredString(*Audio, TEXT("url"), AudioUrl, Error)
        || !RequiredString(*Audio, TEXT("mimeType"), MimeType, Error)
        || !Payload->TryGetArrayField(TEXT("visemes"), Visemes)
        || Visemes == nullptr || Visemes->Num() > 256)
    {
        if (Error.IsEmpty()) Error = TEXT("invalid speech prepared payload");
        return false;
    }
    Gahyeon::Millis PreviousAt = -1;
    for (const TSharedPtr<FJsonValue>& Value : *Visemes)
    {
        const TSharedPtr<FJsonObject>* Cue = nullptr;
        FString Semantic;
        int64 AtMs = 0;
        int64 DurationMs = 0;
        double Weight = 0.0;
        if (!Value.IsValid() || !Value->TryGetObject(Cue) || Cue == nullptr
            || !AllowedFields(*Cue, {
                TEXT("semantic"), TEXT("atMs"), TEXT("durationMs"), TEXT("weight")}, Error)
            || !RequiredString(*Cue, TEXT("semantic"), Semantic, Error)
            || !Integer(*Cue, TEXT("atMs"), 0.0, INT32_MAX, AtMs, Error)
            || AtMs < PreviousAt
            || !Integer(*Cue, TEXT("durationMs"), 1.0, INT32_MAX, DurationMs, Error)
            || !Number(*Cue, TEXT("weight"), DBL_MIN, 1.0, Weight, Error)
            || Weight <= 0.0)
        {
            if (Error.IsEmpty()) Error = TEXT("invalid viseme cue");
            return false;
        }
        PreviousAt = AtMs;
        Message.Visemes.push_back({
            .Semantic = ToUtf8(Semantic),
            .AtMs = AtMs,
            .DurationMs = DurationMs,
            .Weight = Weight});
    }
    Message.UtteranceId = ToUtf8(UtteranceId);
    Message.UtteranceIndex = static_cast<int>(UtteranceIndex);
    Message.SegmentIndex = static_cast<int>(SegmentIndex);
    Message.FinalSegment = bFinalSegment;
    Message.AudioUrl = ToUtf8(AudioUrl);
    Message.MimeType = ToUtf8(MimeType);
    return true;
}

bool DecodeSpeechSequenceEnded(
    const TSharedPtr<FJsonObject>& Payload,
    Gahyeon::ProtocolMessage& Message,
    FString& Error)
{
    if (!AllowedFields(Payload, {
        TEXT("generation"), TEXT("utteranceCount"), TEXT("outcome")}, Error)
        || !OptionalGeneration(Payload, Message, Error)
        || !Message.GenerationId.has_value())
    {
        return false;
    }
    int64 Count = 0;
    FString Outcome;
    if (!Integer(Payload, TEXT("utteranceCount"), 0.0, INT32_MAX, Count, Error)
        || !RequiredString(Payload, TEXT("outcome"), Outcome, Error)
        || (Outcome != TEXT("completed") && Outcome != TEXT("failed")))
    {
        if (Error.IsEmpty()) Error = TEXT("invalid speech sequence outcome");
        return false;
    }
    Message.UtteranceCount = static_cast<int>(Count);
    Message.Outcome = ToUtf8(Outcome);
    return true;
}

bool DecodeGesture(
    const TSharedPtr<FJsonObject>& Payload,
    Gahyeon::ProtocolMessage& Message,
    FString& Error)
{
    if (!AllowedFields(Payload, {
        TEXT("generation"), TEXT("semantic"), TEXT("intensity"), TEXT("priority"),
        TEXT("expiresAfterMs"), TEXT("handPreference"), TEXT("durationHintMs")}, Error)
        || !OptionalGeneration(Payload, Message, Error))
    {
        return false;
    }
    FString Semantic;
    double Intensity = 0.0;
    int64 Priority = 0;
    int64 Expires = 0;
    int64 Duration = 0;
    if (!RequiredString(Payload, TEXT("semantic"), Semantic, Error)
        || !Number(Payload, TEXT("intensity"), 0.0, 1.0, Intensity, Error)
        || !Integer(Payload, TEXT("priority"), INT32_MIN, INT32_MAX, Priority, Error, false)
        || !Integer(Payload, TEXT("expiresAfterMs"), 1.0, INT32_MAX, Expires, Error, false)
        || !Integer(Payload, TEXT("durationHintMs"), 1.0, INT32_MAX, Duration, Error, false))
    {
        return false;
    }
    if (Payload->HasField(TEXT("handPreference")))
    {
        FString Hand;
        if (!RequiredString(Payload, TEXT("handPreference"), Hand, Error)
            || (Hand != TEXT("left") && Hand != TEXT("right") && Hand != TEXT("either")))
        {
            Error = TEXT("invalid hand preference");
            return false;
        }
    }
    Message.Semantic = ToUtf8(Semantic);
    Message.Intensity = Intensity;
    Message.Priority = static_cast<int>(Priority);
    if (Payload->HasField(TEXT("expiresAfterMs"))) Message.ExpiresAfterMs = Expires;
    return true;
}

bool DecodeAttention(
    const TSharedPtr<FJsonObject>& Payload,
    Gahyeon::ProtocolMessage& Message,
    FString& Error)
{
    if (!AllowedFields(Payload, {
        TEXT("generation"), TEXT("kind"), TEXT("targetId"), TEXT("priority"),
        TEXT("expiresAfterMs"), TEXT("headWeight"), TEXT("eyeWeight")}, Error)
        || !OptionalGeneration(Payload, Message, Error))
    {
        return false;
    }
    FString Kind;
    TOptional<FString> TargetId;
    int64 Priority = 0;
    int64 Expires = 0;
    double IgnoredWeight = 0.0;
    if (!RequiredString(Payload, TEXT("kind"), Kind, Error)
        || !OptionalString(Payload, TEXT("targetId"), TargetId, Error)
        || !Integer(Payload, TEXT("priority"), INT32_MIN, INT32_MAX, Priority, Error, false)
        || !Integer(Payload, TEXT("expiresAfterMs"), 1.0, INT32_MAX, Expires, Error, false)
        || !Number(Payload, TEXT("headWeight"), 0.0, 1.0, IgnoredWeight, Error, false)
        || !Number(Payload, TEXT("eyeWeight"), 0.0, 1.0, IgnoredWeight, Error, false))
    {
        return false;
    }
    Message.Semantic = ToUtf8(Kind);
    if (TargetId.IsSet()) Message.Semantic += ":" + ToUtf8(TargetId.GetValue());
    Message.Priority = static_cast<int>(Priority);
    if (Payload->HasField(TEXT("expiresAfterMs"))) Message.ExpiresAfterMs = Expires;
    return true;
}

bool DecodeCognitionEvent(
    const FString& Type,
    const TSharedPtr<FJsonObject>& Payload,
    Gahyeon::ProtocolMessage& Message,
    FString& Error)
{
    if (!OptionalGeneration(Payload, Message, Error)) return false;
    if (Type == TEXT("cognition.request.started"))
    {
        if (!AllowedFields(Payload, {
            TEXT("generation"), TEXT("source"), TEXT("modality"), TEXT("actorId")}, Error))
        {
            return false;
        }
        for (const TCHAR* Name : {TEXT("source"), TEXT("modality"), TEXT("actorId")})
        {
            if (Payload->HasField(Name))
            {
                FString Ignored;
                if (!RequiredString(Payload, Name, Ignored, Error)) return false;
            }
        }
        return true;
    }
    if (Type == TEXT("cognition.response.completed"))
    {
        if (!AllowedFields(Payload, {
            TEXT("generation"), TEXT("runId"), TEXT("content"), TEXT("tools"),
            TEXT("durationMillis")}, Error))
        {
            return false;
        }
        for (const TCHAR* Name : {TEXT("runId"), TEXT("content")})
        {
            if (Payload->HasField(Name))
            {
                FString Ignored;
                if (!Payload->TryGetStringField(Name, Ignored))
                {
                    Error = FString::Printf(TEXT("invalid cognition string field: %s"), Name);
                    return false;
                }
            }
        }
        int64 IgnoredDuration = 0;
        if (!Integer(Payload, TEXT("durationMillis"), 0.0, MaximumSafeJsonInteger,
            IgnoredDuration, Error, false))
        {
            return false;
        }
        if (Payload->HasField(TEXT("tools")))
        {
            const TArray<TSharedPtr<FJsonValue>>* Tools = nullptr;
            if (!Payload->TryGetArrayField(TEXT("tools"), Tools)
                || Tools == nullptr || Tools->Num() > 256)
            {
                Error = TEXT("invalid cognition tools field");
                return false;
            }
            for (const TSharedPtr<FJsonValue>& Tool : *Tools)
            {
                FString Name;
                if (!Tool.IsValid() || !Tool->TryGetString(Name) || Name.IsEmpty())
                {
                    Error = TEXT("invalid cognition tool name");
                    return false;
                }
            }
        }
        return true;
    }
    if (!AllowedFields(Payload, {
        TEXT("generation"), TEXT("errorType"), TEXT("message")}, Error))
    {
        return false;
    }
    for (const TCHAR* Name : {TEXT("errorType"), TEXT("message")})
    {
        if (Payload->HasField(Name))
        {
            FString Ignored;
            if (!Payload->TryGetStringField(Name, Ignored))
            {
                Error = FString::Printf(TEXT("invalid cognition failure field: %s"), Name);
                return false;
            }
        }
    }
    return true;
}

bool DecodeWorldSnapshot(
    const TSharedPtr<FJsonObject>& Payload,
    Gahyeon::ProtocolMessage& Message,
    FString& Error)
{
    if (!AllowedFields(Payload, {
        TEXT("worldId"), TEXT("revision"), TEXT("currentRoom"), TEXT("position"),
        TEXT("activity"), TEXT("activityStartedAt"), TEXT("outfit"), TEXT("worldTime"),
        TEXT("emotion"), TEXT("interactionTarget"), TEXT("updatedAt"), TEXT("capturedAt")},
        Error))
    {
        return false;
    }
    FString WorldId;
    FString Room;
    FString Activity;
    FString Outfit;
    TOptional<FString> InteractionTarget;
    int64 Revision = 0;
    Gahyeon::WorldPosition WorldPosition;
    const TSharedPtr<FJsonObject>* Emotion = nullptr;
    FString EmotionName;
    double EmotionIntensity = 0.0;
    if (!RequiredString(Payload, TEXT("worldId"), WorldId, Error)
        || !Integer(Payload, TEXT("revision"), 0.0, MaximumSafeJsonInteger,
            Revision, Error)
        || !RequiredString(Payload, TEXT("currentRoom"), Room, Error)
        || !Position(Payload, TEXT("position"), WorldPosition, Error)
        || !RequiredString(Payload, TEXT("activity"), Activity, Error)
        || !Iso8601(Payload, TEXT("activityStartedAt"), Error)
        || !RequiredString(Payload, TEXT("outfit"), Outfit, Error)
        || !Iso8601(Payload, TEXT("worldTime"), Error)
        || !Payload->TryGetObjectField(TEXT("emotion"), Emotion)
        || Emotion == nullptr
        || !AllowedFields(*Emotion, {TEXT("name"), TEXT("intensity")}, Error)
        || !RequiredString(*Emotion, TEXT("name"), EmotionName, Error)
        || !Number(*Emotion, TEXT("intensity"), 0.0, 1.0, EmotionIntensity, Error)
        || !OptionalString(Payload, TEXT("interactionTarget"), InteractionTarget, Error)
        || !Iso8601(Payload, TEXT("updatedAt"), Error)
        || !Iso8601(Payload, TEXT("capturedAt"), Error))
    {
        return false;
    }
    Message.Snapshot = Gahyeon::WorldStateSnapshot{
        .WorldId = ToUtf8(WorldId),
        .Revision = static_cast<Gahyeon::Generation>(Revision),
        .CurrentRoom = ToUtf8(Room),
        .Position = WorldPosition,
        .Activity = ToUtf8(Activity),
        .Outfit = ToUtf8(Outfit),
        .Emotion = ToUtf8(EmotionName),
        .EmotionIntensity = EmotionIntensity,
        .InteractionTarget = InteractionTarget.IsSet()
            ? std::optional<std::string>(ToUtf8(InteractionTarget.GetValue()))
            : std::nullopt};
    return true;
}

bool DecodeEmotion(
    const TSharedPtr<FJsonObject>& Payload,
    Gahyeon::ProtocolMessage& Message,
    FString& Error)
{
    if (!AllowedFields(Payload, {
        TEXT("revision"), TEXT("generation"), TEXT("dimensions"), TEXT("valence"),
        TEXT("arousal"), TEXT("dominance"), TEXT("blendSeconds"), TEXT("holdSeconds")},
        Error)
        || !OptionalGeneration(Payload, Message, Error))
    {
        return false;
    }
    int64 IgnoredRevision = 0;
    if (Payload->HasField(TEXT("revision"))
        && !Integer(Payload, TEXT("revision"), 0.0, MaximumSafeJsonInteger,
            IgnoredRevision, Error))
    {
        return false;
    }
    const TSharedPtr<FJsonObject>* Dimensions = nullptr;
    if (!Payload->TryGetObjectField(TEXT("dimensions"), Dimensions)
        || Dimensions == nullptr
        || (*Dimensions)->Values.IsEmpty()
        || (*Dimensions)->Values.Num() > 16)
    {
        Error = TEXT("dimensions must contain 1..16 entries");
        return false;
    }
    for (const TPair<FString, TSharedPtr<FJsonValue>>& Entry : (*Dimensions)->Values)
    {
        double Value = 0.0;
        if (Entry.Key.IsEmpty() || !Entry.Value->TryGetNumber(Value)
            || !FMath::IsFinite(Value) || Value < 0.0 || Value > 1.0)
        {
            Error = TEXT("invalid emotion dimension");
            return false;
        }
        Message.EmotionDimensions.emplace(ToUtf8(Entry.Key), Value);
    }
    double Seconds = 0.0;
    if (!Number(Payload, TEXT("blendSeconds"), 0.0, 5.0, Seconds, Error))
    {
        return false;
    }
    Message.BlendMs = static_cast<Gahyeon::Millis>(Seconds * 1000.0);
    const struct FOptionalSignedValue
    {
        const TCHAR* Name;
        std::optional<double>* Target;
    } SignedValues[] = {
        {TEXT("valence"), &Message.Valence},
        {TEXT("arousal"), &Message.Arousal},
        {TEXT("dominance"), &Message.Dominance}};
    for (const FOptionalSignedValue& Field : SignedValues)
    {
        if (Payload->HasField(Field.Name))
        {
            double Value = 0.0;
            if (!Number(Payload, Field.Name, -1.0, 1.0, Value, Error))
            {
                return false;
            }
            *Field.Target = Value;
        }
    }
    if (Payload->HasField(TEXT("holdSeconds")))
    {
        if (!Number(Payload, TEXT("holdSeconds"), 0.0, 600.0, Seconds, Error))
        {
            return false;
        }
        Message.HoldMs = static_cast<Gahyeon::Millis>(Seconds * 1000.0);
    }
    return true;
}

bool DecodeWorldTransition(
    const TSharedPtr<FJsonObject>& Payload,
    Gahyeon::ProtocolMessage& Message,
    FString& Error)
{
    if (!AllowedFields(Payload, {
        TEXT("actionId"), TEXT("worldId"), TEXT("expectedRevision"), TEXT("generation"),
        TEXT("room"), TEXT("position"), TEXT("activity"), TEXT("interactionTarget"),
        TEXT("timeoutMs")}, Error)
        || !OptionalGeneration(Payload, Message, Error))
    {
        return false;
    }
    FString ActionId;
    FString WorldId;
    FString Room;
    FString Activity;
    FString InteractionTarget;
    int64 ExpectedRevision = 0;
    int64 TimeoutMs = 0;
    if (!RequiredString(Payload, TEXT("actionId"), ActionId, Error)
        || !RequiredString(Payload, TEXT("worldId"), WorldId, Error)
        || !Integer(Payload, TEXT("expectedRevision"), 0.0, MaximumSafeJsonInteger,
            ExpectedRevision, Error)
        || !RequiredString(Payload, TEXT("room"), Room, Error)
        || !Position(Payload, TEXT("position"), Message.TargetPosition, Error)
        || !RequiredString(Payload, TEXT("activity"), Activity, Error)
        || !RequiredString(Payload, TEXT("interactionTarget"), InteractionTarget, Error)
        || !Integer(Payload, TEXT("timeoutMs"), 1.0, 600000.0, TimeoutMs, Error))
    {
        return false;
    }
    Message.ActionId = ToUtf8(ActionId);
    Message.WorldId = ToUtf8(WorldId);
    Message.ExpectedRevision = static_cast<Gahyeon::Generation>(ExpectedRevision);
    Message.Room = ToUtf8(Room);
    Message.Activity = ToUtf8(Activity);
    Message.InteractionTarget = ToUtf8(InteractionTarget);
    Message.ActionTimeoutMs = TimeoutMs;
    return true;
}

bool DecodeActionResult(
    const TSharedPtr<FJsonObject>& Payload,
    Gahyeon::ProtocolMessage& Message,
    FString& Error)
{
    if (!AllowedFields(Payload, {
        TEXT("actionId"), TEXT("expectedRevision"), TEXT("outcome"), TEXT("result"),
        TEXT("reason"), TEXT("finalPosition")}, Error))
    {
        return false;
    }
    FString ActionId;
    FString Outcome;
    FString Result;
    TOptional<FString> Reason;
    int64 ExpectedRevision = 0;
    Gahyeon::WorldPosition FinalPosition;
    if (!RequiredString(Payload, TEXT("actionId"), ActionId, Error)
        || !Integer(Payload, TEXT("expectedRevision"), 0.0, MaximumSafeJsonInteger,
            ExpectedRevision, Error)
        || !RequiredString(Payload, TEXT("outcome"), Outcome, Error)
        || (Outcome != TEXT("completed") && Outcome != TEXT("failed")
            && Outcome != TEXT("cancelled"))
        || !RequiredString(Payload, TEXT("result"), Result, Error)
        || (Result != TEXT("committed") && Result != TEXT("recorded_failure")
            && Result != TEXT("duplicate") && Result != TEXT("conflict"))
        || !OptionalString(Payload, TEXT("reason"), Reason, Error)
        || !Position(Payload, TEXT("finalPosition"), FinalPosition, Error))
    {
        if (Error.IsEmpty())
        {
            Error = TEXT("invalid action result enum");
        }
        return false;
    }
    Message.ActionId = ToUtf8(ActionId);
    Message.ExpectedRevision = static_cast<Gahyeon::Generation>(ExpectedRevision);
    Message.Outcome = ToUtf8(Outcome);
    Message.Result = ToUtf8(Result);
    Message.TargetPosition = FinalPosition;
    return true;
}
}

EGahyeonPayloadDecodeStatus FGahyeonProtocolPayloadDecoder::Decode(
    const FGahyeonProtocolEnvelope& Envelope,
    Gahyeon::ProtocolMessage& OutMessage,
    FString& OutError)
{
    OutMessage = {};
    OutError.Reset();
    TSharedPtr<FJsonObject> Payload;
    const TSharedRef<TJsonReader<>> Reader =
        TJsonReaderFactory<>::Create(Envelope.PayloadJson);
    if (!FJsonSerializer::Deserialize(Reader, Payload) || !Payload.IsValid())
    {
        OutError = TEXT("payload is not a JSON object");
        return EGahyeonPayloadDecodeStatus::Invalid;
    }
    OutMessage.Type = ToUtf8(Envelope.Type);

    bool bDecoded = false;
    if (Envelope.Type == TEXT("world.snapshot"))
    {
        bDecoded = DecodeWorldSnapshot(Payload, OutMessage, OutError);
    }
    else if (Envelope.Type == TEXT("emotion.target"))
    {
        bDecoded = DecodeEmotion(Payload, OutMessage, OutError);
    }
    else if (Envelope.Type == TEXT("character.state.target"))
    {
        bDecoded = DecodeCharacterState(Payload, OutMessage, OutError);
    }
    else if (Envelope.Type == TEXT("world.transition.target"))
    {
        bDecoded = DecodeWorldTransition(Payload, OutMessage, OutError);
    }
    else if (Envelope.Type == TEXT("character.action.result"))
    {
        bDecoded = DecodeActionResult(Payload, OutMessage, OutError);
    }
    else if (Envelope.Type == TEXT("generation.advanced"))
    {
        bDecoded = DecodeGenerationAdvanced(Payload, OutMessage, OutError);
    }
    else if (Envelope.Type == TEXT("speech.prepared"))
    {
        bDecoded = DecodeSpeechPrepared(Payload, OutMessage, OutError);
    }
    else if (Envelope.Type == TEXT("speech.sequence.ended"))
    {
        bDecoded = DecodeSpeechSequenceEnded(Payload, OutMessage, OutError);
    }
    else if (Envelope.Type == TEXT("gesture.intent"))
    {
        bDecoded = DecodeGesture(Payload, OutMessage, OutError);
    }
    else if (Envelope.Type == TEXT("attention.target"))
    {
        bDecoded = DecodeAttention(Payload, OutMessage, OutError);
    }
    else if (Envelope.Type == TEXT("cognition.request.started")
        || Envelope.Type == TEXT("cognition.request.cancelled")
        || Envelope.Type == TEXT("cognition.response.completed")
        || Envelope.Type == TEXT("cognition.response.failed"))
    {
        bDecoded = DecodeCognitionEvent(Envelope.Type, Payload, OutMessage, OutError);
    }
    else
    {
        return EGahyeonPayloadDecodeStatus::Unsupported;
    }
    return bDecoded
        ? EGahyeonPayloadDecodeStatus::Decoded
        : EGahyeonPayloadDecodeStatus::Invalid;
}
