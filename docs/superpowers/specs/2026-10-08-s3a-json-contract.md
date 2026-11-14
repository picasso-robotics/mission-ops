# S3a JSON 계약: 실행 호스트 REST 와 현장 셀 대역 `/cell`

S3a 설계 스펙(`docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md`)을 구현한 site·mission-host 가 실제로 내고 받는 모양이다. 운영 서비스·화면·통합 시험은 이것에 맞춘다. 코드 주석의 «S3a JSON 계약 §N» 은 이 문서의 절을 가리킨다.

공통 규칙

- 모든 본문은 UTF-8 JSON 이다. 시각은 ISO-8601 UTC 문자열(`Instant.toString()`, 예 `"2026-10-08T00:01:05.250Z"`)이다. 소수 초 자릿수는 고정이 아니다(`Instant.parse` 로 읽을 것).
- `null` 칸은 생략하지 않고 `null` 로 나온다.
- 열거 값은 picasso 의 enum 이름 그대로다.
- 포트(루트 `.env`): `MIMIC_GRPC_PORT=8783`(mimic gRPC, 현장이 연다), `SITE_CELL_PORT=8784`(현장 셀 대역), `HOST_PORT=8785`(실행 호스트). 모두 `127.0.0.1` 루프백이다(mimic gRPC 만 모든 인터페이스).
- 실행 호스트 설정 키: `server.port`(기본 `${HOST_PORT}`), `host.mimic.port`(기본 `${MIMIC_GRPC_PORT}`), `host.cell.url`(기본 `http://127.0.0.1:${SITE_CELL_PORT}`, 경로 `/cell` 은 호스트가 붙인다). 시험은 실행 인자 `--server.port=0 --host.mimic.port=<site.mimicPort> --host.cell.url=http://127.0.0.1:<site.cellPort>` 로 덮는다.
- 실행 호스트 기동(같은 JVM): `MissionHostApplication.builder(HostClock { site.now() }).run(args...)`. 인자 없이 `builder()` 이면 실제 시각이다. 설정 파일 이름은 `mission-host` 이고 JDBC·Flyway 자동설정을 끄므로 registry·운영 서비스와 한 JVM 에 떠도 된다. 열린 포트는 `(context as WebServerApplicationContext).webServer.port`.
- 현장 쪽 Kotlin API: `Site.mimicPort`, `Site.cellPort`, `Site.cellSnapshot`, `Site.now()`, `Site.advance(by)`(시험용, 민 직후 셀 대역 훑기), `Site.advanceTo(t)`(앞으로만), `Site.teach(...)`. `SiteConfig` 에 `mimicPort`·`cellPort`(기본 0)·`cell`(기본 `CellFixture.STANDARD`)이 더해졌다.

---

## 1. 현장 셀 대역 `GET http://127.0.0.1:${SITE_CELL_PORT}/cell`

| 경우 | 상태 | 본문 |
|---|---|---|
| `GET /cell` | 200, `Content-Type: application/json` | 아래 |
| `/cell` 에 GET 아닌 방법 | 405, `Allow: GET` | 없음 |
| 다른 경로(`/cells`, `/cell/x` 등) | 404 | 없음 |

```json
{
  "presentations": [
    {"id": "SEQ-IN-02.BIN-A", "occupied": true, "material": "ENGINE-COVER-A", "observedAt": null}
  ],
  "slots": [
    {"id": "RACK-204.S01", "occupied": true,  "material": "ENGINE-COVER-A", "observedAt": "2026-10-08T00:01:05Z"},
    {"id": "RACK-204.S02", "occupied": false, "material": null,             "observedAt": null},
    {"id": "RACK-204.S03", "occupied": false, "material": null,             "observedAt": null},
    {"id": "RACK-204.S04", "occupied": false, "material": null,             "observedAt": null}
  ]
}
```

- 칸은 넷뿐이다: `id`(문자열), `occupied`(불리언), `material`(문자열 또는 null), `observedAt`(시각 문자열 또는 null).
- 고정 픽스처 `CellFixture.STANDARD`: 제시 자리 하나 `SEQ-IN-02.BIN-A` = `ENGINE-COVER-A`(늘 점유, `observedAt` 늘 null), 슬롯 `RACK-204.S01`~`S04`(이 순서). 슬롯은 처음에 전부 빈다.
- 슬롯이 채워지는 때: 현장이 시계를 민 직후(`advance`·`advanceTo`·런처 1초 틱) 같은 mimic 잠금 아래에서 새로 `SUCCEEDED` 가 된 `pick_place` 태스크를 찾아 `destination` 슬롯을 `occupied=true`, `material` = `object_id` 제시 자리의 자재, `observedAt` = 그 밀기 직후의 가상 시각(= 그 태스크 종료의 `state_as_of`)으로 채운다. 같은 태스크는 한 번만. `object_id` 가 제시 자리가 아니면 `material=null` 로 점유만 채운다. `destination` 이 픽스처 슬롯이 아니면 무시한다. 슬롯은 비워지지 않는다(재기동 전까지).

## 2. 실행 호스트 공통

- 기준 주소 `http://127.0.0.1:${HOST_PORT}`. 인증 없음.
- POST 의 `Content-Type` 은 `application/json` 만 받는다. 그 밖(`text/plain`, 폼 등)은 **415**(Spring 기본 오류 본문, 모양 보장 없음).
- 400 본문은 늘 이 모양이다(운영 서비스 `PreRejection` 과 같은 칸):

```json
{"error": "BAD_REQUEST", "detail": "jobOrderId 가 비어 있지 않은 문자열이 아니다"}
```

| `error` | 언제 |
|---|---|
| `UNKNOWN_WORK_MASTER` | `jobOrder.workMasterId` 가 `InspectAsset`·`PrepareSequencedRack` 이 아님(`DeliverContainer` 포함) |
| `BAD_REQUEST` | 본문이 JSON 객체가 아님, 칸 누락·형 틀림, `version < 1`, `requiredEvidence` 가 `E0`~`E3` 아님, 기체 목록 누락·빈 문자열 원소 |

판정 순서: 본문 객체 → `jobOrder` 객체 → `workMasterId` → 나머지 칸 → 기체 목록. 그래서 WorkMaster 가 틀리면 기체 목록이 없어도 `UNKNOWN_WORK_MASTER` 다.

### 2.1 작업 지시 본문 `jobOrder`

picasso `JobOrder` 와 같은 칸이다.

```json
{
  "jobOrderId": "JO-20261008-0001",
  "workMasterId": "PrepareSequencedRack",
  "version": 1,
  "requiredEvidence": "E2",
  "parameters": {},
  "materialRequirements": [{"materialDefinitionId": "ENGINE-COVER-A", "quantity": 2}],
  "equipmentRequirements": [
    {"id": "RACK-204.S01", "equipmentUse": "destination", "properties": {"material": "ENGINE-COVER-A"}},
    {"id": "RACK-204.S02", "equipmentUse": "destination", "properties": {"material": "ENGINE-COVER-A"}},
    {"id": "SEQ-IN-02.BIN-A", "equipmentUse": "source", "properties": {"material": "ENGINE-COVER-A"}}
  ]
}
```

InspectAsset 예:

```json
{
  "jobOrderId": "JO-20261008-0002",
  "workMasterId": "InspectAsset",
  "version": 1,
  "requiredEvidence": "E0",
  "parameters": {},
  "materialRequirements": [],
  "equipmentRequirements": [
    {"id": "T1", "equipmentUse": "inspection_target", "properties": {"location": "bay-7"}}
  ]
}
```

| 칸 | 형 | 필수 | 규칙 |
|---|---|---|---|
| `jobOrderId` | 문자열 | 예 | 비어 있지 않음 |
| `workMasterId` | 문자열 | 예 | `InspectAsset`·`PrepareSequencedRack` |
| `version` | 정수 | 예 | 1 이상(S3a 는 1 고정) |
| `requiredEvidence` | 문자열 | 예 | `E0`·`E1`·`E2`·`E3`. 호스트는 임무별 고정을 검사하지 않는다(운영 서비스가 InspectAsset `E0`, PrepareSequencedRack `E2` 로 넣는다). InspectAsset 에 `E2` 를 넣으면 REJECTED 다 |
| `parameters` | 객체(값은 문자열) | 아니오 | 없거나 null 이면 `{}` |
| `materialRequirements` | 배열 `{materialDefinitionId: 문자열, quantity: 0 이상 정수}` | 아니오 | 없거나 null 이면 `[]`. 비어 있지 않으면 자재별 수가 단위의 기대 자재 수와 같아야 미들웨어 게이트를 지난다 |
| `equipmentRequirements` | 배열 `{id: 문자열, equipmentUse: 문자열, properties?: 객체(값은 문자열)}` | 아니오 | 없거나 null 이면 `[]` |

단위 id 겹침(대상·슬롯 중복, `<id>.travel` 충돌)은 호스트가 검사하지 않는다. 운영 서비스가 400 으로 막는다(스펙 §7.5).

### 2.2 호스트 판정 행 `HostEligibility`

```json
{
  "robotId": "quadruped-01",
  "skillFit": "MISSING",
  "missingSkills": ["pick_place"],
  "runningExecutionId": null,
  "passed": false,
  "reasons": ["모자란 스킬: pick_place"]
}
```

| 칸 | 값 |
|---|---|
| `skillFit` | `FIT`(경로가 로봇인 단위의 스킬이 기체 선언에 다 있음) · `MISSING`(모자람) · `UNKNOWN`(기체 케이퍼빌리티를 못 물어봄, 예: mimic 이 모르는 기체, mimic 불통) |
| `missingSkills` | `MISSING` 일 때 모자란 스킬(이름순). 그 밖에는 `[]` |
| `runningExecutionId` | 이 기체를 쥔 실행 id(picasso `PhysicalState.occupiesRobot` = `!isSettled \|\| PARTIAL`): 종결하지 않은 실행과 부분 완료(`PARTIAL`) 실행. `OPERATOR_HOLD` 도 포함. 없으면 null. 잔여 파지 표시만 있으면 null |
| `passed` | `reasons` 가 비었을 때만 참(`skillFit == FIT`, `runningExecutionId == null`, 복원 못 한 실행 없음, 미해소 잔여 파지 표시 없음, 현장 시간값 적용됨, 실행 일지 차단 없음). 코드: `HostEligibility.passed` KDoc «스킬 적합이고 진행 중인 실행이 없으며 판정 이유가 하나도 없다» |
| `gateTrial` | 관문 확인용이면 참. 새 접수에서 남은 이유가 잔여 파지 표시 하나뿐인 기체이며, 이때 `passed` 도 참이고 `reasons` 에 표시 사유가 남는다. 개정에는 쓰지 않는다. 이 칸이 없던 본문은 거짓. 코드: `HostEligibility.gateTrial` KDoc |
| `reasons` | 통과 못 한 이유(한국어, 화면 표시용). 순서: 스킬(`모자란 스킬: a, b` 또는 `기체 케이퍼빌리티를 못 물어봤다`) → `진행 중인 실행이 있다: exec-N` → `복원 못 한 실행이 있다: <jobOrderId>[, …]`(S4b H2) → `잔여 파지 확인 필요: <jobOrderId> <unitId>[, <jobOrderId> <unitId>…]`(P0-b2, 미해소 표시마다) → `현장 시간값 미적용: 실행 호스트가 현장 설정을 아직 읽지 못했다`(S3c §8.3) → `실행 일지 저장 실패: 호스트 복구 전 배정 차단`. 통과면 `[]` |

## 3. `POST /host/eligibility`

요청:

```json
{"jobOrder": { ...2.1... }, "robotIds": ["humanoid-01", "quadruped-01"]}
```

- `robotIds` 필수, 비어 있지 않은 문자열 배열(빈 배열 허용 → 빈 결과).

응답 200:

```json
{"robots": [ {HostEligibility}, ... ]}
```

- 요청 순서대로, 겹친 id 는 한 번만.
- 호스트는 시운전·연결을 보지 않는다. 운영 서비스가 합친다.

## 4. `POST /host/job-orders`

요청:

```json
{"jobOrder": { ...2.1... }, "candidates": ["quadruped-01", "humanoid-01"]}
```

- `candidates` 필수, 비어 있지 않은 문자열 배열.
- `sourceConfirmations` 선택, `[{source, identity, confirmedBy, confirmedAt}]`(`confirmedAt` 은 ISO-8601 시각). 없거나 null 이면 빈 목록이다. 원소가 객체가 아니거나, 문자열 칸이 비었거나, `confirmedAt` 이 시각이 아니거나, 같은 `source` 가 두 번 오면 400 `BAD_REQUEST` 다. 호스트는 이 칸을 저장하지 않고 그 제출의 `assign` 한 번 동안만 셀 관측으로 쓴다(§7). 운영 서비스는 사람이 기록한 출발 자리 확인이 있을 때만 이 칸을 싣는다. 근거: `SourceObservationHostTest` 의 `출발 자리 확인 칸이 틀리면 400 이다`, `사람의 출발 자리 확인을 실은 제출은 그 한 번만 셀 관측이 되어 게이트를 지나고 다음 제출은 다시 설비만 본다`, `HostClientTest` 의 `출발 자리 확인은 있을 때만 제출 본문에 싣고 출발 미관측 답은 결품 칸과 따로 읽는다`.
- 호스트가 같은 판정을 다시 해 `passed` 인 기체만 `Middleware.assign` 에 넘긴다. 통과한 기체가 없어도 `assign` 을 빈 목록으로 부른다(결과 UNASSIGNED, `refusals` 빈 배열). 이미 같은 작업 지시 id의 실행이 picasso에 있을 때는 후보 목록을 무시하고 해당 실행의 기체(현재 기체)로 보낸다. 같은 버전은 `IDEMPOTENT`, 낮은 버전은 거부, 높은 버전은 그 기체에서의 리비전이다. 리비전 판정 시 실행 호스트는 후보가 아닌 현재 기체만 판정하며, 이때 해당 실행 자체는 제 기체의 진행 중인 실행으로 세지 않고 다른 실행의 점유만 계산한다. 현재 기체가 호스트 판정을 통과하면 그 기체만으로 `assign` 을 호출하며 `excluded` 는 빈 배열이다. 현재 기체가 호스트 판정에서 제외되면(잔여 파지 표시, 다른 실행 점유, 현장 시간값 미적용, 모자란 스킬 등) `assign` 을 부르지 않고 결과는 `UNASSIGNED` 이며 `excluded` 에 그 기체의 판정 행이 실린다. picasso가 개정하지 않는 제출(같은 버전, 낮은 버전, 종료한 `ABORTED` 또는 `PHYSICALLY_DONE` 실행, WorkMaster 변경, 실행이 쥔 케이퍼빌리티를 넘는 요구 근거 등급)은 호스트 판정 없이 현재 기체의 `assign` 으로 넘겨 picasso의 `IDEMPOTENT` 나 거부가 그대로 반환되고 `excluded` 는 빈 배열이다. 단위 id 중복과 연쇄 검사는 계획을 지은 뒤에만 알 수 있어 리비전으로 취급되며, 현재 기체가 판정에서 제외되면 해당 거부 대신 `UNASSIGNED` 가 반환된다. 근거: `ExecutionControlTest` 의 `기존 실행의 기체가 호스트 판정으로 빠지면 통과하는 다른 후보가 있어도 개정하지 않고 UNASSIGNED 에 그 기체의 판정을 실으며 같은 판 재제출은 판정 없이 IDEMPOTENT 다`, `부분 완료 실행의 개정판은 제 실행이 쥔 기체로 빠지지 않아 후보가 다 빠져도 그 기체에서 개정한다`.

