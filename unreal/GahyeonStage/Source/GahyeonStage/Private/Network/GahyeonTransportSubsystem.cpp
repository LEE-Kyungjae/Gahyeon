#include "Network/GahyeonTransportSubsystem.h"

#include "Async/Async.h"
#include "Containers/Ticker.h"
#include "Dom/JsonObject.h"
#include "HAL/PlatformTime.h"
#include "Modules/ModuleManager.h"
#include "Persistence/GahyeonRuntimePersistenceSubsystem.h"
#include "Protocol/GahyeonProtocolParser.h"
#include "Runtime/GahyeonRuntimeSubsystem.h"
#include "Serialization/JsonSerializer.h"
#include "Serialization/JsonWriter.h"
#include "WebSocketsModule.h"

EGahyeonTransportCallbackDisposition UGahyeonTransportSubsystem::ClassifyCallback(
    uint64 CapturedConnectionGeneration,
    uint64 CapturedRuntimeEpoch,
    uint64 CurrentConnectionGeneration,
    uint64 CurrentRuntimeEpoch)
{
    if (CapturedConnectionGeneration != CurrentConnectionGeneration)
    {
        return EGahyeonTransportCallbackDisposition::OldConnection;
    }
    return CapturedRuntimeEpoch == CurrentRuntimeEpoch
        ? EGahyeonTransportCallbackDisposition::Current
        : EGahyeonTransportCallbackDisposition::ReplacedRuntime;
}

int32 UGahyeonTransportSubsystem::NormalizeHeartbeatIntervalMillis(double IntervalMillis)
{
    if (!FMath::IsFinite(IntervalMillis)
        || IntervalMillis < 1'000.0
        || IntervalMillis > 60'000.0
        || IntervalMillis != FMath::FloorToDouble(IntervalMillis))
    {
        return INDEX_NONE;
    }
    return static_cast<int32>(IntervalMillis);
}

bool UGahyeonTransportSubsystem::IsExpectedHeartbeatPong(
    const FString& PendingCorrelationId,
    const FString& ReceivedCorrelationId)
{
    return !PendingCorrelationId.IsEmpty()
        && PendingCorrelationId == ReceivedCorrelationId;
}

void UGahyeonTransportSubsystem::Initialize(FSubsystemCollectionBase& Collection)
{
    Collection.InitializeDependency<UGahyeonRuntimeSubsystem>();
    Collection.InitializeDependency<UGahyeonRuntimePersistenceSubsystem>();
    Super::Initialize(Collection);
    Runtime = GetGameInstance()->GetSubsystem<UGahyeonRuntimeSubsystem>();
    Persistence = GetGameInstance()->GetSubsystem<UGahyeonRuntimePersistenceSubsystem>();
    if (Runtime != nullptr)
    {
        Runtime->SetOutboundSender(
            [WeakThis = TWeakObjectPtr<UGahyeonTransportSubsystem>(this)](const FString& Json)
            {
                return WeakThis.IsValid() && WeakThis->SendProtocolJson(Json);
            });
        Runtime->SetReconnectRequester(
            [WeakThis = TWeakObjectPtr<UGahyeonTransportSubsystem>(this)]()
            {
                if (WeakThis.IsValid())
                {
                    WeakThis->RequestProtocolReconnect();
                }
            });
    }
}

void UGahyeonTransportSubsystem::Deinitialize()
{
    Disconnect();
    if (Runtime != nullptr)
    {
        Runtime->SetOutboundSender({});
        Runtime->SetReconnectRequester({});
    }
    Runtime = nullptr;
    Persistence = nullptr;
    Super::Deinitialize();
}

void UGahyeonTransportSubsystem::Configure(
    const FString& InEndpoint,
    const FString& InSessionId,
    const FString& InWorldId,
    const FString& InInstallationId,
    const FString& InDisplayName,
    const FString& InBearerToken)
{
    check(IsInGameThread());
    if (Socket.IsValid() || bConnectPending)
    {
        return;
    }
    Endpoint = InEndpoint.TrimStartAndEnd();
    SessionId = InSessionId.TrimStartAndEnd();
    WorldId = InWorldId.TrimStartAndEnd();
    InstallationId = InInstallationId.TrimStartAndEnd();
    DisplayName = InDisplayName.TrimStartAndEnd();
    BearerToken = InBearerToken;
}

