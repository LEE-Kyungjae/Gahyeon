# Gahyeon

[한국어](README.md) | [English](README.en.md)

Gahyeon is an independent AI agent evolving toward persistent memory, voice, autonomous behavior, and a living 3D world. Discord and Desktop are clients of the same platform-neutral Core; conversation, speech, sessions, behavior, and world-state decisions do not belong to either UI.

The existing Discord music, scheduling, and server utilities remain available through the Discord adapter while the migration proceeds incrementally. Desktop provides text and voice conversation, a VRM avatar, animation, lip sync, and a persistent world. Looking Glass Go is an optional renderer of that same world.

## Features

- **Platform-neutral Core**: Runs conversation, sessions, STT, TTS, events, and world state without a Discord connection.
- **Desktop client**: Provides text/voice interaction, a VRM avatar, expressions, lip sync, activity animation, and world navigation.
- **Persistent world and behavior**: Restores location and activity and selects deterministic idle behavior.
- **AI agent**: Routes `/gahyeona` (`/가현아` in Korean) and regular messages in the dedicated text channel to the agent runtime.
- **Per-server assistant channels**: `/setup` (`/설정`) creates and binds dedicated text and voice channels.
- **Voice assistant**: Automatically joins the configured voice channel and responds through an STT → AI → TTS pipeline.
- **Turn detection**: TEN VAD and end-of-speech silence rules prevent API calls on every brief pause.
- **Conversation memory**: Preserves user and assistant message boundaries for follow-up requests.
- **Pluggable speech output**: Supports Voicebox, Edge TTS, and a generic HTTP custom-TTS contract with optional Edge fallback.
- **Music and voice-channel tools**: Playback, pause, skip, queue management, and scheduled leave actions.
- **Newsletters and moderation**: Subscription-based DMs and server-management commands.

## Assistant Setup

Run the setup command once as a server administrator:

```text
/setup
```

Discord displays the localized `/설정` name for Korean users. The command creates or restores the bot's dedicated text and voice channels.

- Regular messages in the assistant text channel behave like `/gahyeona`.
- Entering the assistant voice channel makes the bot join automatically.
- Use `/assistant action:start`, `stop`, or `status` for manual control. Korean clients display the localized `/비서` command.

### Voice pipeline

```text
Discord audio → TEN VAD → STT → AI agent/OpenRouter → TTS → Discord audio
```

A turn is finalized only after the configured minimum speech and continuous-silence conditions are met. Each finalized turn creates one streamed OpenRouter request.

## Technology

- Java 21 and Spring Boot 3
- JDA 5 and LavaPlayer
- PostgreSQL / H2 and Flyway
- OpenRouter-backed agent runtime
- TEN VAD and replaceable HTTP STT
- Voicebox, Edge TTS, and generic custom TTS
- Docker, GitHub Actions, and Blue/Green deployment

See the [Core migration](docs/GAHYEON_CORE_MIGRATION.md), [Desktop client](desktop/README.md), and [architecture](docs/ARCHITECTURE.md) documents for implementation details.

## Requirements

- Java 21
- The included Gradle Wrapper
- A Discord bot token and application ID
- An OpenRouter API key when AI features are enabled
- Spotify credentials when music integrations are enabled
- PostgreSQL in production
- Reachable STT and TTS services for voice conversations

## Main environment variables

### Core and AI

| Variable | Description |
| --- | --- |
| `TOKEN` | Discord bot token |
| `APPLICATION_ID` | Discord application ID |
| `BOT_ENABLED` | Enables the Discord connection |
| `ASSISTANT_ENABLED` | Enables the voice assistant |
| `ASSISTANT_OPENROUTER_ENABLED` | Enables the OpenRouter assistant provider |
| `OPENROUTER_API_KEY` | OpenRouter API key |
| `OPENROUTER_MODEL` | OpenRouter model ID |
| `SPOTIFY_CLIENT_ID` | Spotify client ID |
| `SPOTIFY_CLIENT_SECRET` | Spotify client secret |

### STT and turn detection