응답 200(결과가 무엇이든 200 이다. 상태 코드로 결과를 가르지 말 것):

```json
{
  "result": "ACCEPTED",
  "executionId": "exec-1",
  "robotId": "humanoid-01",
  "rejectionReason": null,
  "refusals": [],
  "excluded": [ {HostEligibility of quadruped-01} ],
  "remedy": null,
  "remedyWithheld": false,
  "alternativeLocations": null,
  "sourceMissing": null,
  "sourceUnobserved": null,
  "forgottenJobOrder": false
}
```

| `result` | 미들웨어 | `executionId`·`robotId` | `rejectionReason` | `refusals` |
|---|---|---|---|---|
| `ACCEPTED` | `Submission.Accepted` | 새 실행 | null | `[]` |
| `IDEMPOTENT` | `Submission.Idempotent`(같은 `jobOrderId`·같은 `version`. 실행이 도는 중에도 호스트 판정 없이 `assign` 까지 간다. 실행이 아직 picasso 메모리에 남아 있을 때만 성립한다(보존 폭 안의 `FAILED`·`UNVERIFIED` 실행). 종결해 이빅션한 실행의 같은 id는 `forgottenJobOrder: true`인 REJECTED이다) | 기존 실행과 그 기체 | null | `[]` |
| `REJECTED` | `Submission.Rejected`(요구 근거 등급 초과, picasso의 이빅션한 작업 지시 거부 등), 실행 일지 가드(실행 일지에 이미 있는 작업 지시. 그 작업 지시의 종결(`SETTLED`)·다시 실행(`REOPENED`)·포기(`GAVE_UP`) 이벤트 가운데 마지막이 종결이면 `FORGOTTEN_JOB_ORDER`, 그 밖이면 `JOURNALED_JOB_ORDER`), 실행 일지 차단 | null | 사유(한국어) | `[]` |
| `UNASSIGNED` | `Unassigned` | null | null | `[{"robotId": "...", "reason": "...", "alternativeLocations": [...], "sourceMissing": {...}, "sourceUnobserved": {...}}]`, `assign` 이 기체마다 낸 게이트 사유. 호스트가 먼저 뺀 기체는 여기 없다 |

- `excluded`: 호스트가 판정에서 빼 `assign` 에 넘기지 않은 기체의 판정 행(2.2). 결과와 무관하게 늘 있다(없으면 `[]`). 리비전에서 현재 기체가 호스트 판정으로 빠지면 그 기체의 판정 행이 실리며, 현재 기체가 통과하거나 호스트 판정 없이 `assign` 에 넘긴 제출에서는 `[]` 이다.
- `remedy`: 미들웨어 `Rejected.remedy`(조치 탐색 응답). null = 탐색 안 함 또는 보류. `{"kind":"FOUND","steps":[{skillType, requires:[{subject, requires}], expectedHold, onFailureHold}]}` 또는 `{"kind":"NONE","cause":"NO_CAPABILITY"|"DEPTH_LIMIT","unmet":[{subject, required, observed, detail}]}`. 파지·조건 값은 계약 enum 이름(`HOLD_KIND_EMPTY`, `PRECONDITION_SUBJECT_HOLD` 등).
- `remedyWithheld`: 조치 시퀀스를 계산했으나 사람이 먼저 진단하도록 보류. 참이면 `remedy` 는 null(보류한 단계는 싣지 않음). «대안 없음»(`NONE`)과 다르다.
- `alternativeLocations`: 맨 위 칸은 REJECTED 전용, UNASSIGNED 는 `refusals[]` 기체별 칸에 같은 의미. 출발 결품일 때 그 자재를 든 다른 자리. `[]` = 든 자리 없음, null = 셀이 답하지 않았거나 출발 결품 거부가 아님. 둘을 접지 않는다. 두 null 은 `sourceMissing` 으로 가른다.
- `sourceMissing`: 출발 결품 거부이면 `{material, source, observed}`(`observed` null = 빈 자리, 아니면 거기 있던 다른 식별 정보, `material` 은 단위가 자재를 지정하지 않았으면 null). REJECTED 의 맨 위 `sourceMissing` 은 이번 제출로 미들웨어 조치 탐색 기록(`remedySearches()`)에 새로 붙은 `SOURCE_MISSING` 줄에서 옮긴다. UNASSIGNED 의 기체별 `sourceMissing` 은 picasso `Unassigned.sourceMissing[robotId]`(게이트가 계산한 답)에서 옮기며 조치 탐색 기록에는 적히지 않는다. 아니면 null.
- `sourceUnobserved`: 출발 미관측이면 picasso `SourceUnobserved` 의 `{material, source, occupied}`. `occupied` null = 설비가 말이 없음, true = 설비가 «있다» 고만 함(신원 없음). 출발 결품과 접지 않는다: 그 기체의 `sourceMissing`·`alternativeLocations` 는 null 이다(대체 자리를 계산하지 않음). REJECTED 의 맨 위 칸은 picasso `Submission.Rejected.sourceUnobserved` 에서, UNASSIGNED 의 기체별 칸은 `Unassigned.sourceUnobserved[robotId]` 에서 옮긴다. 조치 탐색 기록에는 적히지 않는다. 출발 관측을 요구한(`REQUIRED`) 단위에서만 나온다. 지금 `assign` 경로에서는 UNASSIGNED 로만 오고, REJECTED 모양은 `SubmitOutcomeTest` 가 picasso 값을 직접 세워 고정한다. 아니면 null. 근거: `SubmitOutcomeTest` 의 `출발 미관측 거부는 그 자리와 설비가 말한 것을 싣고 결품과 대체 자리를 싣지 않는다`, `미배정의 기체별 사유는 출발 미관측일 때만 그 자리를 싣고 결품 칸과 대체 자리는 비었다`, `SourceObservationHostTest` 의 `출발 관측을 요구한 버전은 설비가 말이 없거나 있다고만 하면 미배정이고 기체별 사유에 출발 미관측이 실리며 결품 칸은 비었다`, `확인한 신원이 다르면 결품이고 설비가 비었다고 말하면 사람의 확인이 그 관측을 뒤집지 않는다`.
- `remedy`·`remedyWithheld`·`residualHoldMarkers`(기체별): 관문 확인 제출에서 사슬 위반으로 거절된 경우 그 기체의 `refusals[]`에 조치 탐색 결과(`remedy`: `FOUND`·`NONE`), 조치 보류 여부(`remedyWithheld`), 당시 열려 있던 잔여 파지 표시 식별자 목록(`residualHoldMarkers`)을 싣습니다. 일반 후보의 `refusals[]`에도 `remedy`와 `remedyWithheld`가 실립니다(근거: `IncidentLoopHostTest`).
- `forgottenJobOrder`: boolean. `POST /host/job-orders` 본문의 마지막 칸이며 기본값은 false이다. 거부 사유가 `FORGOTTEN_JOB_ORDER`로 시작하는 REJECTED일 때만 true이다. 운영 서비스는 이를 `/api/job-orders` 결과와 작업 요청 상세 `rejection`에 전달한다. 근거: `SubmitOutcomeTest` 의 `picasso 가 놓은 작업 지시로 거부하면 놓은 작업 지시 칸이 참이고 그 밖의 거부는 거짓이다`, `ForgetSettledTest` 의 `FAILED 는 두는 폭 동안 메모리에 남아 같은 판은 IDEMPOTENT 이고 폭이 지나면 놓아 같은 작업 지시는 판과 무관하게 놓은 작업 지시로 거부한다`, `재기동해 picasso 가 놓은 작업 지시를 몰라도 실행 일지가 같은 작업 지시를 놓은 작업 지시로 거부하고 일지 막힘이 없다`, `정착하지 않은 일지 행이 있는 작업 지시를 다시 내면 일지 쓰기 전에 거부해 호스트가 막히지 않는다`, `MissionHostTest` 의 `끝난 InspectAsset 를 같은 id 로 다시 내면 놓은 작업 지시로 REJECTED 이고 실행에 마지막 작업 응답이 붙는다`.
- 맨 위 다섯 칸(`sourceUnobserved` 포함)은 미들웨어가 거부한 REJECTED 일 때만 값이 있다. ACCEPTED·IDEMPOTENT·UNASSIGNED·일지 차단 REJECTED 는 null·false. UNASSIGNED 의 출발 결품은 맨 위가 아니라 `refusals[]` 의 기체별 칸에 실린다. `forgottenJobOrder`는 REJECTED(picasso의 거부이거나 실행 일지의 거부)일 때만 true일 수 있고 그 밖에는 false이다.
- 지금 실행 호스트의 `assign` 경로에서 출발 결품은 UNASSIGNED 로 오므로 `sourceMissing`·`alternativeLocations` 가 찬 REJECTED 는 HTTP 로 나오지 않는다. 모양은 `SubmitOutcomeTest` 가 picasso 거부 값을 직접 세워 고정한다. UNASSIGNED 는 HTTP 로 기체별 구조를 싣는다. 호스트 셀 대역은 스냅숏이 있으면 늘 답하므로 HTTP 로는 기체별 `[]`·목록만 나오고, 기체별 null(셀 무응답)은 `SubmitOutcomeTest` 가 picasso 값을 직접 세워 고정한다.

미들웨어가 출발 결품으로 거부한 REJECTED 의 모양(지금 경로에서는 나오지 않는다):

```json
{
  "result": "REJECTED",
  "executionId": null,
  "robotId": null,
  "rejectionReason": "출발 자리 SEQ-IN-02.BIN-A 에 ENGINE-COVER-A 이 없다 — 비었다 (설비 관측, 보내지 않았다)",
  "refusals": [],
  "excluded": [],
  "remedy": null,
  "remedyWithheld": false,
  "alternativeLocations": ["SEQ-IN-03.BIN-B"],
  "sourceMissing": {"material": "ENGINE-COVER-A", "source": "SEQ-IN-02.BIN-A", "observed": null},
  "sourceUnobserved": null,
  "forgottenJobOrder": false
}
```

```json
{"kind": "FOUND", "steps": [{"skillType": "release", "requires": [{"subject": "PRECONDITION_SUBJECT_HOLD", "requires": "HOLD_KIND_HOLDING"}], "expectedHold": "HOLD_KIND_EMPTY", "onFailureHold": "HOLD_KIND_HOLDING"}]}
{"kind": "NONE", "cause": "DEPTH_LIMIT", "unmet": [{"subject": "PRECONDITION_SUBJECT_HOLD", "required": "HOLD_KIND_EMPTY", "observed": "HOLD_KIND_HOLDING", "detail": "든 채다"}]}
```

기체별 출발 결품과 대체 자리가 실린 UNASSIGNED 응답 예시:

```json
{
  "result": "UNASSIGNED",
  "executionId": null,
  "robotId": null,
  "rejectionReason": null,
  "refusals": [
    {
      "robotId": "humanoid-01",
      "reason": "출발 자리 SEQ-IN-02.BIN-A 에 ENGINE-COVER-A 이 없다 — 비었다 (설비 관측, 보내지 않았다)",
      "alternativeLocations": ["SEQ-IN-03.BIN-B"],
      "sourceMissing": {"material": "ENGINE-COVER-A", "source": "SEQ-IN-02.BIN-A", "observed": null},
      "sourceUnobserved": null
    }
  ],
  "excluded": [],
  "remedy": null,
  "remedyWithheld": false,
  "alternativeLocations": null,
  "sourceMissing": null,
  "sourceUnobserved": null,
  "forgottenJobOrder": false
}
```

- 같은 `jobOrderId` 를 그 실행이 도는 중에 다시 내면, 그 기체가 `runningExecutionId` 로 빠져 UNASSIGNED 다. 그 실행이 부분 완료(`PARTIAL`)여도 같다(리비전도 UNASSIGNED, 부분 완료는 운영자 취소로 닫음). 그 작업 지시에 미해소 잔여 파지 표시가 있어도 그 기체가 `잔여 파지 확인 필요` 로 빠져 UNASSIGNED(이때 `runningExecutionId` 는 null). 근거: `MissionHostTest` 의 `부분 완료 실행의 기체는 도는 실행으로 판정과 제출에서 빠진다`, `ExecutionControlTest` 의 `든 채 실패로 정착한 실행의 표시는 재기동해도 기체를 빼고 해소도 재기동 뒤까지 남으며 표시는 작업 지시마다 하나다`.

## 5. `GET /host/executions`

응답 200:

```json
{
  "instanceId": "mw-3f0c...",
  "pumpedAt": "2026-10-08T00:01:05.250Z",
  "executions": [
    {
      "executionId": "exec-1",
      "jobOrderId": "JO-20261008-0002",
      "workMasterId": "InspectAsset",
      "missionVersion": null,
      "robotId": "humanoid-01",
      "physicalState": "PHYSICALLY_DONE",
      "units": [
        {"unitId": "T1.travel", "skillType": "navigate_to", "state": "DONE", "reached": "E0"},
        {"unitId": "T1",        "skillType": "inspect",     "state": "DONE", "reached": "E0"}
      ],
      "jobResponse": {
        "jobResponseId": "resp-1",
        "version": 1,
        "physicalState": "PHYSICALLY_DONE",
        "requiredEvidence": "E0",
        "reachedEvidence": "E0",
        "completedUnits": ["T1.travel", "T1"],
        "unverifiedUnits": [],
        "incompleteUnits": {},
        "inDoubtUnits": [],
        "operatorRequired": false,
        "residualHold": "HOLD_KIND_EMPTY",
        "blockedBy": [],
        "connection": "CONNECTION_STATE_ONLINE"
      }
    }
  ]
}
```