bool UGahyeonTransportSubsystem::Connect()
{
    check(IsInGameThread());
    if (Socket.IsValid() || bConnectPending || Persistence == nullptr
        || Endpoint.IsEmpty() || SessionId.IsEmpty() || WorldId.IsEmpty()
        || InstallationId.IsEmpty())
    {
        return false;
    }

    bShouldReconnect = true;
    CancelReconnect();
    bConnectPending = true;
    const uint64 Generation = ++ConnectionGeneration;
    Persistence->LoadAsync(
        [WeakThis = TWeakObjectPtr<UGahyeonTransportSubsystem>(this), Generation](
            UGahyeonRuntimeSaveGame* State,
            const FString& Error)
        {
            if (!WeakThis.IsValid())
            {
                return;
            }
            UGahyeonTransportSubsystem* Self = WeakThis.Get();
            if (Self->ConnectionGeneration != Generation || !Self->bConnectPending)
            {
                return;
            }
            Self->bConnectPending = false;
            if (State == nullptr || !Error.IsEmpty())
            {
                Self->ScheduleReconnect();
                return;
            }
            if (Self->Runtime == nullptr
                || !Self->Runtime->RestorePersistentState(*State))
            {
                Self->ScheduleReconnect();
                return;
            }
            Self->Runtime->BeginBackendConnection();
            Self->OpenSocket(
                Generation,
                Self->Runtime->GetRuntimeEpoch(),
                State->DurableSequence);
        });
    return true;
}

void UGahyeonTransportSubsystem::Disconnect()
{
    check(IsInGameThread());
    bShouldReconnect = false;
    ReconnectAttempt = 0;
    CancelReconnect();
    StopHeartbeat();
    LastHeartbeatRttMillis = -1.0;
    bConnectPending = false;
    ++ConnectionGeneration;
    if (Socket.IsValid())
    {
        Socket->Close(1000, TEXT("client shutdown"));
        Socket.Reset();
    }
    if (Runtime != nullptr)
    {
        Runtime->SetBackendConnected(false);
    }
}

bool UGahyeonTransportSubsystem::IsSocketConnected() const
{
    return Socket.IsValid() && Socket->IsConnected();
}

bool UGahyeonTransportSubsystem::SendProtocolJson(const FString& Json)
{
    check(IsInGameThread());
    if (!Socket.IsValid() || !Socket->IsConnected() || Json.IsEmpty())
    {
        return false;
    }
    Socket->Send(Json);
    return true;
}

FString UGahyeonTransportSubsystem::GetHttpOrigin() const
{
    FString Origin = Endpoint.TrimStartAndEnd();
    if (Origin.StartsWith(TEXT("wss://"), ESearchCase::IgnoreCase))
    {
        Origin = TEXT("https://") + Origin.Mid(6);
    }
    else if (Origin.StartsWith(TEXT("ws://"), ESearchCase::IgnoreCase))
    {
        Origin = TEXT("http://") + Origin.Mid(5);
    }
    else if (!Origin.StartsWith(TEXT("https://"), ESearchCase::IgnoreCase)
        && !Origin.StartsWith(TEXT("http://"), ESearchCase::IgnoreCase))
    {
        return {};
    }
    const int32 AuthorityStart = Origin.Find(TEXT("://")) + 3;
    const int32 PathStart = Origin.Find(
        TEXT("/"), ESearchCase::CaseSensitive, ESearchDir::FromStart, AuthorityStart);
    if (AuthorityStart < 3) return {};
    if (PathStart != INDEX_NONE) Origin.LeftInline(PathStart);
    return Origin;
}

FString UGahyeonTransportSubsystem::GetStreamingSttEndpoint() const
{
    FString Result = Endpoint.TrimStartAndEnd();
    const FString EventSuffix = TEXT("/gahyeon/unreal/v1");
    if (!Result.EndsWith(EventSuffix, ESearchCase::CaseSensitive)) return {};
    Result.LeftChopInline(EventSuffix.Len());
    return Result + TEXT("/gahyeon/unreal/stt/v1");
}

