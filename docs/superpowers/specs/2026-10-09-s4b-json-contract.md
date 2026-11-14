# S4b JSON 계약: 재기동 복원, 송신 기록, 이전 인스턴스 인시던트

S4b 설계 스펙(`docs/superpowers/specs/2026-10-09-s4b-restart-recovery-design.md`)의 실행 호스트와 운영 서비스가 실제로 내고 받는 모양이다. 화면, 통합 시험과 Playwright 는 이것에 맞춘다. 코드 주석의 «S4b 계약 H1» 이나 «S4b JSON 계약 O3» 같은 표기는 이 문서의 절(H·O·C·E)을 가리킨다. picasso 쪽 API 는 `74e4d3d`(P6, `Middleware.resume`)이며 첫 절은 그 위에서 호스트가 기대는 것만 적는다.

HA 단계 0(picasso `8ec3534`)에서 `Middleware.resume` 에 판단 기록 인자(`record`)가 더해졌고 실행 호스트가 그것을 넘긴다. 그래서 아래 «P6 Middleware.resume › 넘어오지 않는 것» 은 기록 없이 리빌드할 때의 거동이다. H1·H2·H8·E3 의 판단 기록·다른 인스턴스 관련 문장은 HA 단계 0 에서 고쳤다.

## P6 Middleware.resume

picasso `74e4d3d`(P6 머지, 앞 기준 `195c1ee`) 의 API 입니다. 계약 proto, 계약 버전 0.9.0, 내보내기 버전 6 은 바뀌지 않습니다.

### 시그니처

```kotlin
// picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt
fun resume(order: JobOrder, robotId: String, mission: ActiveMission): Middleware.Submission

// Middleware.Companion
const val RESUME_SNAPSHOT_UNREADABLE = "기체 스냅숏을 못 읽어 다시 짓지 않는다"
const val RESUME_ATTEMPT = "RESUME_ATTEMPT"
const val RESUME_SIGNAL_PASSED = "RESUME_SIGNAL_PASSED"
const val RESUME_PRIOR_ROBOT = "RESUME_PRIOR_ROBOT"
```

스레드가 없는 미들웨어라 `submit`·`pump` 와 같은 호스트 잠금 아래에서 부릅니다. 기동 복원은 pump 를 켜기 전에 부릅니다.

### 결과

| 결과 | 언제 | 호스트 분류(T3) |
|---|---|---|
| `Accepted(execution)` | 같은 작업 지시 id 의 실행이 없고 리빌드했음. 새 `exec-N`, `revision = order.version`, `physicalState = ACCEPTED` | RESTORED |
| `Idempotent(execution)` | 같은 작업 지시 id 의 실행이 이미 있고 버전이 같음(`revise` 경로) | 이번 기동에서 두 번 부른 경우뿐 |
| `Accepted(existing)` | 같은 작업 지시 id 의 실행이 있고 버전이 더 높음(`revise` 경로, 스냅숏을 안 읽음) | 호스트가 부를 일 없음 |
| `Rejected(reason)` 이고 `reason.startsWith(Middleware.RESUME_SNAPSHOT_UNREADABLE)` | `robots.snapshot(robotId)` 가 `null` | DEFERRED (pump 마다 다시) |
| `Rejected(reason)` 그 밖 | 아래 사유 | GAVE_UP |

`Rejected` 의 `remedy`·`alternativeLocations` 는 늘 `null`, `remedyWithheld` 는 `false` 입니다. 조치 탐색 기록(`remedySearches()`)에 아무것도 남지 않습니다.

사유 문자열(정확한 모양):

- 스냅숏: `기체 스냅숏을 못 읽어 다시 짓지 않는다: robot=<robotId>, 재작업 횟수와 지난 설비 대기를 정할 근거가 없다`
- WorkMaster 불일치: `넘겨받은 임무 정의의 WorkMaster 가 작업 지시와 다르다: 정의=<capability.workMasterId>, 작업 지시=<order.workMasterId>`
- 근거 등급(`submit` 과 같은 문장): `요구 근거 등급 <E?> 은 <workMasterId> 의 최고 등급 <E?> 를 넘는다 — 확인 수단이 없다`
- `revise` 경로의 거부(`submit` 과 같은 문장): `리비전이 WorkMaster 를 바꾼다: ...`, `이미 지난 버전이다: ...`, `종착한 실행에는 새 버전이 붙지 않는다 — ...`, 연쇄 거부

검사 순서는 기존 실행 → WorkMaster 대조 → 스냅숏 한 번 읽기 → 근거 등급 → 계획과 생성입니다(시험이 순서를 댑니다: 스냅숏도 못 읽고 WorkMaster 도 다르면 사유는 WorkMaster 쪽이라 GAVE_UP). 배정 게이트(`admits`)는 부르지 않습니다.

### 실행 트레이스 줄

`execution.eventTrail` 의 `ObservedEvent(local = true, sequence = 그 기체 뷰의 커서, 아직 뷰가 없으면 0, occurredAt = now())` 입니다. 단위 메모(`unit.note`)에는 적지 않으므로 작업 응답의 `incompleteUnits` 값은 그대로입니다.

- `kind = "RESUME_ATTEMPT"`, 스냅숏에 태스크가 있는 로봇 단위마다 한 줄(N 이 0 이어도):
  `detail = "<unitId>: attempt=<N> (스냅숏 태스크 <그 N 의 taskId>)"`
- `kind = "RESUME_SIGNAL_PASSED"`, 통과로 둔 설비 대기 단위마다 한 줄:
  `detail = "<unitId>: 뒤 로봇 단위 <마지막 로봇 단위 id> 의 태스크가 기체에 있어 통과한 것으로 둔다, 이 인스턴스가 관측하지 않아 근거 등급 E0"`
- `kind = "RESUME_PRIOR_ROBOT"`, 마지막 로봇 단위보다 앞선 로봇 단위마다 한 줄(스냅숏에 그 태스크가 없어도):
  `detail = "<unitId>: 뒤 로봇 단위 <마지막 로봇 단위 id> 보다 앞서 성공을 다시 관측해도 설비 근거를 다시 묻지 않는다, 근거 등급 E0"`

줄 순서는 단위 순서로 `RESUME_ATTEMPT` 전부, 그다음 `RESUME_SIGNAL_PASSED` 전부, 그다음 `RESUME_PRIOR_ROBOT` 전부입니다. 호스트가 «이전 실행 id» 를 화면에 붙이려면 트레이스가 아니라 자기 일지의 값을 씁니다(미들웨어는 이전 실행 id 를 모릅니다).

### 재작업 횟수와 설비 대기 규칙

- 로봇 경로 단위마다 스냅숏 `tasks` 키(태스크 id) 가운데 `"$jobOrderId#$unitId"`(N=0) 이거나 `"$jobOrderId#$unitId@r<N>"`(`<N>` 은 정규식 `[1-9]\d*`, 곧 `startUnit` 이 붙이는 모양이고 수로 견줌. 단위 id 뒤 나머지 전체가 맞아야 하므로 `S01` 의 태스크로 `S010@r3` 를 읽지 않음) 인 것의 가장 큰 N 을 `unit.attempt` 에 넣습니다. 계약 `TaskSnapshot.attempt` 는 읽지 않습니다(`RobotSnapshot` 에 그 칸이 없습니다).
- 스냅숏에 태스크가 있는 마지막 로봇 단위(계획 순서, 플릿 단위는 세지 않음)보다 앞선 `SIGNAL` 단위는 `DONE`, `reached = E0`, `verification = NOT_REQUESTED`, `taskId = ""` 입니다. 그 뒤의 `SIGNAL` 단위와 모든 로봇·플릿 단위는 `PENDING` 이고, 첫 pump 가 첫 `PENDING` 단위부터 같은 태스크 id·리비전으로 `StartTask` 를 다시 보냅니다. 마지막 로봇 태스크 뒤의 설비 대기는 다시 기다리므로 재기동 사이에 바뀐 신호로 곧바로 보류나 중단이 날 수 있습니다.
- 그보다 앞선 로봇 단위는 pump 가 `SUCCEEDED` 를 다시 관측하면 설비 근거를 다시 묻지 않고(설비 신호 트레이스 줄도 없음) `DONE`, `reached = E0`, `verification = NOT_REQUESTED` 로 닫습니다. 요구 등급이 E2 여도 같습니다. 실패로 다시 관측되면 보통의 실패 경로(근거 확인 포함, 인시던트)를 탑니다. 마지막 로봇 단위는 보통대로 확인합니다(E2 가능).
- 그래서 앞서 통과한 대기나 앞선 로봇 단위가 있는 실행의 작업 응답은 `reachedEvidence = E0` 입니다.
- 플릿 단위는 재작업 횟수를 되찾지 않습니다. 스냅숏이 종료한 태스크를 빼는 발신자면 마지막 로봇 태스크 앞의 로봇 단위가 새 태스크로 다시 나갑니다(mimic 은 종료한 태스크를 싣습니다). 한계 §15.213.

### 넘어오지 않는 것

운영자 판단, 수용한 결함(`acknowledgedFaults`), 승인 소모 기록과 `approvedBy`, 근거 윈도우의 요청 시각(`requestedAt`), 설비 대기의 기한 시작 시각, 앞선 로봇 단위에 이전 인스턴스가 낸 근거 확인 결과(불일치였어도 E0 `DONE` 으로 보임). 실행 id, 작업 응답 id, 인시던트 id 는 새 인스턴스의 셈입니다.

### 호스트가 넘길 것

