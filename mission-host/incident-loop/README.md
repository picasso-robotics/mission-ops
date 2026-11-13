# 인시던트 고리 자격 선언 견본

## 쓰는 법
`mission-host/incident-loop/entitlements.example.json`을 복사해 현장용 선언 파일을 만든다.
`operator-1`로 지정된 운영자 id 자리표시를 현장 운영자의 실제 사용자 이름으로 바꾼다.
기본 만료 시각(`2026-12-31T00:00:00Z`)을 현장 운영 일정에 맞춘 만료 시각으로 수정한다.
실행 호스트를 기동할 때 `--host.incident-loop.entitlements=<파일>` 옵션으로 해당 선언 파일 경로를 지정한다.
picasso의 `handoff/narrator/entitlements.json`은 시험·시연용 픽스처이며 현장 기체(`humanoid-01`, `quadruped-01`)가 아닌 `hum-02` 등이 들어 있으므로 복사 원본으로 쓰지 않는다(picasso PR #24).

## 조치를 비운 까닭
견본 선언의 `actions: []`는 기본값으로 비워 둔다.
조치 목록이 비어 있으면 제안이 서더라도 값 없는 승인은 `SKILL_OUT_OF_SCOPE`로 끝난다.
잔여 파지 표시가 열려 있는 동안에는 기체가 관문에 닿지 않아 제안이 서지 않는다.
사람이 표시를 «빈손 확인»으로 잘못 해소한 뒤 빈손을 요구하는 순회를 내면 스냅숏 파지에 의해 관문 거절과 함께 조치 제안이 선다.
내려놓기를 자동 승인하려면 선언에 `pick_place`와 `destination`을 적어야 하며 대상 이름은 승인 때 손의 상태에서 채운다.
값 없는 승인 단계에서도 실행 호스트 판정이 기체 단위 제한을 확인하여 막힌 기체에는 실행을 세우지 않는다.
승인으로 실행이 등록된 뒤에도 잔여 파지 표시는 자동으로 풀리지 않으므로 사람이 직접 해소해야 한다.

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