void UGahyeonTransportSubsystem::RequestProtocolReconnect()
{
    check(IsInGameThread());
    StopHeartbeat();
    if (Socket.IsValid())
    {
        Socket->Close(1008, TEXT("durable protocol apply failed"));
    }
    else
    {
        ScheduleReconnect();
    }
}

void UGahyeonTransportSubsystem::OpenSocket(
    uint64 Generation,
    uint64 RuntimeEpoch,
    int64 LastSequence)
{
    check(IsInGameThread());
    if (ConnectionGeneration != Generation || Socket.IsValid() || LastSequence < 0)
    {
        return;
    }

    FWebSocketsModule& WebSockets =
        FModuleManager::LoadModuleChecked<FWebSocketsModule>(TEXT("WebSockets"));
    TMap<FString, FString> Headers;
    if (!BearerToken.IsEmpty())
    {
        Headers.Add(TEXT("Authorization"), TEXT("Bearer ") + BearerToken);
    }
    Socket = WebSockets.CreateWebSocket(
        Endpoint, FString{}, Headers);
    const TWeakObjectPtr<UGahyeonTransportSubsystem> WeakThis(this);
    Socket->OnConnected().AddLambda([WeakThis, Generation, RuntimeEpoch, LastSequence]()
    {
        AsyncTask(ENamedThreads::GameThread,
            [WeakThis, Generation, RuntimeEpoch, LastSequence]()
        {
            if (WeakThis.IsValid())
            {
                WeakThis->SendHello(Generation, RuntimeEpoch, LastSequence);
            }
        });
    });
    Socket->OnMessage().AddLambda(
        [WeakThis, Generation, RuntimeEpoch](const FString& Message)
    {
        // Parsing is UObject-free and safe at the callback boundary.
        FGahyeonProtocolEnvelope Envelope;
        FString Error;
        if (!FGahyeonProtocolParser::ParseInbound(Message, Envelope, Error))
        {
            return;
        }
        AsyncTask(ENamedThreads::GameThread,
            [WeakThis, Generation, RuntimeEpoch, Envelope = MoveTemp(Envelope)]() mutable
            {
                if (!WeakThis.IsValid() || WeakThis->Runtime == nullptr)
                {
                    return;
                }
                const EGahyeonTransportCallbackDisposition Disposition =
                    UGahyeonTransportSubsystem::ClassifyCallback(
                        Generation,
                        RuntimeEpoch,
                        WeakThis->ConnectionGeneration,
                        WeakThis->Runtime->GetRuntimeEpoch());
                if (Disposition == EGahyeonTransportCallbackDisposition::OldConnection)
                {
                    // It must never close the new socket.
                    return;
                }
                if (Disposition == EGahyeonTransportCallbackDisposition::ReplacedRuntime)
                {
                    // A SaveGame restore replaces all RuntimeCore owners. The
                    // old socket must replay against the new cursor instead of
                    // admitting a callback captured by the previous runtime.
                    if (WeakThis->Socket.IsValid())
                    {
                        WeakThis->Socket->Close(1008, TEXT("runtime epoch changed"));
                    }
                    return;
                }
                if (Envelope.Type == TEXT("server.welcome"))
                {
                    if (!WeakThis->ApplyWelcomeHeartbeat(Generation, Envelope.PayloadJson))
                    {
                        return;
                    }
                }
                if (Envelope.Type == TEXT("server.pong"))
                {
                    // Heartbeat health belongs to the connection, not RuntimeCore.
                    // Never let a transport response become a character event.
                    WeakThis->ConsumeHeartbeatPong(
                        Generation,
                        Envelope.CorrelationId,
                        Envelope.Delivery,
                        Envelope.PayloadJson);
                    return;
                }
                if (!WeakThis->Runtime->EnqueueInbound(MoveTemp(Envelope))
                    && WeakThis->Socket.IsValid())
                {
                    // Overflow can lose durable ordering. Force replay from the
                    // last persisted cursor instead of continuing a corrupt stream.
                    WeakThis->Socket->Close(1008, TEXT("inbound mailbox overflow"));
                }
            });
    });
    Socket->OnConnectionError().AddLambda([WeakThis, Generation](const FString& Error)
    {
        (void)Error;
        AsyncTask(ENamedThreads::GameThread, [WeakThis, Generation]()
        {
            if (WeakThis.IsValid())
            {
                WeakThis->HandleDisconnected(Generation);
            }
        });
    });
    Socket->OnClosed().AddLambda(
        [WeakThis, Generation](int32 StatusCode, const FString& Reason, bool bWasClean)
        {
            (void)StatusCode;
            (void)Reason;
            (void)bWasClean;
            AsyncTask(ENamedThreads::GameThread, [WeakThis, Generation]()
            {
                if (WeakThis.IsValid())
                {
                    WeakThis->HandleDisconnected(Generation);
                }
            });
        });
    Socket->Connect();
}

