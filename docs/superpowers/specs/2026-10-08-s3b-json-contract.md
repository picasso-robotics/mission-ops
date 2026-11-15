# S3b JSON 계약: 현장 셀 대역 신호와 실행 호스트 임무 버전 REST

S3b 설계 스펙(`docs/superpowers/specs/2026-10-08-s3b-mission-versions-design.md`)을 구현한 site·mission-host·운영 서비스가 실제로 내고 받는 모양이다(§1~§8 은 현장과 실행 호스트, §9·§10 은 운영 서비스). 화면과 통합 시험은 이것에 맞춘다. 코드 주석의 «S3b JSON 계약 §N» 은 이 문서의 절을 가리킨다.

**S3a JSON 계약(`docs/superpowers/specs/2026-10-08-s3a-json-contract.md`)에서 이 문서가 대체하는 곳**

| S3a 절 | 대체하는 이 문서의 절 | 바뀐 것 |
|---|---|---|
| 공통 규칙의 «실행 호스트 기동(같은 JVM)» 줄(JDBC·Flyway 자동설정을 끄므로 한 JVM 에 떠도 된다) | 공통 규칙 | Flyway 자동설정만 끈다. 호스트는 이제 DataSource 가 있어야 뜬다 |
| §1 현장 `GET /cell` | §1 | `signals` 칸이 더해졌다. `/cell/signals/{name}` 이 404 가 아니다 |
| §6 `GET /host/cell` | §6 | 스냅숏에 `signals` 칸 |
| §7 셀 신호(호스트 안) | §7 | `signal(이름)` 이 스냅숏의 신호를 낸다 |
| §8 상태 코드 요약 | §8 | 임무 REST·신호 조작 행 |
| §9.5 `GET /api/cell` | §9.5 | 넘기는 본문에 `signals` 칸 |
| §9.6 운영 서비스 상태 코드 요약 | §9.6·§10.10 | 기존 행은 그대로, 임무·신호 엔드포인트 행을 §10.10 에 더했다 |

S3a 의 §2~§5(작업 지시 REST)와 §9.1~§9.4 는 그대로다.

공통 규칙

- 모든 본문은 UTF-8 JSON 이다. 시각은 ISO-8601 UTC 문자열(`Instant.toString()`)이고 소수 초 자릿수는 고정이 아니다(`Instant.parse` 로 읽을 것). `null` 칸은 생략하지 않고 `null` 로 나온다. 열거 값은 picasso enum 이름 그대로다.
- **신호 값은 종류와 상관없이 늘 문자열이다**(`"true"`, `"false"`, `"LOT-0001"`). JSON 참거짓이 아니다. picasso `NamedSignal.value` 가 문자열이다.
- 실행 호스트 설정 키(S3a 의 셋에 더해):

| 키 | 기본값(`mission-host.properties`) | 뜻 |
|---|---|---|
| `spring.datasource.url`·`.username`·`.password` | `${PICASSO_DB_URL}`·`${PICASSO_DB_USER}`·`${PICASSO_DB_PASSWORD}` | 임무 버전 저장. 같은 Postgres 의 스키마 `mission` |
| `host.mock-run.profile` | `mission-host/mock-run/humanoid-a.json` | 모의 실행 기체 프로파일. 작업 디렉터리 기준 |
| `host.mock-run.schema` | `picasso/profile/schema/capability-profile.schema.json` | 프로파일 스키마. 작업 디렉터리 기준 |
| `host.mock-run.virtual-limit` | `PT10M` | 모의 실행 가상 시간 상한(ISO-8601 기간). 실제 시간 상한 30초는 코드 상수다 |

- 두 경로 파일이 없으면 호스트 기동이 멈춘다. `:mission-host:run` 은 저장소 루트에서 돈다(`workingDir = rootDir`). 시험은 작업 디렉터리가 모듈 폴더이므로 절대 경로를 넘긴다.
- 실행 호스트 기동(같은 JVM): `MissionHostApplication.builder(HostClock { site.now() }).run(args...)`. 통합 시험의 인자는 다음과 같다.

```
--server.port=0
--host.mimic.port=<site.mimicPort>
--host.cell.url=http://127.0.0.1:<site.cellPort>
--spring.datasource.url=<PostgresSupport.jdbcUrl>
--spring.datasource.username=<PostgresSupport.username>
--spring.datasource.password=<PostgresSupport.password>
--host.mock-run.profile=<루트>/mission-host/mock-run/humanoid-a.json
--host.mock-run.schema=<루트>/picasso/profile/schema/capability-profile.schema.json
```

- 호스트는 Flyway 자동설정만 이름으로 끈다(`FlywayAutoConfiguration`). 마이그레이션은 `HostSchema`(위치 `classpath:db/mission`, 스키마·이력 테이블 `mission`)가 기동 때 올린다. registry·운영 서비스와 한 JVM 에 떠도 위치가 섞이지 않는다.
- 표 셋은 덧붙이기 전용이다(UPDATE·DELETE·TRUNCATE 를 트리거가 막는다). 시험은 띄울 때마다 `DROP SCHEMA IF EXISTS mission CASCADE` 로 지운다(`PostgresSupport.reset()` 은 public 만 지운다). 호스트만 다시 띄우는 시험은 DB 를 지우지 않는다.
- 호스트 기동 때 `mission.mission_version` 의 WorkMaster 마다 가장 큰 버전을 파싱해 카탈로그를 세운다. 다시 검증하지 않는다. 다만 출발 관측 선언(picasso #95) 전에 활성화되어 저장된 버전은, 신원을 대는 출발 자리에 선언이 없는 단위 노드를 OPTIONAL 로 채워 세우고 경고 로그를 남긴다. 고칠 노드가 없으면 같은 정의다. 검증·모의 실행·활성화는 이 길을 지나지 않으므로 새 정의는 선언 없이 활성화되지 않는다. 근거: `SourceObservationHostTest` 의 `출발 관측 선언 전에 활성화한 저장 버전은 OPTIONAL 로 되살아나고 새 정의는 선언 없이 활성화되지 않는다`, `선언을 채우는 것은 신원을 대는 출발 자리에 선언이 없는 노드뿐이고 고칠 것이 없으면 같은 정의다`. 같은 길로 목적지 관측 선언(picasso #115) 전에 활성화되어 저장된 버전은 식별 정보를 대는 목적지에 선언이 없는 단위 노드를 OPTIONAL 로 채워 세우고 경고 로그를 남겨 호스트 기동이 멈추지 않으며, 검증·모의 실행·활성화는 이 길을 지나지 않아 새 정의는 선언 없이 활성화되지 않는다. 근거: `DestinationObservationHostTest` 의 `목적지 관측 선언 전에 활성화한 저장 버전은 OPTIONAL 로 되살아나 기동이 멈추지 않고 새 정의는 선언 없이 활성화되지 않는다`, `목적지 선언을 채우는 것은 식별 정보를 대는 목적지에 선언이 없는 노드뿐이고 두 선언이 다 없으면 둘 다 채운다`. 파싱하지 못하면 기동이 멈추고 예외 메시지에 `저장된 임무 버전 <WorkMaster> 버전 <N> 을 읽지 못했다: <문제>` 가 남는다.

---

## 1. 현장 셀 대역 `GET http://127.0.0.1:${SITE_CELL_PORT}/cell`

| 경우 | 상태 | 본문 |
|---|---|---|
| `GET /cell` | 200, `Content-Type: application/json` | 아래 |
| `/cell` 에 GET 아닌 방법 | 405, `Allow: GET` | 없음 |
| `/cell/signals/{name}` | 2절 | 2절 |
| 다른 경로(`/cells`, `/cell/x`, `/cell/signals/`, `/cell/signals/a/b` 등) | 404 | 없음 |

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
  ],
  "signals": [
    {"name": "rack_present", "location": "RACK-204", "kind": "BOOLEAN", "safety": false, "value": "false",    "observedAt": null},
    {"name": "guard_closed", "location": null,       "kind": "BOOLEAN", "safety": true,  "value": "true",     "observedAt": null},
    {"name": "lot_code",     "location": null,       "kind": "TEXT",    "safety": false, "value": "LOT-0001", "observedAt": null}
  ]
}
```

- 칸 순서는 `presentations`·`slots`·`signals` 이다. 자리 원소는 S3a 그대로 칸 넷이다.
- 신호 원소는 칸 여섯이다: `name`(문자열), `location`(문자열 또는 null), `kind`(`BOOLEAN`·`TEXT`), `safety`(불리언), `value`(문자열), `observedAt`(시각 문자열 또는 null).
- `name`·`location`·`kind`·`safety` 는 신호 사양이다. 픽스처(`CellFixture.STANDARD`)의 선언 그대로이며 바뀌지 않는다. 실행 호스트가 이것을 검증의 신호 사양으로 쓴다.
- 고정 픽스처의 신호 셋과 처음 값(이 순서):

| 이름 | 종류 | 자리 | 안전 | 처음 값 |
|---|---|---|---|---|
| `rack_present` | BOOLEAN | `RACK-204` | 아님 | `"false"` |
| `guard_closed` | BOOLEAN | null | 안전 | `"true"` |
| `lot_code` | TEXT | null | 아님 | `"LOT-0001"` |

- `observedAt` 은 처음에 null(현장이 시각을 주지 않은 처음 값, 읽은 순간이 그 시각)이고, 2절로 쓴 뒤에는 쓴 순간의 현장 가상 시각이다. 값이 같아도 쓰면 시각이 바뀐다.
- 슬롯이 채워지는 규칙은 S3a §1 그대로다. 신호는 시계를 밀어도 바뀌지 않고 2절로만 바뀐다. 재기동하면 처음 값으로 돌아간다.

## 2. 현장 신호 조작 `POST http://127.0.0.1:${SITE_CELL_PORT}/cell/signals/{name}`

