# S4a JSON 계약: 장애 주입, 인시던트 목록과 상세, 운영자 판단

S4a 설계 스펙(`docs/superpowers/specs/2026-10-09-s4a-faults-incidents-design.md`)의 site 와 실행 호스트가 실제로 내고 받는 모양이다. 운영 서비스, 화면, 통합 시험과 Playwright는 이것에 맞춘다. 코드 주석의 «S4a JSON 계약 §N» 은 이 문서의 절을 가리킨다. picasso 쪽 API 는 `195c1ee` 그대로이며(`Middleware.resolve`, `IncidentBundle`, `IncidentResolution`, `ResolveOutcome`) 이 문서는 그 위의 picasso-ops 모양만 적는다.

**앞 계약에서 이 문서가 대체하는 곳**

| 앞 자리 | 대체하는 이 문서의 절 | 바뀐 것 |
|---|---|---|
| S3c JSON 계약의 `GET /host/incidents` | §3 | 줄마다 뒤에 다섯 칸(`unresolved`, `resolution`, `fault`, `held`, `confirmedWithoutEvidence`). 앞 14칸과 쿼리·오류는 그대로 |
| S3b JSON 계약의 `GET /host/missions/templates/{workMasterId}` | §6 | 템플릿이 둘에서 셋(`ARRIVAL_WAIT_HOLD`). 모양은 그대로 |

그 밖의 S3a·S3b·S3c 계약 절은 그대로다.

공통 규칙

- 모든 본문은 UTF-8 JSON 이다. 시각은 ISO-8601 UTC 문자열(`Instant.toString()`)이고 소수 초 자릿수는 고정이 아니다(`Instant.parse` 로 읽을 것). `null` 칸은 생략하지 않고 `null` 로 나온다. 칸 순서는 아래 표의 순서 그대로 나온다(시험이 순서를 대조한다).
- 거부 본문은 늘 `{"error": "<이름>", "detail": "<사람이 읽는 문장>"}` 이다(S3a·S3b 와 같은 모양). 분기는 `error` 로만 한다. `detail` 문장은 바뀔 수 있다.
- POST 는 `Content-Type: application/json` 만 받는다. 아니면 415 다(site 는 `UNSUPPORTED_MEDIA_TYPE` 본문, 호스트는 스프링 기본 415).
- 호스트 시각(인시던트 `at`, 판단 `resolution.at`)은 호스트 시계다. 통합 시험과 호스트 시험에서는 현장의 가상 시계이므로 실제 시각과 다르다(호스트 시험에서는 1970-01-01 근처 값). 실제 시각이 필요한 대조는 `wallClockAt` 을 쓴다.
- 시간값은 초 단위 정수다(S3c 와 같다).

---

## 1. site `POST /faults`

셀 대역 루프백 `HttpServer`(셀 포트, `.env` 의 `SITE_CELL_PORT`, 시험은 `Site.cellPort`)에 붙는다. 호출자는 실행 호스트뿐이다. mimic 제어 채널은 열지 않으며 시계 밀기·시계 모드·시드는 어느 경로로도 내지 않는다.

### 1.1 요청

```json
{"robotId": "humanoid-01", "kind": "SKILL_EXECUTION_FAILED"}
{"robotId": "humanoid-01", "kind": "CONNECTION", "state": "OFFLINE"}
```

| 칸 | 형 | 규칙 |
|---|---|---|
| `robotId` | 문자열 | 필수, 공백이 아님 |
| `kind` | 문자열 | 필수. 받는 값은 `SKILL_EXECUTION_FAILED`, `CONNECTION` 둘 |
| `state` | 문자열 또는 `null` | `kind` 가 `CONNECTION` 이면 필수. 받는 값은 `OFFLINE`, `CONNECTION_BROKEN`, `ONLINE` 셋. `kind` 가 `SKILL_EXECUTION_FAILED` 이면 없거나 `null` 이어야 한다 |

- `SKILL_EXECUTION_FAILED`: 그 기체의 진행 중(`RUNNING`·`PAUSED`·`CANCELLING`) `pick_place` 태스크를 site 가 스스로 찾아(여럿이면 마지막에 시작한 것) mimic `tasks.forceFault("SKILL_EXECUTION_FAILED", taskId)` 로 강제한다. 호출자는 태스크 id 를 모른다. 이 실패 모드는 picasso 프로파일에서 `failure_class` GRASP_FAILED, 스스로 재시도 가능, 새 태스크까지 유지다. 태스크는 곧바로 `RETRIABLE` 이 된다.
- `CONNECTION`: mimic `events.setConnection` 으로 연결 상태를 바꾼다. `ONLINE` 이 아니면 그 기체의 주기 상태 발행(생존 보고)이 멈춘다. 바꾸는 순간 연결 메시지 하나가 registry 로 나간다.
- `CONTROL_AUTHORITY_LOST`: mimic `tasks.forceControlAuthorityLoss()` 를 호출하여 기체 단위 결함인 `CONTROL_AUTHORITY_LOST` 를 세운다. 진행 중인 태스크가 있으면 엔진이 실패 처리하며, 태스크가 없어도 결함이 선다. 선 결함은 지울 때까지 유지되어 기체가 새 태스크를 수락할 수 없으므로 실행 호스트가 다음 로봇 단위 시작 전에 실행을 막는다. 결함 수용으로 해당 실행의 막힘을 풀더라도 기체 결함 자체는 유지되어 다음 실행이 다시 막힌다. 프로파일이 배타 제어 모델이 아니면(`exclusive_control_required=false`) 엔진이 거부하여 현장이 409 `FAULT_REFUSED` 로 응답한다.
- `CONTROL_AUTHORITY_REGAINED`: 엔진의 결함 목록에서 `CONTROL_AUTHORITY_LOST` 결함을 지우고 실제로 지운 것에 대해서만 결함 해소 이벤트를 낸다. picasso 내에 결함을 지우는 호출이 없으므로 현장이 엔진의 결함 목록과 이벤트 스트림을 직접 부른다. 해당 결함이 서 있지 않더라도 요청은 받아들이며 아무것도 지우지 않는다.
- 모든 엔진 호출은 `MimicServer.exclusive` 아래에서 한다. 부른 뒤 밀거나 종결시키지 않는다. 열린 `WatchTask` 스트림에는 다음 시계 진행(런처는 1초 주기, 통합 시험은 시계 밀기)이 민다. 그래서 미들웨어는 다음 시계 진행 뒤에야 스킬 실패를 본다.
- 받지 않는 것: `PAYLOAD_LOST`, `LOCALIZATION_LOST` 등 그 밖의 지울 때까지 유지되는 결함, 전송 장애(`DISCONNECT`, `DELAY`, `EVENT_LOSS`, `DUPLICATE`, `REORDER`), 연결 상태 `HIBERNATING`. 모두 400 `UNSUPPORTED_FAULT` 다. 연결 상태 이름은 접두사 없는 이름만 받는다(`CONNECTION_STATE_OFFLINE` 은 400).

### 1.2 받아들임(200)

스킬 실패:

```json
{"robotId": "humanoid-01", "kind": "SKILL_EXECUTION_FAILED", "taskId": "JO-1#RACK-204.S01", "taskState": "RETRIABLE", "raised": true}
```

| 칸 | 뜻 |
|---|---|
| `taskId` | site 가 찾은 태스크 id(미들웨어가 붙인 id, 보통 `<jobOrderId>#<unitId>`, 재작업이면 뒤에 시도 번호가 붙을 수 있다) |
| `taskState` | 강제 뒤 태스크 상태. 이 실패 모드면 `RETRIABLE` |
| `raised` | 기체 결함이 새로 섰는가. 같은 결함이 이미 서 있으면 `false` |

연결 상태:

```json
{"robotId": "humanoid-01", "kind": "CONNECTION", "state": "OFFLINE", "changed": true}
```

`changed` 는 상태가 실제로 바뀌었는가다. 같은 상태를 다시 넣으면 `false` 이고 아무것도 발행하지 않는다(200 그대로).

제어권 상실:

```json
{"robotId": "humanoid-01", "kind": "CONTROL_AUTHORITY_LOST", "raised": true, "terminated": []}
```

| 칸 | 뜻 |
|---|---|
| `raised` | 결함이 새로 섰는가. 이미 서 있었으면 `false` |
| `terminated` | 엔진이 실패 처리한 진행 중 태스크 id 목록. 도는 태스크가 없었으면 빈 배열 |

제어권 회복:

```json
{"robotId": "humanoid-01", "kind": "CONTROL_AUTHORITY_REGAINED", "cleared": true}
```

`cleared` 는 결함을 실제로 지웠는가다. 지울 결함이 없었으면 `false` 다.

### 1.3 거부

판정 순서: 415 → 400 `BAD_REQUEST` → 400 `UNSUPPORTED_FAULT` → 404 `UNKNOWN_ROBOT` → 409.