- `order`: 일지에 적은 작업 지시 JSON 을 그대로 되돌린 `JobOrder`(같은 `jobOrderId`, 같은 `version`). **이전 인스턴스가 받은 마지막 버전이어야 합니다**(태스크 멱등은 같은 `task_id`·`revision` 에만 섭니다). 운영 서비스는 리비전을 내지 않으므로 버전은 받은 값입니다.
- `robotId`: 일지의 배정 기체.
- `mission`: 일지의 임무 버전으로 짓습니다.
  - 임무 버전이 있으면(데이터 정의) `mission.mission_version` 에서 그 버전의 정의 문서를 읽어 `ActiveMission(DefinedCapability(parsed), missionVersion = <그 버전>)`. 파싱은 `MissionDefinitionParser.parse(text)` 의 `MissionParse.Parsed.definition`. `DefinedCapability` 는 활성화 때 검증을 통과한 정의를 전제하므로 저장된 정의를 그대로 씁니다. 못 읽거나 파싱이 실패하면 `resume` 을 부르지 않고 GAVE_UP.
  - 임무 버전이 `null` 이면(코드 정의) `ActiveMission(<그 WorkMaster 의 코드 케이퍼빌리티>, missionVersion = null)`. 코드 케이퍼빌리티는 `MissionCatalog.codeCapabilities().first { it.workMasterId == order.workMasterId }` 로 얻습니다(카탈로그의 `active()` 는 지금 활성 데이터 버전을 줄 수 있으므로 쓰지 않습니다).

## mission-host (실행 호스트)

picasso 서브모듈 `74e4d3d` 위의 실행 호스트가 실제로 내고 받는 모양입니다. 공통 규칙은 S4a JSON 계약 그대로입니다(UTF-8 JSON, 시각은 ISO-8601 UTC 문자열이고 소수 초 자릿수 고정 아님, `null` 칸 생략 없음, 칸 순서는 아래 표의 순서이며 시험이 대조함, 거부 본문 `{"error", "detail"}` 이고 분기는 `error` 로만). 바뀐 곳은 모두 칸을 **끝에** 더한 것이고, 판단 요청의 `instanceId` 필수만 예외입니다.

**앞 계약에서 이 문서가 대체하는 곳**

| 앞 자리 | 대체하는 절 | 바뀐 것 |
|---|---|---|
| S3a JSON 계약 §5 `GET /host/executions` | H1 | 본문 끝에 `restore`, 실행마다 끝에 `restoredFrom`. `jobResponse` 의 «호스트는 `ack` 하지 않는다» 는 이제 틀림(pump 뒤 기록 뒤 `ack`). 화면 값은 같음 |
| S4a JSON 계약 §3 `GET /host/incidents` | H3 | 본문 끝에 `earlierTotal`, `earlier`. «재기동하면 사라진다» 는 이제 `earlier` 로 남음 |
| S4a JSON 계약 §4 `GET /host/incidents/{incidentId}` | H4 | 질의 `instanceId`(선택). 재기동으로 사라진 id 는 질의 없이는 여전히 404 |
| S4a JSON 계약 §5.1·§5.3 판단 요청 | H5 | 본문 `instanceId` 필수. 없으면 400, 다르면 409 `INSTANCE_MISMATCH` |
| S4a JSON 계약 §7 오류 이름 | H7 | `INSTANCE_MISMATCH`(409), `JOURNAL_WRITE_FAILED`(500) |
| S3a JSON 계약의 `POST /host/job-orders` | H6 | 새 실행 ACCEPTED 의 일지 쓰기가 실패하면 500 `JOURNAL_WRITE_FAILED`. 성공 본문은 그대로 |

그 밖의 S3a·S3b·S3c·S4a 계약 절은 그대로입니다.

### H1. `GET /host/executions` 의 `restore` 와 `restoredFrom`

본문(5칸, 이 순서): `instanceId`, `pumpedAt`, `executions`, `restore`, `journalBlockedExecutionIds`. 실행 하나(13칸, 이 순서): S3a 의 8칸 뒤에 `restoredFrom`, `cancel`(S3a JSON 계약 §5.1, P0-b2), `permitBlock`(S3a JSON 계약 §5.5, ADR 52), `blockedFaults`, `releases`(S3a JSON 계약 §5.9, 결함 수용).

```json
{
  "instanceId": "mw-9b1e...",
  "pumpedAt": "1970-01-01T00:01:35Z",
  "executions": [
    {"executionId": "exec-1", "jobOrderId": "JO-1", "workMasterId": "InspectAsset", "missionVersion": null, "robotId": "humanoid-01",
     "physicalState": "RUNNING", "units": [...], "jobResponse": null,
     "restoredFrom": {"instanceId": "mw-3f0c...", "executionId": "exec-1"}}
  ],
  "restore": {
    "at": "1970-01-01T00:01:35Z",
    "rows": [
      {"jobOrderId": "JO-1", "robotId": "humanoid-01", "previousInstanceId": "mw-3f0c...", "previousExecutionId": "exec-1",
       "result": "RESTORED", "executionId": "exec-1", "reason": null}
    ]
  }
}
```

| 칸 | 형 | 규칙 |
|---|---|---|
| `restoredFrom` | 객체 또는 `null` | 이번 기동이 리빌드한 실행이면 `{instanceId, executionId}`(2칸, 이 순서) = **바로 앞 인스턴스**에서 그 작업 지시를 든 실행. 두 번 재기동하면 둘째 인스턴스의 실행이다(처음 받은 실행이 아니다). 새로 받은 실행은 `null` |
| `restore` | 객체 또는 `null` | 이번 기동의 복원 보고 `{at, rows}`(2칸). 기동 안에서 늘 채워지므로 REST 로는 `null` 을 보지 않는다. 리빌드할 것이 없었으면 `rows` 가 `[]` |
| `restore.at` | 시각 | 복원한 호스트 시계 값(호스트 시험·통합 시험에서는 가상 시각) |
| `restore.rows` | 배열 | 일지의 받은 순서. 행 7칸(아래) |

`restore.rows` 원소(7칸, 이 순서):

| 칸 | 형 | 규칙 |
|---|---|---|
| `jobOrderId` | 문자열 | |
| `robotId` | 문자열 | 일지의 배정 기체 |
| `previousInstanceId`, `previousExecutionId` | 문자열 | 바로 앞 인스턴스의 실행(`restoredFrom` 과 같은 규칙). 리빌드한 적이 없으면 처음 받은 인스턴스와 실행 |
| `result` | 문자열 | `RESTORED`, `DEFERRED`, `GAVE_UP` |
| `executionId` | 문자열 또는 `null` | `RESTORED` 일 때 새 실행 id(새 인스턴스의 셈이라 대개 `exec-1` 부터). 그 밖에는 `null` |
| `reason` | 문자열 또는 `null` | `DEFERRED`·`GAVE_UP` 의 사유. `RESTORED` 면 `null` |

- `DEFERRED` 는 기체 스냅숏을 못 읽었거나 살아 있는 다른 인스턴스가 든 실행인 것이고, 사유는 `살아 있는 다른 인스턴스가 든 실행이라 다시 짓지 않는다: instance=<id>` 또는 picasso 의 문장 그대로 `기체 스냅숏을 못 읽어 다시 짓지 않는다: robot=<robotId>, ...` 로 시작합니다. `previousInstanceId` 는 행을 든 인스턴스이기도 합니다(살아 있는지 보는 대상). 그 인스턴스가 만료되면 pump 가 다시 시도하며, 다시 시도가 스냅숏으로 미뤄지면 보고 행의 사유만 스냅숏 문장으로 바뀌고 일지 이벤트는 더 적지 않습니다. 스냅숏 사유면 pump 마다(250ms) 다시 시도하며, 읽히면 그 행이 그 자리에서 `RESTORED`(실행 id 있음, 사유 `null`)로 바뀝니다. 다른 거부로 바뀌면 `GAVE_UP` 이 됩니다.
- `GAVE_UP` 사유: 임무 버전 행이 없으면 `임무 버전 행이 없다: <workMasterId> 버전 <n>`, 저장된 정의를 못 읽으면 `저장된 임무 버전 ... 을 읽지 못했다: ...`, 판단 기록을 읽지 못하면 `판단 기록을 읽지 못해 다시 짓지 않는다: record=<id>, <picasso 문장>`, 판단 기록이 이 작업 지시의 것이 아니면 `<picasso RESUME_RECORD_MISMATCH 문장> (record=<id>)`(앞머리 `판단 기록이 이 작업 지시의 것이 아니어서 다시 짓지 않는다`), 그 밖에는 picasso `resume` 의 거부 문장 그대로(예 `요구 근거 등급 E2 은 InspectAsset 의 최고 등급 E0 를 넘는다 — 확인 수단이 없다`).
- 리빌드한 실행은 판단 기록이 있으면 판정 있는 단위는 기록의 상태·근거 등급 그대로 서고 다시 돌리지 않습니다(통합 시험 단계 1: 대기 DONE E2, S01 DONE E2). 기록이 없으면 단위가 모두 대기(로봇·플릿 단위는 `PENDING`, 앞서 통과한 설비 대기는 `DONE`·`E0`)에서 시작하고, 첫 pump 들이 기체를 다시 관측해 끝난 단위는 `DONE`, 도는 단위는 `RUNNING` 으로 세웁니다. 호스트 시험에서는 가상 시계를 밀지 않고 시험 상한 5초(실제 시간, pump 250ms) 안에 섰습니다.

### H2. 판정(`POST /host/eligibility`, 제출의 `excluded`)의 더해진 이유

