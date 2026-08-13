# Gahyeon

[한국어](README.md) · [English](README.en.md) · [日本語](README.ja.md)

Gahyeonは、永続メモリ、音声、自律行動、永続的なWorldを備えたモジュール型の
embodied AI agentです。Discord Botに画面を追加するプロジェクトではなく、独立した
一つのGahyeon Coreを複数のClient/Adapterから利用する構成を目指します。

> 優先するのはグラフィックスデモではなく、低遅延リアルタイムAIキャラクターの
> アーキテクチャです。LLMの応答中もReflex、Behavior、Cognitionは互いをブロックせず
> 動作し続けなければなりません。

```text
                         Gahyeon Core
 Conversation · Memory · STT/TTS · Tools · Session
        Emotion · Behavior · Persistent World
                              │
                   Event · HTTP · WebSocket
          ┌───────────────────┼───────────────────┐
          ▼                   ▼                   ▼
 Discord Adapter     Desktop Compatibility    Unreal Stage
                      Three.js / VRM          AAA target
                                                  │
                                      Monitor · Looking Glass
```

Coreは、何を話し、記憶し、感じ、行動し、どこへ移動するかを決定します。
Presentationは、その決定を音声、表情、リップシンク、アニメーション、レンダリングで
表現します。

## 現在の状態

| 領域 | 現在確認できるもの |
|---|---|
| Core/Application | プラットフォーム非依存のConversation、Session、Speech port、Event、World/Behavior境界 |
| Headless | DiscordやLLM providerなしでAPIと永続Worldを実行可能 |
| Discord Adapter | 既存のSlash Command、テキスト・音声会話、音楽、運用機能を維持 |
| Desktop互換Client | Electron/Vue/Three.jsによるテキスト、マイク、音声、VRM、World経路 |
| Unreal Backend Adapter | 条件付きWebSocket v1、replay/cursor/snapshot、streaming speech |
| Unreal RuntimeCore | エンジン非依存C++20 Reflex/Behavior/Cognition、VAD、speech、viseme、World、保存・再接続harness |
| Unreal Stage | UE 5.6 source project、native runtime/ingress、asset不要の診断Pawn・カメラ。Editor build、MetaHuman、NavMesh、packaged buildは未検証 |
| Looking Glass | Desktop WebXR経路を実装。実機Go displayでのacceptanceは未完了 |
| Voice制作 | 重複を除いた5,000文のVoicebox teacher corpusを生成中。完了後は音響/STT/話者QC、Piper段階学習、blind reviewへ自動移行 |
| Character制作 | SDXL LoRA学習・比較は完了。ユーザー原本をcanonical identityとして固定したG0 packとG1 modeling handoff/draftを準備済み。最終hero meshは未完成 |

Reference runtimeの合格を、packaged Unrealの合格とは扱いません。遅い補助rendererの
隔離を含むRT-01からRT-13までの証拠と残る実機検証は
[Acceptance状態表](docs/unreal/ACCEPTANCE_STATUS.md)を参照してください。

## 設計原則

- Discord、Desktop、UnrealはCoreのClient/Adapterです。
- Core domainはJDA、Electron、Unreal、Spring Web、特定providerの型に依存しません。
- LLMはsemantic intentを生成し、frame単位のtransformやanimation asset IDを選びません。
- Reflex、Behavior、Cognitionは異なる時間軸で並行動作します。
- RendererがなくてもHeadless BehaviorとWorldは進行します。
- Network callbackはGame Threadの状態を直接変更しません。
- durable cursorとaction commandは保存成功後にだけACK・送信します。
- Memoryは何を記憶するか、World Stateは現在どこで何をしているかを担当します。

## 必要環境

- Java 21
- Node.js 20以降とnpm
- 本番環境: PostgreSQL 16推奨
- 開発標準: PostgreSQL互換モードのin-memory H2
- Unreal開発: Unreal Engine 5.6と互換MetaHuman plugin

## クイックスタート

### 1. ワークスペースの検証

```bash
./gradlew test
python3 scripts/verify_core_platform_boundaries.py
./scripts/test_unreal_runtime_core.sh
./scripts/verify_unreal_stage_scaffold.sh
./scripts/verify_unreal_protocol_contract.sh
./scripts/test_run_unreal_engine_gate.sh
./scripts/test_smoke_headless_core.sh

cd desktop
npm ci
npm test
npm run build
```

### 2. Headless Coreの実行

Headless CoreはDiscord・Spotify・OpenAI credentialなしで独立して実行できます。
credentialは、それを使用するAdapterまたはProviderを有効にする場合だけ設定します。

```bash
BOT_ENABLED=false \
WEATHER_PREFETCH_ENABLED=false \
GAHYEON_HEADLESS_ENABLED=true \
GAHYEON_BEHAVIOR_ENABLED=true \
TTS_ENABLED=false \
./gradlew bootRun
```