| 상태 | `error` | 언제 |
|---|---|---|
| 415 | `UNSUPPORTED_MEDIA_TYPE` | `Content-Type` 이 `application/json` 이 아님 |
| 400 | `BAD_REQUEST` | JSON 객체가 아님, `robotId`·`kind` 가 비어 있지 않은 문자열이 아님, `state` 가 문자열이 아닌 값, `CONNECTION` 인데 `state` 없음, `CONNECTION` 이 아닌데 `state` 있음 |
| 400 | `UNSUPPORTED_FAULT` | `kind` 가 네 값이 아님, 또는 `CONNECTION` 의 `state` 가 세 값이 아님 |
| 404 | `UNKNOWN_ROBOT` | 이 현장에 없는 기체 |
| 409 | `NO_RUNNING_TASK` | 그 기체에 진행 중인 `pick_place` 태스크가 없음. 유휴, 이미 끝난 태스크, `pick_place` 가 없는 기체(`quadruped-01`) 모두 이것이다 |
| 409 | `FAULT_REFUSED` | mimic 엔진이 강제를 거부함(엔진이 그 태스크를 못 찾음, 또는 엔진의 거부 사유를 `detail` 에 그대로). 프로파일이 배타 제어 모델이 아닌(`exclusive_control_required=false`) 기체에 `CONTROL_AUTHORITY_LOST` 를 주입한 경우에도 이와 같이 거부됨. 스킬 실패는 잠금 아래에서 진행 중 태스크를 골랐으므로 정상 흐름에서는 나지 않는다(방어용). |
| 405 | 본문 없음 | `POST` 가 아님(`Allow: POST`) |
| 404 | 본문 없음 | `/faults/...` 처럼 다른 경로 |

---

## 2. 실행 호스트 `POST /host/faults`

본문을 해석하지 않고 site `POST /faults`(셀 기준 주소 `host.cell.url` + `/faults`)에 그대로 넘기고, site 의 상태 코드와 본문, `Content-Type` 을 그대로 돌려준다. 호스트 잠금을 잡지 않는다. 신호 중계 `POST /host/cell/signals/{name}` 과 같은 꼴이다.

| 상태 | 본문 | 언제 |
|---|---|---|
| site 의 상태 | site 의 본문 | site 가 답함(§1.2, §1.3 의 200·400·404·409·415 가 그대로 온다) |
| 503 | `{"error": "CELL_SILENT", "detail": "현장 셀 대역이 답하지 않는다"}` | site 가 안 닿음(연결 실패, 요청 제한 4초 초과) |
| 415 | 스프링 기본 | 호스트에 온 요청이 `application/json` 이 아님(site 로 넘기지 않는다) |

- 요청 제한 4초는 신호 중계와 같다(site 의 주입이 mimic 엔진 잠금을 기다릴 수 있다). 운영 서비스의 호스트 요청 제한 5초보다 짧아 503 이 운영 서비스에 닿는다.
- 호스트는 장애 주입을 기록하지 않는다. 조작 기록은 운영 서비스의 몫이다(op `INJECT_FAULT`, 재조회하지 않음).

---

## 3. 실행 호스트 `GET /host/incidents`

쿼리 `limit`(선택, 기본 50, 1~500 정수, 밖이면 400 `BAD_REQUEST`)은 S3c 그대로다. 본문 `{"instanceId", "total", "incidents"}` 도 그대로이며 `incidents` 는 최신부터(봉인 역순) 많아야 `limit` 개, `total` 은 자르기 전의 수다. 호스트 잠금 아래에서 읽는다. 호스트 인시던트는 메모리에만 있어 호스트를 재기동하면 사라지고 `instanceId` 가 바뀐다.

줄 하나(19칸, 이 순서):

| # | 칸 | 형 | 뜻 |
|---|---|---|---|
| 1 | `incidentId` | 문자열 | `incident-N`(미들웨어가 뜬 한 번 안에서 1부터) |
| 2 | `executionId` | 문자열 | `exec-N` |
| 3 | `jobOrderId` | 문자열 | |
| 4 | `robotId` | 문자열 | |
| 5 | `unitId` | 문자열 | 설비 대기 단위는 정의의 노드 id(`rack-arrival`), 로봇 단위는 슬롯 id 등 |
| 6 | `at` | 시각 | 봉인 라운드의 호스트 시계 |
| 7 | `failureClass` | 문자열 또는 `null` | 대기 기한이면 `SIGNAL_DEADLINE`, 스킬 실패면 `GRASP_FAILED` |
| 8 | `route` | 문자열 | `ROBOT`, `SIGNAL`, `FLEET` |
| 9 | `missionVersion` | 정수 또는 `null` | `null` 이면 코드 정의 |
| 10 | `siteSettingsVersion` | 정수 또는 `null` | 봉인 라운드의 현장 설정 버전 |
| 11~14 | `evidenceBeforeSeconds`, `evidenceAfterSeconds`, `inDoubtGraceSeconds`, `stallWindowSeconds` | 정수(뒤 둘은 `null` 가능) | S3c 그대로 |
| 15 | `unresolved` | 참거짓 | 봉인할 때 단위가 `OPERATOR_HOLD`·`IN_DOUBT` 였는가. **봉인 때 한 번 정해지고 판단 뒤에도 그대로다** |
| 16 | `resolution` | 객체 또는 `null` | 사람이 이 인시던트의 단위에 낸 판단. 모양은 아래 |
| 17 | `fault` | 객체 또는 `null` | 이 단위를 실패로 만든 결함의 요약 `{failureClass, errorType, errorHint}`. 하류가 결함 없이 실패를 알렸으면(설비 대기 기한 포함) `null` |
| 18 | `held` | 참거짓 | 보류 중(아래 정의) |
| 19 | `confirmedWithoutEvidence` | 참거짓 | `resolution.decision` 이 `CONFIRM_DONE` 이고 봉인 때 확인 결과(`verification`, 상세 칸)가 `MATCHED` 가 아님. 화면의 «설비 근거 없이 완료 확인» 이 이것이다. 호스트가 계산해 낸다(화면이 다시 계산할 필요 없음) |

`resolution`(4칸, 이 순서):

```json
{"decision": "REWORK", "at": "2026-10-09T02:31:05Z", "wallClockAt": "2026-10-09T02:31:05.123Z", "decidedBy": {"id": "kim", "kind": "PERSON"}}
```

| 칸 | 뜻 |
|---|---|
| `decision` | `CONFIRM_DONE`·`REWORK`(운영자 판단)·`OPERATOR_CANCEL`(사람의 운영자 취소)·`REMEDY_APPLIED`(사람의 회복 조치 승인) 가운데 하나(picasso `IncidentDecision`, §15.320). 이 호스트가 내는 것은 앞의 셋이다(회복 입구 없음). 판단 요청(§5)의 `decision` 은 앞의 둘뿐이다 |
| `at` | 판단 시각, 호스트 시계 |
| `wallClockAt` | 판단 시각, 실제 시각. **운영 서비스의 재조회 대조는 이 칸을 쓴다**(§5.4) |
| `decidedBy` | `{id, kind}`. `kind` 는 호스트 REST 로 낸 판단이면 늘 `PERSON` 이다. `id` 는 판단 요청의 `approverId` 그대로다 |

`fault` 요약(3칸, 이 순서): `failureClass`(정준 분류, 분류가 없으면 `UNCLASSIFIED`), `errorType`(어댑터 오류 유형, 예 `SKILL_EXECUTION_FAILED`), `errorHint`(사람이 취할 조치, 프로파일에 없으면 빈 문자열. `humanoid-01` 의 `SKILL_EXECUTION_FAILED` 는 빈 문자열이다).

**`held` 의 정의**: 그 단위(`executionId`, `unitId`)의 지금 상태가 `OPERATOR_HOLD` 이고, 이 인시던트가 그 단위의 인시던트 가운데 `unresolved` 가 참이고 `resolution` 이 `null` 인 것들 중 가장 최근의 것일 때만 참이다. 자르기(`limit`) 전의 전부로 정한다. 전제: 보류 중인 단위에서 `unresolved` 가 아닌 인시던트가 그 뒤에 봉인되는 경로는 지금 picasso 에 없다(그런 경로가 생기면 «가장 최근» 을 미해결 인시던트 안에서만 고르는 이 정의를 다시 볼 것). 그래서:

- 대기 기한 보류 직후: 그 인시던트 `held = true`, `unresolved = true`, `resolution = null`.
- 재작업(`REWORK`) 판단 직후: 그 인시던트에 `resolution` 이 붙고 `held = false`, `unresolved` 는 여전히 `true`. 실행은 곧바로 `RUNNING`.
- 재작업 뒤 두 번째 보류: 새 인시던트만 `held = true`. 앞 인시던트는 `held = false` 이고 `resolution.decision = REWORK` 를 그대로 든다. 한 단위에 `held = true` 는 많아야 하나다.
- 완료 확인(`CONFIRM_DONE`) 뒤: 그 인시던트 `held = false`, 대기 단위면 `confirmedWithoutEvidence = true`.
- 실행 수준 막힘(로봇 수준 결함이 다음 단위를 막아 실행이 `OPERATOR_HOLD`, 상세의 `blockedBy` 가 참)은 단위 보류가 아니므로 `held = false` 이고 판단하면 `NotHeld` 다.
- 단위가 실패로 끝난 인시던트(예 GRASP_FAILED 이고 근거 없음, 단위 `FAILED`)는 `unresolved = false`, `held = false`.

