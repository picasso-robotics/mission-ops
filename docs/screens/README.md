# mission-ops 화면 문서

이 폴더는 관리 화면(`ui/`)의 문서 묶음입니다. 리터치는 끝났고 지금 UI 가 최종입니다.

이 폴더에는 다음 항목을 둡니다.
- 지금 화면의 설계서
- 개발 내역
- 대표 스크린숏

문서를 읽는 사람은 다음과 같습니다.
- 화면을 고치는 개발자
- 리뷰어
- 저장소 소유자

## 기준 버전

| 구분 | 기준 |
|---|---|
| picasso-ops `main` | `bb1d8b5`(PR #23 머지, 2026-10-13, 층별 용어 통일) |
| 서브모듈 picasso | `74e4d3d` |

문서의 사실은 이 버전의 다음 대상에서 확인했습니다. 행 번호는 적지 않습니다.
- `ui/src/**`
- 시험(`ui/src/**/*.test.tsx`, `ui/e2e/*.spec.ts`)
- 운영 서비스 코드

## 이 폴더에 있는 것

| 파일 | 내용 |
|---|---|
| `README.md` | 이 문서입니다. |
| `screen-design.md` | 화면 설계서입니다. 제품과 사용자, 전역 구조, 아홉 영역별 화면, 화면을 바꿀 때 지킬 것(시험이 기대는 이름과 글자), 그림 목록을 다룹니다. |
| `development.md` | 화면 개발 내역입니다. PR #1~#23 이력, 바탕 파일, 컴포넌트 42개, 상태와 데이터 읽기, 시험, 로컬 실행과 CI를 다룹니다. |
| `img/NN-*.png`, `img/INDEX.md` | 대표 스크린숏 21장(데스크톱 1440×900 19장, 390px 폭 2장)과 그 목록입니다. 목록은 촬영 스크립트가 쓰며 파일마다 영역, 구역, 모드, 상태, 만든 방법이 있습니다. |
| `style-preview/` | #18 작업 때 남긴 화면 기록과 저장소 README 의 화면 그림입니다. `*-mock.png` 는 `ui/scripts/*.mjs` 가 API 대역 응답으로 찍은 것(#18 기록)이고, `*-fullstack.png` 는 Playwright `lifecycle.spec.ts`·`station.spec.ts` 가 돌 때마다 다시 찍습니다. 자체 `style-preview/README.md` 가 있습니다. |
| 날짜 붙은 문서들 | 아래 «그때의 구현 기록»을 가리킵니다. |

## 읽는 법

«…» 안의 글자는 화면이나 코드 글자 그대로이고, `${…}` 는 값이 끼워지는 자리입니다.

파일 위치는 따로 적지 않으면 `ui/src/components/` 기준입니다. 다음 파일은 `ui/src/` 바로 아래에 있습니다.
- `App.tsx`
- `api.ts`
- `areas.ts`
- `labels.ts`
- `poll.ts`
- `jobOrderDraft.ts`
- `manualRequests.ts`
- `missionTemplates.ts`
- `styles.css`

층 이름은 다음 구분을 가려 씁니다.
- 외부 주문
- 호출 계약
- 작업 요청
- 임무(임무 정의, 임무 버전)
- 작업 지시
- 실행·단위·태스크

화면 글자가 이 층 이름과 다른 자리(예: 작업 지시 폼의 버튼이 실제로는 작업 요청을 만든다)는 설계서가 화면 글자를 그대로 옮기고 그 사실을 적습니다.

스크린숏 이름은 `NN-영역-상태[-mock].png` 입니다. `-mock` 은 응답 대역으로 그린 화면입니다.

## 스크린숏 다시 찍기

선행 도구는 저장소 `README.md` 의 «선행 도구» 와 같습니다.
- Docker
- JDK 21 이상
- Node 22

포트는 루트 `.env` 의 다음 항목을 읽으며, 없으면 설정이 바로 멈춥니다.
- `OPS_PORT`
- `REGISTRY_PORT`
- `HOST_PORT`

순서는 다음과 같습니다.
1. 저장소 루트에서 배포본 빌드 `./gradlew :site:installDist :mission-host:installDist :ops-service:installDist`
2. `cd ui && npm ci && npx playwright install chromium`
3. `cd ui && npx playwright test -c playwright.screens.config.ts`

이 설정(`ui/playwright.screens.config.ts`)은 시험 디렉터리 `./screens` 의 `capture.spec.ts` 를 돕니다. 다음 조건으로 찍어 `docs/screens/img/` 와 `img/INDEX.md` 를 새로 씁니다.
- 1440×900
- 배율 1
- 밝은 색
- `ko-KR`

이 설정은 `lifecycle.spec.ts` 와 같은 스택(Mock MES 콜백, host-link 프록시, 재기동 가능한 실행 호스트 포함)을 띄웁니다. 한 번 찍는 데 약 7분 걸립니다. CI 의 기본 `npx playwright test` 는 이것을 돌지 않습니다.

`style-preview/*-fullstack.png` 는 `npx playwright test`(lifecycle)와 `npx playwright test --config=playwright.station.config.ts`(station)를 돌리면 다시 찍힙니다.

다시 찍으면 다음 값이 바뀝니다.
- 시각
- 인스턴스 id
- 작업 지시 id

설계서 본문은 스크린숏의 이런 값에 기대지 않습니다.

## 그때의 구현 기록

아래 문서는 PR #18(2026-10-12 머지) 작업 중 단계마다 남긴 기록입니다. 그 시점의 계획·구현·검증을 적은 것이며 지금 사양이 아닙니다. 용어집 정리 이전의 옛 이름과 그때의 수치가 남아 있습니다. 지금 화면은 `screen-design.md` 가 기준입니다.

파일과 다룬 것은 다음과 같습니다.
- [`style-refresh.md`](style-refresh.md): 첫 스타일 적용
- [`onboarding-refresh.md`](onboarding-refresh.md): 로봇 도입 여정 개편
- [`mission-refresh.md`](mission-refresh.md): 임무 관리 화면 개편(임무 목록, 호출 연결, 변환 미리보기)
- [`ux-review-2026-10-10.md`](ux-review-2026-10-10.md): 운영·임무·설정·이력 후속 UX 검토(제안)
- [`upstream-workflow-plan-2026-10-10.md`](upstream-workflow-plan-2026-10-10.md): 상위 시스템 중심 운영과 연계 계획(최초 계획)
- [`upstream-receipts-2026-10-11.md`](upstream-receipts-2026-10-11.md): 외부 주문의 작업 요청 영속 기록, 중복 방지, 실행 추적
- [`upstream-results-2026-10-11.md`](upstream-results-2026-10-11.md): 상위 결과 버전과 수신 확인
- [`upstream-followups-2026-10-11.md`](upstream-followups-2026-10-11.md): 상위 흐름 후속 구현과 검증
- [`manual-receipts-2026-10-11.md`](manual-receipts-2026-10-11.md): 수동 작업을 작업 요청 영속 기록으로 통합
- [`mission-interface-implementation.md`](mission-interface-implementation.md): 호출 계약 저장·공개·실행 연결
- [`interface-eligibility-2026-10-11.md`](interface-eligibility-2026-10-11.md): 호출 계약 입력으로 실행 전 배정 확인
- [`mission-completion-2026-10-11.md`](mission-completion-2026-10-11.md): 새 임무 생성부터 실행까지
- [`reliability-implementation-2026-10-11.md`](reliability-implementation-2026-10-11.md): 결과 전달 신뢰성(outbox, 커서)
- [`manufacturing-scenario-2026-10-11.md`](manufacturing-scenario-2026-10-11.md): 제조 스테이션 통합 시나리오와 작업 요청 비교
- [`shared-station-orchestration-2026-10-12.md`](shared-station-orchestration-2026-10-12.md): 공유 스테이션 오케스트레이션
- [`mission-resource-enforcement-2026-10-12.md`](mission-resource-enforcement-2026-10-12.md): 임무 필수 자원을 모든 호출 경로에 적용
