#include "Gahyeon/ClientRuntimeSaveState.h"

#include <utility>

namespace Gahyeon {

ClientRuntimeSaveState ClientRuntimeSaveStateCodec::Capture(
    const ReplayCursorRuntime& cursor,
    const WorldActionCommandBridge& actions,
    Generation interactionGeneration,
    Millis nowMs) {
    return {
        .SchemaVersion = CurrentSchemaVersion,
        .DurableSequence = cursor.PersistedSequence(),
        .InteractionGeneration = interactionGeneration,
        .WorldActions = actions.Snapshot(nowMs),
    };
}

ClientRuntimeRestoreResult ClientRuntimeSaveStateCodec::Restore(
    ClientRuntimeSaveState state,
    WorldActionCommandBridge& actions,
    Millis nowMs) {
    if (state.SchemaVersion < 1 || state.SchemaVersion > CurrentSchemaVersion) {
        return {ClientSaveStateResult::UnsupportedVersion, std::nullopt, std::nullopt};
    }
    const Generation interactionGeneration = state.SchemaVersion >= 2
        ? state.InteractionGeneration
        : 0;
    if (state.DurableSequence < 0 || interactionGeneration < 0 || nowMs < 0) {
        return {ClientSaveStateResult::Invalid, std::nullopt, std::nullopt};
    }
    const CompletionOutboxResult restored = actions.Restore(
        std::move(state.WorldActions), nowMs);
    if (restored == CompletionOutboxResult::Full) {
        return {ClientSaveStateResult::CapacityExceeded, std::nullopt, std::nullopt};
    }
    if (restored != CompletionOutboxResult::Accepted) {
        return {ClientSaveStateResult::Invalid, std::nullopt, std::nullopt};
    }
    return {
        ClientSaveStateResult::Restored,
        state.DurableSequence,
        interactionGeneration};
}

} // namespace Gahyeon