모양은 S3a 그대로이고 `reasons` 에 문장 하나가 더해질 수 있습니다.

| 문장(정확한 모양) | 언제 |
|---|---|
| `복원 못 한 실행이 있다: <jobOrderId>[, <jobOrderId>...]` | 그 기체에 `DEFERRED` 행이 있거나, `GAVE_UP` 행이 있고 그 기체 스냅숏에 그 작업 지시의 종료하지 않은 태스크(id 가 `<jobOrderId>#` 로 시작, `@rN` 붙은 재작업 태스크 포함)가 있거나 스냅숏을 못 읽음. 작업 지시 id 는 작업 지시마다 한 번 |

- 이 이유만 있을 때 `runningExecutionId` 는 `null` 입니다(호스트가 모르는 고아 태스크라 실행 id 가 없다).
- `GAVE_UP` 행은 판정 때만 그 기체 스냅숏을 한 번 읽습니다. 고아 태스크가 종료 상태(용어집의 넷: `SUCCEEDED`, `FAILED`, `CANCELLED`, `CANCELLED_RECOVERY_FAILED`)이거나 없으면 풀리고, 사람을 기다리는 `RETRIABLE`·`NEEDS_INTERVENTION` 은 종료하지 않은 상태라 계속 뺍니다(호스트 시험 `HostRestoreTest` 의 NEEDS_INTERVENTION 고아 태스크). 풀리면 그 인스턴스에서는 다시 빼지 않습니다. 이전 기동에서 포기한 행도 다음 기동에서 다시 같은 검사를 받습니다(리빌드하지는 않음).
- `DEFERRED` 행은 `RESTORED` 가 될 때까지 늘 뺍니다. 다른 인스턴스로 미룬 `DEFERRED` 행도 같은 문장 `복원 못 한 실행이 있다: <jobOrderId>` 로 그 기체를 뺍니다(근거: `HostInstanceTest` 의 `살아 있는 다른 인스턴스가 든 일지 행은 다시 짓지 않고 미루며 그 기체를 판정에서 빼고 그 심장박동이 끊기면 다시 짓는다`). 그 뒤로는 리빌드한 실행이 `진행 중인 실행이 있다: exec-N` 으로 뺍니다.

### H3. `GET /host/incidents` 의 `earlier`

쿼리 `limit` 은 그대로이고 `incidents` 와 `earlier` 에 따로 걸립니다. 본문(5칸, 이 순서): `instanceId`, `total`, `incidents`, `earlierTotal`, `earlier`.

| 칸 | 형 | 규칙 |
|---|---|---|
| `earlierTotal` | 정수 | 지금 인스턴스가 아닌 사본의 수(자르기 전) |
| `earlier` | 배열 | 지금 인스턴스가 아닌 인스턴스의 인시던트 사본. 적은 순서의 역순(최근 것 먼저), 많아야 `limit` 개. 원소는 24칸: `instanceId`(그 사본의 인스턴스) 다음에 S4a §3 의 23칸 그대로(정체성 네 칸은 사본의 상세 글자에서 읽고, 반입 전 사본이면 넷 다 `null`) |

사본 줄의 값:

- 봉인 뒤 처음 적을 때의 값입니다. `resolution` 만 판단 행으로 다시 세우고(모양은 S4a 그대로 4칸), `confirmedWithoutEvidence` 는 그 판단으로 다시 계산합니다.
- `held` 는 늘 `false` 입니다(이전 인스턴스의 인시던트는 보류 중이 아님).
- 지금 인스턴스의 인시던트는 `incidents` 에만 있고 `earlier` 에 나오지 않습니다(사본은 지금 인스턴스 것도 적지만 `earlier` 가 거릅니다).
- 사본은 봉인 뒤의 pump 기록에서, 판단 행은 판단 뒤의 pump 기록에서 적힙니다. 판단 직후(다음 pump 250ms 전에) 호스트가 죽으면 그 판단은 사본에 없고 운영 서비스 조작 기록에만 남습니다(스펙 T7).

### H4. `GET /host/incidents/{incidentId}?instanceId=`

| 질의 | 결과 |
|---|---|
| 없음 또는 지금 인스턴스 | S4a §4 그대로(미들웨어의 번들). 없으면 404 `INCIDENT_NOT_FOUND` |
| 이전 인스턴스 | 그 인스턴스의 사본(29칸, S4a §4 와 같은 모양·순서). 없으면 404 `INCIDENT_NOT_FOUND` |
| 빈 문자열(`?instanceId=`) | 400 `BAD_REQUEST` |

사본 상세의 값: `instanceId` 는 사본의 인스턴스, `held` 는 `false`, `unitState` 는 `null`(그 실행은 지금 인스턴스에 없다), `resolution`·`confirmedWithoutEvidence` 는 판단 행으로 다시 세운 값, 나머지(`evidenceWindow`, `intent`, `step`, `verification` 등)는 봉인 뒤 처음 적을 때의 값 그대로입니다. 맵 칸(`intent.orderParameters`, `intent.unitParameters`, `equipment[].properties`)의 키 순서도 그때 그대로입니다.

### H5. `POST /host/executions/{executionId}/units/{unitId}/resolve` 의 `instanceId`

```json
{"decision": "REWORK", "approverId": "kim", "requestId": "3f0c6c1e-6a0e-4f43-9a52-2a3b4a9e8d10", "instanceId": "mw-9b1e..."}
```

| 칸 | 규칙 |
|---|---|
| `instanceId` | **필수**, 공백이 아닌 문자열. 운영 서비스는 상세(`GET /host/incidents/{id}`)에서 받은 `instanceId` 를 그대로 넣는다 |

검사 순서: 본문 형식(S4a §5.3 의 400, 그리고 `instanceId` 가 없음·`null`·공백·문자열 아님이면 400 `BAD_REQUEST`) → 인스턴스(지금 인스턴스가 아니면 409 `INSTANCE_MISMATCH`, 판단하지 않음) → 판단(200, S4a §5.2 그대로). 409 본문은 `{"error":"INSTANCE_MISMATCH","detail":"판단 요청의 인스턴스(<보낸 값>)가 지금 인스턴스(<지금 값>)가 아니다"}` 입니다. 경로의 실행·단위는 여전히 검사하지 않습니다(없으면 200 `NotHeld`).

### H6. `GET /host/job-responses`

호스트 잠금을 잡지 않고 DB 만 읽습니다. 쿼리:

| 쿼리 | 규칙 |
|---|---|
| `jobOrderId` | 선택. 주면 그 작업 지시만. 빈 문자열이면 400 `BAD_REQUEST` |
| `limit` | 선택, 기본 50, 1~500 정수. 밖이면 400 `BAD_REQUEST` |

본문(3칸, 이 순서): `instanceId`(지금 인스턴스), `total`(자르기 전의 수), `responses`(최근에 적은 것부터 많아야 `limit` 개).

`responses` 원소(17칸, 이 순서):

| 칸 | 형 | 규칙 |
|---|---|---|
| `instanceId` | 문자열 | 그 응답을 낸 인스턴스 |
| `jobResponseId` | 문자열 | `resp-N`(그 인스턴스의 셈) |
| `jobOrderId`, `executionId` | 문자열 | `executionId` 는 그 인스턴스의 셈 |
| `version` | 정수 | 작업 지시 버전 |
| `physicalState`, `requiredEvidence`, `reachedEvidence` | 문자열 | 작업 응답 그대로 |
| `completedUnits`, `unverifiedUnits` | 문자열 배열 | **정렬됨**(작업 응답의 순서가 아님) |
| `incompleteUnits` | 문자열 배열 | 미완 단위 id 만, 정렬됨. **사유가 없다**(작업 응답의 `incompleteUnits` 는 id→사유 객체) |
| `inDoubtUnits` | 문자열 배열 | **정렬됨** |
| `operatorRequired` | 참거짓 | |
| `residualHold` | 문자열 | `HOLD_KIND_*` |
| `blockedBy` | 문자열 배열 | 정렬됨 |
| `disposition` | 문자열 | `SENT`(송신) 또는 `RESTART_DUPLICATE`(재기동 중복이라 송신 안 함) |
| `recordedAt` | 시각 | 적은 DB 시각(실제 시각) |

`version` 부터 `blockedBy` 까지와 `jobOrderId` 가 내용 키입니다(`connection`, 미완 사유, 응답 id, 실행 id 는 키가 아님).

처분 규칙(스펙 T6): pump 뒤 기록이 미들웨어 `pending()` 의 응답을 차례로 적습니다. 그 인스턴스가 그 작업 지시에 대해 아직 한 행도 적지 않았고(= 새 인스턴스의 첫 응답), 그 작업 지시의 가장 최근 `SENT` 행(인스턴스 무관)과 내용 키가 같으면 `RESTART_DUPLICATE`, 그 밖에는 `SENT` 입니다. 어느 쪽이든 적은 뒤 `ack` 합니다. 그래서:

- 두 번 재기동해도 각 새 인스턴스의 첫 보류 응답은 `RESTART_DUPLICATE` 입니다.
- 같은 인스턴스 안의 다음 응답은 내용이 같아도(재작업 뒤 다시 보류) `SENT` 입니다.
- 처음 받은 작업 지시의 첫 응답은 앞 행이 없으므로 늘 `SENT` 입니다.

### H7. 오류 이름(더해진 것)

