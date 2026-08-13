# 커스텀 음성 TTS

## Voicebox 연결

Voicebox는 교사 음성 생성과 품질 비교에 사용합니다. 운영 환경에서는 저지연
Piper HTTP 서버를 사용할 수 있으며, Voicebox 직접 연결은 비교·폴백 용도로
남겨 둡니다.

```env
TTS_PROVIDER=voicebox
VOICEBOX_BASE_URL=http://127.0.0.1:17493
VOICEBOX_PROFILE_ID=1df376d5-c74d-415c-a2f0-fdb1654f7331
VOICEBOX_MODEL_SIZE=0.6B
VOICEBOX_TIMEOUT_SECONDS=300
```

봇은 Voicebox의 `/generate`에 생성을 요청하고 `/history/{id}`를 폴링한
후 `/audio/{id}`에서 WAV를 받습니다. Voicebox가 꺼져 있거나 생성에
실패하면 `TTS_FALLBACK_TO_EDGE=true`일 때 Edge TTS로 자동 전환합니다.

Voicebox 프로필과 모델은 현재 Mac의 로컬 Voicebox 저장소에 있습니다.
봇이 원격 서버나 컨테이너에서 실행되면 `127.0.0.1`은 그 원격 실행
환경을 가리키므로, 해당 서버에서 접근 가능한 HTTPS 엔드포인트나
SSH 터널 주소를 `VOICEBOX_BASE_URL`로 지정해야 합니다.

## 범용 HTTP 계약

Gahyeon은 음성 학습 엔진과 직접 결합하지 않고 HTTP 추론 서버를 통해 사용자 음성을 합성합니다.
GPT-SoVITS, Fish Speech, XTTS 등 실제 엔진은 이 계약을 구현하는 작은 어댑터 뒤에 배치합니다.

## 요청

`CUSTOM_TTS_ENDPOINT`로 `POST application/json` 요청을 보냅니다.

```json
{
  "text": "읽을 문장",
  "model": "my-korean-model",
  "speakerId": "my-voice",
  "format": "wav"
}
```

`CUSTOM_TTS_API_KEY`가 있으면 `Authorization: Bearer <key>` 헤더를 추가합니다.

## 응답

- 상태: `2xx`
- 본문: WAV 또는 MP3 원본 바이트
- Content-Type: `audio/wav` 또는 `audio/mpeg`

모델 파일은 봇 컨테이너가 아니라 추론 서버의 PVC/볼륨에 마운트합니다.
봇에는 모델 경로 대신 서버가 이해하는 `CUSTOM_TTS_MODEL` 별칭만 전달하는 것을 권장합니다.

## 활성화

```env
TTS_PROVIDER=custom
ASSISTANT_TTS_PROVIDER=custom
TTS_FALLBACK_TO_EDGE=false
CUSTOM_TTS_ENDPOINT=http://voice-server:8000/synthesize
CUSTOM_TTS_MODEL=my-korean-model
CUSTOM_TTS_SPEAKER_ID=my-voice
CUSTOM_TTS_FORMAT=wav
```

커스텀 서버가 준비되지 않았거나 요청이 실패하면 `TTS_FALLBACK_TO_EDGE=true`일 때 기존 Edge TTS를 사용합니다.

## Piper 음성 제작 플레이북

이 절차는 2026-07-28의 프로필 6·9 혼합 실험에서 검증한 기준이다. 다른
사람의 목소리를 만들 때도 원본과 산출물을 별도 디렉터리에 두고 같은 품질
게이트를 적용한다.

### 데이터와 학습

- 교사 음성은 발음이 정확한 클립만 사용한다. CER, 내부 무음, 잘린 첫 음절을
  검사하고 탈락 클립은 학습에 넣지 않는다.
- 목소리 유사도가 높은 데이터와 발음이 안정적인 데이터를 섞을 수 있지만,
  어느 쪽을 몇 번 중복했는지 manifest에 기록한다.
- Piper 미세 조정은 `precision: 32-true`로 실행한다. 이 환경의 FP16 AMP는
  global step만 증가하고 optimizer update가 건너뛰어진 사례가 있었다.
- 첫 배치에서 유효 gradient 수와 실제 parameter delta를 확인한다. 둘 중
  하나라도 0이면 학습을 중단한다.