void UGahyeonTransportSubsystem::SendHello(
    uint64 Generation,
    uint64 RuntimeEpoch,
    int64 LastSequence)
{
    check(IsInGameThread());
    if (Runtime == nullptr)
    {
        return;
    }
    const EGahyeonTransportCallbackDisposition Disposition = ClassifyCallback(
        Generation, RuntimeEpoch, ConnectionGeneration, Runtime->GetRuntimeEpoch());
    if (Disposition == EGahyeonTransportCallbackDisposition::OldConnection)
    {
        return;
    }
    if (Disposition == EGahyeonTransportCallbackDisposition::ReplacedRuntime)
    {
        // Runtime restore happened after OpenSocket but before OnConnected.
        // An open socket without a hello cannot converge, so force a replaying
        // reconnect instead of leaving it alive indefinitely.
        if (Socket.IsValid())
        {
            Socket->Close(1008, TEXT("runtime epoch changed before hello"));
        }
        return;
    }
    if (Socket.IsValid() && Socket->IsConnected())
    {
        ReconnectAttempt = 0;
        Socket->Send(BuildHello(LastSequence));
        StartHeartbeat(Generation, 10.0);
    }
}

void UGahyeonTransportSubsystem::HandleDisconnected(uint64 Generation)
{
    check(IsInGameThread());
    if (ConnectionGeneration != Generation)
    {
        return;
    }
    StopHeartbeat();
    LastHeartbeatRttMillis = -1.0;
    Socket.Reset();
    if (Runtime != nullptr)
    {
        Runtime->SetBackendConnected(false);
    }
    ScheduleReconnect();
}

void UGahyeonTransportSubsystem::ScheduleReconnect()
{
    check(IsInGameThread());
    if (!bShouldReconnect || bConnectPending || Socket.IsValid()
        || ReconnectTickerHandle.IsValid())
    {
        return;
    }

    // Bounded exponential backoff with jitter prevents reconnect storms while
    // keeping the first recovery attempt fast enough for live conversation.
    const int32 Exponent = FMath::Min(ReconnectAttempt, 6);
    const float BaseDelaySeconds = FMath::Min(0.5f * static_cast<float>(1 << Exponent), 30.0f);
    const float DelaySeconds = FMath::Clamp(
        BaseDelaySeconds * FMath::FRandRange(0.8f, 1.2f), 0.25f, 30.0f);
    ++ReconnectAttempt;

    const TWeakObjectPtr<UGahyeonTransportSubsystem> WeakThis(this);
    ReconnectTickerHandle = FTSTicker::GetCoreTicker().AddTicker(
        FTickerDelegate::CreateLambda([WeakThis](float DeltaTime)
        {
            (void)DeltaTime;
            if (!WeakThis.IsValid())
            {
                return false;
            }
            UGahyeonTransportSubsystem* Self = WeakThis.Get();
            Self->ReconnectTickerHandle.Reset();
            if (Self->bShouldReconnect)
            {
                Self->Connect();
            }
            return false;
        }),
        DelaySeconds);
}

void UGahyeonTransportSubsystem::CancelReconnect()
{
    check(IsInGameThread());
    if (ReconnectTickerHandle.IsValid())
    {
        FTSTicker::GetCoreTicker().RemoveTicker(ReconnectTickerHandle);
        ReconnectTickerHandle.Reset();
    }
}

