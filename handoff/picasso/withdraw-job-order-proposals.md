# 요청: 작업 지시의 조치 제안 철회 입구(mission-ops B3)

## 배경
- 실행 호스트가 작업 지시를 영구히 철회하는 기능 도입(`POST /host/job-orders/{jobOrderId}/withdraw`, 표 `mission.job_order_withdrawal`)
- 철회된 작업 지시의 값 없는 승인은 실행 호스트가 picasso `Middleware.attemptApproval` 호출 전에 `REMEDY_NOT_APPLIED` 로 거부, 새 접수는 `assign` 호출 전에 `REJECTED`
- 안전성은 실행 호스트 방벽으로 충분, 이 요청은 선행 조건 아님

## 남는 것
- picasso 제안 장부(`RemedyDesk`)에 철회된 작업 지시의 제안 잔존
- 인시던트 고리 내보내기(조치 탐색 대장)와 승인 에이전트 화면에 그 제안이 계속 보임
- `Middleware.forget` 은 실행이 있어야 하고 제안 장부를 남기므로 철회 수단으로 부적합
- 승인 소모를 가장해 지우는 방식은 승인 기록을 왜곡하므로 배제

## 요청
- 실행이 없는 작업 지시의 제안을 장부에서 거두는 입구(예: `Middleware.withdrawProposals(jobOrderId)`) 검토
- 거둔 사실을 판단 기록이나 조치 탐색 대장에 남겨 «승인됨» 과 구분되는 표지 제공
- 재기동 뒤 제안 장부가 비는 지금 거동과의 관계 정리

## 관련
- 실행 호스트 `MissionHost.withdraw`, `attemptApproval`, `submitCurrent`
- 운영 보장 명세 «대체 접수와 현장 해제의 미전송 확정», «작업 지시 철회의 범위»