- 최종 체크포인트에는 실제 `global_step`을 기록하고, ONNX 변환 전 체크포인트를
  보존한다.

### 현재 실험 기준점

- 프로필 9 FP32 모델은 발음과 유사도가 안정적이지만 약한 코러스형 울렁임이 있다.
- 프로필 6 단독 320스텝은 유사도는 높았지만 발음 정확도가 붕괴해 폐기했다.
- 프로필 9 334개와 프로필 6 가중 140개를 섞은 모델은 320스텝보다
  420스텝에서 울렁임이 약 20% 감소했다.
- 동일 모델의 520스텝은 울렁임이 다시 심해져 과학습 후보로 폐기했다.
- 따라서 현재 청감 기준 체크포인트는 혼합 모델 `step420`이다. 운영 배포 전에는
  반드시 동일 문장 A/B 테스트를 다시 수행한다.

### 코러스형 울렁임 조정

증상은 `chorus-like pitch/formant wobble`로 기록한다. 음정과 포먼트가
주기적으로 흔들리고 목소리가 겹쳐 들리는 현상이다.

Piper 기본 추론 노이즈보다 낮은 값이 이 현상을 줄였다.

```bash
piper \
  --model model.onnx \
  --noise-scale 0.10 \
  --noise-w-scale 0.15 \
  --output_file output.wav
```

검수 순서는 기본값, `0.45/0.50`, `0.25/0.30`, `0.10/0.15`다. 값이 낮을수록
울렁임은 줄 수 있지만 음성이 딱딱해질 수 있으므로 숫자만으로 결정하지 않는다.
2026-07-28 청감 검수에서는 `0.10/0.15`가 가장 나았지만 완전한 해결로
판정하지 않았다.

### 합격 기준

- 긴 미확인 문장에서도 발음 누락이나 임의 단어 치환이 없어야 한다.
- 목소리 유사도뿐 아니라 코러스형 울렁임, 금속성, 템포 끊김을 사람이 듣고
  비교한다.
- 이전 운영 모델보다 명확하게 낫지 않으면 배포하지 않는다.
- 새 모델은 별도 이름으로 배포하고 기존 모델을 즉시 롤백할 수 있게 보존한다.

### WSL 디스크 관리

Piper 체크포인트는 하나당 약 810MB이므로 모든 epoch 체크포인트를 무제한
보존하지 않는다. 최종 모델, 현재 재개 지점, 비교 후보만 남긴다. WSL2의
`ext4.vhdx`는 내부 파일을 삭제해도 자동으로 작아지지 않을 수 있다. Windows
C: 여유 공간이 10GB 아래로 내려가면 `Wsl/Service/CreateInstance/E_FAIL`이
발생할 수 있으므로 학습 전에 호스트와 WSL 양쪽 공간을 확인한다.

2026-07-28에는 중복 체크포인트 145개(78GB)를 제거하고 VHDX를 정상 종료 후
압축해 다음과 같이 복구했다.

- Windows C: 여유 공간: 4.8GB → 80GB
- Ubuntu VHDX: 118.21GB → 43.03GB

`wsl --unregister`는 배포판 데이터를 삭제하므로 공간 복구 절차에 사용하지 않는다.

## 깨끗한 낭독 데이터 수집

즉흥 대화나 합성 음성만으로 운영급 Piper 화자를 만들지 않는다. Piper Recording
Studio의 한국어 문장을 일정한 마이크·거리·톤으로 읽어 최소 1차 1,100문장,
권장 2~4시간을 수집한다.

```bash
./scripts/piper_recording_studio.sh start
open http://127.0.0.1:8765
```

녹음은 기본적으로 `output/piper-recording-studio`에 저장되며 git에 포함되지 않는다.
다른 화자를 녹음할 때는 출력 디렉터리를 분리한다.

```bash
PIPER_RECORDING_OUTPUT="$PWD/output/piper-recording-speaker-2" \
  ./scripts/piper_recording_studio.sh start
```

녹음 기준:

