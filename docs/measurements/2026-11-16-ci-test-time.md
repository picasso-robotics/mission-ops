# CI Gradle 시험 시간 — 2026-11-16

CI 의 `gradle` 잡이 제한 45분(`.github/workflows/ci.yml`)에 다가가(최근 28~34분, 마지막 측정 33분 43초) 잡을 모듈별로 나누기 전에 잰 기준. 측정 대상은 PR #28 의 CI 실행 `43797892832`(GitHub 호스트 러너 `ubuntu-latest`, `./gradlew build` 한 잡)이고, 원자료는 그 실행이 올린 `test-results` 산출물의 시험 XML 이다.

## 모듈별 시험 시간 (XML `testsuite.time` 합)

| 모듈 | 시험 시간 | 시험 수 | 시험 클래스 |
|---|---|---|---|
| mission-host | 14.8분 | 308 | 42 |
| e2e | 12.3분 | 81 | 22 |
| ops-service | 4.3분 | 519 | 45 |
| site | 0.4분 | 50 | 8 |

XML 의 시간은 시험 클래스 안의 시간이라 Spring 문맥·Testcontainers 기동 일부와 컴파일·그 밖의 검사 작업은 들지 않는다. 잡 전체(33분 43초)와의 차가 그 몫이다. 시험은 모듈마다 한 JVM 안에서 순차로 돈다 — `maxParallelForks`·`org.gradle.parallel`·JUnit 병렬 설정이 없다.

## 느린 시험 클래스 (상위)

| 초 | 시험 수 | 클래스 |
|---|---|---|
| 116 | 7 | `e2e.FaultIncidentTest` |
| 114 | 114 | `ops.service.WorkRequestsTest` |
| 96 | 6 | `e2e.RestartRecoveryTest` |
| 90 | 23 | `host.MissionVersionsTest` |
| 83 | 2 | `e2e.StationLateCompletionTest` |
| 76 | 7 | `host.LedgerCopyHostTest` |
| 70 | 1 | `e2e.MixedResourceScenarioTest` |
| 63 | 7 | `e2e.SiteSettingsTest` |
| 62 | 16 | `host.IncidentLoopHostTest` |
| 62 | 11 | `host.SkippedTargetReworkHostTest` |

## 조치

- 잡을 셋으로 나눠 함께 돌림: `:ops-service:build`, `:mission-host:build`, 나머지(`build -x :ops-service:test -x :mission-host:test` — 컴파일·site·e2e·두 모듈의 시험 밖 검사). 셋의 결과를 같은 이름의 `gradle` 잡이 모음.
- 모듈 안 시험 병렬화는 켜지 않음 — 시험들이 공유 시험 DB 를 쓰고 `HostBench` 가 스키마를 지우므로, DB 격리가 먼저다.
- 분할 뒤의 벽시계는 그 PR 의 CI 실행으로 잰다(아래에 덧붙임).

## 재지 못한 것

- 러너마다 CPU·Docker 가 달라 같은 커밋의 시간도 흔들린다. 위 수치는 한 실행의 값이다.
- 잡 안의 단계별 시간(의존성 내려받기·컴파일·검사)은 따로 가르지 않았다.
