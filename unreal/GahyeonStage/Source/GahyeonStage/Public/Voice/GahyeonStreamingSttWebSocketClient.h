#pragma once

#include "CoreMinimal.h"
#include "HAL/ThreadSafeCounter.h"
#include "IWebSocket.h"
#include "Voice/GahyeonStreamingSttAudioSink.h"

/** Game-thread network adapter for the Core-owned Streaming STT v1 endpoint. */
class GAHYEONSTAGE_API FGahyeonStreamingSttWebSocketClient final
    : public TSharedFromThis<FGahyeonStreamingSttWebSocketClient, ESPMode::ThreadSafe>
{
public:
    using FPartialCallback = TFunction<void(int64, const FString&, double)>;
    using FFinalCallback = TFunction<void(int64, const FString&, const FString&)>;

    FGahyeonStreamingSttWebSocketClient(
        FString InEndpoint,
        FString InSessionId,
        FString InBearerToken,
        TSharedPtr<FGahyeonStreamingSttAudioSink, ESPMode::ThreadSafe> InSink,
        FPartialCallback InPartial,
        FFinalCallback InFinal);
    ~FGahyeonStreamingSttWebSocketClient();

    bool Connect();
    void Disconnect();
    void TickGameThread();
    bool IsConnected() const;
    static bool IsCurrentCallback(
        uint64 CallbackGeneration,
        uint64 CurrentGeneration) noexcept;
    static FString SerializeControlJson(
        const FString& SessionId,
        const Gahyeon::StreamingSttCommand& Command);

private:
    bool OpenSocket();
    void HandleConnected(uint64 CallbackGeneration);
    void HandleMessage(const FString& Message, uint64 CallbackGeneration);
    void HandleInboundBackpressure(uint64 CallbackGeneration);
    void HandleDisconnected(uint64 CallbackGeneration);
    bool SendCommand(const Gahyeon::StreamingSttCommand& Command);

    FString Endpoint;
    FString SessionId;
    FString BearerToken;
    TSharedPtr<FGahyeonStreamingSttAudioSink, ESPMode::ThreadSafe> Sink;
    FPartialCallback Partial;
    FFinalCallback Final;
    TSharedPtr<IWebSocket> Socket;
    bool bShouldReconnect = false;
    int32 ReconnectAttempt = 0;
    double NextReconnectAtSeconds = 0.0;
    uint64 SocketGeneration = 0;
    FThreadSafeCounter PendingMessageCallbacks;
    FThreadSafeCounter InboundBackpressureScheduled;
    static constexpr int32 MaximumPendingMessageCallbacks = 128;
};