- 같은 마이크와 입력 게인을 사용하고 입과 마이크 거리를 일정하게 유지한다.
- 음악, 에코, 노이즈 제거, 음성 변조, 자동 게인 이펙트를 끈다.
- 평소보다 또렷하게 읽되 연기하거나 과장된 억양을 섞지 않는다.
- 틀리게 읽거나 음절이 잘린 문장은 즉시 다시 녹음한다.
- 한 세션이 길어져 목소리가 피로해지면 쉬고, 쉰 목소리 데이터를 섞지 않는다.
- 실제 업무비서에서 사용할 숫자, 영문 약어, 고유명사 문장을 별도 세트로 추가한다.

공식 한국어 1,100문장은 약 1.5~1.9시간 분량이다. 1차 녹음 완료 후 전사와
원문 일치, 클리핑, 무음, 화자 유사도를 자동 검사하고 탈락 문장만 재녹음한다.

## 5,000문장 teacher 생성 복구 계약

장시간 Voicebox 생성은 단순 `manifest.jsonl` 줄 수로 완료를 판단하지 않는다. 감시자는
다음을 모두 확인한 뒤 기존 index 다음부터 재개한다.

- frozen 5,000문장 catalog와 manifest text가 index별로 동일함
- index가 1부터 끊김 없이 이어지고 중복이 없음
- audio 경로가 dataset root 밖으로 탈출하지 않음
- 각 WAV가 존재하고 헤더보다 큰 실제 데이터가 있음

현재 상태 확인:

```bash
python3 scripts/check_voicebox_teacher_progress.py \
  --catalog artifacts/voicebox-teacher-pure4000-2026-08-09/sentences-v4-diverse5000.jsonl \
  --output artifacts/voicebox-teacher-diverse1000-2026-08-09 \
  --target 5000
```

완료 gate에서는 `--probe-wav --require-complete`를 추가한다. 이미 로드된 로컬
LaunchAgent의 이전 `diverse4000` 이름은 호환 entrypoint로만 유지하며 실제 감시 로직은
`ensure_voicebox_teacher_diverse5000.sh`에 있다. manifest가 손상되면 자동 생성으로 덮지
않고 실패하여 수동 복구를 요구한다.
기존 v3 process가 4,000에서 정상 종료하면 감시자는 이를 완료로 오인하지 않고 v4 catalog의
4,001번부터 5,000까지 다시 시작한다. 실행 중 generator가 있으면 중복 process를 만들지 않는다.
generator가 종료되면 같은 supervisor process가 durable manifest의 정확한 5,000개 완료를 다시
검증하고 즉시 QC로 넘긴다. 완료 전 종료라면 다음 LaunchAgent 주기에 재개한다. 이 경계는
`test_voicebox_supervisor_decision.py`와 실제 shell fixture인
`test_ensure_voicebox_teacher_diverse5000.py`에서 4,000/4,999/5,000·실패 상태로 검증한다.
2026-08-12 실제 전환에서도 legacy process가 4,000을 atomic manifest에 확정하고 종료한 뒤,
등록된 LaunchAgent의 compatibility entrypoint가 `target_5000` runner를 시작했으며 동일 manifest의
4,001번을 연속해서 확정했다. 이때 generator lock PID와 실행 중 writer가 일치했고 duplicate index,
pipeline failure, 조기 QC 실행은 없었다.
새 runner도 supervisor와 독립적으로 기존 generator process를 확인하고 PID를 담은
`.voicebox-generator.lock`을 획득해야만 실행된다. stale lock만 회수하며, 합성 전에 v4 catalog와
현재 manifest의 연속 index·텍스트·audio 경계를 authoritative checker로 다시 검증한다. 따라서
수동 실행과 launchd 경합이 겹쳐도 두 writer가 같은 manifest를 append하지 않는다.
각 clip manifest 갱신은 기존의 완전한 JSONL과 새 record를 임시 파일에 쓴 뒤 file/directory
`fsync`와 atomic replace로 커밋한다. 마지막 줄이 이미 찢어진 manifest는 자동으로 덮거나
추정 복구하지 않고 즉시 실패하므로, 재부팅·강제 종료 뒤에도 이전 완전 상태 또는 새 완전
상태 중 하나만 authoritative manifest가 된다.
Voicebox WAV도 한 번에 메모리로 읽지 않는다. 선언된 `Content-Length`와 실제 streaming
누적량을 각각 32 MiB로 제한하고, 비어 있거나 과대한 응답은 임시 파일과 함께 폐기한다.
정상 응답만 file/directory `fsync`와 atomic replace 후 manifest에 연결하므로 부분 WAV가
완료 clip으로 노출되지 않는다.
설치 직후에는 RIFF reader로 mono, PCM16, 16~48 kHz, 0.1~120초 범위와 선언 frame 전체의
실제 payload 길이를 확인한다. 손상된 새 응답은 삭제하고 manifest에 기록하지 않으며,
손상된 reuse 후보는 폐기하고 같은 문장을 새로 합성한다. 5,000 runner 시작 시 기존 manifest의
모든 WAV도 `--probe-wav`로 다시 열어 본 뒤 resume한다.
이 판정은 `voicebox_audio_contract.py` 하나에 정의하며 generator와 progress/final completion
checker가 같은 함수를 호출한다. 생성 시 허용한 파일을 전환 gate가 다른 규칙으로 거부하거나,
반대로 전환 gate가 느슨해 손상 파일을 통과시키는 규칙 drift를 허용하지 않는다.

