# 인시던트 고리 자격 선언 견본

## 쓰는 법
`mission-host/incident-loop/entitlements.example.json`을 복사해 현장용 선언 파일을 만든다.
`operator-1`로 지정된 운영자 id 자리표시를 현장 운영자의 실제 사용자 이름으로 바꾼다.
기본 만료 시각(`2026-12-31T00:00:00Z`)을 현장 운영 일정에 맞춘 만료 시각으로 수정한다.
실행 호스트를 기동할 때 `--host.incident-loop.entitlements=<파일>` 옵션으로 해당 선언 파일 경로를 지정한다.
picasso의 `handoff/narrator/entitlements.json`은 시험·시연용 픽스처이며 현장 기체(`humanoid-01`, `quadruped-01`)가 아닌 `hum-02` 등이 들어 있으므로 복사 원본으로 쓰지 않는다(picasso PR #24).

## 조치를 비운 까닭
견본 선언의 `actions` 기본값은 `[]`다.
조치 선언이 비어 있으면 `SKILL_OUT_OF_SCOPE`로 거절된다.
내려놓기 조치를 승인하려면 `pick_place`와 `destination`이 필요하다.
승인은 기체 단위 호스트 제한(`robotRestrictions`)을 먼저 확인한다.
표시 사유만 있는 기체는 새 접수에서 관문 확인을 거쳐 조치 제안을 얻는다.
관문이 통과시키면 일반 실행을 세우지 않고 표시 사유로 제외한다.
이번 인스턴스가 관문 확인으로 세운 제안이고 지금 열린 표시가 모두 그때 열려 있던 것이면 표시 예외로 승인된다.
회복 실행이 내려놓기에 성공해도 표시는 자동으로 풀리지 않고 그대로 남는다.
잔여 파지 표시를 잘못 해소한 뒤 들어온 순회에서 관문 거절과 함께 조치 제안이 서는 동작은 지금도 참이다.

## 운영자 id 도 넣는 까닭
incident-recovery는 사람의 승인 요청도 값 없는 문(`POST /approvals`)으로 보낸다.
이 문은 승인자 종류를 가리지 않고 선언 목록에서 id를 조회한다.
선언에 없는 운영자 id는 `NOT_DECLARED`로 거절된다.

## 반영 시점
실행 호스트는 기동할 때 선언 파일을 한 번만 읽는다.
선언 철회나 권한 범위 변경은 실행 호스트를 재기동해야 반영된다.
선언 만료는 판정마다 현재 시각과 비교한다.
recovery의 `RECOVERY_PICASSO_AGENT_ID`를 `recovery-1`로 바꾸기 전에 실행 호스트가 새 선언으로 먼저 기동되어 있어야 한다.
순서가 거꾸로 되면 자동 승인 요청이 모두 `NOT_DECLARED`로 거절된다.

## 이름 규칙
정본 규칙은 [picasso 용어 사전 §6](https://github.com/picasso-robotics/picasso/blob/main/docs/glossary.md)을 따른다.
에이전트 id는 `<역할>-<선언 번호>` 형식을 쓴다(예: `recovery-1`).
철회 뒤 재발급할 때는 번호를 올린 새 선언을 발행한다.
사람 id는 이 형식을 쓰지 않는다.