| Variable | Description |
| --- | --- |
| `ASSISTANT_STT_ENABLED` | Enables speech recognition |
| `ASSISTANT_STT_BASE_URL` | STT server base URL |
| `ASSISTANT_STT_ENDPOINT` | Transcription endpoint path |
| `ASSISTANT_STT_API_KEY_REQUIRED` | Whether STT authentication is required |
| `ASSISTANT_STT_API_KEY` | STT API key |
| `ASSISTANT_STT_MODEL` | STT model name |
| `ASSISTANT_VAD_ENABLED` | Enables TEN VAD |
| `ASSISTANT_VAD_THRESHOLD` | Speech probability threshold |
| `ASSISTANT_VAD_END_SILENCE_MILLIS` | Continuous silence required to finalize a turn |

### TTS

| Variable | Description |
| --- | --- |
| `TTS_PROVIDER` | `voicebox`, `edge`, or `custom` |
| `TTS_FALLBACK_TO_EDGE` | Uses Edge when the primary provider fails |
| `VOICEBOX_BASE_URL` | Voicebox server URL |
| `VOICEBOX_PROFILE_ID` | Voicebox profile ID |
| `VOICEBOX_PROFILE_NAME` | Profile name fallback when no ID is configured |
| `VOICEBOX_MODEL_SIZE` | Voicebox model size |
| `VOICEBOX_TIMEOUT_SECONDS` | Voicebox request timeout |
| `CUSTOM_TTS_ENDPOINT` | Custom synthesis HTTP endpoint |
| `CUSTOM_TTS_API_KEY` | Optional custom-TTS bearer token |
| `CUSTOM_TTS_MODEL` | Model alias understood by the inference server |
| `CUSTOM_TTS_SPEAKER_ID` | Speaker ID understood by the inference server |
| `CUSTOM_TTS_FORMAT` | `wav` or `mp3` |

See [Custom Voice TTS](docs/CUSTOM_VOICE_TTS.md) for the full HTTP contract. Never commit secrets to the repository or bake them into images; inject them through environment variables or GitHub Actions Secrets.

## Local development

```bash
git clone https://github.com/LEE-Kyungjae/gahyeonbot.git
cd gahyeonbot
./gradlew clean test
./gradlew bootRun
```

To run the Gahyeon Core and Headless/Desktop APIs without connecting to Discord:

```bash
BOT_ENABLED=false ./gradlew bootRun
```

The default development profile uses an in-memory H2 database when no external database is configured. Set the relevant `POSTGRES_DEV_*` variables and `FLYWAY_ENABLED=true` to use PostgreSQL.

## Docker

```bash
docker build -t gahyeonbot:latest .
docker run --rm \
  -e TOKEN \
  -e APPLICATION_ID \
  -e OPENROUTER_API_KEY \
  gahyeonbot:latest
```

Inside a container, `127.0.0.1` refers to the container itself. Configure a container-reachable address when STT, Voicebox, or custom TTS runs on another host.

## Custom-voice research status

- **Voicebox**: Supports cloned-voice profiles built from recordings.
- **Piper distillation**: Experimental tools under `scripts/` distill Voicebox teacher audio into a lightweight Piper model. This is research work and is not treated as the default Discord TTS.
- Confirm speaker consent and audio usage rights, and protect recordings, models, and profile identifiers as sensitive data.

## Test and deploy

```bash
./gradlew clean test
./gradlew clean shadowJar
```

GitHub Actions tests pull requests and pushes to `main`, builds versioned images, and performs approved Blue/Green deployments. See [Deployment](docs/DEPLOYMENT.md) for details.

## Documentation

- [API](docs/API.md)
- [Core migration](docs/GAHYEON_CORE_MIGRATION.md)
- [Desktop client](desktop/README.md)
- [VRM animation](docs/VRM_ANIMATION.md)
- [Looking Glass](docs/LOOKING_GLASS.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Agent runtime](docs/agent-runtime.md)
- [Custom voice TTS](docs/CUSTOM_VOICE_TTS.md)
- [Deployment](docs/DEPLOYMENT.md)

## Contributing and license

Issues and pull requests are welcome. This project is licensed under the [MIT License](LICENSE).

## Contact

- [LEE-Kyungjae](https://github.com/LEE-Kyungjae)
- ze2@kakao.com