| 칸 | 값 |
|---|---|
| `instanceId` | 미들웨어 인스턴스(`mw-<UUID>`). 호스트를 재기동하면 바뀌고 `exec-N` 은 1부터 다시 센다 |
| `pumpedAt` | 마지막 pump 를 **시작할 때**의 호스트 시계 값. 아직 한 번도 안 돌았으면 null. 통합 시험은 밀 때마다 `pumpedAt >= Site.now()` 를 기다린다. 다만 mimic 스트림 갱신은 gRPC 스레드로 비동기로 오므로, 그 pump 가 방금 민 전이를 못 볼 수 있다. 한 주기(250ms) 더 기다리거나 상태로 폴링할 것 |
| `executions` | 생성 순(`exec-N`의 N 순). 끝난 실행도 남는다. 이 인스턴스가 이빅션한 실행은 가장 최근 종결 이벤트 본문(종결 당시의 행)이다. 화면은 이 인자 없는 목록을 읽지 않고 §5.8의 페이지 형식을 읽는다. |
| `missionVersion` | 정수 또는 null. null 이면 코드 정의(화면 표시 «코드 정의»). S3a 는 늘 null |
| `physicalState` | `REQUESTED`·`ACCEPTED`·`RUNNING`·`PARTIAL`·`IN_DOUBT`·`OPERATOR_HOLD`·`PHYSICALLY_DONE`·`UNVERIFIED`·`FAILED`·`CANCELING`·`ABORTED`. 종결은 `PHYSICALLY_DONE`·`UNVERIFIED`·`FAILED`·`ABORTED`·`PARTIAL` |
| `units[].state` | `PENDING`·`IN_DOUBT`·`RUNNING`·`VERIFYING`·`OPERATOR_HOLD`·`DONE`·`UNVERIFIED`·`FAILED`·`ABORTED` |
| `units[].reached` | 근거 등급 `E0`~`E3`. PrepareSequencedRack 이 셀 대역으로 확인되면 `E2` |
| `units[].skillType` | `navigate_to`·`inspect`·`pick_place` |
| `units[].attempt` | 정수. 단위의 지금 시도(picasso `ExecutionUnit.attempt`). 재작업마다 하나 오른다. picasso `4d8b364` 반입 전에 적은 정착 본문에는 없다(null) |
| `units[].decision` | 그 단위의 판단 기록 판단(picasso `RecordedDecision`, 가장 최근 하나) `{decision, approverId, approverKind, at, attempt}` 또는 null. `at` 은 호스트 시계, `attempt` 는 판단이 선 시도(재작업이면 단위의 `attempt` 보다 하나 작다). 재작업 묶음이면 묶음의 단위마다 서고 `DONE` 이던 이동에도 `REWORK` 가 선다. 운영 서비스의 판단 재조회가 대상·시도·판단으로 대조하는 자리다(S4a §5.4). 반입 전 본문에는 없다(null) |
| `jobResponse` | 그 실행의 **마지막** 작업 응답. 없으면 null. 실행 하나에 작업 응답이 여러 번 날 수 있다(호스트는 기록 뒤 `ack` 한다) |
| `jobResponse.residualHold` | `HOLD_KIND_*` 이름 |
| `jobResponse.incompleteUnits` | 단위 id → 사유 문자열 객체 |
| `jobResponse.connection` | `CONNECTION_STATE_*` 이름 |

공통: 루프백·무인증, 호출자는 운영 서비스뿐, 모드 검사와 조작 기록은 운영 서비스 몫, POST 는 `application/json` 만(아니면 415), 400 본문은 2절 모양 `{"error":"BAD_REQUEST","detail":…}`, 승인자는 늘 `Approver(approverId, PERSON)`, `requestId` 는 없거나 null 이거나 UUID 문자열이고 응답에 그대로 돌려줄 뿐 저장하지 않음, 사유는 앞뒤 공백을 뗌. 기록을 못 적으면 500 `RECORD_WRITE_FAILED`(`detail` 은 DB 오류 문장)이고 아무것도 바꾸지 않음.

### 5.1 실행의 `cancel` 칸(P0-b2)

실행마다 끝에 `cancel`(S4b H1 의 `restoredFrom` 뒤, 열째 칸). 이 인스턴스에서 그 실행에 건 운영자 취소. 없으면 null. 기동 복원이 리빌드한 실행이면 그 작업 지시의 처음 취소 행(이전 인스턴스가 적은 것)을 실음. 기동 복원이 그 취소를 다시 건 것이고 취소 행은 새로 적지 않으며(브랜치 `fix/restore-rerun-and-cancel`), 그 밖의 이전 인스턴스 취소는 싣지 않음. 칸 5개 이 순서. `physicalState` 는 취소를 건 때의 물리 상태, `recordedAt` 은 DB 시각.

```json
"cancel": {"approverId": "kim", "approverKind": "PERSON", "reason": "둘째 슬롯 결품으로 부분 완료를 닫는다", "physicalState": "PARTIAL", "recordedAt": "2026-10-14T01:02:03.456789Z"}
```

### 5.2 `POST /host/executions/{executionId}/cancel`(P0-b2)

판정 순서 본문(400) → `instanceId` 가 지금 인스턴스인가(아니면 409 `INSTANCE_MISMATCH`, `detail` «취소 요청의 인스턴스(<요청>)가 지금 인스턴스(<지금>)가 아니다», 취소하지 않음) → 결과. 결과는 늘 200 본문 `result`. 400 은 본문이 객체가 아님(«본문이 JSON 객체가 아니다»), `approverId`·`reason`·`instanceId` 가 비어 있지 않은 문자열이 아님(«<칸> 가 비어 있지 않은 문자열이 아니다»), `requestId` 가 UUID 문자열이 아님. `CANCELED` 면 취소 행을 적고 미들웨어 `cancel` 을 부른 것이며 실제 중단은 다음 pump(`ABORTED`, 일지 `SETTLED`, `ABORTED` 작업 응답과 결과 outbox).

| `result` | 언제 | `physicalState` | `detail` |
|---|---|---|---|
| `CANCELED` | 물리 상태가 `pumps`(종결하지 않음 또는 `PARTIAL`)이고 아직 취소를 안 걸었고 실행 일지에 있음 | 건 때의 상태 | null |
| `NOT_CANCELABLE` | 종결함(이빅션한 실행도 종결로 치며 상태는 마지막 종결 이벤트의 상태다. `종결한 실행은 취소하지 않는다: <상태>`), 이미 취소를 건 실행(`CANCELING` 이거나 이 인스턴스의 취소 행이 있음, `이미 취소를 건 실행이다`), 실행 일지에 없음(`실행 일지에 없는 실행이다`). 근거 시험: `ForgetSettledTest` 의 `정착을 적고 응답을 ack 한 뒤 끝난 실행을 놓으면 호스트 메모리가 비고 목록과 좁힌 목록은 정착 본문으로 같은 줄을 싣는다` | 지금 상태 | 사유 |
| `NOT_FOUND` | 지금 인스턴스에 그 실행도 종결 기록도 없음 | null | `그 실행이 지금 인스턴스에 없다` |
| `REFUSED` | 승인자가 사람이 아님. REST 는 늘 사람 승인자라 호스트 메서드를 직접 부를 때만 | null | `에이전트는 실행을 취소하지 못한다. 실행 중단은 사람의 것이다: <approverId>` |

```json
{"approverId": "kim", "reason": "둘째 슬롯 결품으로 부분 완료를 닫는다", "instanceId": "mw-3f0c...", "requestId": "6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a"}
```

```json
{"result": "CANCELED", "executionId": "exec-1", "physicalState": "PARTIAL", "detail": null, "requestId": "6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a"}
{"result": "NOT_CANCELABLE", "executionId": "exec-1", "physicalState": "ABORTED", "detail": "종결한 실행은 취소하지 않는다: ABORTED", "requestId": null}
```

### 5.3 `GET /host/robots/residual-holds`(P0-b2)

200 늘. 해소 행이 없는 표시를 적은 순서로. 표시 칸 8개 이 순서. `objectRef` 는 관측이 이름을 싣지 않았으면 null. `instanceId`·`executionId` 는 표시를 적은 인스턴스의 것(재기동 뒤에도 그대로).

```json
{"holds": [{"markerId": 1, "robotId": "humanoid-01", "jobOrderId": "JO-1", "executionId": "exec-1", "instanceId": "mw-3f0c...", "unitId": "RACK-204.S02", "objectRef": "C-1", "recordedAt": "2026-10-14T01:02:05.123456Z"}]}
```

### 5.4 `POST /host/robots/{robotId}/residual-holds/{markerId}/clear`(P0-b2)

`markerId` 가 양의 정수가 아니면 400 `BAD_REQUEST`(«markerId 는 양의 정수다: <값>»). 본문 `{approverId, reason, requestId?, cited?}`(앞 셋 규칙은 5.2 와 같음, `requestId` 는 해소·충돌 행에 정규형으로 적고 없으면 호스트가 만듦). `cited`(B2)는 없거나 null 이거나 객체 `{conflictRequestId(UUID 문자열), kind(비어 있지 않은 문자열), objectRef?(문자열|null, 빈 문자열은 null), session?(문자열|null), sequence?(10진 정수 문자열|null, 하위 호환으로 JSON 정수도 받음)}` 이고 모양이 틀리면 400 `BAD_REQUEST`. 200 본문 칸 3개 `hold`(5.3 의 표시 하나)·`clearing`(8칸 `approverId`·`approverKind`·`reason`·`clearedAt`·`requestId`·`observation`·`conflictRequestId`·`acknowledgedMismatch`)·`requestId`(해소 행의 요청 id). 404 `RESIDUAL_HOLD_NOT_FOUND`(그 기체에 그 표시 없음, «기체 <robotId> 에 잔여 파지 표시 <markerId> 가 없다»), 409 `RESIDUAL_HOLD_ALREADY_CLEARED`(«잔여 파지 표시 <markerId> 는 이미 해소했다(<approverId>, <clearedAt>)»), 403 `APPROVER_REFUSED`(에이전트 승인자, REST 로는 안 남). 해소하면 판정이 곧바로 그 기체를 다시 봄.

관측 대조(B2): 표시·이미 해소 확인 뒤 호스트 잠금 아래에서 1) 같은 `requestId` 의 충돌 행이 있으면 그 저장된 충돌을 그대로 409 로 돌려줌(재전송·재기동 뒤에도, 새 행 없음. 다른 표시의 충돌이면 409 `REQUEST_ID_REUSED`). 2) `cited` 가 있으면 저장된 충돌 `cited.conflictRequestId` 와 대조: 있음, 같은 표시, 같은 승인자, `kind`·`objectRef`·`session`·`sequence` 가 저장값과 같음(null 끼리 같음). 하나라도 어긋나면 400 `RESIDUAL_HOLD_CITATION_INVALID`(행 없음). 3) 기체 스냅숏을 지금 한 번 읽음(못 읽으면 `UNREADABLE`, 손을 말하지 않으면 `UNSPECIFIED`, 관측 불가 `NOT_OBSERVABLE`). 4) 지금 `HOLDING` 이고 `cited` 가 없거나 손이 다르면(물품 이름 다름, 세션 한쪽만 미상, 다른 세션, 같은 세션에서 순번 역행) 이번 `requestId` 로 충돌 행을 적고 409 `RESIDUAL_HOLD_OBSERVATION_MISMATCH`. 지금 `HOLDING` 이고 같은 손이면 해소(`acknowledgedMismatch` 참, `conflictRequestId` 연결). 지금 `HOLDING` 이 아니면 해소(`acknowledgedMismatch` 거짓, `cited` 가 있었으면 그 충돌 연결). 409 본문 칸 6개 이 순서 `error`·`detail`·`robotId`·`markerId`·`conflictRequestId`·`observation`. `observation`(해소 행 `clearing.observation` 도 같음) 칸 8개 이 순서 `observedKind`·`observedObjectRef`·`observedDetail`·`snapshotSession`·`snapshotSequence`·`snapshotOccurredAt`(발신자 시계의 응답 시각)·`hostReadStartedAt`·`hostReadReceivedAt`. `snapshotSequence` 는 10진 문자열이다(2^53 을 넘으면 JS 숫자가 정밀도를 잃어 화면 왕복 뒤 지목이 저장값과 달라진다). 화면은 그 문자열을 그대로 `cited.sequence` 로 돌려보낸다. 다른 표시의 해소 행에 이미 쓴 요청 id 도 409 `REQUEST_ID_REUSED`. V15 전 옛 해소 행은 `requestId` null, `observedKind` `NOT_RECORDED`.

```json
{"approverId": "park", "reason": "현장에서 기체 빈손 확인", "requestId": "0b7d4c55-9a51-4f3e-8d5c-6b7a6f1c2a9e"}
```

```json
{
  "hold": {"markerId": 1, "robotId": "humanoid-01", "jobOrderId": "JO-1", "executionId": "exec-1", "instanceId": "mw-3f0c...", "unitId": "RACK-204.S02", "objectRef": "C-1", "recordedAt": "2026-10-14T01:02:05.123456Z"},
  "clearing": {"approverId": "park", "approverKind": "PERSON", "reason": "현장에서 기체 빈손 확인", "clearedAt": "2026-10-14T01:10:00.000001Z"},
  "requestId": "0b7d4c55-9a51-4f3e-8d5c-6b7a6f1c2a9e"
}
```

```json
{"error": "RESIDUAL_HOLD_ALREADY_CLEARED", "detail": "잔여 파지 표시 1 는 이미 해소했다(park, 2026-10-14T01:10:00.000001Z)"}
```

```json
{
  "error": "RESIDUAL_HOLD_OBSERVATION_MISMATCH",
  "detail": "발신자는 기체 humanoid-01 가 지금 C-1 를 들고 있다고 말한다. 현장에서 빈손을 확인했으면 이 관측(충돌 0b7d4c55-...)을 지목해 다시 확인한다. ...",
  "robotId": "humanoid-01",
  "markerId": 1,
  "conflictRequestId": "0b7d4c55-9a51-4f3e-8d5c-6b7a6f1c2a9e",
  "observation": {"observedKind": "HOLDING", "observedObjectRef": "C-1", "observedDetail": null, "snapshotSession": "s-1", "snapshotSequence": "42",
    "snapshotOccurredAt": "2026-10-14T01:09:59.900Z", "hostReadStartedAt": "2026-10-14T01:10:00Z", "hostReadReceivedAt": "2026-10-14T01:10:00Z"}
}
```

### 5.4a `GET /host/robots/{robotId}/residual-holds/{markerId}`(B2)

호스트 잠금을 잡고 DB 를 읽음(처리 중인 해소가 잠금 안에서 스냅숏을 읽는 동안 기다림. 그래서 응답 없음 뒤 재조회가 처리 중인 해소를 «해소 행 없음» 으로 확정하지 않음. 운영 서비스는 시간 초과면 모름). 200 본문 칸 2개 `hold`(5.3 의 표시 하나)·`clearing`(5.4 의 해소 행, 해소하지 않았으면 null). 그 기체에 그 표시가 없으면 404 `RESIDUAL_HOLD_NOT_FOUND`, `markerId` 가 양의 정수가 아니면 400 `BAD_REQUEST`. 운영 서비스의 응답 없음 뒤 재조회가 씀.