| 이름 | 상태 | 자리 |
|---|---|---|
| `INSTANCE_MISMATCH` | 409 | 호스트 판단(H5) |
| `JOURNAL_WRITE_FAILED` | 500 | 호스트 `POST /host/job-orders`. 새 실행의 일지를 못 적음. 실행은 미들웨어에 남고 리빌드 목록에서 빠진다(스펙 §9 한계, 호스트 시험 `HostJournalTest` 의 일지 쓰기 실패) |
| `BAD_REQUEST` | 400 | 위 자리에 더해 `GET /host/job-responses` 의 쿼리, 상세의 빈 `instanceId` |

### H8. 표(스키마 `mission`, `V2__execution_journal.sql`)

다섯 표 모두 덧붙이기 전용입니다(V2 의 `mission_record_append_only()` 트리거로 UPDATE·DELETE·TRUNCATE 거부, 문구 `실행 일지·송신 기록·인시던트 사본은 덧붙이기만 한다(<연산> <표>)`). 시각 기본값은 `clock_timestamp()` 입니다.

`execution_journal`(새 실행의 ACCEPTED 마다 한 행):

| 칸 | 형 | 규칙 |
|---|---|---|
| `journal_id` | BIGINT identity, UNIQUE | 받은 순서. 복원 순서 |
| `job_order_id` | TEXT PK | |
| `robot_id` | TEXT | 배정 기체 |
| `job_order` | JSONB | picasso `JobOrder` 칸 그대로(`jobOrderId`, `workMasterId`, `version`, `requiredEvidence`, `parameters`, `materialRequirements[{materialDefinitionId, quantity}]`, `equipmentRequirements[{id, equipmentUse, properties}]`) |
| `work_master_id` | TEXT | |
| `mission_version` | INTEGER NULL | 받은 때의 임무 버전. NULL 이면 코드 정의 |
| `instance_id`, `execution_id` | TEXT | 받은 인스턴스와 실행 |
| `received_at` | TIMESTAMPTZ | |

`execution_journal_event`:

| 칸 | 형 | 규칙 |
|---|---|---|
| `event_id` | BIGINT identity PK | |
| `job_order_id` | TEXT FK | |
| `kind` | TEXT | `RESTORED`, `DEFERRED`, `GAVE_UP`, `SETTLED` |
| `instance_id` | TEXT | 이벤트를 적은 인스턴스 |
| `execution_id` | TEXT NULL | `RESTORED`(새 실행)·`SETTLED`(종결한 실행)에만 있음(CHECK) |
| `detail` | TEXT NULL | `DEFERRED`·`GAVE_UP` 사유, `SETTLED` 의 물리 상태. `RESTORED` 만 NULL(CHECK) |
| `recorded_at` | TIMESTAMPTZ | |
| `snapshot` | JSON NULL | `SETTLED` 시점의 실행 본문(`GET /host/executions` 의 `ExecutionView` JSON: 재할당 뒤 기체, 단위 상태와 근거, 마지막 작업 응답, 운영자 취소). `SETTLED` 만 본문 가능(CHECK `execution_journal_event_snapshot_settled`), V8 이전 `SETTLED` 행과 다른 모든 이벤트는 NULL. JSONB 아님(적힌 본문 글자 그대로라 키 순서 보존) |

리빌드 대상 = `SETTLED`·`GAVE_UP` 이벤트가 없는 일지 행. `DEFERRED` 는 미룬 기동마다 한 번 적고(다시 시도마다 적지 않음, 미룬 까닭이 만료 뒤 스냅숏으로 바뀌어도 이벤트를 더 적지 않음) 결과가 바뀌면 `RESTORED` 나 `GAVE_UP` 을 하나 더 적습니다. `DEFERRED` 의 `detail` 에 다른 인스턴스 사유(`살아 있는 다른 인스턴스가 든 실행이라 다시 짓지 않는다: instance=<id>`)도 옵니다. `SETTLED` 는 물리 상태가 `PHYSICALLY_DONE`·`UNVERIFIED`·`FAILED`·`ABORTED` 가 된 실행에 한 번(`PARTIAL` 은 미들웨어가 계속 돌리므로 적지 않음).

`job_response_log`: `log_id` identity PK, `instance_id`, `job_response_id`(UNIQUE(`instance_id`, `job_response_id`)), `job_order_id`, `execution_id`, `version`, `physical_state`, `required_evidence`, `reached_evidence`, `completed_units`·`unverified_units`·`incomplete_units`·`in_doubt_units`·`blocked_by`(JSONB 정렬 배열), `operator_required`, `residual_hold`, `disposition`(`SENT`·`RESTART_DUPLICATE`), `recorded_at`.

`incident_copy`: `copy_id` identity UNIQUE(적은 순서), PK(`instance_id`, `incident_id`), `execution_id`, `job_order_id`, `unit_id`, `detail`(**JSON**, JSONB 아님. 상세 본문 글자 그대로라 키 순서 보존), `recorded_at`.

`incident_copy_resolution`: PK·FK(`instance_id`, `incident_id`) → `incident_copy`, `decision`, `decided_at`(호스트 시계), `wall_clock_at`(실제 시각), `decided_by_id`, `decided_by_kind`, `recorded_at`.

HA 단계 0 에서 더해진 V6 `execution_judgment_record`(`V6__execution_judgment_record.sql`): `record_id` identity PK, `job_order_id`(FK → `execution_journal`), `instance_id`, `execution_id`, `wire_version`(`JudgmentRecordWire.SCHEMA_VERSION`, 지금 `"1"`), `record`(TEXT, 받은 글자 그대로. JSON 이 아님), `recorded_at`. 변경·삭제·통째 비우기 금지. 수락 직후, pump 뒤 기록, 운영자 판단 직후 직전 글자와 다를 때만 한 행.

HA 단계 0 에서 더해진 V7 `host_instance`(`V7__host_instance_heartbeat.sql`): `instance_id` PK, `started_at`, `last_seen_at`, `expires_at`, `stopped_at`(NULL 가능), CHECK(`expires_at >= last_seen_at OR stopped_at IS NOT NULL`). 인스턴스마다 한 행이고 그 인스턴스만 고침. 덧붙이기 전용이 아님. 시각은 DB `clock_timestamp()`.

브랜치 `perf/ui-execution-list` 에서 더해진 V8(`V8__settled_execution_snapshot.sql`): `execution_journal_event` 에 `snapshot JSON`(NULL 가능) 칸을 더함. 새 표는 없고 표 수는 15 개로 유지됨. CHECK(`execution_journal_event_snapshot_settled`: `snapshot IS NULL OR kind = 'SETTLED'`)로 종결 이벤트만 본문을 가질 수 있습니다. 종결 이력 페이징을 위해 `(event_id) WHERE kind = 'SETTLED'` 부분 인덱스 `execution_journal_event_settled` 를 둠(커서는 `event_id`). 실행 호스트가 `recordAfterPump` 에서 동일 트랜잭션으로 `SETTLED` 이벤트를 적을 때 그 시점의 실행 본문인 `GET /host/executions` 실행 행(`ExecutionView` JSON)을 함께 적습니다. 이 본문에는 재할당 뒤 기체, 단위 상태와 근거, 마지막 작업 응답, 운영자 취소가 담김. JSONB 가 아닌 JSON 을 쓰는 이유는 인시던트 사본과 마찬가지로 적힌 본문 글자 그대로라 REST 실행 행의 키 순서를 보존하기 위함임. V8 이전에 적힌 `SETTLED` 행과 다른 모든 이벤트는 NULL 입니다. 표는 여전히 덧붙이기 전용입니다(행 트리거는 `UPDATE`·`DELETE` 만 막으며 변경 없음). 화면의 실행 목록은 미들웨어 메모리가 아니라 이 종결 이력에서 종결된 실행을 읽습니다(HA 설계 기록 §10, S3a JSON 계약 §5.8). 근거 시험은 `MissionStoreTest`(마이그레이션 8 개), `SettledHistoryTest` 의 `정착 이력은 인스턴스를 가리지 않고 일지 행의 기체와 임무를 싣고 본문 없는 정착 이벤트도 읽는다`(종결 아닌 이벤트에 본문 작성이 거부됨도 확인).

브랜치 `feat/forget-settled` 에서 더해진 V9(`V9__settled_snapshot_by_execution.sql`): `(instance_id, execution_id, event_id) WHERE kind = 'SETTLED'` 에 부분 인덱스 `execution_journal_event_settled_execution` 을 더함. 새 표나 칸 변경은 없고 표 수는 15 개로 유지되며 표는 여전히 덧붙이기 전용임. 이 인스턴스가 이빅션한 실행(picasso `forget`)을 실행 목록에 넣을 때(실행마다 가장 최근 종결 본문) 쓰임. 또한 종결 이벤트는 이제 종결마다 한 번 적으므로, 다시 실행되어(리비전 또는 운영자 판단) 다시 종결한 실행은 여러 `SETTLED` 줄을 가짐. 종결 이력 줄 필드 `superseded`(인덱스 `execution_journal_event_order` 대상 SQL `EXISTS` 로 계산)는 같은 작업 지시의 더 나중 종결이 뒤따르는 줄을 표시함. 근거 시험은 `MissionStoreTest`(마이그레이션 9 개), `ForgetSettledTest` 의 `실패 뒤 개정판으로 다시 정착하면 그 판의 정착 이력 줄을 하나 더 적고 앞 줄은 다시 정착함으로 표시하며 pump 를 거듭해도 정착마다 한 줄이다`.