## Teacher QC와 Piper 인계

5,000문장 생성이 끝나면 로드된 감시자가 다음 단계를 순서대로 한 번만 실행한다.

1. `check_voicebox_catalog_diversity.py`가 정규화 중복, 4-gram 근접 중복, 길이,
   질문·감탄·숫자·영문·복문, 어휘량과 반복 suffix 집중도를 검사한다.
2. frozen catalog, 연속 index, WAV 존재성과 헤더를 다시 검증한다.
3. `acoustic_qc_voicebox_teacher.py`가 길이, 음량, 클리핑, 무음, WAV 손상을 검사한다.
4. 음향 통과분만 `stt_qc_voicebox_teacher.py`가 전사하고 정규화 CER 0.12 이하를 선택한다.
5. 안전한 종결 인용부호 중복은 학습 metadata에서 정규화하고, 3토큰 이상 인접 반복
   구절은 제외한다. 원문과 정규화 여부는 package manifest에 보존한다.
6. 선택된 음성을 22.05kHz mono PCM16으로 변환해 Piper dataset과 tar.gz를 만든다.
7. 모든 단계가 성공하면 다양성 report와 frozen catalog/완성 manifest SHA-256을 포함한
   `piper_handoff_ready.json`을 원자적으로 기록한다.
8. 완성된 archive를 SHA-256으로 검증해 `land`에 전송하고 Piper 단계 학습을 제출한다.
9. 원격 학습 직전에 `speaker_consistency_qc.py`가 각 clip을 실제 profile 9 기준 음성
   `real-v3-dataset-2026-07-28/reference9.wav`와 비교하고, 통과 metadata만 학습에 사용한다.

현재 frozen 5,000문장 기준 정규화 완전중복은 0개다. 문장부호는 정규화에서 제거하므로
마침표·물음표·느낌표만 다른 변형도 중복으로 계산한다. 전체 12,497,500쌍 중 4-gram
Jaccard 0.8 초과는 34쌍, 최대는 0.88이다. 질문 522, 감탄 175, 영문 포함 319,
ASCII 숫자 포함 24, 복문 285, 공백 기준 고유 어휘 22,567개이며 최대 동일 12자 suffix
집중률은 2.1%다. 이 값이 단순 보고가 아니라 학습 인계 hard gate로 실행된다.
현재 text-quality dry run에서는 종결부호 metadata 79건을 정규화하고 인접 반복 구절
9건을 제외해, 음향/STT/speaker QC 이전 최대 4,991개를 유지한다.