화면의 «미해결» 은 `unresolved && resolution == null` 이다(스펙 §8.2). «보류 중» 강조는 `held` 만 본다.

---

## 4. 실행 호스트 `GET /host/incidents/{incidentId}`

호스트 잠금 아래에서 읽는다. 없으면 404 `{"error": "INCIDENT_NOT_FOUND", ...}`(재기동으로 사라진 id 도 이것이다).

본문(29칸, 이 순서):

| 칸 | 형 | 뜻 |
|---|---|---|
| `instanceId` | 문자열 | 목록과 같다 |
| `incidentId`, `executionId`, `jobOrderId`, `robotId`, `unitId`, `at` | | 목록과 같다 |
| `wallClockAt` | 시각 | 봉인한 실제 시각 |
| `failureClass`, `route`, `unresolved`, `resolution`, `held`, `confirmedWithoutEvidence` | | 목록과 같다(`resolution` 모양 포함) |
| `unitState` | 문자열 또는 `null` | 그 단위의 **지금** 상태(`PENDING`, `IN_DOUBT`, `RUNNING`, `VERIFYING`, `OPERATOR_HOLD`, `DONE`, `UNVERIFIED`, `FAILED`, `ABORTED`). 이 인스턴스가 이빅션한 실행은 마지막 종결 본문의 그 단위 상태이다(더는 바뀌지 않는다). 실행과 종결 본문이 모두 없을 때만 `null`이다. 근거: `ForgetSettledTest` 의 `FAILED 는 두는 폭 동안 메모리에 남아 같은 판은 IDEMPOTENT 이고 폭이 지나면 놓아 같은 작업 지시는 판과 무관하게 놓은 작업 지시로 거부한다`(인시던트 상세 `unitState` 가 `FAILED`) |
| `fault` | 결함 객체 또는 `null` | 이 단위를 실패로 만든 결함의 전부(아래) |
| `blockedBy` | 결함 객체의 배열 | 봉인 때 실행 전체를 막던 결함. 없으면 빈 배열 |
| `requiredEvidence` | 문자열 | 작업 지시가 요구한 근거 등급 `E0`~`E3` |
| `reachedEvidence` | 문자열 | 봉인 때 이 단위가 닿은 근거 등급 |
| `verification` | 문자열 | 봉인 때의 설비 확인 결과(`NOT_REQUESTED`, `MATCHED`, `ABSENT`, `MISMATCH` 등 picasso `Verification` 이름) |
| `step` | 객체 | `{at, plan, completed}`. `at` 은 1부터의 단위 위치(계획에 없으면 0), `plan` 은 계획된 단위 id 전부, `completed` 는 봉인 때까지 끝난 단위 id |
| `evidenceWindow` | 배열 | 근거 윈도우 안의 관측. 원소 `{sequence, occurredAt, kind, detail, local}` |
| `windowTruncated` | 참거짓 | 윈도우 밖이라 버린 관측이 있는가 |
| `preconditionSubjects` | 문자열 배열 | 위반된 사전 조건의 주어 |
| `expectedHold` | 문자열 또는 `null` | 효과에서 유도한 기대 파지(`HOLD_KIND_*`) |
| `observedHold` | 문자열 | 그때 관측한 파지(`HOLD_KIND_*`, 대기 단위는 `HOLD_KIND_UNSPECIFIED`) |
| `effectMismatch` | 문자열 또는 `null` | 효과와 관측의 어긋남 |
| `linkBroken` | 참거짓 | 실행이 연결이 끊긴 채 돌던 중이었는가 |
| `intent` | 객체 | 의도 전체(아래) |

결함 객체(9칸, 이 순서): `failureClass`, `errorType`, `vendorDetail`(빈 문자열 가능), `errorHint`(빈 문자열 가능), `references`(`[{key, value}]`, 스킬 수준이면 `KEY_SKILL_ID`·`KEY_TASK_ID`, 로봇 수준이면 빈 배열일 수 있다), `canContinueCurrentTask`, `canAcceptNewTask`, `activeUntilKind`(`KIND_UNTIL_CLEARED`, `KIND_UNTIL_NEW_TASK`, `KIND_UNTIL`), `activeUntilTime`(빈 문자열 가능).

`evidenceWindow` 원소: `sequence`(정수), `occurredAt`(ISO-8601 문자열, 호스트 시계 축), `kind`(`RESYNC`, `CELL_SIGNAL`, `TASK_TRANSITION`, `SKILL_TRANSITION`, `FAULT_RAISED` 등), `detail`(사람이 읽는 문장), `local`(참이면 미들웨어가 적은 관측이고 `sequence` 는 커서 값을 빌린 것이라 단조가 아니다).

`intent`(17칸, 이 순서): `workMasterId`, `orderVersion`, `orderParameters`(문자열 맵), `materials`(`[{materialDefinitionId, quantity}]`), `equipment`(`[{id, equipmentUse, properties}]`), `capabilityMaxEvidence`, `evidenceBeforeSeconds`, `evidenceAfterSeconds`, `skillType`, `unitParameters`(문자열 맵), `source`, `destination`, `expectedIdentity`(셋 다 `null` 가능), `missionVersion`, `siteSettingsVersion`, `inDoubtGraceSeconds`, `stallWindowSeconds`(목록과 같은 뜻).

실측한 값(호스트 시험, 표준 시간값 버전 1):

- 대기 기한 보류(`ARRIVAL_WAIT_HOLD`, 슬롯 하나): `failureClass` `SIGNAL_DEADLINE`, `route` `SIGNAL`, `fault` `null`, `blockedBy` `[]`, `requiredEvidence` `E2`, `reachedEvidence` `E0`, `verification` `NOT_REQUESTED`, `step` `{"at":1,"plan":["rack-arrival","RACK-204.S01"],"completed":[]}`, `observedHold` `HOLD_KIND_UNSPECIFIED`, `intent.skillType` `equipment_wait`, `intent.unitParameters` `{"signal":"rack_present","expect":"true","deadlineSeconds":"20","onDeadline":"OPERATOR_HOLD"}`, `intent.source`·`destination`·`expectedIdentity` `null`. `evidenceWindow` 에 `kind` `CELL_SIGNAL` 이고 `detail` 이 `signal rack_present at deadline: ...` 인 원소가 하나 있다.
- 진행 중 스킬 실패(코드 정의, 슬롯이 비어 근거 없음): `failureClass` `GRASP_FAILED`, `route` `ROBOT`, 단위 `FAILED`, `unresolved` `false`, `fault` `{"failureClass":"GRASP_FAILED","errorType":"SKILL_EXECUTION_FAILED","vendorDetail":"","errorHint":"","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"JO-1#RACK-204.S01"}],"canContinueCurrentTask":false,"canAcceptNewTask":true,"activeUntilKind":"KIND_UNTIL_NEW_TASK","activeUntilTime":""}`, `reachedEvidence` `E0`, `verification` `NOT_REQUESTED`(설비 슬롯은 실제로 읽었다. 스펙 §8.2 대로 코드 이름 그대로 보일 것), `evidenceWindow` 에 `FAULT_RAISED` 원소.
- 로봇 수준 결함(`LOCALIZATION_LOST`, 현장 주입 종류에는 없음)이 다음 단위를 막음: `blockedBy` 원소 `errorType` `LOCALIZATION_LOST`, `canAcceptNewTask` `false`, `activeUntilKind` `KIND_UNTIL_CLEARED`.

---

## 5. 실행 호스트 `POST /host/executions/{executionId}/units/{unitId}/resolve`

### 5.1 요청

```json
{"decision": "REWORK", "approverId": "kim", "requestId": "3f0c6c1e-6a0e-4f43-9a52-2a3b4a9e8d10"}
```

| 칸 | 규칙 |
|---|---|
| `decision` | 필수. `CONFIRM_DONE` 또는 `REWORK` |
| `approverId` | 필수, 공백이 아닌 문자열. 운영 서비스는 `X-Ops-User` 를 넣는다. 호스트는 기본값을 두지 않는다(ADR 43) |
| `requestId` | 선택. 없거나 `null` 이거나 UUID 문자열. 호스트는 응답에 그대로 돌려줄 뿐 **저장하지 않는다**. 같은 `requestId` 로 다시 보내도 중복을 막지 않는다(두 번째는 대개 NotHeld) |

호스트는 `Approver(approverId, PERSON)` 을 만들어 호스트 잠금 아래에서 `Middleware.resolve(executionId, unitId, decision, approver)` 를 부른다. 판단은 그 단위의 인시던트 가운데 판단 없는 가장 최근 것에 `resolution` 으로 붙는다(picasso 규칙). 사유 칸은 없다(picasso 인시던트에 자리가 없어 운영 서비스 조작 기록에만 남는다).

### 5.2 응답

결과는 늘 200 본문이다(4칸, 이 순서). 본문이 틀리면 400, `application/json` 이 아니면 415 이고(§5.3) 그 밖의 상태 코드로 결과를 내지 않는다.

```json
{"result": "Resolved", "detail": null, "incidentId": "incident-1", "requestId": "3f0c6c1e-6a0e-4f43-9a52-2a3b4a9e8d10"}
{"result": "NotHeld", "detail": null, "incidentId": null, "requestId": null}
{"result": "Refused", "detail": "에이전트는 운영자 판단을 내지 못한다 ...", "incidentId": null, "requestId": null}
```