(예의 시각·id 값은 보기다. 근거 시험: `ExecutionControlTest`, 칸 순서는 거기서 대조.)

### 5.5 실행의 `permitBlock` 칸(ADR 52)

실행마다 끝에 `permitBlock`(`cancel` 뒤, 열한째 칸). 이 실행의 새 로봇 명령을 지금 막고 있는 작업 허가다. 없거나 현장이 대응을 선언하지 않았으면 null. 칸 4개 이 순서 `unitId`, `withheld`, `robots`, `since`. `withheld` 는 `START`·`REVISION_UPDATE`·`IN_DOUBT_LOOKUP`. `robots` 는 막은 기체만, 원소 칸 3개 `robotId`, `permit`(`PERMITTED`·`INHIBITED`·`UNKNOWN`), `reasons`. `reasons` 원소 칸 5개 `source`, `cause`, `subject`, `resumeBasis`, `observedAt`. `cause` 는 `REPORTED`·`EXPIRED`·`SESSION_CHANGED`·`SCOPE_UNKNOWN`·`NOT_COVERED`·`PORT_FAILED`. `source`·`observedAt` 은 `NOT_COVERED`·`PORT_FAILED` 면 null, `subject` 는 원천이 말하지 않았으면 null. `since` 는 같은 단위의 같은 종류가 처음 막힌 호스트 시각. 시각은 ISO-8601. 운영자 보류(`OPERATOR_HOLD`)·결함 차단(`jobResponse.blockedBy`)과 다른 칸이고 작업 응답에는 싣지 않는다. 근거 시험: `WorkPermitHostTest`, 칸 순서는 `HostRestoreTest` 의 `EXECUTION_FIELDS`.

```json
"permitBlock": {"unitId": "T1.travel", "withheld": "START", "robots": [{"robotId": "humanoid-01", "permit": "INHIBITED", "reasons": [{"source": "cell/guard_closed", "cause": "REPORTED", "subject": "guard_closed=false", "resumeBasis": "안전 계통이 guard_closed 을 true 으로 되돌릴 때", "observedAt": "2026-10-14T01:02:03.250Z"}]}], "since": "2026-10-14T01:02:03.500Z"}
```

```json
{"robotId": "humanoid-01", "permit": "UNKNOWN", "reasons": [{"source": null, "cause": "NOT_COVERED", "subject": null, "resumeBasis": "이 기체를 덮는 허가 원천이 생길 때", "observedAt": null}]}
```

### 5.6 `GET /host/work-permits`(ADR 52)

늘 200. 본문 칸 6개 이 순서 `declared`, `evaluatedAt`, `session`, `validFor`(ISO-8601 기간 문자열), `mappings`(원소 `{signal, permittedWhen, robots}`), `robots`(대응이 맡은 기체마다 원소 `{robotId, permit, reasons}`, 5.5 와 같은 모양, 통과한 기체도 실림). 선언하지 않았으면 `declared` false, `session`·`validFor` null, 두 배열은 빈 것. 판정은 발행 게이트와 같은 규칙으로 같은 판독을 본다. 읽기만 하고 기체를 세우거나 풀지 않는다. 호스트 잠금을 잡지 않는다. 운영 서비스 스테이션 조정이 `tick` 마다 읽는다(운영 서비스 화면 경로는 없음). 근거 시험: `WorkPermitHostTest` 의 첫째·둘째 시험, 운영 서비스 `HostClientTest` 의 `작업 허가는 GET host work-permits 본문 그대로이고 200 아님은 모름이다`.

```json
{
  "declared": true,
  "evaluatedAt": "2026-10-14T01:02:04Z",
  "session": "cell-band#1",
  "validFor": "PT10S",
  "mappings": [{"signal": "guard_closed", "permittedWhen": "true", "robots": ["humanoid-01", "quadruped-01"]}],
  "robots": [
    {"robotId": "humanoid-01", "permit": "INHIBITED", "reasons": [{"source": "cell/guard_closed", "cause": "REPORTED", "subject": "guard_closed=false", "resumeBasis": "안전 계통이 guard_closed 을 true 으로 되돌릴 때", "observedAt": "2026-10-14T01:02:03.750Z"}]},
    {"robotId": "quadruped-01", "permit": "INHIBITED", "reasons": [{"source": "cell/guard_closed", "cause": "REPORTED", "subject": "guard_closed=false", "resumeBasis": "안전 계통이 guard_closed 을 true 으로 되돌릴 때", "observedAt": "2026-10-14T01:02:03.750Z"}]}
  ]
}
```

```json
{"declared": false, "evaluatedAt": "2026-10-14T01:02:04Z", "session": null, "validFor": null, "mappings": [], "robots": []}
```

(예의 시각 값은 보기다.)

### 5.7 좁히는 인자

`GET /host/executions?jobOrderId=…&executionId=…`. 두 인자 모두 되풀이 가능하며 값은 쉼표로 가르지 않는다. 인자를 주면 같은 모양이고 `instanceId`·`pumpedAt`·`restore`는 그대로이며, `executions`와 `journalBlockedExecutionIds`는 고른 실행만 담는다. 두 인자는 합집합이다(작업 지시나 실행 id 중 하나라도 맞으면 든다). 빈 값은 400 `BAD_REQUEST`다. 인자가 없으면 전과 같은 전체 목록이다. 모르는 인자는 버린다. 좁히는 인자는 운영 서비스 스테이션 조정과 작업 요청 상세·재작업 게이트, 응답 누락 뒤의 두 다시 읽기(작업 지시 제출은 `jobOrderId`, 실행 취소는 `executionId`로 읽는다)가 쓴다. 운영 서비스 `GET /api/executions`는 이 인자들을 호스트에 넘기지 않는다(무시한다. 9.4 참고). 좁힌 읽기 역시 이 인스턴스가 이빅션한 실행을(종결 본문에서) 가져오며, 결과는 같은 시점의 전체 목록을 해당 실행으로 거른 것과 여전히 같다. 근거 시험: 실행 호스트 `ExecutionsNarrowedTest`의 `작업 지시나 실행 id 로 좁힌 실행 목록은 같은 순간의 전체 목록에서 그 실행만 남긴 것과 같다`, `일지에 막힌 실행은 좁힌 목록에 그 실행이 들 때만 막힘으로 나온다`, `인자 없는 실행 목록은 정착한 실행까지 전과 같은 본문이고 좁히는 값은 쉼표로 가르지 않으며 빈 값은 400 이다`, `ForgetSettledTest`의 `정착을 적고 응답을 ack 한 뒤 끝난 실행을 놓으면 호스트 메모리가 비고 목록과 좁힌 목록은 정착 본문으로 같은 줄을 싣는다`.

### 5.8 화면 페이지

화면의 실행 목록은 활성 실행과 최근 종결 실행 N개를 읽고, 그보다 이전 이력은 페이지로 읽는다. 종결한 실행은 미들웨어 메모리가 아니라 실행 호스트의 종결 기록(DB)에서 읽으므로, 실행 호스트가 picasso `forget`을 불러 실제로 메모리에서 사라지거나 호스트 재기동 뒤에도 남는다.

`GET /host/executions?recent=N`. N은 1~200 정수다. 본문 칸은 `{instanceId, pumpedAt, executions, restore, journalBlockedExecutionIds, reworkableTargets, settled, nextBefore, olderTotal}` 순서다. 앞 다섯 칸은 §5와 이름과 뜻이 같다.
- `executions`: 현재 인스턴스의 남은 실행만 담는다(메모리, 생성 순). 남은 실행은 활성이거나, 종결했으나 이번 종결 기록(`SETTLED` 이벤트)이 아직 커밋되지 않은 실행이다(재실행해 다시 종결한 실행도 든다). 활성은 `PhysicalState.pumps`(아직 종결하지 않았거나 `PARTIAL`: `REQUESTED`·`ACCEPTED`·`RUNNING`·`PARTIAL`·`IN_DOUBT`·`OPERATOR_HOLD`·`CANCELING`), 적어도 한 단위가 `OPERATOR_HOLD`나 `IN_DOUBT`, 일지에 막힘 중 하나에 해당한다.
- `journalBlockedExecutionIds`: 남은 실행 가운데 막힌 실행이다. 막힌 실행은 늘 활성이고 따라서 늘 남은 실행이므로 전체 목록과 같다.
- `reworkableTargets`: 재작업할 수 있는 실패 묶음 목록이다(picasso §15.323). 실행 호스트 잠금 아래에서 picasso `Middleware.reworkCandidates()`(묶음 하나에 후보 하나, 메모리 조건 — 모양·종결·같은 기체의 다른 활성 실행·묶음 규칙)를 그 순서대로 옮기고 보존 만료 시각만 덧붙인다. 호스트는 건너뛴 단위 술어(`PREDECESSOR_NOT_DONE` ∧ 태스크 빔)를 따로 들지 않는다. 각 요소는 `{executionId, jobOrderId, robotId, unitId, physicalState, retainedUntil, attempt, bundle, kind, incidentUnitId, recheckFailed}` 이다. `unitId` 는 판단을 낼 대상(건너뛴 쌍이면 건너뛴 점검), `attempt` 는 그 대상의 지금 시도, `bundle` 은 함께 되돌릴 단위 id(계획 순서), `kind` 는 `SKIPPED`·`ATTEMPTED_INSPECTION`·`ATTEMPTED_HANDLING`(`ATTEMPTED_HANDLING` 은 받을 때 물리 관문 — 손·목적지·출발·예약·구역 — 을 지난다), `incidentUnitId` 는 판단이 붙을 인시던트의 단위(없으면 null), `recheckFailed` 는 묶음에 첫 발행 직전 재검사를 못 지나 태스크 없이 `FAILED`(`failureClass = REWORK_RECHECK`)로 닫힌 단위가 있는가다. 뒤 다섯 칸은 picasso `4d8b364` 반입 때 끝에 붙었다. `FAILED` 실행은 현재 종결 기록이 쓰인 뒤에만 목록에 오르며 `retainedUntil` 은 종결 기록 시각에 `host.settled.revisable-retention`(기본 5분)을 더한 시각이고, `PARTIAL` 은 `retainedUntil: null` 이다. 재작업하거나 이빅션되면 목록에서 빠진다. 목록에 있더라도 실행 호스트의 수락이 보장되는 것은 아니며(후보는 포트를 읽지 않는다 — 발행 관문·물리 관문은 판단 때에만 답한다) picasso 가 `NotHeld` 나 `Refused` 로 응답할 수 있다. `Refused.detail` 은 picasso 문장 그대로이고 읽는 쪽은 뜯어 범주를 나누지 않는다. 운영 서비스는 이 본문을 그대로 전달하며, 이 칸이 없는 본문은 대상이 없는 것으로 읽는다.
- `settled`: 인스턴스를 가리지 않고 가장 최근 종결 이력 N개 행이며 최신 순이다. 종결마다 한 행이라 한 작업 지시가 여러 행을 가질 수 있다. 각 원소는 `{settleSeq, instanceId, settledAt, snapshot, execution, superseded}`이다. `execution`은 §5의 실행 한 행과 모양이 같다.
  - `settleSeq`: `execution_journal_event`의 `SETTLED` 이벤트 `event_id`다. 정렬 키이자 커서다. 클수록 나중에 종결한 것이다. 다른 일지 이벤트가 같은 식별자를 나누므로 연속적이지 않다.
  - `instanceId`: 종결 기록을 쓴 인스턴스다(재기동 전 인스턴스일 수 있다).
  - `settledAt`: 기록의 DB 시각이다.
  - `snapshot`: `execution`이 종결할 때 쓴 실행 본문이면 true다(마이그레이션 V8). V8 전에 쓰인 행은 `snapshot: false`이며 일지 행으로 만든 `execution`이다(`units` 빈 목록, `jobResponse`와 `cancel` null, 접수 당시 기체).
  - `superseded`: 같은 작업 지시의 더 뒤 종결 이벤트가 있으면 true이다.
- `nextBefore`: 이전 이력이 있으면 이 페이지에서 가장 오래된 `settleSeq`이고, 없으면 null이다.
- `olderTotal`: `nextBefore`보다 오래된 이력 행의 수다(null이면 0).

`GET /host/executions?before=<settleSeq>[&limit=M]`. `before`는 양의 정수, M은 1~200 정수이며 기본값은 20이다. 본문은 `{settled, nextBefore, olderTotal}`이다. `settleSeq`가 `before`보다 작은 이력 행을 최신 순으로 최대 M개 담는다. 칸의 뜻은 위와 같다.

`GET /host/executions/summary`. 본문은 `{instanceId, pumpedAt, live, states, journalBlockedExecutionIds}`다. `live`는 남은 실행 수다. `states`는 열한 가지 `PhysicalState` 이름을 모두 담고(0 포함) 남은 실행만 센다. 늘 200이다.

읽기 순서는 메모리가 먼저이고(호스트 잠금 아래) DB가 나중이다(잠금 밖). 남은 실행이 아닌 실행은 이미 종결 기록이 커밋되었으므로 빠뜨리지 않는다. 그 사이에 종결 기록이 쓰인 실행은 양쪽에 모두 나타날 수 있으며, 화면은 (인스턴스, 실행 id) 기준 남은 실행 쪽에 한 번만 표시한다. 호스트는 호스트 잠금 아래 단일 트랜잭션 안에서만 종결 기록을 쓰므로 `event_id` 할당 순서는 커밋 순서와 같다(단일 호스트 전제).

값이 잘못되었거나(정수가 아님, 범위 벗어남, 빈 값, 되풀이됨), `recent`를 `before`나 `limit`와 섞어 쓰거나, `before` 없이 `limit`를 주거나, 페이지 인자를 좁히는 인자(`jobOrderId`·`executionId`)와 섞어 쓰면 400 `BAD_REQUEST`다. `instanceId`는 이 엔드포인트들의 인자가 아니며 다른 모르는 인자처럼 버린다.