브랜치 `fix/restore-rerun-and-cancel` 에서 더해진 V10(`V10__rerun_after_settle.sql`): 새 표 `execution_journal_revision`(`revision_id` identity PK, `job_order_id`(FK → `execution_journal`), `version`, `job_order`(JSONB, `execution_journal.job_order` 와 같은 모양), `instance_id`, `execution_id`, `recorded_at`(DB `clock_timestamp()`), UNIQUE(`job_order_id`, `version`))을 더함. 이미 있던 실행의 `revise` 가 ACCEPTED 를 낸 버전마다 한 행. 변경·삭제·통째 비우기 금지(V2 의 `mission_record_append_only()` 트리거). 표 수는 15 개에서 16 개. `execution_journal_event.kind` 에 `REOPENED` 를 더함(다섯 종류: `RESTORED`, `DEFERRED`, `GAVE_UP`, `SETTLED`, `REOPENED`). CHECK `execution_journal_event_kind_check` 를 지우고 다섯 종류로 다시 만듦. 옛 CHECK `execution_journal_event_check`(`execution_id` 는 `RESTORED`·`SETTLED` 에만)를 지우고 `execution_journal_event_execution_check`(`execution_id` 는 `RESTORED`·`SETTLED`·`REOPENED` 에만)로 바꿈. `execution_journal_event_check1`(`RESTORED` 만 `detail` NULL)은 그대로이고 `REOPENED` 는 `detail` 에 그때의 물리 상태를 듦. `REOPENED` 는 이 인스턴스가 종결을 적은 실행이 다시 실행되는 것. 실행 호스트가 그 실행의 다음 미들웨어 pump 전에 판단 기록과 한 트랜잭션으로 적음. 위 표의 `kind`·`execution_id` 행과 «리빌드 대상 = `SETTLED`·`GAVE_UP` 이벤트가 없는 일지 행» 문장은 V10 뒤 다음과 같이 읽음: 리빌드 대상은 `SETTLED`·`REOPENED`·`GAVE_UP` 가운데 마지막이 `REOPENED` 이거나 셋 다 없는 일지 행, 마지막이 `GAVE_UP` 이면 판정 제외 대상. 리빌드할 때 작업 지시는 가장 높은 버전의 리비전 행(없으면 일지 행). REST 모양은 바뀌지 않음. 다만 리빌드한 실행의 `cancel` 칸(H1)이 이제 그 작업 지시의 처음 취소 행(이전 인스턴스가 적은 것)일 수 있음. 칸 모양은 그대로. 근거 시험은 `MissionStoreTest`(마이그레이션 10 개, `V10 받은 개정판 표는 덧붙이기만 하고 판마다 한 행이며 다시 돎 이벤트는 실행 id 와 상세를 든다`), `RerunRestoreTest` 다섯.

브랜치 `feat/host-fault-release` 에서 더해진 V11(`V11__fault_release.sql`): 새 표 `execution_fault_release`(운영자 결함 수용). 칸: `release_id`(identity PK), `instance_id`, `execution_id`, `job_order_id`(FK → `execution_journal`), `faults`(JSONB, 수용한 막는 결함 전부, 호스트 결함 객체 모양의 배열, CHECK 로 비어 있지 않은 배열만), `approver_id`, `approver_kind`(CHECK `= 'PERSON'`), `reason`(CHECK 공백만이면 안 됨), `request_id`(UUID, UNIQUE, NULL 허용), `recorded_at`(DB `clock_timestamp()`). `instance_id`·`execution_id`·`approver_id` 는 빈 문자열이면 안 됨(CHECK). 인덱스 `execution_fault_release_order` (`job_order_id`, `release_id`). 변경·삭제·통째 비우기 금지(V2 의 `mission_record_append_only()` 트리거 둘). 표 수는 16 개에서 17 개. 덧붙이기 전용 표는 14 개에서 15 개. 수용마다 한 행. 실행 호스트가 사람 승인자 판정과 본 결함 대조 뒤 picasso `release` 를 부르기 전에 이 행을 먼저 적는다. 못 적으면 `release` 를 부르지 않는다(500 `RECORD_WRITE_FAILED`). 실행 목록의 `releases` 칸(S3a JSON 계약 §5.9)이 작업 지시 id 로 이 표를 읽으므로 재기동 전 인스턴스의 수용도 보인다. REST: 새 엔드포인트 `POST /host/executions/{executionId}/release`(S3a JSON 계약 §5.10)와 실행 칸 둘(`blockedFaults`, `releases`)이 더해짐. 그 밖의 칸 모양은 그대로. 근거 시험은 `MissionStoreTest`(마이그레이션 11 개, 표 목록), `FaultReleaseHostTest` 의 `감수 표는 덧붙이기만 하고 승인자는 사람뿐이며 요청 id 는 한 번이고 감수한 결함은 비지 않는다`.

### H9. e2e·화면이 기댈 사실

- 복원은 호스트 빈 생성 안(pump 와 웹 서버보다 먼저)에서 끝납니다. 호스트가 `GET /host/executions` 에 답하기 시작하면 `restore` 는 이미 채워져 있고 리빌드한 실행이 목록에 있습니다.
- 재기동 뒤 `exec-N`·`resp-N`·`incident-N` 은 1부터 다시 셉니다. 그래서 리빌드한 실행의 id 가 이전 실행과 같은 `exec-1` 일 수 있습니다. 화면의 «이전 exec-k» 는 `restoredFrom.executionId` 를 씁니다.
- 리빌드한 실행의 단위는 첫 pump 들이 기체를 다시 관측한 뒤 끝난 대로·도는 대로 섭니다(호스트 시험에서 가상 시계를 밀지 않고 시험 상한 5초 안). e2e 는 상태로 폴링할 것.
- 재기동 앞뒤로 기체의 태스크 집합이 같습니다(호스트 시험: `InspectAsset` 두 대상, `T1.travel` 이 끝나고 `T1` 이 도는 중에 재기동해 mimic 태스크 id 집합과 상태가 같음. 재작업한 로봇 단위는 `@r1` 태스크에 다시 붙고 새 태스크 없음).
- 운영자 보류에 선 실행을 다시 띄우면 대기가 새로 시작하므로 기한(`ARRIVAL_WAIT_HOLD` 20초)이 다시 지나야 보류가 다시 섭니다. 그 보류의 작업 응답이 `RESTART_DUPLICATE` 로 적힙니다. 호스트 시험은 재기동마다 5초씩 여섯 번 밀어 섰습니다.
- 송신 기록의 행은 pump 뒤에 적히므로 응답이 난 pump 와 같은 잠금 안에서 적힙니다. `GET /host/executions` 의 `jobResponse` 가 보인 뒤에는 `GET /host/job-responses` 에도 그 행이 있습니다(DB 가 살아 있으면).
- 화면에 보일 글자(호스트가 내는 것): 결과 `RESTORED`·`DEFERRED`·`GAVE_UP`, 처분 `SENT`·`RESTART_DUPLICATE`, 판정 이유 `복원 못 한 실행이 있다: <jobOrderId>`. 한국어 표시 문구(«재기동: 이전 인스턴스의 실행 n건을 리빌드했습니다» 등)는 화면이 정합니다.
- 기동 복원 중 DB 가 실패하면 호스트 기동이 멈춥니다(빈 생성 예외). pump 뒤 기록이 실패하면 경고만 남기고 `ack` 하지 않아 다음 pump 에 다시 합니다.

## 운영 서비스 (ops-service)

운영 서비스가 실제로 내고 받는 모양입니다. 공통 규칙은 S4a JSON 계약 §9.1 그대로입니다(`null` 칸 생략 없음, 사전 거부 본문 `{"error", "detail"}`, 쓰기 판정 순서 게이트 → 본문 → 사유 → 호스트, 호스트를 부른 쓰기는 늘 200). 새 쓰기 조작은 없고 조작 기록 op 도 더하지 않았습니다.

**앞 계약에서 이 절이 대체하는 곳**

| 앞 자리 | 대체하는 절 | 바뀐 것 |
|---|---|---|
| S4a JSON 계약 §9.2 `GET /api/incidents` | O1 | 호스트 본문 그대로라 끝에 `earlierTotal`, `earlier` 가 실림(운영 서비스는 해석하지 않음) |
| S4a JSON 계약 §9.3 `GET /api/incidents/{incidentId}` | O2 | 질의 `instanceId`(선택) 중계, 빈 값은 400 |
| S4a JSON 계약 §9.5 판단 요청·재조회 | O3 | 본문 `instanceId` 필수, 호스트 본문 끝에 `instanceId`, 재조회는 인스턴스로 가름 |
| S4a JSON 계약 §9.6 조작 기록 | O3 | `RESOLVE_OPERATOR_HOLD` 의 `request` 끝에 `instanceId` |
| S4a JSON 계약 §9.7 상태 코드 | O5 | `GET /api/job-responses` 행, `JOB_RESPONSE_BAD_REQUEST` |

### O1. `GET /api/incidents`

호스트 `GET /host/incidents`(H3)를 그대로 중계합니다. `limit` 규칙과 400 `INCIDENT_BAD_REQUEST`, 503 `HOST_SILENT` 는 S4a §9.2 그대로입니다. 200 본문은 호스트의 5칸(`instanceId`, `total`, `incidents`, `earlierTotal`, `earlier`)이고 사본 줄은 24칸입니다. `limit` 은 호스트가 `incidents` 와 `earlier` 에 따로 겁니다.

### O2. `GET /api/incidents/{incidentId}?instanceId=`