사람이 PLC 역할을 하는 정상 조작이다(결정 3). 현장이 mimic 엔진 잠금(`MimicServer.exclusive`) 아래에서 처리한다.

요청: `Content-Type: application/json`, 본문

```json
{"value": "true"}
```

판정 순서와 응답:

| 순서 | 상태 | `error` | 언제 |
|---|---|---|---|
| 1 | 405, `Allow: POST`, 본문 없음 | 없음 | POST 가 아님 |
| 2 | 415 | `UNSUPPORTED_MEDIA_TYPE` | `Content-Type` 이 `application/json` 이 아님(매개변수 `;charset=...` 는 된다) |
| 3 | 400 | `BAD_REQUEST` | 본문이 JSON 객체가 아님, `value` 가 없거나 문자열이 아님(`{"value": true}`·`{"value": null}` 포함) |
| 4 | 404 | `UNKNOWN_SIGNAL` | 셀 대역에 없는 신호 이름 |
| 5 | 403 | `SAFETY_SIGNAL_READ_ONLY` | 안전 신호(`safety: true`). 값이 맞아도 거부한다(ADR 32: 안전 PLC 는 소프트웨어에서 쓸 수 없다) |
| 6 | 400 | `SIGNAL_VALUE_INVALID` | BOOLEAN 신호에 `"true"`·`"false"` 가 아닌 값(`"TRUE"`, `"1"`, `""` 포함). TEXT 는 빈 문자열을 포함해 어떤 문자열이든 받는다 |
| 7 | 200 | 없음 | 바꿈 |

거부 본문은 늘 `{"error": "...", "detail": "..."}` 이다(`Content-Type: application/json`). 거부하면 값과 관측 시각이 그대로다.

응답 200(바뀐 신호 하나, 1절의 신호 원소 모양):

```json
{"name": "rack_present", "location": "RACK-204", "kind": "BOOLEAN", "safety": false, "value": "true", "observedAt": "2026-10-08T00:02:00Z"}
```

---

## 3. 실행 호스트 임무 REST 공통

- 기준 주소 `http://127.0.0.1:${HOST_PORT}`. 인증 없음. 호출자는 운영 서비스뿐이다. **모드 검사(엔지니어 모드만)·사유 필수 검사의 운영자 쪽 의미·조작 기록은 운영 서비스가 한다.** 호스트는 `actor`·`reason` 이 비어 있지 않은지만 본다.
- POST 는 `application/json` 만 받는다. 그 밖은 **415**(스프링 기본 오류 본문, 모양 보장 없음).
- 결과(통과·거부·모름·실패·활성화)는 **늘 200 의 본문** `result` 칸에 있다. 상태 코드로 결과를 가르지 말 것. 상태 코드가 가르는 것은 아래뿐이다.

| 상태 | `error` | 언제 |
|---|---|---|
| 400 | `UNKNOWN_WORK_MASTER` | 경로의 WorkMaster 가 `PrepareSequencedRack` 이 아님(`InspectAsset`·`DeliverContainer` 포함, T6) |
| 400 | `BAD_REQUEST` | 본문이 JSON 객체가 아님, 칸 누락·형 틀림·빈 문자열, `requestId` 가 정규형 UUID 가 아님, 경로의 초안 id 가 양의 정수가 아님, 기체 목록 누락·빈 문자열 원소 |
| 404 | `DRAFT_NOT_FOUND` | 경로의 초안이 없음 |
| 404 | `REQUEST_NOT_FOUND` | 재조회(4.7)에서 그 요청 id 로 남은 행이 없음 |
| 409 | `REQUEST_ID_REUSED` | 그 요청 id 가 이미 초안·모의 실행·버전 중 어느 표에든 있거나, 같은 요청 id 의 조작을 지금 처리 중임. 아무것도 남지 않는다 |
| 409 | `REQUEST_IN_PROGRESS` | 재조회(4.7)에서 그 요청 id 의 조작(초안 저장·모의 실행·활성화)을 아직 처리 중임. 남은 행이 없다는 답이 아니다 |
| 503 | `CELL_SILENT` | 신호 조작 전달(5절)에서 현장 셀 대역이 안 닿음 |

오류 본문은 늘 `{"error": "...", "detail": "..."}`(S3a 의 `HostRejection`)이다. 판정 순서: 경로(WorkMaster·초안 id) → 본문 → 초안 있음 → 요청 id 쓰임.

- `requestId` 는 운영 서비스가 만든 조작의 요청 id(정규형 소문자 UUID 문자열)다. 호스트가 초안·모의 실행·버전 행에 남긴다(T9). 요청 id 하나는 조작 하나다.
- `robotIds` 는 운영 서비스가 넘긴 시운전 완료 기체(현장 스킬 계산용)다. 필수, 비어 있지 않은 문자열 배열, 빈 배열은 된다(그때 현장 스킬은 빈 집합이고 `pick_place` 를 쓰는 정의는 `SKILL_NOT_ON_SITE`). 겹친 id 는 한 번만 센다.

### 3.1 거부 하나 `RefusalView`

```json
{
  "kind": "SIGNAL_NOT_IN_SPEC",
  "nodeId": "rack-arrival",
  "observed": "rack_ready",
  "expected": "신호 사양의 이름 중 하나(guard_closed, lot_code, rack_present)",
  "checkedAt": "2026-10-08T00:00:01Z",
  "basisVersion": null,
  "owner": "ENGINEER",
  "nextAction": "신호 이름을 고치거나 신호 사양에 더한다"
}
```

| 칸 | 값 |
|---|---|
| `kind` | picasso `MissionRefusalKind` 17종: `UNREADABLE`·`DUPLICATE_NODE_ID`·`DEADLINE_INVALID`·`SIGNAL_NOT_IN_SPEC`·`SIGNAL_VALUE_INVALID`·`FLOOR_UNOWNED`·`SKILL_NOT_IN_CONTRACT`·`SKILL_NOT_ON_SITE`·`SAFETY_SIGNAL_WAIT`·`SOURCE_OBSERVATION_UNDECLARED`·`SOURCE_OBSERVATION_UNUSED`·`DESTINATION_OBSERVATION_UNDECLARED`·`DESTINATION_OBSERVATION_UNUSED`·`AFTER_INVALID`·`UNIT_ID_SHAPE_COLLISION`·`FLEET_UNIT_INVALID`·`WHEN_MISSING_INVALID`(뒤 넷은 picasso `2edb29e`, §15.325). 호스트에서 `FLOOR_UNOWNED` 는 나지 않는다(바닥 소유 `FloorOwnership.None`). 근거: `SourceObservationHostTest` 의 `출발 관측 선언 전에 활성화한 저장 버전은 OPTIONAL 로 되살아나고 새 정의는 선언 없이 활성화되지 않는다`. |
| `nodeId` | 막힌 노드 id. 문서 전체의 문제(`UNREADABLE`)는 null |
| `observed` | 지금 값. `UNREADABLE` 이면 JSON 경로로 시작하는 문제 문자열(`$.steps[0].skill: 빠졌다`, `$.maxEvidence: 문자열이어야 한다(수 2.0)`). 문법 오류는 첫 자리에서 멈춘다 |
| `expected` | 통과에 필요한 값. 파서 문제의 `UNREADABLE` 은 `임무 정의 문서 버전 1 의 모양` |
| `checkedAt` | 판정 시각 = 본문의 `checkedAt`(호스트 시계. 통합 시험에서는 현장 가상 시각) |
| `basisVersion` | 정수 또는 null. S3b 에서는 늘 null(현장 설정 버전은 S3c). 운영 서비스 `Finding.basisVersion`(`Long?`)으로 옮길 때 그대로 |
| `owner` | `ENGINEER`(화면 안의 엔지니어) 또는 `OUTSIDE_CONSOLE`(화면 밖, `FLOOR_UNOWNED` 만) |
| `nextAction` | 바로 갈 작업(한국어, 종류가 정함) |