credentialなしでDiscordを無効にした実起動とhealth・World revisionのHTTP smokeは、
`./scripts/smoke_headless_core.sh`で一括再現できます。この無資格smokeではConversation
readinessが意図どおり`DOWN`となり、DBとWorldの動作を検証します。
配布JARまで検証する場合は、
`GAHYEON_HEADLESS_SMOKE_MODE=jar ./scripts/smoke_headless_core.sh`を使用します。
低速な開発環境では、`GAHYEON_HEADLESS_SMOKE_STARTUP_TIMEOUT`を30～900秒の範囲で設定できます。
実際のDocker image境界まで検証するには、`./scripts/smoke_headless_container.sh`を
実行します。一時containerでDiscordを無効化し、healthとWorld revision更新を確認してから、
このスクリプト自身が作成したcontainerだけを削除します。

標準API rootは`http://127.0.0.1:8080/api`です。Client tokenがない場合、Client APIは
loopback通信のみを許可します。

### 3. Desktop開発Clientの実行

別のターミナルで実行します。

```bash
cd desktop
npm ci
GAHYEON_CORE_API_URL=http://127.0.0.1:8080/api npm run dev
```

遠隔Coreを使う場合は、両側に同じ高エントロピーの`GAHYEON_CLIENT_TOKEN`を設定します。
VRM/VRMAと環境assetは[`desktop/.env.example`](desktop/.env.example)を参照してください。

### 4. LLM会話の有効化

```bash
GAHYEON_AGENT_PROVIDER=openai \
AGENT_API_KEY='<key>' \
AGENT_BASE_URL='https://openrouter.ai/api' \
AGENT_MODEL='<model>' \
GAHYEON_HEADLESS_ENABLED=true \
BOT_ENABLED=false \
./gradlew bootRun
```

選択したprovider/modelがtool callと発話可能なテキストを安全に分離できることを確認する
までは、`GAHYEON_AGENT_TOOL_SAFE_STREAMING_ENABLED`を有効にしないでください。

## Discord互換Adapter

```bash
BOT_ENABLED=true \
TOKEN='<discord-token>' \
APPLICATION_ID='<application-id>' \
GAHYEON_AGENT_PROVIDER=openai \
AGENT_API_KEY='<key>' \
./gradlew bootRun
```

既存の`/설정`、`/가현아`、退出、音楽、運用Slash Commandを維持します。音声会話は
`TEN VAD → STT → Conversation → TTS`を使い、ConversationとSpeech domainは
Discord objectを参照しません。

`BOT_ENABLED=true`の場合、Discord tokenの欠落・拒否または初期化失敗でもapplication
process自体は終了しませんが、`/api/health`とActuatorのDiscord healthは`FAILED`/DOWNに
fail closedします。Blue/Green followerがPostgreSQL advisory lockを正常に待つ場合だけ
`STANDBY`/UPとなり、`BOT_ENABLED=false`は明示的な`DISABLED`/UP状態です。

## Unreal Stage

Backend WebSocket AdapterとC++20 RuntimeCoreは準備済みですが、標準では無効です。
UE 5.6 Editorとpackaged buildの検証前に本番で有効化しないでください。

UE 5.6をインストールした開発機での正式gate:

```bash
GAHYEON_UE_ROOT="/path/to/UE_5.6" ./scripts/run_unreal_engine_gate.sh
```

GTX 1660 Ti の Windows 制作マシンでは、まず canonical Stage を検証します。

```powershell
.\scripts\run_unreal_engine_gate.ps1 -UnrealRoot "C:\Program Files\Epic Games\UE_5.6"
```

Editor 検証後に packaged Development まで生成・封印する場合は `-Package` を追加します。
packaged 版の10分測定は次の runner で実行・集計・検証します。

```powershell
.\scripts\run_desktop_realtime_acceptance.ps1 `
  -PackagedRoot "C:\gahyeon-package" `
  -EvidenceRoot "C:\gahyeon-evidence\desktop-0001"
```

- [Unrealアーキテクチャ](docs/unreal/ARCHITECTURE.md)
- [Protocol v1](docs/unreal/PROTOCOL_V1.md)
- [Adapter統合契約](docs/unreal/ADAPTER_INTEGRATION.md)
- [Vertical Slice順序](docs/unreal/VERTICAL_SLICE.md)
- [リアルタイムAcceptance](docs/unreal/REALTIME_ACCEPTANCE.md)
- [開発環境の準備状態](docs/unreal/READINESS.md)

## 主要設定