| `result` | 뜻 | 바뀐 것 |
|---|---|---|
| `Resolved` | 판단이 섰다. `incidentId` 는 판단이 붙은 인시던트(그 단위에 판단 없는 인시던트가 없었으면 `null`) | `REWORK`: 단위가 새 정체성으로 `PENDING` 이 되고 실행은 곧바로 `RUNNING`, 대기 단위면 대기가 새로 시작된다(기한도 새로 센다). `CONFIRM_DONE`: 단위 `DONE`. 봉인 때 확인 결과가 `MATCHED` 였으면 근거 등급 E2, 아니면 올리지 않는다. 실행은 다음 단위로 간다 |
| `NotHeld` | 실행이 없거나 그 단위가 `OPERATOR_HOLD` 가 아니다 | 없음 |
| `Refused` | 승인자가 에이전트다. `detail` 에 picasso 의 이유 | 없음 |

- 이름은 picasso `ResolveOutcome` 그대로 대소문자를 섞은 `Resolved`, `NotHeld`, `Refused` 다. 운영 서비스는 `Resolved` 를 SUCCEEDED, 나머지 둘을 REJECTED 로 기록하고 본문에 이 이름을 남긴다(스펙 T7).
- **Refused 는 호스트 REST 로는 나지 않는다.** 호스트는 늘 `PERSON` 을 만든다. 호스트 시험은 호스트 빈을 직접 불러 Refused 이름을 확인한다. 운영 서비스와 화면은 Refused 를 처리하되 통합 시험에서 만들 수단은 없다.
- 실행 수준 막힘(`blockedBy`)은 단위 보류가 아니므로 `NotHeld` 다(실행 수준 막힘을 푸는 것은 운영자 결함 수용 `POST /host/executions/{executionId}/release`(S3a JSON 계약 §5.10)다).
- 판단 뒤 작업 응답은 갱신되지 않는다(picasso `resolve` 가 `notify` 를 부르지 않음, 스펙 T10). 실행 목록 `GET /host/executions` 의 실행 상태·단위 상태와 인시던트는 곧바로 바뀐다. `jobResponse` 칸은 다음 갱신까지 옛 값이다.

### 5.3 거부

| 상태 | `error` | 언제 |
|---|---|---|
| 400 | `BAD_REQUEST` | JSON 객체가 아님, `decision` 이 두 값이 아님, `approverId` 가 없거나 공백, `requestId` 가 UUID 문자열이 아닌 값(숫자 포함) |
| 415 | 스프링 기본 | `application/json` 이 아님 |

경로의 `executionId`·`unitId` 는 검사하지 않는다. 없는 실행·단위는 `NotHeld` 다(404 가 아니다).

### 5.4 운영 서비스의 재조회 대조(응답 없음 뒤)

호스트는 `requestId` 를 저장하지 않으므로 대조는 인시던트로 한다. 요청을 보내기 직전의 실제 시각을 `sentAt` 으로 정해 두고, `GET /host/incidents?limit=500` 에서 같은 `executionId`·`unitId` 의 인시던트 가운데 **하나라도** 다음을 모두 만족하면 반영(Resolved)으로 본다.

- `resolution != null`
- `resolution.decidedBy.id == 행위자(X-Ops-User)`
- `resolution.decision == 요청한 결정`
- `resolution.wallClockAt >= sentAt`(`at` 이 아니라 `wallClockAt`. 통합 시험에서는 `at` 이 가상 시각이라 실제 시각과 비교할 수 없다)

어느 것도 없으면 반영 안 됨이다. 같은 사람이 같은 단위를 앞서 판단한 기록은 `wallClockAt` 조건이 거른다. 스펙 §7·§9 의 «가장 최근 인시던트» 와 «그 단위가 더 이상 보류가 아님» 조건은 쓰지 않는다. 재작업 뒤 대기가 기한(20초)을 다시 넘기면 새 인시던트가 가장 최근이 되고 단위가 다시 보류가 되어, 반영된 재작업을 반영 안 됨으로 읽는다.

---

## 6. 템플릿 `ARRIVAL_WAIT_HOLD`

`GET /host/missions/templates/PrepareSequencedRack` 의 `templates` 가 셋이 된다. 순서는 `DATA_V1`, `ARRIVAL_WAIT`, `ARRIVAL_WAIT_HOLD` 다. 원소 모양 `{id, title, definition}` 은 S3b 그대로다.

| 칸 | 값 |
|---|---|
| `id` | `ARRIVAL_WAIT_HOLD` |
| `title` | `랙 도착 대기(rack_present = true, 기한 20초, 기한 뒤 운영자 보류)` |
| `definition` | `ARRIVAL_WAIT` 의 글자에서 대기 노드의 `"deadlineSeconds": 120, "onDeadline": "ABORTED"` 만 `"deadlineSeconds": 20, "onDeadline": "OPERATOR_HOLD"` 로 바꾼 것. 나머지 글자는 같다 |

`definition` 글자:

```
{
"schemaVersion": 1,
"workMasterId": "PrepareSequencedRack",
"maxEvidence": "E2",
"preferredOptionals": { "verify_grasp": "true" },
"steps": [
{"kind": "wait", "id": "rack-arrival", "signal": "rack_present", "expect": "true", "deadlineSeconds": 20, "onDeadline": "OPERATOR_HOLD"},
{
"kind": "unit",
"id": "place",
"skill": "pick_place",
"forEach": "destination",
"pairWith": { "equipmentUse": "source", "property": "material" },
"whenUnpaired": "NO_SOURCE_FOR_MATERIAL",
"unitId": { "from": "ITEM_ID" },
"parameters": { "object_id": { "from": "PAIRED_ID", "otherwise": "" }, "destination": { "from": "ITEM_ID" } },
"expectedIdentity": { "from": "ITEM_PROPERTY", "property": "material" },
"source": { "from": "PAIRED_ID" },
"destination": { "from": "ITEM_ID" }
}
]
}
```

- S3b 흐름(초안 저장, 모의 실행, 활성화) 그대로 활성화된다. 모의 실행은 신호를 기대 값으로 두므로 통과한다.
- 동작: 대기 단위가 시작된 시각(호스트 시계) + 20초를 **넘긴 뒤의 첫 pump** 에서 단위가 `OPERATOR_HOLD`, 실행이 `OPERATOR_HOLD`, `SIGNAL_DEADLINE` 인시던트(`unresolved = true`, `held = true`)가 선다. 실시간 런처(1초 시계, 250ms pump)에서는 대기 시작 뒤 약 20~21초다. 호스트 시험에서는 가상 시계를 5초씩 밀어 30초 안에 섰다.
- 대기 단위 id 는 `rack-arrival` 이고 판단 경로는 `/host/executions/{executionId}/units/rack-arrival/resolve` 다.
- 신호가 `true` 인 채 남아 있으면 다음 대기는 곧바로 `DONE` 이다(셀 대역 신호는 스스로 돌아가지 않는다). 보류를 다시 만들려면 `rack_present` 를 `false` 로 되돌린다(스펙 단계 5).
- 활성 버전은 DB 에 남으므로 이 버전을 활성화한 뒤의 모든 PrepareSequencedRack 작업 지시가 대기로 시작한다.

---

## 7. 오류 이름 모음

| 이름 | 상태 | 자리 |
|---|---|---|
| `BAD_REQUEST` | 400 | site `/faults`, 호스트 판단, 호스트 인시던트 `limit` |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | site `/faults` |
| `UNSUPPORTED_FAULT` | 400 | site `/faults`(호스트가 중계) |
| `UNKNOWN_ROBOT` | 404 | site `/faults`(호스트가 중계) |
| `NO_RUNNING_TASK` | 409 | site `/faults`(호스트가 중계) |
| `FAULT_REFUSED` | 409 | site `/faults`(배타 제어 모델이 아닌 기체의 제어권 상실) |
| `CELL_SILENT` | 503 | 호스트 `/host/faults`(신호 중계와 같은 이름) |
| `INCIDENT_NOT_FOUND` | 404 | 호스트 `/host/incidents/{incidentId}` |

판단 결과 `Resolved`·`NotHeld`·`Refused` 는 오류가 아니라 200 본문의 `result` 다.

---

## 8. 통합 시험과 화면을 위한 사실