근거 시험: 실행 호스트 `ExecutionsPagedTest`의 다섯(`화면 목록은 같은 순간의 전체 목록에서 남은 실행을 고르고 정착 이력은 정착할 때의 실행 본문이며 실행 수는 남은 실행을 센 것이다`, `정착 기록이 커밋되기 전의 정착 실행은 남은 실행으로 보이고 기록되면 정착 이력으로 옮긴다`, `커서를 이어 읽으면 정착 이력이 다 한 번씩 나오고 사이에 정착이 생겨도 남은 페이지가 그대로이며 재기동 뒤에도 남는다`, `일지에 막힌 실행은 활성이라 화면 목록에 늘 들고 실행 수의 남은 실행과 막힘에도 든다`, `페이지 인자는 범위와 짝을 검사하고 인자 없는 목록과 모르는 인자는 그대로다`), `SettledHistoryTest`의 셋(`정착 이력 페이지는 정착 이벤트 전부를 최근부터 늘어놓고 커서 앞에서 limit 개로 자른 것과 같다`, `커서를 이어 읽으면 정착 이력이 겹치거나 빠지지 않고 사이에 새 정착이 생겨도 남은 페이지가 그대로다`, `정착 이력은 인스턴스를 가리지 않고 일지 행의 기체와 임무를 싣고 본문 없는 정착 이벤트도 읽는다`), `ExecutionPagesTest`의 둘(`활성은 더 돌 수 있는 실행과 운영자 보류나 불확실 단위가 남은 실행과 일지에 막힌 실행이다`, `남은 실행은 활성이거나 정착 기록이 아직 커밋되지 않은 실행이다`), `ForgetSettledTest`의 둘(`실패 뒤 개정판으로 다시 정착하면 그 판의 정착 이력 줄을 하나 더 적고 앞 줄은 다시 정착함으로 표시하며 pump 를 거듭해도 정착마다 한 줄이다`, `취소한 보류 실행은 판단을 기다리는 동안 놓지 않고 판단으로 같은 판에서 다시 정착하면 정착 줄을 하나 더 적고 판단 기록을 적은 뒤 놓는다`).

### 5.9 실행의 `blockedFaults`·`releases` 칸

실행마다 끝에 `blockedFaults`(`permitBlock` 뒤, 열두째 칸)와 `releases`(마지막 열셋째 칸). 실행 행은 칸 13개 이 순서 `executionId, jobOrderId, workMasterId, missionVersion, robotId, physicalState, units, jobResponse, restoredFrom, cancel, permitBlock, blockedFaults, releases`. `blockedFaults` 는 결함 객체의 배열. 지금 다음 단위를 막고 있는 기체 결함 전부(picasso `Execution.blockedBy`). 없으면 `[]`. 결함 객체 칸은 S4a JSON 계약 §4 의 결함 객체 9칸과 같다(`failureClass, errorType, vendorDetail, errorHint, references[{key, value}], canContinueCurrentTask, canAcceptNewTask, activeUntilKind, activeUntilTime`), 같은 결함이면 인시던트 상세 `blockedBy` 와 칸마다 같다. `failureClass` 는 분류 이름(접두사 `FAILURE_CLASS_` 를 뗀 것, 분류가 없거나 모르면 picasso `Middleware.UNCLASSIFIED`), 그 목록은 작업 응답 `blockedBy` 의 이름들과 같다. 참조 `key` 는 계약 열거형 이름(예 `KEY_TASK_ID`). 작업 응답의 `blockedBy` 는 분류 이름뿐이고 picasso 가 새 작업 응답을 낼 때만 바뀌지만 `blockedFaults` 는 지금의 값이다. 결함 수용(§5.10)이 이것과 대조한다. 작업 허가 차단(`permitBlock`)과 다른 칸이다. `releases` 는 그 작업 지시에 건 운영자 결함 수용(V11 `execution_fault_release`, 적은 순서). 없으면 `[]`. 원소 칸 5개 이 순서 `requestId`, `approverId`, `reason`, `faults`, `recordedAt`. `requestId` 는 없으면 null. `faults` 는 수용할 때의 `blockedFaults` 그대로(결함 객체 배열). `recordedAt` 은 DB 시각. 작업 지시 id 로 읽으므로 재기동 전 인스턴스가 적은 수용도 싣는다. 이 인스턴스가 이빅션한 실행의 줄과 종결 이력의 `execution`(§5.8)은 종결할 때 적은 본문이라 두 칸도 종결할 때의 값이다. V11 전에 적은 종결 본문에는 두 칸이 없다. 이빅션한 실행의 줄로 읽으면 두 칸이 `[]` 로 나오고, 종결 이력의 `execution` 은 적힌 글자 그대로라 두 칸이 빠진다. 근거 시험: `FaultReleaseHostTest` 의 `본 결함대로 감수하면 감수 행과 판단 기록을 곧바로 적고 막힘이 걷힌 작업 응답 하나를 곧바로 내며 이동이 실패한 대상의 점검은 거르고 다음 대상의 단위를 낸다`(실행 목록 `blockedFaults` 가 인시던트 상세 `blockedBy` 와 같음, `releases` 칸 순서), `JudgmentRecordHostTest` 의 `감수한 결함을 실은 기록으로 다시 지으면 그 결함이 다음 단위를 다시 막지 않는다`(재기동한 인스턴스의 `releases` 가 앞 인스턴스의 수용을 실음), 칸 순서는 `HostRestoreTest` 의 `EXECUTION_FIELDS`.

```json
"blockedFaults": [{"failureClass": "LOCALIZATION_LOST", "errorType": "LOCALIZATION_LOST", "vendorDetail": "", "errorHint": "로봇을 알려진 랜드마크 앞으로 옮기고 재측위를 실행하십시오.", "references": [], "canContinueCurrentTask": false, "canAcceptNewTask": false, "activeUntilKind": "KIND_UNTIL_CLEARED", "activeUntilTime": ""}]
```
```json
"releases": [{"requestId": "6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a", "approverId": "kim", "reason": "현장에서 기체 상태를 확인했다", "faults": [{"failureClass": "LOCALIZATION_LOST", "errorType": "LOCALIZATION_LOST", "vendorDetail": "", "errorHint": "로봇을 알려진 랜드마크 앞으로 옮기고 재측위를 실행하십시오.", "references": [], "canContinueCurrentTask": false, "canAcceptNewTask": false, "activeUntilKind": "KIND_UNTIL_CLEARED", "activeUntilTime": ""}], "recordedAt": "2026-10-17T01:02:03.456789Z"}]
```
(예의 값은 보기다.)

### 5.10 `POST /host/executions/{executionId}/release`

운영자 결함 수용이다. picasso `Middleware.release` 는 승인자와 사유를 받지 않고 지금 막는 결함 전부를 수용한다. 그래서 사람 승인자 판정과 «본 결함» 대조를 실행 호스트가 한다. 요청 본문은 `{approverId, reason, instanceId, sawFaults, requestId?}` 다. `approverId`·`reason`·`instanceId` 는 비어 있지 않은 문자열(공백만이면 안 됨)이고, 사유는 앞뒤 공백을 떼며 길이 제한은 호스트에 없다. `sawFaults` 는 운영자가 본 막는 결함이며 비어 있지 않은 객체 배열이다(원소 `{errorType, references}`, `errorType` 비어 있지 않은 문자열, `references` 필수이고 빈 배열 가능한 객체 배열, 원소 `{key, value}`, `key` 비어 있지 않은 문자열, `value` 빈 문자열 가능한 문자열). `requestId` 는 없음·`null`·UUID 문자열이다. 400 은 본문이 객체가 아님(«본문이 JSON 객체가 아니다»), `approverId`·`reason`·`instanceId` 와 `errorType`·`key` 가 비어 있지 않은 문자열이 아님(«<칸> 가 비어 있지 않은 문자열이 아니다»), `sawFaults` 가 비어 있지 않은 객체 배열이 아님(«sawFaults 가 비어 있지 않은 객체의 배열이 아니다»), `references` 가 객체 배열이 아님(«sawFaults 의 references 가 객체의 배열이 아니다»), 참조 `value` 가 문자열이 아님(«sawFaults 의 참조 value 가 문자열이 아니다»), `requestId` 가 UUID 문자열이 아님(«requestId 가 UUID 문자열이 아니다»). 판정 순서 본문(400) → `instanceId` 가 지금 인스턴스인가(아니면 409 `INSTANCE_MISMATCH`, `detail` «수용 요청의 인스턴스(<요청>)가 지금 인스턴스(<지금>)가 아니다», 수용하지 않음) → (여기서부터 호스트 잠금 안) 사람 승인자 → 요청 id 재사용 → 실행 찾기 → 막는 결함 유무 → 본 결함 대조 → 수용 행 적기(V11 `execution_fault_release`) → picasso `release` → 판단 기록 적기 → 결과. 결과는 늘 200 본문 `{result, executionId, physicalState, detail, requestId}` (5칸, 이 순서) 다. `requestId` 는 요청 값 그대로다. §5 공통과 달리 `requestId` 는 수용 행에 적는다. 같은 `requestId` 의 수용 행이 이미 있으면 409 `REQUEST_ID_REUSED`(`detail` «결함 수용의 요청 id 가 이미 쓰였다: <requestId>»)이고 아무것도 바꾸지 않는다. 실행 상태와 무관하게 실행을 찾기 전에 가른다(수용해서 더는 막히지 않은 실행에 같은 요청을 다시 보내도 409). S3b JSON 계약 §3 과 같은 이름이다. 요청 id 의 UNIQUE 는 DB 의 둘째 담이다. 잠금 안에서 먼저 묻고 적으므로 이 인스턴스에서는 거기 걸리지 않고, 걸리면 500 `RECORD_WRITE_FAILED` 다(409 아님). 수용 행을 못 적으면 500 `RECORD_WRITE_FAILED` 다. picasso `release` 를 부르지 않는다. 실행은 막힌 그대로, 판단 기록도 늘지 않는다. 수용 직후 pump 없이 판단 기록을 적는다. 그 기록의 `acknowledgedFaults` 에 수용한 결함의 열쇠(실측 `LOCALIZATION_LOST[]`)가 들어 재기동을 넘는다. 판단 기록 쓰기가 실패하면 경고만 남기고 다음 pump 뒤 기록이 같은 글자를 다시 적는다(운영자 판단과 같다).

| `result` | 언제 | `physicalState` | `detail` | 바뀐 것 |
|---|---|---|---|---|
| `RELEASED` | 막는 결함이 있고 본 결함과 같음 | 수용 뒤 상태 `RUNNING` | 작업 허가가 아직 이 실행의 새 로봇 명령을 막고 있으면 `작업 허가 대기 중이다. 수용으로 풀리지 않는다: <unitId> (<withheld>)`, 아니면 null | 수용 행 하나, picasso `acknowledgedFaults`, 실행 `RUNNING`, 작업 응답 하나(`RUNNING`, `blockedBy` `[]`), 판단 기록 한 행. 다음 pump가 이를 송신 기록·결과 outbox에 기록하고 다음 단위를 낸다. |
| `NOT_BLOCKED` | 실행은 있으나 막는 결함이 없음 | 지금 상태(이 인스턴스가 이빅션한 실행이면 마지막 종결 본문의 상태) | 작업 허가만 막고 있으면 `작업 허가 대기 중이다. 수용으로 풀리지 않는다: <unitId> (<withheld>)`(허가는 수용할 값이 아니다, ADR 52, picasso `release` 가 허가 차단을 걷지 않는다). 종결한 실행(이 인스턴스가 이빅션한 실행 포함)이면 `종결한 실행이다: <상태>`. 그 밖(도는데 막는 결함 없음, `PARTIAL` 포함)은 `막는 결함이 없다`. | 없음 |
| `BLOCK_CHANGED` | 본 결함과 지금 막는 결함이 다름 | 지금 상태(`OPERATOR_HOLD`) | `본 결함과 지금 막는 결함이 다르다. 다시 읽고 판단한다: <errorType[KEY=value,…]>, …`(지금 막는 결함을 `, ` 로 이음, 참조가 없으면 `[]`) | 없음 |
| `NOT_FOUND` | 지금 인스턴스에 그 실행이 없음(메모리에도 종결 본문에도) | null | `그 실행이 지금 인스턴스에 없다` | 없음 |
| `REFUSED` | 승인자가 사람이 아님. REST 는 늘 사람 승인자라 호스트 메서드를 직접 부를 때만 | null | `에이전트는 결함을 수용하지 못한다. 결함을 안고 계속할지는 사람이 현장에서 정한다: <approverId>` | 없음 |

대조는 (`errorType`, 참조 (key, value) 모음)의 다중집합으로 한다. 참조 순서와 결함 순서는 보지 않는다. 같은 결함을 둘 보내면 지금 하나일 때 다르다. 참조 `key` 는 계약 열거형 이름(예 `KEY_TASK_ID`)이고 §5.9 `blockedFaults` 의 `references` 와 같다. picasso 의 결함 열쇠 문자열은 호스트가 만들지 않는다. picasso `release` 가 막는 결함 전부를 수용하므로 이 대조가 본 적 없는 결함의 수용을 막는 자리다. 대조와 수용이 같은 잠금 안이라 그 사이에 막는 결함이 바뀌지 않는다. 수용은 결함을 지우지 않는다. 같은 결함은 다시 막지 않고 새 결함은 다시 막는다.

