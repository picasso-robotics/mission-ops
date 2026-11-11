# 부하 측정 after — 작업 요청 검색·실행 호스트 pump 기록 문장 — 2026-11-11

| 세트 | 커밋 |
|---|---|
| 바탕 | `ad5baf7` (`main`, picasso `51d8230`), 2026-11-11 같은 기계 |
| after | 브랜치 `perf/search-and-pump` (바탕 `ad5baf7`), 2026-11-11 같은 기계 |
| 하네스 | after 하네스. `explain` 은 «(전)» 행에 바꾸기 전 검색 문자열(strpos)을 같은 회차에 같이 돌리고 미ACK 0.1% 회차를 더했다. `pump`·`pumpscale` 바탕은 실행 호스트 변경(`mission-host/src/main`)만 걷어 같은 하네스로 after 바로 뒤에 잼. 바탕의 검색·목록 실측은 같은 날 `ad5baf7` 의 하네스로 잰 값 |

```
./gradlew :load:loadBench --args="explain"
./gradlew :load:loadBench --args="pump pumpscale"
```

| 항목 | 값 |
|---|---|
| JVM | OpenJDK 64-Bit Server VM 21.0.13 |
| OS | Windows 11 10.0 amd64 |
| 논리 CPU(JVM) | 20 |
| 최대 힙 | 4096 MiB |
| 모드 | full |
| Postgres | 16.15 |

`perf/search-and-pump` 브랜치에서 운영 서비스 검색 질의와 실행 호스트 pump 트랜잭션을 고치고 부하 하네스로 지연 시간과 DB 문장 수를 쟀다. 2026-11-11 같은 기계에서 바탕 커밋 `ad5baf7` main 과 picasso `51d8230` 을 두고 앞뒤를 견줬다. pump 는 실행 호스트 변경만 잠시 걷어 같은 하네스로 연달아 비교했고, 검색은 같은 회차에 바꾸기 전 질의 문자열을 같이 돌려 측정했다.

| 세트 | 바탕 JDBC 왕복 SELECT 1 p50 / p95 / max ms | after |
|---|---|---|
| 기체 1 | 0.63 / 1.87 / 4.65 | 0.53 / 1.18 / 3.16 |
| 기체 10 | 0.54 / 1.20 / 3.56 | 0.71 / 2.01 / 5.86 |
| 기체 50 | 0.52 / 0.63 / 1.53 | 0.55 / 1.43 / 2.64 |
| 기체 100 | 0.50 / 1.56 / 8.52 | 0.68 / 1.99 / 6.31 |
| 기체 200 | 0.66 / 2.53 / 4.54 | 0.23 / 0.32 / 0.48 |
| explain | 0.41 / 0.88 / 3.04 | 0.68 / 1.78 / 3.69 |

## 핫스팟 5 — 작업 요청 검색 (`WorkRequests.list`)