종류별 `owner`·`nextAction`(picasso 가 정하며 호스트는 그대로 낸다):

| `kind` | `owner` | `nextAction` |
|---|---|---|
| `UNREADABLE` | ENGINEER | 정의 JSON 의 틀린 칸을 고친다 |
| `DUPLICATE_NODE_ID` | ENGINEER | 노드 id 를 겹치지 않게 고친다 |
| `DEADLINE_INVALID` | ENGINEER | 대기 노드의 기한 칸을 고친다 |
| `SIGNAL_NOT_IN_SPEC` | ENGINEER | 신호 이름을 고치거나 신호 사양에 더한다 |
| `SIGNAL_VALUE_INVALID` | ENGINEER | 기대 값을 신호 종류에 맞게 고친다 |
| `FLOOR_UNOWNED` | OUTSIDE_CONSOLE | 그 자리의 바닥 소유를 선언한다 |
| `SKILL_NOT_IN_CONTRACT` | ENGINEER | 스킬 이름을 계약 카탈로그의 이름으로 고친다 |
| `SKILL_NOT_ON_SITE` | ENGINEER | 그 스킬을 제공하는 기체를 현장에 둔다 |
| `SAFETY_SIGNAL_WAIT` | ENGINEER | 대기 노드를 안전 신호가 아닌 신호로 바꾼다 |
| `SOURCE_OBSERVATION_UNDECLARED` | ENGINEER | 단위 노드에 출발 관측을 REQUIRED 나 OPTIONAL 로 적는다 |
| `SOURCE_OBSERVATION_UNUSED` | ENGINEER | 출발 관측 선언을 지우거나 출발 자리와 기대 식별 정보를 함께 적는다 |
| `DESTINATION_OBSERVATION_UNDECLARED` | ENGINEER | 단위 노드에 목적지 관측을 REQUIRED 나 OPTIONAL 로 적는다 |
| `DESTINATION_OBSERVATION_UNUSED` | ENGINEER | 목적지 관측 선언을 지우거나 목적지와 기대 식별 정보를 함께 적는다 |
| `AFTER_INVALID` | ENGINEER | after 를 같은 그룹의 앞 단위 노드 id 로 고치거나 지운다 |
| `UNIT_ID_SHAPE_COLLISION` | ENGINEER | 한쪽 단위 id 에 다른 접미사를 붙인다 |
| `FLEET_UNIT_INVALID` | ENGINEER | 플릿 노드를 transport 와 출발·목적지·기대 식별 정보로 적고 관측 선언을 지운다 |
| `WHEN_MISSING_INVALID` | ENGINEER | whenMissing 의 unit 과 parameters 를 그 그룹의 노드와 파라미터 이름으로 고친다 |

**정의의 WorkMaster 가 경로와 다름**(T6): `kind: UNREADABLE`, `nodeId: null`, `observed: "$.workMasterId: 초안의 WorkMaster 와 다르다(InspectAsset)"`, `expected: "PrepareSequencedRack"`.

판정 순서: 파싱(읽을 수 없으면 `REFUSED` 와 문제 전부) → WorkMaster 대조(다르면 `REFUSED` 그 하나) → 입력(모르면 `INPUT_UNKNOWN`) → 검증기(거부 전부 모아 `REFUSED`). 문서 수준의 문제는 현장 입력을 몰라도 `REFUSED` 다.

### 3.2 판정 입력 `InputsView` 와 모름 `UnknownView`

```json
"inputs": {
  "signals": [
    {"name": "rack_present", "location": "RACK-204", "kind": "BOOLEAN", "safety": false},
    {"name": "guard_closed", "location": null, "kind": "BOOLEAN", "safety": true},
    {"name": "lot_code", "location": null, "kind": "TEXT", "safety": false}
  ],
  "siteSkills": ["inspect", "navigate_to", "pick_place"],
  "robotIds": ["humanoid-01", "quadruped-01"],
  "unknownRobots": []
}
```

| 칸 | 값 |
|---|---|
| `signals` | 신호 사양 = 마지막 pump 가 읽은 셀 대역 스냅숏의 `signals`(사양 칸 넷). 스냅숏이 없거나 현장 본문에 `signals` 칸이 없으면 null(모름). 빈 배열은 «신호가 없다» 는 사양이다 |
| `siteSkills` | 현장 스킬 = `robotIds` 기체들이 선언한 스킬의 합(이름순). 한 기체라도 케이퍼빌리티를 못 물어보면 null(모름) |
| `robotIds` | 요청의 기체(겹친 id 는 한 번) |
| `unknownRobots` | 케이퍼빌리티를 못 물어본 기체(mimic 이 모르는 기체, mimic 불통) |

`result` 가 `INPUT_UNKNOWN` 일 때만 `unknown` 이 있다(그 밖에는 null):

```json
"unknown": {"inputs": ["SITE_SKILLS"], "robots": ["ghost-01"], "detail": "기체 케이퍼빌리티를 못 물어봐 현장 스킬을 못 읽었다(ghost-01)"}
```

| `unknown.inputs` 원소 | 뜻(T7) |
|---|---|
| `SIGNAL_SPEC` | 셀 대역 스냅숏이 없어(또는 신호 목록이 없어) 신호 사양을 못 읽었다 |
| `SITE_SKILLS` | 기체 케이퍼빌리티를 못 물어봐 현장 스킬을 못 읽었다 |
| `SAMPLE_ORDER` | 모의 실행만: 스냅숏에 슬롯이나 자재를 든 제시 자리가 없어 표본 작업 지시를 못 만든다 |

`detail` 은 원소마다 한 문장을 `; ` 로 이은 화면용 한국어다. 모름은 거부가 아니다. 화면은 거부 카드가 아니라 «모름» 으로 보인다.

### 3.3 행 모양

초안 `DraftView`:

```json
{
  "draftId": 3,
  "workMasterId": "PrepareSequencedRack",
  "definition": "{\n\"schemaVersion\": 1,\n ...}",
  "savedBy": "lee",
  "requestId": "6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a",
  "savedAt": "2026-10-08T05:12:01.123456Z",
  "lastMockRun": null
}
```

- `definition` 은 받은 글자 그대로(읽을 수 없는 문서여도)다. `lastMockRun` 은 그 초안의 마지막 모의 실행(`MockRunView`) 또는 null.
- `savedAt`·`startedAt`·`finishedAt`·`activatedAt` 은 DB 시각(`clock_timestamp()`, 실제 시각)이다. 호스트 시계(가상)가 아니다. `checkedAt` 만 호스트 시계다.

모의 실행 `MockRunView`:

```json
{"mockRunId": 5, "draftId": 3, "passed": true, "result": { ...4.5 결과... }, "requestId": "...", "startedAt": "...", "finishedAt": "..."}
```

버전 `VersionView`:

```json
{"workMasterId": "PrepareSequencedRack", "version": 2, "draftId": 4, "definition": "...", "activatedBy": "lee", "reason": "랙 도착 대기 도입", "requestId": "...", "activatedAt": "..."}
```

## 4. 임무 엔드포인트

### 4.1 `GET /host/missions/{workMasterId}`

응답 200:

```json
{
  "workMasterId": "PrepareSequencedRack",
  "active": {"version": 2, "source": "DATA", "detail": {VersionView of 2}},
  "versions": [ {VersionView 2}, {VersionView 1} ],
  "drafts": [ {DraftView 최근}, ... ]
}
```

| 칸 | 값 |
|---|---|
| `active` | 지금 카탈로그의 활성. 버전이 없으면 `{"version": null, "source": "CODE", "detail": null}`(화면 «코드 정의», 코드라 정의 JSON 이 없다). 데이터 버전이면 `source: "DATA"` 이고 `detail` 이 그 버전 행 |
| `versions` | 버전 이력. 높은 번호부터 전부 |
| `drafts` | 초안. 최근 것(`draftId` 큰 것)부터 20개. 각 초안에 마지막 모의 실행 |

### 4.2 `GET /host/missions/templates/{workMasterId}`

응답 200:

```json
{
  "workMasterId": "PrepareSequencedRack",
  "templates": [
    {"id": "DATA_V1", "title": "코드 PrepareSequencedRack 을 옮긴 데이터 정의", "definition": "{\n\"schemaVersion\": 1,\n..."},
    {"id": "ARRIVAL_WAIT", "title": "랙 도착 대기(rack_present = true, 기한 120초, 기한 뒤 ABORTED)", "definition": "..."}
  ]
}
```