음향 QC는 WAV 파일명이나 수정 시각이 아니라 sample rate·channel·sample width와 raw PCM을
결합한 SHA-256도 계산한다. 서로 다른 catalog text가 같은 PCM을 가리키는 group이 하나라도
있으면 해당 clip을 모두 `duplicate_audio`로 거부하고 최종화 자체를 실패시킨다. STT
checkpoint는 이 PCM identity까지 같아야 재사용되며, 최종 패키저도 모든 선택 clip에 유효하고
서로 다른 identity가 있는지 다시 확인한다. 변환된 22.05kHz WAV SHA와 source PCM SHA를 모두
package manifest에 남겨 text → source audio → training audio 결합을 추적한다.
재실행 시 `verify_voicebox_handoff_identity.py`가 현재 catalog와 5,000개 manifest의 SHA를
ready marker와 다시 비교한다. QC 이후 원문·manifest가 한 바이트라도 바뀌면 기존 archive를
재사용하거나 원격 학습에 제출하지 않는다.
같은 archive SHA는 `piper_training_submitted.json`, 원격 `.dataset-ready`, 원격
`COMPLETED.json`, 로컬 `piper_training_complete.json`까지 전달된다. monitor는 모든 단계의
digest와 source identity가 연속적으로 일치해야만 결과를 회수하고 청취 review를 생성한다.
원격 runner가 speaker QC, 각 training/export/evaluation 또는 완료 manifest 단계에서 실패하면
dataset SHA, 단계, 종료 코드와 실패 명령을 `FAILED.json`에 원자적으로 기록한다. monitor는 이
marker가 제출한 archive와 같은 identity인지 확인한 뒤 로컬 결과 폴더로 회수한다. 따라서
프로세스가 사라진 상태를 무기한 “학습 중”으로 오인하지 않는다.
제출 이후의 최신 관측값은 로컬 `piper_training_status.json`에 원자적으로 기록한다. `training`
상태는 speaker QC, baseline, 각 step의 training/export/evaluation, completion manifest phase를
구분하며, step 완료는 checkpoint 생성만이 아니라 ONNX·평가 suite·SHA256SUMS가 모두 존재할
때만 인정한다.

음향 QC와 STT QC는 파일 크기·수정 시각·원문·PCM identity가 같은 결과만 재사용한다. STT 결과는 매
파일 직후 `stt_qc.jsonl`에 원자적으로 체크포인트되므로 장시간 검사 중 장애가 발생해도
완료된 전사를 다시 실행하지 않는다. 생성 중에는 Voicebox 자원을 합성과 공유하지 않도록
STT QC를 시작하지 않는다.

수동 실행이 필요할 때의 단일 진입점은 다음과 같다.

```bash
./scripts/finalize_voicebox_teacher_piper.sh
```

전체 진행 상태는 다음 명령 하나로 확인한다.

```bash
python3 scripts/report_voice_pipeline_status.py \
  --catalog artifacts/voicebox-teacher-pure4000-2026-08-09/sentences-v4-diverse5000.jsonl \
  --output artifacts/voicebox-teacher-diverse1000-2026-08-09 \
  --target 5000 | jq .
```

보고기는 authoritative progress checker를 먼저 통과한 뒤 최근 50개 non-reused 생성 시간의
중앙값으로 남은 **Voicebox 생성 시간만** 추정한다. QC·패키징·Piper 학습 시간은 ETA에
포함하지 않는다. 마지막 WAV가 최장 job timeout과 LaunchAgent 재시작 여유를 합친 2,100초를
넘도록 갱신되지 않으면 `stale=true`로 표시한다.
`supervisor.ready`는 로컬 LaunchAgent 등록, plist의 compatibility entrypoint, 해당
entrypoint의 5,000 supervisor 위임, v4 catalog/5,000 runner와 finalizer wiring을 모두
현재 파일에서 확인한 경우에만 true다. 따라서 단순히 generator process가 살아 있다는
사실과 4,000 종료 후 자동 완주가 가능한 상태를 구분할 수 있다.
자동 generate/finalize/monitor 단계가 0이 아닌 코드로 끝나면 supervisor는
`pipeline_supervisor_failure.json`을 원자적으로 남기고 보고기는 최우선으로
`stage=pipeline_failed`와 실패 단계·exit code·UTC 시각을 노출한다. LaunchAgent는 다음
주기에 같은 안전한 단계를 재시도하며, 실제 성공한 경우에만 이전 실패 marker를 제거한다.

최종 산출물:

- `acoustic_qc.jsonl`, `acoustic_qc_summary.json`
- `catalog_diversity_report.json`
- `stt_qc.jsonl`, `stt_qc_summary.json`, `metadata_selected.csv`
- `piper_dataset/`, `voicebox-teacher-piper-dataset.tar.gz`
- `piper_handoff_ready.json`
- `piper_training_submitted.json` (원격 경로, PID, dataset SHA-256)

