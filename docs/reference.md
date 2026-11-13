# 상세 참고 문서

## 단계 이력

현재 picasso-ops main 은 PR #24 가 머지된 `511d5bc`(2026-10-13 11:50 KST) 기준이며, 서브모듈 picasso 는 `74e4d3d` 에 고정되어 있습니다. S4b 와 화면 문서(#15) 뒤에 picasso-ops PR 아홉 개(#16~#24)가 추가 머지되었으며, 기능 변경인 #18 과 그 뒤 수정(#20·#21·#22), 문서(#16·#17·#19·#24), 화면과 응답 문구의 용어 정비(#23)를 포함합니다. 이 기간에 picasso 쪽 PR 은 없고 서브모듈 고정 커밋도 바뀌지 않았습니다. 이 브랜치 `chore/picasso-51d8230`에서는 서브모듈 picasso가 `51d8230`(조직 저장소 picasso PR #1 머지)에 고정되어 있습니다.

P1 → S1a → S1b → S1c → P2a → P2b → S1d → 용어 → S2 → P3 → P4 → S3a → S3b → P5 → S3c → S4a → P6 → S4b → 화면 문서 → #16 → #17 → #18 → #20 → #21 → #19 → #22 → #23 → #24 (2026-10-07 13:47 ~ 2026-10-13 11:50 KST)

표는 머지 순서이며 P4(10/8 19:00)가 S3a(10/8 21:54)보다 먼저이고, #20(10/12 15:47:50), #21(15:48:08), #19(15:48:12)는 번호 순서가 아니라 이 순서로 머지되었습니다. P 는 picasso PR, S 는 picasso-ops PR 이며, #16 이후 행은 picasso-ops PR 번호로 적습니다. 용어 풀이는 [용어 풀이](#용어-풀이)를 참고합니다.

| 단계 | 더한 것 | PR(머지 커밋) |
|---|---|---|
| P1 | registry 어댑터 제품·빌드 등록·조회 운영자 REST 3개 | picasso #79 (`6b1a255`) |
| S1a | site·ops-service·ui·e2e 골격, 다섯 영역, 모드·사용자, 전체 상태 띠 | picasso-ops #1 (`b4fcc56`) |
| S1b | 로봇 선언·퇴역·복귀, 막힘·거부 판정, 기체 상세, Playwright CI | picasso-ops #2 (`d94a5e6`) |
| S1c | 어댑터 제품·빌드·인스턴스 등록과 화면 | picasso-ops #3 (`2a8c617`) |
| P2a | 리비전 시험 요청·집기·보고, 시험 실행기 | picasso #80 (`2370ed3`) |
| P2b | 리비전 제출·활성화·바인딩 REST, 기동 스킬 동기화 | picasso #81 (`41beedb`) |
| 문서 | P2·S1d 스펙, P2a·P2b 계획 | picasso-ops #4 (`3f00dfb`), #5 (`f84e095`) |
| S1d | 리비전·시험·활성화·바인딩·명칭 기록, 시운전 판정 | picasso-ops #6 (`694ea9e`) |
| 용어 | 용어집 대응표에 따른 문서·화면 문구 변경 | picasso-ops #7 (`7254521`) |
| S2 | 현장 설정 버전, 연결 기준 시간 변경, 막힘 근거 버전 | picasso-ops #8 (`16de942`) |
| P3 | 노드형 임무 정의·검증·버전 카탈로그, 실행의 임무 버전 고정 | picasso #82 (`8f0cc04`), 설계 picasso-ops #9 (`f7ca937`) |
| P4 | 기한에 닫힌 태스크 스트림 재부착, mimic 엔진 잠금 | picasso #83 (`1e3f4ae`) |
| S3a | 실행 호스트, 작업 지시·배정 가능·실행 목록·셀 대역 | picasso-ops #10 (`d16b19b`) |
| S3b | 임무 편집·검증·모의 실행·활성화, 셀 신호 조작 | picasso-ops #11 (`b2cce34`), 한계 문서 정정 picasso #84 (`2ca7cd6`) |
| P5 | 현장 시간값 소스 포트, 인시던트 의도의 설정 버전 | picasso #85 (`195c1ee`) |
| S3c | 시간값 넷, 실행 호스트 반영 표시 | picasso-ops #12 (`e482c33`) |
| S4a | 장애 주입, 인시던트, 보류 단위 운영자 판단 | picasso-ops #13 (`8ed1b91`) |
| P6 | 재기동 복원 입구 `Middleware.resume` | picasso #86 (`74e4d3d`) |
| S4b | 재기동 복원 보고, 이전 인스턴스 인시던트, 작업 응답 송신 기록 | picasso-ops #14 (`9606364`) |
| 화면 문서 | 스크린숏 65장, 화면 설계서·개발 내역, 촬영 설정 | picasso-ops #15 (`443a14c`) |
| 문서 | README 전면 재구성과 참고 문서(이 문서) 분리 | picasso-ops #16 (`2a8c94e`) |
| 문서 | 다이어그램 다크 버전(`.dark.svg`)과 테마별 전환 | picasso-ops #17 (`71345ac`) |
| 운영 확장 | 호출 계약과 배포, 외부 주문 API, 작업 요청 영속 저장, 결과 버전·수신 확인·콜백 전달, 호스트 결과 outbox, 공유 스테이션 조정과 임무 필수 자원, 화면 재구성 | picasso-ops #18 (`3bbcb9c`) |
| 수정 | 멈춘 작업 요청 정리(stale sweep), 자원 요구 등록 기준 조정, 현장 확인 해제와 작업 요청 종료 연동 | picasso-ops #20 (`efc578f`) |
| 수정 | 스테이션의 확인 필요 물리 상태 목록 정정, 재작업 게이트와 `REWORKING`, 결과 버전 기준선(V16) | picasso-ops #21 (`3325a54`) |
| 문서 | README 의 첫 화면과 Mock MES 실행 절차 정정 | picasso-ops #19 (`88501ac`) |
| 수정 | 미배정 재평가와 자원 요구 등록의 경합을 임무 잠금으로 직렬화, 제출 직전 권한 거부의 `REJECTED` 종료 | picasso-ops #22 (`2ca84aa`) |
| 용어 | 층별 표준 이름(외부 주문, 호출 계약, 작업 요청, 임무, 작업 지시)으로 화면과 응답 문구 정비 | picasso-ops #23 (`bb1d8b5`) |
| 화면 문서 | 화면 설계서·개발 내역 개편, 대표 스크린숏 21장으로 교체, 촬영 스크립트 재작성 | picasso-ops #24 (`511d5bc`) |

