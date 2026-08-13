# Microphone permission and device gate

`UGahyeonVoiceInputComponent`의 capture open 성공은 권한 승인을 뜻하지 않는다. 제품 빌드는
첫 음성 사용 전에 사용 목적을 표시하고 OS 권한 결과를 확인한 다음
`StartMicrophoneCapture()`를 호출해야 한다.

## 플랫폼 준비

- Windows: 설정의 Microphone privacy에서 desktop app 접근이 허용되어야 한다. 입력 장치가
  없거나 독점 사용 중이면 `capture_open_failed`를 사용자에게 장치 선택/재시도 UI로 보여준다.
- macOS: packaged application의 `Info.plist`에 `NSMicrophoneUsageDescription`을 넣고 서명된
  실제 app에서 최초 권한 prompt와 거부 후 재시도를 검증한다.
- Android를 지원할 경우: manifest의 `android.permission.RECORD_AUDIO`와 runtime permission을
  모두 처리한다. 현재 Vertical Slice의 목표 플랫폼에는 포함하지 않는다.

권한 거부, 장치 제거, callback overflow는 Cognition이나 캐릭터 Idle을 중단시키지 않는다.
UI는 `GetLastCaptureError()`, `GetCaptureOverflowCount()`,
`GetSttBackpressureCount()`를 읽어 상태를 보여준다. 자동으로 무한 재시도하지 않으며 사용자가
권한이나 장치를 변경한 뒤 명시적으로 다시 시작한다.

## 실기기 합격 조건

1. 허용/거부/장치 없음 각각에서 Game Thread stall 0회
2. 거부 상태에서도 text conversation과 local Idle/Reflex 정상 동작
3. 10분 capture에서 callback overflow 0, mailbox drop 0
4. 48 kHz mono/stereo 입력에서 RMS가 유한한 0..1 범위
5. capture stop 뒤 callback 및 STT worker가 모두 종료되고 PIE 재시작 시 중복 stream 0