화자 일관성 gate는 Resemblyzer embedding cosine similarity `0.85` 미만 clip을 학습
metadata에서 제외한다. 제외 후 최소 4,000개가 남아야 하고, 전체 제외율은 2% 이하,
전체 분포의 p05는 0.90 이상, median은 0.93 이상이어야 한다. 이 기준은 동일 profile 9
기존 224개 clip의 실제 분포(min 0.8675, p05 0.9305, median 0.9552)를 바탕으로 잡았다.
기준 음성 SHA-256과 encoder ID를 checkpoint에 결합하므로 reference가 교체되면 이전
similarity를 재사용하지 않는다. 결과는 원격 run의 `speaker-consistency/`와 최종
`piper_training_results/speaker-consistency/`에 함께 보존된다.

원격 학습은 FP32, batch 4 설정을 유지하며 600/1,200/2,400/3,600/4,800 step에서
각각 checkpoint, ONNX, 평가 WAV, 자동 평가 JSON과 SHA-256을 남긴다. 평가는 일반 안내,
숫자·영문, 감탄, 질문, 긴 문장의 5개 고정 문장으로 수행하고 CER, 화자 유사도, 클리핑,
활성 음성 비율을 집계한다. 약 5,000개 중
학습/검증 분할을 고려하면 1,200 step이 대략 1 epoch에 해당한다. 이미 다른 Piper 학습이
실행 중이면 데이터는 보존하고 제출을 실패 처리하여 다음 감시 주기에 재시도하며, 동시에
두 학습을 실행하지 않는다.

새 후보끼리만 비교해서는 품질 향상을 증명할 수 없으므로 기존 청감 기준인
`ze69-blend-fp32 step420`도 같은 5문장·같은 reference로 다시 생성하고 `baseline/`에
평가 WAV, JSON, ONNX/config, SHA-256을 남긴다. 신규 후보의 hard gate가 통과하더라도
평균 CER `baseline + 0.02`, 최대 CER `baseline + 0.05`를 넘거나, 평균 화자 유사도가
baseline보다 낮거나, 최소 유사도가 `baseline - 0.02`보다 낮거나, 종합 점수가 baseline보다
낮으면 `objectiveNoRegression=false`로 승격 대상에서 제외한다.

학습 제출 뒤에도 같은 감시자가 완료된 step과 현재 step을 확인한다. 4,800 step 완료
마커가 생기면 각 단계의 ONNX와 5개 비교 WAV, 평가 JSON, 체크섬을
`piper_training_results/`로 회수하고 `ranking.json`을 만든다. 자동 순위는 모든 문장의
hard gate 통과 여부를 먼저 보고 CER와 화자 유사도를 함께 사용하지만, 최종 운영 모델은
반드시 회수된 비교 WAV를 청취한 뒤 확정한다. 회수가 끝나면
`piper_training_complete.json`에 원격 완료 정보와 후보 순위를 기록한다.

Acoustic QC에서 같은 PCM이 둘 이상의 문장에 나타나면 해당 그룹은 모두
`duplicate_audio`로 제외한다. 이는 5,000개 전체 작업을 중단시키지는 않는다. 이후 STT/CER
gate를 통과한 선택본이 **4,000개 이상**이고 그 PCM identity가 전부 고유할 때만 dataset을
패키징한다. 따라서 제거 여유분을 실제로 활용하면서 중복 음성이 학습에 들어가는 것은 막는다.
Dataset tarball은 고정 timestamp·owner·mode와 정렬된 파일 순서로 결정론적으로 생성하고,
임시 파일을 완전히 쓴 뒤 원자적으로 교체한다. Handoff와 원격 제출 전에는 catalog/manifest뿐
아니라 archive의 byte 수와 SHA-256도 재검증한다. 로컬 archive가 유실·손상되면 동일한
digest로 재구성할 수 있고, 다른 dataset이 기존 학습 제출에 이어 붙는 것은 거부한다. 제출
후 감시는 로컬 대용량 archive에 의존하지 않고 `submitted`와 원격 completion의 dataset SHA를
대조하므로, 로컬 archive나 생성 WAV를 잃어도 이미 실행 중인 학습 결과를 회수할 수 있다.
Supervisor도 `submitted` marker를 5,000개 로컬 진행률보다 먼저 확인하여 재생성을 시작하지
않는다.