| 질의 | 운영 서비스 |
|---|---|
| 없음 | 호스트에 질의 없이 넘김(S4a §9.3 그대로) |
| 공백이 아닌 값 | 경로 조각과 같은 인코딩(`URLEncoder` 뒤 `+` → `%20`)으로 `?instanceId=` 에 실어 넘김. 결과 매핑은 S4a §9.3 그대로(200 객체, 404 `INCIDENT_NOT_FOUND` 본문 그대로, 그 밖 503 `HOST_SILENT`) |
| 빈 값·공백뿐(`?instanceId=`, `?instanceId=%20`) | 400 `INCIDENT_BAD_REQUEST`, detail `instanceId 는 비어 있지 않은 문자열이다`. 호스트를 부르지 않음 |

### O3. `POST /api/executions/{executionId}/units/{unitId}/resolve`

```json
{"decision": "REWORK", "instanceId": "mw-9b1e...", "reason": "랙 재배치 뒤 재작업"}
```

| 칸 | 규칙 |
|---|---|
| `decision` | S4a §9.5 그대로 |
| `instanceId` | **필수**, 공백이 아닌 문자열. 화면은 인시던트 상세의 `instanceId` 를 넣음. 없음·`null`·공백·문자열 아님이면 400 `RESOLVE_BAD_REQUEST`, detail `instanceId 는 비어 있지 않은 문자열이다(인시던트 상세의 instanceId)` |
| `reason` | S4a §9.5 그대로 |

- 판정 순서: 게이트(400 `ACTOR_REQUIRED` → 403 `MODE_NOT_ALLOWED`) → `decision`(400 `RESOLVE_BAD_REQUEST`) → `instanceId`(400 `RESOLVE_BAD_REQUEST`) → 사유(400 `REASON_REQUIRED`) → 호스트. 그래서 `{"decision":"REWORK"}` 는 이제 `REASON_REQUIRED` 가 아니라 `RESOLVE_BAD_REQUEST` 다.
- 호스트에 보내는 본문(4칸, 이 순서): `{"decision", "approverId": <X-Ops-User>, "requestId": <조작 요청 id>, "instanceId"}`. 시험이 글자 그대로 대조한다.
- 호스트 409 `INSTANCE_MISMATCH` 는 다른 호스트 4xx 와 같은 경로다: `result` `REJECTED`, `outcome`·`answer`·`confirmation` null, `rejection` `{"status": 409, "error": "INSTANCE_MISMATCH", "detail": "판단 요청의 인스턴스(<보낸 값>)가 지금 인스턴스(<지금 값>)가 아니다"}`. 재조회하지 않는다. 조작 기록 행은 `REJECTED`, `targetResponse` `{"status": 409, "body": {"error": "INSTANCE_MISMATCH", ...}}`.
- 조작 기록 `request`(이 순서): `{"op":"RESOLVE_OPERATOR_HOLD","executionId","unitId","decision","approverId","instanceId"}`.
- 응답 200 `HoldResolveOutcome` 의 모양(9칸)은 S4a §9.5 그대로다(`instanceId` 칸을 더하지 않음).

**응답 없음 뒤 재조회**(S4a §5.4 를 대체): `GET /host/incidents?limit=500` 을 한 번 읽는다.

| 목록의 `instanceId` | 보는 곳 | 대조하는 줄 |
|---|---|---|
| 요청한 `instanceId` 와 같음 | `incidents` | 같은 `executionId`·`unitId` |
| 다름(그 사이 재기동) | `earlier` | `instanceId` 가 요청한 값이고 같은 `executionId`·`unitId` 인 사본 |

- 반영 조건(하나라도)은 S4a §5.4 그대로다: `resolution != null`, `decidedBy.id == X-Ops-User`, `decision == 요청한 결정`, `wallClockAt >= sentAt`.
- 반영 안 됨의 관측은 위 대조 대상 가운데 첫 줄(목록 순서, `earlier` 는 최근에 적은 것부터)이고 없으면 null 이다. 사본 줄이면 관측에 `instanceId` 칸이 있다.
- 보는 곳이 배열이 아니면(호스트가 `earlier` 를 싣지 않음 포함) 확인 못 함이라 확인 행을 붙이지 않는다.
- 판단 직후 다음 pump 기록 전에 호스트가 죽으면 그 판단은 사본에 없어(H3) 재조회는 반영 안 됨으로 남긴다. 판단 사실은 조작 기록의 `NO_RESPONSE` 행과 사유에만 남는다.

### O4. `GET /api/job-responses?jobOrderId=&limit=`

호스트 `GET /host/job-responses`(H6)를 그대로 중계합니다. 모드와 관계없고 조작 기록에 남지 않습니다.

| 쿼리 | 규칙 |
|---|---|
| `jobOrderId` | 선택. 있으면 같은 인코딩으로 호스트에 싣는다. 빈 값·공백뿐이면 400 `JOB_RESPONSE_BAD_REQUEST`, detail `jobOrderId 는 비어 있지 않은 문자열이다` |
| `limit` | 선택. 있으면 1~500 정수여야 하고 그대로 싣는다(없으면 싣지 않아 호스트 기본 50). 아니면 400 `JOB_RESPONSE_BAD_REQUEST`, detail `limit 은 1~500 의 정수다: <값>` |

- 둘 다 있으면 호스트 질의는 `?jobOrderId=<값>&limit=<값>` 순서다.
- 200: H6 본문 그대로(3칸, 행 17칸).
- 호스트 불통(연결 실패, 시간 초과, 200 아님(호스트 400 포함), 객체 아님): 503 `{"error": "HOST_SILENT", "detail": "실행 호스트가 답하지 않는다: <이유>"}`.

### O5. 상태 코드와 오류 이름(더해진 것)

| 엔드포인트 | 200 | 400 | 403 | 404 | 503 |
|---|---|---|---|---|---|
| `GET /api/incidents/{incidentId}?instanceId=` | 호스트 본문 | `INCIDENT_BAD_REQUEST`(빈 `instanceId`) | | 호스트 `INCIDENT_NOT_FOUND` | `HOST_SILENT` |
| `POST .../resolve` | 보냄(결과는 본문, 409 `INSTANCE_MISMATCH` 는 `rejection`) | `ACTOR_REQUIRED`·`RESOLVE_BAD_REQUEST`(`decision`·`instanceId`)·`REASON_REQUIRED` | `MODE_NOT_ALLOWED`(엔지니어) | | |
| `GET /api/job-responses` | 호스트 본문 | `JOB_RESPONSE_BAD_REQUEST` | | | `HOST_SILENT` |

| 이름 | 상태 | 자리 |
|---|---|---|
| `JOB_RESPONSE_BAD_REQUEST` | 400 | `GET /api/job-responses` 의 쿼리 |
| `INSTANCE_MISMATCH` | (200 본문의 `rejection.error`) | 판단. 운영 서비스가 내는 상태 코드가 아니라 호스트 409 를 옮긴 것 |

## 화면 (ui)

화면과 Playwright 의 글자입니다. S4a JSON 계약 §10 의 글자는 바뀐 곳(아래 «바뀐 것»)말고 그대로이며, 화면 시험(vitest)과 Playwright 가 이 글자로 찾습니다.

**S4a §10 에서 바뀐 것**

- 인시던트 상세는 늘 `GET /api/incidents/<id>?instanceId=<고른 줄의 인스턴스>` 로 읽습니다(지금 표의 줄이면 목록의 `instanceId`). 고른 인시던트는 (인스턴스, id)이고, 고른 뒤 호스트가 재기동해도 고른 인스턴스의 사본을 계속 읽습니다(새 인스턴스의 같은 `incident-N` 으로 바뀌지 않음).
- 판단 요청 본문은 `{"decision", "instanceId": <상세의 instanceId>, "reason"}` 입니다.
- 운영 영역 구역 순서: `작업 지시`, `배정 가능`, `실행`, `인시던트`, `셀 대역`, `작업 응답 송신 기록`(새 구역은 맨 끝이라 S4a 의 «실행 다음, 셀 대역 앞» 은 그대로).

### C1. 실행 목록의 복원 보고(스펙 §8)

| 무엇 | 글자 |
|---|---|
| 자리 | region `실행` 안, 머리(dl) 다음·표 `실행 목록` 앞. `restore` 가 있고 `restore.rows` 가 비어 있지 않을 때만. `restore` 가 null 이거나 칸이 없거나 `rows` 가 `[]` 이면 띠가 없다 |
| 띠 | region `재기동 복원 보고`(class `restore`) |
| 첫 줄 | `재기동: 이전 인스턴스의 실행 <RESTORED 수>건을 리빌드했습니다(복원 시각 <restore.at>)`(앞 문장은 굵게) |
| 리빌드한 것 | RESTORED 가 있을 때만 list `리빌드한 실행`, 항목 `<jobOrderId>(<robotId>): 이전 <previousExecutionId> → <executionId>` |
| 미룬 것 | DEFERRED 가 있을 때만 `미룬 실행 <n>건. 실행 호스트가 다시 시도하며 그동안 그 기체는 배정에서 빠집니다` 와 list `미룬 실행`, 항목 `<jobOrderId>(<robotId>, 이전 <previousExecutionId>): <reason>` |
| 포기한 것 | GAVE_UP 이 있을 때만 `포기한 실행 <n>건. 다시 짓지 않습니다. 기체의 남은 태스크를 운영자가 확인하십시오` 와 list `포기한 실행`, 항목은 미룬 것과 같은 꼴 |
| 사유 | 호스트 문장 그대로. null 이면 `사유 없음` |
| 리빌드한 실행 행 | `restoredFrom` 이 있으면 첫 칸 `<executionId> (이전 <restoredFrom.executionId>)`(뒤 부분 span class `restored`, title `이전 인스턴스 <restoredFrom.instanceId>`). 없으면 S3a 그대로 `<executionId>` |

