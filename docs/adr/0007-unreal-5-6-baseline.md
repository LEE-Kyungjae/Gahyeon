# ADR-0007: Unreal Engine 5.6을 첫 Stage 기준으로 고정

- 상태: Accepted
- 결정일: 2026-08-11

## 맥락

실제 Unreal project 생성을 계속 보류하면 RuntimeCore와 Backend 계약이 엔진 경계에서
검증되지 않는다. 반대로 `Latest`를 따라가면 C++ API, asset serialization, MetaHuman
workflow가 바뀔 때 재현 가능한 build 기준이 사라진다.

Epic은 UE 5.6부터 MetaHuman Creator를 Unreal Editor 안으로 통합했고, 5.6 이전과 이후의
MetaHuman workflow가 명확히 갈린다.

## 결정

첫 Gahyeon Stage source project와 VS-2~VS-9는 Unreal Engine 5.6을 기준으로 한다.

- `.uproject`의 `EngineAssociation`은 `5.6`이다.
- Target의 build/include order도 5.6으로 고정한다.
- Engine upgrade는 별도 ADR, clean build, protocol automation test, packaged smoke 이후에만 한다.
- MetaHuman plugin은 실제 Editor 설치에서 호환성을 확인하기 전까지 필수 project plugin으로
  선언하지 않는다. Control Rig, Full Body IK, WebSockets, Enhanced Input만 source scaffold에
  활성화한다.
- source scaffold 정적 검증과 실제 Unreal Editor compile/open 증거는 구분한다.

## 결과

좋은 점:

- source와 build target이 재현 가능한 기준점을 갖는다.
- 5.6 이후의 in-editor MetaHuman workflow를 전제로 hero asset pipeline을 설계할 수 있다.
- 설치된 Unreal 머신이 생기는 즉시 VS-2 compiler gate를 실행할 수 있다.

비용과 제한:

- 현재 개발 머신에는 Unreal이 없어 UHT/UBT 컴파일을 아직 증명하지 못한다.
- 최신 Engine 기능을 자동으로 따라가지 않는다.
- 5.6 plugin patch 차이는 실제 설치 환경에서 추가로 고정해야 한다.

## 공식 근거

- [Unreal Engine 5.6 Documentation](https://dev.epicgames.com/documentation/unreal-engine/unreal-engine-5-6-documentation?application_version=5.6&lang=en-US)
- [MetaHuman workflow changes](https://dev.epicgames.com/documentation/metahuman/metahuman-workflow-changes?lang=en-US)
- [MetaHuman plugins overview](https://dev.epicgames.com/documentation/metahuman/metahuman-plugins-overview-in-unreal-engine)