void UGahyeonTransportSubsystem::StartHeartbeat(
    uint64 Generation,
    double IntervalSeconds)
{
    check(IsInGameThread());
    StopHeartbeat();
    const float SafeIntervalSeconds = static_cast<float>(
        FMath::Clamp(IntervalSeconds, 1.0, 60.0));
    const TWeakObjectPtr<UGahyeonTransportSubsystem> WeakThis(this);
    HeartbeatTickerHandle = FTSTicker::GetCoreTicker().AddTicker(
        FTickerDelegate::CreateLambda([WeakThis, Generation](float DeltaTime)
        {
            (void)DeltaTime;
            if (!WeakThis.IsValid()) return false;
            UGahyeonTransportSubsystem* Self = WeakThis.Get();
            if (Self->ConnectionGeneration != Generation
                || !Self->Socket.IsValid()
                || !Self->Socket->IsConnected())
            {
                Self->HeartbeatTickerHandle.Reset();
                return false;
            }
            if (!Self->PendingHeartbeatCorrelationId.IsEmpty())
            {
                // One complete negotiated interval elapsed without a pong. The
                // TCP socket may be half-open, so resume through a fresh hello.
                ++Self->HeartbeatTimeoutCount;
                Self->HeartbeatTickerHandle.Reset();
                Self->RequestProtocolReconnect();
                return false;
            }
            if (!Self->SendProtocolJson(Self->BuildPing()))
            {
                Self->HeartbeatTickerHandle.Reset();
                Self->RequestProtocolReconnect();
                return false;
            }
            return true;
        }),
        SafeIntervalSeconds);
}

void UGahyeonTransportSubsystem::StopHeartbeat()
{
    check(IsInGameThread());
    if (HeartbeatTickerHandle.IsValid())
    {
        FTSTicker::GetCoreTicker().RemoveTicker(HeartbeatTickerHandle);
        HeartbeatTickerHandle.Reset();
    }
    PendingHeartbeatCorrelationId.Reset();
    PendingHeartbeatSentAtSeconds = 0.0;
}