- 스킬 실패는 site 가 엔진을 부른 뒤 밀지 않으므로 다음 시계 진행 전에는 실행 목록에 안 보인다. 통합 시험은 주입 뒤 시계를 민다.
- 진행 중 태스크 찾기는 `RUNNING`·`PAUSED`·`CANCELLING` 이다. 작업 지시를 낸 직후 태스크는 `ACCEPTED` 라 다음 시계 진행(tick) 전에는 `NO_RUNNING_TASK` 다. `pick_place` 소요는 45초 ±10% 이므로 시작 뒤 1~40초 사이에 넣으면 된다.
- 셀 대역 슬롯은 비우지 않는다. 같은 슬롯이 근거 윈도우 앞 폭(표준 30초) 안에 채워진 적이 있으면 스킬 실패가 `FAILED` 대신 운영자 보류(`verification` `MATCHED`)가 될 수 있다. 새 스택에서 아직 채운 적 없는 슬롯을 쓸 것.
- 연결 상태 주입은 `humanoid-01`·`quadruped-01` 모두 받는다. 스킬 실패는 `humanoid-01` 만 뜻이 있다.
- 자연 실패 추첨(시드 0, `humanoid-01`): 추첨은 `pick_place` 태스크마다 생성 때 지터 한 번, 정상 완주 때 실패 모드 둘(GRASP_FAILED 0.05, PAYLOAD_LOST 0.01) 순서로 `java.util.Random(0).nextDouble()` 을 소비한다. 강제한 태스크는 완주 추첨을 하지 않는다. 다른 스킬은 추첨하지 않는다. 그래서 새 스택에서 강제 없이 돌면 5번째 `pick_place` 가 자연 GRASP_FAILED(인출 14번째 값 0.0232)다. 첫 `pick_place` 에 스킬 실패를 강제하면 이후 인출이 둘씩 당겨져 자연 실패는 13번째 `pick_place`(GRASP 추첨이 인출 36번째 값 0.0045)로 밀린다. mimic 하나에 `pick_place` 를 차례로 걸어 실측으로 확인했다(강제 없이 일곱: 5번째만 `RETRIABLE`. 첫 태스크 강제 뒤 열넷: 1번째와 13번째만 `RETRIABLE`). 같은 스택 안의 앞 단계 `pick_place` 도 모두 센다(취소된 태스크는 완주 추첨을 하지 않는다). 시나리오의 `pick_place` 수를 세어 이 자리를 피할 것.

---

## 9. 운영 서비스 REST(화면이 부르는 것)

운영 서비스가 실제로 내고 받는 모양이다(스펙 §7, T7). 화면과 통합 시험은 이것에 맞춘다. 운영 서비스는 picasso 코드를 쓰지 않고 §2~§5 의 호스트 REST 만 부른다.

### 9.1 공통 규칙

- 기준 주소 `http://127.0.0.1:${OPS_PORT}`. `null` 칸은 생략하지 않고 칸 순서는 이 절의 예 그대로다.
- POST 는 `Content-Type: application/json` 만 받는다. 그 밖은 415(스프링 기본 본문, 모양 보장 없음).
- 행위자 헤더 `X-Ops-Mode`(`engineer`·`operator`)와 `X-Ops-User`(`[A-Za-z0-9._-]{1,64}`)는 S3a·S3b 와 같다. 두 읽기는 헤더를 보지 않는다.
- 사전 거부(400·403, 읽기의 503)의 본문은 `{"error": "...", "detail": "..."}` 이다. 사전 거부는 호스트에 닿지 않았으므로 조작 기록에 남지 않는다.
- 쓰기 판정 순서: 게이트(400 `ACTOR_REQUIRED` → 403 `MODE_NOT_ALLOWED`) → 본문(400 `FAULT_BAD_REQUEST`·`RESOLVE_BAD_REQUEST`) → 사유(400 `REASON_REQUIRED`) → 호스트. 게이트가 먼저라 모드가 틀린 깨진 본문은 403 이다.
- 사유는 문자열이고 앞뒤 공백을 깎은 뒤 비어 있지 않아야 한다. 없음, `null`, 문자열 아님(숫자 등), 공백뿐이면 400 `REASON_REQUIRED`. 깎은 값이 조작 기록의 `reason` 칸에 남는다.
- 호스트를 부르는 요청 제한은 5초, 연결 제한은 2초다(S3a 그대로).
- 쓰기는 호스트에 보냈으면 무엇을 답했든 **200** 이다. 결과는 본문에 있다(S3b 계약 §10.1 의 조작 응답 공통 규칙 그대로). 쓰기에서 호스트 불통은 503 이 아니라 200 의 `NO_RESPONSE` 다.

| 호스트가 한 것 | `result` | `confirmation` | 호스트 본문 칸(`fault`·`answer`) | `rejection` |
|---|---|---|---|---|
| 2xx 이고 본문을 읽음 | 아래 결과 매핑 | null | 호스트 200 본문 그대로 | null |
| 4xx(`{error, detail}`) | `REJECTED` | null | null | `{"status": 409, "error": "NO_RUNNING_TASK", "detail": "..."}` |
| 5xx(호스트 503 `CELL_SILENT` 포함), 연결 실패, 시간 초과, 2xx 인데 본문을 못 읽음 | `NO_RESPONSE` | 판단만 재조회 결과(§9.5). 장애 주입은 늘 null | null | null |

### 9.2 `GET /api/incidents`

호스트 `GET /host/incidents`(§3)를 그대로 중계한다. 모드와 관계없다.

- 쿼리 `limit`(선택). 있으면 1~500 정수여야 하고 그대로 호스트에 싣는다. 없으면 싣지 않는다(호스트 기본 50). 정수가 아니거나 범위 밖이거나 빈 값(`?limit=`)이면 400 `INCIDENT_BAD_REQUEST`(detail `limit 은 1~500 의 정수다: <값>`)이고 호스트를 부르지 않는다.
- 200: §3 본문 그대로(`{"instanceId", "total", "incidents"}`, 줄은 19칸).
- 호스트 불통(연결 실패, 시간 초과, 200 아님, 객체 아님): 503 `{"error": "HOST_SILENT", "detail": "실행 호스트가 답하지 않는다: <이유>"}`.

### 9.3 `GET /api/incidents/{incidentId}`

호스트 `GET /host/incidents/{incidentId}`(§4)를 그대로 중계한다. 모드와 관계없다. id 는 경로 조각으로 인코딩해 넘긴다.

| 호스트가 한 것 | 운영 서비스 |
|---|---|
| 200 객체 | 200, §4 본문 그대로(29칸) |
| 404 이고 `error` 가 `INCIDENT_NOT_FOUND` | 404, 호스트 본문 그대로(`{"error": "INCIDENT_NOT_FOUND", "detail": "..."}`) |
| 그 밖(연결 실패, 시간 초과, 다른 404, 5xx, 200 인데 객체 아님) | 503 `HOST_SILENT` |

다른 404(예: 스프링 기본 본문 `{"timestamp", "status": 404, "error": "Not Found", "path"}`)는 호스트가 인시던트를 판단한 것이 아니므로 503 이다.

### 9.4 `POST /api/faults`

**엔지니어 모드만**(운영자 모드면 403 `MODE_NOT_ALLOWED`, detail `이 조작은 engineer 모드에서 한다`). op `INJECT_FAULT`. 요청:

```json
{"robotId": "humanoid-01", "kind": "SKILL_EXECUTION_FAILED", "reason": "스킬 실패 시연"}
{"robotId": "quadruped-01", "kind": "CONNECTION", "state": "OFFLINE", "reason": "묵은 값 시연"}
```

| 칸 | 규칙 |
|---|---|
| `robotId` | 필수, 공백이 아닌 문자열 |
| `kind` | 필수, 공백이 아닌 문자열. 값(네 종류)은 운영 서비스가 보지 않는다 |
| `state` | 없거나 `null` 이거나 문자열. 값(세 상태)과 `kind` 와의 짝은 운영 서비스가 보지 않는다 |
| `reason` | 필수(§9.1) |

- 본문이 JSON 객체가 아니거나 위 모양이 아니면 400 `FAULT_BAD_REQUEST`(detail `robotId·kind 는 비어 있지 않은 문자열이고 state 는 없거나 null 이거나 문자열이다`). 모양이 맞고 사유가 없으면 400 `REASON_REQUIRED`(detail `장애 주입 사유가 없다`).
- 종류·상태 값 검사, `kind` 와 `state` 의 짝, 진행 중 태스크 찾기, 기체 확인은 현장이 하고(§1.3) 그 거부가 200 본문의 `rejection` 으로 그대로 온다. 화면은 받는 종류 둘과 상태 셋만 고르게 한다.
- 호스트에 보내는 본문은 `{"robotId", "kind"}` 이고 `state` 가 문자열일 때만 `"state"` 를 더한다(`null` 은 싣지 않는다). `reason` 은 싣지 않는다.

응답 200 `FaultInjectionOutcome`(칸 8개, 이 순서):

```json
{
  "requestId": "6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a",
  "robotId": "humanoid-01",
  "kind": "SKILL_EXECUTION_FAILED",
  "state": null,
  "result": "SUCCEEDED",
  "confirmation": null,
  "fault": {"robotId": "humanoid-01", "kind": "SKILL_EXECUTION_FAILED", "taskId": "JO-1#RACK-204.S01", "taskState": "RETRIABLE", "raised": true},
  "rejection": null
}
```

| 현장(호스트를 거쳐)이 한 것 | `result` | `fault` | `rejection` |
|---|---|---|---|
| 200(§1.2, 본문의 `robotId` 가 문자열) | `SUCCEEDED` | 현장 200 본문 그대로 | null |
| 400 `BAD_REQUEST`·`UNSUPPORTED_FAULT`, 404 `UNKNOWN_ROBOT`, 409 `NO_RUNNING_TASK`·`FAULT_REFUSED`, 415 | `REJECTED` | null | `{"status", "error", "detail"}`(현장 이름 그대로) |
| 호스트 503 `CELL_SILENT`, 호스트 불통, 200 인데 `robotId` 가 문자열이 아님 | `NO_RESPONSE` | null | null |