### 후보 승인과 런타임 배포

자동 순위 1위가 곧 운영 모델은 아니다. 5개 고정 문장과 별도의 미확인 문장을 직접 듣고
hard gate를 통과한 step을 고른 뒤에만 불변 release bundle로 승격한다.

먼저 step과 자동 점수뿐 아니라 어느 후보가 기존 baseline인지도 숨긴 블라인드 청취표를 만든다.

```bash
python3 scripts/build_piper_listening_review.py \
  --completion artifacts/voicebox-teacher-diverse1000-2026-08-09/piper_training_complete.json \
  --output artifacts/voicebox-teacher-diverse1000-2026-08-09/listening-review
open artifacts/voicebox-teacher-diverse1000-2026-08-09/listening-review/index.html
```

같은 헤드폰·볼륨으로 후보 A~F를 듣고 정체성, 발음, 자연스러움, 울렁임·금속성·끊김
부재를 각각 확인한다. 하나라도 실패하면 승인하지 않고 데이터/학습 설정으로 돌아간다.
모든 기준을 통과한 후보가 있을 때만 결정 기록을 만든다.
선택 결과가 baseline이면 현재 모델이 더 낫다는 뜻이므로 새 candidate를 대신 승인하지 않는다.

```bash
python3 scripts/record_piper_listening_decision.py \
  --review-key artifacts/voicebox-teacher-diverse1000-2026-08-09/listening-review/review-key.json \
  --selected-label B \
  --approved-by owner \
  --identity-pass --pronunciation-pass --naturalness-pass --artifact-pass \
  --output artifacts/voicebox-teacher-diverse1000-2026-08-09/listening-decision.json
```

결정 파일에 공개된 `selectedStep`을 사용해 release를 승격한다.

```bash
python3 scripts/promote_piper_candidate.py \
  --completion artifacts/voicebox-teacher-diverse1000-2026-08-09/piper_training_complete.json \
  --decision artifacts/voicebox-teacher-diverse1000-2026-08-09/listening-decision.json \
  --step 2400 \
  --output-root artifacts/piper-releases
```

각 stage의 `SHA256SUMS`는 ONNX/config, `evaluation-suite.json`, 5개 청취 WAV를 하나로
봉인한다. 블라인드 청취표 생성과 승격 도구가 모두 이 전체 묶음을 재검증하므로 학습 뒤
점수나 문장·오디오 매핑만 바꾼 결과는 후보가 될 수 없다. 승격 도구는 객관 평가 hard pass, 명시적 청취
승인 기록의 review ID, 선택 step, model SHA와 네 가지 청취 gate를 모두 확인한다.
release alias에는 step과 모델 digest가 포함되므로 같은 이름으로
다른 weight를 덮어쓸 수 없다. 승인 JSON도 digest와 함께 release bundle에 복사하므로
원래 학습 작업 폴더를 보관처로 옮긴 뒤에도 어떤 청취 결정으로 승격했는지 감사할 수 있다.
Release manifest v2는 모델/config뿐 아니라 평가 JSON, 청취 결정, 5개 WAV 각각의 byte 수와
SHA-256을 열거한다. 이 inventory와 실제 release 파일 집합이 정확히 같아야 하므로 승격 뒤
품질 증거를 바꾸거나 미등록 파일을 끼워 넣은 bundle은 배포할 수 없다.

승인된 bundle은 다음 명령으로 `land`의 독립 Piper runtime에 배포한다.

```bash
./scripts/deploy_piper_release_to_land.sh \
  artifacts/piper-releases/gahyeon-voicebox-diverse5000-step2400-<sha12>
```

배포는 로컬과 원격에서 release 전체 inventory와 ONNX/config SHA-256을 재검증하고 원자적인 `current` symlink로
전환한다. Piper 서버도 시작할 때 파일을 직접 다시 해시하므로 환경변수에 적힌 digest만
신뢰하지 않는다. 전환 후 실제 한국어 문장을 합성해 mono PCM16 WAV, 최소 길이, 모델/config
digest 응답 header와 실시간 계수 `RTF <= 1.0`을 확인한다. 어느 단계든 실패하면 이전 승인
release로 자동 rollback하고, 이전 release도 health를 통과하지 못하면 서비스를 중지한다.