bool UGahyeonTransportSubsystem::ApplyWelcomeHeartbeat(
    uint64 Generation,
    const FString& PayloadJson)
{
    check(IsInGameThread());
    if (Generation != ConnectionGeneration) return false;
    TSharedPtr<FJsonObject> Payload;
    const TSharedRef<TJsonReader<>> Reader = TJsonReaderFactory<>::Create(PayloadJson);
    double IntervalMillis = 0.0;
    if (!FJsonSerializer::Deserialize(Reader, Payload)
        || !Payload.IsValid()
        || !Payload->TryGetNumberField(TEXT("heartbeatIntervalMs"), IntervalMillis))
    {
        if (Socket.IsValid())
        {
            StopHeartbeat();
            Socket->Close(1008, TEXT("invalid heartbeat contract"));
        }
        return false;
    }
    const int32 SafeIntervalMillis = NormalizeHeartbeatIntervalMillis(IntervalMillis);
    if (SafeIntervalMillis == INDEX_NONE)
    {
        if (Socket.IsValid())
        {
            StopHeartbeat();
            Socket->Close(1008, TEXT("invalid heartbeat contract"));
        }
        return false;
    }
    StartHeartbeat(Generation, static_cast<double>(SafeIntervalMillis) / 1'000.0);
    return true;
}

bool UGahyeonTransportSubsystem::ConsumeHeartbeatPong(
    uint64 Generation,
    const FString& CorrelationId,
    const FString& Delivery,
    const FString& PayloadJson)
{
    check(IsInGameThread());
    if (Generation != ConnectionGeneration)
    {
        return false;
    }
    TSharedPtr<FJsonObject> Payload;
    FString ClientSentAt;
    FDateTime ParsedClientSentAt;
    const TSharedRef<TJsonReader<>> Reader = TJsonReaderFactory<>::Create(PayloadJson);
    if (Delivery != TEXT("ephemeral")
        || !FJsonSerializer::Deserialize(Reader, Payload)
        || !Payload.IsValid()
        || Payload->Values.Num() != 1
        || !Payload->TryGetStringField(TEXT("clientSentAt"), ClientSentAt)
        || !FDateTime::ParseIso8601(*ClientSentAt, ParsedClientSentAt))
    {
        ++InvalidHeartbeatPongCount;
        StopHeartbeat();
        if (Socket.IsValid())
        {
            Socket->Close(1008, TEXT("invalid heartbeat pong"));
        }
        return false;
    }
    if (!IsExpectedHeartbeatPong(PendingHeartbeatCorrelationId, CorrelationId))
    {
        // A delayed reply may arrive after reconnect/retry. It must neither
        // satisfy the current heartbeat nor poison RuntimeCore.
        ++StaleHeartbeatPongCount;
        return false;
    }
    LastHeartbeatRttMillis = FMath::Max(
        0.0,
        (FPlatformTime::Seconds() - PendingHeartbeatSentAtSeconds) * 1'000.0);
    PendingHeartbeatCorrelationId.Reset();
    PendingHeartbeatSentAtSeconds = 0.0;
    ++AcceptedHeartbeatPongCount;
    return true;
}

FString UGahyeonTransportSubsystem::BuildHello(int64 LastSequence) const
{
    const FString MessageId = FGuid::NewGuid().ToString(EGuidFormats::DigitsWithHyphensLower);
    const FString CorrelationId = TEXT("connection:") + MessageId;
    TSharedRef<FJsonObject> Payload = MakeShared<FJsonObject>();
    Payload->SetStringField(TEXT("sessionId"), SessionId);
    Payload->SetStringField(TEXT("worldId"), WorldId);
    Payload->SetStringField(TEXT("installationId"), InstallationId);
    Payload->SetStringField(TEXT("displayName"),
        DisplayName.IsEmpty() ? TEXT("Gahyeon user") : DisplayName);
    Payload->SetNumberField(TEXT("lastSequence"), static_cast<double>(LastSequence));

    TSharedRef<FJsonObject> Root = MakeShared<FJsonObject>();
    Root->SetStringField(TEXT("protocol"), TEXT("gahyeon.unreal.v1"));
    Root->SetNumberField(TEXT("schemaVersion"), 1);
    Root->SetStringField(TEXT("messageId"), MessageId);
    Root->SetStringField(TEXT("type"), TEXT("client.hello"));
    Root->SetStringField(TEXT("sentAt"), FDateTime::UtcNow().ToIso8601());
    Root->SetStringField(TEXT("sessionId"), SessionId);
    Root->SetStringField(TEXT("correlationId"), CorrelationId);
    Root->SetStringField(TEXT("delivery"), TEXT("ephemeral"));
    Root->SetObjectField(TEXT("payload"), Payload);

    FString Json;
    const TSharedRef<TJsonWriter<>> Writer = TJsonWriterFactory<>::Create(&Json);
    FJsonSerializer::Serialize(Root, Writer);
    return Json;
}

FString UGahyeonTransportSubsystem::BuildPing()
{
    const FString MessageId = FGuid::NewGuid().ToString(EGuidFormats::DigitsWithHyphensLower);
    const FString CorrelationId = TEXT("heartbeat:") + MessageId;
    TSharedRef<FJsonObject> Root = MakeShared<FJsonObject>();
    Root->SetStringField(TEXT("protocol"), TEXT("gahyeon.unreal.v1"));
    Root->SetNumberField(TEXT("schemaVersion"), 1);
    Root->SetStringField(TEXT("messageId"), MessageId);
    Root->SetStringField(TEXT("type"), TEXT("client.ping"));
    Root->SetStringField(TEXT("sentAt"), FDateTime::UtcNow().ToIso8601());
    Root->SetStringField(TEXT("sessionId"), SessionId);
    Root->SetStringField(TEXT("correlationId"), CorrelationId);
    Root->SetStringField(TEXT("delivery"), TEXT("ephemeral"));
    Root->SetObjectField(TEXT("payload"), MakeShared<FJsonObject>());
    FString Json;
    const TSharedRef<TJsonWriter<>> Writer = TJsonWriterFactory<>::Create(&Json);
    if (!FJsonSerializer::Serialize(Root, Writer))
    {
        return FString{};
    }
    PendingHeartbeatCorrelationId = CorrelationId;
    PendingHeartbeatSentAtSeconds = FPlatformTime::Seconds();
    return Json;
}