단계별 결함 주입과 바이트 대조 수는 [초기 개발 이력](readme-history.md#개발-방식)에 있습니다.

## 구성 상세

### 구성 요소, 포트, 저장

| 구성 요소 | 역할 | 포트·바인딩 | 저장 |
|---|---|---|---|
| `ui/` | React·Vite·TypeScript 관리 화면, 운영 서비스만 호출, 메뉴 아홉(운영 개요, 작업 요청, 공유 자원, 운영, 로봇, 임무, 현장 설정, 이력, 검증 도구) | dev 5173, Playwright preview 4173 | 없음 |
| `ops-service/` | 화면의 유일한 백엔드, 외부 주문 API(`/integration/v1/...`), 작업 요청 영속 저장·결과 버전·수신 확인·콜백 전달, 공유 스테이션 조정, registry·실행 호스트 REST 호출, picasso 모듈 미사용(빌드가 `checkNoPicassoOnMain` 으로 집행) | `OPS_PORT=8782`, 루프백(`server.address=127.0.0.1`) | 스키마 `ops`, 마이그레이션 V1~V21, 표 18개와 뷰 1개 |
| `mission-host/` | picasso 미들웨어로 작업 지시 실행, 임무 정의 검증·모의 실행·활성화, 250ms 마다 셀 대역 읽기·pump, 현장이 작업 허가 대응을 선언하면 pump 마다 같은 셀 대역 스냅숏으로 작업 허가를 비춰 미들웨어 허가 포트에 넘김, `GET /host/work-permits` 로 지금의 작업 허가를 내줌, registry 진단 읽기로 기체 퇴역을 비춰 미들웨어 퇴역 포트에 넘기고 `GET /host/retirements` 로 내줌, 내보낸 작업 응답을 결과 outbox 에 기록하고 `GET /host/result-events` 로 내줌, 판단 기록을 영속하고 기동 복원에 넘김, 인스턴스 심장박동, 호출자는 운영 서비스뿐 | `HOST_PORT=8785`, 루프백 | 스키마 `mission`, 마이그레이션 V1~V11, 표 17개 |
| `site/` | registry·mimic·`site-runner`·셀 대역을 한 프로세스에서 구동 | 아래 세부 구성 요소 참조 | registry 마이그레이션을 런처가 실행 |
| `registry` | 기체 원장, 어댑터·리비전·바인딩·명칭·생존 보고 | `REGISTRY_PORT=8781`, 루프백 | Postgres public |
| `mimic` | `humanoid-01`·`quadruped-01` 가상 기체, 가상 시계, seed 0 | `MIMIC_GRPC_PORT=8783`, 모든 인터페이스 | 메모리 |
| `site-runner` | 1초마다 리비전 시험 요청을 집어 별도 mimic 으로 시험, registry 에 결과 기록 | 없음 | registry |
| 셀 대역 | 셀 신호·자리·슬롯 관측, 신호 쓰기·장애 주입 입구, 신호 일곱(`inspection_ready`, `inspection_complete`, 텍스트 `inspection_job`, `inspection_job_done`, `rack_present`, 안전 신호 `guard_closed`, 텍스트 `lot_code`) | `SITE_CELL_PORT=8784`, 루프백 | 메모리 |
| Postgres | registry public, 운영 서비스 `ops`, 실행 호스트 `mission` | `127.0.0.1:45432` | `postgres:16-alpine`, DB `picasso`, `site/compose.yaml` |
| `picasso/` | 고정 커밋 `16eabb8` 의 읽기 전용 서브모듈, Gradle `includeBuild` | 없음 | 없음 |
| `e2e/` | 한 JVM 에 Postgres·registry·mimic·실행 호스트·운영 서비스를 띄우는 통합 시험 | 시험 스택 | Testcontainers |
| `scripts/mock-mes.mjs` | Mock MES 역할로 외부 주문 API 를 부르는 로컬 도구 | 없음 | 없음 |

`ops` 스키마의 표는 다음과 같습니다.

- V1~V4: `operation_log`(조작 기록, 덧붙이기 전용), `site_settings`(현장 설정 버전, 덧붙이기 전용), 뷰 `site_timings_current`
- V5: `mission_interface`(호출 계약, 정의 칸은 변경 금지이고 상태만 바뀜)
- V6~V9: `work_request`(작업 요청), `work_result`(결과 버전), `work_request_recovery`(미배정 재평가 기록), `work_result_delivery`(콜백 전달 상태)
- V10~V13: `interface_deployment`(호출 계약 배포, 변경·삭제 금지), `mission_profile`(데이터 임무 입력 프로파일, 변경·삭제 금지), `result_cursor`(호스트 결과 피드 커서, 한 행), `work_request_review`(현장 확인 기록, 변경·삭제 금지)
- V14~V16: `rf_station`(공유 스테이션, 점유 작업 칸 `owner`), `station_run`(스테이션 작업), `station_event`(스테이션 작업 상태 이력, 변경·삭제·통째 비우기 금지), `mission_resource_requirement`(임무 필수 자원, 변경·삭제·통째 비우기 금지), V15 가 `work_request.required_station_id` 와 상태 `WAITING_RESOURCE` 를, V16 이 `station_run.result_baseline`·`baseline_review_only` 를 더함
- V17: 표를 새로 만들지 않고 `rf_station` 에 칸 넷(`job_signal`, `job_echo_signal`, `quarantined_by`, `quarantined_at`)과 제약 둘(`station_job_pair`, `station_quarantine_pair`, `job_signal`·`job_echo_signal` 은 각각 유일)을 더함
- V18: `work_request_source_confirmation`(출발 자리 확인)을 만듦. 덧붙이기만 하고 행 트리거로 UPDATE·DELETE 를 막음. `confirmer_kind='PERSON'` 이며 `context` 에 사람이 확인한 출발 미관측 답(호스트 답)을 남김
- V19: `work_request_supersession`(상위 시스템 대체 연결)을 만듦. `original_receipt_id` 가 기본 키(앞선 작업 요청 하나에 대체 하나), `replacement_receipt_id` 는 유일, 칸 `source_system_id`, `original_state`(`REJECTED`·`UNASSIGNED` 만), `created_at`. 두 id 가 같으면 거부함. 행 트리거(`ops.protect_interface_deployment`)로 UPDATE·DELETE 를 막음
- V20: 표를 새로 만들지 않고 인덱스 둘(`work_result_unacknowledged`(`work_result(receipt_id, version)`, 부분 조건 `acknowledged_at IS NULL`), `work_request_state_received`(`work_request(state, received_at DESC, receipt_id)`))만 만듦. 파일 `V20__work_request_list_indexes.sql`. 쓰임: 수신 미확인 결과 찾기, 상태 필터·상태별 집계. 실행 계획 측정은 [docs/measurements/2026-10-14-load-after-delivery.md](measurements/2026-10-14-load-after-delivery.md)
- V21: `service_instance`(운영 서비스 인스턴스와 심장박동)를 만듦. 파일 `V21__service_instance_heartbeat.sql`. 인스턴스마다 한 행이고 그 프로세스만 고침. 칸 `instance_id`·`started_at`·`last_seen_at`·`expires_at`·`stopped_at`, 시각은 모두 DB `clock_timestamp()`. 덧붙이기 전용 표가 아님. 그리고 `station_run` 에 `instance_id`(상태를 마지막으로 쓴 인스턴스, V21 이전 행은 NULL)를 더함
- V22: `ops.work_request`에 `source_confirmations_closed_at` 열을 더함. 파일 `V22__work_request_source_confirmation_round.sql`. 출발 자리 확인 회차가 닫힌 시각을 기록하며, null은 회차가 닫힌 적 없음을 뜻함.
- V23: 표를 만들지 않음. 파일 `V23__supersede_closed_original.sql`. `ops.work_request_supersession`에 널 허용 열 `original_result_version`을 더함. `REJECTED`·`UNASSIGNED` 행은 결과 버전이 없고 `ACCEPTED` 행은 양의 결과 버전을 갖도록 CHECK 제약조건 `work_request_supersession_original_state_check`를 다시 정의함(NULL로 평가되는 CHECK는 통과하므로 `IS NOT NULL`을 명시함). 표 트리거는 UPDATE와 DELETE만 방지하므로 널 허용 열 추가는 안전함.

`mission` 스키마의 표는 다음과 같습니다.

- V1: `draft`, `mock_run`, `mission_version`(임무 버전)
- V2: `execution_journal`, `execution_journal_event`, `job_response_log`, `incident_copy`, `incident_copy_resolution`(S4b)
- V3: `draft.sample_order` 칸 추가
- V4: `result_producer`(생산자 id 와 마지막 순번을 든 한 행), `result_outbox`(순번이 붙은 결과 이벤트, 변경·삭제·통째 비우기 금지)
- V5: `execution_cancel`(운영자 실행 취소, 실행 호스트가 미들웨어 `cancel` 을 부르기 직전에 적음, `(instance_id, execution_id)` 유일), `robot_residual_hold`(잔여 파지 표시, `job_order_id` 유일), `robot_residual_hold_clearing`(표시 해소, `marker_id` 가 기본 키라 표시마다 많아야 하나). 셋 다 변경·삭제·통째 비우기 금지(V2 의 `mission_record_append_only()` 트리거), `approver_kind` 는 `'PERSON'` 만, 사유는 공백만이면 안 됨. 시각은 DB `clock_timestamp()`.
- V6: `execution_judgment_record`(판단 기록). 칸 `record_id`(기본 키), `job_order_id`(실행 일지 작업 지시 id 외래 키), `instance_id`, `execution_id`, `wire_version`(적을 때의 `JudgmentRecordWire.SCHEMA_VERSION`, 지금 `"1"`), `record`(TEXT, 받은 글자 그대로), `recorded_at`. 변경·삭제·통째 비우기 금지(V2 의 `mission_record_append_only()` 트리거). 시각은 DB `clock_timestamp()`.
- V7: `host_instance`(실행 호스트 인스턴스와 심장박동). 인스턴스마다 한 행이고 그 인스턴스만 고침. 칸은 ops V21 의 `service_instance` 와 같은 다섯(`instance_id`·`started_at`·`last_seen_at`·`expires_at`·`stopped_at`). 덧붙이기 전용 표가 아님.
- V8: `execution_journal_event` 에 `snapshot JSON`(NULL 가능) 칸 추가. 새 표 없이 표 수는 15 개 유지. CHECK(`execution_journal_event_snapshot_settled`: `snapshot IS NULL OR kind = 'SETTLED'`)로 종결 이벤트에만 `GET /host/executions` 실행 행(`ExecutionView` JSON) 본문을 남김. V8 이전 행과 다른 모든 이벤트는 NULL. 인시던트 사본과 마찬가지로 적힌 본문 글자 그대로라 키 순서를 보존하는 JSON(JSONB 아님) 사용. 종결 이력 페이징을 위한 부분 인덱스는 `execution_journal_event_settled`(`(event_id) WHERE kind = 'SETTLED'`, 커서는 `event_id`). 화면의 실행 목록은 미들웨어 메모리가 아니라 이 종결 이력에서 종결된 실행을 읽음(HA 설계 기록 §10, S3a JSON 계약 §5.8). 여전히 덧붙이기 전용 표(`UPDATE`·`DELETE` 만 막는 행 트리거 변경 없음).
- V9: 부분 색인 `execution_journal_event_settled_execution` `(instance_id, execution_id, event_id) WHERE kind = 'SETTLED'` 추가. 새 표나 칸 없이 표 수는 15 개 유지. 이 인스턴스가 이빅션한 실행을 인자 없는 `GET /host/executions` 와 좁힌 `GET /host/executions` 에 채우는 데(실행별 가장 최근 종결 본문) 씀. 여전히 덧붙이기 전용.
- V10: `execution_journal_revision`(받은 리비전). 이미 있던 실행의 `revise` 가 ACCEPTED 를 낸 버전마다 한 행이고 `(job_order_id, version)` 유일. 칸 `revision_id`(기본 키), `job_order_id`(실행 일지 작업 지시 id 외래 키), `version`, `job_order`(JSONB, 일지의 작업 지시와 같은 모양), `instance_id`, `execution_id`, `recorded_at`. 변경·삭제·통째 비우기 금지(V2 의 `mission_record_append_only()` 트리거). 시각은 DB `clock_timestamp()`. 표 수는 16 개. `execution_journal_event` 의 이벤트 종류에 `REOPENED`(종결을 적은 실행이 다시 실행됨, 실행 id 필수, `detail` 은 그때의 물리 상태)를 더함. CHECK `execution_journal_event_kind_check` 는 다섯 종류로 다시 만들고, 실행 id CHECK 는 `execution_journal_event_check` 를 `execution_journal_event_execution_check`(`RESTORED`·`SETTLED`·`REOPENED` 에만 실행 id)로 바꿈.
- V11: `execution_fault_release`(운영자 결함 수용, 수용마다 한 행, 실행 호스트가 picasso `release` 를 부르기 전에 적음, 적기에 실패하면 `release` 를 부르지 않음: 500 `RECORD_WRITE_FAILED`). 칸 `release_id`(기본 키), `instance_id`, `execution_id`, `job_order_id`(실행 일지 작업 지시 id 외래 키), `faults`(JSONB, 수용한 막는 결함 전부, 비어 있지 않은 배열만), `approver_id`, `approver_kind`(`'PERSON'` 만), `reason`(공백만이면 안 됨), `request_id`(UUID, 유일, NULL 가능), `recorded_at`. 색인 `execution_fault_release_order`(`job_order_id`, `release_id`). 변경·삭제·통째 비우기 금지(V2 의 `mission_record_append_only()` 트리거). 시각은 DB `clock_timestamp()`.
- V12: `execution_close`(실행 호스트가 복원하지 못한 실행의 운영자 종결, 종결당 한 행, 일지 이벤트 `CLOSED`·송신 기록 행·결과 outbox 이벤트와 한 트랜잭션에서 기록)를 만듦. 열은 `close_id`(기본키), `event_id`(이벤트 외래키, 유일), `job_order_id`(일지 외래키, 유일하여 작업 지시당 한 행), `instance_id`(종결 인스턴스), `closed_instance_id`, `closed_execution_id`, `robot_id`, `restore_result`(`GAVE_UP`·`DEFERRED`), `restore_reason`, `basis`(`SNAPSHOT`·`ATTESTED`), `approver_id`, `approver_kind`(`'PERSON'`만 허용), `reason`(빈 값 불가), `request_id`(UUID, 유일, NOT NULL), `response_id`(`close-<event_id>`), `recorded_at`임. 수정·삭제·TRUNCATE 불가(V2 `mission_record_append_only()` 트리거). 시각은 DB `clock_timestamp()`. 표는 18개가 됨. 또한 `execution_journal_event_kind_check`를 6개 종류로 다시 정의함(`CLOSED` 추가. `CLOSED`는 실행 id가 없고 `detail`은 종결 근거임).
- V13: `mission.execution_close_confirmed`를 만듦. 열은 `close_id`(기본키이자 `execution_close` 외래키), 확인한 인스턴스의 `instance_id`, `confirmed_at` 임. 수정·삭제·TRUNCATE 불가(V2 `mission_record_append_only()` 트리거). 종결당 최대 1개 행이며 다른 인스턴스의 두 번째 쓰기는 무시함. 표는 19개가 됨.

마이그레이션 수와 표 수는 `ops-service/src/main/resources/db/ops/` 와 `mission-host/src/main/resources/db/mission/` 의 SQL 파일을 세고 `CREATE TABLE`·`CREATE VIEW` 를 세어 얻은 값입니다.

### 핵심 경로

핵심 경로는 다음과 같습니다.

- 장애 주입: 화면 `/api/faults` → 운영 서비스 `/host/faults` → 실행 호스트 → 현장 루프백 `/faults` → 잠금 아래 mimic 엔진 직접 호출
- 신호 쓰기: 화면 `/api/cell/signals/{name}` → 운영 서비스 `/host/cell/signals/{name}` → 실행 호스트 → 현장 `/cell/signals/{name}`
- 외부 주문: Mock MES `POST /integration/v1/job-orders`(Bearer 토큰) → 운영 서비스가 `work_request` 를 먼저 커밋 → 입력 변환·배포 고정 → 임무에 필수 자원이 있으면 스테이션 대기열, 없으면 실행 호스트 `POST /host/job-orders`
- 화면 작업 요청: 운영 화면 `POST /api/work-requests/manual`, 호출 계약 `POST /api/mission-interfaces/{id}/{version}/invoke`, 기존 경로 `POST /api/job-orders` 가 모두 같은 작업 요청 경로를 지남
- 작업 지시 배정: 운영 서비스가 배정 가능 기체만 후보로 선정 → 실행 호스트의 picasso 미들웨어가 하나 선택 → mimic 기체에서 실행
- 결과 회신: 실행 호스트가 작업 응답을 `mission.result_outbox` 에 순번과 함께 기록 → 운영 서비스가 5초마다 `GET /host/result-events` 를 커서로 읽어 `ops.work_result` 버전으로 저장 → 콜백 설정이 있으면 2초마다 루프백 Mock MES 로 전달 → Mock MES 가 `POST /integration/v1/job-orders/{id}/results/{version}/ack` 로 수신 확인
- 공유 스테이션: 운영 서비스 스테이션 워커가 2초마다 `tick` → 대기열 맨 앞 작업이 스테이션을 점유 → 셀 대역의 준비·완료 신호를 실행 호스트 `GET /host/cell` 로 읽음 → 조건이 맞으면, 작업 번호 쌍이 있는 스테이션은 작업 번호를 `POST /host/cell/signals/{name}` 으로 먼저 쓴 뒤 작업 요청 제출
- 작업 허가: 셀 대역 `GET /cell`(250ms) → 실행 호스트 `CellWorkPermits.observe`(호스트 잠금 아래) → 미들웨어 발행 게이트가 새 로봇 명령 자리마다 `read()` → 막히면 실행의 `permitBlock` → `GET /host/executions` → 운영 서비스 `GET /api/executions?recent=20`·`GET /api/work-requests/{id}` 가 그대로 넘김 → 화면. 화면은 `GET /api/executions?recent=20`(운영 화면: 남은 실행과 가장 최근 종결 이력 20건)과 `GET /api/executions/summary`(운영 개요: 건수만)를 읽음. `permitBlock`은 `?recent=20`의 남은 실행 행에 담겨 화면에 닿음. 스테이션 조정은 `GET /host/work-permits` 를 `tick` 마다 잠금 밖에서 한 번 읽음.
- 기체 퇴역: registry 진단 읽기 `GET /diag/robots?retired=true`(`host.retirement.read-interval`, 기본 `PT2S`) → 실행 호스트 `RegistryRetirements`(호스트 잠금 밖 스레드 스냅숏) → 미들웨어 발행 게이트가 새 로봇 명령 자리마다 `retired()` → 막히면 실행의 `permitBlock`(`INHIBITED`, `RETIRED`) → `GET /host/executions` → 운영 서비스 `GET /api/executions?recent=20`·`GET /api/work-requests/{id}` 가 그대로 넘김 → 화면 `PermitBlockNotice`. 실행 호스트가 비춘 퇴역은 `GET /host/retirements` 로 읽기만 하며 운영 서비스 경로는 없습니다.

### 기타 구성 및 설정

- 모의 실행: 실행 호스트 내부의 별도 mimic과 가상 시계로 표본 작업 지시 하나를 실행합니다. 현장 기체, 셀 대역, registry 에는 접근하지 않으며 프로파일로 `mission-host/mock-run/humanoid-a.json` 을 사용합니다. 또한 모의 실행의 이상적 현장이 표본 작업 지시 수락 전에는 목적지 슬롯을 빈 것으로, 수락 뒤에는 점유된 것으로 응답하도록 바꾸어 게이트에서 목적지 관측을 요구한(`REQUIRED`) 정의도 모의 실행을 통과하고 근거는 종전처럼 서도록 했습니다.
- 작업 허가 대응: 실행 호스트 설정 `host.work-permit.mapping`(작업 디렉터리 기준 JSON 파일 경로, `mission-host.properties` 기본값 빈 문자열)으로 선언합니다. 비면 선언하지 않은 현장이고 미들웨어에 허가 포트를 붙이지 않아 발행 거동이 오늘 그대로입니다. 경로가 있는데 파일을 못 읽거나 모양이 틀리면 기동이 실패합니다. 파일 모양은 `{"validFor":"PT10S","signals":[{"signal":"guard_closed","permittedWhen":"true","robots":["humanoid-01","quadruped-01"]}]}` 입니다. `validFor` 는 선택(기본 `PT10S`), 모르는 칸 거부, 신호 이름 겹침 거부, 줄마다 기체 하나 이상입니다. `.env` 키가 아닙니다. 실행 호스트 안 모의 실행의 별도 미들웨어에는 허가 포트를 붙이지 않습니다. 판독의 관측 시각은 실행 호스트가 셀 대역을 읽은 호스트 시각(잠금 대기 시간 포함)이고, 관측이 `validFor` 를 넘기면 `EXPIRED` 모름으로 막습니다. 근거: `WorkPermitHostTest` 의 `허가 대응을 선언하지 않으면 안전 신호가 막아도 포트를 붙이지 않고 오늘처럼 낸다`, `CellWorkPermitsTest` 의 `대응 파일은 칸을 엄격히 읽고 validFor 가 빠지면 기본값이다`.
- 기체 퇴역 읽기: 실행 호스트 설정 `host.registry.url`(`mission-host.properties` 기본값 `http://127.0.0.1:${REGISTRY_PORT}`)과 `host.retirement.read-interval`(기본값 `PT2S`)로 선언합니다. 주소를 비우면 읽지 않고 미들웨어에 포트를 붙이지 않아 발행 동작이 지금 그대로입니다. 한 번도 못 읽었으면 퇴역이 없어 아무것도 막지 않고, 한 번 읽은 뒤 실패하거나 본문 모양이 틀려도 마지막으로 읽은 퇴역을 유지하며 실패 까닭을 남기고 registry 가 늦게 떠도 기동은 멈추지 않습니다. `.env` 키는 늘지 않습니다(`REGISTRY_PORT` 는 이미 있음). 조회 엔드포인트는 `GET /host/retirements` 입니다. 근거: `RegistryRetirementsTest` 의 `주소가 비면 읽지 않고 붙이지 않는다`·`한 번 읽은 뒤 registry 가 안 닿으면 마지막 퇴역을 그대로 두고 실패를 남긴다`·`한 번도 못 읽었으면 아무 기체도 퇴역으로 두지 않는다`·`본문 모양이 틀리면 퇴역 없음으로 읽지 않고 실패로 남긴다`, `RetirementHostTest` 의 `registry 주소가 비면 퇴역 포트를 붙이지 않는다`·`registry 가 안 닿으면 퇴역으로 막지 않고 실패를 조회에 남긴다`.
- 인시던트 고리: 실행 호스트 설정 `host.incident-loop.entitlements`, `host.incident-loop.approval-port`, `host.incident-loop.export`로 선언합니다. 기본값은 모두 빈 문자열이며 `.env` 키는 늘지 않습니다. 셋 다 비우면 붙지 않아 지금 동작 그대로 유지됩니다. 자격 선언 파일을 읽지 못하거나 자격 선언 없이 승인 포트를 열면 기동이 멈춥니다. 승인으로 새 실행이 서면 실행 일지(`mission.execution_journal`)에 적으며, 적지 않으면 pump와 제출이 막힙니다. 실행이 없는 작업 지시가 일지에 이미 있으면 `REMEDY_NOT_APPLIED`, 일지가 막혀 있으면 `REFUSED_BY_GATE`로 거부합니다. pump마다 호스트 잠금 아래에서 인시던트 대장(`incidents.jsonl`)·조치 탐색 대장(`remedy-searches.jsonl`)·안내(`manifest.json`)·작업 응답(`job-responses.jsonl`)을 씁니다. 미들웨어 목록 축소에 대비해 프로세스 메모리에 덧붙이기 사본(`IncidentExport`)을 유지하며 쓰기 실패는 pump를 멈추지 않습니다. 인증 없는 개념 증명이며 picasso §15.176과 §15.203 한계를 닫지 않습니다. 근거: `MissionHost.attemptApproval`, `IncidentExport`, `ApprovalWindow`, `IncidentLoopHostTest`.
- 서브모듈: `picasso 16eabb8` 커밋(조직 저장소 picasso PR #20 머지, 2026-11-13)으로 고정된 읽기 전용 서브모듈입니다. 버전 카탈로그 `picasso/gradle/libs.versions.toml` 을 공유하며 picasso 라이브러리 측 수정은 picasso 저장소 PR 로 반영합니다. 고정 커밋은 picasso main `16eabb8` 와 같습니다. `4d6d768` 뒤로 PR #97(adapter-host 잠금), #98(판단 기록·단위의 기체 바인딩), #99(다이어그램 용어), #100(리빌드한 운영자 보류의 인시던트 재오픈), #101(다시 연 인시던트의 `at` 과 트레이스 줄 시각 일치, 판단 기록 영속 한계 행 닫음)이 들어 있습니다. `faa8ccc` 뒤로 PR #102(판단 기록 KDoc 의 설계 일지 절 번호 수정: §15.213 → §15.221), PR #103(`Middleware.forget`·`ForgetOutcome`·`Middleware.FORGOTTEN_JOB_ORDER`, `executionSeq` 의 실행 id 재사용 없음)이 들어 있습니다. `7d0fa8f` 뒤로 PR #104(문서: 이빅션 소비자 한계 행 닫음, 오케스트레이션 기록), #105(문서: KDoc 링크 수정, 배선도 노드 이름 `chainRefusal`), #106(승인 시점 연쇄 거부 시 기존 제안 유지, 이미 실행이 있는 작업 지시의 `adopt` 리비전 경로 직행: 동일 버전 멱등·낮은 버전 거부·높은 버전은 현재 기체에서 해당 실행 리비전·후보 목록 미참조), #107(단위 ID 중복 계획의 `submit` 및 리비전 거부 접두사 `Middleware.DUPLICATE_UNIT_ID`, 새 임무 검증기 거부 종류 `SOURCE_OBSERVATION_UNUSED` 추가로 11종), #108(스트림 수정: `RobotPort.release`, 세션 변경 시 커서 초기화, 동일 멱등 키 패리티, adapter-host의 취소된 감시 스트림 정리), #109(`adopt`의 모든 후보 게이트 통과 실패 시 조치 탐색 기록과 제안 레지스터에 각 후보 거부 기록, `Submission.Rejected`에 `approvalRefusal`·`consumedApproval` 추가, `release`가 true 반환 시 작업 응답 발행)이 들어 있습니다. `ddca127` 뒤로 PR #110은 하위 자체 취소 시 운영자 보류와 인시던트 전환 및 보류·결함 차단 해소 시 작업 응답 송신을 추가했고, PR #111은 리빌드한 실행의 첫 pump 전에 취소할 때 기체 스냅숏 태스크에 `StartTask` 없이 붙어 취소하는 처리를 추가했습니다. `955af4f` 뒤로 PR #112는 문서 용어를 정리했고, PR #113은 사람에게 보이는 코드 문자열의 용어를 정리하며 거부 사유 상수(`FORGOTTEN_JOB_ORDER`·`RESUME_SNAPSHOT_UNREADABLE`·`RESUME_RECORD_MISMATCH`)의 글자를 바꿨습니다. `aada343` 뒤로 PR #114는 `InspectAsset`에서 대상으로 가는 이동이 실패하면 그 대상의 점검을 태스크 없이 `PREDECESSOR_NOT_DONE` 실패로 거르고 순회를 다음 대상으로 잇도록 바꿨고, PR #115는 임무 정의의 단위 노드에 목적지 관측 선언(`destinationObservation`)을 추가하여 검증기 거부 종류를 13종으로 늘리고 게이트가 `REQUIRED` 로봇 단위에 한해 설비에 목적지를 묻도록 확장했습니다. `b853f7e` 뒤로 PR #116은 프로덕션 코드 문자열에 용어집의 새 명칭을 반영하고 레지스트리 및 검증기 안내 문구를 정비했으며, PR #117은 순회가 `PARTIAL` 또는 `FAILED`로 종결된 뒤 `PREDECESSOR_NOT_DONE`으로 건너뛴 점검 단위의 재작업(`REWORK`)을 허용하여 선행 이동과 점검 단위를 새 시도로 되돌리고 실행을 `RUNNING`으로 복귀시키도록 확장했습니다. `70083fd` 뒤로 PR #118은 `Middleware.pump`의 실행 및 기체 동기화 단위 예외 격리와 `PUMP_FAILED` 트레이스, 취소 전송 실패 시 `CANCEL_UNSENT` 기록 및 닿을 때까지 매 라운드 재전송(`CANCEL_RESENT`)을 도입하고 `Middleware.cancel`의 예외 전파를 없앴으며, PR #119는 미확정(`IN_DOUBT`) 단위를 유예(`inDoubtGrace`) 내 최소 2초 간격으로 조회하여 로봇 스냅숏의 동일 리비전 확인 시 `StartTask` 없이 부착(`DOUBT_ATTACHED`)하고 취소 핸들 미확보 시 운영자 보류에 머물도록 변경했습니다. 실행 호스트의 두 `middleware.cancel` 호출은 새 계약에 이미 부합하며 새 트레이스나 `CancelReport`를 직접 참조하지 않아 코드 변경이 필요하지 않았습니다. `fb7b2e8` 뒤로 PR #120은 현장 시계 동기화 환경 전제(K)와 허용 폭을 문서화했고, PR #121은 미들웨어가 로봇 스냅숏 왕복에서 측정한 발신자의 시계 차가 임계(근거 시간 윈도우의 좁은 쪽 절반)를 넘을 때 판정 변경 없이 `CLOCK_SKEW` 트레이스와 `UNVERIFIED` 메모를 기록하도록 했으며, PR #122와 PR #123은 picasso 한계 레지스터에 새 행을 추가했습니다. 실행 호스트는 `RobotSnapshot`을 직접 생성하지 않고 `HostClock`이 에뮬레이터를 1초 이내, 곧 임계 미만으로 유지하므로 코드 변경이 필요하지 않았습니다. 실행 호스트는 이미 실행이 있는 작업 지시의 실제 개정에 대해서만 해당 실행의 기체를 판정하고 비개정 건은 picasso에 그대로 넘기며, 기동 복원 시에는 PR #111에 따라 리빌드 직후 기록된 운영자 취소를 곧바로 다시 겁니다. `30df704` 뒤로 PR #124는 마지막 스냅숏 관측 뒤 `LogicalCapability.linkLossWindow`(기본 30초)를 넘기면 `LINK_BROKEN` 트레이스를 남기고 실행을 `IN_DOUBT`로 바꾸며 갱신 스트림이 영구히 닫힐 때(`RobotPort.unfollowable`, 기본 구현은 `null`) 단위를 `IN_DOUBT`로 두고 `WATCH_CLOSED` 트레이스를 남기도록 했고, PR #125는 미확정 단위의 취소를 키로 지은 핸들로 보내고 미전송 취소로 운영자 보류에 둔 실행은 스냅숏에 태스크가 보이면 취소를 다시 보내도록(`CANCEL_RESENT`) 했으며, PR #126은 `ClientRobotPort`가 단건 호출의 `DEADLINE_EXCEEDED` 뒤 그 기체의 단건 호출을 차단 창(`backoff`, 기본 30초) 동안 하위로 보내지 않고 바로 `UNAVAILABLE`로 실패시키도록 하고 생성자에 기본값 있는 `now`와 `backoff` 매개변수를 더했습니다. PR #127은 운영자 재작업이 새 시도를 낼 때 단위의 시도별 관측 칸(파지 어긋남, 결함, 도달 근거, 진행률 등)을 비우고 앞 시도에서 자재를 쥔 기록은 재할당 거부에만 남도록 했으며, PR #128은 재작업 직후 다음 라운드 전에 리비전이 와도 단위의 시도 번호와 앞 시도 파지 기록을 새 계획의 단위로 옮기도록 했습니다. `86dcf71` 뒤로 picasso PR #129와 #130이 머지되어 picasso main이 `3b5d6c5`가 되었으며, PR #129는 결함 수용으로 쥔 키(결함 유형과 참조)를 그 기체의 스냅숏에서 결함이 걷히거나 결함 걷힘(`FAULT_CLEARED`) 재생 이벤트가 오면 놓고 `ACKNOWLEDGED_FAULT_CLEARED` 트레이스를 남기도록 하여(스냅숏을 못 읽은 라운드에는 놓지 않음) 판단 기록은 그대로 `acknowledgedFaults`를 싣되 키를 놓으면 그 칸이 줄어들고 그 뒤 같은 키로 다시 생긴 결함은 새 결함으로 다시 막도록 했습니다. PR #130은 새 포트 `RetirementPort`와 `Middleware(retirements)`(기본 `null`)를 추가하여, 이를 붙이면 발행 게이트가 퇴역한 기체의 새 로봇 명령을 보류하고(`Execution.permitBlock`, 까닭 `RETIRED`) 재할당이 퇴역한 후보를 거르되 도는 단위는 끊지 않도록 했습니다. 새 공개 면이 모두 기본값이 있어 서브모듈 이동 때는 실행 호스트 코드 변경이 필요하지 않았습니다. 그 뒤 브랜치 `feat/retirement-port`가 실행 호스트에 `RegistryRetirements`로 `RetirementPort`를 붙였습니다(흐름과 설정은 이 문서의 데이터 흐름 목록의 «기체 퇴역» 줄과 설정 목록의 «기체 퇴역 읽기» 줄). 운영 서비스의 배정 가능 판정은 지금처럼 퇴역 기체에 (퇴역한 기체다) 사유를 붙이므로 이 보류가 드러나는 것은 제출 뒤 퇴역한 기체의 실행입니다. 실행 호스트는 `ClientRobotPort`를 기본 시계(벽시계)로 만들고 운영 환경의 `HostClock`도 벽시계라 차단 창과 미들웨어 판정을 같은 시계로 재며, 모의 실행기(`MockRunner`)는 미들웨어에 하네스의 가상 시계를 넣지만 `ClientRobotPort`에는 넣지 않아 차단 창은 벽시계로 재고 in-process 하네스에서는 기한 초과가 나지 않아 그대로 두었습니다. 실행 호스트가 #91 의 `PhysicalState.occupiesRobot`(판정)·`pumps`(취소 대상)와 #96 의 `WorkPermitPort`·`Middleware(permits)`·`Execution.permitBlock`, #98 의 `Middleware.judgmentRecord`·`Middleware.resume(…, record)`·`JudgmentRecordWire`, #103 의 `Middleware.forget`·`ForgetOutcome`·`Middleware.FORGOTTEN_JOB_ORDER`, #130 의 `RetirementPort`·`Middleware(retirements)` 를 씁니다. `3b5d6c5` 뒤로 옛 저장소 picasso PR #131~#180이 머지되었고, 조직 저장소로 옮긴 뒤 조직 저장소 PR #1(README의 저장소 주소 갱신)이 머지되어 picasso main이 `51d8230`이 되었습니다. PR #131~#141(2026-11-02~04)은 미들웨어의 취소·리빌드 수정으로, 그중 PR #134는 판단 기록 `JudgmentRecord`에 `cancelRequested` 칸을 더하고 `JudgmentRecordWire.SCHEMA_VERSION`을 1에서 2로 올렸으며, 버전 1 기록은 취소가 걸리지 않은 기록으로 읽고 `Middleware.resume`은 `cancelRequested`가 참인 기록을 받으면 실행을 취소가 걸린 상태(`CANCELING`)로 다시 세웁니다. 실행 호스트는 판단 기록을 picasso의 글자 그대로 적고 읽으므로 코드 변경이 없으며, `JudgmentRecord`를 직접 만드는 실행 호스트 시험 `JudgmentRecordHostTest`에 `cancelRequested = false`를 넣었습니다. PR #142와 #147(2026-11-05)은 `uplink`에 비동기 적재 큐 `QueuedIngest`를 넣어 `RegistryLink.http`의 태스크 적재, 생존 보고, 핸드셰이크 보고를 작업 스레드 하나가 보내도록 했고, 큐에 5초 넘게 머문 생존 보고는 버립니다. 현장 런처 `Site`가 이 연계를 직접 만들어 들고, `advance`·`advanceTo` 뒤 잠금 밖에서 `RegistryLink.drain`(최대 10초)으로 큐가 빌 때까지 기다립니다. 기다리지 않으면 시계를 민 직후 registry에 생존 보고가 아직 없어 현장 런처 시험 `SiteTest`·`SiteRunnerTest`가 실패했습니다. 닫을 때는 `MimicCli.Started.close`로 서버를 내리고 큐를 비웁니다. PR #144(2026-11-05)는 계약에 `FaultObservability`를 더해 계약 SemVer를 0.10.0으로 올렸으며, registry는 기동 때 스킬 종류를 지금의 계약 SemVer로 동기화하므로 새 데이터베이스에서는 모든 스킬의 도입 SemVer가 0.10.0이고 계약 SemVer 0.9.0으로 선언한 어댑터 빌드를 바인딩하면 `CONTRACT_TOO_OLD`로 거부됩니다. 통합 시험(`Commissioned`, `CommissioningTest`)과 Playwright 시험(`lifecycle.spec.ts`, `station.spec.ts`)의 빌드 선언을 0.10.0으로 바꾸고 `CommissioningTest`의 카탈로그 계약 SemVer 기대값을 0.10.0으로 바꿨으며, 운영 서비스와 화면 코드는 바뀌지 않았습니다. PR #148~#152와 #158~#162(2026-11-05~07)는 소비자 잠금과 호스트 잠금 밖으로 읽기와 명령 송신을 옮겼고(`IssuingRobotPort`, `PrefetchingFleetPort`, `IssuingFleetPort`, `RobotPort.capabilitiesPending` 등), PR #177과 #179는 미들웨어 메모리 목록에 상한(`Retention`)을 두었으며, PR #140과 #180은 adapter-host 세션 id를 바꿨으나, 새 공개 면이 선택 메서드이거나 기본값이 있어 실행 호스트 코드 변경이 필요하지 않았습니다. PR #155(2026-11-06)는 Testcontainers를 1.21.4로 올렸고, 서브모듈 카탈로그를 공유하는 이 저장소의 시험도 1.21.4를 쓰므로 이 저장소 CI의 gradle 작업을 `ubuntu-24.04` 고정에서 `ubuntu-latest`로 되돌렸습니다. 내보내기 `manifest.schemaVersion`은 그대로 6이고 `contractSemver`는 0.10.0입니다(`51d8230`의 `handoff/narrator/run-1/manifest.json`). 조직 저장소 PR #2~#20(2026-11-10~13)은 미들웨어 수명주기 재설계와 의존 정비(S0~S4c, 층 시험, H1)로, 실행 호스트가 쓰는 공개 API는 소스 호환으로 남았고 S3가 `ExecutionUnit`의 `hold` 세터를 닫아 `ExecutionControlTest`의 직접 생성 한 곳만 생성자 인자 `hold`로 고쳤습니다(picasso `handoff/mission-ops/close-setters.md`). H1이 낸 `host-kit` 모듈은 아직 가져오지 않았습니다. 내보내기 `manifest.schemaVersion`은 그대로 6이고 `contractSemver`는 0.10.0입니다(`16eabb8`의 `handoff/narrator/run-1/manifest.json`).
- 환경 변수(`.env`): `SITE_ID`, `PICASSO_DB_URL`, `PICASSO_DB_USER`, `PICASSO_DB_PASSWORD`, `REGISTRY_PORT`, `OPS_PORT`, `MIMIC_GRPC_PORT`, `SITE_CELL_PORT`, `HOST_PORT`, `PICASSO_OPERATOR_TOKEN`, `PICASSO_INGEST_TOKEN`. 실제 값은 커밋된 `.env` 를 따릅니다.
- `.env` 밖의 선택 환경 변수(운영 서비스): `PICASSO_INTEGRATION_TOKEN`(비면 외부 주문 API 꺼짐), `PICASSO_INTEGRATION_SOURCE`(기본 `mock-mes`), `PICASSO_LOCAL_CALLBACK_URL`(루프백 IP 리터럴 http 주소만, 비면 콜백 전달 꺼짐), `PICASSO_LOCAL_CALLBACK_TOKEN`
- 운영 서비스 주기 속성(`ops-service.properties`): `ops.results.poll-ms=5000`(결과 수집과 멈춘 작업 요청 정리), `ops.requests.stale-seconds=300`, `ops.results.delivery-ms=2000`, `ops.results.delivery-batch`(콜백 전달 한 회차에 보내는 최대 수)는 속성 파일에는 없고 코드 기본값 10, 1~100 밖이면 기동 거부(운영 서비스가 뜨지 않음), 스테이션 워커 `ops.stations.poll-ms` 는 속성 파일에 없고 코드 기본값 2000
- 인스턴스 심장박동: 실행 호스트 `host.instance.heartbeat-period=PT5S`·`host.instance.heartbeat-expiry=PT30S`(`mission-host.properties`), 운영 서비스 `ops.instance.heartbeat-period=PT5S`·`ops.instance.heartbeat-expiry=PT30S`(`ops-service.properties`). `.env` 키가 아님. 만료는 주기의 세 배 이상이어야 하고 아니면 기동이 멈춤. 심장박동은 자기 스레드에서 돌아 pump·`tick` 이 길어도 늦지 않음. 기본값 근거: HA 설계 기록 §4.5 하한(주기의 세 배 + GC 정지 + DB 왕복)에 실측 없이 15초 여유. 근거 시험: `HostInstanceTest` 의 `심장박동 만료가 주기의 세 배보다 짧으면 기동하지 않는다`.
- 종결 보존: 실행 호스트 `host.settled.revisable-retention=PT5M`(`mission-host.properties`), `.env` 키가 아님. 기본값은 5분(소유자 결정)이며 설정 가능. `PHYSICALLY_DONE`·`ABORTED` 는 곧바로 이빅션함. `FAILED`·`UNVERIFIED` 는 종결 기록 커밋 시점부터 호스트 시계 기준으로 이 기간 동안 유지하여 같은 버전은 IDEMPOTENT 로 처리하고 더 높은 버전은 리비전으로 받음. 그 뒤에는 이빅션되며 해당 작업 지시 id 는 모든 버전에서 거부됨. 음수 값이면 기동이 멈춤. 근거 시험: `ForgetSettledTest` 의 `놓을 때는 끝난 실행은 곧이고 개정판을 받을 실행은 두는 폭이 지난 뒤이며 구동이 돌리는 실행은 놓지 않는다`. `FAILED` 실행의 건너뛴 대상 재작업도 이 보존 기간의 제약을 받으며, 화면 목록에는 `retainedUntil` 로 보존 만료 시각이 실림.
- 기체 명부: `site/robots.json` 에 `humanoid-01`(HA-0001, `humanoid-a.json`)과 `quadruped-01`(QB-0001, `quadruped-b.json`)이 정의되어 있습니다.

## 운영 흐름 상세

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="diagrams/operations-flow.dark.svg">
  <img alt="운영 흐름 다이어그램" src="diagrams/operations-flow.svg">
</picture>

운영 흐름의 전문은 [README 의 운영 흐름](../README.md#운영-흐름)에 기술되어 있습니다. 그림은 기체 도입(Day-1)과 운영(Day-2)의 화면 절차입니다. 외부 주문과 공유 스테이션을 포함한 작업 요청의 흐름은 이 문서의 [작업 요청과 결과 회신](#작업-요청과-결과-회신), [공유 스테이션 조정](#공유-스테이션-조정) 절에 있습니다.

설계 제안 §8.1에 따라 초안은 언제나 작성할 수 있으며, 활성화 단계에서만 강한 게이트 검증을 적용합니다.

## 주요 시퀀스

### 작업 지시

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="diagrams/sequence-job-order.dark.svg">
  <img alt="작업 지시 시퀀스" src="diagrams/sequence-job-order.svg">
</picture>

- 배정 가능 판정 분담: 운영 서비스는 시운전과 연결 상태를 판정하고, 실행 호스트는 스킬 적합, 진행 중인 실행(picasso `PhysicalState.occupiesRobot`, 종결하지 않은 실행과 부분 완료 `PARTIAL` 실행, 운영자 보류 포함), 복원 못 한 실행, 미해소 잔여 파지 표시, 현장 시간값 미적용, 실행 일지 차단을 판정합니다. 운영 서비스는 배정 가능한 기체만을 후보로 전달합니다. 운영 서비스는 작업 지시 id를 높은 버전으로 재사용하지 않아(항상 버전 1 전송) 리비전은 호스트 REST `POST /host/job-orders` 직접 호출로만 인입되며, 실행 호스트는 제 실행을 진행 중인 실행으로 세지 않고 현재 기체만 판정합니다. 현재 기체가 잔여 파지 표시나 다른 실행 점유 등으로 제외되면 `assign` 없이 `UNASSIGNED` 로 남지만, 개정 대상이 아닌 제출은 판정 없이 `assign` 에 넘어가 `IDEMPOTENT` 나 거부가 그대로 반환됩니다.
- 기록 시점: 화면의 작업 지시 폼은 `POST /api/work-requests/manual` 로 작업 요청을 만듭니다. 운영 서비스는 실행 호스트를 부르기 전에 `ops.work_request` 행을 `RECEIVED` 로 따로 커밋하고, 입력을 작업 지시로 변환해 저장한 뒤 `DISPATCHING` 으로 바꾸고 나서 `POST /host/job-orders` 를 부릅니다. 작업 지시 id 는 `JO-<작업 요청 id>` 로 작업 요청마다 고정됩니다. 실행 호스트는 새 실행 정보를 응답하기 전에 실행 일지에 먼저 기록합니다. 운영 서비스는 응답을 받은 뒤 조작 기록에 남기고 작업 요청 상태를 `ACCEPTED`, `UNASSIGNED`, `REJECTED`, `IN_DOUBT` 가운데 하나로 정합니다.
- 작업 응답 처리: 실행 호스트가 작업 지시를 받은 시점(`ACCEPTED`)에는 작업 응답을 만들지 않습니다. pump 가 mimic 에서 태스크를 시작하고, 실행이 종결하거나 보류될 때 작업 응답을 송신 기록(`job_response_log`)에 남깁니다. 처분이 송신인 작업 응답은 같은 트랜잭션에서 결과 outbox(`result_outbox`)에도 순번과 함께 기록됩니다.
- 재조회 동작: 응답이 없으면 운영 서비스가 1초 뒤 그 작업 지시로 좁힌 실행 목록인 `GET /host/executions?jobOrderId=<그 작업 지시>`를 한 번 재조회합니다. 목록에 그 작업 지시가 보이면 `ACCEPTED` 이고, 보이지 않아도 보내지 않았다는 증거로 보지 않아 `IN_DOUBT` 로 둡니다. 좁힌 재조회는 호스트가 이미 이빅션한 실행도 종결 본문에서 찾습니다.
- 같은 작업 지시 id 재제출: 재기동 전후 모두 picasso 메모리에 실행이 없더라도 실행 일지에 행이 있으면 호스트가 새 실행을 짓지 않고 거부합니다. 가르는 기준은 그 작업 지시의 종결·다시 실행·포기·운영자 종결(`CLOSED`) 이벤트 가운데 마지막 것입니다. 마지막이 종결이거나 운영자 종결(`CLOSED`)이면 사유가 `FORGOTTEN_JOB_ORDER` 로 시작하고(운영자 종결 사유는 종결된 인스턴스와 실행, `UNVERIFIED`를 담음) `forgottenJobOrder` 가 true 인 `REJECTED` 로 거부하고, 그 밖의 행(종결하지 않은 행, 종결 뒤 다시 실행된 행)이면 사유가 `JOURNALED_JOB_ORDER` 로 시작하고 false 인 `REJECTED` 로 거부합니다. 새 실행을 짓지 않으므로 실행 일지 쓰기 실패가 발생하지 않습니다. 제출마다 실행 일지 기본키 조회로 DB 문 1개가 추가되고, 행이 있을 때 이벤트 읽기 1회가 추가됩니다. 운영 서비스는 작업 요청마다 작업 지시 id 를 하나만 유지하므로 다시 할 작업은 새 작업 요청으로 나갑니다.

### 보류와 운영자 판단

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="diagrams/sequence-hold-decision.dark.svg">
  <img alt="보류와 운영자 판단 시퀀스" src="diagrams/sequence-hold-decision.svg">
</picture>

- 운영자 보류 전환: 운영자 보류 대기 템플릿 `ARRIVAL_WAIT_HOLD`의 설비 대기 단위가 20초 기한을 초과하면 단위 운영자 보류로 전환되고 인시던트가 봉인됩니다.
- 상태 기록: 해당 pump 직후 인시던트 사본과 보류 작업 응답이 호스트 DB `mission`에 기록됩니다.
- 운영자 판단 전달: 운영자 판단(완료 확인 또는 재작업, 사유 필수 입력) 시 상세 정보의 `instanceId`를 실어 운영 서비스에서 실행 호스트로 전달합니다.
- 인스턴스 불일치 검증: 실행 호스트 재기동으로 인해 인스턴스가 다르면 409 `INSTANCE_MISMATCH`를 반환합니다.
- 사유 기록 위치: 입력된 사유는 실행 호스트로 전달되지 않으며, 조작 기록에만 남깁니다.
- 판단 행(사본) 반영: 판단 행은 다음 pump 뒤 인시던트 사본에 반영됩니다. 그러나 판단 자체(picasso 판단 기록)는 판단 직후 실행 호스트가 `mission.execution_judgment_record` 에 적어 재기동 뒤에도 그 단위가 판단된 대로 섭니다. 근거: `JudgmentRecordHostTest` 의 `판단 기록은 수락 직후와 판정 뒤와 운영자 판단 직후에 적고 바뀌지 않으면 더 적지 않으며 정착하는 pump 가 마지막 판정을 적는다`.
- 장애 주입 처리: 진행 중 `pick_place` 스킬 실패와 같은 장애 주입은 대개 `GRASP_FAILED` 인시던트로 이어지며, 이는 운영자 판단 대상이 아닙니다.
- 재작업 게이트: 재작업(`REWORK`) 판단만 게이트를 지나고 완료 확인(`CONFIRM_DONE`)은 그대로 전달됩니다. 운영 서비스는 실행 호스트의 실행 목록에서 그 실행의 작업 지시를 찾아 작업 요청과 잇고, 그 작업 요청에 임무 필수 자원(`required_station_id`)이 있으면 공유 스테이션 조정을 거쳐서만 재작업을 전달합니다. 자세한 조건은 [재작업 게이트](#재작업-게이트) 링크로 안내합니다.

## 작업 요청과 결과 회신

이 절은 작업 요청의 상태 전이, 중복 방지와 배포 고정, 멈춘 작업 요청 정리, 그리고 실행 호스트의 결과 수집과 회신 메커니즘을 다룹니다. 관련 코드는 `ops-service/src/main/kotlin/dev/picasso/ops/service/requests/WorkRequests.kt` 에 있습니다.

### 작업 요청 상태

| 상태 | 뜻 | 다음 상태 |
|---|---|---|
| `RECEIVED` | 행을 막 커밋한 상태입니다. 호스트 호출 전에 반드시 `DISPATCHING` 을 먼저 기록하므로 `RECEIVED` 는 호스트에 보내지 않은 것이 확실합니다. | 입력 검증 거부나 내부 오류면 `REJECTED`(내부 오류 상세: 호스트에 보내지 않았으니 새 요청 ID 로 다시 보내라는 안내), 임무에 필수 자원이 있으면 `WAITING_RESOURCE`, 아니면 `DISPATCHING` 입니다. |
| `WAITING_RESOURCE` | 임무 필수 자원의 스테이션 대기열에 들어간 상태입니다. 스테이션 작업(`station_run`, 상태 `QUEUED`)과 같은 트랜잭션에서 기록합니다. | 스테이션이 준비되면 `DISPATCHING`, 스테이션 작업이 `TIMED_OUT`·`CANCELLED`·`REJECTED` 가 되거나 운영자가 현장 확인 후 해제(`CONFIRM_CLEAR`)하면 `REJECTED` 입니다. |
| `DISPATCHING` | 실행 호스트에 제출하는 중입니다. 이 뒤의 중단은 보냈는지 알 수 없습니다. | 제출 결과에 따라 `ACCEPTED`·`UNASSIGNED`·`REJECTED`·`IN_DOUBT` 입니다. 제출 직전 자원 권한 확인이 거부되면 `REJECTED`(호스트 호출 전이라 보내지 않은 것이 확실)입니다. 현장 확인 후 해제되면 `IN_DOUBT` 입니다. 오래 멈추면 정리가 `IN_DOUBT` 로 닫습니다. |
| `ACCEPTED` | 실행 호스트가 작업 지시를 받은 상태입니다(제출 응답이나 관측으로 확인). `ACCEPTED`·`IN_DOUBT` 는 `detail` 을 바꾸지 않아 제출 전 문장(«공통 작업 요청 검증 완료», «필수 자원 확보 및 설비 준비 확인», «운영자 미배정 재평가: <사유>», «출발 자리 확인 후 다시 제출: <출발 자리> = <확인한 신원> (<행위자>): <사유>»)이 남습니다. | 종료 상태입니다. 결과는 결과 버전으로 따로 쌓입니다. |
| `REJECTED` | 보내지 않았거나 실행 호스트가 거부한 상태입니다. 실행 호스트가 거부(호스트 200 `REJECTED` 또는 호스트 4xx)하면 `detail` 을 «호스트 제출 거부: <rejectionReason>»(사유가 비면 «호스트 제출 거부: 사유 없음», 2000자에서 자름)로 바꿉니다(전에는 제출 전 문장이 남음). 구조는 `submission.outcome` 에 그대로 남고, 상세 API `GET /api/work-requests/{id}` 가 `rejection {reason, remedy, remedyWithheld, alternativeLocations, sourceMissing, sourceUnobserved, forgottenJobOrder}` 으로 싣습니다(호스트가 거부한 `REJECTED` 일 때만 있고 입력 검증, 제출 직전 권한, 배포 변경, 멈춤 정리, 스테이션 종료로 닫힌 `REJECTED` 와 다른 상태는 null, 이들은 제 `detail` 그대로). `sourceUnobserved` 는 출발 미관측으로 거부한 `REJECTED` 일 때 값이 있고, 지금 제출 경로에서는 나오지 않습니다(출발 미관측은 `UNASSIGNED` 로 옵니다). `forgottenJobOrder` 는 호스트가 이미 끝나 이빅션한 작업 지시를 거부했을 때 true 이고(화면에 `FORGOTTEN_JOB_ORDER_TEXT` 안내가 붙음), 그 외의 경우나 해당 필드가 없는 이전 저장 본문에서는 false 입니다. | 종료 상태입니다. 상위 시스템이 대체한 앞선 작업 요청도 상태·`detail`·`submission` 이 그대로입니다. |
| `UNASSIGNED` | 배정 가능 기체가 없어 제출하지 못한 상태입니다. 두 갈래로 나뉩니다. 전송 전 미배정(운영 서비스 판정에 배정 가능 기체 없음, 호스트 안 부름, 제출 기록 없음)은 `detail` 이 전과 같은 판정 문장(`humanoid-01: 이유; 이유 / quadruped-01: 이유` 꼴)입니다. 호스트 미배정(호스트가 200 `UNASSIGNED`)은 `detail` 을 «호스트 미배정: <robotId>: <reason> / <robotId>: <reason>; <reason>» 로 바꿉니다(앞은 `refusals` 가 기체마다 낸 사유, 뒤는 `excluded` 로 호스트가 판정에서 뺀 기체 이유를 `; ` 로 이음. 둘 다 없으면 «호스트 미배정: 기체별 사유 없음», 2000자에서 자름). 기체별 사유 구조는 `submission.outcome.refusals[]` 와 작업 요청 상세 API(`GET /api/work-requests/{id}`)의 `unassigned` 칸에 실립니다. 제출 기록이 있으므로 미배정 재평가 대상이 아닙니다. 호스트 미배정의 기체별 사유에 출발 미관측이 있으면 상세 API 의 `pendingSources`(출발 자리마다 `{source, material, occupied, robotIds}`)가 비지 않습니다. 운영자의 출발 자리 확인(`POST /api/work-requests/{id}/source-confirmations`, 본문 `requestId`·`source`·`identity`·`reason`·`expectedUpdatedAt`)만 같은 작업 지시 id 로 다시 보냅니다. 자동 재제출은 없습니다. 결품(`sourceMissing`)과 전송 전 미배정은 409 `SOURCE_NOT_PENDING` 입니다. 오류 이름은 `BAD_SOURCE_CONFIRMATION`(400), `MODE_NOT_ALLOWED`(403), `STALE_SOURCE_CONFIRMATION`·`SOURCE_NOT_PENDING`·`SOURCE_CONFIRMATION_CONFLICT`·`RESOURCE_MANAGED`(409)입니다. 새로 기록하면 202, 같은 요청 id 에 같은 내용이면 200 `duplicate:true` 입니다. 이 작업 요청의 이후 제출(미배정 재평가 포함)은 마지막으로 회차가 닫힌 시각 이후에 기록된 확인만 출발 자리마다 최신 하나씩 싣습니다. 확인을 받으면 `DISPATCHING` 으로 잡을 때 앞 호스트 답(`submission`)을 비웁니다(그 답은 확인 행의 `context` 에 남습니다). 그래서 다시 낼 때 배정 가능한 기체가 없으면 전송 전 미배정(제출 기록 없음)이 되어 회차가 닫히며, 이후 미배정 재평가는 이전 확인을 싣지 않아 실행 호스트가 다시 출발 미관측으로 답하고 사람이 다시 확인해야 합니다. 확인 이력은 상세 API 칸 `sourceConfirmations` 에 있습니다. | 제출 기록과 실행 관측이 없는 `UNASSIGNED` 만 운영자 미배정 재평가(`POST /api/work-requests/{id}/recover`)로 `DISPATCHING` 이 됩니다. 필수 자원이 있는 작업 요청은 이 API 가 409 `RESOURCE_MANAGED` 로 막고 공유 스테이션 조정만 재평가합니다. 출발 자리 확인을 받으면 같은 작업 지시 id 로 `DISPATCHING` 이 됩니다. 상위 시스템이 대체한 앞선 작업 요청은 상태·`detail`·`submission` 이 그대로이고, 미배정 재평가(`recover`)와 출발 자리 확인(`source-confirmations`)이 409 `ORIGINAL_REPLACED` 입니다(재평가·확인 기록을 남기지 않고 제출하지 않습니다). |
| `IN_DOUBT` | 제출 결과를 확정할 수 없는 상태입니다(응답 없음, 재조회에도 안 보임, 예외, 제출 중 중단). | 자동 재제출은 없습니다. 결과 피드 이벤트나 상세 조회에서 호스트 실행 목록에 그 작업 지시가 보이면 `ACCEPTED` 로 올라갑니다. 미배정 재평가는 409 `RECOVERY_NOT_SAFE` 입니다. |

운영자는 `POST /api/work-requests/{id}/reviews` 로 현장 확인을 기록할 수 있습니다. 관측 값은 `NOT_CONFIRMED`·`NOT_STARTED`·`IN_PROGRESS`·`COMPLETED` 넷이고 사유와 조회한 `updatedAt` 이 필요합니다(바뀌었으면 409 `STALE_REVIEW`). `updatedAt`은 상태가 승격되거나 첫 관측, 호스트 인스턴스 변경, 허가 사유의 원천 관측 시각(`permitBlock` 사유의 `observedAt`)을 제외한 실행 내용 변경이 있을 때만 갱신되며, 변경 사항이 없는 단순 반복 관측에서는 갱신되지 않고 유지됩니다. 기록은 `work_request_review` 에 남고 변경·삭제가 금지되며, 작업 요청 상태를 바꾸거나 제출하거나 완료로 만들지 않습니다. 출발 자리 확인(`source-confirmations`)은 현장 확인(`reviews`)과 다른 표에 남고, 현장 확인과 달리 같은 작업 지시를 다시 제출합니다.

### 중복 방지와 배포 고정

- `work_request` 는 `(source_system_id, external_order_id)` 가 유일합니다. 출처는 외부 주문이면 설정 식별자(기본 `mock-mes`), 운영 화면 수동 요청이면 `ops:manual`, 호출 계약 호출이면 `ops:interface` 이고, 키는 외부 주문이면 `externalOrderId`, 화면이면 요청 본문의 `requestId`(정규 UUID)입니다.
- 본문은 키 순서를 정렬한 JSON 의 SHA-256(`payload_hash`)으로 비교합니다. 같은 키에 같은 본문이면 기존 작업 요청을 돌려주고(외부 주문 API 는 새 요청 202, 재전송 200), 다른 본문이면 409 `ORDER_CONFLICT` 입니다. 화면 요청은 본문에 요청자(`requestedBy`)가 들어가므로 같은 요청 ID 를 다른 사용자가 보내도 409 입니다.
- 재전송은 상태와 상관없이 다시 제출하지 않습니다. `IN_DOUBT` 도 마찬가지입니다.
- 기존 경로 `POST /api/job-orders` 는 호출마다 새 `requestId` 를 만들어 같은 작업 요청 경로를 지나므로, 호출자 쪽 재전송의 중복 방지는 제공하지 않습니다.
- 변환한 작업 지시(`normalized_order`)에는 작업 요청을 받은 시점의 배포(`deploymentVersion`, `missionVersion`)가 들어갑니다. 대기열에서 나와 제출할 때는 저장한 작업 지시를 그대로 쓰고, 미배정 재평가는 원래 입력을 다시 변환해 저장본과 다르면 409 `RECOVERY_INPUT_CHANGED` 로 막습니다. 호출 계약 호출에 `expectedDeploymentVersion` 을 주면 현재 배포와 다를 때 409 `STALE_DEPLOYMENT` 입니다.
- 상위 시스템 대체 연결: 외부 주문 본문의 선택 칸 `replacesExternalOrderId` 는 같은 출처의 앞선 `externalOrderId`(1~160자 문자열, 자기 자신 불가)입니다. 잘못된 값은 400 `BAD_REPLACES`, 이 출처에 없으면(다른 출처의 주문도 같은 답) 409 `ORIGINAL_NOT_FOUND`, 앞선 작업 요청이 실행 관측·결과 버전이 없는 `REJECTED`·`UNASSIGNED`도, 최신 결과 버전에 운영자 종결 `closure`(kind `UNRESTORED_CLOSED`)를 담은 `ACCEPTED`도 아니거나 스테이션 작업이 끝나지 않았으면 409 `ORIGINAL_NOT_REPLACEABLE`, 이미 다른 작업 요청이 대체했으면 409 `ORIGINAL_ALREADY_REPLACED` 입니다. 이 거부들은 작업 요청을 남기지 않습니다.
- 연결은 새 작업 요청 행과 같은 트랜잭션에서 앞선 작업 요청 행의 잠금 아래 기록합니다. 같은 앞선 작업 요청을 잇는 동시 새 작업 요청은 하나만 받습니다.
- 운영자가 종결한 앞선 작업 요청의 대체 연결은 해당 결과 버전을 `original_result_version`(V23)에 기록하며, `REJECTED`·`UNASSIGNED`인 앞선 작업 요청의 연결에는 결과 버전이 없습니다.
- 같은 새 주문의 재전송은 연결 판정 없이 기존 작업 요청을 돌려줍니다(200). 연결 칸은 본문 해시에 들어갑니다.
- 대체 위치의 재검증은 새 작업 요청의 제출 게이트가 합니다(실행 호스트가 그 순간의 셀을 다시 봅니다). 운영 서비스는 미리 거르지 않습니다.
- 상세 API `GET /api/work-requests/{id}` 와 외부 조회 `GET /integration/v1/job-orders/{id}` 는 칸 `replaces`, `supersededBy`(`{receiptId, externalOrderId, state, linkedAt}` 또는 null)를 싣습니다.

### 멈춘 작업 요청 정리

- 결과 수집 워커(`WorkResultCollector`)가 `ops.results.poll-ms`(5000ms)마다 결과를 모으기 전에 `sweepStale` 을 먼저 돌립니다.
- `updated_at` 이 `ops.requests.stale-seconds`(기본 300초)보다 오래된 `RECEIVED` 는 `REJECTED`(상세: 처리 중단, 호스트에 보내지 않았으니 새 요청 ID 로 다시 보내라)로, `DISPATCHING` 은 `IN_DOUBT`(상세: 제출 도중 중단, 실행 관측과 현장 확인 필요)로 닫습니다. `WAITING_RESOURCE` 는 스테이션 조정의 몫이라 건드리지 않습니다.
- 정리를 먼저 돌리는 까닭은 멈춘 제출이 `IN_DOUBT` 가 된 뒤에 관측이 `ACCEPTED` 로 올릴 수 있게 하기 위해서입니다(코드 주석). 정리가 실패해도 결과 수집은 계속합니다.
- 작업 요청 경로의 상태 전이는 «현재 상태가 `RECEIVED` 일 때만» 같은 조건부 갱신이라, 정리가 먼저 닫은 행을 덮어쓰지 않습니다.

### 결과 버전과 회신

- 실행 호스트는 처분이 송신인 작업 응답을 `mission.result_outbox` 에 기록합니다. 순번은 `mission.result_producer` 한 행을 잠그고 같은 트랜잭션에서 하나씩 올리므로 커밋 순서와 같고 빈 번호가 없습니다. 재기동 중복으로 판정한 응답은 기록하지 않습니다. `GET /host/result-events?after=&limit=`(limit 1~200)이 생산자 id(`producerId`), 마지막 순번(`highWatermark`), 이벤트 목록을 줍니다.
- 운영 서비스 수집은 `ops.result_cursor` 의 순번 다음부터 100건씩 읽습니다. 순번이 이어지지 않거나 이벤트 하나라도 형식이 틀리면 그 쪽 전체를 저장하지 않고, 생산자 id 가 바뀌면 멈춥니다. 커밋 직전에 커서가 그대로인지 확인해 두 수집기가 같은 쪽을 두 번 저장하지 않게 합니다.
- 결과 버전(`ops.work_result`)은 작업 요청마다 1부터 올라가는 버전이고, 한 버전은 호스트 인스턴스·실행·작업 응답 id 조합 하나입니다. 스냅숏을 키 정렬 JSON 으로 만든 SHA-256 을 `result_hash` 로 둡니다. 코드는 버전 행을 덧붙이기만 하고 `acknowledged_at` 만 한 번 채웁니다. 이 표에는 변경을 막는 DB 트리거가 없으므로 불변은 코드 관례입니다.
- 운영자 종결 결과는 응답 id가 `close-<event_id>`이고, 현재 인스턴스가 아닌 종결 대상 쌍(이전 인스턴스의 실행)을 인스턴스와 실행 id로 담으며, 물리 상태 `UNVERIFIED`, 빈 완료 단위(`completedUnits`), 미완료 단위(`incompleteUnits`)마다 코드 `UNRESTORED_CLOSED`, `operatorRequired=true`, `reachedEvidence=E0`, 최상위 `closure` `{kind, basis}`를 가집니다. 이 결과는 결과 버전 스냅숏에 보관되어 결과 API와 콜백 본문에 반영됩니다. 승인자와 종결 사유는 결과에 들지 않고 실행 호스트의 종결 행과 운영 서비스 조작 기록에 남으며, 잘못된 `closure`(알 수 없는 `kind`나 `basis`, 또는 `UNVERIFIED`가 아닌 결과)는 그 쪽 전체를 저장하지 않습니다.
- 외부 결과 API 는 `GET /integration/v1/job-orders/{id}/results/latest`, `GET .../results/{version}`, 수신 확인 `POST .../results/{version}/ack` 를 제공합니다. 본문은 정확히 `{"resultHash": "<64자리 16진>"}` 이고, 해시가 다르면 409 `RESULT_CONFLICT`, 같은 수신 확인을 다시 보내면 처음 기록한 `acknowledgedAt` 을 그대로 돌려줍니다. 다른 출처의 작업 요청은 404 입니다. 실행하지 않은 작업 요청(`REJECTED`·`UNASSIGNED`)에는 결과 버전이 없어 `results/latest` 가 404 `RESULT_NOT_READY` 이고 콜백(`WorkResultDelivery`)도 없습니다. 출발 결품의 대체 위치는 외부 조회 `GET /integration/v1/job-orders/{id}` 에서만 읽습니다.
- 콜백 전달(`WorkResultDelivery`)은 `PICASSO_LOCAL_CALLBACK_URL` 이 있을 때만 켜지고 주소는 루프백 IP 리터럴 http 만 받습니다. 전달 행은 2초 주기가 넣지 않습니다. 결과 버전을 적는 트랜잭션(`WorkRequests.captureResult`)이 같은 트랜잭션에서 `work_result_delivery` 행 하나를 넣습니다(`ON CONFLICT DO NOTHING`). 설정 출처(`ops.integration.source`)의 작업 요청만, 콜백 주소가 비면 넣지 않습니다. 결과 버전이 되돌려지면 전달 행도 없고, 전달 행을 못 넣으면 결과 버전도 되돌려집니다. 프로세스마다 첫 전달 회차에 한 번 따라잡기(`WorkResultDelivery.catchUp`)가 전달 행이 없는 설정 출처 결과 버전을 넣습니다(전달 행을 함께 넣기 전에 적은 결과, 전달이 꺼져 있을 때 적은 결과). 성공한 뒤로는 회차마다 결과 전체를 훑지 않습니다. 실패하면 다음 회차에 다시 시도합니다. `ops.results.delivery-ms`(2000ms)마다 도는 회차에서 최대 `ops.results.delivery-batch`(기본 10)건을 `FOR UPDATE SKIP LOCKED` 와 30초 임대로 집어 보냅니다. 본문은 `receiptId`·`version`·`resultHash`·`snapshot`, 헤더는 `Idempotency-Key: <작업 요청 id>/v<버전>` 입니다. 2xx 면 `DELIVERED`, 아니면 `RETRY` 이고 다음 시도까지 2의 min(시도 수, 8)제곱 초를 기다립니다(최대 256초, 코드의 300초 상한에는 닿지 않음). 시도 수 상한이나 영구 실패 상태가 없어 4xx 도 계속 다시 보냅니다. HTTP 성공은 전달 성공일 뿐 수신 확인이 아닙니다. 근거: `WorkRequestsTest` 의 `결과 버전을 적을 때 같은 자리에서 버전마다 전달 행 하나를 넣고 다른 출처와 꺼진 전달은 넣지 않는다`, `결과 버전과 전달 행은 한 트랜잭션이라 어느 쪽이 실패해도 둘 다 남지 않는다`, `전달 주기는 기동 뒤 첫 회차에만 전달 행 없는 예전 결과를 넣고 그 뒤 회차는 결과를 다시 훑지 않는다`, `쓸 때 넣은 전달 행은 넣은 순서로 한 회차에 정한 수만큼 보내고 같은 키로 보내며 ACK와 분리한다`.

## 공유 스테이션 조정

공유 스테이션(`ops.rf_station`)은 엔지니어가 등록합니다(`POST /api/stations`). 준비 신호와 완료 신호는 셀 대역에 선언된 안전 신호가 아닌 일반 BOOLEAN 신호여야 하고 서로 달라야 하며, 단계 기한 `timeout_seconds` 는 5~3600초입니다. 등록 뒤 바꿀 수 없습니다. 선택 칸인 작업 번호 송신 신호 `jobSignal` 와 작업 번호 반환 신호 `jobEchoSignal`(DB 칸 `job_signal`, `job_echo_signal`)은 둘 다 있거나 둘 다 없어야 하고(DB 제약 `station_job_pair`), 서로 달라야 하며, 셀 대역에 선언된 안전 아닌 TEXT 신호여야 합니다(거부 문구 «작업 번호 송신·반환 신호는 둘 다 연결하거나 둘 다 비워야 합니다.», «현장에 선언된 TEXT 신호가 필요합니다.»). 격리 칸은 `rf_station.quarantined_by`(격리를 건 스테이션 작업 id, `station_run` 참조)와 `quarantined_at`(둘 다 있거나 둘 다 없음, 제약 `station_quarantine_pair`)입니다. `GET /api/stations` 의 스테이션 행에 `jobSignal`, `jobEchoSignal`, `quarantinedBy`, `quarantinedAt` 이 실립니다. 설비 쪽 요구 사양은 [공유 스테이션 설비 연동 요구 사양](station-equipment-requirements.md) 에 있습니다. 스테이션은 한 번에 한 작업만 점유합니다(`rf_station.owner`, 부분 유일 색인 `station_one_owner`). 스테이션 작업(`ops.station_run`)은 두 길로 생깁니다. 운영자가 `POST /api/stations/runs` 로 직접 요청하거나(요청 ID, 스테이션, 호출 계약과 버전, 조회한 배포 버전, 입력), 임무에 필수 자원(`ops.mission_resource_requirement`, 임무당 스테이션 하나, 엔지니어 등록, 변경·삭제 금지)이 있으면 모든 작업 요청 경로가 작업 요청을 `WAITING_RESOURCE` 로 두고 같은 id 의 스테이션 작업을 대기열에 넣습니다. 스테이션 워커(`StationWorker`)가 `ops.stations.poll-ms`(기본 2000ms)마다 `StationCoordinator.tick` 을 부릅니다. 상태나 대기 사유가 바뀔 때마다 `revision` 이 오르고 `ops.station_event` 에 한 행이 남습니다. 근거는 `GET /api/stations/runs/{id}/evidence`(스키마 `ops.station.evidence/1`)로 읽습니다.

### 스테이션 작업 상태

| 상태 | 뜻 | 다음 상태 |
| --- | --- | --- |
| `QUEUED` | 대기열에서 차례를 기다림 | 스테이션에 점유 작업이 없으면 가장 먼저 들어온 작업(`created_at`, `run_id` 순)이 점유해 `WAITING_READY`. 운영자 `CANCEL_QUEUED` 면 `CANCELLED` |
| `WAITING_READY` | 스테이션을 점유하고 설비 준비를 기다림. 로봇에는 아직 보내지 않음 | 준비 신호가 true 이고 완료 신호가 false 면 `DISPATCHING`(이때 `dispatched_at` 기록). 단계 기한이 지나면 `TIMED_OUT`. 격리 중이면 운영자 `CONFIRM_STATION_IDLE` 전까지 보내지 않으며(사유 «스테이션 격리; 현장 확인과 격리 해제 판단을 기다립니다») 단계 기한은 멈춤. 작업 번호 쌍이 있는 스테이션은 작업 번호 쓰기 성공 뒤 `DISPATCHING`(근거 `jobSignal`·`jobToken`·`signals`), 쓰기 실패면 `WAITING_READY` 에 남음(사유 «작업 번호 쓰기 실패; 설비 신호 기록을 기다립니다»). 현장이 작업 허가를 선언했으면 격리 확인 다음, 기한 확인 앞에서 허가를 봄. 막히면 `WAITING_READY` 에 남고 사유는 «작업 허가 대기; 작업 번호를 쓰지 않고 작업 요청을 내지 않습니다: <기체> <허가>[ <까닭>][(<막는 주체>)]; 풀릴 근거: <근거>»(까닭은 `REPORTED` 가 아닐 때만, 막는 주체는 있을 때만), 실행 호스트가 답했으나 허가를 못 읽으면 «작업 허가 대기; 작업 번호를 쓰지 않고 작업 요청을 내지 않습니다: 작업 허가를 읽지 못했습니다». 근거(`context`)는 `workPermits`(기체마다 `robotId`·`permit`·`causes`·`subjects`·`resumeBasis`) 또는 `permitUnreadable`. 단계 기한은 멈춤. 허가가 돌아오면 «작업 허가 복귀; 설비 준비 기한을 다시 셉니다»(근거 `workPermits`)로 `WAITING_READY` 에 다시 들어감. 어느 현장에서든 실행 호스트가 작업 허가 읽기에 응답하지 않으면 사유 «실행 호스트 응답 없음; 작업 번호를 쓰지 않고 작업 요청을 내지 않습니다: <원인>»(근거 `hostUnreachable`)으로 `WAITING_READY` 에 머물며 단계 기한은 멈추고, 실행 호스트가 다시 응답하면 «실행 호스트 응답 복귀; 설비 준비 기한을 다시 셉니다»로 `WAITING_READY` 에 다시 들어감. |
| `DISPATCHING` | 고정한 호출 계약으로 작업 요청을 제출함 | 제출 결과가 `ACCEPTED` 면 `RUNNING`, 전송 전 미배정이면 `WAITING_ROBOT`, `REJECTED` 면 `REJECTED`(사유는 «계약 제출 거부: <작업 요청 detail>; 자원 해제», `detail` 이 없거나 비면 «계약 제출 거부; 자원 해제»), 호스트 미배정에 출발 미관측이 있으면 `REVIEW_REQUIRED`(사유는 «출발 미관측; 작업 요청 상세에서 출발 자리 확인을 기록하기 전에는 다시 제출하지 않고 점유를 유지합니다»), 그 밖이거나 호출 결과를 모르면 `REVIEW_REQUIRED`. 프로세스가 이 상태에서 멈췄다면 다음 `tick` 이 같은 키로 작업 요청을 다시 찾아볼 뿐 새로 보내지 않음 |
| `RUNNING` | 로봇이 받아 실행 중. 저장된 결과와 새 설비 완료 신호를 기다림 | 완료 조건(아래 기준선 절)이 맞으면 `COMPLETED`. 기준선보다 새 결과의 물리 상태가 `OPERATOR_HOLD`·`IN_DOUBT`·`UNVERIFIED`·`FAILED`·`ABORTED`·`PARTIAL` 이면 `REVIEW_REQUIRED`. 단계 기한이 지나면 `REVIEW_REQUIRED`. 완료 근거와 판단된 결함을 작업 허가 차단, 실행 기한, 셀 신호 유예보다 먼저 판정함. 실행 기한이 지난 처리 주기라도 로봇 완료와 새 완료 신호가 모두 확인되면 `COMPLETED` 로 완료 처리함. 로봇 실행의 작업 허가 차단 중에는 실행 기한이 일시 정지되며, 복귀 시 `phase_at` 을 갱신해 기한을 다시 계산함. 셀 신호 읽기 실패 시 최대 15초 동안 유예 상태로 관측을 유지하며, 유예가 만료되거나 선언된 신호가 누락된 경우에만 `REVIEW_REQUIRED` 로 전환함. 작업 번호 쌍이 있는 스테이션은 완료 신호가 true 이고 반환 번호가 다르면 «설비 완료 신호 수신, 작업 번호 불일치; 현장 확인이 필요합니다» 로 `REVIEW_REQUIRED`(근거 `expected`·`echoed`·`signals`), 반환 신호가 읽기에 없어도 `REVIEW_REQUIRED` |
| `WAITING_ROBOT` | 호스트에 보내기 전 배정 가능 기체가 없었음 | 설비가 여전히 준비 상태이고 이 상태에 들어온 지 5초가 지났으며 재평가가 3번 미만이면 `RETRYING` 으로 선점한 뒤 미배정 재평가. 작업 요청이 더는 전송 전 미배정이 아니면 그 상태에 따라 전이. 단계 기한이 지나면 `REVIEW_REQUIRED`. 제한 재평가(5초 간격·3회)는 전송 전 미배정만 돌고 출발 미관측은 돌지 않습니다. 스테이션의 전송 전 미배정 제한 재평가가, 처음엔 후보가 전부 빠졌다가(진행 중인 실행·스킬 부족) 나중에 배정 가능해진 기체를 다음 재평가에서 고르고, 재평가 상한(3회)·간격(5초)은 그대로임을 시험 `WorkRequestsTest` «전송 전 미배정의 제한 재평가는 나중에 배정 가능해진 기체를 고르고 재평가 횟수와 간격은 그대로다» 가 확인합니다. |
| `RETRYING` | 미배정 재평가를 호스트에 보내는 중. 출발 자리 확인 뒤 같은 작업 요청을 다시 제출하는 중인 경우도 이 상태 | 결과에 따라 `RUNNING`·`WAITING_ROBOT`·`REJECTED`·`REVIEW_REQUIRED`, 결과를 모르면 `REVIEW_REQUIRED`. 출발 자리 확인 재제출이 거부되면 같은 사유(«출발 미관측; 작업 요청 상세에서 출발 자리 확인을 기록하기 전에는 다시 제출하지 않고 점유를 유지합니다»)의 `REVIEW_REQUIRED` 로 돌아갑니다. `tick` 이 이 상태를 보면, 이 인스턴스가 남긴 것이면 «재평가 뒤 상태 정리 실패; 실행 관측과 현장 확인 필요» 로 `REVIEW_REQUIRED`. 다른 인스턴스(`station_run.instance_id`)가 남긴 것이면 그 인스턴스의 심장박동이 만료됐거나 멈춤을 적었을 때만 «재평가 중 프로세스 중단; 실행 관측과 현장 확인 필요» 로 `REVIEW_REQUIRED`, 살아 있으면 그대로 둔다. 이벤트 근거(`context`)의 `claimedBy` 에 남긴 인스턴스가 적힌다. |
| `REWORKING` | 운영자 재작업을 실행 호스트에 전달하는 중. 다른 판단을 막음 | 아래 재작업 게이트 절. 이 인스턴스가 남긴 것이면 «재작업 전달 뒤 상태 정리 실패; 반영 여부는 실행 관측과 현장 확인 필요», 다른 인스턴스가 남긴 것이면 만료나 멈춤 뒤에만 «재작업 전달 중 프로세스 중단; 반영 여부는 실행 관측과 현장 확인 필요», 살아 있으면 그대로 둠. 이벤트 근거(`context`)의 `claimedBy` 에 남긴 인스턴스가 적힘 |
| `REVIEW_REQUIRED` | 자동으로 판단하지 않고 점유를 유지한 채 현장 확인을 기다림 | 운영자 `RESUME_OBSERVATION` 이면 `RUNNING`, `CONFIRM_CLEAR` 면 `MANUALLY_CLEARED`, `CONFIRM_COMPLETION` 이면 작업 시간 초과나 현장 신호 관측 불명 사유인 작업에 한해 현장 확인 후 `COMPLETED`, 재작업 게이트를 지나면 `REWORKING`. 작업 번호 쌍이 없고 제출 진입(`dispatched_at`) 후 미전송으로 확인되지 않은 작업은 `CONFIRM_CLEAR` 시 스테이션 격리도 걸림. 묶인 작업 요청이 출발 미관측이면 운영자의 출발 자리 확인으로 `RETRYING`(사유 «출발 자리 확인 기록; 같은 작업 요청을 다시 제출합니다», 행위자는 운영자)이 되고 결과에 따라 전이합니다. 이때 `RESUME_OBSERVATION` 은 409 입니다. |
| `COMPLETED` | 로봇 물리 완료와 이번 작업의 설비 완료 신호를 확인하고 점유를 풂 | 종료 |
| `TIMED_OUT` | 설비 준비 단계 기한 초과, 로봇에 보내기 전에 점유를 풂 | 종료. 묶인 작업 요청이 `WAITING_RESOURCE` 면 `REJECTED` |
| `CANCELLED` | 운영자가 대기열에서 취소 | 종료. 묶인 작업 요청이 `WAITING_RESOURCE` 면 `REJECTED` |
| `REJECTED` | 작업 요청 제출이 거부되어 점유를 풂(묶인 작업 요청이 `REJECTED` 면 사유 «계약 제출 거부: <작업 요청 detail>; 자원 해제», detail 이 없거나 비면 «계약 제출 거부; 자원 해제») | 종료 |
| `MANUALLY_CLEARED` | 운영자가 현장 확인 뒤 점유를 풂. 물리 완료 판정이 아님 | 종료. 묶인 작업 요청이 `WAITING_RESOURCE`·`UNASSIGNED` 면 `REJECTED`(실행하지 않음), `DISPATCHING` 이면 `IN_DOUBT`(실행 관측으로 확인), `IN_DOUBT`·`ACCEPTED`·`REJECTED` 는 그대로. 작업 번호 쌍이 없는 스테이션에서 `dispatched_at` 이 있는 스테이션 작업을 `CONFIRM_CLEAR` 로 해제할 때 작업 요청이 미전송으로 확인되지 않으면 같은 트랜잭션에서 스테이션을 격리함(이력에 «자원 해제에 따른 스테이션 격리» 기록) |

단계 기한은 그 상태에 들어온 시각(`phase_at`)부터 스테이션의 `timeout_seconds` 로 잽니다. 실행 중 기한 초과는 점유를 유지하고 자동으로 다시 실행하지 않습니다. 대기 사유만 바뀌는 경우(작업 번호 쓰기 실패, 격리, 작업 허가 대기)는 `phase_at` 을 바꾸지 않으며, 격리 중 설비 준비 기한은 멈추고 `CONFIRM_STATION_IDLE` 판단 시각부터 다시 셉니다. 작업 허가 대기 중에도 설비 준비 기한은 멈추고, 허가가 돌아와 «작업 허가 복귀; 설비 준비 기한을 다시 셉니다» 로 다시 들어갈 때 `phase_at` 을 그 시각으로 다시 둡니다(기한을 그때부터 다시 셈). 실행 호스트 응답 없음으로 대기할 때도 설비 준비 기한은 멈추고, «실행 호스트 응답 복귀; 설비 준비 기한을 다시 셉니다» 로 다시 들어갈 때 `phase_at` 을 그 시각으로 다시 둡니다. `RUNNING` 단계에서는 로봇 실행에 작업 허가 차단이 걸려 있는 동안 실행 기한이 일시 정지되며, 차단이 풀려 작업 허가가 복귀하면 `phase_at` 을 복귀 시점으로 갱신하여 그때부터 실행 기한을 다시 계산합니다. 미배정 재평가 요청 ID 는 스테이션 작업 id 와 시도 번호로 정해지는 UUID 이고, 재평가는 최대 3번입니다.

### 운영자 판단

`POST /api/stations/runs/{id}/decision`(운영자 모드만, 본문 `expectedRevision`·`decision`·`reason`, 조회한 버전과 지금 버전이 다르면 409)으로 요청합니다.

- `CANCEL_QUEUED`: `QUEUED` 만 `CANCELLED` 로 바꿉니다.
- `RESUME_OBSERVATION`: 작업 요청이 묶인 `REVIEW_REQUIRED` 만 대상으로 합니다. 작업 요청이 출발 미관측으로 묶인 작업에는 409 를 반환합니다. 셀 신호를 스테이션 잠금 전에 읽고, 읽지 못하면 503, 설비 완료 신호가 켜져 있으면 409(신호를 초기화한 뒤 다시 하라)를 반환합니다. 통과하면 `RUNNING` 으로 돌리고 `dispatched_at` 을 지금으로 다시 정합니다. 로봇에 아무것도 다시 보내지 않습니다.
- `CONFIRM_CLEAR`: `REVIEW_REQUIRED` 만 `MANUALLY_CLEARED` 로 바꿉니다. 점유를 풀고 같은 트랜잭션에서 묶인 작업 요청을 위 표처럼 닫습니다. 해제는 실행 호스트의 실행을 멈추지 않습니다. 판단 전(잠금 밖)에 셀 신호와 해제할 작업의 작업 요청을 읽어 이력 근거에 `signals`, `receiptId`, `physicalState`, `resultVersion`, `quarantined` 를 남기며, 셀 신호를 읽지 못해도 해제는 막지 않습니다(근거의 `signals` 는 null). 작업 번호 쌍이 없는 스테이션에서 `dispatched_at` 이 있는 스테이션 작업을 해제할 때, 작업 요청을 다시 확인하여 미전송으로 확인되지 않으면 같은 트랜잭션에서 스테이션을 격리하고, 해제한 작업의 이력에 «자원 해제에 따른 스테이션 격리» 한 행을 더 남깁니다(정상 완료, 대기 취소, 전송 전 시간 초과, 가용 기체 대기 중 시간 초과, 실행 호스트의 미배정 응답, 제출 거부는 격리하지 않음). `dispatched_at` 은 작업이 로봇에 도달한 시점이 아니라 제출에 진입한 시점을 기록합니다.
- `CONFIRM_COMPLETION`: `REVIEW_REQUIRED` 상태에서 작업 시간 초과(«작업 시간 초과; 물리 상태 확인 전 점유를 유지합니다») 또는 현장 신호 관측 불명(«현장 신호 관측 불명; 점유를 유지하고 현장 확인을 기다립니다») 사유로 전환된 작업에 대해 운영자가 현장 확인 후 완료 처리합니다. 그 외 사유인 경우 409(«작업 시간 초과나 현장 신호 관측 불명으로 확인 필요 상태가 된 작업만 완료 확인할 수 있습니다»)로 거부합니다. 저장된 로봇 결과가 기준선 이후의 `PHYSICALLY_DONE` 이어야 하며(없으면 409 «해당 작업의 로봇 물리 완료 저장 결과가 없습니다», 결과 조회 실패 시 503), 설비 완료 신호가 켜져 있어야 하고(꺼져 있으면 409 «설비 완료 신호가 꺼져 있습니다», 신호 조회 실패 시 503), 작업 번호 반환 스테이션은 반환 번호가 해당 작업 번호와 일치해야 합니다(불일치 시 409 «반환 번호가 해당 작업의 작업 번호와 일치하지 않습니다»). 성공 시 운영자를 주체로 «운영자 현장 확인 후 완료 확인: {사유}» 사유를 기록하고 격리 없이 점유를 해제하여 `COMPLETED` 로 전이하며, 증거로 `execution`, `signals`, `physicalState`, `resultVersion`, `review`, 작업 번호 반환 스테이션의 경우 `expected`/`echoed` 를 남깁니다.
- `CONFIRM_STATION_IDLE`: 격리된 스테이션을 점유해 `WAITING_READY` 인 스테이션 작업에만 씁니다(아니면 409 «격리된 스테이션을 점유해 설비 준비를 기다리는 작업만 격리를 해제합니다»). 셀 신호를 스테이션 잠금 전에 읽고, 읽지 못하면 503 «현장 신호를 확인할 수 없어 격리를 해제하지 않습니다», 완료 신호가 켜져 있으면 409 «설비 완료 신호가 켜져 있습니다. 신호가 꺼진 것을 확인한 뒤 격리를 해제하십시오». 통과하면 격리 칸을 비우고 상태는 `WAITING_READY` 그대로 새 단계로 다시 들어가며(단계 기한이 그 판단 시각부터 다시 시작), 이력에 «현장 확인 후 격리 해제: {사유}» 와 근거 `releasedRunId`·`quarantinedAt`·`signals` 를 남깁니다. 로봇에 아무것도 보내지 않습니다.
- 새 API `POST /api/stations/runs/{id}/source-confirmations`(운영자 모드만, 본문은 작업 요청의 출발 자리 확인과 같음: `requestId`·`source`·`identity`·`reason`·`expectedUpdatedAt`). 스테이션 작업이 스테이션 점유자이고 `REVIEW_REQUIRED` 이며 작업 요청이 출발 미관측일 때만 받습니다. 셀 신호를 못 읽으면 503, 준비 신호가 false 이거나 완료 신호가 true 면 409(상태 그대로)입니다. 통과하면 `RETRYING` 으로 잡고 같은 작업 요청을 다시 제출합니다. 응답은 200 이고 본문은 다시 제출한 뒤의 작업 요청 상태입니다. 이 판단은 `decision` API 가 아닙니다.

### 재작업 게이트

인시던트 화면의 보류 해소 가운데 재작업(`REWORK`)만 게이트 `StationReworkGuard` 를 지납니다. 재작업은 보류한 단위를 다시 돌려 로봇이 다시 움직이기 때문입니다. 운영 서비스가 실행 호스트의 실행 목록에서 실행 id 로 작업 지시를 찾고 작업 요청과 잇습니다. 실행 목록을 읽지 못하면 503 `HOST_SILENT`, 그 실행이 목록에 없으면 409 `EXECUTION_NOT_FOUND` 로 막습니다(자원 관리 여부를 알 수 없으니 차단). 요청한 인스턴스가 호스트의 현재 인스턴스가 아니면 그대로 넘기고 호스트가 `INSTANCE_MISMATCH` 로 거부합니다. 작업 요청이 없거나 필수 자원이 없는 작업 요청이면 그대로 넘깁니다. 필수 자원이 있는 작업 요청이면 그 스테이션 작업이 지금 스테이션의 점유 작업이고 상태가 `REVIEW_REQUIRED` 일 때만 허용합니다. 아니면 409(«자원 관리 작업의 재작업은 스테이션 점유를 유지한 확인 필요 상태에서만 할 수 있습니다»)를 반환합니다. 허용하면 먼저 `REWORKING` 으로 선점하고, DB 잠금 밖에서 호스트에 전달한 뒤 다시 잠금 안에서 결과를 반영합니다. 반영 시 호스트가 거부하거나 결과를 모르거나, 셀 신호를 모르거나, 설비 완료 신호가 켜져 있으면 사유를 남기고 `REVIEW_REQUIRED` 로 돌립니다(점유 유지). 반영되었고 완료 신호가 꺼져 있으면 `RUNNING` 으로 가고 `dispatched_at` 을 지금으로 다시 정합니다. 게이트가 막은 요청은 사전 거부라 조작 기록에 남지 않습니다. 종결된 순회의 건너뛴 대상을 재작업할 때도 동일한 게이트가 적용되며, 실행 호스트의 `Refused`나 `EVICTED`를 포함한 `NotHeld` 응답은 스테이션 작업을 다시 `REVIEW_REQUIRED`로 되돌립니다.

### 결과 버전 기준선

V16 이 `station_run.result_baseline` 과 `baseline_review_only` 를 더했습니다. `RUNNING` 에 들어갈 때마다(제출 수락, 미배정 재평가 수락, 관측 재개, 재작업 반영, 출발 자리 확인 뒤 재제출 수락) 같은 잠금 트랜잭션에서 그 작업 요청의 최신 결과 버전을 `result_baseline` 으로 적습니다. 그보다 새 결과만 이번 시도의 근거로 씁니다. 그래서 재작업이나 관측 재개 직후 이미 저장된 이전 보류 결과로 곧바로 `REVIEW_REQUIRED` 로 되돌아가지 않습니다. 관측 재개는 같은 실행을 다시 보는 것이므로 `baseline_review_only` 를 true 로 둡니다. 이때는 확인 대기 중 저장된 `PHYSICALLY_DONE` 도 완료 근거로 인정하되, `REVIEW_REQUIRED` 로 넘기는 판단에는 기준선보다 새 결과만 씁니다. 재작업은 재작업 전에 저장된 물리 완료를 완료 근거로 쓰지 않습니다. `COMPLETED` 조건 전체는 (가) 저장된 최신 결과의 물리 상태가 `PHYSICALLY_DONE`이고 기준선보다 새 결과이거나 관측 재개여야 하며, (나) 설비 완료 신호가 true이고, (다) 그 읽기가 이번 `RUNNING` 진입 뒤의 것이어야 하며, (라) 작업 번호 쌍이 있는 스테이션은 같은 읽기의 반환 번호가 이번 작업 번호여야 합니다. `tick`은 셀 신호를 읽기 직전에 운영 서비스 시계로 `readAt`을 적고, `readAt`이 `phase_at`(이번 `RUNNING` 진입 시각, 운영 서비스 시계)보다 앞이면 그 읽기의 완료 true는 판단하지 않습니다. 현장 시계와 운영 서비스 시계가 다르므로 신호의 `observedAt`(현장 시계)은 비교하지 않고 근거에만 남깁니다. `RUNNING`에 들어가는 길(제출 수락 전 준비 확인, 미배정 재평가 전, 관측 재개, 재작업 반영, 출발 자리 확인 뒤 재제출 전)은 모두 그 직전에 완료 신호 false를 확인합니다.

### 작업 번호 신호와 스테이션 격리

작업 번호 쌍이 있는 스테이션은 등록 칸 `jobSignal`·`jobEchoSignal`(TEXT)을 씁니다. 작업 번호는 스테이션 작업 id(UUID 문자열)입니다. 현장이 작업 허가를 선언했으면 번호를 쓰기 전에 작업 허가를 보고, 막히거나 못 읽으면 번호를 쓰지 않습니다. 허가는 `tick` 첫머리에 셀 신호와 함께 잠금 밖에서 한 번 읽습니다(`OpsStationPort.permit` → 실행 호스트 `GET /host/work-permits`). 운영 서비스가 `WAITING_READY`에서 준비 true·완료 false를 읽으면 잠금 밖에서 `jobSignal`에 번호를 씁니다(`OpsStationPort.write`, `CellSignalOperations.write` 경유로 운영 서비스 → 실행 호스트 → 현장, 조작 기록 `WRITE_CELL_SIGNAL`, 행위자는 그 스테이션 작업의 요청자). 현장이 받아들였거나 응답 없음 뒤 다시 읽은 값이 같으면 성공입니다. 그 뒤 다시 스테이션 잠금 안에서 상태·`revision`·점유·격리 여부가 그대로인지 확인하고 `DISPATCHING`으로 넘깁니다. 바뀌었으면 넘기지 않고, 실패면 «작업 번호 쓰기 실패; 설비 신호 기록을 기다립니다»로 남깁니다. 설비는 시작 때 번호를 붙들고, 완료 신호를 올리기 전에 반환 신호에 그 번호를 써야 합니다. 완료 true인데 반환 번호가 다르면 «설비 완료 신호 수신, 작업 번호 불일치; 현장 확인이 필요합니다»로 `REVIEW_REQUIRED`가 되며 점유를 유지합니다. 운영자가 신호를 내리고 `RESUME_OBSERVATION`을 하면 이번 번호로 완료할 수 있습니다. 설비 세부 요구는 [공유 스테이션 설비 연동 요구 사양](station-equipment-requirements.md)을 봅니다.

외부 검토 실험 A1에서 확인되었듯, 스테이션 작업 X를 보낸 뒤 로봇이 끝나지 않아 실행 기한 초과로 `REVIEW_REQUIRED`가 되었을 때 운영자가 `CONFIRM_CLEAR`로 해제해도 실행 호스트에서 X의 실행이 멈추지 않습니다. 해제는 실행을 멈추지 않으므로, 다음 작업 Y가 보내진 뒤 X 쪽 설비가 늦게 완료 신호를 올리면 Y의 완료로 잘못 받아들일 위험이 있습니다. 따라서 작업 번호 쌍이 없는 스테이션은 보낸 적이 있는 스테이션 작업을 `CONFIRM_CLEAR`로 해제하면 스테이션을 격리합니다. 격리되면 다음 작업은 `WAITING_READY`에서 보내지 않고 기한이 멈추며, `CONFIRM_STATION_IDLE`로 풉니다. 해제 조건과 구체적인 거부 사유는 운영자 판단 절을 봅니다.

이 격리 방식에도 한계는 남습니다. 작업 번호 쌍이 없는 스테이션에서 X가 정상 완료한 뒤 X 쪽 설비가 완료 신호를 다시 true로 쓰면, 실행 중인 Y의 로봇이 물리 완료일 때 Y의 완료로 받아들일 수 있습니다(정상 완료는 격리하지 않습니다). 둘을 가를 수 있는 것은 작업 번호 쌍뿐입니다.

### 잠금과 실행 단위

DB 잠금(스테이션 행 `FOR UPDATE`, 임무별 advisory 잠금 `pg_advisory_xact_lock(hashtext(임무 id))`)은 판단에만 쓰고 호스트 호출 동안에는 잡지 않습니다. 처리 주기는 잠금 없는 한 번의 읽기로 모든 스테이션과 점유 작업을 보고, 조용한 스테이션(점유 없고 대기열 없음, 확인 필요, 기한 전 실행 중)은 잠그지 않습니다. 쓰는 판단만 스테이션을 잠그고 잠근 작업으로 다시 판단합니다. 작업 번호 쓰기도 호스트 호출이라 잠금 밖에서 하고 그 뒤 잠금 안에서 상태·`revision`·점유·격리를 다시 확인한 뒤에만 `DISPATCHING`으로 넘깁니다. 작업 허가 읽기도 잠금 밖에서 합니다. 실행 호스트 쪽의 허가 포트 `read()` 는 호스트 잠금 아래에서 셀 대역 스냅숏으로 지어 둔 판독만 돌려주며 DB 나 HTTP 를 부르지 않습니다(근거: `CellWorkPermitsTest`). `tick` 과 `rework` 는 JVM 의 `@Synchronized` 로 한 프로세스 안에서 서로 겹치지 않습니다. `tick` 이 `RETRYING`·`REWORKING` 을 보면 앞 프로세스가 멈췄다고 읽는 것은 조정기가 하나뿐일 때만 맞습니다. 배포 전제는 [보장 범위](guarantees.md) 링크를 봅니다.

## 시스템 동작 및 운영 상세

### 데이터와 재기동

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="diagrams/sequence-restart.dark.svg">
  <img alt="재기동 시퀀스" src="diagrams/sequence-restart.svg">
</picture>

운영 서비스는 실행 호스트를 부르기 전에 작업 요청을 먼저 커밋하므로, 운영 서비스가 재기동해도 같은 키의 재전송은 기존 작업 요청을 돌려주고 다시 제출하지 않습니다. 새로운 실행이 시작되면 응답을 반환하기 전에 작업 지시와 배정 기체를 실행 일지에 먼저 기록합니다.

복원은 pump 호출 전에 진행됩니다. 대상은 그 작업 지시의 종결(`SETTLED`)·다시 실행(`REOPENED`)·포기(`GAVE_UP`)·운영자 종결(`CLOSED`) 이벤트 가운데 마지막이 `REOPENED` 이거나 넷 다 없는 일지 행이며, 종결 뒤 리비전 수락이나 운영자 판단으로 다시 실행된 실행도 듭니다. 수신 순서로, 일지 행의 기체와 수신 당시의 임무 버전으로 리빌드하며(작업 지시는 받은 리비전 가운데 가장 높은 버전이고 리비전이 없으면 일지 행의 작업 지시), 동일한 태스크 id 로 다시 연결되므로 새로운 명령은 생성되지 않습니다. 종결 뒤 다시 실행된 행을 리빌드하지 못해 포기(`GAVE_UP`)하면 그 기체는 판정에서 빠진 채로 남고(재기동을 거듭해도), 같은 작업 지시 id 를 다시 내면 `JOURNALED_JOB_ORDER` 로 거부합니다.

스냅숏 읽기에 실패하면 해당 행의 처리를 미루고 매 pump마다 재시도를 수행합니다. 해당 기체는 «복원 못 한 실행이 있다» 상태로 분류되어 배정 대상에서 제외됩니다.

살아 있는 다른 인스턴스가 든 행도 미룹니다(`DEFERRED`, 사유 앞머리 `살아 있는 다른 인스턴스가 든 실행이라 리빌드하지 않는다`, 뒤에 `: instance=<id>`). 그 기체는 판정에서 빠지고, 그 인스턴스의 심장박동이 끊기면 pump 가 리빌드합니다. 행을 든 인스턴스는 그 작업 지시의 마지막 `RESTORED` 이벤트의 인스턴스이며, 없으면 일지의 `instance_id`(받은 인스턴스)입니다. 판단 기록을 못 읽으면 `GAVE_UP`(사유 `판단 기록을 읽지 못해 리빌드하지 않는다: record=<행 번호>, <picasso 거부 문장>`)입니다. picasso 가 기록을 거부하면 `GAVE_UP`(사유 = picasso `RESUME_RECORD_MISMATCH` 문장 `판단 기록이 이 작업 지시의 것이 아니어서 리빌드하지 않는다` 로 시작하고 끝에 ` (record=<행 번호>)`)입니다. 둘 다 기록 없이 리빌드하지 않습니다. 근거: `HostInstanceTest` 의 `살아 있는 다른 인스턴스가 든 일지 행은 다시 짓지 않고 미루며 그 기체를 판정에서 빼고 그 심장박동이 끊기면 다시 짓는다`, `JudgmentRecordHostTest` 의 `판단 기록을 읽지 못하면 기록 없이 다시 짓지 않고 기록 행 번호와 함께 포기로 보고한다`, `다른 버전의 작업 지시에 선 기록이면 picasso 의 RESUME_RECORD_MISMATCH 거부를 기록 행 번호와 함께 포기로 보고한다`.

기동 복원에서 리빌드하지 못해 포기(`GAVE_UP`)되었거나 스냅숏을 읽지 못해 미룸(`DEFERRED`, 사유가 `RESUME_SNAPSHOT_UNREADABLE`로 시작)된 일지 행은 사람 운영자가 종결할 수 있습니다. 살아 있는 다른 인스턴스가 든 미룸 행(`살아 있는 다른 인스턴스가 든 실행이라 리빌드하지 않는다`)은 종결할 수 없고(`NOT_CLOSABLE`), 에이전트 승인자는 `REFUSED`를 받으며 사유는 필수입니다. 정지 확인은 순서대로 진행되어 실행 호스트가 기체 스냅숏을 읽고 해당 작업 지시의 미완료 태스크(태스크 id가 `<jobOrderId>#`로 시작하고 종료 상태가 아닌 태스크)를 확인합니다. 스냅숏을 읽을 수 있고 해당 태스크가 없는 `GAVE_UP` 행은 `SNAPSHOT` 근거로 종결됩니다. 스냅숏을 읽을 수 있는 `DEFERRED` 행은 다음 pump에서 리빌드하므로 `NOT_CLOSABLE`이며, 스냅숏을 읽지 못하는 동안의 증언으로만 종결할 수 있습니다. 태스크가 여전히 이동 중이거나 대기 중인 명령이 있는 경우(`RETRIABLE`과 `NEEDS_INTERVENTION` 외 미완료 상태)는 `TASK_LIVE`가 반환되고 증언을 받지 않습니다. 스냅숏을 읽을 수 없거나 남은 태스크가 모두 사람을 기다리는 상태(`RETRIABLE`, `NEEDS_INTERVENTION`)이면 증언 없이는 `STOP_UNCONFIRMED`가 반환되고, 기체가 현장에서 멈추었다는 운영자의 증언(`attestedStopped=true`)이 있으면 `ATTESTED` 근거로 종결됩니다. 종결 대상이 바뀐 경우(`TARGET_CHANGED`), 일지 행이 없는 경우(`NOT_FOUND`), 이미 종결된 경우(`ALREADY_CLOSED`), 또는 실행 중인 실행이 있거나 마지막 이벤트가 `SETTLED`인 경우의 `NOT_CLOSABLE`은 아무것도 기록하지 않습니다. `ATTESTED` 종결 뒤 해당 기체는 읽을 수 있는 스냅숏에서 미완료 태스크가 없음을 확인할 때까지 «정지 증언으로 종결하고 스냅숏 확인 전인 실행이 있다: <job order ids>» 사유로 배정에서 제외되며 재기동 때 DB에서 다시 읽습니다(`SNAPSHOT` 종결은 해당 행에 대한 기체 배정 제외를 해제합니다). 이후 판정에서 일치하는 스냅숏을 확인하면 실행 호스트가 `mission.execution_close_confirmed`에 확인 행을 기록한 뒤 메모리에서 해제하므로, 재기동 때는 미확인 증언 종결만 다시 읽습니다. 종결된 작업 지시 id를 다시 제출하면 마지막 이벤트가 `CLOSED`이므로 `FORGOTTEN_JOB_ORDER`로 거부되며, 종결된 행은 더 이상 복원 대상이나 `GAVE_UP` 행이 아니며 «복원 못 한 실행이 있다» 사유를 더하지 않습니다. 인터페이스로는 실행 호스트의 `POST /host/job-orders/{jobOrderId}/close` 및 `GET /host/job-orders/{jobOrderId}/closure`, 운영 서비스의 `POST /api/job-orders/{jobOrderId}/close`(운영자 모드 전용)가 제공되며, 실행 목록(`GET /host/executions?recent=`)의 `unrestored`에 아직 종결되지 않은 이전 기동의 `GAVE_UP` 행(기체 배정 제외 해제 후와 재기동을 넘어서도 유지), 이번 기동의 `DEFERRED` 행, 스냅숏 확인 전인 `ATTESTED` 종결 행이 포함됩니다. 심장박동가 유효한 다른 인스턴스가 든 일지 행은 미룸(`DEFERRED`)이나 포기(`GAVE_UP`) 상태와 무관하게 심장박동 만료 전까지 종결할 수 없어 복원 못 한 실행 목록에 사유와 함께 `closable=false`로 표시되고 종결 요청 시 `NOT_CLOSABLE`(`HELD_BY_LIVE_INSTANCE`)로 응답합니다. 여러 인스턴스가 실행될 때 다른 인스턴스가 이미 종결한 일지 행은 재시도 전에 확인되어 목록에서 제거되므로 리빌드되지 않으며, 해당 행에 대한 종결 요청에는 `ALREADY_CLOSED`로 응답합니다. 종결 조회(`GET /host/job-orders/{jobOrderId}/closure?requestId=<id>`)는 종결 기록 커밋 완료 시 200, 미종결 시 404 `NOT_CLOSED`, 실행 호스트 잠금 대기나 스냅숏 조회 등 진행 중일 때는 409 `REQUEST_IN_PROGRESS`로 응답하므로, 운영 서비스가 무응답 후 정규형 UUID 요청 id로 재확인할 때 진행 중인 종결을 미반영이 아닌 알 수 없음으로 기록합니다. 근거: 운영 보장 명세 «복원 못 한 실행의 운영자 종결».

미해소 잔여 파지 표시는 기동 복원 때 `mission.robot_residual_hold` 에서 해소 행이 없는 것을 다시 읽어 판정에 씁니다(재기동 뒤에도 그 기체를 빼고, 해소도 재기동 뒤까지 남습니다). 그 작업 지시에 운영자 취소가 적혀 있으면(예: 취소를 적은 뒤 다음 pump 가 `ABORTED` 로 종결시키기 전에 재기동) 기동 복원이 리빌드한 실행(새 실행 id)에 그 취소를 한 번 다시 겁니다. 취소 행은 새로 적지 않으며, 새 인스턴스 실행 목록의 `cancel` 칸에는 그 작업 지시의 처음 취소 행이 실리고 그 실행에 운영자 취소를 다시 내면 «이미 취소를 건 실행이다» 로 받지 않습니다. 기동 복원 시 실행 호스트는 리빌드한 실행에 기록된 운영자 취소를 곧바로 다시 겁니다. 첫 pump는 `StartTask`를 다시 보내지 않고 기체 스냅숏에서 찾은 태스크에 붙어 취소를 보내며 하위가 끝난 뒤 종결합니다(태스크가 이미 끝났으면 취소 없이 그 종료로 판정하고, 기체 스냅숏을 못 읽으면 `CANCELING`에서 대기합니다). 다시 붙지 못하는 경우와 곧바로 건 경우의 한계는 운영 보장 명세 «잔여 파지 표시의 범위» 에 있습니다.

재기동 후에도 활성 임무 버전은 그대로 유지되며, 이전 인스턴스의 인시던트는 사본을 통해 조회합니다.

실행 호스트는 작업 응답마다 처분을 송신 기록(`job_response_log`)에 남깁니다. 새 인스턴스에서 어떤 작업 지시의 첫 작업 응답이 그 작업 지시의 가장 최근 송신 기록과 내용이 같으면 재기동 중복(`RESTART_DUPLICATE`)으로 적고 결과 outbox에 넣지 않습니다. 처분이 송신(`SENT`)인 응답만 `result_outbox`를 거쳐 운영 서비스의 결과 버전이 되고, 콜백이 설정되어 있으면 Mock MES로 전달됩니다. 상위 시스템 연계는 로컬 Mock MES 하나로만 검증했습니다.

인시던트 사본의 판단 행은 여전히 pump 뒤에 적습니다. 그러나 판단 자체는 판단 기록으로 넘어옵니다. 판단·운영자 보류·판정·근거 등급·설비 대기 기한의 시작이 판단 기록으로 재기동을 넘어옵니다.

운영자 보류에 선 실행은 기한을 다시 기다리지 않고 보류 그대로 섭니다. 넘어온 보류는 새 인스턴스에 인시던트로 다시 열려 화면에서 «보류 중» 으로 보이고 그대로 판단합니다. 사람에게 새로 묻는 것이 아니라 앞 인스턴스가 물은 질문이 그대로 남는 것입니다.

앞선 단위는 기록의 근거 등급 그대로 섭니다(E2 였으면 E2). 그래서 작업 응답의 도달 근거 등급도 E2 일 수 있습니다.

재작업으로 다시 기다리던 설비 대기는 재작업 때 시작한 기한을 이어 잽니다. 재기동 때 돌고 있던 대기는 판정이 없어 인시던트를 다시 열지 않고, 이어 잰 기한이 지나면 새 보류(새 인시던트)가 섭니다.

기록 없는 행(판단 기록 표 V6 이전에 받은 일지 행)만 옛 거동입니다: 앞선 단위 E0, 작업 응답 도달 E0, 기한은 재기동 때부터 새로. 근거: 통합 시험 `RestartRecoveryTest` 단계 1(E2), 단계 4(보류 그대로, 재기동 중복), 단계 5(기한 이어 잼, 재작업 판단 뒤 `RUNNING` 응답이 따라 재기동 뒤 보류 응답은 송신), `JudgmentRecordHostTest` 의 `운영자가 확인한 로봇 단위의 보류는 재기동 뒤 다시 묻지 않고 확인한 근거 등급 그대로다`, `재작업 판단 뒤 재기동해도 설비 대기 기한은 재기동 때가 아니라 재작업으로 다시 기다리기 시작한 때부터 이어 잰다`.

실행 일지와 받은 리비전(`execution_journal_revision`), 송신 기록, 사본, 운영자 취소(`execution_cancel`), 잔여 파지 표시와 그 해소(`robot_residual_hold`, `robot_residual_hold_clearing`), 운영자 결함 수용(`execution_fault_release`), 운영자 종결(`execution_close`)과 그 스냅숏 확인(`execution_close_confirmed`)에는 별도의 보존 기한이 없습니다. 결과 outbox와 운영 서비스의 작업 요청·결과 버전·스테이션 작업 기록에도 보존 기한이나 삭제 코드가 없습니다.

`mission` 스키마의 표는 이제 19개입니다(임무 버전 표 3, S4b 표 5, V4의 `result_producer`·`result_outbox`, V5의 `execution_cancel`·`robot_residual_hold`·`robot_residual_hold_clearing`, V6의 `execution_judgment_record`, V7의 `host_instance`, V10의 `execution_journal_revision`, V11의 `execution_fault_release`, V12의 `execution_close`, V13의 `execution_close_confirmed`). 이 가운데 `result_producer`와 `host_instance`는 갱신하는 표이고(`host_instance`는 인스턴스마다 한 행을 심장박동마다 고침), 나머지 17개는 트리거가 UPDATE·DELETE·TRUNCATE를 막습니다. 데이터를 지우려면 스키마째 지웁니다.

셀 신호 값은 런처 재기동 시 처음 값으로 재설정됩니다.

운영 서비스가 멈췄다 다시 뜨면 스테이션 점유, 대기열, 상태 이력은 DB에 남아 그대로 이어집니다. 결과 수집은 `ops.result_cursor`의 순번부터 이어 읽습니다. 멈춘 시점에 `DISPATCHING`·`RETRYING`·`REWORKING`이던 것의 처리는 «작업 요청과 결과 회신»·«공유 스테이션 조정» 절에 있습니다.

### 현장 대역의 한계

런처 시계는 기동 직후 mimic 가상 시계를 실제 시각까지 맞춘 뒤, 이후 실제 시간 1초마다 따라잡도록 동작합니다. 가상 시각은 실제 시각보다 앞서지 않으며, 두 시각의 차이는 1초 이내로 유지됩니다.

자재 제시 자리인 `SEQ-IN-02.BIN-A`에는 항상 `ENGINE-COVER-A`가 배치되며, `RACK-204.S01`부터 `S04`까지의 슬롯은 초기에 비어 있습니다.

슬롯은 `pick_place` 작업이 성공할 때 제시 자리의 자재로 채워지며, 자동으로 비워지지 않습니다. 이는 기체의 보고를 근거로 한 것이며 독립된 설비 센서 등을 통한 확인은 아닙니다.

근거 윈도우의 앞 폭 안에 채워진 슬롯을 재사용할 경우, 스킬 실패가 발생했을 때 단위 실패 대신 운영자 보류로 처리될 수 있습니다.

`quadruped-01` 기체는 `pick_place` 케이퍼빌리티가 없으므로 연결 상태 장애만 발생할 수 있습니다.

신호는 일곱입니다. 공유 검사 스테이션의 일반 공정 신호 `inspection_ready`(처음 true)와 `inspection_complete`(처음 false), 작업 번호 송신·반환 텍스트 신호 `inspection_job`·`inspection_job_done`(처음 빈 문자열), 랙 자리 `RACK-204`의 `rack_present`(처음 false), 안전 신호 `guard_closed`(처음 true), 텍스트 신호 `lot_code`(처음 `LOT-0001`)로 구성됩니다. 운영 서비스가 전송 전에 `inspection_job`에 번호를 쓰고 설비 역할이 `inspection_job_done`에 반환합니다. 시간이 지나도 스스로 바뀌지 않고 일반 신호는 `POST /cell/signals/{name}` 로만 바뀌고 안전 신호 쓰기는 거부됩니다. 안전 신호는 프로세스 안 시험 대역 `Site.actuateSafety`(안전 계통이 셀 문을 여닫는 것을 흉내 냄, HTTP 로 열지 않음)로만 바뀝니다. 셀 대역 화면의 신호 표는 TEXT 신호에 조작 버튼을 두지 않습니다. 신호의 `observedAt`은 마지막으로 값을 쓴 시각이고, 한 번도 쓰지 않은 처음 값은 `observedAt`이 비어 있습니다.

신호 사양은 셀 대역 픽스처 코드에 상수로 정의되어 있으며, 화면 편집이나 버전 관리를 지원하지 않습니다.

신호 품질은 값, 관측 시각, «모름» 상태로만 구분되며, 응답 없음, 단절, 불확실 등의 품질 상태는 표시하지 않습니다.

`pick_place` 단위는 기체별 시드 추첨에 따라 약 6%의 확률로 자연 실패가 발생합니다. 슬롯 4개를 처리하는 `PrepareSequencedRack` 임무에서는 전체 과정 중 어딘가에서 실패할 확률이 약 22%이며, 실패 내역은 실행 목록에 표시됩니다.

화면 시험에서는 현장의 두 `pick_place` 단위 중 첫 번째가 강제로 실패하도록 설정되어 있습니다. 따라서 완주 추첨을 거치지 않으며, seed 0 환경에서 자연 실패가 섞이지 않습니다.

장애 주입 시 현장에서 진행 중인 태스크를 탐색하며, 대상이 없으면 «진행 중 태스크 없음» 오류로 거부됩니다. 작업 지시 직후에는 상태가 `ACCEPTED`이므로 다음 시계 진행이 이루어진 뒤에 장애를 주입해야 합니다.

스킬 실패 주입은 진행 중인 `pick_place`에 `SKILL_EXECUTION_FAILED`(`GRASP_FAILED`)를 전달하는 방식으로 수행됩니다.

연결 상태 장애 주입은 `OFFLINE` 또는 `CONNECTION_BROKEN`을 통해 단절을 유도하고 `ONLINE`으로 복구합니다. 동작 중인 로봇 단위는 단절 시 `IN_DOUBT` 상태로 전환되며, 복구 시 `RUNNING` 상태로 돌아옵니다.

mimic 제어 채널은 열지 않으므로 시계 밀기, 시계 모드, 시드 노출 기능이 제공되지 않습니다.

현장 장애 주입 엔드포인트 `POST /faults`는 기존의 `SKILL_EXECUTION_FAILED`, `CONNECTION`과 더불어 새 종류인 `CONTROL_AUTHORITY_LOST` 및 `CONTROL_AUTHORITY_REGAINED`를 지원합니다. `CONTROL_AUTHORITY_LOST`는 기체 단위 결함을 세워 다음 로봇 단위 시작 전에 실행을 막으며, 특정 실행에서 결함 수용을 수행해도 기체의 결함은 유지되어 다음 실행이 다시 막히므로, picasso 내에 결함 제거 호출이 없는 문제를 해결하기 위해 엔진 결함 목록을 직접 정리하는 `CONTROL_AUTHORITY_REGAINED`와 쌍으로 제공되어 현장 재기동 없이 기체를 복구할 수 있습니다. 이 두 종류는 `state`를 받지 않으며, `PAYLOAD_LOST`나 `LOCALIZATION_LOST` 같은 다른 지속 결함 및 전송 장애는 여전히 400 `UNSUPPORTED_FAULT`로 처리됩니다. 현재 화면의 장애 주입 양식에서는 이 두 종류를 고를 수 없으므로, Playwright 생애주기 시험은 엔지니어 모드의 운영 서비스 API인 `POST /api/faults`를 호출하여 직접 주입합니다.

어댑터 적합성은 기록 기능이 개방되지 않아 빌드와 인스턴스 모두 `UNTESTED` 상태로 유지됩니다.

기체 선언 전에 registry로 전송된 mimic 생존 보고는 거부되며, 거부된 보고는 어디에도 기록되지 않습니다. 기체 선언이 완료된 이후부터 정상적으로 보고가 연결됩니다.

`site/robots.json` 파일에서 `humanoid-01`에는 `dock-3`과 `bay-7`이라는 티칭 명칭이 지정되어 있고 `quadruped-01`에는 명칭이 없습니다. 런처 기동 시 이 정보가 주입되며, `quadruped-01`의 명칭을 기록하려 하면 «기체가 아는 명칭 없음»으로 처리됩니다.

registry는 티칭 명칭의 개수만 대조하며, 명칭 자체의 문자열은 검증하지 않습니다.

### 임무 편집의 한계

임무 편집기는 textarea 형태로 제공되며, 편집기 전용 JSON Schema 파일은 없습니다. 임무 정의에 대한 유효성 검증은 실행 호스트에서 직접 수행합니다.

기본 템플릿 세트는 데이터 정의, 랙 도착 대기, 운영자 보류 대기 세 가지로 구성되며, `mission-host/src/main/resources/mission-templates/` 경로에 위치합니다.

랙 도착 대기 템플릿은 120초 기한이 지나면 실행을 중단하고 `ABORTED` 상태로 전환합니다.

운영자 보류 대기 템플릿인 `ARRIVAL_WAIT_HOLD`는 20초 기한이 경과했을 때 중단하는 대신 대기 단위를 운영자 보류 상태로 전환합니다.

운영자 보류 대기를 활성화하면 이후 발행되는 모든 `PrepareSequencedRack` 작업 지시가 20초 대기로 시작됩니다. 이 상태에서도 기체는 다른 작업에 다시 배정될 수 있습니다.

런처 시계가 실제 시각을 따르므로, 랙 도착 대기 작업 지시가 내려진 뒤 2분 안에 `rack_present` 신호를 켜야 작업이 다음 단계로 진행됩니다.

셀 대역은 한 번 변경된 신호를 이전 상태로 되돌리지 않습니다. 따라서 신호를 한 번 켜 두면 이후의 대기 단위는 즉시 해제됩니다.

모의 실행 프로파일은 실패 모드를 제외한 사본을 사용하며, 무작위 추첨을 수행하지 않습니다.

임무 활성화 근거로는 해당 초안의 마지막 모의 실행 결과만 사용하며, 이전 버전의 모의 실행을 재실행하지 않습니다.

모의 실행 시에는 현장 시간값 대신 케이퍼빌리티 기본값을 사용합니다. 이로 인해 임무 검증 결과의 `basisVersion` 항목은 항상 비어 있습니다.

#18부터 엔지니어는 기본 두 임무 밖의 새 임무를 입력 프로파일(`POST /api/mission-profiles`, 입력 스키마와 선언형 매핑, 실행 식이나 스크립트 없음)로 등록할 수 있고, 등록한 프로파일은 바꾸거나 지울 수 없으며(`ops.mission_profile`), 예제는 `docs/examples/`에 있습니다.

### 판정과 권한

site는 적재 토큰과 운영자 토큰을 둘 다 받고, 운영 서비스는 운영자 토큰만 받고, 실행 호스트는 둘 다 받지 않습니다(실행 명령의 `env -u`). 브라우저에는 토큰을 보내지 않습니다.

운영 서비스는 선택으로 외부 주문 토큰 `PICASSO_INTEGRATION_TOKEN`을 받습니다. 하나의 공유 Bearer 토큰이 하나의 설정 출처(기본 `mock-mes`)를 뜻하며, 비교는 SHA-256 다이제스트의 상수 시간 비교입니다. 비어 있으면 `/integration/v1/...`는 모두 401입니다. 테넌트별 권한 체계는 아닙니다.

화면 API의 `X-Ops-Mode`·`X-Ops-User` 헤더는 모드와 사용자 이름을 고르는 값이지 인증이 아닙니다. 운영 서비스 API에 닿을 수 있으면 누구나 이 헤더를 붙일 수 있습니다. 헤더가 없거나 틀리면 400, 모드가 맞지 않으면 403으로 호출 전에 막습니다.

registry, 실행 호스트, 운영 서비스, 셀 대역은 모두 `127.0.0.1`에만 바인딩됩니다.

mimic gRPC 서비스는 picasso가 지정한 주소에 따라 모든 인터페이스에 바인딩됩니다. 인증 체계가 없는 기체 제어 API를 제공하며, 포트 번호는 `.env` 파일에 고정되어 있습니다.

배정 가능 판정에서 물어보지 못한 칸은 «모름»이고, «모름»이 하나라도 있으면 그 기체는 배정 불가입니다.

단위가 보류되면 운영자가 인시던트 상세 화면에서 직접 판단(완료 확인·재작업)을 내리거나 운영자 실행 취소로도 닫을 수 있습니다(운영자 보류 `OPERATOR_HOLD` 도 취소 대상, 다음 pump 에 `ABORTED` 로 종결). 둘 다 하지 않으면 실행이 종결되지 않으며 기체도 배정에서 빠집니다. 종결할 때 마지막 로봇 단위가 든 채였으면 잔여 파지 표시가 서서 사람이 빈손을 확인할 때까지 계속 빠집니다.

재작업을 선택하면 해당 단위를 다시 계획하며, 대기 단위의 경우 새로운 기한이 적용됩니다. 건너뛴 점검 단위를 재작업하면 해당 점검과 선행 이동이 함께 새 시도로 돌아가고 판단은 선행 이동의 인시던트에 기록됩니다. 운영 UI의 실행 제어 화면(`ExecutionControls`)에 있는 「건너뛴 대상 재작업」 폼은 운영자 모드에서만 보이며, 엔지니어 모드에는 운영자 모드에서 한다는 안내만 있습니다. 실행 호스트가 재작업할 수 있다고 싣는 대상(`reworkableTargets`)만 고를 수 있으며, 재작업 사유를 적고 현장 확인을 체크해야 보낼 수 있고, 보내면 기존 판단 API로 `REWORK`를 전달합니다.

완료 확인을 선택하면 추가 근거 없이 해당 단위를 완료 처리하고 다음 단위로 진행합니다.

판단은 현재 실행 중인 인스턴스의 인시던트에 대해서만 수행할 수 있습니다. 요청 시 인시던트 상세의 `instanceId`를 전달하며, 요청 도중 실행 호스트가 재기동되면 «실행 호스트가 재기동해 인스턴스가 다름» 오류로 거부됩니다.

재기동을 넘어온 운영자 보류도 지금 인스턴스의 인시던트로 다시 열려 같은 경로(인시던트 상세의 `instanceId` 로 판단)로 판단합니다. 판단은 그 인시던트에 붙습니다(picasso PR #100). 다시 연 인시던트는 근거 윈도우에서 그 인시던트 시각(`at`, 다시 연 시각)의 `RESUME_HOLD_REOPENED` 줄로 가릅니다. 결함 원문·파지 관측은 비어 있습니다(이 인스턴스가 관측하지 않음). 줄이 있다는 것만으로는 가르지 못합니다: 다시 연 뒤 30초(근거 윈도우 앞 폭) 안에 기한으로 새로 선 보류의 윈도우에도 그 줄이 실립니다. 근거: 통합 시험 `RestartRecoveryTest` 단계 4(다시 연 인시던트 상세의 `instanceId`·실행·단위로 재작업, 판단이 그 인시던트에 붙음), 단계 5(셋째 인스턴스의 재작업 뒤 보류는 그 줄을 윈도우에 들지만 시각이 달라 다시 연 것이 아님).

판단 요청에 대한 응답이 없으면 운영 서비스가 1초 뒤 인시던트 목록을 한 번 조회하여, 동일한 행위자와 동일한 결정이 요청 시점 이후에 등록되었는지 대조합니다.

실행 호스트가 요청 id를 저장하지 않으므로 처리 중인 상태를 구분하지 못합니다. 잠금 대기로 인해 뒤늦게 반영된 판단은 «반영 안 됨»으로 잘못 판정될 수 있으며, 운영 서비스와 실행 호스트가 동일한 머신에서 동작한다는 전제를 두고 있습니다.

장애 주입은 `INJECT_FAULT` 조작 기록으로 남으며 재조회를 수행하지 않습니다. 따라서 응답을 받지 못하면 실제로 장애가 주입되었는지 여부를 끝내 알 수 없습니다.

registry가 응답하지 않을 경우 기존 목록을 비우지 않고 직전 값에 «모름» 상태를 병합하여 유지합니다.

리비전 시험 실행기(`site-runner`)는 리비전 시험마다 자기 mimic을 따로 띄우므로 현장 기체의 보고와 상태를 바꾸지 않습니다.

- 부분 완료와 운영자 실행 취소: 판정의 «진행 중인 실행» 은 picasso `PhysicalState.occupiesRobot` 이라 부분 완료(`PARTIAL`) 실행의 기체도 뺍니다(리비전으로 남은 단위가 다시 돌 수 있어 기체가 비지 않음). 다만 해당 실행 자체의 리비전은 제 실행을 진행 중인 실행으로 세지 않아 그 기체에서 개정됩니다. 운영 서비스는 작업 지시 id 를 다시 쓰지 않아 리비전으로 닫지 못하므로, 운영자가 `POST /api/executions/{executionId}/cancel`(운영자 모드만, 본문 `{instanceId, reason}`, `instanceId` 는 실행 목록의 것)로 취소합니다. 운영 서비스가 실행 호스트 `POST /host/executions/{executionId}/cancel` 에 `{approverId(=X-Ops-User), reason, instanceId, requestId}` 를 보냅니다. 실행 호스트는 잠금 아래에서 판정한 뒤 취소 행(`mission.execution_cancel`)을 먼저 적고 미들웨어 `cancel` 을 부릅니다(못 적으면 500 `RECORD_WRITE_FAILED`, 취소하지 않음). 실제 중단은 다음 pump 가 하류를 읽고 확정: `ABORTED`, 일지 `SETTLED` 이벤트(사유 `ABORTED`), `ABORTED` 작업 응답이 송신 기록과 결과 outbox 로 갑니다(그래서 운영 서비스에 새 결과 버전). 결과는 200 본문 `result`: `CANCELED`(조작 기록 `SUCCEEDED`), `NOT_CANCELABLE`(종결한 실행, 이미 취소를 건 실행(`CANCELING` 또는 이 인스턴스 취소 행 있음), 실행 일지에 없는 실행), `NOT_FOUND`(지금 인스턴스에 없음), `REFUSED`(에이전트 승인자, REST 로는 안 남), 셋 다 `REJECTED`. 다른 인스턴스는 409 `INSTANCE_MISMATCH`. 조작 기록 `op` = `CANCEL_EXECUTION`, 대상 = 실행 id, 사유 = 입력 사유(호스트 표에도 남음). 실행 목록의 실행마다 `cancel` 칸 `{approverId, approverKind, reason, physicalState(취소를 건 때), recordedAt}`(없으면 null). 실행 목록의 실행마다 `permitBlock` 칸 `{unitId, withheld, robots:[{robotId, permit, reasons:[{source, cause, subject, resumeBasis, observedAt}]}], since}`(없으면 null). `withheld` 는 `START`·`REVISION_UPDATE`·`IN_DOUBT_LOOKUP`. 운영자 조작은 없고, 내기 전에 막힌 실행의 취소는 허가를 기다리지 않고 `ABORTED` 로 끝남. 근거: `WorkPermitHostTest` 의 `INHIBITED 면 …`·`INHIBITED 여도 …` 두 시험, `HostRestoreTest` 의 실행 칸 순서 13칸 대조. 하위에서 요청하지 않은 자체 취소가 보고되면 단위를 `ABORTED`로 끝내지 않고 운영자 보류(`OPERATOR_HOLD`)로 세우고 인시던트를 열며, 통상의 인시던트 판단(«완료 확인», «재작업»)이나 운영자 실행 취소로 해소합니다.
- 응답 없음 뒤 재조회(취소): 1초 뒤 `GET /host/executions?executionId=<그 실행>` 한 번. 인스턴스가 같고 그 실행의 `cancel.approverId` 가 행위자이며 `recordedAt` 이 보내기 직전 운영 서비스 시계 이후면 `CONFIRMED_APPLIED`, 실행이 없거나 그런 `cancel` 이 없으면 `CONFIRMED_NOT_APPLIED`, 인스턴스가 바뀌었거나 못 읽으면 확인 행 없음. 운영 서비스 시계와 DB 시계를 비교하므로 같은 머신 전제.
- 잔여 파지 표시와 기체 빈손 확인: 종결할 때 마지막 로봇 단위(계획 순서)의 파지 관측이 `HOLDING` 이면 표시가 서고, 해소할 때까지 판정 이유 «잔여 파지 확인 필요: <jobOrderId> <unitId>» 로 그 기체를 뺍니다(같은 작업 지시의 리비전도). 운영자가 현장에서 빈손을 확인한 뒤 `POST /api/robots/{robotId}/residual-holds/{markerId}/clear`(운영자 모드만, 본문 `{reason}`)로 해소합니다. 조작 기록 `op` = `CONFIRM_ROBOT_EMPTY`, 대상 `<robotId>/<markerId>`. 해소 200 은 `SUCCEEDED`, 404 `RESIDUAL_HOLD_NOT_FOUND`(그 기체에 그 표시 없음)·409 `RESIDUAL_HOLD_ALREADY_CLEARED` 는 `REJECTED`. 응답 없음 뒤 재조회는 미해소 목록 `GET /host/robots/residual-holds` 에 그 표시가 없으면 반영됨. 미해소 목록 읽기 `GET /api/robots/residual-holds` 는 모드와 무관, 호스트가 안 닿으면 503 `HOST_SILENT`.
- 결함 차단과 운영자 결함 수용(실행 호스트): 실행 목록의 각 실행에는 `blockedFaults`(지금 다음 단위를 막는 기체 결함 전부, 인시던트 상세 `blockedBy` 와 같은 결함 객체, 없으면 `[]`)와 `releases`(그 작업 지시의 수용 `{requestId, approverId, reason, faults, recordedAt}`, 작업 지시 id 로 읽어 재기동 전 인스턴스의 것도 실음, 없으면 `[]`)도 실립니다(S3a JSON 계약 §5.9). 실행 호스트 REST `POST /host/executions/{executionId}/release`(S3a JSON 계약 §5.10, 본문 `{approverId, reason, instanceId, sawFaults, requestId?}`)의 승인자는 늘 사람(`PERSON`)이며, 실행 호스트는 호스트 잠금 아래에서 운영자가 본 결함(`sawFaults`)과 지금 막는 결함을 견주고 수용 행을 적은 뒤 picasso `release` 를 부르고 판단 기록을 적습니다. 200 본문 `result` 는 `RELEASED`, `NOT_BLOCKED`(막는 결함 없음: 작업 허가만 막음, 종결한 실행, 막힘 없이 진행 중인 실행), `BLOCK_CHANGED`(본 결함과 지금 막는 결함이 다름), `NOT_FOUND`, `REFUSED`(에이전트 승인자, REST 로는 안 남)이고, 오류는 400 `BAD_REQUEST`, 409 `INSTANCE_MISMATCH`, 409 `REQUEST_ID_REUSED`(같은 요청 id 의 수용 행이 이미 있음), 415, 500 `RECORD_WRITE_FAILED` 입니다. 수용(RELEASED) 직후 picasso는 `blockedBy`가 빈 `[]`인 `RUNNING` 작업 응답을 한 번 내며, `jobResponse`는 곧바로 이 응답을 보여줍니다. 다음 pump가 이를 송신 기록과 결과 outbox에 한 번 기록하며, 반복된 수용 요청은 응답을 더하지 않습니다. 운영 서비스 `POST /api/executions/{executionId}/release` 와 운영 › 실행 화면의 `form[aria-label=결함 수용]`(h3 «결함 수용»)에서 부릅니다.
- 운영자 결함 수용(운영 서비스·화면): `POST /api/executions/{executionId}/release`. 운영자 모드만. 본문 `{instanceId, reason, sawFaults}`. 실행 호스트에는 `sawFaults` 의 `errorType` 과 `references` 의 `key`·`value` 만 옮겨 보냄. 조작 기록 `target` = 실행 id, `request` = `{"op":"RELEASE_FAULT_BLOCK","executionId","approverId","instanceId","sawFaults"}`. 결과 매김은 `RELEASED` 면 `SUCCEEDED`, `NOT_BLOCKED`·`BLOCK_CHANGED`·`NOT_FOUND`·`REFUSED` 와 실행 호스트 4xx 는 `REJECTED`, 무응답·5xx 는 `NO_RESPONSE`. 화면 자리는 운영 › 실행의 form «실행 취소» 아래 `form[aria-label=결함 수용]`(h3 «결함 수용»).
- 응답 없음 뒤 재조회(결함 수용): 1초 뒤 `GET /host/executions?executionId=<그 실행>` 한 번. 목록의 인스턴스가 요청한 인스턴스와 같고 그 실행의 `releases` 에 이 요청 id 가 있으면 `CONFIRMED_APPLIED`, 실행이 없거나 그 요청 id 가 없으면 `CONFIRMED_NOT_APPLIED`, 인스턴스가 바뀌었거나 못 읽으면 확인 행 없음(null). 취소와 달리 요청 id 로 찾으므로 시계를 견주지 않음.
- 세 조작 모두 사전 거부(400 `ACTOR_REQUIRED`, 403 `MODE_NOT_ALLOWED`, 400 `CANCEL_BAD_REQUEST`·`CLEAR_BAD_REQUEST`·`RELEASE_BAD_REQUEST`, 400 `REASON_REQUIRED`)는 조작 기록에 남지 않습니다.
- 한계: 표시는 시각 순서가 아니라 계획 순서의 마지막 로봇 단위로 정합니다. 화면의 현장 확인 체크박스는 서버가 집행하지 않습니다(사유만 서버 필수). 표시는 실행 호스트의 운영 정책(ADR 41)이고 picasso 보장이 아닙니다(picasso 는 종결한 파지를 정보로 둠, §15.146). 스킬이 취소를 받지 않으면(`CANCEL_UNSUPPORTED`) 미들웨어는 도는 단위를 끝까지 두고 다음 경계에서 멈춥니다. 호스트는 그 거부를 읽지 않아 화면에 보이지 않습니다.

## 현장 시간값 상세

앞 폭, 뒤 폭, `inDoubtGrace`는 결과 판정에 직접 영향을 미치는 값입니다. 반면 `stallWindow`는 정체 상태를 화면에 표시하는 시점에만 관여합니다.

변경된 연결 기준은 재기동 없이 다음번 기체 목록 읽기 주기부터 즉시 적용됩니다.

실행 호스트는 원본 표를 직접 읽지 않고 운영 서비스 뷰인 `ops.site_timings_current`를 1초마다 읽기 전용으로 조회하며, 다음 pump 주기부터 적용합니다. 현재 적용 상태는 `GET /host/site-timings` 엔드포인트로 확인합니다.

실행 호스트와 통신할 수 없으면 «모름», 뷰를 아직 읽지 못했으면 «미적용»으로 표시됩니다. «미적용» 상태인 동안에는 신규 작업 지시를 받지 않으며, 기체별 배정 가능 여부의 이유에도 «미적용»이 명시됩니다. 이 상태에서 제출된 작업 지시는 미배정 상태로 남습니다.

시간값 설정은 현장 전체에 하나의 값으로 적용되며, 임무별로 덮어쓸 수 없습니다.

운영 서비스가 뷰를 변경하면 실행 호스트의 조회 로직도 함께 변경되어야 합니다. 통합 시험인 `SiteTimingsTest`의 «실제 현재 버전 뷰의 칸은 실행 호스트가 읽는 뷰 상수의 칸과 이름과 형이 같다» 시험 메서드가 뷰 컬럼의 이름과 타입을 직접 대조하여 검증합니다.

1초의 읽기 주기로 인해 운영 서비스의 최신 버전과 실행 호스트에 적용된 버전 사이에 차이가 발생할 수 있으며, 이 두 수치는 화면에 각각 따로 표시됩니다.

뷰 읽기에 실패하면 마지막으로 정상 적용된 버전을 유지하고 읽기 실패를 표시합니다. 뷰의 값이 picasso 허용 범위를 벗어난 경우에는 적용을 거부하고 해당 버전과 이유를 표시합니다.

근거 기한은 해당 단위가 완료되는 순간의 뒤 폭 값으로 결정됩니다. 단위가 완료된 이후 뒤 폭을 변경하더라도 이미 완료된 단위에는 영향을 주지 않습니다.

인시던트에 기록되는 값은 해당 라운드를 봉인할 당시의 설정 버전과 4가지 시간값입니다. 따라서 단위 완료 시점에 근거 기한을 산출했던 시간값과 서로 다를 수 있습니다.

설비 대기 단위의 기한 판정은 임무 정의에 명시된 `deadlineSeconds` 값을 기준으로 수행하며, 현장 시간값은 사용하지 않습니다.

현장 설정 버전, `inDoubtGrace`, `stallWindow` 값은 picasso 데이터 내보내기 항목에 포함되지 않습니다.

보고의 오래됨 판정은 운영 서비스가 registry의 실제 수신 시각을 기준으로 수행합니다. 연결 기준 하한이 60초로 설정되어 있으므로, 통신이 단절된 뒤 `REPORT_STALE` 상태로 전환되어 기체 배정이 불가능해질 때까지는 실제 1분 이상의 시간이 소요됩니다.

## 시험

### 시험 수

Kotlin 네 모듈은 2026-11-02에 `feat/retirement-port` 브랜치의 `<모듈>/src/test` 아래 `.kt` 파일에서 `@Test` 애너테이션(정규식 `@Test\b`, 그래서 `@TestInstance`·`@TestMethodOrder`는 세지 않음)이 붙은 함수를 세었습니다. 매개변수 시험(`@ParameterizedTest`·`@RepeatedTest`·`@TestFactory`)은 없습니다. 이렇게 센 수는 네 모듈 모두 아래 표의 실행 기록과 같습니다. UI는 2026-11-02 같은 브랜치 로컬에서 `cd ui && npx vitest run --maxWorkers=2` 로 실행해 29개 파일 285개 통과를 확인했습니다. 같은 기계에서 작업자 수를 정하지 않고 돌리면 5초 시험 시간 제한을 넘는 시험이 생겨 실패로 끝날 수 있었습니다.

| 모듈 | 시험 수 | 실행으로 확인한 곳 |
|---|---|---|
| `ops-service` | 498(그대로) | 로컬 시험 결과 XML 498건, 실패 0 |
| `mission-host` | 223(215 에서 8 늘어남: `RegistryRetirementsTest` 5, `RetirementHostTest` 3) | 로컬 시험 결과 XML 223건, 실패 0 |
| `site` | 41(그대로) | 로컬 시험 결과 XML 41건, 실패 0 |
| `e2e` | 77(그대로, 시험 클래스 21개) | 로컬 시험 결과 XML 77건, 실패 0 |
| `ui`(vitest) | 285(282 에서 3 늘어남: `PermitBlockNotice.test.tsx` 3, 시험 파일 29개) | 2026-11-02 `feat/retirement-port` 브랜치 로컬 실행, 실패 0 |
| `ui`(Playwright) | 2(`lifecycle.spec.ts` 1, `station.spec.ts` 1) | `./gradlew installDist` 후 `feat/retirement-port` 브랜치 로컬 실행, 기본 설정 `npx playwright test` 1 통과, `npx playwright test --config=playwright.station.config.ts` 1 통과 |

모듈마다 잰 때가 달라 합계를 내지 않습니다.

### E2E 통합 시험 내부 구조

현장 가상 시계를 시험이 직접 진행합니다. 호스트 기동 전에 `ops` 스키마를 올려 설정 버전 1 적용 상태로 시작합니다.

임무 버전 시험에서는 실행 호스트만 같은 포트로 다시 띄워 활성 버전이 유지되는지 확인합니다.

장애 및 인시던트 시험은 새 스택에서 스킬 실패를 먼저 검증하며, 오래됨 단계에서만 실제 60초를 넘겨 대기합니다.

재기동 시험인 `RestartRecoveryTest`는 6단계로 약 81초가 걸립니다. 재기동은 `E2eStack.restartHost()`로 수행하며, 새 명령 수신 여부는 site의 `Site.taskHistory`로 확인합니다.

실제 대기가 필요한 구간은 1분 넘게 기다리는 오래됨 확인과 20초 동안 대기하는 운영자 보류입니다.

`StationScenarioTest`는 데이터 임무 `StationInspection`(예제 `docs/examples/mission-profile-station-inspection.json`)을 활성화하고 호출 계약 `station-check`를 배포한 뒤 스테이션 `inspection-1`에 작업 둘을 넣어, 두 번째가 `QUEUED`로 기다리고 첫 작업이 로봇 물리 완료만으로는 `RUNNING`에 머물다가 설비 완료 신호를 켜야 `COMPLETED`가 되는 것을 봅니다. `MixedResourceScenarioTest`는 `InspectAsset`에 필수 자원을 등록한 뒤 외부 주문, 호출 계약 호출, 수동 요청, 기존 `POST /api/job-orders` 네 경로가 모두 같은 스테이션 대기열을 지나 차례로 실행되고 외부 주문 재전송이 같은 작업 요청을 돌려주는 것을 봅니다. `ManufacturingScenarioTest`는 스테이션을 쓰지 않습니다. 같은 검사 호출 계약으로 낸 작업 요청 둘이 두 기체에 하나씩 배정되고, 시운전을 마쳤어도 `pick_place`가 없는 기체는 순차 공급(`PrepareSequencedRack`) 후보에서 빠지며, 운영자 보류에 선 순차 공급을 재작업으로 해소하고 끝낸 뒤에도 같은 요청 ID 재전송이 같은 작업 요청·작업 지시·변환 결과를 돌려주는 것을 봅니다. `StationPhysicalStateParityTest`는 스택을 띄우지 않고 스테이션 조정기의 확인 필요 물리 상태 목록(`StationCoordinator.REVIEW_PHYSICAL_STATES`)을 picasso `PhysicalState`와 이름으로 맞댑니다. 운영 서비스 main과 그 시험에는 picasso 미들웨어가 클래스패스에 없어 이 모듈에서 봅니다.

`StationLateCompletionTest`는 `StationScenarioTest`와 같은 데이터 임무 `StationInspection`과 호출 계약 `station-check`로 스테이션 `inspection-1`(단계 기한 20초)을 씁니다. X를 보내고 로봇을 끝내지 않아 실행 기한 초과 → `REVIEW_REQUIRED` → `CONFIRM_CLEAR`. 작업 번호 쌍(`inspection_job`·`inspection_job_done`) 변형: Y가 다른 기체로 `RUNNING`, `inspection_job`에 Y의 번호가 쓰여 있음, 시험이 설비 역할로 X의 번호와 완료 true를 쓰면 Y는 «설비 완료 신호 수신, 작업 번호 불일치; 현장 확인이 필요합니다»로 `REVIEW_REQUIRED`이고 점유 유지, 신호를 내리고 관측 재개 뒤 Y의 번호와 완료를 쓰면 `COMPLETED`. 쌍이 없는 변형: X 해제 뒤 스테이션이 격리되고 Y는 `WAITING_READY`(작업 요청 없음)에 머물며, 완료 신호가 켜져 있으면 `CONFIRM_STATION_IDLE`이 409, 끄고 판단하면 격리가 풀리고 Y가 실행되어 완료. 두 변형 모두 `build/reports/station-late-completion-echo.json`, `station-late-completion-quarantine.json`에 근거를 남깁니다.

`WorkPermitTest` 는 `guard_closed` 가 `humanoid-01`·`quadruped-01` 두 기체를 맡는다는 대응 파일(임시 파일)을 `E2eStack.start(permitMapping = …)` 로 실행 호스트에 넘깁니다. `Site.actuateSafety("guard_closed", "false")` 로 셀 문을 엽니다(안전 계통 대역, HTTP 아님). 운영 서비스의 신호 쓰기 `POST /api/cell/signals/guard_closed` 는 `REJECTED`·`SAFETY_SIGNAL_READ_ONLY` 입니다. `POST /api/job-orders` 로 낸 작업 지시의 실행은 단위가 모두 `PENDING` 이고 `permitBlock`(`withheld` `START`, 그 기체 `INHIBITED`, 막는 주체 `guard_closed=false`)을 들고 기다리며 기체에 태스크가 나가지 않습니다. 작업 요청 상세의 실행에도 같은 `permitBlock.robots` 가 실립니다. 문을 닫으면(`"true"`) 실행이 `PHYSICALLY_DONE` 으로 끝나고 `permitBlock` 은 null 입니다.

`SkeletonTest`는 `ops.flyway_schema_history`의 성공한 버전이 1부터 19까지인지 봅니다.

시험 클래스는 18개로, `SkeletonTest`, `LifecycleTest`, `AdapterTest`, `CommissioningTest`, `SiteSettingsTest`, `JobOrderTest`, `MissionVersionTest`, `SiteTimingsTest`, `FaultIncidentTest`, `RestartRecoveryTest`에 `StationScenarioTest`, `MixedResourceScenarioTest`, `ManufacturingScenarioTest`, `StationPhysicalStateParityTest`, `StationLateCompletionTest`, `SourceObservationTest`, `UpstreamReplacementTest`, `WorkPermitTest`가 더해졌습니다. 도우미 파일은 그대로 `E2eStack`, `ExecutionDriver`, `Commissioned`입니다. `E2eStack.start(integrationToken = …)`로 외부 주문 API를 켠 스택을 띄웁니다. `E2eStack.start(permitMapping = …)`로 작업 허가 대응을 선언한 스택을 띄웁니다.

### 입증 근거 통합 시험 메서드

통합 시험 소스 파일 경로는 `e2e/src/test/kotlin/dev/picasso/ops/e2e/<클래스>.kt`이며, 메서드 이름 뒤의 괄호는 `main 511d5bc` 기준 행 번호입니다. 옛 목록의 행 번호는 `511d5bc`에서 다시 대조해 모두 같았습니다. `StationLateCompletionTest`의 행 번호는 PR #26 기준이고, 다른 클래스의 행 번호는 PR #26에서도 같습니다. `SourceObservationTest` 의 행 번호는 `feat/source-observation` 브랜치 기준입니다. `UpstreamReplacementTest` 의 행 번호는 `feat/upstream-alternatives` 브랜치 기준입니다. `WorkPermitTest` 의 행 번호는 `feat/work-permit` 브랜치 기준입니다.

`LifecycleTest`:

- `엔지니어가 선언하면 CLAIMED 이고 첫 보고를 기다린다`(51)
- `보고가 오면 CONFIRMED 이고 바인딩 말고는 막힘이 없다`(63)
- `운영자가 사유와 함께 퇴역시키면 RETIRED 이다`(73)
- `퇴역 뒤에도 보고가 오면 운영자가 풀 막힘으로 보인다`(81)
- `복귀하면 퇴역이 풀리고 바인딩 말고는 막힘이 사라진다`(102)

`CommissioningTest`:

- `시험 전 활성화는 ACTIVATION_REFUSED 이고 시험을 요청하면 현장 실행기가 TESTED 로 올린다`(118)
- `명칭을 기록하면 티칭한 기체는 시운전 완료가 되고 티칭 안 한 기체는 CONTRADICTED 로 막힌다`(163)

`JobOrderTest`:

- `InspectAsset 작업 지시가 배정되어 끝나고 작업 응답이 붙으며 임무 버전은 코드 정의다`(89)
- `PrepareSequencedRack 작업 지시가 pick_place 가 있는 기체에 배정되어 셀 대역 신호로 E2 에 이른다`(113)

`SiteSettingsTest`:

- `엔지니어가 버전 1 위에서 60초로 바꾸면 버전 2 이고 조작 기록에 성공 행이 남는다`(85)
- `마지막 보고가 60초보다 오래되면 근거 버전 2 의 오래됨으로 막힌다`(101)
- `버전 2 위에서 3600초로 바꾸면 버전 3 이고 다음 읽기에서 오래됨이 풀린다`(120)
- `지난 기준 버전으로 바꾸려 하면 버전 충돌로 거부되고 조작 기록에 거부 행이 남는다`(132)
- `범위 밖 값과 운영자 모드는 보내기 전에 막히고 조작 기록에 남지 않는다`(145)

`SiteTimingsTest`:

- `엔지니어가 시간값을 바꾸면 버전 2 이고 실행 호스트가 수 초 안에 버전 2 를 적용한다`(99)
- `랙 도착 대기 버전으로 낸 작업 지시가 신호 없이 기한을 넘기면 SIGNAL_DEADLINE 인시던트가 설정 버전 2 와 그 값을 싣는다`(114)
- `둘째 작업 지시가 기다리는 중에 버전 3 으로 바꾸면 기한을 넘긴 인시던트는 버전 3 을 든다`(144)
- `범위 밖 시간값은 칸 이름을 실은 SETTING_OUT_OF_RANGE 이고 운영 서비스의 범위 사본은 picasso 범위와 같다`(170)
- `실제 현재 버전 뷰의 칸은 실행 호스트가 읽는 뷰 상수의 칸과 이름과 형이 같다`(203)

`MissionVersionTest`:

- `데이터 정의를 초안 저장하고 검증하고 모의 실행을 통과시킨 뒤 사유를 적어 활성화하면 버전 1 이다`(81)
- `신호 사양에 없는 신호를 기다리는 초안의 활성화는 SIGNAL_NOT_IN_SPEC 거부 카드로 막히고 활성 버전은 그대로다`(131)
- `버전 1 로 도는 실행 중에 버전 2 를 활성화하면 옛 실행은 버전 1 로 끝나고 새 작업 지시는 랙 도착을 기다렸다가 신호를 켜면 끝난다`(156)
- `실행 호스트를 같은 포트로 다시 띄워도 활성 버전은 2 이고 새 작업 지시가 버전 2 로 선다`(231)

`FaultIncidentTest`:

- `단계 1 데이터 정의 버전으로 도는 pick_place 에 스킬 실패를 넣으면 단위가 FAILED 이고 GRASP_FAILED 인시던트가 두 버전과 함께 보인다`(96)
- `단계 2a 로봇 단위가 도는 중 연결을 OFFLINE 으로 바꾸면 시계를 밀지 않아도 실행이 IN_DOUBT 이고 ONLINE 이면 RUNNING 으로 돌아온다`(150)
- `단계 2b 유휴 humanoid-01 을 OFFLINE 으로 두고 연결 기준을 60초로 줄이면 실제 시간으로 오래됨 막힘이 서고 ONLINE 이면 신선으로 돌아온다`(174)
- `단계 3 보류 버전으로 낸 작업 지시가 신호 없이 기한 20초를 넘기면 운영자 보류이고 인시던트가 미해결이며 보류 중이다`(218)
- `단계 4 운영자가 사유를 적어 재작업을 판단하면 대기가 새로 시작되고 신호를 켜면 끝나며 판단자와 사유가 남는다`(258)
- `단계 5 신호를 되돌린 뒤 또 하나의 보류에서 완료 확인을 판단하면 단위가 근거 없이 완료되고 그 표시가 선다`(294)
- `단계 6 모드가 틀리거나 사유가 없으면 사전 거부이고 보류가 아닌 단위의 판단과 도는 태스크 없는 스킬 실패는 거부로 남는다`(324)

`RestartRecoveryTest`:

- `단계 1 도는 실행이 있을 때 다시 띄우면 같은 작업 지시가 새 인스턴스에서 끝난 단위는 끝난 대로 도는 단위는 도는 대로 다시 서고 판단 기록으로 앞선 단위의 근거 등급이 넘어와 도달 근거 등급이 E2 이다`(107)
- `단계 2 도는 InspectAsset 을 다시 띄워도 시계를 밀기 전 기체의 태스크 로그가 그대로이고 끝까지 가도 태스크마다 ACCEPTED 가 한 번뿐이다`(149)
- `단계 3 작업 지시를 받은 뒤 다른 임무 버전을 활성화하고 다시 띄우면 받은 때의 임무 버전으로 다시 서고 그 버전으로 끝난다`(189)
- `단계 4 운영자 보류에 선 실행을 두 번 다시 띄우면 기한을 다시 기다리지 않고 보류 그대로 서고 새 인스턴스가 다시 연 인시던트로 판단하며 그 보류 응답이 매번 재기동 중복으로 적히고 송신은 한 번뿐이며 같은 인스턴스에서 재작업 뒤 다시 선 보류는 송신이다`(210)
- `단계 5 설비 대기 보류를 재작업으로 판단한 뒤 다시 띄우면 대기가 재작업 때 시작한 기한을 이어 재고 재작업 판단 뒤 응답과 내용이 다른 그 보류 응답은 송신이며 옛 인시던트와 그 판단은 earlier 에만 있다`(261)
- `단계 6 재기동 뒤 이전 인스턴스의 인시던트와 판단이 보이고 이전 인스턴스를 실은 판단은 INSTANCE_MISMATCH 로 거부된다`(313)

`SkeletonTest`:

- `ops 스키마는 운영 서비스가 올리고 조작 기록은 비어 있다`(51)

`StationScenarioTest`:

- `shared station serializes two real mission calls and requires fresh equipment completion`(20)

`MixedResourceScenarioTest`:

- `MES operator contract manual and legacy submissions share the same required resource and stable receipts`(24)

`ManufacturingScenarioTest`:

- `shared inspection contract and evidence gated supply preserve upstream identity across rework`(31)

`StationPhysicalStateParityTest`:

- `확인 필요 물리 상태는 정착한 비성공 상태와 운영자 보류와 결과 불명이다`(15)

`StationLateCompletionTest`:

- `작업 번호 반환 스테이션은 해제한 이전 작업의 늦은 완료 신호를 실제 스택에서 다음 작업의 완료로 채택하지 않는다`(46)
- `작업 번호 반환이 없는 스테이션은 보낸 작업을 해제하면 격리하고 현장 확인 후 격리 해제 전에는 실제 스택에서 다음 작업을 보내지 않는다`(69)

`SourceObservationTest`:

- `출발 미관측은 확인 대기로 보류되고 사람의 출발 자리 확인을 기록하면 같은 작업 지시가 게이트를 지나 실행이 선다`(50)

`UpstreamReplacementTest`:

- `상위 시스템은 출발 결품의 대체 위치를 상태 조회로 읽고 앞선 외부 주문을 이은 새 외부 주문으로 고쳐 보내 실행이 서고 연결이 양쪽에 보인다`(55)

`WorkPermitTest`:

- `셀 문이 열리면 실행이 새 명령 없이 기다리며 막은 기체와 주체와 풀릴 근거가 실행 목록에 보이고 닫히면 끝까지 간다`(49)

### Playwright 화면 시험

화면 시험은 `ui/e2e/lifecycle.spec.ts` 와 `ui/e2e/station.spec.ts` 의 시험 하나씩입니다. 기본 설정 `ui/playwright.config.ts` 는 `testMatch` 로 생애주기만 돌고, 생애주기 시험이 끝에서 registry 를 멈추기 때문에 공유 스테이션 시험은 `ui/playwright.station.config.ts` 로 새 스택을 씁니다. CI 는 둘을 차례로 돕니다. 배포본은 먼저 `./gradlew :site:installDist :mission-host:installDist :ops-service:installDist` 로 만듭니다.

```bash
./gradlew :site:installDist :mission-host:installDist :ops-service:installDist
cd ui
npx playwright test
npx playwright test --config=playwright.station.config.ts
```

시험을 실행할 때 띄우는 스택은 Mock MES 콜백 수신기(`ui/e2e/mock-mes-callback.mjs`, 포트 8786, 키마다 첫 시도는 503 으로 응답), Postgres(compose 프로젝트 `picasso-ops-e2e`, 시작할 때와 끝날 때 `down -v`), 런처, 재기동 제어가 붙은 실행 호스트(제어 포트 8789), host-link 프록시(`ui/e2e/host-link.mjs`, 프록시 8791, 제어 8790), 외부 주문 토큰과 콜백이 켜지고 프록시를 거쳐 호스트를 부르는 운영 서비스(제어 포트 8788), 화면 preview 4173 입니다. 시험 시간 제한은 600초, 작업자는 1 입니다.

#### 생애주기 시험

`ui/e2e/lifecycle.spec.ts` 의 시험 `화면에서 기체 생애주기와 어댑터 등록을 한 번 돌고 registry 를 멈추면 모름을 본다` 는 다음 순서로 검증합니다.

1. 로봇 추가의 도입 준비 안내(새 제조사, 기존 유형)를 확인하고, 기체 선언, `CONFIRMED`, 퇴역, 퇴역 뒤 보고, 복귀, 조작 기록, 어댑터 제품·빌드·인스턴스 등록을 거칩니다.
2. 리비전 제출, 시험 요청, 실행기의 `TESTED`, 활성화, 바인딩, 명칭 기록으로 `humanoid-01` 시운전을 마칩니다. `quadruped-01` 은 «기체가 아는 명칭 없음»으로 막힙니다.
3. 연결 기준 120초의 현장 설정 버전 2 와 운영자 변경 거부를 보고, `stallWindow` 600초 버전 3 과 실행 호스트 반영을 봅니다.
4. 운영자가 작업 지시 폼으로 `InspectAsset` 을 내면 `POST /api/work-requests/manual` 이 202 이고 출처 `ops:manual`, 요청자 `operator/local` 입니다. `humanoid-01` 에 배정되어 «코드 정의» 실행이 `PHYSICALLY_DONE` 으로 끝납니다. «동일 작업 요청 확인»은 새로고침 뒤에도 같은 작업 요청을 돌려주고(`duplicate` true) 실행 목록에는 그 작업 지시가 한 행뿐입니다.
5. 호출 계약 `asset-check` 를 작성·매핑 미리보기·공개하고 실행 배포 v2 를 적용한 뒤 배정 가능을 확인하고 작업 요청을 냅니다. 응답은 `ACCEPTED` 이고 배포 버전 2, 임무 버전 `CODE` 이며, 같은 요청 확인은 «추가 실행 없음»입니다.
6. 외부 주문 API: 토큰 없이 401, 새 외부 주문 202(`ACCEPTED`), 같은 외부 주문 재전송 200(같은 작업 요청 id), 같은 키에 다른 입력 409, 빈 입력은 202 에 `REJECTED` 입니다. 상세를 조회하지 않아도 결과 수집이 결과 v1 을 저장하고, Mock MES 콜백은 두 번째 시도에 받습니다. 수신 확인은 토큰 없이 401, 틀린 해시 409, 맞는 해시 200 이고 다시 보내면 같은 응답입니다. 화면에는 «v1 수신 확인됨», 전달 이력 `DELIVERED`, 운영 개요의 «수집 중»이 보입니다.
7. 호출 계약을 공개 중지하면 그 계약 호출은 202 에 `REJECTED` 입니다. 이어 데이터 정의 초안 저장, 검증, 모의 실행, 활성화로 «버전 1 (활성)» 을 만듭니다.
8. `rack_present` 신호를 쓰고 슬롯 하나짜리 `PrepareSequencedRack` 에 스킬 실패를 넣어 `GRASP_FAILED` 인시던트를 봅니다. 스킬 실패로 humanoid-01 이 부품을 든 채 실행이 끝나 잔여 파지 표시가 서며, 운영자 모드에서 «잔여 파지 확인» 표에 그 행이 보이고, 다음 작업 지시 폼의 기체별 배정 가능에서 humanoid-01 이 «불가»(이유 «잔여 파지 확인 필요: <작업 지시 id> RACK-204.S01»)임을 확인합니다. «기체 빈손 확인» 폼에 근거를 적고 확인 체크를 해 보내면 표시가 해소되고 humanoid-01 이 «가능» 으로 돌아옵니다.
9. 운영자 보류 대기의 실제 20초 기한 동안 엔지니어 판단 버튼이 없음을 확인하고, 운영자 재작업, 신호 켜기, 실행 종료를 거쳐 «사람이 판단함» 을 봅니다. 보류 버전이 DB 에 남으므로 이 단계를 뒤쪽에 둡니다.
10. 송신 기록에서 그 작업 지시의 처분 «송신», «재기동 중복» 0행, «그 가운데 재기동 중복 0건» 을 봅니다.
11. `PrepareSequencedRack`의 활성 버전 2(운영자 보류 대기)에서 `rack_present` 신호를 끄고 슬롯 S03에 대한 작업 지시를 제출합니다. 랙 도착 대기 단위가 실행되는 동안(로봇 태스크 없음) `POST /api/faults`로 `CONTROL_AUTHORITY_LOST`를 주입하여 `raised` true와 빈 `terminated`를 확인합니다. 신호를 켜면 실행 행에 «결함 차단 1건»과 `OPERATOR_HOLD`가 표시되고 pick_place 단위는 `PENDING` 상태가 됩니다. 운영자 모드의 «결함 수용» 양식에 `CONTROL_AUTHORITY_LOST` 결함 카드가 표시되며, 사유를 입력하고 현장 확인을 거쳐 결함 수용을 완료하면 행에 수용 기록이 표시되고 «결함 차단»이 사라집니다. 이후 실행이 `PHYSICALLY_DONE`에 도달하고 실행 호스트의 `jobResponse.blockedBy`가 비어 있으며 `releases`에 항목이 1건 기록된 것을 확인한 뒤, 마지막으로 `CONTROL_AUTHORITY_REGAINED`를 호출하여 결함을 지우고 `cleared` true를 검증합니다.
12. 새 임무 `LineAssetCheck` 를 화면에서 등록, 초안 저장, 검증, 모의 실행, 활성화하고 호출 계약 `line-asset-check` 에 임무 버전 1 을 배포·공개한 뒤 작업 요청을 내어 `PHYSICALLY_DONE` 까지 봅니다.
13. 검증 도구의 «제조 시나리오 검증»에서 두 작업 요청을 비교해 «계약 또는 실행 배포가 다릅니다» 를 봅니다.
14. 결과 불명: host-link 가 실행 호스트의 수락 응답 직후 연결을 끊으면 외부 주문 `MES-RESTART-1` 은 202 에 `IN_DOUBT` 이고 결과 수집은 `UNAVAILABLE` 이 됩니다. 운영자가 현장 확인(`IN_PROGRESS`)을 기록하고, 호스트에서 실행이 `PHYSICALLY_DONE` 이 되어도 결과 v1 은 아직 404 입니다. 운영 서비스를 재기동해도 결과 커서 순번은 그대로이고 재전송은 200 에 같은 작업 요청이며 호스트 제출 수는 1 만 늘어 있습니다. 실행 호스트를 재기동해도 결과 피드는 같습니다. 연결을 되살리면 결과 v1 이 200 이 되고 작업 요청은 `ACCEPTED` 로 올라가며 호스트 제출 수는 여전히 1 만 늘어 있습니다.
15. 화면이 존재하지 않는 API(404)를 부르지 않았고 주요 API 가 200 을 받았는지 확인합니다.
16. 마지막으로 registry 를 멈추고 «모름» 과 직전 값 목록을 봅니다.

생애주기 시험은 14단계에서 운영 서비스와 실행 호스트를 재기동합니다. 실행 중인 실행의 복원(같은 태스크로 다시 붙기, 재기동 중복 처분)은 통합 시험 `RestartRecoveryTest` 가 보며, 생애주기 시험의 송신 기록 단계 주석도 그렇게 적고 있습니다.

#### 공유 스테이션 시험

공유 스테이션 시험은 `ui/e2e/station.spec.ts` 의 시험 `공유 스테이션은 설비 준비 후 두 미션을 순차 실행하고 새 완료 근거로 해제한다` 하나를 돕니다. 운영 서비스 API 로 `humanoid-01` 시운전을 마치고 호출 계약 `shared-inspection` 을 공개한 뒤, 화면에서 스테이션 `inspection-1` 과 임무 필수 자원 `InspectAsset → inspection-1` 을 등록합니다. 운영자가 화면에서 스테이션 작업 A 를 내면 `WAITING_READY`(작업 요청 없음)이고, 호출 계약으로 낸 B 는 202 에 스테이션 작업 `QUEUED` 입니다. 준비 신호를 켜면 A 가 `RUNNING`, B 는 `QUEUED` 입니다. A 는 로봇 물리 완료 뒤에도 `RUNNING` 이고 완료 신호를 켜야 `COMPLETED` 가 됩니다. 그 뒤 B 는 `WAITING_READY` 이고 작업 요청은 `WAITING_RESOURCE` 입니다. 완료 신호를 끄면 B 가 `RUNNING`, 물리 완료와 완료 신호로 `COMPLETED` 입니다. 근거 응답의 `schema` 는 `ops.station.evidence/1`, `explanationMayChangeState` 는 false 입니다. 이 시험과 생애주기 시험은 실행할 때마다 `docs/screens/style-preview/` 의 `*-fullstack.png` 를 다시 찍습니다.

### 스크린숏 촬영

촬영은 CI 와 분리된 설정으로 합니다. 스택은 생애주기 시험과 같고 시험 디렉터리만 `ui/screens`(`capture.spec.ts`)입니다.

```bash
cd ui && npx playwright test -c playwright.screens.config.ts
```

실행하면 `docs/screens/img/` 의 `NN-*.png` 를 지우고 대표 21장(1440×900 데스크톱 19장, 390px 폭 모바일 2장)을 다시 찍은 뒤 `docs/screens/img/INDEX.md` 를 다시 씁니다. INDEX 에는 실행마다 바뀌는 작업 지시 id, 인스턴스 id, 시각을 자리 표시로 바꿔 적습니다. 찍기 전에 화면에 보여야 할 문구를 단언하고 틀린 상태면 찍지 않고 실패합니다.

조건은 배율 1, 밝은 색 구성, `ko-KR`, 작업자 1, 시험 시간 제한 1,500,000ms 입니다. 대역 응답으로 그린 것은 `14-operations-restart-restored-mock.png` 한 장입니다. PR #24 기록으로 한 번 촬영에 약 7분이 걸렸습니다. Postgres 는 compose 프로젝트 `picasso-ops-e2e` 를 시작과 끝에 `down -v` 하므로 그 프로젝트의 데이터는 남지 않습니다.

## 단계별 설계 스펙 및 JSON 계약

| 단계 | 설계 스펙 | JSON 계약 |
|---|---|---|
| S1a, S1b, S1c | `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md` | |
| P2, S1d | `docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md` | |
| S2 | `docs/superpowers/specs/2026-10-08-s2-site-settings-design.md` | |
| P3 | `docs/superpowers/specs/2026-10-08-p3-mission-definition-versions-design.md` | |
| S3a | `docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md` | `docs/superpowers/specs/2026-10-08-s3a-json-contract.md` |
| S3b | `docs/superpowers/specs/2026-10-08-s3b-mission-versions-design.md` | `docs/superpowers/specs/2026-10-08-s3b-json-contract.md` |
| S3c | `docs/superpowers/specs/2026-10-09-s3c-site-timings-design.md` | `docs/superpowers/specs/2026-10-09-s3c-json-contract.md` |
| S4a | `docs/superpowers/specs/2026-10-09-s4a-faults-incidents-design.md` | `docs/superpowers/specs/2026-10-09-s4a-json-contract.md` |
| S4b | `docs/superpowers/specs/2026-10-09-s4b-restart-recovery-design.md` | `docs/superpowers/specs/2026-10-09-s4b-json-contract.md` |

`docs/superpowers/specs/`에 설계·JSON 계약 문서 14개, `docs/superpowers/plans/`에 구현 계획 17개(P1, S1a, S1b, S1c, P2a, P2b, P3, P4, S1d, S2, S3a, S3b, P5, P6, S3c, S4a, S4b)가 있습니다. 이 수는 디렉터리의 파일을 세어 얻었습니다.

#18 이후 변경에는 이 디렉터리의 설계 문서가 없고, 구현 기록은 `docs/screens/`의 날짜 붙은 문서에 있습니다.

- [공유 스테이션 오케스트레이션](screens/shared-station-orchestration-2026-10-12.md): 스테이션 상태와 복구
- [임무 필수 자원](screens/mission-resource-enforcement-2026-10-12.md): 모든 작업 요청 경로의 필수 자원 적용
- [결과 전달 신뢰성](screens/reliability-implementation-2026-10-11.md): 결과 outbox, 커서, 결과 불명 흐름
- [상위 결과 버전과 수신 확인](screens/upstream-results-2026-10-11.md): 결과 버전·해시·수신 확인
- [수동 작업 요청](screens/manual-receipts-2026-10-11.md): 화면의 수동 작업 요청과 요청 ID

이 문서들은 그때의 기록이고, #20~#22의 수정은 이 참고 문서의 «작업 요청과 결과 회신»·«공유 스테이션 조정» 절에 반영했습니다.

## 용어 풀이

- pump: 실행 호스트가 250ms 고정 지연으로 셀 대역 `GET /cell`을 읽고 `Middleware.pump()`를 호출하는 한 라운드입니다(`MissionHost.kt:1096` `PUMP_PERIOD`).
- 종결: 실행이 종료 상태에 도달함을 뜻합니다. 일지 이벤트 `SETTLED`는 `PHYSICALLY_DONE`, `UNVERIFIED`, `FAILED`, `ABORTED`에만 기록되며, `PARTIAL`은 제외됩니다. `PARTIAL`은 미들웨어가 계속 돌리므로(`pumps`) 종결로 적지 않고 판정에서 기체를 쥔 것으로 봅니다. 운영자가 취소한 `PARTIAL`은 다음 pump에 `ABORTED`로 종결해 `SETTLED`(사유 `ABORTED`)가 적힙니다. 종결을 적는 같은 트랜잭션에서 잔여 파지 표시가 설 수 있습니다.
- 봉인: 라운드 종료 시 `sealIncidents`로 인시던트를 확정하는 작업입니다. `unresolved`는 봉인 시점에 단위가 OPERATOR_HOLD 또는 IN_DOUBT였는지를 나타내는 칸이며, 봉인 뒤에는 그 값을 유지합니다(picasso `docs/glossary.md` 206행).
- 근거 등급: E2는 셀 슬롯 관측 근거로 확인된 등급이고, E0은 판단 기록 없이 복원한 앞선 단위 등과 같이 설비 근거가 없는 등급입니다(판단 기록이 있으면 기록의 근거 등급 그대로).
- P/S: P는 picasso 저장소 단계, S는 mission-ops 단계를 뜻합니다.
- 인스턴스(`instanceId`): 실행 호스트를 기동할 때마다 새로 생성되는 `Middleware`이며, `exec-N`, `resp-N`, `incident-N` 식별자는 1부터 시작합니다. 운영 서비스 인스턴스(`ops-<UUID>`, 운영 서비스 프로세스마다 새로)도 있습니다. 두 인스턴스 모두 심장박동을 적습니다(실행 호스트 `mission.host_instance`, 운영 서비스 `ops.service_instance`). 살아 있음은 행이 있고 멈춤(`stopped_at`)이 없으며 만료(`expires_at`)가 DB 시각보다 뒤인 것입니다. 리스가 아닙니다.
- 작업 요청(`work_request`): 외부 주문, 호출 계약 호출, 화면 수동 요청이 운영 서비스에 남기는 영속 기록입니다. 작업 지시 id는 `JO-<작업 요청 id>`입니다.
- 스테이션 작업(`station_run`): 공유 스테이션 하나를 차례로 점유하는 단위입니다. 필수 자원 경로에서는 작업 요청과 같은 id입니다.
- 결과 버전(`work_result`): 작업 요청마다 1부터 붙는 저장 결과입니다. 수신 확인은 버전과 `resultHash`에 묶입니다.
- 기준선(`result_baseline`): 스테이션 작업이 `RUNNING`에 들어갈 때 적은 최신 결과 버전입니다. 그보다 새 결과만 이번 시도의 근거입니다.
- 작업 번호(`jobSignal`·`jobEchoSignal`): 스테이션 작업 id를 설비에 넘기고 돌려받는 TEXT 신호 쌍입니다. 반환 번호가 이번 번호와 같아야 완료 근거가 됩니다.
- 격리(`quarantined_by`): 쌍이 없는 스테이션에서 보낸 작업을 현장 확인으로 해제했을 때 걸리는 표시입니다. 운영자 `CONFIRM_STATION_IDLE` 전까지 다음 작업을 보내지 않습니다.
- 잔여 파지 표시(`robot_residual_hold`): 실행이 종결할 때 마지막 로봇 단위의 파지 관측이 `HOLDING`이면 실행 호스트가 그 기체에 거는 표시입니다. 운영자 `CONFIRM_ROBOT_EMPTY`(화면 «기체 빈손 확인») 전까지 그 기체를 배정에서 뺍니다. 실행 호스트 운영 정책(ADR 41)이며 picasso 보장이 아닙니다.
- 작업 허가: 현장 안전 신호를 기체 집합마다 비춘 정보 투영입니다(picasso ADR 52). 안전 기능이 아닙니다. picasso 는 이 값으로 새 로봇 명령만 내지 않습니다(단위 출발, 개정 갱신, 미확정 조회). 이미 나간 명령, 취소, 관측은 막지 않습니다. 기체를 세우는 것은 안전 계통입니다. 실행 호스트가 대응(`host.work-permit.mapping`)을 선언받았을 때만 미들웨어 발행 게이트가 봅니다.
- 허가 차단(`permitBlock`): 실행의 새 로봇 명령을 지금 막고 있는 작업 허가입니다. 막힌 단위, 내지 않은 명령의 종류, 기체마다 허가와 까닭, 처음 막힌 시각을 담습니다. 운영자 보류·결함 차단과 다른 칸이고 운영자 조작으로 풀리지 않습니다.
- 원천 세션(`cell-band#N`): 셀 대역은 세션을 내지 않으므로 실행 호스트가 정합니다. 셀 대역 읽기가 끊길 때마다 N+1(끊김 하나를 원천 재기동으로 봄)입니다. 끊긴 동안 앞 세션 줄은 `SESSION_CHANGED` 모름(마지막 주체가 보임)입니다. 실행 호스트를 다시 띄우면 1부터입니다.

## 추가 화면 참고

![코드 정의 InspectAsset 실행](screens/img/12-operations-executions.png)

이 그림은 `12-operations-executions.png`로, 운영자가 수동 작업 요청(`InspectAsset`)을 낸 직후의 수동 작업 요청 추적과 실행 목록입니다(실제 스택에서 촬영). 대표 스크린숏은 21장이고 목록과 촬영 조건은 [스크린숏 목록](screens/img/INDEX.md) 링크에 있습니다. 이 문서의 절과 맞닿는 그림 다섯은 다음과 같습니다.

- `09-requests-in-doubt.png`: 전송 결과 불명 작업 요청과 현장 확인 기록 폼
- `10-stations-queue.png`: 스테이션 점유 작업 실행 중과 다음 작업 요청 자원 대기
- `11-stations-review-required.png`: 단계 기한 초과로 현장 확인 필요
- `13-operations-incident-hold.png`: 설비 대기 기한 초과로 보류 중인 인시던트와 보류 해소 폼
- `14-operations-restart-restored-mock.png`: 실행 호스트 재기동 뒤 복원 보고(응답 대역으로 그림)