배포기는 업로드 전에 RAM·디스크·GPU·동시 학습을 검사하고, 원격 digest를 다시 확인한 뒤
`current` symlink를 원자적으로 교체한다. systemd runtime은 `127.0.0.1:18767/health`에서
alias와 model SHA를 반환한다. 새 모델이 정해진 시간 안에 healthy가 되지 않거나 응답 digest가
다르면 직전 release로 rollback하며, 직전 release도 실패하면 서비스를 중지한다. Backend에는
모델 파일 경로가 아니라 이 runtime의 `/synthesize` endpoint와 release alias만 설정한다.

Piper는 `land`의 loopback에만 bind하고 기존 STT와 같은 방식으로 `zeze`의
`127.0.0.1:18767`에 reverse SSH tunnel을 연다. 배포 성공은 `land` 로컬 health뿐 아니라
`zeze`에서 터널을 통과한 alias/SHA health까지 일치해야 한다. Backend/Unreal 음성 설정은
다음과 같이 release alias를 사용한다.

```env
TTS_PROVIDER=custom
ASSISTANT_TTS_PROVIDER=custom
TTS_FALLBACK_TO_EDGE=false
CUSTOM_TTS_ENDPOINT=http://127.0.0.1:18767/synthesize
CUSTOM_TTS_MODEL=gahyeon-voicebox-diverse5000-step2400-<sha12>
CUSTOM_TTS_MODEL_SHA256=<release.json modelSha256>
CUSTOM_TTS_CONFIG_SHA256=<release.json configSha256>
CUSTOM_TTS_SPEAKER_ID=gahyeon
CUSTOM_TTS_FORMAT=wav
```

`ASSISTANT_TTS_PROVIDER`를 함께 바꾸지 않으면 Unreal/음성 Assistant 경로는 기존 provider를
계속 사용한다. 정체성 음성에서 generic Edge 목소리로 조용히 바뀌는 것을 막기 위해 운영
기본은 `TTS_FALLBACK_TO_EDGE=false`로 두고 실패를 계측·표현한다.

Piper runtime은 합성을 한 번에 하나만 허용하고 대기열을 서버 내부에 무제한 쌓지 않는다.
이미 합성 중이면 짧은 admission timeout 뒤 HTTP 429를 반환해 Backend의 generation 취소와
backpressure가 관측 가능하게 유지된다. 기본은 CPU 추론이며, 학습/그래픽 작업과 GPU를 공유할
필요가 없는 운영 환경에서 검증한 뒤에만 `PIPER_USE_CUDA=true`를 사용한다.
운영 Gahyeon Core에는 두 SHA 환경변수를 반드시 고정한다. 그러면 TTS client는 응답의
`X-Piper-Model`, `X-Piper-Model-SHA256`, `X-Piper-Config-SHA256`을 재생 전에 검사하며,
터널이나 배포 대상이 다른 release를 반환하면 오디오를 폐기한다. SHA pin을 비워 두는 동작은
Piper identity header가 없는 일반 custom provider와의 호환용이다. 일반 네트워크/provider
장애에는 설정에 따라 Edge fallback을 사용할 수 있지만, identity 불일치는 승인되지 않은 다른
목소리를 내보내는 것보다 무음 실패가 안전하므로 fallback하지 않는다.
응답 본문은 읽는 단계에서 32 MiB로 제한하며, `CUSTOM_TTS_FORMAT=wav`이면 호환되는
Content-Type, RIFF/WAVE header와 비어 있지 않은 `data` chunk를 확인한 뒤에만 임시 파일로
기록한다. 따라서 과대 응답이나 HTML/JSON 오류 본문이 오디오 재생 경로로 들어오지 않는다.
provider 검증 뒤의 공용 speech adapter와 Desktop Browser/Electron transport에는 더 좁은
16 MiB 상한을 둔다. Adapter는 파일을 `readAllBytes`하기 전에 크기를 검사하고 임시 파일을
삭제하며, Desktop은 선언된 `Content-Length`와 실제 chunk 누적량을 모두 제한한다.
