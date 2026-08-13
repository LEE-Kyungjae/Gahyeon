#include "Voice/GahyeonStreamingSttWebSocketClient.h"

#include "Async/Async.h"
#include "Dom/JsonObject.h"
#include "Modules/ModuleManager.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"
#include "Serialization/JsonWriter.h"
#include "WebSocketsModule.h"
#include "HAL/PlatformTime.h"

FGahyeonStreamingSttWebSocketClient::FGahyeonStreamingSttWebSocketClient(
    FString InEndpoint,
    FString InSessionId,
    FString InBearerToken,
    TSharedPtr<FGahyeonStreamingSttAudioSink, ESPMode::ThreadSafe> InSink,
    FPartialCallback InPartial,
    FFinalCallback InFinal)
    : Endpoint(MoveTemp(InEndpoint)),
      SessionId(MoveTemp(InSessionId)),
      BearerToken(MoveTemp(InBearerToken)),
      Sink(MoveTemp(InSink)),
      Partial(MoveTemp(InPartial)),
      Final(MoveTemp(InFinal))
{
}

FGahyeonStreamingSttWebSocketClient::~FGahyeonStreamingSttWebSocketClient()
{
    Disconnect();
}

bool FGahyeonStreamingSttWebSocketClient::Connect()
{
    check(IsInGameThread());
    if (Socket.IsValid() || !Sink.IsValid() || Endpoint.IsEmpty() || SessionId.IsEmpty())
    {
        return false;
    }
    bShouldReconnect = true;
    ReconnectAttempt = 0;
    NextReconnectAtSeconds = 0.0;
    return OpenSocket();
}

bool FGahyeonStreamingSttWebSocketClient::OpenSocket()
{
    check(IsInGameThread());
    if (Socket.IsValid() || !bShouldReconnect) return false;
    FWebSocketsModule& WebSockets =
        FModuleManager::LoadModuleChecked<FWebSocketsModule>(TEXT("WebSockets"));
    TMap<FString, FString> Headers;
    if (!BearerToken.IsEmpty())
    {
        Headers.Add(TEXT("Authorization"), TEXT("Bearer ") + BearerToken);
    }
    Socket = WebSockets.CreateWebSocket(Endpoint, FString{}, Headers);
    const uint64 CallbackGeneration = ++SocketGeneration;
    const TWeakPtr<FGahyeonStreamingSttWebSocketClient, ESPMode::ThreadSafe> WeakThis = AsShared();
    Socket->OnConnected().AddLambda([WeakThis, CallbackGeneration]()
    {
        AsyncTask(ENamedThreads::GameThread, [WeakThis, CallbackGeneration]()
        {
            if (const auto Pinned = WeakThis.Pin()) Pinned->HandleConnected(CallbackGeneration);
        });
    });
    Socket->OnMessage().AddLambda([WeakThis, CallbackGeneration](const FString& Message)
    {
        const auto Pinned = WeakThis.Pin();
        if (!Pinned.IsValid()) return;
        if (Pinned->PendingMessageCallbacks.Increment() > MaximumPendingMessageCallbacks)
        {
            Pinned->PendingMessageCallbacks.Decrement();
            if (Pinned->InboundBackpressureScheduled.Increment() != 1)
            {
                Pinned->InboundBackpressureScheduled.Decrement();
                return;
            }
            AsyncTask(ENamedThreads::GameThread, [WeakThis, CallbackGeneration]()
            {
                if (const auto Current = WeakThis.Pin())
                {
                    Current->HandleInboundBackpressure(CallbackGeneration);
                }
            });
            return;
        }
        AsyncTask(ENamedThreads::GameThread, [WeakThis, CallbackGeneration, Message]()
        {
            if (const auto Current = WeakThis.Pin())
            {
                Current->HandleMessage(Message, CallbackGeneration);
                Current->PendingMessageCallbacks.Decrement();
            }
        });
    });
    Socket->OnConnectionError().AddLambda([WeakThis, CallbackGeneration](const FString& Error)
    {
        (void)Error;
        AsyncTask(ENamedThreads::GameThread, [WeakThis, CallbackGeneration]()
        {
            if (const auto Pinned = WeakThis.Pin()) Pinned->HandleDisconnected(CallbackGeneration);
        });
    });
    Socket->OnClosed().AddLambda(
        [WeakThis, CallbackGeneration](int32 StatusCode, const FString& Reason, bool bWasClean)
        {
            (void)StatusCode;
            (void)Reason;
            (void)bWasClean;
            AsyncTask(ENamedThreads::GameThread, [WeakThis, CallbackGeneration]()
            {
                if (const auto Pinned = WeakThis.Pin())
                {
                    Pinned->HandleDisconnected(CallbackGeneration);
                }
            });
        });
    Socket->Connect();
    return true;
}

