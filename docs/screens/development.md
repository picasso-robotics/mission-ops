# mission-ops 화면 개발 내역

이 문서는 관리 화면(`ui/`)을 다룹니다.
다음 내용을 적습니다.
- 어느 PR 에서 무엇을 얻었는지
- 어떤 파일이 무엇을 하는지
- 상태와 데이터를 어떻게 읽고 어떻게 시험하는지

기준은 picasso-ops `main` = `bb1d8b5`(PR #23 머지, 2026-10-13)입니다.
화면 설계는 `screen-design.md`입니다.
PR 표의 머지 커밋은 `git log --merges --oneline`으로 확인한 값입니다.
제목과 날짜는 `gh pr list --state merged`로 확인한 값입니다.

## 목차

1. PR 이력
2. 바탕 파일
3. 컴포넌트
4. 상태와 데이터 읽기
5. 시험
6. 로컬 실행과 CI

## 1. PR 이력

| PR | 머지 커밋 | 머지 날짜 | 단계 | 제목(원문) |
|---|---|---|---|---|
| #1 | `b4fcc56` | 10-07 | S1a 골격 | `feat(s1a): picasso 서브모듈 고정 및 site·ops-service·ui·e2e 골격 추가` |
| #2 | `d94a5e6` | 10-07 | S1b 생애주기 | `feat(s1b): 로봇 선언·퇴역·복귀 조작과 막힘·거절 판정, 기체 상세 화면, Playwright CI 추가` |
| #3 | `2a8c617` | 10-07 | S1c 어댑터 | `feat(s1c): 어댑터 제품·빌드·인스턴스 등록 조작과 거절 판정, 어댑터 화면, Playwright 어댑터 흐름 추가` |
| #4 | `3f00dfb` | 10-07 | 설계 문서 | `docs(specs): P2·S1d 설계 스펙과 P2a 구현 계획 추가` |
| #5 | `f84e095` | 10-07 | 계획 문서 | `docs(plans): P2b 개정판·바인딩 REST 구현 계획과 실행 결과` |
| #6 | `694ea9e` | 10-07 | S1d 시운전 | `feat(s1d): 개정판 제출·시험·활성화·바인딩·명칭 기록과 시운전 판정, 프로파일 화면, Playwright 흐름 추가` |
| #7 | `7254521` | 10-08 | 용어 정리 | `docs(terms): 용어집 대응표에 맞춰 문서와 화면 문구의 옛 이름 교체` |
| #8 | `16de942` | 10-08 | S2 현장 설정 | `feat(s2): 현장 설정 버전 기록과 연결 기준 시간 변경, 막힘 근거 버전 추가` |
| #9 | `f7ca937` | 10-08 | 설계 문서 | `docs(plans): P3 임무 정의 데이터와 임무 버전 스펙·계획·실행 결과 추가` |
| #10 | `d16b19b` | 10-08 | S3a 실행 호스트 | `feat(mission-host): 미들웨어 실행 호스트와 운영 영역의 작업 지시·실행 추가` |
| #11 | `b2cce34` | 10-08 | S3b 임무 버전 | `feat(mission-host): 임무 버전 저장·JSON 편집기·모의 실행·활성화 추가` |
| #12 | `e482c33` | 10-09 | S3c 현장 시간값 | `feat(mission-host): 현장 시간값 설정 버전의 실행 호스트 적용과 인시던트 봉인` |
| #13 | `8ed1b91` | 10-09 | S4a 장애·인시던트 | `feat(mission-host): 장애 주입·인시던트 화면·운영자 보류 해소` |
| #14 | `9606364` | 10-09 | S4b 재기동 복원 | `feat(mission-host): 호스트 재기동 뒤 실행 복원과 중복 명령·중복 작업 응답 막기` |
| #15 | `443a14c` | 10-09 | 화면 설계서 첫 버전 | `docs(screens): 디자이너 리터치용 화면 설계서와 스크린숏` |
| #16 | `2a8c94e` | 10-09 | README | `docs(readme): README 전면 재구성 및 레퍼런스 문서 분리` |
| #17 | `71345ac` | 10-09 | README | `docs(readme): 다크 모드 다이어그램 추가 및 테마별 이미지 전환 적용` |
| #18 | `3bbcb9c` | 10-12 | UI 재구성 | `feat(ops,ui): 미션 계약과 영속 접수 및 공유 자원 운영 체계` |
| #19 | `88501ac` | 10-12 | README | `docs(readme): UI 초기 화면 및 Mock MES 실행 절차 오류 정정` |
| #20 | `efc578f` | 10-12 | 작업 요청 수정 | `fix(requests): 고아 접수 정리와 자원 요구 등록 기준 완화 및 현장 확인 해제 연동` |
| #21 | `3325a54` | 10-12 | 공유 자원 수정 | `fix(stations): 로봇 물리 상태 매핑 정정과 재작업 관문 및 결과 버전 기준선 적용` |
| #22 | `2ca84aa` | 10-12 | 작업 요청 수정 | `fix(requests): 미배정 재평가와 자원 요구 등록 경합 해소 및 권한 확인 거부 접수 종결` |
| #23 | `bb1d8b5` | 10-13 | 용어 통일 | `refactor(ui,ops): 층별 표준 용어 반영 및 화면 노출 문자열 정비` |

머지 날짜는 모두 2026년입니다.
제목 원문에는 그 당시의 옛 이름이 남아 있습니다.
지금 이름과의 대응은 다음과 같습니다.

| 옛 이름 | 지금 이름 |
|---|---|
| 거절 | 거부 |
| 개정판 | 리비전 |
| 미션 | 임무 |
| 접수 | 작업 요청 |

PR #18은 지금 UI의 바탕입니다.
5영역 화면을 다음 9영역으로 다시 짰습니다.
- 운영 개요
- 작업 요청
- 공유 자원
- 운영
- 로봇
- 임무
- 현장 설정
- 이력
- 검증 도구

PR #18의 머지 전 커밋은 다음과 같습니다.
- `0cb0779`: 운영 서비스·실행 호스트·현장 쪽 기능(임무 계약, 작업 요청 영속 기록, 공유 자원 조정)
- `4e8a7d0`: 화면 개편
- `b2ee4ca`: 제조 시나리오와 자원 실행 E2E
- `4a06131`: 운영 개편 명세와 검증 화면 기록
- `92bc20c`: 브라우저 시나리오별 스택 실행 분리(CI)
- `ee6f7ae`: 최종 브라우저 회귀 결과
- `6b7aed9`: E2E 기대값 갱신
- `bbaf5da`: README

단계별 기록은 `docs/screens/`의 날짜 붙은 문서들이며 다음 항목을 다룹니다.
- 스타일
- 로봇 도입 여정
- 임무 관리
- 호출 계약
- 상위 작업 요청
- 결과 신뢰성
- 제조 시나리오
- 공유 스테이션
- 임무 필수 자원

PR #20부터 PR #22까지는 PR #18 뒤의 고침입니다.
다음 내용을 다룹니다.
- 남은 작업 요청 정리와 자원 요구 등록 조건
- 공유 스테이션의 물리 상태 매핑·재작업 게이트·결과 버전 기준선
- 미배정 재평가와 자원 요구 등록의 경합

PR #23은 층 이름 통일입니다.
화면 글자와 운영 서비스 문구를 층 이름과 용어집 새 이름에 맞췄습니다.
층 이름은 다음과 같습니다.
- 외부 주문
- 호출 계약
- 작업 요청
- 임무
- 작업 지시
- 실행

반영된 예시는 다음과 같습니다.
- 버튼 «작업 요청 내기»
- 버튼 «호출 계약으로 작업 요청»
- 지표 카드 «총 작업 요청»·«작업 요청 거부»
- 비교 화면 h2 «호출 계약과 실행 근거 비교»
- 화면에서 «업무» 를 임무 뜻으로 쓰던 자리 정리
- 거부 문구 «거부»

공유 자원 메뉴 아이콘이 안 그려지던 것과 조사 오류 하나도 함께 고쳤습니다.

## 2. 바탕 파일

| 파일 | 하는 일 |
|---|---|
| `index.html` | `lang="ko"`, 제목 `Picasso Ops · Robot Operations`, 파비콘 |
| `src/main.tsx` | React 진입점 |
| `src/App.tsx` | 껍데기(사이드바·상단 바·영역 제목), 해시 라우팅, 세션(모드·사용자) 상태, 다섯 조회 묶음 읽기, 영역 전환 |
| `src/areas.ts` | 아홉 영역의 id·글자·순서 |
| `src/api.ts` | 운영 서비스 호출 전부(1,406행). 요청 헤더 `X-Ops-Mode`·`X-Ops-User`, 결과 분류 `deliver`, 호출 계약 계열 `interfaceRequest` |
| `src/labels.ts` | 코드 값 → 화면 글자 표(설계서 §2.7) |
| `src/poll.ts` | 주기 상수 `POLL_MS`, `ELIGIBILITY_DEBOUNCE_MS`, `SIGNAL_SETTLE_MS` |
| `src/jobOrderDraft.ts` | 작업 지시 폼의 초안과 보내기 전 검사 |
| `src/manualRequests.ts` | 수동 작업 요청의 sessionStorage 보관 |
| `src/executionHistory.ts` | 5초 조회에 불러온 이전 실행 페이지 결합 |
| `src/missionTemplates.ts` | 새 임무 생성의 시작 템플릿 둘 |
| `src/styles.css` | 스타일 한 파일(257행), `:root` 토큰, 반응형 |
| `public/fonts/` | Pretendard Variable 과 라이선스 고지 |
| `src/setupTests.ts`, `src/testing/fakeOps.ts` | vitest 설정과 운영 서비스 가짜 응답 |

## 3. 컴포넌트

`ui/src/components/`의 시험 아닌 `.tsx` 43개는 다음과 같습니다.

| 파일 | 하는 일 | 영역 |
|---|---|---|
| AdapterForms | 어댑터 제품·빌드·인스턴스 등록 폼 셋 | 로봇 |
| AdaptersSection | 어댑터 관리 탭의 인스턴스·제품·빌드 표 | 로봇 |
| CellBand | 셀 대역 자리·신호 표와 신호 버튼 | 운영 |
| CollectorStatus | 작업 응답 수집기 상태 | 운영 개요 |
| CommissioningCards | 바인딩·사이트 명칭·시운전 카드 | 로봇 |
| DeclareForm | 기체 선언 폼 | 로봇 |
| EligibilityTable | 기체별 배정 가능 아홉 열 표 | 운영, 임무 |
| ExecutionControls | 운영자 실행 취소 폼 `CancelExecutionForm`(실행 목록 아래), 잔여 파지 확인 표와 기체 빈손 확인 폼 `ResidualHolds`(배정 가능 아래) | 운영 |
| ExecutionList | 실행 목록과 재기동 복원 보고, 최근 종결 이력 행과 «이전 실행» 영역, 물리 상태 칸의 운영자 취소 표시, 취소 폼 자리 | 운영 |
| FaultPanel | 장애 주입 | 검증 도구 |
| FindingCard | 막힘·거부 카드 | 로봇, 현장 설정, 임무 |
| HistoryArea | 조작 기록 | 이력 |
| IncidentSection | 인시던트 목록·상세·운영자 판단 | 운영 |
| InterfaceDeployment | 호출 계약의 실행 배포 | 임무 |
| JobOrderFormView | 작업 지시 폼 | 운영 |
| JobOrderNotice | 작업 지시 제출 결과 | 운영 |
| JobResponseLog | 작업 응답 송신 기록 | 운영 |
| ManufacturingValidation | 제조 시나리오 검증, 두 작업 요청 비교 | 검증 도구 |
| MissionCreator | 템플릿으로 새 임무 생성 | 임무 |
| MissionDefinitionEditor | 임무 정의 편집기, 버전 이력, 초안 목록 | 임무 |
| MissionInterfaces | 버전별 호출 계약 작성·선택·공개, 호출 계약으로 작업 요청 | 임무 |
| MissionNotice | 임무 조작 결과 | 임무 |
| MissionResourceSettings | 임무 필수 자원 등록, 호출 전 자원 안내 `MissionResourceNotice` | 공유 자원, 운영, 임무 |
| MissionsArea | 임무 영역 껍데기와 패널 넷, 지원 임무 카드, 변환 미리보기 `ContractPreview` | 임무 |
| MockRunReport | 모의 실행 보고 | 임무 |
| ModeSwitch | 모드·사용자 | 상단 바 |
| OnboardingGuide | 로봇 도입 준비 | 로봇 |
| OperationsArea | 운영 영역 껍데기, 읽기 주기, 수동 작업 요청 | 운영 |
| OutcomeNotice | registry 조작 결과 | 로봇, 현장 설정 |
| OverviewArea | 운영 개요 | 운영 개요 |
| ProfilesSection | 프로파일 리비전 표와 제출 | 로봇 |
| RegistryBanner | 전체 상태 띠 | 전역 |
| RejectionSummary, UnassignedSourceMissing | 호스트 거부 사유·출발 자리·대체 위치·조치 제안 요약(대체 위치·출발 자리 문구 함수는 labels.ts 로 이동) | 작업 요청 |
| RequestRecovery | 미배정 재평가 | 작업 요청 |
| RequestReview | 현장 확인 기록 | 작업 요청 |
| RobotDetail | 기체 상세 | 로봇 |
| RobotsArea | 로봇 영역 껍데기와 하위 탭, 기체 목록 | 로봇 |
| SignalNotice | 신호 조작 결과 | 운영 |
| SiteArea | 현장 설정 | 현장 설정 |
| SourceConfirmation, SourceConfirmationHistory | 출발 미관측 확인 대기 구역과 운영자의 출발 자리 확인 기록, 출발 자리 확인 이력 | 작업 요청 |
| StationWorkspace | 공유 스테이션 점유·작업 요청·작업 근거 | 공유 자원 |
| StoredResultSummary | 저장 결과의 완료 근거 | 작업 요청, 검증 도구 |
| WorkRequests, UpstreamLink | 통합 작업 요청 목록과 상세, 상위 시스템 대체 연결 구역 둘(`UpstreamLink` 는 `WorkRequests.tsx` 안에 있고 새 파일은 없음) | 작업 요청, 운영 |

PR #18에서 새로 생긴 컴포넌트는 다음 14개입니다.
- OverviewArea
- CollectorStatus
- WorkRequests
- RequestRecovery
- RequestReview
- StoredResultSummary
- StationWorkspace
- MissionResourceSettings
- OnboardingGuide
- MissionCreator
- MissionInterfaces
- InterfaceDeployment
- MissionDefinitionEditor(옛 MissionsArea 의 편집기 몸통을 옮김)
- ManufacturingValidation

나머지 26개는 PR #14까지 있던 것입니다.
PR #18에서 다음 컴포넌트가 바뀌었습니다.
- RobotsArea(하위 탭)
- MissionsArea(패널 껍데기)
- OperationsArea(수동 작업 요청)
- EligibilityTable(아홉 열)
- ExecutionList(일지 차단)
- HistoryArea(region 과 h2)

## 4. 상태와 데이터 읽기

상태 관리 라이브러리와 라우터 라이브러리는 없습니다. React 19 의 `useState`·`useEffect` 와 컴포넌트 안의 `fetch` 로 읽습니다.

세션은 `App.tsx` 의 `useState` 하나(`{mode, user}`, 처음 값 엔지니어·`local`)로 관리합니다. 모든 요청에 `X-Ops-Mode`·`X-Ops-User` 헤더(`api.ts` 의 `actorHeaders`)를 싣습니다. 세션은 저장하지 않습니다.

`App.tsx` 는 다음 다섯 경로를 `Promise.all` 로 묶어서 읽습니다.
- `/api/robots`
- `/api/adapters`
- `/api/profiles`
- `/api/operations`
- `/api/site-settings`

읽는 시점은 다음과 같습니다.
- 5초마다
- 세션이 바뀔 때
- 조작 뒤(`tick` 을 올림)

하나라도 실패하면 값을 지우지 않고 `opsError` 만 세웁니다. 조회 결과는 다음 대상이 씁니다.
- 로봇
- 현장 설정
- 이력
- 장애 주입
- 전체 상태 띠

다음 영역은 자기 `setInterval(…, POLL_MS)` 로 5초마다 `tick` 을 올려 다시 읽습니다.
- 운영 개요
- 작업 요청
- 공유 자원
- 운영
- 임무 편집기
- 결과 수집기

늦게 온 응답은 `alive` 표지로 버립니다. 실패하면 직전 값을 두고 오류만 바꿉니다. 영역을 떠나면 마운트가 풀려 읽기도 멈춥니다(로봇 영역만 늘 마운트합니다).

실행 호스트를 거치거나 새 영역이 쓰는 읽기는 `api.ts` 의 `getHostJson` 으로 다섯 조회와 따로 둡니다. 대상 항목은 다음과 같습니다.
- 실행
- 셀
- 인시던트
- 잔여 파지 표시(`GET /api/robots/residual-holds`, `fetchResidualHolds`)
- 스테이션과 스테이션 작업
- 호출 계약
- 임무 필수 자원
- 지원 임무 계약
- 배포 이력
- 임무 템플릿

실행 호스트가 멈춰도 다섯 조회가 직전 값이 되지 않습니다.

쓰기 결과 분류는 두 갈래입니다.

`deliver` 의 대상은 다음과 같습니다.
- registry 쓰기
- 현장 설정
- 임무 정의
- 신호
- 장애
- 판단
- 실행 취소(`cancelExecution`)
- 기체 빈손 확인(`confirmRobotEmpty`)
- 수동 작업 요청 `POST /api/work-requests/manual`

`deliver` 의 결과 분류는 다음과 같습니다.
- 2xx: 결과
- 400·403: 사전 거부
- 그 밖: 결과 모름

결과 안에서 응답 없음은 운영 서비스가 다시 읽은 확인 결과로 다음과 같이 나눕니다.
- 반영됨
- 반영 안 됨
- 확인 못 함

각 알림 컴포넌트가 이 갈래를 글자로 보입니다.

`interfaceRequest` 의 대상은 다음과 같습니다.
- 호출 계약
- 공유 스테이션
- 임무 필수 자원

실패하면 `InterfaceApiError` 를 던지고 화면은 메시지를 alert 로 보입니다.

컴포넌트가 `fetch` 를 직접 부르는 작업 요청(`/api/work-requests/*`) 기능은 다음과 같습니다.
- 목록
- 상세
- 집계
- 수집기
- 재평가
- 현장 확인

작업 요청을 만드는 조작은 요청 id(UUID)를 sessionStorage 에 먼저 적고 보냅니다. 응답이 불명이면 같은 id 로만 다시 확인합니다. 키는 모드·사용자(일부는 작업 요청 id)로 갈라 둡니다. `manualRequests.ts` 가 수동 작업 요청의 보관을 맡습니다. 시험은 매 시험 뒤 sessionStorage 를 비웁니다(`setupTests.ts`).

작업 지시 폼이 바뀌면 300ms(`ELIGIBILITY_DEBOUNCE_MS`) 더 바뀌지 않을 때 배정 가능을 묻습니다. 신호 조작, 판단, 실행 취소, 기체 빈손 확인 뒤(`OperationsArea` 의 `rereadNowAndAfterPump`)에는 곧바로 한 번, 500ms(`SIGNAL_SETTLE_MS`) 뒤 한 번 더 읽습니다. 실행 호스트의 pump 주기 250ms 보다 길게 잡은 값입니다. 잔여 파지 표는 자기 조작 뒤 한 번 더 읽습니다(`own` 카운터).

URL 은 해시만 씁니다. `App.tsx` 가 영역을 읽고 씁니다. `WorkRequests.tsx` 가 다음 작업 요청 쿼리를 읽고 씁니다.
- `search`
- `state`
- `result`
- `offset`
- `receipt`

## 5. 시험

### 5.1 vitest

설정은 `ui/vite.config.ts` 의 `test`(jsdom, `src/**/*.test.{ts,tsx}`, `setupFiles` 는 `src/setupTests.ts`)에 있습니다. 도구는 Testing Library 의 다음 항목을 씁니다.
- react
- user-event
- jest-dom

운영 서비스 가짜 응답은 `src/testing/fakeOps.ts` 의 `installFakeOps` 와 보기 생성 함수들입니다. CI 의 실행 명령은 `cd ui && npm test`(= `vitest run`)입니다. 로컬에서는 `cd ui && npx vitest run --maxWorkers=2` 로 동시 실행 수를 줄여 돌립니다. 근거는 `docs/screens/shared-station-orchestration-2026-10-12.md` 입니다. 기본 동시 실행에서 기존 시험이 5초 기한을 넘기고 뒤따른 요청 수 검증이 실패해 동시 실행 수를 줄였고, 시험 제한 시간이나 기대값은 바꾸지 않았습니다.

`bb1d8b5` 와 같은 트리에서 `npx vitest run --maxWorkers=2` 를 실행한 결과는 시험 파일 23개, 시험 213개가 모두 통과(실행기 출력 «Test Files 23 passed (23), Tests 213 passed (213)»)입니다. 이 수는 소스를 센 값이 아니라 실행 결과입니다.

시험은 `data-testid` 를 쓰지 않고 다음 항목으로 찾습니다(설계서 §4).
- 역할
- 접근 이름
- 라벨
- 글자

전용 시험 파일이 없는 컴포넌트는 영역 시험이 함께 봅니다. 예시는 다음과 같습니다.
- `CollectorStatus`: `FollowupWorkspace.test.tsx`
- `StoredResultSummary`: `WorkRequests.test.tsx`
- `RejectionSummary`: `WorkRequests.test.tsx`
- `SourceConfirmation`: `WorkRequests.test.tsx`
- `EligibilityTable`: `OperationsArea.test.tsx`, `MissionInterfaces.test.tsx`
- `InterfaceDeployment`: `FollowupWorkspace.test.tsx`

### 5.2 Playwright

`ui/e2e/lifecycle.spec.ts` 는 735행이며 시험 하나입니다. 설정은 `ui/playwright.config.ts` 이며 `testMatch` 로 이 파일만 지정합니다. 스택 구성은 다음과 같습니다.
- Mock MES 콜백(`e2e/mock-mes-callback.mjs`, 8786)
- Postgres(`e2e/postgres.mjs`)
- 런처(`run-dist.mjs site`)
- 실행 호스트(`run-dist.mjs mission-host --restartable`)
- host-link 프록시(`e2e/host-link.mjs`, 8790)
- 운영 서비스(`run-dist.mjs ops-service --mock-mes --restartable --host-proxy`)
- 화면(`npm run build && npm run preview`, 4173)

다음 항목을 한 흐름으로 돕니다.
- 기체 생애주기
- 어댑터·프로파일·시운전
- 현장 설정
- 수동 작업 요청
- 호출 계약 저장·공개·배포·호출
- Mock MES 외부 주문과 결과 수신 확인
- 운영 개요
- 임무 정의
- 장애·인시던트·판단
- 새 임무 등록과 비교
- 수집기 단절과 전송 결과 불명
- 실행 호스트 재기동
- registry 불통

`ui/e2e/station.spec.ts` 는 90행이며 시험 하나입니다. 설정은 `ui/playwright.station.config.ts` 이며 기본 설정에 `testMatch: '**/station.spec.ts'` 를 더한 구성입니다. lifecycle 이 끝에 registry 를 끄므로 새 스택에서 따로 돕니다. 확인하는 항목은 다음과 같습니다.
- 공유 스테이션 등록
- 임무 필수 자원
- 설비 준비 뒤 두 작업의 순차 실행
- 새 완료 근거로 해제

두 시험은 돌면서 `docs/screens/style-preview/*-fullstack.png` 를 부수로 찍습니다(저장소 README 의 화면 그림).

배포본이 먼저 있어야 합니다. 명령어는 `./gradlew :site:installDist :mission-host:installDist :ops-service:installDist` 입니다.

### 5.3 스크린숏 촬영

스크린숏 촬영 파일은 `ui/screens/capture.spec.ts` 입니다. 설정은 `ui/playwright.screens.config.ts` 이며 세부 내용은 다음과 같습니다.
- `testDir: './screens'`
- 1440×900
- 배율 1
- `colorScheme: 'light'`
- `locale: 'ko-KR'`

이 설정은 `lifecycle.spec.ts` 와 같은 스택(Mock MES 콜백, host-link 프록시, 재기동 가능한 실행 호스트 포함)을 띄웁니다. 한 번 찍는 데 약 7분 걸립니다. 대표 스크린숏 21장을 찍습니다.

실행 명령어는 `cd ui && npx playwright test -c playwright.screens.config.ts` 입니다. 결과는 `docs/screens/img/` 와 `img/INDEX.md` 입니다. CI 의 기본 `npx playwright test` 는 이것을 돌지 않습니다.

`ui/scripts/` 의 다음 스크립트는 #18 때 스타일 검토와 기록용으로 `docs/screens/style-preview/` 에 대역(`-mock`) 화면을 찍은 스크립트입니다.
- `review-style.mjs`
- `capture-missions.mjs`
- `capture-overview.mjs`
- `capture-receipts.mjs`
- `capture-results.mjs`

## 6. 로컬 실행과 CI

로컬 전체 스택(저장소 `README.md` «띄우기») 구성은 다음과 같습니다.
- Postgres(`docker compose -f site/compose.yaml --env-file .env up -d --wait`)
- 배포본 빌드
- `.env` 적용
- 터미널 넷에 서비스 실행

터미널 넷에서 실행하는 대상은 다음과 같습니다.
- 런처
- 실행 호스트
- 운영 서비스
- 화면 개발 서버(`cd ui && npm ci && npm run dev`, `http://localhost:5173`)

운영 서비스 API 는 `http://127.0.0.1:8782/api/…` 입니다. 외부 주문(Mock MES)을 쓰려면 운영 서비스를 띄우기 전에 `PICASSO_INTEGRATION_TOKEN` 을 비어 있지 않은 값으로 둡니다.

개발 서버와 preview 는 `/api` 와 `/integration` 을 운영 서비스로 넘깁니다(`vite.config.ts` 의 proxy).

CI(`.github/workflows/ci.yml`, push to main 과 PR)의 job 은 셋입니다.
- `gradle`(`./gradlew build`)
- `ui`(`npm ci`, `npm test`, `npm run build`)
- `playwright`(배포본 빌드 뒤 `npx playwright test` 와 `npx playwright test --config=playwright.station.config.ts`)

스크린숏 촬영 설정은 CI 밖입니다.