picasso `ddca127`(PR #109)부터 `release`는 true를 반환할 때 `notify`를 한 번 부르고 새 작업 응답(`physicalState` `RUNNING`, `blockedBy` `[]`, `operatorRequired` false)을 낸다. RELEASED 수용 직후 실행 목록의 `jobResponse`(마지막 작업 응답)는 곧바로 이 `RUNNING` 응답이 된다. 다음 pump가 이를 송신 기록과 결과 outbox에 한 번 기록하므로 상위도 이를 본다. 반복된 수용 요청은 `NOT_BLOCKED`를 반환하며 응답을 추가하지 않는다. 단위 시작과 단위 완료는 여전히 작업 응답을 내지 않으므로, 이 `RUNNING` 응답은 실행이 종결(실측 `blockedBy` `[]`인 `PARTIAL`)할 때까지 마지막 응답으로 유지된다. 실행 목록의 `physicalState`·`blockedFaults`·`releases`는 곧바로 바뀐다. 운영자 판단(picasso `resolve`)은 여전히 `notify`를 부르지 않아 판단 뒤 다음 작업 응답 전까지 작업 응답이 갱신되지 않는 반면(S4a JSON 계약 §5.2), 수용은 이와 다르다.

```json
{"approverId": "kim", "reason": "현장에서 기체 상태를 확인했다", "instanceId": "mw-3f0c...", "sawFaults": [{"errorType": "LOCALIZATION_LOST", "references": []}], "requestId": "6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a"}
```
```json
{"result": "RELEASED", "executionId": "exec-1", "physicalState": "RUNNING", "detail": null, "requestId": "6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a"}
{"result": "BLOCK_CHANGED", "executionId": "exec-1", "physicalState": "OPERATOR_HOLD", "detail": "본 결함과 지금 막는 결함이 다르다. 다시 읽고 판단한다: LOCALIZATION_LOST[]", "requestId": null}
{"result": "NOT_BLOCKED", "executionId": "exec-1", "physicalState": "PHYSICALLY_DONE", "detail": "종결한 실행이다: PHYSICALLY_DONE", "requestId": null}
```
```json
{"error": "REQUEST_ID_REUSED", "detail": "결함 수용의 요청 id 가 이미 쓰였다: 6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a"}
```

(예의 id 값은 보기다. 근거 시험: `FaultReleaseHostTest` 의 첫째~여덟째 시험, `본 결함대로 감수하면 감수 행과 판단 기록을 곧바로 적고 막힘이 걷힌 작업 응답 하나를 곧바로 내며 이동이 실패한 대상의 점검은 거르고 다음 대상의 단위를 낸다`, `본 결함과 지금 막는 결함이 다르면 BLOCK_CHANGED 이고 감수 행 없이 막힌 그대로다`.)

### 5.11 `POST /host/job-orders/{jobOrderId}/withdraw`(B3)

실행이 없는 작업 지시를 영구히 철회한다. 그 뒤로 실행 호스트는 그 작업 지시를 값 없는 승인(`REMEDY_NOT_APPLIED`)으로도 새 접수(`POST /host/job-orders` 의 `REJECTED`, `rejectionReason` 앞머리 `철회된 작업 지시다. 실행을 짓지 않는다`)로도 실행하지 않는다. 풀어 주는 호출은 없다. 대상이 작업 지시 id 라 `instanceId` 막이는 없다.

본문 `{requestId, reason, requestedBy}`: `requestId` 는 필수 UUID 문자열(정규형으로 적음), `reason`·`requestedBy` 는 비어 있지 않은 문자열(`reason` 은 앞뒤 공백을 뗌). 판정은 호스트 잠금 아래이고 순서는 다음과 같다.

1. 실행 일지가 막혀 있으면(`journalBlockedExecutionIds` 가 비지 않음) 500 `JOURNAL_WRITE_FAILED`, 적지 않음. 일지에 없는 실행이 미들웨어에 있을 수 있어 어느 결과도 주지 않는다.
2. 그 작업 지시의 실행이 미들웨어에 있으면 `EXECUTION_EXISTS`(지금 인스턴스, 실행 id, 기체, 지금 물리 상태), 적지 않음.
3. 실행 일지에 그 작업 지시의 행이 있으면 `EXECUTION_EXISTS`(일지의 마지막 실행 — 받은 행이나 그 뒤 다시 지은·정착한 실행 —, 기체, 마지막 차례가 정착·다시 돎이면 그 물리 상태, 운영자 종결이면 `UNVERIFIED`, 그 밖에는 null), 적지 않음.
4. 철회 행이 이미 있으면 `WITHDRAWN` 과 그 행(요청 id 가 달라도 같다, 작업 지시 기준 멱등).
5. 그 요청 id 를 다른 작업 지시의 철회에 썼으면 409 `REQUEST_ID_REUSED`, 적지 않음.
6. 철회 행을 적고 `WITHDRAWN`. 그 작업 지시의 관문 확인 제안 표시를 지운다(picasso 의 제안 장부는 지우지 않음). 행을 못 적으면 500 `RECORD_WRITE_FAILED`.

```json
{"requestId": "6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a", "reason": "상위 시스템 대체 접수: MES-SUPPLY-2", "requestedBy": "operator/mock-mes"}
```

```json
{"result": "WITHDRAWN", "jobOrderId": "JO-1", "instanceId": null, "executionId": null, "robotId": null, "physicalState": null,
 "withdrawal": {"requestId": "6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a", "requestedBy": "operator/mock-mes", "reason": "상위 시스템 대체 접수: MES-SUPPLY-2", "withdrawnAt": "2026-11-14T01:02:03.456Z"},
 "requestId": "6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a"}
{"result": "EXECUTION_EXISTS", "jobOrderId": "JO-1", "instanceId": "mw-3f0c...", "executionId": "exec-1", "robotId": "humanoid-01", "physicalState": "RUNNING", "withdrawal": null, "requestId": "6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a"}
```

`withdrawal.requestId` 는 처음 철회한 요청의 id 다(다시 철회한 요청의 id 는 맨 끝 `requestId`). 실행 취소(5.2)의 `NOT_FOUND` 는 미실행 증거가 아니다 — 미실행 작업 지시를 폐기하는 취소를 만들면 이 철회를 거친다. 근거 시험: `IncidentLoopHostTest` 의 `관문 확인으로 제안이 선 작업 지시를 철회하면 값 없는 승인과 재제출을 재기동 뒤에도 거부하고 철회는 작업 지시마다 한 행이다`, `승인이 먼저 실행을 세운 작업 지시의 철회는 EXECUTION_EXISTS 이고 적지 않으며 정착해 놓은 뒤에는 실행 일지로 답한다`, `실행 일지가 막혀 있으면 철회는 성공을 주지 않고 다른 작업 지시에 쓴 요청 id 는 409 다`.

## 6. `GET /host/cell`

응답 200 늘:

```json
{"cell": {"presentations": [...], "slots": [...]}}
```

- `cell` 은 마지막 pump 가 현장 `/cell` 에서 읽은 스냅숏이며 모양은 1절 본문과 같다(칸 넷, 시각은 ISO 문자열).
- 현장 셀 대역이 안 닿거나(연결 실패, 1초 시간 초과, 200 아님, 모양 어긋남) 아직 pump 가 안 돌았으면 `{"cell": null}`(못 물어봄, 화면 «모름»). 이때 미들웨어의 셀 신호도 null 이라 PrepareSequencedRack 은 E2 를 못 얻고 마감 뒤 UNVERIFIED 가 된다.

## 7. 셀 신호(호스트 안, 참고)

- `observe(자리)`: 스냅숏의 제시 자리·슬롯에서 같은 id 를 찾아 `SlotSignal(occupied, material, observedAt)`. 없는 자리는 null. 스냅숏이 없으면 null. 그 제출 본문에 그 자리의 출발 자리 확인(`sourceConfirmations`)이 있고 스냅숏이 말이 없거나(null) «있다» 고만 하면(점유, 신원 없음) `SlotSignal(true, identity, confirmedAt)` 이다. 스냅숏이 비었다·다른 것이 있다·신원까지 말하면 스냅숏의 답 그대로다(확인이 관측을 뒤집지 않는다). 그 제출이 끝나면 확인을 비운다. 근거: `SourceObservationHostTest` 의 `사람의 출발 자리 확인을 실은 제출은 그 한 번만 셀 관측이 되어 게이트를 지나고 다음 제출은 다시 설비만 본다`, `확인한 신원이 다르면 결품이고 설비가 비었다고 말하면 사람의 확인이 그 관측을 뒤집지 않는다`.
- `holding(자재)`: 그 자재를 든 **제시 자리**만(채운 슬롯은 안 냄). 스냅숏이 없으면 null.
- `signal(이름)`: 늘 null(S3b).

## 8. 상태 코드 요약

| 엔드포인트 | 200 | 400 | 415 |
|---|---|---|---|
| `POST /host/eligibility` | 판정 | `UNKNOWN_WORK_MASTER`·`BAD_REQUEST` | JSON 아님 |
| `POST /host/job-orders` | 결과 넷 전부 | `UNKNOWN_WORK_MASTER`·`BAD_REQUEST` | JSON 아님 |
| `GET /host/executions` | 늘 | `BAD_REQUEST`(좁히는 인자의 빈 값이나 잘못된 페이지 인자, 5.7·5.8절 참조) | 없음 |
| `GET /host/executions/summary` | 늘 | 없음 | 없음 |
| `GET /host/cell` | 늘(`cell` null 가능) | 없음 | 없음 |
| `GET /host/work-permits` | 늘(`declared` false 가능) | 없음 | 없음 |
| `POST /host/executions/{executionId}/cancel` | 결과 넷 전부(5.2) | `BAD_REQUEST`(그 밖 409 `INSTANCE_MISMATCH`, 500 `RECORD_WRITE_FAILED`) | JSON 아님 |
| `POST /host/executions/{executionId}/release` | 결과 다섯 전부(5.10) | `BAD_REQUEST`(그 밖 409 `INSTANCE_MISMATCH`, 409 `REQUEST_ID_REUSED`, 500 `RECORD_WRITE_FAILED`) | JSON 아님 |
| `GET /host/robots/residual-holds` | 늘 | 없음 | 없음 |
| `POST /host/robots/{robotId}/residual-holds/{markerId}/clear` | 해소함 | `BAD_REQUEST`(그 밖 400 `RESIDUAL_HOLD_CITATION_INVALID`, 404 `RESIDUAL_HOLD_NOT_FOUND`, 409 `RESIDUAL_HOLD_ALREADY_CLEARED`, 409 `RESIDUAL_HOLD_OBSERVATION_MISMATCH`, 409 `REQUEST_ID_REUSED`, 403 `APPROVER_REFUSED`(REST 로는 안 남), 500 `RECORD_WRITE_FAILED`) | JSON 아님 |
| `GET /host/robots/{robotId}/residual-holds/{markerId}` | 늘 | `BAD_REQUEST`(그 밖 404 `RESIDUAL_HOLD_NOT_FOUND`) | 없음 |
| `POST /host/job-orders/{jobOrderId}/withdraw` | 결과 둘 전부(5.11) | `BAD_REQUEST`(그 밖 409 `REQUEST_ID_REUSED`, 500 `JOURNAL_WRITE_FAILED`(일지 막힘), 500 `RECORD_WRITE_FAILED`) | JSON 아님 |
| 현장 `GET /cell` | 늘 | 없음(405·404 는 위 1절) | 없음 |

호스트가 안 닿으면(연결 실패) 운영 서비스가 503·NO_RESPONSE 로 다룬다(스펙 §8). 작업 지시 철회(5.11)만 운영 서비스의 요청 제한이 따로다(`ops.host.withdraw-timeout`, 기본 3초) — 상위 시스템 대체가 앞선 작업 요청 행 잠금을 쥔 채 부른다. 운영 서비스는 철회의 응답 없음·5xx·잘못된 본문을 미전송 증거로 쓰지 않는다(대체 503 `WITHDRAWAL_UNCONFIRMED`, 9.9). 취소·해소(P0-b2)·결함 수용(5.10) 기록 실패는 500 `RECORD_WRITE_FAILED`, 실행 일지 쓰기 실패는 500 `JOURNAL_WRITE_FAILED`(S4b H6, 이 PR 전부터)다. 운영 서비스는 호스트 5xx 를 응답 없음으로 다루고 재조회한다.

---

## 9. 운영 서비스 REST(화면이 부르는 것)

운영 서비스가 실제로 내고 받는 모양이다. 화면과 통합 시험은 이것에 맞춘다.

공통 규칙

- 기준 주소 `http://127.0.0.1:${OPS_PORT}`. 시각은 ISO-8601 UTC 문자열이고, `null` 칸은 생략하지 않는다(스프링 기본 Jackson).
- 호스트 주소 설정 키는 `ops.host.url` 이다. 속성 파일이 `http://127.0.0.1:${HOST_PORT}` 로 주고 기본값이 없다(빠지면 기동에서 멈춘다). 형식이 `http(s)://호스트[:포트]` 가 아니어도 기동에서 멈춘다. 통합 시험은 `--ops.host.url=http://127.0.0.1:<호스트 포트>` 로 덮는다. 덮지 않으면 `.env` 의 8785 를 보고, 거기 아무것도 없으면 호스트 불통으로 다룬다(기존 시험은 그대로 돈다).
- 운영 서비스가 호스트를 부르는 제한: 연결 2초, 요청 5초. registry(3초)보다 긴 것은 호스트가 잠금 아래에서 mimic gRPC 를 부르고, mimic 이 엔진 잠금 아래에서 registry 적재(요청 3초)를 기다릴 수 있어서다.
- POST 는 `Content-Type: application/json` 만 받는다. 그 밖은 **415**(스프링 기본 본문, 모양 보장 없음).
- 사전 거부(400·403·503)의 본문은 늘 `PreRejection` 이다: `{"error": "...", "detail": "..."}`. 사전 거부는 조작 기록에 남지 않는다.

### 9.1 폼 초안(두 POST 의 본문)

```json
{"workMasterId": "InspectAsset", "targets": [{"id": "T1", "location": "bay-7"}]}
```

```json
{"workMasterId": "PrepareSequencedRack", "slots": ["RACK-204.S01", "RACK-204.S02"],
 "material": "ENGINE-COVER-A", "presentation": "SEQ-IN-02.BIN-A"}
```

| 칸 | 규칙 |
|---|---|
| `workMasterId` | `InspectAsset`·`PrepareSequencedRack`. 그 밖(`DeliverContainer` 포함)은 400 `UNKNOWN_WORK_MASTER` |
| `targets` | InspectAsset 만 읽는다. 비어 있지 않은 배열, 원소는 `{id, location}`(둘 다 비어 있지 않은 문자열). `id` 는 64자 이하(기체 `inspect` 스킬의 `target` 파라미터 `max_length`, mimic 처럼 UTF-16 문자 수) |
| `slots` | PrepareSequencedRack 만 읽는다. 비어 있지 않은 배열, 원소는 비어 있지 않은 문자열 |
| `material`·`presentation` | PrepareSequencedRack 만 읽는다. 비어 있지 않은 문자열. 제시 자리는 화면이 `/api/cell` 의 `presentations` 에서 자재로 고른다 |

- 그 임무가 쓰지 않는 칸은 무시한다.
- 400 `JOB_ORDER_BAD_REQUEST`: 본문이 JSON 객체가 아님, 칸 누락·형 틀림·빈 문자열·빈 배열, 대상 id 64자 초과(detail `대상 id 가 64자를 넘는다: 65자`).
- 400 `UNIT_ID_CONFLICT`: 단위 id 가 겹침. InspectAsset 의 단위 id 는 대상마다 `<id>.travel`·`<id>`, PrepareSequencedRack 은 슬롯 id 다. 대상 id 중복, 슬롯 중복, 대상 id 가 다른 대상의 `<id>.travel` 과 같은 경우가 걸린다. detail 예 `단위 id 가 겹친다: T1, T1.travel`(겹친 단위 id 이름순).
- 판정 순서: 본문 객체 → `workMasterId` → 그 임무의 칸 → 단위 id 겹침.
- 장소 이름·슬롯이 기체가 아는 명칭인지는 보지 않는다(스펙 §12).

운영 서비스가 만드는 작업 지시 본문은 2.1 절 그대로다. `version` 1, `parameters` `{}`, `requiredEvidence` 는 InspectAsset `E0`·PrepareSequencedRack `E2`, PrepareSequencedRack 의 `materialRequirements` 는 `[{materialDefinitionId: material, quantity: 슬롯 수}]`, 장비 요구는 슬롯마다 `destination` 을 폼 순서대로 두고 끝에 제시 자리 하나를 `source` 로 둔다(둘 다 `properties.material`). InspectAsset 은 대상마다 `inspection_target`(`properties.location`)이다.

### 9.2 `POST /api/job-orders/eligibility`

모드 헤더 없이 된다(부작용 없음). 본문은 9.1 폼. 호스트에는 작업 지시 id `JO-DRAFT` 로 묻는다.

응답 200:

```json
{
  "checkedAt": "2026-10-08T00:00:00Z",
  "registry": "OK",
  "robotsAsOf": "2026-10-08T00:00:00Z",
  "host": "OK",
  "robots": [
    {
      "robotId": "humanoid-01",
      "commissioning": "COMPLETE",
      "connection": "FRESH",
      "settingsVersion": 1,
      "host": {"robotId": "humanoid-01", "skillFit": "FIT", "missingSkills": [], "runningExecutionId": null, "passed": true, "reasons": []},
      "eligible": true,
      "reasons": []
    },
    {
      "robotId": "quadruped-01",
      "commissioning": "COMPLETE",
      "connection": "FRESH",
      "settingsVersion": 1,
      "host": {"robotId": "quadruped-01", "skillFit": "MISSING", "missingSkills": ["pick_place"], "runningExecutionId": null, "passed": false, "reasons": ["모자란 스킬: pick_place"]},
      "eligible": false,
      "reasons": ["모자란 스킬: pick_place"]
    }
  ]
}
```

| 칸 | 값 |
|---|---|
| `registry` | `OK`·`REGISTRY_SILENT`·`REGISTRY_UNAUTHORIZED`(`GET /api/robots` 의 것과 같다). `OK` 가 아니면 모든 행의 `commissioning`·`connection`·`settingsVersion` 이 null(«모름»). 직전 목록의 값을 대신 쓰지 않는다 |
| `robotsAsOf` | 기체 목록을 registry 에서 읽은 시각. `checkedAt` 과 다르면 직전 목록이다. 한 번도 못 읽었으면 null |
| `host` | `OK`·`HOST_SILENT`. `HOST_SILENT` 면 모든 행의 `host` 가 null(«모름») |
| `robots` | 그 사이트의 기체 목록 전부(퇴역 포함, `GET /api/robots` 와 같은 순서). 목록을 한 번도 못 읽었으면 null(«모름»), 기체가 없으면 `[]`. 둘 다 호스트를 부르지 않는다 |
| `robots[].commissioning` | `COMPLETE`·`INCOMPLETE`·`RETIRED`, 또는 null(모름) |
| `robots[].connection` | `FRESH`·`STALE`·`NO_REPORT`, 또는 null(모름) |
| `robots[].settingsVersion` | 연결 판정의 근거 현장 설정 버전. `connection` 이 null 이면 null |
| `robots[].host` | 호스트 판정 행(2.2 그대로), 또는 null(호스트 불통, 또는 호스트가 그 기체 행을 주지 않음). `skillFit: UNKNOWN` 은 호스트가 기체 케이퍼빌리티를 못 물어본 것이고 이것도 «모름» 이다 |
| `robots[].eligible` | `commissioning == COMPLETE` 이고 `connection == FRESH` 이고 `host.passed`. null 이 하나라도 있으면 거짓 |
| `robots[].reasons` | 배정 가능이 아닌 이유(화면 표시용). 순서: registry 모름 또는 시운전·연결, 그다음 호스트. 배정 가능이면 `[]` |

이유 문구(그대로 나온다):

| 경우 | 문구 |
|---|---|
| registry 불통 | `registry 가 답하지 않아 시운전·연결을 모른다` |
| 운영자 토큰 불일치 | `운영자 토큰이 registry 와 맞지 않아 시운전·연결을 모른다` |
| 시운전 미완 | `시운전이 끝나지 않았다` |
| 퇴역 | `퇴역한 기체다` |
| 연결 오래됨 | `연결이 오래됐다(기준 90초, 현장 설정 버전 1)` |
| 보고 없음 | `생존 보고가 없다` |
| 호스트 불통 | `실행 호스트가 답하지 않아 스킬 적합·진행 중인 실행을 모른다` |
| 호스트가 그 기체 행을 안 줌 | `실행 호스트가 이 기체를 판정하지 않았다` |
| 호스트 판정 | 2.2 의 `reasons` 그대로(`모자란 스킬: a, b`, `기체 케이퍼빌리티를 못 물어봤다`, `진행 중인 실행이 있다: exec-N`, `잔여 파지 확인 필요: <jobOrderId> <unitId>`, 그 밖 S4b·S3c 이유도 그대로 옴) |

400 은 9.1 의 셋뿐이다. 403·`ACTOR_REQUIRED` 는 없다.

### 9.3 `POST /api/job-orders`

헤더 `X-Ops-Mode: operator`, `X-Ops-User: <[A-Za-z0-9._-]{1,64}>`. 본문은 9.1 폼.

| 순서 | 상태 | `error` | 언제 |
|---|---|---|---|
| 1 | 400 | `ACTOR_REQUIRED` | 두 헤더가 없거나 틀림 |
| 2 | 403 | `MODE_NOT_ALLOWED` | 엔지니어 모드. 본문이 깨져도 403 이 먼저다 |
| 3 | 400 | `JOB_ORDER_BAD_REQUEST`·`UNKNOWN_WORK_MASTER`·`UNIT_ID_CONFLICT` | 9.1 |
| 4 | 400 | `NO_ELIGIBLE_ROBOT` | 새 작업 지시 id 로 다시 판정해 `eligible` 인 기체가 없음. 호스트를 부르지 않는다. detail 은 `humanoid-01: 이유; 이유 / quadruped-01: 이유` 꼴. 기체 목록이 null 이면 `기체 목록을 아직 읽지 못했다`, 비었으면 `이 사이트에 기체가 없다` |
| 5 | 200 | 아래 | 호스트에 제출함. 호스트가 무엇을 답했든 200 이다 |

1~4 는 조작 기록에 남지 않는다. 5 만 남는다.

응답 200:

```json
{
  "requestId": "6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a",
  "jobOrderId": "JO-20261008-6f1c2a9e",
  "result": "SUCCEEDED",
  "confirmation": null,
  "outcome": {
    "result": "ACCEPTED",
    "executionId": "exec-1",
    "robotId": "humanoid-01",
    "rejectionReason": null,
    "refusals": [],
    "excluded": [],
    "remedy": null,
    "remedyWithheld": false,
    "alternativeLocations": null,
    "sourceMissing": null,
    "sourceUnobserved": null,
    "forgottenJobOrder": false
  }
}
```

- 작업 지시 id 는 `JO-<UTC yyyyMMdd>-<requestId 앞 8자>`(예 `JO-20261008-6f1c2a9e`). 응답 본문의 `jobOrderId` 칸에 늘 실리고(결과·`NO_RESPONSE` 무관), 실행 목록의 `jobOrderId` 와 조작 기록의 `target` 과 같다. 칸 순서는 `requestId`·`jobOrderId`·`result`·`confirmation`·`outcome` 이다.
- 후보는 판정에서 `eligible` 인 기체만, 판정 행 순서대로다. `outcome.excluded` 는 호스트가 다시 판정해 뺀 기체다(4절).

| 호스트가 한 것 | `result` | `confirmation` | `outcome` |
|---|---|---|---|
| 200 `ACCEPTED`·`IDEMPOTENT` | `SUCCEEDED` | null | 호스트 본문(4절) |
| 200 `REJECTED`·`UNASSIGNED` | `REJECTED` | null | 호스트 본문(4절) |
| 4xx(`{error, detail}`) | `REJECTED` | null | 운영 서비스가 옮긴 모양: `result: "REJECTED"`, `rejectionReason: "실행 호스트가 거부했다(HTTP 400): <error>: <detail>"`, 나머지 null·`[]`·`remedyWithheld` false(`remedy`·`alternativeLocations`·`sourceMissing`·`sourceUnobserved` 은 null). 폼을 먼저 검사하므로 정상 흐름에서는 나오지 않는다. 운영 서비스 `HostSubmitOutcome` 은 새 다섯 칸이 없던 호스트 본문도 읽는다(없으면 null·false) |
| 5xx, 연결 실패, 시간 초과, 200 인데 본문을 못 읽음 | `NO_RESPONSE` | 재조회에서 그 작업 지시 id 의 실행이 있으면 `CONFIRMED_APPLIED`, 없으면 `CONFIRMED_NOT_APPLIED`, 재조회도 못 읽으면 null | null |

- 재조회는 1초 뒤 `GET /host/executions` 한 번이다. 그래서 `NO_RESPONSE` 응답은 1초 남짓 늦게 온다(시간 초과면 5초에 1초를 더한다).
- `confirmation` 이 null 인 `NO_RESPONSE` 는 «반영되었을 수 있으나 확인하지 못했다. 실행 목록에서 확인하라» 다.

조작 기록(`GET /api/operations` 의 행):

| 칸 | 값 |
|---|---|
| `target` | 작업 지시 id |
| `mode`·`user` | `OPERATOR`·헤더의 사용자 |
| `request` | `{"op":"SUBMIT_JOB_ORDER","jobOrder":{2.1},"candidates":[...]}`(JSON 문자열). 출발 자리 확인이 있으면 `sourceConfirmations` 도 실린다(근거: `WorkRequestsTest` 의 `출발 자리 확인은 사람과 시각과 신원을 덧붙이기로 남기고 같은 작업 지시를 확인과 함께 다시 제출한다`). |
| `reason` | null |
| `result` | 위 표의 `result`. 재조회 행은 같은 `requestId` 의 새 행(`CONFIRMED_*`) |
| `targetResponse` | 호스트가 답했으면 `{"status": 200, "body": {...}}`(본문이 JSON 객체가 아니면 `body` 는 글자), 닿지 않았으면 `{"cause": "응답 없음: ConnectException"}`, 재조회 행은 `{"observed": {"instanceId", "executionId", "robotId", "physicalState"}}` 또는 `{"observed": null}`(JSON 문자열) |

**이름이 바뀐 칸**: `GET /api/operations` 행의 `registryResponse` 는 이제 `targetResponse` 다(V3 마이그레이션, DB 칸 `target_response`). 지금 화면(`ui/src/api.ts` 의 `OperationRecord`)은 이 칸을 읽지 않는다.

### 9.4 `GET /api/executions`

모드 헤더 없이 된다. 호스트 `GET /host/executions` 의 200 본문(5절)을 **그대로** 넘긴다(해석·변형 없음). 그래서 `permitBlock`(5.5)도 그대로 넘어간다(운영 서비스는 해석하지 않는다). 작업 요청 상세(`GET /api/work-requests/{id}`)의 `execution` 도 같은 실행 객체다. 근거 시험: `WorkRequestsTest` 의 `작업 허가로 막힌 실행은 작업 요청 상세의 실행에 막은 기체와 주체와 풀릴 근거를 그대로 싣고 작업 요청 상태를 바꾸지 않는다`, 통합 시험 `WorkPermitTest`.

인자가 없으면 `GET /api/executions` 는 이전처럼 실행 호스트의 인자 없는 목록을 그대로 넘긴다. 페이지 인자 `recent`·`before`·`limit`(5.8절)은 운영 서비스가 실행 호스트와 같은 규칙으로 먼저 검사한다(`recent`·`limit` 은 1~200 정수, `before` 는 양의 정수, 빈 값이나 중복 값 없음, `recent` 는 `before`·`limit` 과 섞지 않음, `limit` 은 `before` 와만 함께 씀). 잘못되었으면 실행 호스트에 닿지 않고 400 `EXECUTIONS_BAD_REQUEST` 로 응답한다(사전 거부). 그렇지 않으면 해당 인자로 실행 호스트를 호출해 호스트 본문을 그대로 넘긴다. `limit` 이 없으면 보내지 않는다(호스트 기본값 20). `GET /api/executions/summary` 는 호스트의 `GET /host/executions/summary` 본문을 그대로 넘긴다. 다른 인자(예: `jobOrderId`)는 이전처럼 무시한다. 화면은 5초마다 운영 영역에서 `GET /api/executions?recent=20`, 개요에서 `GET /api/executions/summary` 를 읽고, 운영자가 더 오래된 실행을 요청할 때 `?before=…&limit=20` 을 읽는다. 근거 시험: `JobOrderControllerTest` 의 `화면 실행 페이지와 실행 수는 인자를 검사한 뒤 호스트 본문을 그대로 넘기고 인자 없는 목록과 모르는 인자는 그대로다`, `HostClientTest` 의 `화면 실행 페이지와 실행 수는 인자를 실어 보내고 본문을 그대로 넘기며 200 아님은 모름이다`, 통합 시험 `JobOrderTest` 의 `화면 실행 목록은 남은 실행과 정착 이력이고 이력 페이지와 실행 수가 운영 서비스를 거쳐 전체 목록과 맞는다`. 호스트에 닿을 수 없으면 두 엔드포인트 모두 아래의 503을 준다.

호스트가 안 닿으면(연결 실패, 5초 시간 초과, 200 아님, 객체 아님) **503**:

```json
{"error": "HOST_SILENT", "detail": "실행 호스트가 답하지 않는다: 응답 없음: ConnectException"}
```

### 9.5 `GET /api/cell`

모드 헤더 없이 된다. 호스트 `GET /host/cell` 의 200 본문(6절, `{"cell": {...}}` 또는 `{"cell": null}`)을 그대로 넘긴다. `{"cell": null}` 은 200 이다(호스트는 닿았고 현장 셀 대역을 못 읽음, 화면 «모름»). 호스트가 안 닿으면 9.4 와 같은 503.

### 9.6 상태 코드 요약

| 엔드포인트 | 200 | 400 | 403 | 415 | 503 |
|---|---|---|---|---|---|
| `POST /api/job-orders/eligibility` | 판정(registry·호스트 불통도 200, 칸이 null) | 폼 셋 | 없음 | JSON 아님 | 없음 |
| `POST /api/job-orders` | 제출함(결과는 본문) | `ACTOR_REQUIRED`·폼 셋·`NO_ELIGIBLE_ROBOT` | `MODE_NOT_ALLOWED` | JSON 아님 | 없음 |
| `GET /api/executions` | 호스트 본문 | `EXECUTIONS_BAD_REQUEST`(페이지 인자) | 없음 | 없음 | `HOST_SILENT` |
| `GET /api/executions/summary` | 호스트 본문 | 없음 | 없음 | 없음 | `HOST_SILENT` |
| `GET /api/cell` | 호스트 본문 | 없음 | 없음 | 없음 | `HOST_SILENT` |
| `GET /api/robots/residual-holds` | 호스트 본문 | 없음 | 없음 | 없음 | `HOST_SILENT` |
| `POST /api/executions/{executionId}/cancel` | 보냄(결과는 본문) | `ACTOR_REQUIRED`·`CANCEL_BAD_REQUEST`·`REASON_REQUIRED` | `MODE_NOT_ALLOWED` | JSON 아님 | 없음 |
| `POST /api/robots/{robotId}/residual-holds/{markerId}/clear` | 보냄(결과는 본문) | `ACTOR_REQUIRED`·`CLEAR_BAD_REQUEST`·`REASON_REQUIRED` | `MODE_NOT_ALLOWED` | JSON 아님 | 없음 |
| `POST /api/executions/{executionId}/release` | 보냄(결과는 본문, 9.8) | `ACTOR_REQUIRED`·`RELEASE_BAD_REQUEST`·`REASON_REQUIRED` | `MODE_NOT_ALLOWED` | JSON 아님 | 없음 |

### 9.7 운영자 실행 취소와 기체 빈손 확인(P0-b2)

- `GET /api/robots/residual-holds`: 모드 헤더 없이 됨. 호스트 5.3 본문 그대로. 호스트가 안 닿으면 503 `HOST_SILENT`(«실행 호스트가 답하지 않는다: <cause>»).
- 두 POST 공통: 운영자 모드만. 판정 순서 1) 400 `ACTOR_REQUIRED` 2) 403 `MODE_NOT_ALLOWED` 3) 본문(취소: 본문이 객체가 아니거나 `instanceId` 가 비어 있지 않은 문자열이 아니면 400 `CANCEL_BAD_REQUEST` «instanceId 는 비어 있지 않은 문자열이다(실행 목록의 instanceId)»; 해소: `markerId` 가 양의 정수가 아니면 400 `CLEAR_BAD_REQUEST` «markerId 는 양의 정수다: <값>») 4) 사유(앞뒤 공백 뗀 뒤 비면 400 `REASON_REQUIRED`, «취소 사유가 없다»·«빈손 확인 사유가 없다»). 1~4 는 조작 기록에 남지 않음. 그 뒤는 호스트가 무엇을 답했든 200. 승인자는 본문에 없고 `X-Ops-User`. 요청 id 는 운영 서비스가 만들어 호스트에 넘김.
- 취소 본문 `{instanceId, reason}`, 200 칸 7개 이 순서 `requestId`·`executionId`·`result`·`confirmation`·`outcome`(호스트 `result` 이름, 호스트가 200 으로 답했을 때만)·`answer`(호스트 200 본문)·`rejection`(호스트 4xx 일 때 `{status, error, detail}`).
- 빈손 확인 본문 `{reason, cited?}`, 200 칸 7개 이 순서 `requestId`·`robotId`·`markerId`·`result`·`confirmation`·`answer`·`rejection`. `cited`(B2)는 호스트 409 `RESIDUAL_HOLD_OBSERVATION_MISMATCH` 의 충돌을 다시 확인할 때 지목하는 것이고 요청 본문에서만 옴(운영 서비스가 호스트 응답이나 목록으로 채우지 않음). 모양(5.4)이 틀리면 3)의 본문 단계에서 400 `CLEAR_BAD_REQUEST`. 다섯 칸만 옮겨 호스트에 보냄. `rejection` 은 `{status, error, detail}` 에 더해 호스트 4xx 본문에 `error`·`detail` 밖의 칸이 있으면 `fields`(그 칸들, 어긋남이면 `robotId`·`markerId`·`conflictRequestId`·`observation`)를 실음. 없으면 `fields` 칸이 없음.
- `result`: 취소는 `CANCELED` → `SUCCEEDED`, `NOT_CANCELABLE`·`NOT_FOUND`·`REFUSED` 와 호스트 4xx → `REJECTED`. 빈손 확인은 200 본문에 객체 `clearing` 이 있으면 `SUCCEEDED`, 400·404·409(어긋남 포함) → `REJECTED`. 둘 다 연결 실패·시간 초과·5xx·200 인데 본문을 못 읽음 → `NO_RESPONSE` 와 1초 뒤 재조회 한 번: 취소는 `GET /host/executions` 의 인스턴스가 요청 인스턴스와 같고 그 실행의 `cancel.approverId` 가 행위자이며 `recordedAt` ≥ 보내기 직전 운영 서비스 시각이면 `CONFIRMED_APPLIED`, 실행이 없거나 그런 `cancel` 이 없으면 `CONFIRMED_NOT_APPLIED`, 인스턴스가 다르거나 못 읽으면 null(확인 행 없음). 빈손 확인은 읽기만 함(다시 보내지 않음): `GET /host/robots/{robotId}/residual-holds/{markerId}`(5.4a)의 `clearing.requestId` 가 이 요청 id 면 `CONFIRMED_APPLIED`, 해소 행이 없거나(표시 없음 404 포함) 다른 요청의 해소면 `CONFIRMED_NOT_APPLIED`, 못 읽으면 null.
- 조작 기록: 취소 `target` = 실행 id, `request` = `{"op":"CANCEL_EXECUTION","executionId","approverId","instanceId"}`; 빈손 확인 `target` = `<robotId>/<markerId>`, `request` = `{"op":"CONFIRM_ROBOT_EMPTY","robotId","markerId"(수),"approverId","cited"(있을 때만)}`; `reason` = 입력 사유; `targetResponse` 와 재조회 행 규칙은 9.3 과 같음(재조회 행 `observed` 는 취소면 그 실행, 빈손 확인이면 5.4a 본문 또는 null).