void FGahyeonStreamingSttWebSocketClient::Disconnect()
{
    check(IsInGameThread());
    bShouldReconnect = false;
    ++SocketGeneration;
    if (Sink.IsValid()) Sink->SetTransportAvailable(false);
    if (Socket.IsValid())
    {
        Socket->Close(1000, TEXT("streaming stt shutdown"));
        Socket.Reset();
    }
}

void FGahyeonStreamingSttWebSocketClient::TickGameThread()
{
    check(IsInGameThread());
    if (!Socket.IsValid() && bShouldReconnect
        && FPlatformTime::Seconds() >= NextReconnectAtSeconds)
    {
        OpenSocket();
    }
    if (!Socket.IsValid() || !Socket->IsConnected() || !Sink.IsValid()) return;
    int32 Drained = 0;
    while (Drained++ < 32)
    {
        const auto Command = Sink->TakeCommand();
        if (!Command.has_value()) break;
        if (!SendCommand(*Command))
        {
            const uint64 FailedGeneration = SocketGeneration;
            Socket->Close(1011, TEXT("streaming stt send failed"));
            HandleDisconnected(FailedGeneration);
            break;
        }
    }
}

bool FGahyeonStreamingSttWebSocketClient::IsConnected() const
{
    return Socket.IsValid() && Socket->IsConnected();
}

bool FGahyeonStreamingSttWebSocketClient::IsCurrentCallback(
    uint64 CallbackGeneration,
    uint64 CurrentGeneration) noexcept
{
    return CallbackGeneration == CurrentGeneration;
}

void FGahyeonStreamingSttWebSocketClient::HandleConnected(uint64 CallbackGeneration)
{
    check(IsInGameThread());
    if (!IsCurrentCallback(CallbackGeneration, SocketGeneration) || !Socket.IsValid()) return;
    if (Sink.IsValid()) Sink->SetTransportAvailable(true);
    ReconnectAttempt = 0;
    NextReconnectAtSeconds = 0.0;
}

void FGahyeonStreamingSttWebSocketClient::HandleMessage(
    const FString& Message,
    uint64 CallbackGeneration)
{
    check(IsInGameThread());
    InboundBackpressureScheduled.Reset();
    if (!IsCurrentCallback(CallbackGeneration, SocketGeneration)) return;
    TSharedPtr<FJsonObject> Root;
    const TSharedRef<TJsonReader<>> Reader = TJsonReaderFactory<>::Create(Message);
    if (!FJsonSerializer::Deserialize(Reader, Root) || !Root.IsValid() || !Sink.IsValid()) return;
    FString Type;
    FString IncomingSession;
    FString StreamId;
    double GenerationNumber = -1;
    if (!Root->TryGetStringField(TEXT("type"), Type)
        || !Root->TryGetStringField(TEXT("sessionId"), IncomingSession)
        || IncomingSession != SessionId
        || !Root->TryGetStringField(TEXT("streamId"), StreamId)
        || !Root->TryGetNumberField(TEXT("generation"), GenerationNumber)
        || GenerationNumber < 0 || GenerationNumber > static_cast<double>(MAX_int64)) return;
    const int64 Generation = static_cast<int64>(GenerationNumber);
    if (Type == TEXT("stt.stream.error"))
    {
        Sink->ProviderFailed(Generation, StreamId);
        return;
    }
    double SequenceNumber = -1;
    FString Text;
    if (!Root->TryGetNumberField(TEXT("resultSequence"), SequenceNumber)
        || SequenceNumber < 0 || SequenceNumber > static_cast<double>(MAX_uint64)
        || !Root->TryGetStringField(TEXT("text"), Text) || Text.TrimStartAndEnd().IsEmpty()) return;
    const uint64 Sequence = static_cast<uint64>(SequenceNumber);
    if (Type == TEXT("stt.transcript.partial"))
    {
        double Stability = 0;
        if (!Root->TryGetNumberField(TEXT("stability"), Stability)
            || !FMath::IsFinite(Stability) || Stability < 0 || Stability > 1) return;
        if (Sink->AcceptPartial(Generation, StreamId, Sequence, Text)
            == Gahyeon::StreamingSttResult::Accepted && Partial)
        {
            Partial(Generation, Text, Stability);
        }
    }
    else if (Type == TEXT("stt.transcript.final"))
    {
        FString Language;
        if (!Root->TryGetStringField(TEXT("language"), Language) || Language.IsEmpty()) return;
        if (Sink->AcceptFinal(Generation, StreamId, Sequence, Text)
            == Gahyeon::StreamingSttResult::Accepted && Final)
        {
            Final(Generation, Text, Language);
        }
    }
}