### C2. 인시던트의 이전 인스턴스 부분(스펙 §8)

| 무엇 | 글자 |
|---|---|
| 자리 | region `인시던트` 안, 지금 표(또는 `인시던트가 없습니다`) 다음. 목록에 `earlier` 배열이 있을 때만 |
| 부분 | region `이전 인스턴스`, 제목 `이전 인스턴스` |
| 없음 | `이전 인스턴스의 인시던트가 없습니다`(`earlier` 가 `[]`) |
| 머리 | `재기동 앞 인스턴스의 인시던트 <earlierTotal>건 가운데 최신 <earlier 수>건. 읽기 전용이며 여기서 판단하지 않습니다` |
| 표 | table `이전 인스턴스 인시던트 목록`. 줄 순서는 호스트 순서(최근에 적은 것부터) |
| 열 | `인스턴스`, `인시던트`, `발생 시각`, `기체`, `실행 id`, `단위`, `실패 종류`, `경로`, `현장 설정 버전`, `임무 버전`, `판단`(보류 열 없음, `held` 강조 없음) |
| 칸 값 | 지금 표와 같은 규칙(판단 칸 `미해결`·`판단됨`·`판단됨(설비 근거 없이 완료 확인)`·`판단 대상 아님`) |
| 상세 열기 | 둘째 칸 버튼, 접근 이름 `<instanceId> <incidentId> 상세 보기`(보이는 글자는 `<incidentId>`). 지금 표의 버튼은 S4a 그대로 `<incidentId> 상세 보기` |
| 고른 줄 | 고른 (인스턴스, id)와 같은 줄만 class `selected`(두 표 가운데 하나) |

상세(region `인시던트 상세`)에서 고른 인시던트가 지금 인스턴스의 것이 아니면(이전 표에서 골랐거나 고른 뒤 재기동):

| 무엇 | 글자 |
|---|---|
| 제목 | `<incidentId> 상세(이전 인스턴스 <instanceId>)`(지금 것은 S4a 그대로 `<incidentId> 상세`) |
| 안내 | `이전 인스턴스의 사본입니다. 읽기 전용이며 판단하지 않습니다` |
| 판단 폼 | 없음. 상세의 `held` 와 모드에 관계없다(`보류 중입니다. 운영자 판단은...` 문구도 없음) |
| 그 밖 | 사람의 판단·관측·결함·근거 윈도우는 S4a §10.3 그대로. 사본의 `unitState` 가 null 이라 `단위의 지금 상태` 는 `모름(실행 없음)` |

판단 결과(S4a §10.4)에 더해진 풀이: 호스트 409 는 `실행 호스트가 거부함(실행 호스트가 재기동해 인스턴스가 다름). <detail>`.

### C3. 작업 응답 송신 기록(스펙 §8)

| 무엇 | 글자 |
|---|---|
| 구역 | region `작업 응답 송신 기록`, 제목 같음. «운영» 영역 맨 끝(셀 대역 다음). 두 모드 모두 같다(읽기만) |
| 작업 지시 고르기 | 라벨 `송신 기록의 작업 지시`(select). 보기 `전체`(값 빈 문자열, 처음 값) 다음에 고른 작업 지시, 실행 목록의 작업 지시(최신 실행부터), 읽은 송신 기록의 작업 지시 순으로 겹침 없이 |
| 읽기 | `전체` 면 `GET /api/job-responses`, 고르면 `GET /api/job-responses?jobOrderId=<인코딩>`. 5초 주기와 고를 때마다 읽는다. 고르면 직전 값을 지우고 다시 읽는다 |
| 머리 | `실행 호스트 인스턴스 <instanceId>, 송신 기록 <total>건 가운데 최신 <행 수>건, 그 가운데 재기동 중복 <보인 행 가운데 RESTART_DUPLICATE 수>건` |
| 없음 | `송신 기록이 없습니다` |
| 못 읽음 | `모름: 송신 기록을 아직 읽지 못했습니다 (<detail>)`, 읽은 뒤 불통이면 `직전 값입니다. 실행 호스트 불통: <detail>` |
| 표 | table `송신 기록 목록`. 줄 순서는 호스트 순서(최근에 적은 것부터) |
| 열 | `인스턴스`, `작업 응답 id`, `작업 지시 id`, `실행 id`, `버전`, `물리 상태`, `근거(도달/요구)`, `단위`, `운영자 필요`, `처분`, `적은 시각` |
| 근거 칸 | `<reachedEvidence>/<requiredEvidence>`(예 `E0/E2`) |
| 단위 칸 | 빈 것을 빼고 `완료 <ids>; 미확인 <ids>; 미완 <ids>; 불확실 <ids>; 막은 결함 <ids>`(id 는 `, ` 로 이음), 다 비면 `-` |
| 운영자 필요 | `필요` 또는 `-` |
| 처분 | `SENT` → `송신`, `RESTART_DUPLICATE` → `재기동 중복(송신 안 함)`(줄에 class `duplicate`) |

### C4. Playwright 가 기대는 흐름(더해진 것)

- 운영자 보류 단계(S4a §10.6) 끝, registry 를 멈추기 전에 한 단계: 보류 실행 행의 둘째 칸(작업 지시 id)을 읽어 `송신 기록의 작업 지시` 에서 고르고, 표 `송신 기록 목록` 의 그 작업 지시 줄 가운데 `PHYSICALLY_DONE` 줄이 `송신` 을 담고, `재기동 중복` 줄이 없고, 구역이 `그 가운데 재기동 중복 0건` 을 담는다.
- S4a 의 판단 단계는 바꾸지 않았다. 판단 본문의 `instanceId` 는 화면이 상세에서 싣는다.
- 재기동 단계는 넣지 않는다(스펙 T11, e2e `RestartRecoveryTest` 몫).

## 통합 시험

e2e 가 실제로 기대는 것입니다. 스펙 §3 의 여섯을 `e2e/.../RestartRecoveryTest.kt` 가 한 스택(Postgres·registry·mimic·셀 대역·실행 호스트·운영 서비스)에서 차례로 보입니다. 재기동은 `E2eStack.restartHost()`(같은 DB·현장, 같은 포트)이고 운영 서비스는 다시 띄우지 않습니다.

### E1. 순서와 자원

| 단계 | 스펙 §3 | 임무 버전(활성화) | 작업 지시 | 재기동 |
|---|---|---|---|---|
| 1 | 1 | 1 = `ARRIVAL_WAIT`(120초, ABORTED) | 랙 S01·S02, `rack_present=true` | 1 |
| 2 | 2 | (그대로) | InspectAsset T1(bay-7)·T2(dock-3) | 1 |
| 3 | 5 | 버전 1 로 받은 뒤 2 = `DATA_V1`(대기 없음) | 랙 S03 | 1 |
| 4 | 3 | 3 = `ARRIVAL_WAIT_HOLD`(20초, 운영자 보류), `rack_present=false` | 랙 S04(끝에 같은 인스턴스에서 재작업 한 번) | 2 |
| 5 | 4 | (그대로) | 단계 4 의 실행을 이어 씀 | 1 |
| 6 | 6 | (그대로) | 단계 4 의 실행을 이어 씀(재기동 없음, 단계 5 의 재기동 뒤) | 0 |

- 작업 지시는 모두 humanoid-01 이 받습니다(`pick_place` 는 그 기체만 있고, InspectAsset 은 비용이 같아 기체 id 순). 앞 실행이 끝나야 다음이 배정되므로 순서가 있습니다.
- 셀 대역은 슬롯을 비우지 않으므로 작업 지시마다 슬롯을 따로 씁니다(넷 다 씀). humanoid-01 의 `pick_place` 는 넷이라 시드 0 의 자연 실패에 이르지 않습니다. 다시 보낸 같은 태스크 id 의 `StartTask` 는 추첨을 더 하지 않습니다.
- 실행 id 는 인스턴스마다 다시 세므로 단계 4~6 은 작업 지시 id 로 실행을 찾습니다.

### E2. 시계와 기다림

- 가상 시계만 밉니다(`ExecutionDriver.push`, 5초 또는 1초). 실제 시간으로 기다리는 것은 셋뿐입니다. (가) 리빌드한 실행의 단위가 기체를 다시 관측해 서는 것: 시계를 밀지 않고 상한 10초로 상태를 폴링하고, 기다린 앞뒤로 가상 시각이 같음을 단언합니다. (나) 확인 중(VERIFYING) 단위: 진행기와 같은 3초 상한. (다) 보류 응답의 송신 기록 행: 상한 10초 폴링(대개 곧바로 있음).
- 설비 대기 기한 20초는 호스트 시계(현장 가상 시계)로 셉니다. 그래서 «기한이 다시 지난 뒤» 는 실제 시간이 아니라 가상 시계 밀기입니다. S4a 단계 2b 처럼 registry 의 실제 수신 시각으로 판정하는 단계가 없어 60초 실제 대기가 없습니다.

### E3. 단계마다 단언하는 것

