# 실행 호스트 인시던트 고리 안내

## 이 호스트에서 정상인 것
이 호스트에서 조치 탐색 줄이 없는 `residualHold=HOLDING` 인시던트는 정상 상황이다.
기본 임무(`InspectAsset`, `PrepareSequencedRack`)와 참조 프로파일에서는 든 채 실패한 뒤 표시를 풀고 낸 순회를 기체가 전제 미충족(`PRECONDITION_UNMET`, HOLD)으로 거부한다.
이때 인시던트와 운영자 보류가 설정되며 조치 탐색은 돌지 않는다(picasso §15.150 설계 의도).
따라서 이 인시던트에는 승인할 제안이 없다(값 없는 승인을 보내면 `NO_PROPOSAL`). 사람 과제로 다루기를 권한다.

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