void FGahyeonStreamingSttWebSocketClient::HandleInboundBackpressure(
    uint64 CallbackGeneration)
{
    check(IsInGameThread());
    if (!IsCurrentCallback(CallbackGeneration, SocketGeneration)) return;
    if (Sink.IsValid()) Sink->ResultIngressBackpressured();
    if (Socket.IsValid()) Socket->Close(1008, TEXT("streaming stt result backpressure"));
    HandleDisconnected(CallbackGeneration);
}

void FGahyeonStreamingSttWebSocketClient::HandleDisconnected(uint64 CallbackGeneration)
{
    check(IsInGameThread());
    if (!IsCurrentCallback(CallbackGeneration, SocketGeneration)) return;
    if (Sink.IsValid()) Sink->TransportFailed();
    Socket.Reset();
    ++SocketGeneration;
    if (bShouldReconnect)
    {
        const int32 Exponent = FMath::Min(ReconnectAttempt, 6);
        const double Delay = FMath::Min(0.5 * static_cast<double>(1 << Exponent), 30.0);
        ++ReconnectAttempt;
        NextReconnectAtSeconds = FPlatformTime::Seconds() + Delay;
    }
}

bool FGahyeonStreamingSttWebSocketClient::SendCommand(
    const Gahyeon::StreamingSttCommand& Command)
{
    if (!Socket.IsValid() || !Socket->IsConnected()) return false;
    if (Command.Type == Gahyeon::StreamingSttCommandType::Audio)
    {
        if (Command.BinaryFrame.empty()) return false;
        Socket->Send(Command.BinaryFrame.data(), Command.BinaryFrame.size(), true);
        return true;
    }
    const FString Json = SerializeControlJson(SessionId, Command);
    if (Json.IsEmpty()) return false;
    Socket->Send(Json);
    return true;
}

FString FGahyeonStreamingSttWebSocketClient::SerializeControlJson(
    const FString& InSessionId,
    const Gahyeon::StreamingSttCommand& Command)
{
    TSharedRef<FJsonObject> Root = MakeShared<FJsonObject>();
    Root->SetNumberField(TEXT("schemaVersion"), 1);
    Root->SetStringField(TEXT("sessionId"), InSessionId);
    Root->SetStringField(TEXT("streamId"), UTF8_TO_TCHAR(Command.StreamId.c_str()));
    Root->SetNumberField(TEXT("generation"), static_cast<double>(Command.Generation));
    switch (Command.Type)
    {
    case Gahyeon::StreamingSttCommandType::Start:
    {
        Root->SetStringField(TEXT("type"), TEXT("stt.stream.start"));
        Root->SetNumberField(TEXT("observedAtMs"), static_cast<double>(Command.ObservedAtMs));
        TSharedRef<FJsonObject> Format = MakeShared<FJsonObject>();
        Format->SetStringField(TEXT("encoding"), TEXT("float32le"));
        Format->SetNumberField(TEXT("sampleRate"), Command.Format.SampleRate);
        Format->SetNumberField(TEXT("channels"), Command.Format.Channels);
        Format->SetNumberField(TEXT("framesPerChunk"), Command.Format.FramesPerChunk);
        Root->SetObjectField(TEXT("format"), Format);
        break;
    }
    case Gahyeon::StreamingSttCommandType::End:
        Root->SetStringField(TEXT("type"), TEXT("stt.stream.end"));
        Root->SetNumberField(TEXT("observedAtMs"), static_cast<double>(Command.ObservedAtMs));
        Root->SetNumberField(TEXT("lastAudioSequence"), static_cast<double>(Command.Sequence));
        break;
    case Gahyeon::StreamingSttCommandType::Cancel:
        Root->SetStringField(TEXT("type"), TEXT("stt.stream.cancel"));
        switch (Command.CancelReason)
        {
        case Gahyeon::StreamingSttCancelReason::BargeIn:
            Root->SetStringField(TEXT("reason"), TEXT("barge_in"));
            break;
        case Gahyeon::StreamingSttCancelReason::Backpressure:
            Root->SetStringField(TEXT("reason"), TEXT("backpressure"));
            break;
        case Gahyeon::StreamingSttCancelReason::CaptureError:
            Root->SetStringField(TEXT("reason"), TEXT("capture_error"));
            break;
        case Gahyeon::StreamingSttCancelReason::Timeout:
            Root->SetStringField(TEXT("reason"), TEXT("timeout"));
            break;
        case Gahyeon::StreamingSttCancelReason::ClientReset:
        default:
            Root->SetStringField(TEXT("reason"), TEXT("client_reset"));
            break;
        }
        break;
    default:
        return {};
    }
    FString Json;
    const TSharedRef<TJsonWriter<>> Writer = TJsonWriterFactory<>::Create(&Json);
    return FJsonSerializer::Serialize(Root, Writer) ? Json : FString{};
}