- `DATA_V1` 은 picasso `MissionFixtures.PREPARE_SEQUENCED_RACK` 의 사본(버전 1 모양, 단위 노드 하나 `place`)이다. 코드 `PrepareSequencedRack` 과 같은 단위를 계획한다.
- `ARRIVAL_WAIT` 는 `MissionFixtures.withArrivalWait()` 의 사본에서 `onDeadline` 만 `ABORTED` 로 바꾼 것(버전 2 모양)이다. 첫 노드가 `{"kind": "wait", "id": "rack-arrival", "signal": "rack_present", "expect": "true", "deadlineSeconds": 120, "onDeadline": "ABORTED"}` 이다.
- 세 템플릿(`DATA_V1`, `ARRIVAL_WAIT`, S4a 에서 더한 `ARRIVAL_WAIT_HOLD`) 모두 단위 노드 `place` 에 `"sourceObservation": "OPTIONAL"` 이 있다(picasso 픽스처와 같은 줄 자리, `source` 다음). 코드 케이퍼빌리티의 기본값과 같아 거동은 그대로다. 근거: `SourceObservationHostTest` 의 `시작용 정의는 출발 관측을 OPTIONAL 로 선언하고 그 버전은 설비가 말이 없어도 오늘처럼 보낸다`, 기존 `MissionVersionsTest` 의 활성화 시험 전부. 아울러 세 템플릿 모두 단위 노드 `place` 에 `"destinationObservation": "OPTIONAL"` 도 선언하여 현장 동작은 그대로다. 근거: `DestinationObservationHostTest` 의 `시작용 정의는 목적지 관측을 OPTIONAL 로 선언하고 그 버전은 설비가 목적지를 있다고 해도 오늘처럼 보낸다`.
- 글자는 칸마다 한 줄이고 끝 개행이 없다. 틀린 변경은 이 글자를 바꿔 만들 수 있다(예: `"signal": "rack_present"` → `"signal": "rack_ready"` 가 SIGNAL_NOT_IN_SPEC).

### 4.3 `POST /host/missions/{workMasterId}/drafts`

요청:

```json
{"definition": "<정의 JSON 글자>", "actor": "lee", "requestId": "6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a"}
```

- `definition` 은 문자열이면 무엇이든 된다(빈 문자열, 깨진 JSON 포함, 초안은 자유롭다). JSON 객체를 그대로 넣으면 400 이다(글자로 넣을 것).
- `actor` 는 비어 있지 않은 문자열(운영 서비스의 `X-Ops-User`).

응답 200: `{"draft": {DraftView, lastMockRun: null}}`. 저장은 검증하지 않는다.

### 4.4 `POST /host/missions/drafts/{draftId}/validate`

요청: `{"robotIds": ["humanoid-01", "quadruped-01"]}`. 아무것도 남기지 않는다(요청 id 없음).

응답 200:

```json
{
  "result": "PASSED",
  "draftId": 3,
  "workMasterId": "PrepareSequencedRack",
  "checkedAt": "2026-10-08T00:00:01Z",
  "refusals": [],
  "unknown": null,
  "inputs": {InputsView}
}
```

| `result` | `refusals` | `unknown` |
|---|---|---|
| `PASSED` | `[]` | null |
| `REFUSED` | 1개 이상(3.1) | null |
| `INPUT_UNKNOWN` | `[]` | 3.2 |

### 4.5 `POST /host/missions/drafts/{draftId}/mock-run`

요청: `{"robotIds": [...], "requestId": "<UUID>"}`.

요청 안에서 동기로 돈다(실제 시간 1~3초, 상한 30초). 운영 서비스는 이 요청만 호스트 요청 제한을 60초로 둔다(T10). 검증 입력을 읽는 동안만 호스트 잠금을 잡는다.

응답 200:

```json
{
  "result": "PASSED",
  "draftId": 3,
  "workMasterId": "PrepareSequencedRack",
  "checkedAt": "...",
  "refusals": [],
  "unknown": null,
  "inputs": {InputsView},
  "mockRun": {MockRunView}
}
```

| `result` | 뜻 | `mockRun` | 남는 행 |
|---|---|---|---|
| `PASSED` | 검증을 지나 돌았고 실행이 모든 단위 완료로 종결 | 그 행(`passed: true`) | 모의 실행 행 |
| `FAILED` | 검증을 지나 돌았으나 실패(아래 하위 범주) | 그 행(`passed: false`) | 모의 실행 행 |
| `REFUSED` | 검증 거부. 돌지 않았다 | null | 없음(요청 id 재조회는 404) |
| `INPUT_UNKNOWN` | 입력 모름(`SAMPLE_ORDER` 포함). 돌지 않았다 | null | 없음(요청 id 재조회는 404) |

모의 실행 결과 `mockRun.result`(모의 실행 표의 `result` JSONB 그대로). **JSONB 라 칸 순서는 보장하지 않는다**(DB 가 다시 정렬한다). 이름으로 읽을 것:

```json
{
  "passed": true,
  "failure": null,
  "detail": null,
  "robotId": "mock-01",
  "sample": {
    "jobOrderId": "MOCK-3",
    "requiredEvidence": "E2",
    "slots": ["RACK-204.S01", "RACK-204.S02"],
    "material": "ENGINE-COVER-A",
    "presentation": "SEQ-IN-02.BIN-A"
  },
  "physicalState": "PHYSICALLY_DONE",
  "units": [
    {"unitId": "rack-arrival", "route": "SIGNAL", "skillType": "equipment_wait", "state": "DONE", "reached": "E2", "failureClass": null},
    {"unitId": "RACK-204.S01", "route": "ROBOT", "skillType": "pick_place", "state": "DONE", "reached": "E2", "failureClass": null},
    {"unitId": "RACK-204.S02", "route": "ROBOT", "skillType": "pick_place", "state": "DONE", "reached": "E2", "failureClass": null}
  ],
  "virtualElapsedSeconds": 105,
  "wallElapsedMillis": 812
}
```

| 칸 | 값 |
|---|---|
| `failure` | 통과면 null. 실패 하위 범주: `DEFINITION`(돌리기 전 검사에서 걸림, 돌지 않음: 표본으로 세운 계획이 비었거나 단위 id 가 겹침, 표본 작업 지시에 없는 반복 대상(최상위 단위 노드·반복 그룹의 `forEach`), 플릿 경로 단위의 용기·출발지·목적지를 표본이 채우지 못함(`detail` 은 «표본이 플릿 운반 단위를 채우지 못했습니다: <단위 id>(용기 없음, …)»), 같은 신호를 기대 값이 다른 대기 노드 둘이 씀. 어느 것인지는 `detail` 이 말함) · `SUBMISSION_REJECTED`(표본 작업 지시를 미들웨어가 받지 않음, 예: 정의의 `maxEvidence` 가 E2 미만) · `NOT_SETTLED`(가상 시간 상한 안에 종결하지 않음) · `WALL_CLOCK_LIMIT`(실제 시간 30초 초과) · `EXECUTION_FAILED`(종결했으나 모든 단위 완료가 아님). 옛 행에만 `FLEET_ROUTE`(플릿 경로 노드를 돌리지 않던 때의 실패)가 남아 있을 수 있고 지금 호스트는 내지 않는다. 플릿 경로 노드는 요청마다 새 인프로세스 플릿 미믹(picasso `fleet-mimic` 의 `AmrFleetMimic`)으로 돈다 |
| `detail` | 실패의 화면용 한국어. 통과면 null |
| `robotId` | 모의 실행 기체 id `mock-01`(현장 기체와 겹치지 않음) |
| `sample` | 표본 작업 지시: 마지막 셀 스냅숏의 슬롯 앞 둘, 자재를 든 첫 제시 자리, 자재 수 = 슬롯 수, 요구 근거 E2. id 는 `MOCK-<draftId>` |
| `physicalState` | 실행의 마지막 물리 상태. 실행이 서지 않았으면(`DEFINITION`·`SUBMISSION_REJECTED`, 옛 행의 `FLEET_ROUTE`) null |
| `units` | 단위별 상태와 근거 등급. 실행이 서지 않았으면 `[]`. `route` 는 `ROBOT`·`FLEET`(플릿 미믹에 맡긴 운반, `skillType` 은 `transport`)·`SIGNAL`(대기 단위, `skillType` 은 `equipment_wait`), `state`·`reached` 는 S3a §5 의 단위 칸과 같은 이름이다 |
| `virtualElapsedSeconds` | 표본 작업 지시 뒤 흐른 가상 시간(정수 초) |
| `wallElapsedMillis` | 실제로 걸린 시간 |

이상적 현장(T8): 목적지 슬롯은 처음부터 점유이고 기대 자재를 내며(관측 시각 null), 표본 작업 지시의 출발 자리(`source` 장비 요구)는 점유이고 그 자재를 내며, 그래서 출발 관측을 요구한(`REQUIRED`) 단위도 모의 실행을 지난다. 그 밖의 자리는 침묵, 이름 있는 신호는 대기 노드의 기대 값을 낸다. 기체 프로파일은 현장 humanoid 프로파일에서 실패 모드를 뺀 사본(`mission-host/mock-run/humanoid-a.json`)이다. 현장 기체·현장 셀 대역·registry 에 아무것도 닿지 않는다.

