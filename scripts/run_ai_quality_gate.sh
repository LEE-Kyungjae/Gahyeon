#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

python3 scripts/test_verify_ai_quality_catalog.py
python3 scripts/verify_ai_quality_catalog.py
python3 scripts/test_create_autonomy_contract.py
python3 scripts/test_decide_autonomy_action.py
python3 scripts/test_evaluate_canary_observation.py
python3 scripts/test_verify_blind_tts_review.py
python3 scripts/test_piper_listening_review.py
python3 scripts/test_piper_runtime_server.py
./gradlew test \
  --tests 'com.gahyeonbot.adapters.discord.voice.VoiceAssistantTranscriptionGuardTest' \
  --tests 'com.gahyeonbot.services.ai.agent.AgentResponseSanitizerTest' \
  --tests 'com.gahyeonbot.services.ai.agent.DefaultAgentRuntimeCancellationTest' \
  --tests 'com.gahyeonbot.services.ai.WeatherToolsTest'

echo "Gahyeon AI quality gate passed."
