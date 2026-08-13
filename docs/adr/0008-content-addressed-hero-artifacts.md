# ADR-0008: Hero binary는 Git 밖의 content-addressed artifact로 관리

- 상태: Accepted
- 결정일: 2026-08-12

## 맥락

Gahyeon 한 캐릭터만 제작하더라도 sculpt/DCC 원본, 4K~8K texture, strand groom,
Unreal bulk data와 renderer별 package는 일반 source repository에 넣기 어렵다. 현재 개발
환경에는 Git LFS가 설치되어 있지 않고, `../zaeze`의 asset factory는 2D product media
lifecycle이므로 3D 제작 원본의 정본으로 사용할 수 없다. 저장 제품을 지금 임의로 고르면
승인 manifest가 특정 서비스 URL과 자격 증명 방식에 결합된다.

## 결정

Hero 대용량 binary의 논리적 정본은 SHA-256 content address로 식별하는 외부 artifact
storage에 둔다. S3 호환 object storage, 사설 artifact server 등 실제 provider 선택은 배포
인프라 결정으로 남기되 다음 경계는 지금부터 고정한다.

- Git에는 schema, ADR, 제작/승인 규칙, digest가 포함된 manifest와 작은 텍스트 evidence를 둔다.
- 원본 reference, `.blend`/sculpt source, texture/groom source, `unreal-content-zip`, VRM/GLB와
  대형 시각 evidence는 artifact storage에 둔다.
- 저장 key는 최소한 `gahyeon/<kind>/<sha256>/<filename>` 형태의 불변 주소를 사용한다.
- 승인 manifest의 `uri`는 build workspace로 materialize된 상대 경로다. storage URL이나
  만료되는 signed URL을 정체성으로 사용하지 않는다.
- 다운로드/동기화 단계가 bytes와 SHA-256을 확인한 뒤에만 workspace에 원자적으로 배치한다.
- `hero-engine` ZIP은 추가로 내부 `hero-content-manifest.json` inventory를 통과해야 한다.
- 승인 뒤 수정은 overwrite가 아니라 새 digest와 새 candidate lifecycle로 처리한다.
- 로컬 다운로드는 `artifacts/hero-cache/`에 두며 Git에 포함하지 않는다.

## 결과

장점:

- Git history와 clone 크기를 대형 binary로 팽창시키지 않는다.
- 저장 provider를 바꿔도 Hero manifest의 승인 identity는 유지된다.
- Desktop, Unreal, Looking Glass package가 동일한 provenance chain을 공유하면서 각자 다른
  binary를 사용할 수 있다.
- 유출되기 쉬운 signed URL과 credential이 repository manifest에 남지 않는다.

비용과 제한:

- 실제 G1 binary publish 전 object storage provider, retention, backup, 접근 권한을 정해야 한다.
- CI와 새 개발 머신은 별도 materialization credential이 필요하다.
- 현재 `artifacts/gahyeon-ch`의 사용자 원본은 아직 로컬 자료이며, 외부 저장소 백업 완료를
  증명하기 전 삭제하거나 이동하지 않는다.

## 기각한 대안

- 일반 Git commit: binary delta와 clone 비용이 크고 승인 후 교체 방지가 약하다.
- 현 상태의 Git LFS: 클라이언트가 설치되어 있지 않고 remote quota/retention이 결정되지 않았다.
- Zaeze media catalog 직접 재사용: 3D dependency bundle과 DCC source lifecycle 계약이 없다.
- 서비스 URL을 manifest 정체성으로 사용: URL 만료·이동이 승인 identity를 깨뜨린다.