### 4.6 `POST /host/missions/drafts/{draftId}/activate`

요청:

```json
{"actor": "lee", "reason": "랙 도착 대기 도입", "robotIds": ["humanoid-01", "quadruped-01"], "requestId": "<UUID>"}
```

`actor`·`reason` 은 비어 있지 않은 문자열(빈 사유는 400 `BAD_REQUEST`, 운영 서비스가 먼저 `REASON_REQUIRED` 로 막는다).

호스트 잠금 아래에서: 다시 검증(지금의 신호 사양·현장 스킬) → 그 초안의 마지막 모의 실행이 통과인지 → 버전 번호 = 그 WorkMaster 의 최대 + 1 로 버전 행 → 카탈로그. 활성화한 버전은 **다음 작업 지시부터** 쓰이고, 진행 중인 실행은 쥔 버전으로 끝난다.

응답 200:

```json
{
  "result": "ACTIVATED",
  "draftId": 3,
  "workMasterId": "PrepareSequencedRack",
  "checkedAt": "...",
  "version": 1,
  "refusals": [],
  "unknown": null,
  "inputs": {InputsView},
  "lastMockRun": {MockRunView},
  "activated": {VersionView}
}
```

| `result` | 뜻 | `version`·`activated` | `refusals` | `unknown` | `lastMockRun` |
|---|---|---|---|---|---|
| `ACTIVATED` | 활성화됨 | 선 버전과 그 행 | `[]` | null | 통과한 그 모의 실행 |
| `REFUSED` | 다시 검증에서 거부 | null | 3.1 | null | null |
| `FLEET_PORT_ABSENT` | 정의에 플릿 경로 노드가 있는데 실행 호스트에 플릿 포트가 없음(`host.fleet.url` 빈 값. 값이 있으면 이 결과는 나지 않고 다음 판정으로 간다). 마지막 모의 실행이 통과여도 활성화하지 않음(모의 실행 통과는 «이 호스트에서 돈다» 가 아님) | null | `[]` | null | 마지막 모의 실행 또는 null |
| `MOCK_RUN_REQUIRED` | 그 초안의 마지막 모의 실행이 없거나 실패 | null | `[]` | null | 마지막 모의 실행(실패한 것) 또는 null |
| `INPUT_UNKNOWN` | 신호 사양이나 현장 스킬을 모름. 활성화하지 않음 | null | `[]` | 3.2 | null |

- 판정 순서가 위 순서라 거부와 모의 실행 없음이 함께면 `REFUSED` 다. 플릿 포트 없음과 모의 실행 없음이 함께면 `FLEET_PORT_ABSENT` 다.
- 앞 초안·다른 초안의 모의 실행은 보지 않는다. 같은 초안을 다시 활성화하면 새 버전 번호가 선다.
- 버전 행만 남는다(`REFUSED`·`FLEET_PORT_ABSENT`·`MOCK_RUN_REQUIRED`·`INPUT_UNKNOWN` 은 아무것도 남지 않아 요청 id 재조회가 404).

### 4.7 `GET /host/missions/requests/{requestId}`

운영 서비스의 재조회(T9). 응답이 없을 때 그 요청 id 로 반영 여부를 본다.

응답 200(셋 중 남은 것만 null 이 아니다. 정상이면 하나다):

```json
{"requestId": "...", "draft": null, "mockRun": {MockRunView}, "version": null}
```

- `draft` 는 `DraftView`(그 초안의 마지막 모의 실행 포함), `mockRun` 은 `MockRunView`, `version` 은 `VersionView`.
- 남은 행이 없으면 404 `REQUEST_NOT_FOUND`. 정규형 UUID 가 아니면 400 `BAD_REQUEST`.
- 그 요청 id 의 조작을 아직 처리 중이면(호스트 잠금을 기다리는 활성화, 하네스를 세우거나 입력을 읽는 잠금을 기다리거나 도는 모의 실행, 저장 중인 초안) 409 `REQUEST_IN_PROGRESS`. 호스트는 요청 id 를 받는 조작 셋의 처음부터 끝까지 그 id 를 처리 중으로 두고, 행을 남긴 뒤에 뺀다. 재조회는 처리 중인지를 먼저 보고 행을 나중에 읽으므로, 처리 중이 아니고 행도 없으면 그 조작은 행을 남기지 않고 끝났거나 아직 호스트에 오지 않은 것이다. 처리 중 표시는 프로세스 안에만 있다(재기동하면 없어진다).

## 5. 신호 조작 전달 `POST /host/cell/signals/{name}`

호스트가 현장 `POST /cell/signals/{name}`(2절)에 **본문 그대로** 넘기고, 현장의 상태 코드와 본문을 그대로 돌려준다(200·400·403·404·415). 안전 신호 거부도 현장이 하고 호스트는 넘기기만 한다(ADR 32).

| 경우 | 상태 | 본문 |
|---|---|---|
| 현장이 답함 | 현장의 상태 | 현장의 본문(2절) |
| 호스트에 `application/json` 이 아님 | 415 | 스프링 기본 |
| 현장이 안 닿음(연결 실패, 4초 시간 초과) | 503 | `{"error": "CELL_SILENT", "detail": "현장 셀 대역이 답하지 않는다"}` |

- 요청 제한은 4초다(셀 대역 읽기는 1초). 현장의 신호 쓰기는 mimic 엔진 잠금을 기다리고, 엔진은 그 잠금 아래에서 registry 적재(요청 제한 3초)를 할 수 있다. 운영 서비스의 호스트 요청 제한(5초)보다 짧아 호스트의 503 이 운영 서비스에 닿는다.
- 이름은 경로 조각으로 인코딩해 넘긴다. 바뀐 값은 **다음 pump(250ms 주기)부터** `GET /host/cell` 과 설비 대기에 보인다. 운영 서비스가 재조회로 대조할 때는 `GET /host/cell` 의 `signals` 에서 그 이름의 `value` 를 본다(T9).

## 6. `GET /host/cell`

응답 200 늘:

```json
{"cell": {"presentations": [...], "slots": [...], "signals": [...]}}
```

- `cell` 은 마지막 pump 가 현장 `/cell` 에서 읽은 스냅숏이며 모양은 1절 본문과 같다(칸 셋, 신호 원소 칸 여섯).
- 현장 본문에 `signals` 칸이 없으면 `"signals": null`(신호 사양을 모름). 현장 셀 대역이 안 닿거나(연결 실패, 1초 시간 초과, 200 아님, 모양 어긋남. 신호 원소의 모양이 어긋나도 스냅숏 전체를 못 읽은 것이다) 아직 pump 가 안 돌았으면 `{"cell": null}`.

## 7. 셀 신호(호스트 안, 참고)

- `observe(자리)`·`holding(자재)`: S3a §7 그대로.
- `signal(이름)`: 스냅숏의 `signals` 에서 이름으로 `NamedSignal(value, observedAt)`. 모르는 이름, 신호 목록이 없는 스냅숏, 스냅숏 없음은 null(못 읽음)이고 설비 대기는 기한까지 기다린다.

## 8. 상태 코드 요약(호스트·현장)

| 엔드포인트 | 200 | 400 | 403 | 404 | 409 | 415 | 503 |
|---|---|---|---|---|---|---|---|
| `POST /host/eligibility` | 판정 | `UNKNOWN_WORK_MASTER`·`FLEET_PORT_ABSENT`·`BAD_REQUEST` | | | | JSON 아님 | |
| `POST /host/job-orders` | 결과 넷 | `UNKNOWN_WORK_MASTER`·`FLEET_PORT_ABSENT`·`BAD_REQUEST` | | | | JSON 아님 | |
| `GET /host/executions` | 늘 | | | | | | |
| `GET /host/cell` | 늘(`cell` null 가능) | | | | | | |
| `GET /host/missions/{wm}` | 개요 | `UNKNOWN_WORK_MASTER` | | | | | |
| `GET /host/missions/templates/{wm}` | 템플릿 둘 | `UNKNOWN_WORK_MASTER` | | | | | |
| `POST /host/missions/{wm}/drafts` | 저장 | `UNKNOWN_WORK_MASTER`·`BAD_REQUEST` | | | `REQUEST_ID_REUSED` | JSON 아님 | |
| `POST .../drafts/{id}/validate` | 결과 셋 | `BAD_REQUEST` | | `DRAFT_NOT_FOUND` | | JSON 아님 | |
| `POST .../drafts/{id}/mock-run` | 결과 넷 | `BAD_REQUEST` | | `DRAFT_NOT_FOUND` | `REQUEST_ID_REUSED` | JSON 아님 | |
| `POST .../drafts/{id}/activate` | 결과 다섯 | `BAD_REQUEST` | | `DRAFT_NOT_FOUND` | `REQUEST_ID_REUSED` | JSON 아님 | |
| `GET /host/missions/requests/{id}` | 남은 행 | `BAD_REQUEST` | | `REQUEST_NOT_FOUND` | `REQUEST_IN_PROGRESS` | | |
| `POST /host/cell/signals/{name}` | 현장 200 | 현장 400 | 현장 403 | 현장 404 | | 현장 415·호스트 415 | `CELL_SILENT` |
| 현장 `GET /cell` | 늘 | | | (다른 경로) | | | |
| 현장 `POST /cell/signals/{name}` | 바꿈 | `BAD_REQUEST`·`SIGNAL_VALUE_INVALID` | `SAFETY_SIGNAL_READ_ONLY` | `UNKNOWN_SIGNAL` | | `UNSUPPORTED_MEDIA_TYPE` | |