V24 마이그레이션으로 세 칸에 trigram GIN 인덱스를 더하고 건수 기준 분기 계획을 도입하여 10만 행 기준 좁은 검색의 운영 실측 p50 이 79.9 ms 에서 8.81 ms 로 줄었다. 반면 맞는 행이 7만 개인 넓은 검색 'mes' 는 플래너가 병렬이 아닌 순차 스캔을 골라 count 가 32.6 ms 에서 65.3 ms 로 느려졌으며 운영 실측 p50 은 93.7 ms 가 나왔다. 다만 넓은 검색도 1쪽 조회는 둘 다 0.1 ms 대였고, 플래너의 부분 문자열 추정 오차로 1.95 ms 와 116 ms 를 오가던 문제는 1000 행 기준 분기로 막았다. 세 글자보다 짧은 검색어는 trigram 이 없어 전과 같이 전체를 읽으며, 검색어의 `%`, `_`, `\` 는 `WorkRequests.containing` 처럼 글자 그대로 맞춘다.

| 행 수 | 질의 | (전) ms | after ms | after 첫 스캔 노드 |
|---|---|---|---|---|
| 10000 | 좁은 검색 'EXT-3333' count | 3.380 | 0.677 | Bitmap Heap Scan on work_request |
| 10000 | 좁은 검색 'EXT-3333' 1쪽 (맞는 행 1) | 4.884 | 0.796 | Bitmap Heap Scan on work_request |
| 10000 | 넓은 검색 'mes' count | 10.549 | 15.109 | Seq Scan on work_request |
| 10000 | 넓은 검색 'mes' 1쪽 (맞는 행 7000) | 0.110 | 0.082 | Index Scan using work_request_received on work_request |
| 100000 | 좁은 검색 'EXT-33333' count | 25.990 | 2.439 | Bitmap Heap Scan on work_request |
| 100000 | 좁은 검색 'EXT-33333' 1쪽 (맞는 행 1) | 102.316 | 4.604 | Bitmap Heap Scan on work_request |
| 100000 | 넓은 검색 'mes' count | 32.644 | 65.282 | Seq Scan on work_request |
| 100000 | 넓은 검색 'mes' 1쪽 (맞는 행 70000) | 0.106 | 0.164 | Index Scan using work_request_received on work_request |

운영 코드 실측(10회). 바탕은 같은 날 `ad5baf7` 회차(넓은 검색은 그 하네스에 없음).

| 행 수 | 호출 | 바탕 p50 / p95 / max ms | after |
|---|---|---|---|
| 10000 | `WorkRequests.list("", null, 0, 50)` | p50 6.77 / p95 12.0 / max 12.0 | p50 7.00 / p95 14.5 / max 14.5 |
| 10000 | `WorkRequests.list("EXT-…", null, 0, 50)` | p50 9.83 / p95 11.3 / max 11.3 | p50 5.41 / p95 6.74 / max 6.74 |
| 10000 | `WorkRequests.list("mes", null, 0, 50)` | - | p50 14.1 / p95 46.0 / max 46.0 |
| 100000 | `WorkRequests.list("", null, 0, 50)` | p50 9.24 / p95 10.3 / max 10.3 | p50 11.3 / p95 15.7 / max 15.7 |
| 100000 | `WorkRequests.list("EXT-…", null, 0, 50)` | p50 79.9 / p95 92.4 / max 92.4 | p50 8.81 / p95 10.4 / max 10.4 |
| 100000 | `WorkRequests.list("mes", null, 0, 50)` | - | p50 93.7 / p95 103 / max 103 |

## 핫스팟 5 — 수신 미확인 집계 (`WorkRequests.list(…, unacknowledged)`·`summary`)

설계 기록 §10 의 설명과 달리 평소처럼 미ACK 가 0.1% 인 조건에서는 플래너가 V20 부분 인덱스 `work_result_unacknowledged` 에서 시작하여 0.84 ms(`summary()` 실측 p50 16.5 ms)만에 끝난다. 상위 수신 장애로 미ACK 가 10% 에 달하면 순차 스캔이 일어나 10만 행 summary 미ACK 결과가 59.1 ms, `summary()` 실측 p50 이 62.3 ms 까지 늘어난다. 결과 쪽에서 시작하도록 IN 반조인이나 행마다 최신 버전 max 를 찾는 배열로 바꾼 두 형태를 검증했으나, 10% 분포에서 목록 조회가 25.8 ms 에서 34.5 ms 로, summary 가 44.2 ms 에서 48.5 ms 로 오히려 나빠져 질의를 바꾸지 않았다.

질의는 바뀌지 않았다(after 회차, 미ACK 분포 둘).

| 행 수 | 질의 | 미ACK 10% ms | 첫 스캔 노드 | 미ACK 0.1% ms | 첫 스캔 노드 |
|---|---|---|---|---|---|
| 10000 | list 미ACK count | 5.777 | Seq Scan on work_request | 0.265 | Index Only Scan using work_result_unacknowledged on work_result u |
| 10000 | list 미ACK 1쪽 | 0.715 | Index Scan using work_request_received on work_request | 1.119 | Index Only Scan using work_result_unacknowledged on work_result u |
| 10000 | summary 미ACK 결과 | 5.238 | Seq Scan on work_request r | 0.233 | Index Only Scan using work_result_unacknowledged on work_result u |
| 100000 | list 미ACK count | 54.160 | Seq Scan on work_request | 0.728 | Index Only Scan using work_result_unacknowledged on work_result u |
| 100000 | list 미ACK 1쪽 | 2.903 | Index Scan using work_request_received on work_request | 1.780 | Index Only Scan using work_result_unacknowledged on work_result u |
| 100000 | summary 미ACK 결과 | 59.099 | Seq Scan on work_request r | 0.840 | Index Only Scan using work_result_unacknowledged on work_result u |

| 행 수 | 호출 | 미ACK 10% p50 / p95 / max ms | 미ACK 0.1% |
|---|---|---|---|
| 10000 | `WorkRequests.list("", null, 0, 50, unacknowledged)` | p50 8.70 / p95 11.7 / max 11.7 | p50 12.7 / p95 19.9 / max 19.9 |
| 10000 | `WorkRequests.summary()` | p50 13.5 / p95 21.2 / max 21.2 | p50 11.9 / p95 17.1 / max 17.1 |
| 100000 | `WorkRequests.list("", null, 0, 50, unacknowledged)` | p50 61.9 / p95 72.6 / max 72.6 | p50 7.65 / p95 11.7 / max 11.7 |
| 100000 | `WorkRequests.summary()` | p50 62.3 / p95 65.0 / max 65.0 | p50 16.5 / p95 23.0 / max 23.0 |

## 핫스팟 3·4 — 실행 호스트 pump (`MissionHost.pumpOnce`)

gRPC 와 HTTP 응답 대기를 세지 못해 pump 안 표본이 49개에 그쳤던 JFR 대신 2 ms 주기 벽시계 표본기 `WallSampler` 로 병목을 확인했다. 바꾸기 전 기체 50 표본은 picasso `Middleware.sync` 가 약 41%, 기록 트랜잭션이 절반 남짓이었으며 인시던트 목록 훑기는 표본 1개에 불과했다. `HostRecords.judgmentRecords` 단일화, `appendResult` 의 outbox 삽입 및 카운터 증가 CTE 결합, `firstResponseBasis` 병합으로 동작 변경 없이 223개 시험을 통과하며 기체 50 기준 DB 문장 수를 29.4개에서 17.2개로 줄였다. 개별 pump 지연 시간은 표본기 정지 간섭과 환경 편차로 흔들렸으나, 일하는 pump 안의 벽시계 표본 합은 기체 50 에서 13%, 기체 200 에서 21% 줄었다(기록 몫이 작은 기체 1·10 은 +21%·+7% 로 회차 편차 안이다). 바꾼 뒤 기체 50 표본은 외부 기체 상태를 읽는 `Middleware.sync` 가 57%, `appendResult` 가 13% 를 차지했다.

일하는 pump (주기 안의 모든 pumpOnce)

| 기체 M | 실행 수 구간 | 바탕 pump 수 | 바탕 p50 / p95 / max ms | 바탕 DB 문장/pump | after pump 수 | after p50 / p95 / max ms | after DB 문장/pump |
|---|---|---|---|---|---|---|---|
| 1 | 0→10 | 206 | 6.12 / 19.7 / 100 | 0.66 | 210 | 6.32 / 18.7 / 116 | 0.55 |
| 1 | 10→50 | 832 | 2.97 / 13.6 / 54.4 | 0.65 | 840 | 4.38 / 20.9 / 98.7 | 0.55 |
| 10 | 0→10 | 21 | 23.4 / 82.6 / 187 | 5.86 | 21 | 27.3 / 62.4 / 91.9 | 3.71 |
| 10 | 10→100 | 189 | 9.97 / 64.9 / 206 | 5.85 | 185 | 13.9 / 71.7 / 178 | 3.72 |
| 10 | 100→300 | 416 | 8.53 / 53.6 / 137 | 5.92 | 414 | 9.04 / 44.5 / 158 | 3.77 |
| 50 | 0→50 | 21 | 24.4 / 122 / 254 | 28.8 | 21 | 37.9 / 158 / 212 | 17.1 |
| 50 | 50→500 | 183 | 23.4 / 196 / 475 | 29.7 | 183 | 41.9 / 215 / 502 | 17.7 |
| 50 | 500→1000 | 210 | 37.8 / 441 / 2091 | 28.8 | 210 | 45.2 / 197 / 632 | 17.1 |
| 50 | 1000→2000 | 412 | 45.1 / 301 / 986 | 29.4 | 418 | 49.9 / 222 / 930 | 17.2 |

일하는 pump 의 벽시계 표본 합(2 ms 마다, pump 안에 있던 시간)

| 기체 M | 바탕 표본 | after 표본 | 차이 |
|---|---|---|---|
| 1 | 1661 | 2007 | +21% |
| 10 | 3173 | 3388 | +7% |
| 50 | 19387 | 16947 | -13% |

단계별 표본, 기체 1

| 세트 | 단계 | 표본 | 비율 % |
|---|---|---|---|
| 바탕 | picasso Middleware.pump · Middleware.sync [grpc] | 705 | 42.4 |
| 바탕 | 셀 대역 읽기 | 368 | 22.2 |
| 바탕 | pump 뒤 기록 · HostRecords.judgmentRecord [db] | 90 | 5.42 |
| 바탕 | picasso Middleware.pump · Middleware.pump [grpc] | 64 | 3.85 |
| 바탕 | picasso Middleware.pump · Middleware.sync | 62 | 3.73 |
| 바탕 | pump 뒤 기록 · HostRecords.appendResult [db] | 61 | 3.67 |
| 바탕 | pump 뒤 기록 · store.HostRecords.inTransaction [db] | 54 | 3.25 |
| 바탕 | pump 뒤 기록 · HostRecords.releases [db] | 37 | 2.23 |
| 바탕 | pump 뒤 기록 · HostRecords.journalRow [db] | 27 | 1.63 |
| 바탕 | pump 뒤 기록 · HostRecords.loggedIn [db] | 23 | 1.38 |
| 바탕 | 나머지 | 170 | 10.2 |
| after | picasso Middleware.pump · Middleware.sync [grpc] | 853 | 42.5 |
| after | 셀 대역 읽기 | 425 | 21.2 |
| after | pump 뒤 기록 · HostRecords.judgmentRecords [db] | 117 | 5.83 |
| after | picasso Middleware.pump · Middleware.sync | 95 | 4.73 |
| after | pump 뒤 기록 · HostRecords.appendResult [db] | 80 | 3.99 |
| after | picasso Middleware.pump · Middleware.pump [grpc] | 71 | 3.54 |
| after | pump 뒤 기록 · store.HostRecords.inTransaction [db] | 67 | 3.34 |
| after | picasso Middleware.pump · Middleware.pump | 47 | 2.34 |
| after | pump 뒤 기록 · HostRecords.releases [db] | 38 | 1.89 |
| after | pump 뒤 기록 · HostRecords.firstResponseBasis [db] | 34 | 1.69 |
| after | 나머지 | 180 | 8.97 |

단계별 표본, 기체 10

| 세트 | 단계 | 표본 | 비율 % |
|---|---|---|---|
| 바탕 | picasso Middleware.pump · Middleware.sync [grpc] | 1425 | 44.9 |
| 바탕 | pump 뒤 기록 · HostRecords.appendResult [db] | 365 | 11.5 |
| 바탕 | pump 뒤 기록 · HostRecords.judgmentRecord [db] | 338 | 10.7 |
| 바탕 | 셀 대역 읽기 | 139 | 4.38 |
| 바탕 | pump 뒤 기록 · HostRecords.logResponse [db] | 120 | 3.78 |
| 바탕 | pump 뒤 기록 · HostRecords.settled [db] | 105 | 3.31 |
| 바탕 | pump 뒤 기록 · HostRecords.lastSent [db] | 100 | 3.15 |
| 바탕 | picasso Middleware.pump · Middleware.sync | 99 | 3.12 |
| 바탕 | picasso Middleware.pump · Middleware.pump [grpc] | 92 | 2.90 |
| 바탕 | pump 뒤 기록 · HostRecords.loggedIn [db] | 82 | 2.58 |
| 바탕 | 나머지 | 308 | 9.71 |
| after | picasso Middleware.pump · Middleware.sync [grpc] | 1748 | 51.6 |
| after | pump 뒤 기록 · HostRecords.appendResult [db] | 350 | 10.3 |
| after | 셀 대역 읽기 | 165 | 4.87 |
| after | picasso Middleware.pump · Middleware.sync | 147 | 4.34 |
| after | pump 뒤 기록 · HostRecords.settled [db] | 135 | 3.98 |
| after | pump 뒤 기록 · HostRecords.logResponse [db] | 134 | 3.96 |
| after | pump 뒤 기록 · HostRecords.firstResponseBasis [db] | 124 | 3.66 |
| after | picasso Middleware.pump · Middleware.pump [grpc] | 113 | 3.34 |
| after | pump 뒤 기록 · HostRecords.judgmentRecords [db] | 112 | 3.31 |
| after | pump 뒤 기록 · HostRecords.journalRow [db] | 112 | 3.31 |
| after | 나머지 | 248 | 7.32 |

단계별 표본, 기체 50

| 세트 | 단계 | 표본 | 비율 % |
|---|---|---|---|
| 바탕 | picasso Middleware.pump · Middleware.sync [grpc] | 7844 | 40.5 |
| 바탕 | pump 뒤 기록 · HostRecords.appendResult [db] | 3001 | 15.5 |
| 바탕 | pump 뒤 기록 · HostRecords.judgmentRecord [db] | 2914 | 15.0 |
| 바탕 | pump 뒤 기록 · HostRecords.settled [db] | 868 | 4.48 |
| 바탕 | pump 뒤 기록 · HostRecords.journalRow [db] | 845 | 4.36 |
| 바탕 | pump 뒤 기록 · HostRecords.logResponse [db] | 784 | 4.04 |
| 바탕 | pump 뒤 기록 · HostRecords.loggedIn [db] | 764 | 3.94 |
| 바탕 | pump 뒤 기록 · HostRecords.lastSent [db] | 755 | 3.89 |
| 바탕 | picasso Middleware.pump · Middleware.pump [grpc] | 435 | 2.24 |
| 바탕 | 셀 대역 읽기 | 255 | 1.32 |
| 바탕 | 나머지 | 922 | 4.76 |
| after | picasso Middleware.pump · Middleware.sync [grpc] | 9608 | 56.7 |
| after | pump 뒤 기록 · HostRecords.appendResult [db] | 2150 | 12.7 |
| after | pump 뒤 기록 · HostRecords.settled [db] | 820 | 4.84 |
| after | pump 뒤 기록 · HostRecords.logResponse [db] | 792 | 4.67 |
| after | pump 뒤 기록 · HostRecords.journalRow [db] | 754 | 4.45 |
| after | pump 뒤 기록 · HostRecords.firstResponseBasis [db] | 695 | 4.10 |
| after | picasso Middleware.pump · Middleware.pump [grpc] | 498 | 2.94 |
| after | picasso Middleware.pump · Middleware.sync | 359 | 2.12 |
| after | 셀 대역 읽기 | 244 | 1.44 |
| after | picasso Middleware.pump · Middleware$Execution.getRobots | 237 | 1.40 |
| after | 나머지 | 790 | 4.66 |

## 기체 늘림 — `pumpscale` (기체 100·200)

`all` 에 넣지 않은 새 시나리오 `pumpscale` 로 기체 수를 늘려 측정한 결과, 바꾼 뒤 기체 100 에서 일하는 pump p50 66.5 ms 와 p95 282 ms, 기체 200 에서 p50 83.2 ms 와 p95 425 ms(최대 2915 ms)가 나왔다. pump 주기가 250 ms 이므로 기체 200 에서는 p95 가 주기를 넘어선다. 기체 200 표본은 picasso `Middleware.sync` 42%, `appendResult` 20%, `settled` 9% 였으며, 다음 병목은 이 저장소 밖인 picasso 의 기체별 동기화와 응답마다 남기는 기록 문장이다.

| 기체 M | 실행 수 구간 | 바탕 pump 수 | 바탕 p50 / p95 / max ms | 바탕 DB 문장/pump | after pump 수 | after p50 / p95 / max ms | after DB 문장/pump |
|---|---|---|---|---|---|---|---|
| 100 | 0→100 | 21 | 65.0 / 486 / 1580 | 57.4 | 21 | 71.4 / 441 / 900 | 33.8 |
| 100 | 100→1000 | 179 | 52.4 / 478 / 1242 | 60.6 | 185 | 66.5 / 282 / 876 | 34.6 |
| 200 | 0→200 | 21 | 120 / 466 / 944 | 115 | 21 | 53.1 / 317 / 1093 | 67.2 |
| 200 | 200→1000 | 78 | 102 / 864 / 1472 | 123 | 78 | 83.2 / 425 / 2915 | 72.4 |

일하는 pump 의 벽시계 표본 합(2 ms 마다, pump 안에 있던 시간)

| 기체 M | 바탕 표본 | after 표본 | 차이 |
|---|---|---|---|
| 100 | 7699 | 6419 | -17% |
| 200 | 6185 | 4904 | -21% |

단계별 표본, 기체 100

| 세트 | 단계 | 표본 | 비율 % |
|---|---|---|---|
| 바탕 | picasso Middleware.pump · Middleware.sync [grpc] | 2887 | 37.5 |
| 바탕 | pump 뒤 기록 · HostRecords.appendResult [db] | 1369 | 17.8 |
| 바탕 | pump 뒤 기록 · HostRecords.judgmentRecord [db] | 1114 | 14.5 |
| 바탕 | pump 뒤 기록 · HostRecords.logResponse [db] | 383 | 4.97 |
| 바탕 | pump 뒤 기록 · HostRecords.settled [db] | 367 | 4.77 |
| 바탕 | pump 뒤 기록 · HostRecords.loggedIn [db] | 351 | 4.56 |
| 바탕 | pump 뒤 기록 · HostRecords.lastSent [db] | 320 | 4.16 |
| 바탕 | pump 뒤 기록 · HostRecords.journalRow [db] | 299 | 3.88 |
| 바탕 | picasso Middleware.pump · Middleware.pump [grpc] | 190 | 2.47 |
| 바탕 | picasso Middleware.pump · Middleware$Execution.getRobots | 94 | 1.22 |
| 바탕 | 나머지 | 325 | 4.22 |
| after | picasso Middleware.pump · Middleware.sync [grpc] | 3490 | 54.4 |
| after | pump 뒤 기록 · HostRecords.appendResult [db] | 950 | 14.8 |
| after | pump 뒤 기록 · HostRecords.settled [db] | 376 | 5.86 |
| after | pump 뒤 기록 · HostRecords.logResponse [db] | 337 | 5.25 |
| after | pump 뒤 기록 · HostRecords.journalRow [db] | 289 | 4.50 |
| after | pump 뒤 기록 · HostRecords.firstResponseBasis [db] | 286 | 4.46 |
| after | picasso Middleware.pump · Middleware.pump [grpc] | 207 | 3.22 |
| after | pump 뒤 기록 · HostRecords.judgmentRecords [db] | 84 | 1.31 |
| after | picasso Middleware.pump · Middleware.sync | 83 | 1.29 |
| after | 셀 대역 읽기 | 71 | 1.11 |
| after | 나머지 | 246 | 3.83 |

단계별 표본, 기체 200

| 세트 | 단계 | 표본 | 비율 % |
|---|---|---|---|
| 바탕 | picasso Middleware.pump · Middleware.sync [grpc] | 2515 | 40.7 |
| 바탕 | pump 뒤 기록 · HostRecords.appendResult [db] | 1004 | 16.2 |
| 바탕 | pump 뒤 기록 · HostRecords.judgmentRecord [db] | 840 | 13.6 |
| 바탕 | pump 뒤 기록 · HostRecords.logResponse [db] | 282 | 4.56 |
| 바탕 | pump 뒤 기록 · HostRecords.lastSent [db] | 280 | 4.53 |
| 바탕 | pump 뒤 기록 · HostRecords.settled [db] | 279 | 4.51 |
| 바탕 | pump 뒤 기록 · HostRecords.journalRow [db] | 252 | 4.07 |
| 바탕 | pump 뒤 기록 · HostRecords.loggedIn [db] | 246 | 3.98 |
| 바탕 | picasso Middleware.pump · Middleware.pump [grpc] | 146 | 2.36 |
| 바탕 | picasso Middleware.pump · Middleware$Execution.getRobots | 97 | 1.57 |
| 바탕 | 나머지 | 244 | 3.95 |
| after | picasso Middleware.pump · Middleware.sync [grpc] | 2072 | 42.3 |
| after | pump 뒤 기록 · HostRecords.appendResult [db] | 993 | 20.2 |
| after | pump 뒤 기록 · HostRecords.settled [db] | 452 | 9.22 |
| after | pump 뒤 기록 · HostRecords.logResponse [db] | 375 | 7.65 |
| after | pump 뒤 기록 · HostRecords.firstResponseBasis [db] | 299 | 6.10 |
| after | pump 뒤 기록 · HostRecords.journalRow [db] | 294 | 6.00 |
| after | picasso Middleware.pump · Middleware.pump [grpc] | 106 | 2.16 |
| after | picasso Middleware.pump · Middleware$Execution.getRobots | 62 | 1.26 |
| after | pump 뒤 기록 · HostRecords.judgmentRecords [db] | 55 | 1.12 |
| after | picasso Middleware.pump · Middleware.sync | 51 | 1.04 |
| after | 나머지 | 145 | 2.96 |

## 심장박동 값 (HA 설계 §4.5)

심장박동은 pump 와 다른 스레드(실행 호스트 `beater`, 운영 서비스 `ops-instance-heartbeat`)라 긴 pump 가 심장박동을 늦추지 않는다. 하네스 세트마다 가장 긴 GC 정지는 2~8 ms(G1 동시 주기 제외, 최대 힙 4 GiB)였고, JDBC `SELECT 1` 왕복은 p50 0.23~0.71 ms 에 max 8.52 ms 였다. 설계 기록 §4.5 식에 대입한 만료 하한은 15.01 초 안쪽이어서 지금 만료 30 초가 이를 넉넉히 넘으므로 주기 5 초와 만료 30 초를 그대로 둔다. 다만 이 수치는 같은 기계의 로컬 Docker Postgres 와 하네스 부하에서 잰 값이며, 망을 건너는 DB, 더 큰 힙, 운영 부하에서는 재지 않았다.

| 기체 M | 바탕 최대 GC 정지 ms | after |
|---|---|---|
| 1 | 7 | 5 |
| 10 | 3 | 4 |
| 50 | 8 | 3 |
| 100 | 3 | 3 |
| 200 | 3 | 2 |