- 장애 주입은 **재조회하지 않는다.** `confirmation` 은 늘 null 이고 `NO_RESPONSE` 행 하나만 남는다(재조회 지연도 없어 응답이 늦어지지 않는다). 주입의 효과(태스크 상태, 연결 상태)는 다른 조작과 섞여 이 요청의 반영을 가를 칸이 없다. 화면은 실행 목록과 기체 목록에서 확인하게 한다.
- 같은 연결 상태를 다시 넣은 200(`changed: false`)도 `SUCCEEDED` 다.

### 9.5 `POST /api/executions/{executionId}/units/{unitId}/resolve`

**운영자 모드만**(엔지니어 모드면 403 `MODE_NOT_ALLOWED`, detail `이 조작은 operator 모드에서 한다`). op `RESOLVE_OPERATOR_HOLD`. 경로는 호스트(§5)와 같은 꼴이다. 화면은 인시던트 상세의 `executionId`·`unitId` 를 싣는다. 요청:

```json
{"decision": "REWORK", "reason": "랙 재배치 뒤 재작업"}
```

| 칸 | 규칙 |
|---|---|
| `decision` | 필수. `CONFIRM_DONE` 또는 `REWORK`(대소문자 그대로). 그 밖이면 400 `RESOLVE_BAD_REQUEST`(detail `decision 은 CONFIRM_DONE 또는 REWORK 이다`) |
| `reason` | 필수(§9.1). 없으면 400 `REASON_REQUIRED`(detail `판단 사유가 없다`) |

- 본문이 JSON 객체가 아니어도 400 `RESOLVE_BAD_REQUEST` 다. 경로의 `executionId`·`unitId` 는 검사하지 않는다(없는 실행·단위는 호스트가 `NotHeld` 로 답한다). 경로 조각으로 인코딩해 호스트에 넘긴다.
- 승인자는 `X-Ops-User` 다. 호스트에 `{"decision", "approverId": <X-Ops-User>, "requestId": <조작 요청 id>}` 를 보낸다. 본문에 승인자 칸을 받지 않는다(ADR 43: 기본값도, 화면이 고른 다른 이름도 없다). 사유는 호스트에 싣지 않는다(picasso 인시던트에 자리가 없다).

응답 200 `HoldResolveOutcome`(칸 9개, 이 순서):

```json
{
  "requestId": "3f0c6c1e-6a0e-4f43-9a52-2a3b4a9e8d10",
  "executionId": "exec-1",
  "unitId": "rack-arrival",
  "decision": "REWORK",
  "result": "SUCCEEDED",
  "confirmation": null,
  "outcome": "Resolved",
  "answer": {"result": "Resolved", "detail": null, "incidentId": "incident-1", "requestId": "3f0c6c1e-6a0e-4f43-9a52-2a3b4a9e8d10"},
  "rejection": null
}
```

| 칸 | 뜻 |
|---|---|
| `outcome` | 호스트 판단 결과 이름 그대로(`Resolved`, `NotHeld`, `Refused`). 호스트가 200 으로 답했을 때만 있고 그 밖에는 null. **화면은 `result` 가 아니라 이 칸으로 가른다**(`NotHeld` 는 «보류 단위가 아닙니다», `Refused` 는 «에이전트 판단은 거부됩니다», 스펙 §8.3) |
| `answer` | 호스트 200 본문(§5.2) 그대로. 200 이 아니면 null |

**결과 매핑**:

| 호스트 200 본문의 `result` | `result` |
|---|---|
| `Resolved` | `SUCCEEDED` |
| `NotHeld`, `Refused` | `REJECTED`(조작 기록의 응답 칸 본문에 원래 이름) |
| 그 밖의 이름이나 `result` 가 문자열이 아님 | 본문을 못 읽은 것이라 `NO_RESPONSE` 와 재조회 |

호스트 4xx(본문이 틀린 400 `BAD_REQUEST`, 415)는 `REJECTED` 와 `rejection` 이다. 운영 서비스가 먼저 검사하므로 정상 흐름에서는 나지 않는다.

**응답 없음 뒤 재조회**(§5.4 그대로): 응답 없음 1초 뒤 한 번 `GET /host/incidents?limit=500` 을 읽는다.

- 요청을 보내기 **직전**의 운영 서비스 실제 시각을 `sentAt` 으로 정해 둔다(요청 id 를 만든 뒤, 호스트를 부르기 바로 앞).
- `executionId`·`unitId` 가 같은 인시던트 가운데 **하나라도** `resolution != null`, `resolution.decidedBy.id == X-Ops-User`, `resolution.decision == 요청한 decision`, `Instant.parse(resolution.wallClockAt) >= sentAt` 을 모두 만족하면 `CONFIRMED_APPLIED` 다. 관측(`observed`)은 그 인시던트 줄이다.
- 어느 것도 없으면 `CONFIRMED_NOT_APPLIED` 다. 관측은 그 실행·단위의 가장 최근 인시던트 줄(목록의 첫 것)이고 없으면 null 이다.
- 목록을 못 읽으면(호스트 불통, 200 아님, `incidents` 가 배열 아님) 확인 못 함이라 `confirmation` 이 null 이고 확인 행을 붙이지 않는다.
- «가장 최근 인시던트» 와 «그 단위가 보류가 아님» 조건은 쓰지 않는다(§5.4). 같은 사람이 같은 단위에 앞서 같은 결정을 낸 기록은 `wallClockAt < sentAt` 이라 걸러진다. `wallClockAt` 이 `sentAt` 과 같으면 반영이다.
- `sentAt` 은 운영 서비스 시계, `wallClockAt` 은 호스트 시계다. 둘이 같은 기계(루프백)에 있다는 전제다.

### 9.6 조작 기록(`GET /api/operations` 의 행)

| 조작 | `mode` | `target` | `request`(JSON 문자열) | `reason` |
|---|---|---|---|---|
| 장애 주입 | `ENGINEER` | 기체 id | `{"op":"INJECT_FAULT","robotId":"humanoid-01","kind":"SKILL_EXECUTION_FAILED","state":null}` | 깎은 사유 |
| 운영자 판단 | `OPERATOR` | `<executionId>/<unitId>`(예 `exec-1/rack-arrival`) | `{"op":"RESOLVE_OPERATOR_HOLD","executionId":"exec-1","unitId":"rack-arrival","decision":"REWORK","approverId":"kim"}` | 깎은 사유 |

- `user` 는 `X-Ops-User` 이고 판단에서는 `request.approverId` 와 같다.
- `request` 의 `state` 는 장애 주입 요청에 없거나 `null` 이었으면 `null` 이다.
- `targetResponse`: 호스트가 답했으면 `{"status": 200, "body": {...}}`(본문이 JSON 객체가 아니면 `body` 는 글자). 판단 결과 이름은 `body.result`, 현장 거부 이름은 `body.error` 에 있다. 닿지 않았으면 `{"cause": "응답 없음: ConnectException"}`. 판단 재조회 행(`CONFIRMED_*`)은 `{"observed": <인시던트 줄>}` 또는 `{"observed": null}` 이다.
- 승인자, 결정, 사유, 실행 id, 단위 id, 결과가 한 행에 다 있다: 승인자·결정·실행·단위는 `request`, 사유는 `reason`, 결과는 `result`(SUCCEEDED·REJECTED·NO_RESPONSE)와 `targetResponse.body.result`(원래 이름).

### 9.7 상태 코드 요약(운영 서비스, S3b 계약 §10.10 에 더함)

| 엔드포인트 | 200 | 400 | 403 | 404 | 415 | 503 |
|---|---|---|---|---|---|---|
| `GET /api/incidents` | 호스트 본문 | `INCIDENT_BAD_REQUEST` | | | | `HOST_SILENT` |
| `GET /api/incidents/{incidentId}` | 호스트 본문 | | | 호스트 `INCIDENT_NOT_FOUND` 본문 그대로 | | `HOST_SILENT` |
| `POST /api/faults` | 보냄(결과는 본문) | `ACTOR_REQUIRED`·`FAULT_BAD_REQUEST`·`REASON_REQUIRED` | `MODE_NOT_ALLOWED`(운영자) | | JSON 아님 | |
| `POST /api/executions/{e}/units/{u}/resolve` | 보냄(결과는 본문) | `ACTOR_REQUIRED`·`RESOLVE_BAD_REQUEST`·`REASON_REQUIRED` | `MODE_NOT_ALLOWED`(엔지니어) | | JSON 아님 | |

운영 서비스 오류 이름(§7 에 더함):

| 이름 | 상태 | 자리 |
|---|---|---|
| `INCIDENT_BAD_REQUEST` | 400 | `GET /api/incidents` 의 `limit` |
| `FAULT_BAD_REQUEST` | 400 | `POST /api/faults` 본문 |
| `RESOLVE_BAD_REQUEST` | 400 | 판단 본문 |
| `REASON_REQUIRED` | 400 | 장애 주입, 판단(현장 설정 변경·임무 활성화와 같은 이름) |
| `HOST_SILENT` | 503 | 두 인시던트 읽기(S3a 와 같은 이름) |

### 9.8 운영 서비스 쪽 한계

