# 실행 호스트 인시던트 고리 안내

## 이 호스트에서 정상인 것
잔여 파지 표시가 열린 기체라도 새 접수에서는 관문 확인으로 제안이 서며 `refusals[]`의 기체별 `remedy`·`residualHoldMarkers`로 보인다.
값 없는 승인은 이번 인스턴스가 관문 확인으로 세운 제안이고 현재 열린 표시가 모두 그때 열려 있던 것이어야 표시 예외로 처리된다.
근거 기록은 메모리에 보관되므로 인스턴스 재기동 뒤에는 다시 제출해야 한다.
승인 요청에는 실행할 조치가 선언되어 있어야 한다.
진행 중인 실행을 비롯한 그 밖의 기체 단위 제한이 걸려 있으면 `REFUSED_BY_GATE`("실행 호스트 판정")로 거부된다.
표시는 승인 접수, 조치 성공, 빈손 관측으로 자동 해소되지 않는다.
관문이 통과시킨 표시 기체는 일반 실행을 세우지 않고 `excluded`에 표시 사유로 둔다.

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