| 変数 | 用途 | 標準値 |
|---|---|---|
| `BOT_ENABLED` | Discord Adapter接続 | `true` |
| `GAHYEON_HEADLESS_ENABLED` | Headless/Desktop API | `false` |
| `GAHYEON_CLIENT_TOKEN` | 遠隔Client bearer認証 | なし。loopbackのみ |
| `GAHYEON_BEHAVIOR_ENABLED` | Core自律行動scheduler | `false` |
| `GAHYEON_UNREAL_WEBSOCKET_ENABLED` | Unreal WebSocket endpoint | `false` |
| `GAHYEON_UNREAL_COGNITION_*` | Unreal Cognition worker/queue上限 | 小さいbounded pool |
| `GAHYEON_UNREAL_TTS_*` | Unreal TTS worker/queue上限 | 小さいbounded pool |
| `GAHYEON_UNREAL_VISEME_ALIGNER_*` | exact lip-sync HTTP aligner、250ms playback deadline、専用bounded pool | 無効 |
| `GAHYEON_UNREAL_SPEECH_SEGMENT_MAX_CHARACTERS` | streaming TTS文分割上限 | `120` |
| `GAHYEON_AGENT_PROVIDER` | Spring AI chat provider | `none` |
| `GAHYEON_AGENT_PROVIDER_FAILURE_COOLDOWN_MILLIS` | model provider障害後のrecovery probe待機時間 | `5000` |
| `GAHYEON_CONTENT_SAFETY_PROVIDER` | 交換可能な入力安全Adapter（`openai`、`none`） | `openai` |
| `GAHYEON_CONTENT_SAFETY_CONNECT_TIMEOUT_MILLIS` / `READ_TIMEOUT_MILLIS` | 入力安全provider上限（各100～5000ms） | `300` / `700` |
| `GAHYEON_CONTENT_SAFETY_FAILURE_COOLDOWN_MILLIS` | 入力安全provider障害後の単一recovery probe待機 | `30000` |
| `GAHYEON_AGENT_TOOL_SAFE_STREAMING_ENABLED` | 検証済みproviderのtoken streaming | `false` |
| `GAHYEON_AGENT_STREAMING_VERIFIED_BASE_URL` | streaming probeを通過した正確なprovider base URL | なし |
| `GAHYEON_AGENT_STREAMING_VERIFIED_MODEL` | streaming probeを通過した正確なmodel ID | なし |
| `AGENT_API_KEY`, `AGENT_BASE_URL`, `AGENT_MODEL` | LLM endpoint | providerごとに設定 |
| `ASSISTANT_STT_*`, `ASSISTANT_VAD_*` | Discord音声認識とVAD | 環境ごとに設定 |
| `TTS_PROVIDER` | `voicebox`、`edge`、`custom` | `voicebox` |

音声設定とfallbackは[Custom Voice TTS](docs/CUSTOM_VOICE_TTS.md)を参照してください。

## リポジトリ構成

```text
src/main/java/com/gahyeonbot/
├─ core/          framework/platform非依存domain
├─ application/   use case、port、orchestration
└─ adapters/      Discord、Desktop、Headless、Unreal、provider実装

desktop/           Electron/Vue/Three.js互換Presentation Client
unreal/RuntimeCore/ エンジン非依存C++20リアルタイムreference runtime
unreal/GahyeonStage/ UE 5.6 source-only Stage projectとnative module
docs/unreal/        Unreal architecture、protocol、acceptance、integration契約
scripts/            Voice/Piper、SDXL asset pipeline、運用補助ツール
```

## 名称と互換性

製品・キャラクター・アーキテクチャの正式名称は**Gahyeon**です。`com.gahyeonbot`の
Java package、既存database/container名、GHCR path、一部service fileの`gahyeonbot`は、
運用migrationを壊さないためのlegacy identifierです。現在の製品名を示すものではなく、
repositoryとdeploymentを調整して移行するまでは一括変更しません。

## ドキュメント

- [システムアーキテクチャ](docs/ARCHITECTURE.md)
- [Core分離記録](docs/GAHYEON_CORE_MIGRATION.md)
- [API](docs/API.md)
- [Desktop](desktop/README.md)
- [AIRI分析](docs/AIRI_DESKTOP_ANALYSIS.md)
- [AAA Character Pipeline](docs/AAA_CHARACTER_PIPELINE.md)
- [Character品質Gate](docs/GAHYEON_CHARACTER_QUALITY_GATES.md)
- [G1 Modeling Handoff](docs/GAHYEON_G1_MODELING_HANDOFF.md)
- [Unreal Acceptance状態](docs/unreal/ACCEPTANCE_STATUS.md)
- [Looking Glass](docs/LOOKING_GLASS.md)
- [音声](docs/CUSTOM_VOICE_TTS.md)
- [デプロイ](docs/DEPLOYMENT.md)

## セキュリティとasset

秘密鍵、元音声、学習checkpoint、ライセンス付きのVRM/VRMA/MetaHuman/環境assetをGitや
container imageへ含めないでください。デプロイ環境のsecretと別のartifact storageを
使用します。

SDXL出力や生成draftはcanonicalな顔の根拠ではありません。Character identity authorityは
checksumで固定したユーザー原本packであり、生成G1 sheetの推定領域と承認状態は別manifestで
管理します。