```json
{
  "requestId": "6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a",
  "executionId": "exec-1",
  "result": "SUCCEEDED",
  "confirmation": null,
  "outcome": "CANCELED",
  "answer": {"result": "CANCELED", "executionId": "exec-1", "physicalState": "PARTIAL", "detail": null, "requestId": "6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a"},
  "rejection": null
}
```

```json
{
  "requestId": "0b7d4c55-9a51-4f3e-8d5c-6b7a6f1c2a9e",
  "robotId": "humanoid-01",
  "markerId": 1,
  "result": "REJECTED",
  "confirmation": null,
  "answer": null,
  "rejection": {"status": 409, "error": "RESIDUAL_HOLD_ALREADY_CLEARED", "detail": "잔여 파지 표시 1 는 이미 해소했다(park, 2026-10-14T01:10:00.000001Z)"}
}
```

(근거 시험: `ExecutionControlsTest` 여섯.)

### 9.8 운영자 결함 수용

- `POST /api/executions/{executionId}/release`: 운영자 모드만. 본문 `{instanceId, reason, sawFaults}`. 판정 순서 1) 400 `ACTOR_REQUIRED` 2) 403 `MODE_NOT_ALLOWED` 3) 본문(본문이 객체가 아니거나 `instanceId` 가 비어 있지 않은 문자열이 아니면 400 `RELEASE_BAD_REQUEST` «instanceId 는 비어 있지 않은 문자열이다(실행 목록의 instanceId)», `sawFaults` 가 비어 있지 않은 배열이 아니거나 원소의 `errorType` 이 비어 있지 않은 문자열이 아니거나 `references` 가 배열이 아니거나 참조의 `key` 가 비어 있지 않은 문자열·`value` 가 문자열이 아니면 400 `RELEASE_BAD_REQUEST` «sawFaults 는 비어 있지 않은 배열이고 원소마다 errorType 은 비어 있지 않은 문자열, references 는 {key, value} 객체의 배열이다») 4) 사유(앞뒤 공백을 뗀 뒤 비면 400 `REASON_REQUIRED` «결함 수용 사유가 없다»). 1~4 는 조작 기록에 남지 않음. 그 뒤는 실행 호스트가 무엇을 답했든 200. 승인자는 본문에 없고 `X-Ops-User`. 요청 id 는 운영 서비스가 만들어 실행 호스트에 넘기고, 실행 호스트는 그 id 를 수용 행에 적음.
- 실행 호스트(5.10)에는 `errorType` 과 `references` 의 `key`·`value` 만 옮겨 보냄. 결함 객체의 다른 칸(`failureClass` 등)은 버림.
- 200 칸 7개 이 순서 `requestId`·`executionId`·`result`·`confirmation`·`outcome`(실행 호스트 `result` 이름, 실행 호스트가 200 으로 답했을 때만)·`answer`(실행 호스트 200 본문)·`rejection`(실행 호스트 4xx 일 때 `{status, error, detail}`). 취소와 같은 칸. `result`: `RELEASED` → `SUCCEEDED`. `NOT_BLOCKED`·`BLOCK_CHANGED`·`NOT_FOUND`·`REFUSED` 와 실행 호스트 4xx(409 `INSTANCE_MISMATCH`, 409 `REQUEST_ID_REUSED`, 400 `BAD_REQUEST`) → `REJECTED`. 연결 실패·시간 초과·5xx·200 인데 본문을 못 읽음 → `NO_RESPONSE` 와 1초 뒤 재조회 한 번.
- 재조회: `GET /host/executions?executionId=<그 실행>`. 목록의 인스턴스가 요청한 인스턴스와 같고 그 실행의 `releases` 에 이 요청 id 가 있으면 `CONFIRMED_APPLIED`, 실행이 없거나 그 요청 id 가 없으면 `CONFIRMED_NOT_APPLIED`, 인스턴스가 다르거나(같은 실행 id 가 다른 실행일 수 있음) 못 읽으면 null(확인 행 없음). 취소의 재조회와 달리 시각을 견주지 않음(요청 id 로 찾음).
- 조작 기록: `target` = 실행 id, `request` = `{"op":"RELEASE_FAULT_BLOCK","executionId","approverId","instanceId","sawFaults"}`, `reason` = 입력 사유. 재조회 행의 `observed` 는 그 실행 또는 null.
- 재작업 게이트(스테이션 조정)는 거치지 않음. 수용은 끝난 단위를 다시 돌리지 않고 계획의 다음 단위로 감.

