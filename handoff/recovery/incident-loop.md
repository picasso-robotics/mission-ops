# 실행 호스트 인시던트 고리 안내

## 이 호스트에서 정상인 것
잔여 파지 표시가 열린 동안에는 탐색 줄 없는 `residualHold=HOLDING` 인시던트가 정상이며 사람이 풀어야 할 과제다.
표시를 잘못 푼 뒤 낸 작업 지시는 관문에서 거절되고 조치 제안이 서서 승인 대상이 된다.
값 없는 승인이 실행으로 이어지려면 선언에 조치가 정의되어 있어야 한다.
승인 요청도 실행 호스트 판정의 기체 제한에 걸리면 `REFUSED_BY_GATE`("실행 호스트 판정")로 막힌다.
승인 뒤에도 잔여 파지 표시는 자동으로 풀리지 않으므로 사람이 직접 해소해야 한다.

## 선언과 승인자 id
견본 위치는 `mission-host/incident-loop/entitlements.example.json`이다.
자동 승인자 id는 `recovery-1`(소유자 결정 2026-11-14)이며, 운영자 id 자리표시 `operator-1`은 현장 운영자 사용자 이름으로 바꾼다.
`POST /approvals` 문은 승인자 종류를 가리지 않고 선언을 찾으므로 선언에 없는 운영자 id는 `NOT_DECLARED`로 거절된다.
에이전트 id는 picasso 용어 사전 §6에 따라 `<역할>-<선언 번호>` 형식을 쓰고 사람 id는 이 모양을 쓰지 않는다.
picasso의 `handoff/narrator/entitlements.json`은 현장 기체 id가 아닌 시연용 픽스처이므로 쓰지 않는다.

## 전환 순서
1. `mission-host/incident-loop/entitlements.example.json`을 복사해 현장용 선언 파일을 마련한다.
2. 실행 호스트를 새 선언 파일(`--host.incident-loop.entitlements=<파일>`)로 먼저 기동한다. 선언은 기동 때 한 번 읽으므로 재기동해야 반영된다.
3. 호스트 기동 확인 후 recovery의 `RECOVERY_PICASSO_AGENT_ID`를 `recovery-1`로 바꾼다.
순서가 거꾸로 되면 자동 승인이 모두 `NOT_DECLARED`로 거절된다.

## 받는 쪽이 정할 것
위 사실을 바탕으로 무엇을 언제 반입할지는 recovery 측에서 정한다.
