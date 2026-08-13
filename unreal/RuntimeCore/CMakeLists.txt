cmake_minimum_required(VERSION 3.20)
project(GahyeonRuntimeCore LANGUAGES CXX)

set(RUNTIME_MODULE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../GahyeonStage/Source/GahyeonRuntimeCore")

add_library(gahyeon_runtime_core STATIC
    ${RUNTIME_MODULE_DIR}/Private/ClientRuntimeSaveState.cpp
    ${RUNTIME_MODULE_DIR}/Private/ConnectionConvergenceRuntime.cpp
    ${RUNTIME_MODULE_DIR}/Private/EmotionRuntime.cpp
    ${RUNTIME_MODULE_DIR}/Private/GestureRuntime.cpp
    ${RUNTIME_MODULE_DIR}/Private/AmbientMotionRuntime.cpp
    ${RUNTIME_MODULE_DIR}/Private/AttentionRuntime.cpp
    ${RUNTIME_MODULE_DIR}/Private/IntentMailbox.cpp
    ${RUNTIME_MODULE_DIR}/Private/IntentRuntime.cpp
    ${RUNTIME_MODULE_DIR}/Private/LipSyncRuntime.cpp
    ${RUNTIME_MODULE_DIR}/Private/LatencyTrace.cpp
    ${RUNTIME_MODULE_DIR}/Private/MockCognitionRuntime.cpp
    ${RUNTIME_MODULE_DIR}/Private/PcmUtteranceBuffer.cpp
    ${RUNTIME_MODULE_DIR}/Private/ProtocolMessageTranslator.cpp
    ${RUNTIME_MODULE_DIR}/Private/ProtocolEventRuntime.cpp
    ${RUNTIME_MODULE_DIR}/Private/ProtocolGameThreadDispatcher.cpp
    ${RUNTIME_MODULE_DIR}/Private/ProtocolIngressMailbox.cpp
    ${RUNTIME_MODULE_DIR}/Private/ProtocolNetworkBridge.cpp
    ${RUNTIME_MODULE_DIR}/Private/RealtimeCharacterCoordinator.cpp
    ${RUNTIME_MODULE_DIR}/Private/ReplayCursorRuntime.cpp
    ${RUNTIME_MODULE_DIR}/Private/SpeechQueue.cpp
    ${RUNTIME_MODULE_DIR}/Private/SpeechPlaybackCoordinator.cpp
    ${RUNTIME_MODULE_DIR}/Private/StreamingSttClientRuntime.cpp
    ${RUNTIME_MODULE_DIR}/Private/VoiceActivityDetector.cpp
    ${RUNTIME_MODULE_DIR}/Private/VoiceInteractionController.cpp
    ${RUNTIME_MODULE_DIR}/Private/WorldStateRuntime.cpp
    ${RUNTIME_MODULE_DIR}/Private/WorldActionRuntime.cpp
    ${RUNTIME_MODULE_DIR}/Private/WorldActionCompletionOutbox.cpp
    ${RUNTIME_MODULE_DIR}/Private/WorldActionCommandBridge.cpp
)
target_include_directories(gahyeon_runtime_core PUBLIC ${RUNTIME_MODULE_DIR}/Public)
target_compile_definitions(gahyeon_runtime_core PUBLIC GAHYEON_RUNTIME_CORE_API=)
target_compile_features(gahyeon_runtime_core PUBLIC cxx_std_20)
find_package(Threads REQUIRED)
target_link_libraries(gahyeon_runtime_core PUBLIC Threads::Threads)

enable_testing()
add_executable(gahyeon_runtime_core_tests tests/RuntimeCoreTests.cpp)
target_link_libraries(gahyeon_runtime_core_tests PRIVATE gahyeon_runtime_core)
add_test(NAME gahyeon_runtime_core_tests COMMAND gahyeon_runtime_core_tests)
