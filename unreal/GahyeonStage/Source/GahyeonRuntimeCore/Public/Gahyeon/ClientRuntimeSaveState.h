#pragma once

#include "Gahyeon/ReplayCursorRuntime.h"
#include "Gahyeon/WorldActionCommandBridge.h"

#include <optional>

namespace Gahyeon {

struct ClientRuntimeSaveState {
    int SchemaVersion = 2;
    Generation DurableSequence = 0;
    Generation InteractionGeneration = 0;
    WorldActionBridgeSnapshot WorldActions;
};

enum class ClientSaveStateResult {
    Restored,
    UnsupportedVersion,
    Invalid,
    CapacityExceeded,
};

struct ClientRuntimeRestoreResult {
    ClientSaveStateResult Result = ClientSaveStateResult::Invalid;
    std::optional<Generation> DurableSequence;
    std::optional<Generation> InteractionGeneration;
};

/** Versioned data boundary mapped by an Unreal USaveGame without engine types. */
class GAHYEON_RUNTIME_CORE_API ClientRuntimeSaveStateCodec {
public:
    static constexpr int CurrentSchemaVersion = 2;

    static ClientRuntimeSaveState Capture(
        const ReplayCursorRuntime& cursor,
        const WorldActionCommandBridge& actions,
        Generation interactionGeneration,
        Millis nowMs);
    static ClientRuntimeRestoreResult Restore(
        ClientRuntimeSaveState state,
        WorldActionCommandBridge& actions,
        Millis nowMs);
};

} // namespace Gahyeon