호스트가 안 닿으면(연결 실패) 운영 서비스가 503·NO_RESPONSE 로 다룬다. 호스트 자신은 `CELL_SILENT` 말고 5xx 를 의도해 내지 않는다.

---

## 9. 운영 서비스 REST

### 9.5 `GET /api/cell`

S3a 그대로 호스트 `GET /host/cell` 의 200 본문을 해석 없이 넘긴다. 그래서 본문에 `signals`(6절)가 실린다. `{"cell": null}` 과 `"signals": null` 은 둘 다 200 이고 화면 «모름» 이다. 호스트가 안 닿으면 S3a §9.4 와 같은 503 `HOST_SILENT`.

### 9.6 상태 코드 요약

S3a §9.6 의 네 행은 그대로다. 임무 엔드포인트(`GET /api/missions/{workMasterId}`, 템플릿 읽기, 초안 저장·검증·모의 실행·활성화)와 `POST /api/cell/signals/{name}` 의 모양·상태 코드는 운영 서비스 영역이 S3b 스펙 §7 에 따라 정해 10절에 적었다(상태 코드는 §10.10). 맞춘 것:

- 호스트 결과 → 조작 기록: `ACTIVATED` → `SUCCEEDED`, `REFUSED`·`FLEET_PORT_ABSENT`·`MOCK_RUN_REQUIRED`·`INPUT_UNKNOWN` → `REJECTED`(호스트 본문의 `result` 가 남음). 모의 실행 `PASSED`·`FAILED` 는 돌았으므로 둘 다 호스트가 받은 조작이다. 호스트 불통·5xx 는 `NO_RESPONSE` 와 4.7 재조회.
- 409 `REQUEST_ID_REUSED` 는 운영 서비스가 같은 요청 id 를 두 번 보냈다는 뜻이다(재조회 대신 다시 보낸 경우).
- 거부 → `Finding`: `target` null, `observed` 앞에 `노드 <nodeId>: `(nodeId 가 null 이면 붙이지 않음), `owner` ENGINEER → ENGINEER(`inScreen` 참), OUTSIDE_CONSOLE → SITE(`inScreen` 거짓), `basisVersion` 은 `Long` 으로, `checkedAt` 은 거부의 `checkedAt`.
- 신호 조작의 재조회 대조는 `GET /host/cell` 의 `cell.signals[name].value`.

---

## 10. 운영 서비스 임무·신호 REST(화면이 부르는 것)

운영 서비스가 실제로 내고 받는 모양이다(S3b 스펙 §7). 화면과 통합 시험은 이것에 맞춘다. 코드 주석의 «S3b JSON 계약 §10.N» 은 이 절을 가리킨다.

### 10.1 공통 규칙

- 기준 주소 `http://127.0.0.1:${OPS_PORT}`. 시각은 ISO-8601 UTC 문자열이고 `null` 칸은 생략하지 않는다. 칸 순서는 이 절의 예 그대로다.
- POST 는 `Content-Type: application/json` 만 받는다. 그 밖은 **415**(스프링 기본 본문, 모양 보장 없음). 본문을 읽지 않는 검증·모의 실행도 같다(화면은 `{}` 를 싣는다).
- 행위자 헤더 `X-Ops-Mode`(`engineer`·`operator`)와 `X-Ops-User`(`[A-Za-z0-9._-]{1,64}`). S3a §9.3 과 같다.
- 사전 거부(400·403·503, 검증의 호스트 4xx)의 본문은 늘 `PreRejection` `{"error": "...", "detail": "..."}` 이다. 사전 거부는 호스트에 닿지 않았으므로 조작 기록에 남지 않는다.
- 판정 순서(쓰기): 게이트(400 `ACTOR_REQUIRED` → 403 `MODE_NOT_ALLOWED`) → WorkMaster(400 `UNKNOWN_WORK_MASTER`) → 초안 id(400 `MISSION_BAD_REQUEST`) → 본문(400 `MISSION_BAD_REQUEST`, 활성화는 400 `REASON_REQUIRED`) → 시운전 완료 기체(503 `COMMISSIONED_ROBOTS_UNKNOWN`) → 호스트. 게이트가 먼저라 운영자 모드의 깨진 본문은 403 이다.
- WorkMaster 는 `PrepareSequencedRack` 만 받는다(T6). 그 밖(`InspectAsset`·`DeliverContainer` 포함)은 읽기·쓰기 모두 400 `UNKNOWN_WORK_MASTER`(detail `편집하지 않는 임무다: InspectAsset (편집하는 것: [PrepareSequencedRack])`).
- 초안 id 는 경로의 양의 정수다. 그 밖은 400 `MISSION_BAD_REQUEST`(detail `초안 id 가 양의 정수가 아니다: x`).
- 운영 서비스가 호스트를 부르는 요청 제한: 5초. **모의 실행만 60초**(T10). 연결 제한 2초.

**시운전 완료 기체(T7)**: 검증·모의 실행·활성화는 `GET /api/robots` 와 같은 기체 목록 판정에서 `commissioning.state == COMPLETE` 인 기체 id 를 목록 순서대로 `robotIds` 로 호스트에 넘긴다. 퇴역·시운전 미완 기체는 빠진다. 시운전 완료 기체가 하나도 없으면 빈 목록을 넘긴다(호스트가 `SKILL_NOT_ON_SITE` 로 거부). 다음이면 **모름**이라 호스트를 부르지 않고 503 이다. 빈 목록이나 직전 목록으로 대신하지 않는다(모름을 없음으로 접으면 엉뚱한 `SKILL_NOT_ON_SITE` 가 난다).

| 경우 | 상태 | `error` | `detail` |
|---|---|---|---|
| registry 불통(기체 목록을 지금 못 읽음, 직전 목록이 있어도) | 503 | `COMMISSIONED_ROBOTS_UNKNOWN` | `registry 가 답하지 않아 시운전 완료 기체를 모른다` |
| 운영자 토큰 불일치 | 503 | `COMMISSIONED_ROBOTS_UNKNOWN` | `운영자 토큰이 registry 와 맞지 않아 시운전 완료 기체를 모른다` |
| 시운전 판정이 없는 기체가 있음(바인딩을 못 읽은 목록) | 503 | `COMMISSIONED_ROBOTS_UNKNOWN` | `시운전 판정이 없는 기체가 있어 시운전 완료 기체를 모른다` |

화면은 이것을 거부 카드가 아니라 «모름» 으로 보인다(호스트의 `INPUT_UNKNOWN` 과 같은 자리).

**조작 응답 공통(초안 저장·모의 실행·활성화·신호 조작)**: 호스트에 보냈으면 호스트가 무엇을 답했든 **200** 이다. 결과는 본문에 있다.

| 호스트가 한 것 | `result` | `confirmation` | 호스트 본문 칸 | `rejection` |
|---|---|---|---|---|
| 2xx 이고 본문을 읽음 | 아래 결과 매핑 | null | 호스트 200 본문 그대로 | null |
| 4xx(`{error, detail}`) | `REJECTED` | null | null | `{"status": 404, "error": "DRAFT_NOT_FOUND", "detail": "..."}` |
| 5xx(신호의 503 `CELL_SILENT` 포함), 연결 실패, 시간 초과, 2xx 인데 본문을 못 읽음 | `NO_RESPONSE` | 재조회 결과(아래). 재조회도 못 읽으면 null | null | null |