| 단계 | 단언 |
|---|---|
| 1 | 재기동 전 단위 (대기 DONE E2, S01 DONE E2, S02 RUNNING). 재기동 뒤 `restore.rows` 한 행 = (작업 지시, humanoid-01, 이전 인스턴스, 이전 실행 id, RESTORED, 새 실행 id, `reason` null). 실행 목록에 그 실행 하나, `restoredFrom` = `{이전 인스턴스, 이전 실행 id}`, `missionVersion` 1. 재기동 뒤 시계를 밀지 않고 (대기 DONE E2, S01 DONE E2, S02 RUNNING). 끝까지 밀면 PHYSICALLY_DONE, (E2, E2, E2), 작업 응답 도달 E2·요구 E2. 송신 기록 행의 도달 근거도 E2. 송신 기록은 새 인스턴스의 PHYSICALLY_DONE 하나가 SENT(재기동 전에는 응답이 없었다) |
| 2 | 재기동 전(T1.travel DONE, T1 RUNNING) 기체 태스크 = 그 작업 지시의 둘. 재기동 뒤 시계를 밀지 않고 단위가 (DONE, RUNNING, PENDING, PENDING) 로 선 때: 태스크 id 집합이 같고, 태스크마다 재기동 전 로그가 그대로 앞에 있으며 `ACCEPTED` 가 한 번. 끝까지 가면 태스크 넷, 이 기체의 모든 태스크(단계 1 포함)가 `ACCEPTED` 를 한 번만 지났고 `@r` 태스크가 없다 |
| 3 | 받은 때 버전 1, 활성화 뒤 지금 활성은 `DATA` 2. 재기동 뒤 그 작업 지시 RESTORED, 리빌드한 실행의 `missionVersion` 1 이고 단위가 (rack-arrival, S03)(버전 2 라면 S03 하나). 끝까지 가면 PHYSICALLY_DONE·버전 1 |
| 4 | 첫 인스턴스의 보류 응답 SENT. 재기동마다: RESTORED(둘째 재기동은 `previousInstanceId` 가 둘째 인스턴스), 시계를 밀지 않고 대기 OPERATOR_HOLD·S04 PENDING·실행 OPERATOR_HOLD 로 선다(기한을 다시 기다리지 않음). 새 인스턴스의 목록에 그 작업 지시의 보류 중 인시던트가 있고 상세의 `instanceId` 가 새 인스턴스, 단위가 대기이며, 근거 윈도우에 인시던트 시각과 같은 시각의 `RESUME_HOLD_REOPENED` 줄이 있다(다시 연 인시던트). 그 보류 응답이 새 인스턴스의 RESTART_DUPLICATE. 두 번 재기동 뒤 송신 기록 = (셋째 RESTART_DUPLICATE, 둘째 RESTART_DUPLICATE, 첫째 SENT), SENT 한 번, 세 행의 내용 키가 같다. 이어 셋째 인스턴스에서 다시 연 인시던트 상세의 `instanceId`·실행·단위로 재작업 → SUCCEEDED·Resolved, 응답 `answer.incidentId` 가 그 인시던트, 그 인시던트 판단 REWORK·`held` false. 다시 보류가 서면 그 응답은 같은 내용이어도 셋째의 SENT(새로 일어난 일) |
| 5 | 단계 4 끝의 보류(셋째 인스턴스의 둘째 보류)를 상세의 `instanceId` 를 실어 재작업 → SUCCEEDED·Resolved. 시계를 밀기 전에 대기가 RUNNING 으로 다시 기다리기 시작하기를 기다린다. 10초 밀어 대기 RUNNING(이 pump 들이 판단 행을 적음). 재기동 뒤 대기 RUNNING, 재기동 뒤 5초(재작업 뒤 15초)에도 RUNNING, 보류는 재기동 뒤 20초 전에 선다(기한을 판단 기록의 재작업 때 대기 시작부터 이어 잼). 그 응답은 새 인스턴스의 RESTART_DUPLICATE(상위가 마지막으로 받은 것이 단계 4 끝의 SENT). 지금 인스턴스의 `incidents` 에는 그 작업 지시의 새 보류 하나(미해결·보류 중)뿐이고 판단 든 줄이 없으며, 그 보류는 다시 연 것이 아니다(인시던트 시각의 `RESUME_HOLD_REOPENED` 줄 없음). `earlier` 에 그 작업 지시의 넷이 최근 것부터 인스턴스 (셋째, 셋째, 둘째, 첫째): 셋째의 재작업 뒤 기한 보류(첫 줄, 재작업한 인스턴스·인시던트, 판단 (REWORK, kim, PERSON)), 셋째가 다시 연 보류(REWORK), 둘째가 다시 연 보류(판단 없음), 첫째의 기한 보류(판단 없음). 넷 다 `held` false·`SIGNAL_DEADLINE`. 다시 연 것 여부 (아님, 다시 연 것, 다시 연 것, 아님). `earlier` 에 지금 인스턴스 줄이 없다 |
| 6 | `GET /api/incidents/{id}?instanceId=<이전>` 이 사본(그 인스턴스, 작업 지시, 판단 REWORK, `held` false, `unitState` null). 이전 인스턴스를 실은 판단 → 200, `result` REJECTED, `outcome`·`answer`·`confirmation` null, `rejection` (409, INSTANCE_MISMATCH, detail 에 두 인스턴스). 조작 기록 첫 RESOLVE 행 REJECTED·사유, `request.instanceId` 가 보낸 값, `targetResponse` 409 INSTANCE_MISMATCH. 대기는 여전히 OPERATOR_HOLD. 지금 인스턴스로 CONFIRM_DONE → Resolved, 끝까지 가면 PHYSICALLY_DONE, (대기 DONE E0, S04 DONE E2) |

### E4. 시험 쪽에 더한 것

- `Site.taskHistory(robotId)`: mimic 이 호스팅하는 태스크마다 갱신 로그의 상태 이름(적은 순서). 엔진 잠금 아래에서 읽기만 합니다. mimic 은 RPC 진입마다 접수한 태스크를 집어 들므로(`ACCEPTED` → `RUNNING`) 가상 시계를 밀지 않아도 재기동 뒤 로그가 이어질 수 있습니다. 그래서 단계 2 는 «로그 그대로» 가 아니라 «재기동 전 로그가 앞에 그대로, `ACCEPTED` 한 번, 태스크 집합 같음» 을 봅니다.
- `FaultIncidentTest` 의 판단 요청은 본문에 `instanceId` 를 싣습니다(O3). 보류 판단은 그 인시던트 상세의 값, 보류가 아닌 단위의 판단과 사유 없는 사전 거부 확인은 목록의 지금 인스턴스입니다. 사유 없는 요청도 `instanceId` 를 실어야 `REASON_REQUIRED` 가 나옵니다(판정 순서가 `instanceId` 를 먼저 봄). 단언은 바꾸지 않았습니다.

### E5. 통합 시험이 입증하지 않는 것

- 로봇 단위의 `@rN` 맞추기(재작업한 로봇 단위의 재기동): e2e 에는 로봇 단위를 보류로 세울 수단이 없어 P6 미들웨어 시험과 mission-host `HostRestoreTest` 몫입니다(스펙 §3-4).
- DEFERRED·GAVE_UP 과 판정 제외(`복원 못 한 실행이 있다`): 기체 스냅숏을 막는 포트가 e2e 에 없어 mission-host 시험 몫입니다.
- 판단 직후 다음 pump 전에 호스트가 죽는 경우(사본에 판단 없음): 시험은 판단 뒤 가상 시계를 밀어 pump 기록을 거친 뒤 재기동합니다.

### E6. 결함 주입(I1~I9)

e2e 판정은 `RestartRecoveryTest` 의 실패한 단계 이름입니다. 같은 변이를 그 모듈의 S4b 시험 클래스로도 돌렸습니다(`I<n>m`).

| id | 바꾼 곳 | 변이 | e2e | 모듈 시험 |
|---|---|---|---|---|
| I1 | 호스트 일지 JSON | 설비 요구의 `properties` 를 비움 | 잡음(단계 1~6) | 잡음 |
| I2 | 호스트 일지 JSON | 작업 지시 버전 +1(새 리비전 명령) | 잡음(단계 1~6) | 잡음 |
| I3 | 호스트 복원 | 일지 버전 대신 지금 활성 버전 | 잡음(단계 3~6) | 잡음 |
| I4 | 호스트 내용 키 | 미완 단위에 사유를 넣음 | 못 잡음(등가) | 못 잡음 |
| I5 | 호스트 pump 뒤 기록 | 적은 뒤 `ack` 안 함 | 잡음(단계 4~6) | 잡음 |
| I6 | 호스트 사본 상세 | 판단을 봉인 때 값으로 | 잡음(단계 5·6) | 잡음 |
| I7 | 호스트 판단 REST | 인스턴스 대조 끔 | 잡음(단계 6) | 잡음 |
| I8 | 운영 서비스 상세 | `instanceId` 질의 중계 안 함 | 잡음(단계 6) | 잡음 |
| I9 | 호스트 송신 기록 SQL | 최근 SENT 대신 최근 RESTART_DUPLICATE 와 견줌 | 잡음(단계 4·5) | 잡음 |

- I4 는 이 흐름에서 등가입니다. 작업 응답의 미완 사유는 `failureClass ?: note ?: state` 라 보류 응답은 인스턴스와 무관하게 대기 단위 `SIGNAL_DEADLINE`, 로봇 단위 `PENDING` 입니다. 사유가 인스턴스마다 갈리는 것은 실패 분류가 없고 메모가 있는 단위(`sender refused: ...`, `observed=... at ...` 등)이며 e2e 와 호스트 시험 어디에도 그런 재기동 흐름이 없습니다. 스펙 T6 의 «사유 문자열이 인스턴스마다 달라진다» 는 이 경우에 한한 말입니다.
- 아홉 가운데 모듈 시험이 못 잡고 e2e 만 잡은 것은 없습니다. e2e 가 더 보이는 것은 실제 현장(셀 대역 근거, mimic 태스크 로그)과 운영 서비스 중계를 거친 같은 결과입니다.
