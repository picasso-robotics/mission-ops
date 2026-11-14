# 실행 호스트 인시던트 고리 안내

## 이 호스트에서 정상인 것
잔여 파지 표시가 열린 기체라도 새 접수에서는 관문 확인으로 제안이 서며 `refusals[]`의 기체별 `remedy`·`residualHoldMarkers`로 보인다.
값 없는 승인은 이번 인스턴스가 관문 확인으로 세운 제안이고 현재 열린 표시가 모두 그때 열려 있던 것이어야 표시 예외로 처리된다.
근거 기록은 메모리에 보관되므로 인스턴스 재기동 뒤에는 다시 제출해야 한다.
승인 요청에는 실행할 조치가 선언되어 있어야 한다.
진행 중인 실행을 비롯한 그 밖의 기체 단위 제한이 걸려 있으면 `REFUSED_BY_GATE`("실행 호스트 판정")로 거부된다.
표시는 승인 접수, 조치 성공, 빈손 관측으로 자동 해소되지 않는다.
사람의 «빈손 확인» 은 실행 호스트가 그 순간 기체 스냅숏을 읽어 발신자가 아직 든 채라고 말하면 409 `RESIDUAL_HOLD_OBSERVATION_MISMATCH` 로 막고 그 관측을 충돌로 남긴다.
사람이 그 저장된 충돌을 지목해 다시 확인할 때만 어긋남을 알고 해소하며, 지목이 저장값과 다르면 400 `RESIDUAL_HOLD_CITATION_INVALID` 다.
회복 실행이 내려놓아 손이 빈 뒤 그 충돌을 지목하면 관측 `EMPTY` 로 어긋남 인지 없이 해소되고 회복 연결 행은 그대로다.
그 작업 지시가 승인으로 실행되면 운영 서비스의 작업 요청은 `UNASSIGNED`·`REJECTED` 에서 `ACCEPTED` 로 올라가고 앞선 답은 제출 기록에 남는다.
상위 시스템이 대체 접수(`replacesExternalOrderId`)로 앞선 작업 요청을 바꾸거나 스테이션이 현장 해제·제출 거부 해제로 미전송을 확정하면 실행 호스트가 그 작업 지시를 철회한다(B3, `POST /host/job-orders/{jobOrderId}/withdraw`, 영구).
철회된 작업 지시의 값 없는 승인은 `REMEDY_NOT_APPLIED`(사유 앞머리 «철회된 작업 지시다. 실행을 짓지 않는다»)로 거부되고 제안은 소모되지 않는다. 잔여 파지 표시 예외보다 앞이라 관문 확인으로 선 제안도 같다.
승인이 먼저 실행을 세웠으면 철회는 `EXECUTION_EXISTS` 이고 대체는 409 `ORIGINAL_NOT_REPLACEABLE`, 앞선 작업 요청은 `ACCEPTED` 로 올라간다.
철회된 작업 지시의 제안은 picasso 의 제안 장부와 내보내기에는 남는다. 승인 에이전트는 `REMEDY_NOT_APPLIED` 를 그 작업 지시의 끝으로 읽는다.
관문이 통과시킨 표시 기체는 일반 실행을 세우지 않고 `excluded`에 표시 사유로 둔다.
작업 수락 뒤 기체가 `PRECONDITION_UNMET`(파지 주어)으로 거부해 운영자 보류에 선 실행에는 같은 (기체, 작업 지시)에 회복 제안이 설 수 있다(picasso §15.320).
그 제안의 값 없는 승인은 호스트 판정보다 앞에서 picasso 에 넘겨 `HUMAN_APPROVAL_REQUIRED`(대상이 낡았으면 `REMEDY_NOT_APPLIED`)로 거부되고 실행도 조치도 서지 않는다. 다시 시도해도 같다.
이 실행 호스트에는 사람의 회복 입구가 없다. 그 보류는 운영자가 화면에서 판단(재작업·완료 확인)이나 실행 취소로 닫는다.
운영자 실행 취소는 그 실행의 보류·미확정 단위 인시던트의 판단 칸에 `OPERATOR_CANCEL` 을 승인자와 함께 싣는다. 인시던트 뒤의 탐색 줄 사이에 사람의 걸음이 데이터로 남는다.

## 규약 버전(picasso `4d8b364`)
승인 호스트(`POST /approvals`) 답의 `schemaVersion` 은 `"5"` 그대로다(`fde49d1` 에서 `refusal` 에 `HUMAN_APPROVAL_REQUIRED` 가 늘고 거부 답 `consumed` 에 `target` 칸이 붙었다).
인시던트 고리 내보내기 `manifest.json` 의 `schemaVersion` 은 `"9"` 다(picasso §15.323). 인시던트 줄의 판단 칸에 선택 칸 `resolution.reworkBasis` 가 붙었다 — 보류가 아닌 재작업 묶음의 뿌리와 물리 관문을 지난 단위의 인시던트에만 서고, `{robotId, readAt}` 만 든 표지는 «관측 없음» 이지 손·자리를 확인했다는 뜻이 아니다. 칸 정의는 picasso `handoff/narrator/rework-generalized.md` 를 따른다. `"8"` 에서 안내에 그 벌을 낸 인스턴스의 `instanceId` 가 붙었다(picasso §15.322). 한 벌은 한 인스턴스의 것이다.
인시던트 줄에 정체성 넷(`incidentKey`·`firstOpenedAt`·`openingKind`·`attempt`)과 판단자 `resolution.decidedBy`(`{id, kind}`), 임무 버전과 현장 설정 셋(함께 널이거나 함께 있음)이 붙었다. 조치 탐색 줄 `outcome` 에 `SOURCE_UNOBSERVED`·`DESTINATION_OCCUPIED`·`DESTINATION_UNOBSERVED` 가 늘었고 셋 다 `steps` 가 없다(승인 가능한 조치가 아님).
재기동이 다시 연 보류는 `openingKind` 가 `REOPENED` 이고 앞 인스턴스 벌의 원래 보류와 같은 `incidentKey`·`firstOpenedAt` 을 든다. 인시던트 id 는 재기동마다 `incident-1` 부터 다시 세므로 사건은 (`instanceId`, `incidentId`)가 아니라 `incidentKey` 로 접는다. 열쇠가 널인 `REOPENED` 는 연결 불명이며 새 사건으로도 같은 사건으로도 세지 않는다. `attempt` 는 재발 판정에만 쓴다.
실행 호스트의 미들웨어 인스턴스 id 는 기동마다 새 값(`mw-<UUID>`)이라 열쇠가 인스턴스를 넘어 겹치지 않는다. 판단 기록 규약은 `"6"` 이다(실행 호스트는 글자를 그대로 저장, 구조 변경 없음, 판 1~5 행도 읽힘).
실패 종류 `REWORK_RECHECK` 인 실패 인시던트는 받은 재작업을 내기 직전에 손·자리가 달라 내지 않은 시도다. 운영자 보류가 아니고 완료 확인을 받지 않으며 사람의 새 `REWORK` 만 받는다. 그 실패의 시도 번호는 태스크 없이 소모되므로 `@r1` 없이 `@r2` 가 나갈 수 있다.
판단자 원문 id 는 감사 조회·승인자 연결에서만 읽고, 설명 문장을 만드는 자리에는 `kind` 만 넘긴다.
recovery `core` 가 내보내기를 `"8"` 까지만 읽으면 이 호스트의 번들은 모르는 버전으로 멈춘다.

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