- 요청 id 는 운영 서비스가 조작마다 새로 만든 정규형 소문자 UUID 다. 임무 조작은 그것을 호스트에 `requestId` 로 넘긴다. 같은 요청 id 로 다시 보내지 않는다(호스트가 409 `REQUEST_ID_REUSED` 로 막는다). 409 가 오면 4xx 행 그대로 `REJECTED` 다.
- 재조회는 응답 없음 1초 뒤 한 번이다. 그래서 `NO_RESPONSE` 응답은 1초 남짓 늦게 온다(시간 초과면 5초, 모의 실행은 60초에 1초를 더한다). `confirmation` 이 null 인 `NO_RESPONSE` 는 «반영되었을 수 있으나 확인하지 못했다» 다. 화면은 임무 개요(초안·버전 목록)나 셀 대역 표에서 확인하게 한다.
- 임무 조작의 재조회는 `GET /host/missions/requests/{requestId}`(§4.7)다. 200 이고 이 조작의 행 칸(초안 저장 `draft`, 모의 실행 `mockRun`, 활성화 `version`)이 객체면 `CONFIRMED_APPLIED`, 404 `REQUEST_NOT_FOUND` 이거나 다른 칸만 있으면 `CONFIRMED_NOT_APPLIED`, 409 `REQUEST_IN_PROGRESS`(호스트가 그 조작을 아직 처리 중)는 확인 못 함(null)이다. 확인 행을 붙이지 않고 `NO_RESPONSE` 행만 남는다. 그 밖(연결 실패, 다른 상태, `REQUEST_NOT_FOUND` 가 아닌 404)도 확인 못 함(null)이다. 모의 실행의 `REFUSED`·`INPUT_UNKNOWN` 과 활성화의 `ACTIVATED` 아닌 결과는 행을 남기지 않으므로 재조회에서 `CONFIRMED_NOT_APPLIED` 로 보인다.
- 신호 조작의 재조회는 `GET /host/cell`(§6)이다. `cell.signals` 에서 그 이름의 `value` 가 보낸 값과 같으면 `CONFIRMED_APPLIED`, 다르면 `CONFIRMED_NOT_APPLIED`, 신호 목록에 그 이름이 없으면 `CONFIRMED_NOT_APPLIED`, 호스트 불통·`{"cell": null}`·`"signals": null` 이면 확인 못 함(null)이다. 값만 대조하므로 이미 그 값이던 신호를 다시 쓴 조작은 현장에 닿지 않았어도 `CONFIRMED_APPLIED` 다(신호 조작은 값을 그 값으로 두는 조작이다).

**결과 매핑(호스트 결과 → `result`, §9.6)**:

| 조작 | 호스트 200 본문의 `result` | `result` |
|---|---|---|
| 초안 저장 | (결과 칸 없음, `draft.draftId` 가 정수) | `SUCCEEDED` |
| 모의 실행 | `PASSED`·`FAILED` | `SUCCEEDED`(돌았다) |
| 모의 실행 | `REFUSED`·`INPUT_UNKNOWN` | `REJECTED` |
| 활성화 | `ACTIVATED` | `SUCCEEDED` |
| 활성화 | `REFUSED`·`FLEET_PORT_ABSENT`·`MOCK_RUN_REQUIRED`·`INPUT_UNKNOWN` | `REJECTED` |
| 신호 조작 | (현장 200, 바뀐 신호, `name` 이 문자열) | `SUCCEEDED` |

- 결과 이름은 호스트 본문의 `result` 에 그대로 남는다. **화면은 `REJECTED` 를 보고 거부 카드를 그리지 말고 호스트 본문의 `result` 로 가를 것.** `INPUT_UNKNOWN` 은 «모름»(호스트 `unknown.detail` 을 보인다), `MOCK_RUN_REQUIRED` 는 «통과한 모의 실행이 없다», `FLEET_PORT_ABSENT` 는 «이 실행 호스트에는 플릿 포트가 없어 플릿 운반 노드를 든 정의를 활성화하지 않는다», `REFUSED` 만 거부 카드(`findings`)다.
- 2xx 인데 결과 이름이 그 조작의 것이 아니거나(예: 활성화에 `PASSED`), 거부 원소를 못 읽으면(모르는 `owner` 등) 본문을 못 읽은 것이라 `NO_RESPONSE` 와 재조회다.

### 10.2 거부 카드 `Finding`(호스트 거부 → 화면)

호스트 결과가 `REFUSED` 일 때만 `findings` 에 거부마다 하나씩, 호스트 순서대로 온다. 그 밖(통과, `FAILED`, `INPUT_UNKNOWN`, `FLEET_PORT_ABSENT`, `MOCK_RUN_REQUIRED`)은 `[]` 이다.

```json
{
  "kind": "SIGNAL_NOT_IN_SPEC",
  "observed": "노드 rack-arrival: rack_ready",
  "expected": "신호 사양의 이름 중 하나(guard_closed, lot_code, rack_present)",
  "checkedAt": "2026-10-08T00:00:01Z",
  "owner": "ENGINEER",
  "inScreen": true,
  "action": "신호 이름을 고치거나 신호 사양에 더한다",
  "target": null,
  "basisVersion": null
}
```

| 칸 | 호스트 `RefusalView`(§3.1)에서 |
|---|---|
| `kind` | `kind` 그대로(10종). 화면의 종류 이름표에 10종을 더한다 |
| `observed` | `nodeId` 가 있으면 `노드 <nodeId>: <observed>`, null 이면(`UNREADABLE` 등 문서 전체의 문제) `observed` 그대로 |
| `expected` | 그대로 |
| `checkedAt` | 거부의 `checkedAt`(호스트가 검증한 시각, 통합 시험에서는 현장 가상 시각) |
| `owner`·`inScreen` | `ENGINEER` → `ENGINEER`·`true`, `OUTSIDE_CONSOLE` → `SITE`·`false` |
| `action` | `nextAction` 그대로 |
| `target` | 늘 null(화면은 이 칸을 기체 상세 링크로 그린다. 임무 거부의 대상은 노드다) |
| `basisVersion` | 그대로(정수 또는 null, S3b 에서는 늘 null) |

### 10.3 `GET /api/missions/{workMasterId}`, `GET /api/missions/templates/{workMasterId}`

모드 헤더 없이 된다. 호스트 `GET /host/missions/{workMasterId}`(§4.1)·`GET /host/missions/templates/{workMasterId}`(§4.2)의 200 본문을 **그대로** 넘긴다.

| 경우 | 상태 | 본문 |
|---|---|---|
| 호스트가 답함 | 200 | 호스트 본문 |
| WorkMaster 가 `PrepareSequencedRack` 이 아님 | 400 | `UNKNOWN_WORK_MASTER` |
| 호스트 불통(연결 실패, 5초 시간 초과, 200 아님, 객체 아님) | 503 | `{"error": "HOST_SILENT", "detail": "실행 호스트가 답하지 않는다: 응답 없음: ConnectException"}` |

503 이면 화면은 직전 값을 계속 보이고 «호스트가 답하지 않는다» 를 덧붙인다(S3b 스펙 §9).

### 10.4 `POST /api/missions/{workMasterId}/drafts`

헤더 `X-Ops-Mode: engineer`, `X-Ops-User`. 본문:

```json
{"definition": "<정의 JSON 글자>"}
```

- `definition` 은 문자열이면 무엇이든 된다(빈 문자열, 깨진 JSON 포함, 초안은 자유롭다). 없거나 문자열이 아니면(정의 객체를 그대로 넣은 경우 포함) 400 `MISSION_BAD_REQUEST`(detail `definition 이 문자열이 아니다(정의 JSON 은 글자로 싣는다)`). 본문이 JSON 객체가 아니어도 같다.
- 호스트에는 `actor` 로 `X-Ops-User` 를 넘긴다.

응답 200 `MissionOperationOutcome`:

```json
{
  "requestId": "6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a",
  "workMasterId": "PrepareSequencedRack",
  "result": "SUCCEEDED",
  "confirmation": null,
  "outcome": {"draft": {"draftId": 3, "workMasterId": "PrepareSequencedRack", "definition": "...", "savedBy": "lee", "requestId": "6f1c2a9e-...", "savedAt": "...", "lastMockRun": null}},
  "findings": [],
  "rejection": null
}
```

`outcome` 은 호스트 §4.3 본문 그대로다. 화면은 `outcome.draft.draftId` 로 다음 검증·모의 실행·활성화를 부른다.

### 10.5 `POST /api/missions/{workMasterId}/drafts/{draftId}/validate`

헤더 `X-Ops-Mode: engineer`, `X-Ops-User`. 본문은 읽지 않는다(`{}`). 검증은 **조작이 아니다**. 호스트도 아무것도 남기지 않으므로 조작 기록에 남지 않고 요청 id·`result`·`confirmation` 이 없다.

응답 200 `MissionValidationReply`:

```json
{
  "workMasterId": "PrepareSequencedRack",
  "outcome": {"result": "REFUSED", "draftId": 4, "workMasterId": "PrepareSequencedRack", "checkedAt": "...", "refusals": [...], "unknown": null, "inputs": {...}},
  "findings": [{Finding}]
}
```