- 판단 재조회는 응답 없음 1초 뒤 한 번이다. 호스트가 잠금을 기다리느라 운영 서비스의 5초 요청 제한을 넘긴 판단은, 재조회 때 아직 서지 않았으면 `CONFIRMED_NOT_APPLIED` 로 남고 그 뒤에 설 수 있다. 호스트가 요청 id 를 저장하지 않아 «처리 중» 을 가를 수 없다(S3b 의 `REQUEST_IN_PROGRESS` 같은 응답이 없다). 화면은 인시던트 목록을 다시 읽게 한다.
- 장애 주입의 `NO_RESPONSE` 는 반영 여부를 끝내 모른다(재조회하지 않는다).

---

## 10. 화면이 기대는 것(Playwright 가 쓰는 글자)

화면이 실제로 보이는 이름과 문구다. Playwright 는 이 글자로 찾는다. 역할(role)과 접근 이름(name)은 Testing Library·Playwright 의 `getByRole(role, { name })` 기준이다. `<what>` 은 아래 각 절의 조작 이름이다. API 모양은 §9 그대로이며 화면 때문에 바꾼 것은 없다.

### 10.1 영역과 모드

- 영역 버튼: `현장·자원`, `운영`(S2·S3a 그대로). 모드 라디오: `엔지니어`, `운영자`. 사용자 칸은 S3a 그대로이며 판단자(`decidedBy.id`)가 이 값이 된다.
- 인시던트·실행·셀 대역은 «운영» 영역이 열려 있을 때만 5초마다 읽는다. 판단 직후에는 곧바로 한 번, 0.5초(`SIGNAL_SETTLE_MS`) 뒤 한 번 더 읽는다(인시던트 목록, 고른 상세, 실행 목록, 셀 대역).

### 10.2 장애 주입(현장·자원 영역, 스펙 §8.1)

| 무엇 | 글자 |
|---|---|
| 구역 | region `장애 주입`(현장 설정 구역 `현장 설정` 다음) |
| 폼(엔지니어 모드) | form `장애 주입 폼` |
| 기체 선택 | 라벨 `기체`(select). 기체 목록 가운데 퇴역이 아닌 것, 목록 순서. 처음 값은 첫 기체 |
| 종류 선택 | 라벨 `장애 종류`. 보기 글자 `스킬 실패(진행 중 태스크)`(값 `SKILL_EXECUTION_FAILED`), `연결 상태`(값 `CONNECTION`). 처음 값은 스킬 실패 |
| 연결 상태 선택 | 종류가 연결 상태일 때만 라벨 `연결 상태`. 보기 글자 `OFFLINE`, `CONNECTION_BROKEN`, `ONLINE(복구)`(값은 앞 둘 그대로, 마지막은 `ONLINE`) |
| 사유 | 라벨 `장애 주입 사유`. 비었거나 공백뿐이면 보내지 않고 alert `장애 주입 사유를 넣으십시오` |
| 버튼 | `장애 넣기` |
| 운영자 모드 | 폼과 버튼 없이 `장애 주입은 엔지니어 모드에서 합니다` |
| 결과 | status `장애 주입 결과`, 글자 `<what>: <결과>` |

`<what>` 은 스킬 실패면 `<robotId> 스킬 실패`, 연결 상태면 `<robotId> 연결 상태 <state>`(예 `quadruped-01 연결 상태 OFFLINE`).

| 결과 | 글자 |
|---|---|
| 받아들임, 스킬 실패 | `받아들임(태스크 <taskId>, <taskState>)`, `raised` 가 거짓이면 뒤에 `. 같은 결함이 이미 서 있었습니다` |
| 받아들임, 연결 상태 바뀜 | `받아들임(연결 상태 <state>)` |
| 받아들임, 같은 상태 | `받아들임(이미 <state> 상태라 바뀐 것 없음)` |
| 현장 거부 | `현장이 거부함(<풀이>). <detail>`. 풀이: `NO_RUNNING_TASK` → `진행 중 태스크 없음`, `UNKNOWN_ROBOT` → `모르는 기체`, `UNSUPPORTED_FAULT` → `받지 않는 장애 종류`, `FAULT_REFUSED` → `mimic 엔진이 강제를 거부함`, `BAD_REQUEST` → `본문 오류`, `UNSUPPORTED_MEDIA_TYPE` → `JSON 이 아닌 본문`. 거부 본문을 못 읽어 `error` 가 null 이면 `현장이 거부함(이유 없음)` 이고 `detail` 이 null 이면 뒤 문장을 붙이지 않는다 |
| 응답 없음(현장 불통, 호스트 불통 모두) | `응답 없음. 넣었는지 모릅니다. 실행 목록과 기체 목록에서 확인하십시오` |
| 사전 거부(400·403) | `보내지 않음(<풀이>). <detail>`(예 `보내지 않음(이 모드에서 할 수 없는 조작). ...`) |
| 운영 서비스 그 밖 | `결과 모름(<원인>). 실행 목록과 기체 목록에서 확인하십시오` |

### 10.3 인시던트(운영 영역, 스펙 §8.2)

| 무엇 | 글자 |
|---|---|
| 구역 | region `인시던트`(실행 구역 다음, 셀 대역 구역 앞) |
| 머리 | `실행 호스트 인스턴스 <instanceId>, 인시던트 <total>건 가운데 최신 <n>건` |
| 없음 | `인시던트가 없습니다` |
| 못 읽음 | `모름: 인시던트 목록을 아직 읽지 못했습니다 (<detail>)`, 읽은 뒤 불통이면 `직전 값입니다. 실행 호스트 불통: <detail>` |
| 표 | table `인시던트 목록`. 줄 순서는 호스트 순서(최신부터) 그대로 |
| 열 | `인시던트`, `발생 시각`, `기체`, `실행 id`, `단위`, `실패 종류`, `경로`, `현장 설정 버전`, `임무 버전`, `판단`, `보류` |
| 칸 값 | 발생 시각은 `at` 그대로. 실패 종류 null 은 `-`. 현장 설정 버전 `버전 N`(null 은 `모름`). 임무 버전 `버전 N`(null 은 `코드 정의`) |
| 판단 칸 | `미해결`(`unresolved` 이고 판단 없음), `판단됨`, `판단됨(설비 근거 없이 완료 확인)`(`confirmedWithoutEvidence`), `판단 대상 아님`(봉인 때 보류·불확실이 아니었음) |
| 보류 칸 | `held` 일 때만 `보류 중`(굵게, 줄에 class `held`), 아니면 `-` |
| 상세 열기 | 줄의 첫 칸 버튼, 접근 이름 `<incidentId> 상세 보기`(보이는 글자는 `<incidentId>`) |

상세(region `인시던트 상세`, 제목 `<incidentId> 상세`):

| 무엇 | 글자 |
|---|---|
| 읽는 중, 못 읽음, 없음 | `상세를 읽는 중입니다`, `모름: 상세를 읽지 못했습니다 (<detail>)`, `실행 호스트에 이 인시던트가 없습니다. 실행 호스트를 재기동했을 수 있습니다`(404 `INCIDENT_NOT_FOUND`) |
| 판단 없음 | `사람의 판단 없음. 아래 값은 모두 관측입니다` |
| 사람의 판단 | region `사람의 판단`(관측과 다른 구역, class `asserted`). 첫 줄 `사람이 판단함: <decidedBy.id>, <wallClockAt>`, 둘째 줄 `결정 <완료 확인|재작업>(<decision>), 호스트 시각 <at>`, `confirmedWithoutEvidence` 면 굵게 `설비 근거 없이 완료 확인` |
| 관측 | region `관측`, 제목 `관측(봉인 때 기록)`. 칸 이름(dt): `발생 시각(호스트 시계)`, `봉인한 실제 시각`, `기체`, `실행`, `단위`, `단위의 지금 상태`, `실패 종류`, `경로`, `현장 설정 버전`, `시간값`, `임무 버전`, `단계 위치`, `필요 근거 등급`, `도달 근거 등급`, `확인 결과(코드 이름)`, `파지(기대/관측)`. 있을 때만 `위반된 사전 조건`, `효과와 관측의 어긋남`, `연결` |
| 칸 값 | 현장 설정 버전·임무 버전은 `intent` 의 값(`버전 2`, `PrepareSequencedRack 버전 3` 또는 `PrepareSequencedRack 코드 정의`). 시간값 `근거 윈도우 앞 폭 30초, 뒤 폭 15초, inDoubtGrace 60초, stallWindow 300초`. 단계 위치 `1/2. 계획 rack-arrival → RACK-204.S01. 끝난 단위 없음`. 확인 결과는 코드 이름 그대로(`NOT_REQUESTED`), 풀지 않는다 |
| 결함 | 제목 `결함`. 없으면 `없음(결함 없이 실패를 알림)`, 있으면 aria-label `이 단위의 결함` 인 목록(칸 `분류` = `GRASP_FAILED(SKILL_EXECUTION_FAILED)`, `조치 힌트`(빈 값은 `없음`), `참조` = `KEY_SKILL_ID=pick_place, KEY_TASK_ID=...`, `지금 태스크 계속`, `새 태스크 받기`(`가능`·`불가`), `유지`) |
| blockedBy | 제목 `실행을 막던 결함(blockedBy)`. 없으면 `없음`, 있으면 aria-label `실행을 막던 결함 <n>` 인 목록(칸은 결함과 같다) |
| 근거 윈도우 | 제목 `근거 윈도우(앞 30초, 뒤 15초)`. table `근거 윈도우`, 열 `순번`, `시각`, `종류`, `내용`, `출처`(`미들웨어 기록`·`현장 관측`). 비면 `윈도우 안의 관측이 없습니다`, 버린 관측이 있으면 `윈도우 밖이라 버린 관측이 있습니다` |

