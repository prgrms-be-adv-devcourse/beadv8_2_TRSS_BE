# 문서 위치 · 건너뛸 것 · 참고 힌트

`/confluence-check`는 연결표 없이, 문서 3개의 **목차(제목)**를 읽고 바뀐 코드와 관련된 섹션을 직접 고른다. 이 파일에는 거의 바뀌지 않는 것만 적는다.

## 문서 위치

Confluence 주소는 여기 적지 않고, 레포의 이동 안내 파일에서 읽는다 (`이 문서는 Confluence로 이동했습니다 → <URL>` 줄).

| 문서 | 주소를 읽을 파일 |
| --- | --- |
| 기획서 | `docs/palette_proposal.md` |
| 정책 | `docs/palette_policy.md` |
| 개발 가이드 | `docs/palette_development_guide.md` |

## 문서와 상관없는 변경 (건너뜀)

- 테스트 코드(`src/test/**`)만 바뀐 경우
- `infra/**`, `.github/**`, `Dockerfile` 같은 배포 설정
- `.claude/**`, `docs/**`
- 이름 바꾸기·주석·로그 문구처럼 동작과 규칙이 같은 리팩터링

## 목차만으로는 놓치기 쉬운 것 (참고 힌트)

제목만 보고 고르면 빠뜨리기 쉬운 연결이다. 목록에 없어도 관련 있으면 고른다.

- 상태 enum(`*Status.java`, `*Type.java`)이 바뀌면 → 정책의 **상태코드** 섹션도 본다
- 컨트롤러(`in/*Controller.java`)의 경로·권한이 바뀌면 → 기획서의 **API 명세서**도 본다
- `application*.yaml`의 숫자 값(기간·횟수·금액)이 바뀌면 → 그 모듈의 **정책** 섹션을 본다
- 서비스 간 호출(HTTP 어댑터·내부 API·이벤트·Outbox)이 바뀌면 → 개발 가이드 **6. 컨텍스트 간 통신**을 본다