`outcome` 은 호스트 §4.4 본문 그대로(`result` 는 `PASSED`·`REFUSED`·`INPUT_UNKNOWN`)다.

| 경우 | 상태 | 본문 |
|---|---|---|
| 호스트 200 이고 결과 셋 중 하나 | 200 | 위 |
| 호스트 4xx(없는 초안 404 `DRAFT_NOT_FOUND` 등) | **호스트의 상태 그대로** | `PreRejection`(호스트의 `error`·`detail`. `error` 가 없으면 `HOST_REJECTED`) |
| 호스트 불통, 5xx, 200 인데 본문을 못 읽음 | 503 | `HOST_SILENT` |
| 시운전 완료 기체를 모름 | 503 | `COMMISSIONED_ROBOTS_UNKNOWN` |

### 10.6 `POST /api/missions/{workMasterId}/drafts/{draftId}/mock-run`

헤더 `X-Ops-Mode: engineer`, `X-Ops-User`. 본문은 읽지 않는다(`{}`). 호스트 요청 제한 60초(T10). 실제로는 1~3초 걸린다.

응답 200 `MissionOperationOutcome`(10.4 와 같은 칸). `outcome` 은 호스트 §4.5 본문 그대로다. 단위별 표는 `outcome.mockRun.result.units`, 통과 여부는 `outcome.result`(`PASSED`·`FAILED`)와 `outcome.mockRun.passed`, 실패 이유는 `outcome.mockRun.result.failure`·`.detail` 이다. `REFUSED` 면 `findings`, `INPUT_UNKNOWN` 이면 `outcome.unknown` 이다.

### 10.7 `POST /api/missions/{workMasterId}/drafts/{draftId}/activate`

헤더 `X-Ops-Mode: engineer`, `X-Ops-User`. 본문:

```json
{"reason": "랙 도착 대기 도입"}
```

- `reason` 은 앞뒤 공백을 깎고 비어 있지 않아야 한다. 없음, 빈 문자열, 공백뿐, 문자열 아님, 본문이 JSON 객체가 아님은 모두 400 `REASON_REQUIRED`(detail `활성화 사유가 없다`, 현장 설정 변경과 같은 이름). 깎은 사유가 호스트와 조작 기록의 사유 칸에 간다.

응답 200 `MissionOperationOutcome`. `outcome` 은 호스트 §4.6 본문 그대로다. 선 버전은 `outcome.version`(`ACTIVATED` 일 때), 그 행은 `outcome.activated` 다.

### 10.8 `POST /api/cell/signals/{name}`

헤더 `X-Ops-Mode` 는 `operator`·`engineer` **둘 다 된다**(결정 3, 운영 영역의 조작). 헤더가 없거나 틀리면 400 `ACTOR_REQUIRED`. 본문:

```json
{"value": "true"}
```

- 본문이 JSON 객체가 아니거나 `value` 가 문자열이 아니면(`{"value": true}`·`{"value": null}` 포함) 400 `SIGNAL_BAD_REQUEST`(detail `value 가 문자열이 아니다(신호 값은 늘 문자열이다)`). 호스트에 닿지 않는다.
- 값이 신호 종류에 맞는지, 안전 신호인지는 운영 서비스가 보지 않는다. 현장 셀 대역이 보고 그 거부가 200 본문의 `rejection` 으로 그대로 온다(ADR 32). 화면은 안전 신호에 버튼을 두지 않는다.

응답 200 `SignalWriteOutcome`:

```json
{
  "requestId": "6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a",
  "name": "rack_present",
  "value": "true",
  "result": "SUCCEEDED",
  "confirmation": null,
  "signal": {"name": "rack_present", "location": "RACK-204", "kind": "BOOLEAN", "safety": false, "value": "true", "observedAt": "2026-10-08T00:02:00Z"},
  "rejection": null
}
```

| 현장(호스트를 거쳐)이 한 것 | `result` | `signal` | `rejection` |
|---|---|---|---|
| 200 바꿈 | `SUCCEEDED` | 바뀐 신호(§2 의 200 본문) | null |
| 403 `SAFETY_SIGNAL_READ_ONLY`, 404 `UNKNOWN_SIGNAL`, 400 `SIGNAL_VALUE_INVALID` | `REJECTED` | null | `{"status": 403, "error": "SAFETY_SIGNAL_READ_ONLY", "detail": "..."}` |
| 호스트 503 `CELL_SILENT`, 호스트 불통 | `NO_RESPONSE` | null | null |

바뀐 값은 호스트의 다음 pump(250ms)부터 `GET /api/cell` 에 보인다. 화면은 200 을 받은 뒤 셀 대역 표를 다시 읽는다.

### 10.9 조작 기록(`GET /api/operations` 의 행)

| 조작 | `target` | `request`(JSON 문자열) | `reason` |
|---|---|---|---|
| 초안 저장 | WorkMaster id | `{"op":"SAVE_MISSION_DRAFT","workMasterId":"PrepareSequencedRack","definition":"<글자>"}` | null |
| 모의 실행 | WorkMaster id | `{"op":"MOCK_RUN_MISSION_DRAFT","workMasterId":"...","draftId":3,"robotIds":["humanoid-01","quadruped-01"]}` | null |
| 활성화 | WorkMaster id | `{"op":"ACTIVATE_MISSION_VERSION","workMasterId":"...","draftId":3,"robotIds":[...]}` | 깎은 사유 |
| 신호 조작 | 신호 이름 | `{"op":"WRITE_CELL_SIGNAL","name":"rack_present","value":"true"}` | null |

- `mode`·`user` 는 헤더 그대로(신호 조작은 `OPERATOR`·`ENGINEER` 둘 다 나온다). 검증은 행이 없다.
- `result` 는 10.1 의 `result`. 재조회 행은 같은 `requestId` 의 새 행(`CONFIRMED_*`)이다.
- `targetResponse`: 호스트가 답했으면 `{"status": 200, "body": {...}}`(본문이 JSON 객체가 아니면 `body` 는 글자), 닿지 않았으면 `{"cause": "응답 없음: ConnectException"}`, 재조회 행은 `{"observed": <§4.7 본문>}`(임무)·`{"observed": <신호 원소>}`(신호) 또는 `{"observed": null}`(남은 행 없음, 그 이름의 신호 없음).

### 10.10 상태 코드 요약(운영 서비스, S3a §9.6 에 더함)

| 엔드포인트 | 200 | 400 | 403 | 404 | 415 | 503 |
|---|---|---|---|---|---|---|
| `GET /api/missions/{wm}` | 호스트 본문 | `UNKNOWN_WORK_MASTER` | | | | `HOST_SILENT` |
| `GET /api/missions/templates/{wm}` | 호스트 본문 | `UNKNOWN_WORK_MASTER` | | | | `HOST_SILENT` |
| `POST /api/missions/{wm}/drafts` | 보냄(결과는 본문) | `ACTOR_REQUIRED`·`UNKNOWN_WORK_MASTER`·`MISSION_BAD_REQUEST` | `MODE_NOT_ALLOWED` | | JSON 아님 | |
| `POST /api/missions/{wm}/drafts/{id}/validate` | 판정 | `ACTOR_REQUIRED`·`UNKNOWN_WORK_MASTER`·`MISSION_BAD_REQUEST`·호스트 400 | `MODE_NOT_ALLOWED` | 호스트 `DRAFT_NOT_FOUND` | JSON 아님 | `HOST_SILENT`·`COMMISSIONED_ROBOTS_UNKNOWN` |
| `POST /api/missions/{wm}/drafts/{id}/mock-run` | 보냄(결과는 본문) | `ACTOR_REQUIRED`·`UNKNOWN_WORK_MASTER`·`MISSION_BAD_REQUEST` | `MODE_NOT_ALLOWED` | | JSON 아님 | `COMMISSIONED_ROBOTS_UNKNOWN` |
| `POST /api/missions/{wm}/drafts/{id}/activate` | 보냄(결과는 본문) | `ACTOR_REQUIRED`·`UNKNOWN_WORK_MASTER`·`MISSION_BAD_REQUEST`·`REASON_REQUIRED` | `MODE_NOT_ALLOWED` | | JSON 아님 | `COMMISSIONED_ROBOTS_UNKNOWN` |
| `POST /api/cell/signals/{name}` | 보냄(결과는 본문) | `ACTOR_REQUIRED`·`SIGNAL_BAD_REQUEST` | 없음(두 모드) | | JSON 아님 | |

- 쓰기의 호스트 4xx(없는 초안, 현장의 신호 거부)는 200 본문의 `rejection` 이다. 검증만 호스트 4xx 를 같은 상태 코드로 넘긴다(조작이 아니라 기록할 결과가 없다).
- 쓰기에서 호스트 불통은 503 이 아니라 200 의 `NO_RESPONSE` 다.