### 10.4 운영자 판단(인시던트 상세 안, 스펙 §8.3)

| 무엇 | 글자 |
|---|---|
| 자리 | 상세의 `held` 가 참일 때만. 거짓이면 판단 폼도 문구도 없다 |
| 폼(운영자 모드) | form `운영자 판단`. 머리 `<executionId>/<unitId> 보류를 판단합니다. 판단자는 <사용자> 입니다` |
| 사유 | 라벨 `판단 사유`. 비었거나 공백뿐이면 보내지 않고 alert `판단 사유를 넣으십시오` |
| 버튼 | `완료 확인`(`CONFIRM_DONE`), `재작업`(`REWORK`). 그 밖의 버튼(풀이, 중단)은 없다 |
| 엔지니어 모드 | 버튼 없이 `보류 중입니다. 운영자 판단은 운영자 모드에서 합니다` |
| 결과 | status `판단 결과`(인시던트 구역 머리, 상세를 바꿔도 남는다), 글자 `<executionId>/<unitId> <완료 확인|재작업>: <결과>` |

| 결과(`outcome`·`result`·`confirmation`) | 글자 |
|---|---|
| `Resolved` | `판단이 섰습니다(<answer.incidentId>)`, incidentId 가 null 이면 `판단이 섰습니다` |
| `NotHeld` | `보류 단위가 아닙니다` |
| `Refused` | `에이전트 판단은 거부됩니다. <answer.detail>`(detail 이 없으면 마침표 앞까지) |
| `REJECTED` 이고 `rejection`(호스트 4xx) | `실행 호스트가 거부함(<풀이>). <detail>`. `error` 가 null 이면 `실행 호스트가 거부함(이유 없음)`, `detail` 이 null 이면 뒤 문장 없음 |
| `NO_RESPONSE`, `CONFIRMED_APPLIED` | `응답은 없었으나 다시 읽어 보니 판단이 붙음` |
| `NO_RESPONSE`, `CONFIRMED_NOT_APPLIED` | `응답 없음. 다시 읽어 보니 판단이 붙지 않음. 늦게 붙을 수 있으니 인시던트 목록에서 확인하십시오` |
| `NO_RESPONSE`, 확인 못 함 | `응답 없음. 판단이 붙었을 수 있음. 다시 읽지도 못해 확인하지 못했습니다. 인시던트 목록에서 확인하십시오` |
| 사전 거부(400·403) | `보내지 않음(<풀이>). <detail>`(403 은 `이 모드에서 할 수 없는 조작`, 400 `REASON_REQUIRED` 는 `사유 없음`, `RESOLVE_BAD_REQUEST` 는 `판단 본문 오류`) |
| 운영 서비스 그 밖 | `결과 모름(<원인>). 인시던트 목록에서 확인하십시오` |

### 10.5 임무·정책 영역

템플릿 `ARRIVAL_WAIT_HOLD` 의 불러오기 버튼은 `운영자 보류 대기 템플릿 불러오기` 다(앞 둘은 `데이터 정의 템플릿 불러오기`, `랙 도착 대기 템플릿 불러오기` 그대로).

### 10.6 Playwright 가 기댈 흐름(참고)

- 장애 주입 뒤 효과는 장애 주입 결과가 아니라 «운영» 영역의 실행 목록(단위 상태)과 인시던트 목록에서 확인한다. 스킬 실패는 다음 시계 진행 뒤에야 보인다(§8).
- 보류 판단은 «운영» 영역에서 `보류 중` 줄의 `<incidentId> 상세 보기` → 상세의 `판단 사유` → `재작업`. 판단 뒤 그 줄의 판단 칸이 `판단됨`, 보류 칸이 `-` 이 되고 상세에 region `사람의 판단` 이 선다(곧바로 또는 0.5초 뒤 다시 읽기, 늦어도 다음 5초 주기).

---

## 11. 통합 시험이 기대는 것

통합 시험 `FaultIncidentTest` 와 Playwright 생애주기 시험이 실측으로 기대는 순서와 시간이다. 앞 절의 모양은 바꾸지 않는다.

### 11.1 통합 시험의 순서

| 단계 | 작업 지시(슬롯) | 활성 임무 버전 | 현장 설정 버전 | `pick_place` 번호(시드 0) |
|---|---|---|---|---|
| 1 | `RACK-204.S01` | 1(`DATA_V1`) | 1 | 1번째, 강제 |
| 2a | `RACK-204.S02` | 1 | 1 | 2번째 |
| 2b | 없음(유휴) | 1 | 1 → 2(연결 기준 60초) | 없음 |
| 3, 4 | `RACK-204.S03` | 2(`ARRIVAL_WAIT_HOLD`) | 2 | 3번째(재작업은 대기 단위라 `pick_place` 를 더하지 않는다) |
| 5 | `RACK-204.S04` | 2 | 2 | 4번째 |

- 작업 지시 하나에 슬롯 하나다. 스킬 실패는 새 스택에서 아직 채운 적 없는 S01 에 먼저 넣는다. 첫 `pick_place` 를 강제했으므로 자연 실패는 13번째이고 이 시험의 `pick_place` 는 넷이다.
- 인시던트는 단계 1 의 `GRASP_FAILED` 하나, 단계 3 과 5 의 `SIGNAL_DEADLINE` 둘로 모두 셋이다(단계 2a 의 단절은 인시던트를 남기지 않는다). 그래서 단계 1 인시던트는 현장 설정 버전 1, 보류 인시던트는 버전 2 를 든다.

### 11.2 시간

- 스킬 실패: 작업 지시 뒤 가상 시계를 5초 한 번 밀면 태스크가 `RUNNING` 이고 그때 넣으면 200 이다. 다음 밀기부터 미들웨어가 보고, 공용 진행기로 밀면 단위 `FAILED`, 실행 `FAILED` 로 종결한다.
- 단계 2a: 시계를 밀지 않아도 OFFLINE 주입 뒤 실제 시간 수 초 안(시험 상한 5초, pump 250ms)에 실행이 `IN_DOUBT` 이고 단위는 `RUNNING` 그대로다. ONLINE 도 같이 곧바로 `RUNNING` 으로 돌아온다. 그 뒤 진행기로 밀면 `PHYSICALLY_DONE`, 단위 `DONE`·`E2` 다.
- 단계 2b: 연결 기준 60초에서 OFFLINE 주입부터 오래됨까지 실측 약 62초(실제 시간)다. 5초마다 가상 시계를 31초 밀어 quadruped-01 은 내내 신선했다. 배정 가능 표의 이유는 `연결이 오래됐다(기준 60초, 현장 설정 버전 2)` 다. ONLINE 주입은 시계를 밀지 않아도 실제 시간 수 초 안에 신선으로 돌아온다(연결 메시지가 registry 의 마지막 보고 시각을 새로 세운다).
- 단계 3: 대기 시작 뒤 5초씩 밀어 다섯 번 안(가상 20초 이상)에 단위와 실행이 `OPERATOR_HOLD` 다. 판단 없이 더 밀어도 보류 그대로다.
- 단계 4: 재작업 판단의 응답 직후 대기 단위는 `PENDING` 또는 `RUNNING`, 실행은 `RUNNING` 이다. 다음 밀기 전에 `rack_present` 를 켜면 두 번째 보류 없이 `PHYSICALLY_DONE`, 두 단위 `DONE`·`E2` 다.
- 단계 5: 완료 확인 응답 직후 대기 단위는 `DONE`·`E0` 이고, 이어지는 `pick_place` 는 `DONE`·`E2` 다.
- 시험 클래스 하나가 실측 95~111초이고, 그 가운데 단계 2b 가 약 62초다.

### 11.3 Playwright

- 영역을 옮기면 작업 지시 폼이 처음 값(`InspectAsset`)으로 돌아온다. 두 번째 작업 지시 전에 임무, 슬롯, 자재를 다시 고른다.
- 앞 단계가 연결 기준(버전 2)과 `stallWindow`(버전 3)를 바꾸므로 Playwright 의 인시던트 줄은 현장 설정 `버전 3` 이다. 스킬 실패 인시던트의 임무 버전은 `버전 1`, 보류 인시던트는 `버전 2` 다.
- 앞 단계가 `rack_present` 를 켜 두므로 보류 단계 전에 끈다.
- 재작업 판단 뒤 화면의 곧바로 읽기와 0.5초 뒤 읽기로 대기 단위 `RUNNING` 이 실제 시간 10초 안에 보인다. 그때 신호를 켜면 새 기한 20초 안이다.
- 시험 하나가 실측 3.4분(스택 기동 포함 4.0분)이다. 시험 제한 420초는 그대로 두었다.