```json
{"instanceId": "mw-3f0c...", "reason": "현장에서 기체 상태를 확인했다", "sawFaults": [{"errorType": "LOCALIZATION_LOST", "references": []}]}
```

```json
{
  "requestId": "6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a",
  "executionId": "exec-1",
  "result": "SUCCEEDED",
  "confirmation": null,
  "outcome": "RELEASED",
  "answer": {"result": "RELEASED", "executionId": "exec-1", "physicalState": "RUNNING", "detail": null, "requestId": "6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a"},
  "rejection": null
}
```

(근거 시험: `ExecutionControlsTest` 의 `결함 감수는 운영자 모드만 하고 본문이 틀리면 사유보다 먼저 400 RELEASE_BAD_REQUEST 이며 사유가 없으면 400 REASON_REQUIRED 다`·`결함 감수는 승인자가 행위자이고 본 결함의 종류와 참조만 옮겨 보내며 RELEASED 는 성공 그 밖의 결과와 호스트 4xx 는 거부로 남는다`·`결함 감수 응답이 없으면 그 실행의 releases 에 이 요청 id 가 있는지로 대조하고 인스턴스가 바뀌었거나 못 읽으면 확인하지 못한다`, `HostClientTest` 의 `결함 감수는 승인자와 사유와 인스턴스와 본 결함과 요청 id 를 실어 보내고 상태 코드와 본문을 그대로 돌려준다`.)

### 9.9 상위 시스템 대체 접수와 작업 지시 철회(B3)

외부 주문 `POST /integration/v1/job-orders` 의 `replacesExternalOrderId` 로 실행 없이 끝난 앞선 작업 요청(`REJECTED`·`UNASSIGNED`)을 대체할 때 운영 서비스는 앞선 작업 요청 행 잠금을 쥔 채, 기존 조건과 대체 연결 검사 뒤에 실행 호스트의 작업 지시 철회(5.11)를 부른다. 같은 새 주문의 재전송과 운영자 종결로 닫힌 앞선 작업 요청은 부르지 않는다. 출발 자리 확인이 `submission` 을 비우므로 제출 기록 유무와 무관하게 늘 부른다.

| 실행 호스트 답 | 운영 서비스 답 | 적는 것 |
|---|---|---|
| `WITHDRAWN` | 202(새 작업 요청) | 새 작업 요청과 연결(`withdrawal_request_id` = 철회 행의 요청 id) |
| `EXECUTION_EXISTS` | 409 `ORIGINAL_NOT_REPLACEABLE`(`detail` 에 실행 id·기체) | 앞선 작업 요청을 `ACCEPTED` 로 올림(`last_execution` 에 그 실행, 작업 응답·결과 버전 없음). 커밋한 뒤 409 |
| 응답 없음·5xx·잘못된 본문 | 503 `WITHDRAWAL_UNCONFIRMED` | 없음(철회는 반영됐을 수 있다. 같은 요청 id 의 재전송이 멱등 호출로 이어 감) |

```json
{"error": "ORIGINAL_NOT_REPLACEABLE", "detail": "실행 호스트가 앞선 작업 요청의 작업 지시를 이미 실행했습니다(실행 exec-1, 기체 humanoid-01). 대체하지 않고 앞선 작업 요청을 수락으로 바꿨습니다"}
{"error": "WITHDRAWAL_UNCONFIRMED", "detail": "실행 호스트가 앞선 작업 요청의 작업 지시 철회를 확정하지 않아 대체를 접수하지 않았습니다(응답 없음: HttpTimeoutException). 같은 요청을 다시 보내십시오"}
```

스테이션의 현장 확인 해제(`CONFIRM_CLEAR`)와 제출 거부 자동 해제도 같은 철회를 스테이션 잠금 밖에서 부르고 `WITHDRAWN` 만 미전송으로 본다(요청 id 는 작업 요청에서 지은 고정값). 현장 확인 해제는 본문 검증과 선점(`CLEARING`) 뒤에만 부르고 `requestedBy` 는 해제한 운영자, `reason` 은 «공유 스테이션 현장 확인 해제: <그 사람의 사유>» 다. 자동 해제의 `requestedBy` 는 `system/station` 이다. (근거 시험: `WorkRequestsTest` 의 `실행 호스트가 앞선 작업 지시의 실행을 알리면 대체를 409 로 거절하고 앞선 작업 요청을 수락으로 올려 커밋한다`, `철회에 답이 없거나 일지가 막혔으면 대체를 503 으로 접수하지 않고 아무것도 적지 않으며 복구 뒤 같은 요청은 통과한다`, `현장 확인 해제는 실행 호스트가 철회를 확정할 때만 미전송으로 닫고 실행이 있거나 답이 없으면 스테이션을 격리한다`, `HostClientTest` 의 `작업 지시 철회는 본문을 실어 보내고 이 호출 전용의 짧은 요청 제한으로 끊는다`.)
