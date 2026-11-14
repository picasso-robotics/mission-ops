import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * S1b 완료 판정의 화면 쪽(스펙 §3·§10). 선언 → 보고(CONFIRMED) → 퇴역 → 퇴역 뒤 보고 감지 → 복귀 → 조작 기록,
 * 그리고 registry 를 멈추면 화면 전체 상태가 «모름» 이고 목록이 직전 값으로 남는 것(스펙 §10 마지막 문단).
 * 기체는 site/robots.json 의 humanoid-01 이다. 런처가 mimic 을 띄워 두었으므로 선언하면 보고가 붙는다.
 *
 * 이어서 S1c 의 화면 쪽(스펙 §3). 제품 선언 → 빌드 선언 → 인스턴스 등록 → 인스턴스 목록에 UNTESTED.
 * 이어서 S1d 의 화면 쪽(P2·S1d 스펙 §3·§11). 리비전 제출(파일 고르기) → 시험 요청 → 현장 실행기가 TESTED → 활성화 →
 * 바인딩 → 명칭 기록 → «시운전 완료»(humanoid-01). quadruped-01 은 명칭을 티칭하지 않아 «기체가 아는 명칭 없음» 으로 막힌다.
 * 이어서 S2 의 화면 쪽(S2 스펙 §3). 현장 설정 영역에서 연결 기준 시간을 바꾸면 버전 2 와 이력 행이 보이고, 운영자 모드는
 * 바꾸지 못하며, 기체 상세가 버전 2 의 기준으로 판정한다.
 * 이어서 S3a 의 화면 쪽(S3a 스펙 §3). 운영자 모드로 «운영» 영역에서 InspectAsset 작업 지시를 내면 시운전을 마친 humanoid-01 에
 * 배정되고 실행 목록에 «코드 정의» 행이 보인다. 실행 호스트는 실제 시각을 쓰고 런처가 가상 시계를 실제 시각까지 따라잡게 민다.
 * 호출 계약: 두 기본 업무 중 InspectAsset의 입력 매핑을 저장·공개·호출하고 물리 완료를 확인한 뒤 공개 중지와 409 거절을 검증한다.
 * 이어서 S3b 의 화면 쪽(S3b 스펙 §3). 엔지니어 모드로 «임무» 영역에서 데이터 정의 템플릿을 불러와 초안 저장 → 검증 → 모의
 * 실행 → 활성화(사유)하면 버전 이력에 «버전 1 (활성)» 이 보인다. 운영 영역의 셀 대역 신호 표에서 rack_present 를 켜면 신호 조작
 * 결과와 신호 값이 보인다.
 * S4b 의 화면 쪽(S4b 스펙 T11)은 운영자 보류 단계 뒤에 송신 기록 구역이 그 작업 지시의 송신 행을 보이는 것 하나다.
 * 결함 감수의 화면 쪽은 송신 기록 단계 뒤다. 제어권 상실로 막힌 실행을 운영자가 결함 카드를 보고 감수하고, 끝나면 제어권 회복으로 지운다.
 * S3c 의 화면 쪽(S3c 스펙 §3)은 S2 단계 뒤다. 엔지니어 모드로 stallWindow 를 바꾸면 버전 3 이력 행이 보이고 «실행 호스트 반영»
 * 이 버전 3 이 된다.
 * registry 를 멈추는 것은 맨 끝이다. 그 뒤로는 조작이 registry 에 닿지 않는다.
 *
 * 선언 직후의 CLAIMED 는 여기서 단언하지 않는다. 실시간 1:1 시계에서는 다음 보고가 1초 안에 올 수도 있어
 * CLAIMED 가 화면에 보이는 시간이 정해지지 않는다. CLAIMED 는 가상 시계를 직접 미는 통합 시험이 본다.
 */
test('화면에서 기체 생애주기와 어댑터 등록을 한 번 돌고 registry 를 멈추면 모름을 본다', async ({ page }) => {
  await page.goto('/')
  const detail = page.getByRole('region', { name: 'humanoid-01 상세' })
  const apiResponses: { method: string; path: string; status: number }[] = []
  page.on('response', response => {
    const path = new URL(response.url()).pathname
    if (path.startsWith('/api/')) apiResponses.push({ method: response.request().method(), path, status: response.status() })
  })

  // 새 도입 화면의 안내와 관리 탭은 실제 API 목록을 사용한다. 경로 선택은 등록 요청을 대신 보내지 않는다.
  await page.getByRole('button', { name: '로봇 추가', exact: true }).click()
  const onboarding = page.getByRole('region', { name: '로봇 도입 준비' })
  await onboarding.getByRole('radio', { name: /새 제조사/ }).check()
  await expect(onboarding.getByText(/등록 폼이 어댑터 코드를 생성하거나 배포하지는 않습니다/)).toBeVisible()
  await onboarding.getByRole('button', { name: '어댑터 확인·등록' }).click()
  await expect(page.getByRole('form', { name: '제품 선언' })).toBeVisible()
  await page.getByRole('button', { name: '도입 준비', exact: true }).click()
  await expect(onboarding.getByRole('radio', { name: /새 제조사/ })).toBeChecked()
  await onboarding.getByRole('radio', { name: /기존 유형/ }).check()

  // 엔지니어 모드(기본)에서 선언
  const declare = page.getByRole('form', { name: '기체 선언' })
  await declare.getByLabel('robot_id').fill('humanoid-01')
  await declare.getByLabel('일련번호').fill('HA-0001')
  await declare.getByRole('button', { name: '선언' }).click()
  await expect(page.getByText('humanoid-01 선언: 반영됨', { exact: true })).toBeVisible()
  await expect(onboarding.getByLabel('준비를 이어갈 기체')).toHaveValue('humanoid-01')

  // 보고를 기다려 CONFIRMED 를 본다
  await page.getByRole('button', { name: 'humanoid-01', exact: true }).click()
  await expect(detail.getByText('CONFIRMED', { exact: true })).toBeVisible()
  await expect(detail.getByText('바인딩 없음', { exact: true })).toBeVisible()

  // 운영자 모드에서 사유를 넣어 퇴역
  await page.getByLabel('운영자').check()
  await detail.getByLabel('퇴역 사유').fill('정비')
  await detail.getByRole('button', { name: '퇴역' }).click()
  await expect(detail.getByText('RETIRED', { exact: true })).toBeVisible()

  // 퇴역 뒤에도 기체가 보고하므로 막힘이 뜬다
  await expect(detail.getByText('퇴역 뒤 보고', { exact: true })).toBeVisible()
  await expect(detail.getByText('운영자(화면 안): 현장에서 기체를 내리거나 복귀')).toBeVisible()

  // 복귀
  await detail.getByRole('button', { name: '복귀' }).click()
  await expect(detail.getByText('CONFIRMED', { exact: true })).toBeVisible()
  await expect(detail.getByText('바인딩 없음', { exact: true })).toBeVisible()

  // 조작 기록
  await page.getByRole('button', { name: '이력' }).click()
  await expect(page.getByRole('cell', { name: '운영자/local' })).toHaveCount(2)
  await expect(page.getByRole('cell', { name: '엔지니어/local' })).toHaveCount(1)
  await expect(page.getByRole('cell', { name: '정비' })).toBeVisible()

  // 어댑터: 엔지니어 모드에서 제품 → 빌드 → 인스턴스
  await page.getByRole('navigation', { name: '영역' }).getByRole('button', { name: '로봇', exact: true }).click()
  await page.getByLabel('엔지니어').check()
  await page.getByRole('button', { name: '어댑터 관리', exact: true }).click()
  const product = page.getByRole('form', { name: '제품 선언' })
  await product.getByLabel('vendor').fill('acme')
  await product.getByLabel('name').fill('fleet')
  await product.getByRole('button', { name: '제품 선언' }).click()
  await expect(page.getByText('acme/fleet 제품 선언: 반영됨', { exact: true })).toBeVisible()

  const build = page.getByRole('form', { name: '빌드 선언' })
  await build.getByLabel('제품').selectOption('acme/fleet')
  await build.getByLabel('버전').fill('1.0.0')
  await build.getByLabel('계약 semver').fill('0.11.0')
  await build.getByRole('button', { name: '빌드 선언' }).click()
  await expect(page.getByText('acme/fleet 1.0.0 빌드 선언: 반영됨', { exact: true })).toBeVisible()

  const instance = page.getByRole('form', { name: '인스턴스 등록' })
  await instance.getByLabel('instance_id').fill('fleet-gw-01')
  await instance.getByLabel('빌드').selectOption('acme/fleet 1.0.0')
  await instance.getByRole('button', { name: '인스턴스 등록' }).click()
  await expect(page.getByText('fleet-gw-01 인스턴스 등록: 반영됨', { exact: true })).toBeVisible()
  const instances = page.getByRole('table', { name: '인스턴스 목록' })
  await expect(instances.getByRole('row', { name: /fleet-gw-01/ })).toContainText('UNTESTED')

  // 프로파일: 리비전 둘을 제출하고 시험을 요청하면 현장 실행기가 TESTED 로 올린다. 그 뒤 활성화.
  await page.getByRole('button', { name: '기체', exact: true }).click()
  const declareQuadruped = page.getByRole('form', { name: '기체 선언' })
  await declareQuadruped.getByLabel('robot_id').fill('quadruped-01')
  await declareQuadruped.getByLabel('일련번호').fill('QB-0001')
  await declareQuadruped.getByRole('button', { name: '선언' }).click()
  await expect(page.getByText('quadruped-01 선언: 반영됨', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: '프로파일 관리', exact: true }).click()
  const profiles = page.getByRole('region', { name: '프로파일' })
  const revisions = profiles.getByRole('table', { name: '리비전 목록' })
  for (const [model, revision] of [['humanoid-a', 2], ['quadruped-b', 1]] as const) {
    const submit = profiles.getByRole('form', { name: '리비전 제출' })
    await submit.getByLabel('프로파일 문서').setInputFiles(fileURLToPath(new URL(`../../picasso/profile/profiles/${model}.json`, import.meta.url)))
    await submit.getByRole('button', { name: '제출' }).click()
    await expect(page.getByText(`picasso-ref/${model}#${revision} 제출: 반영됨`, { exact: true })).toBeVisible()
    const row = revisions.getByRole('row', { name: new RegExp(`picasso-ref/${model} ${revision}`) })
    await row.getByRole('button', { name: '시험 요청' }).click()
    await expect(page.getByText(`picasso-ref/${model}#${revision} 시험 요청: 반영됨`, { exact: true })).toBeVisible()
    await expect(row).toContainText('TESTED')
    await expect(row).toContainText('PASS (site-runner)')
    await row.getByRole('button', { name: '활성화' }).click()
    await expect(page.getByText(`picasso-ref/${model}#${revision} 활성화: 반영됨`, { exact: true })).toBeVisible()
    await expect(row).toContainText('ACTIVE')
  }

  // 바인딩과 명칭 기록. humanoid-01 은 현장에서 명칭을 티칭했으므로 기록하면 시운전 완료다.
  await page.getByRole('button', { name: '기체', exact: true }).click()
  for (const [robotId, model, revision] of [['humanoid-01', 'humanoid-a', 2], ['quadruped-01', 'quadruped-b', 1]] as const) {
    await page.getByRole('button', { name: robotId, exact: true }).click()
    const robot = page.getByRole('region', { name: `${robotId} 상세` })
    const bind = robot.getByRole('form', { name: '바인딩' })
    await bind.getByLabel('빌드').selectOption('acme/fleet 1.0.0')
    await bind.getByLabel('리비전').selectOption(`picasso-ref/${model}#${revision}`)
    await bind.getByRole('button', { name: '바인딩' }).click()
    await expect(page.getByText(`${robotId} 바인딩: 반영됨`, { exact: true })).toBeVisible()
    await expect(robot.getByText('명칭 기록 없음', { exact: true })).toBeVisible()
    await robot.getByRole('button', { name: '명칭 등록 기록' }).click()
    await expect(page.getByText(`${robotId} 명칭 기록: 반영됨`, { exact: true })).toBeVisible()
  }
  await page.getByRole('button', { name: 'humanoid-01', exact: true }).click()
  await expect(detail.getByRole('heading', { name: '시운전: 완료' })).toBeVisible()
  await expect(detail.getByText('막힘 없음', { exact: true })).toBeVisible()

  // quadruped-01 은 명칭을 티칭하지 않았다. 사람은 기록했는데 기체가 아는 명칭이 없다.
  await page.getByRole('button', { name: 'quadruped-01', exact: true }).click()
  const quadruped = page.getByRole('region', { name: 'quadruped-01 상세' })
  await expect(quadruped.getByRole('heading', { name: '시운전: 미완' })).toBeVisible()
  await expect(quadruped.getByText('기체가 아는 명칭 없음', { exact: true })).toBeVisible()
  await expect(quadruped.getByText(/현장\(화면 밖\): 현장에서 명칭 티칭을 다시/)).toBeVisible()

  // 실제 서버 시운전 판정과 도입 안내를 대조하고 배정 판단 화면으로 이어진다.
  await page.getByRole('button', { name: '도입 준비', exact: true }).click()
  await onboarding.getByLabel('준비를 이어갈 기체').selectOption('quadruped-01')
  await expect(onboarding.getByText(/다음 조치: 아래 상세의 명칭 상태/)).toBeVisible()
  await onboarding.getByLabel('준비를 이어갈 기체').selectOption('humanoid-01')
  await expect(onboarding.getByText('시운전 완료가 확인되었습니다. 운영에서 실제 임무의 배정 가능 여부를 확인하세요.')).toBeVisible()
  await onboarding.getByRole('button', { name: '운영에서 배정 조건 확인' }).click()
  await expect(page.getByRole('form', { name: '작업 지시 폼' })).toBeVisible()
  await page.getByRole('button', { name: '로봇', exact: true }).click()
  await expect(onboarding.getByLabel('준비를 이어갈 기체')).toHaveValue('humanoid-01')
  await page.getByRole('button', { name: '기체', exact: true }).click()

  // 현장 설정(S2 스펙 §3). 엔지니어 모드에서 연결 기준 시간을 바꾸면 새 버전과 이력 행이 보이고, 기체 상세가 그 버전으로
  // 판정한다. 런처가 30초마다 보고하므로 120초면 기체는 계속 신선하다.
  await page.getByRole('button', { name: '현장 설정' }).click()
  const settings = page.getByRole('region', { name: '현장 설정' })
  const change = settings.getByRole('form', { name: '현장 설정 변경' })
  await change.getByLabel('연결 기준 시간(초)').fill('120')
  await change.getByLabel('변경 사유').fill('연결 기준 늘림')
  await change.getByRole('button', { name: '변경' }).click()
  await expect(page.getByText('연결 기준 시간 120초로 변경: 반영됨', { exact: true })).toBeVisible()
  const versions = settings.getByRole('table', { name: '현장 설정 버전 이력' })
  await expect(versions.getByRole('row', { name: /^2 120초 local 엔지니어 연결 기준 늘림/ })).toBeVisible()
  await page.getByLabel('운영자').check()
  await expect(settings.getByText('현장 설정 변경은 엔지니어 모드에서 합니다', { exact: true })).toBeVisible()
  await page.getByLabel('엔지니어').check()
  await page.getByRole('navigation', { name: '영역' }).getByRole('button', { name: '로봇', exact: true }).click()
  await page.getByRole('button', { name: 'humanoid-01', exact: true }).click()
  await expect(detail.getByText('기준 120초, 현장 설정 버전 2', { exact: true })).toBeVisible()

  // 현장 시간값(S3c 스펙 §3). 엔지니어 모드에서 stallWindow 를 바꾸면 버전 3 과 시간값 열이 붙은 이력 행이 보이고, 실행 호스트가
  // ops 의 현재 버전 뷰를 읽어 적용한 버전이 따로 보인다. 실행 호스트는 운영 서비스보다 먼저 떠 미적용으로 시작하고 운영 서비스가
  // 마이그레이션을 마친 뒤 적용하므로 여기서는 이미 버전 2 다. 화면은 5초마다 다시 읽고 호스트는 1초마다 읽는다.
  const hostApplied = { timeout: 15_000 }
  await page.getByRole('button', { name: '현장 설정' }).click()
  await expect(settings.getByText('실행 호스트 반영: 버전 2', { exact: true })).toBeVisible(hostApplied)
  await change.getByLabel('stallWindow(초)').fill('600')
  await change.getByLabel('변경 사유').fill('정체 표시 늦춤')
  await change.getByRole('button', { name: '변경' }).click()
  await expect(page.getByText('stallWindow 600초로 변경: 반영됨', { exact: true })).toBeVisible()
  await expect(versions.getByRole('row', { name: /^3 120초 local 엔지니어 정체 표시 늦춤 .* 30초 15초 60초 600초$/ })).toBeVisible()
  await expect(versions.getByRole('row', { name: /^2 120초 local 엔지니어 연결 기준 늘림 .* 30초 15초 60초 300초$/ })).toBeVisible()
  await expect(settings.getByText('실행 호스트 반영: 버전 3', { exact: true })).toBeVisible(hostApplied)

  // 운영(S3a 스펙 §3). 시운전 완료는 humanoid-01 하나다. quadruped-01 은 명칭 막힘으로 시운전 미완이라 배정 불가다.
  await page.getByLabel('운영자').check()
  await page.getByRole('button', { name: '운영', exact: true }).click()
  const order = page.getByRole('form', { name: '작업 지시 폼' })
  await order.getByLabel('임무').selectOption('InspectAsset')
  await order.getByLabel('대상 1 id').fill('T1')
  await order.getByLabel('대상 1 장소').fill('bay-7')
  const eligibility = page.getByRole('table', { name: '기체별 배정 가능' })
  await expect(eligibility.getByRole('row', { name: /^humanoid-01 / }).getByRole('cell', { name: '가능', exact: true })).toHaveText('가능')
  const quadrupedRow = eligibility.getByRole('row', { name: /^quadruped-01 / })
  await expect(quadrupedRow.getByRole('cell', { name: '불가', exact: true })).toHaveText('불가')
  await expect(quadrupedRow).toContainText('시운전이 끝나지 않았다')
  const manualReceived = page.waitForResponse(response => response.url().endsWith('/api/work-requests/manual') && response.request().method() === 'POST')
  await order.getByRole('button', { name: '작업 요청 내기' }).click()
  const manualReply = await manualReceived
  expect(manualReply.status()).toBe(202)
  const manual = await manualReply.json()
  expect(manual.receipt.sourceSystemId).toBe('ops:manual')
  expect(manual.receipt.payload.requestedBy).toBe('operator/local')

  const notice = page.getByRole('status', { name: '제출 결과' })
  await expect(notice).toContainText('InspectAsset 작업 지시: 배정됨')
  await expect(notice.locator('dt', { hasText: '배정된 기체' }).locator('xpath=following-sibling::dd[1]')).toHaveText('humanoid-01')
  const executions = page.getByRole('table', { name: '실행 목록' })
  const run = executions.getByRole('row').filter({ hasText: 'InspectAsset' })
  await expect(run).toContainText('코드 정의')
  // 이동 20초와 점검 12초(±10%)를 실제 시간으로 돈다.
  await expect(run).toContainText('PHYSICALLY_DONE', { timeout: 90_000 })

  const manualTrace = page.getByRole('region', { name: '수동 작업 요청 추적' })
  await manualTrace.getByRole('button', { name: '작업 요청·실행 결과 보기' }).click()
  const manualDetail = page.getByRole('article', { name: '작업 요청 상세' })
  await expect(manualDetail).toContainText(manual.receipt.jobOrderId)
  await expect(manualDetail).toContainText('operator/local')
  await expect(manualDetail).toContainText('로컬 작업 · 외부 수신 확인 대상 아님')
  const manualReplay = page.waitForResponse(response => response.url().endsWith('/api/work-requests/manual') && response.request().method() === 'POST')
  await manualTrace.getByRole('button', { name: '동일 작업 요청 확인' }).click()
  expect((await (await manualReplay).json()).duplicate).toBe(true)
  await expect(executions.getByRole('row').filter({ hasText: manual.receipt.jobOrderId })).toHaveCount(1)
  const originalViewport = page.viewportSize()!
  await page.getByRole('region', { name: '작업 지시', exact: true }).screenshot({ path: fileURLToPath(new URL('../../docs/screens/style-preview/manual-request-fullstack.png', import.meta.url)), style: '.skip-link { visibility:hidden!important; }' })
  await page.setViewportSize({ width: 390, height: 900 })
  await page.getByRole('region', { name: '작업 지시', exact: true }).screenshot({ path: fileURLToPath(new URL('../../docs/screens/style-preview/manual-request-mobile-fullstack.png', import.meta.url)) })
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(391)
  await page.setViewportSize(originalViewport)
  await page.reload()
  await page.getByLabel('운영자', { exact: true }).check()
  await page.getByRole('button', { name: '운영', exact: true }).click()
  await expect(manualTrace).toContainText(manual.receipt.externalOrderId)
  const restoredReplay = page.waitForResponse(response => response.url().endsWith('/api/work-requests/manual') && response.request().method() === 'POST')
  await manualTrace.getByRole('button', { name: '동일 작업 요청 확인' }).click()
  const restored = await (await restoredReplay).json()
  expect(restored.duplicate).toBe(true)
  expect(restored.receipt.receiptId).toBe(manual.receipt.receiptId)
  await expect(executions.getByRole('row').filter({ hasText: manual.receipt.jobOrderId })).toHaveCount(1)

  // 임무(S3b 스펙 §3). 엔지니어 모드로 데이터 정의를 초안 저장 → 검증 → 모의 실행 → 활성화한다. 시운전 완료 기체는
  // humanoid-01 하나이고 pick_place 를 가지므로 검증을 지난다. 모의 실행은 실행 호스트 안의 별도 mimic 으로 1~3초 돈다.
  await page.getByLabel('엔지니어').check()
  await page.getByRole('button', { name: '임무' }).click()
  await page.getByRole('navigation', { name: '임무 관리' }).getByRole('button', { name: '호출 연결', exact: true }).click()
  const interfaces = page.getByRole('region', { name: '버전별 호출 계약' })
  await interfaces.getByRole('button', { name: '호출 계약 만들기' }).click()
  const interfaceForm = interfaces.getByRole('region', { name: '호출 계약 작성' })
  await interfaceForm.getByLabel('호출 식별자', { exact: true }).fill('asset-check')
  await interfaceForm.getByLabel('계약 이름', { exact: true }).fill('MES 자산 점검')
  await interfaceForm.getByLabel('대상 임무', { exact: true }).selectOption('InspectAsset')
  await interfaceForm.getByRole('button', { name: '매핑 검증·미리보기' }).click()
  await expect(interfaceForm.getByLabel('계약 매핑 미리보기')).toContainText('JO-PREVIEW')
  await interfaceForm.getByRole('button', { name: '계약 초안 저장' }).click()
  const savedInterface = interfaces.getByRole('region', { name: '선택한 호출 계약' })
  await savedInterface.getByLabel('공개 변경 사유').fill('대상 목록 매핑 검증 완료')
  await savedInterface.getByRole('button', { name: '호출 계약 공개' }).click()
  await expect(interfaces.getByRole('status', { name: '호출 계약 처리 결과' })).toHaveText('asset-check v1: 공개')
  const deploymentPanel=savedInterface.getByRole('region',{name:'실행 배포'})
  await deploymentPanel.getByLabel('배포 판단 사유').fill('초기 코드 정의 명시적 고정')
  await deploymentPanel.getByRole('button',{name:'새 실행 배포 적용'}).click()
  await expect(deploymentPanel).toContainText('실행 배포 · v2')
  await page.getByLabel('운영자', { exact: true }).check()
  await savedInterface.getByText('공개된 계약으로 작업 요청', { exact: true }).click()
  await savedInterface.getByRole('button',{name:'이 입력으로 배정 가능 확인'}).click()
  await expect(savedInterface.getByRole('table',{name:'기체별 배정 가능'})).toContainText('navigate_to')
  await expect(savedInterface.getByText(/현재 배정 가능한 기체가 있습니다/)).toBeVisible()
  await savedInterface.getByLabel('이 입력으로 실제 작업을 지시합니다').check()
  await savedInterface.getByRole('button', { name: '호출 계약으로 작업 요청' }).click()
  await expect(savedInterface.getByRole('status', { name: '계약 호출 결과' })).toContainText('ACCEPTED')
  const invokeReceipt = JSON.parse(await savedInterface.getByLabel('계약 호출 응답 JSON').innerText())
  expect(invokeReceipt.receipt.normalizedOrder.deployment.deploymentVersion).toBe(2)
  expect(invokeReceipt.receipt.normalizedOrder.missionVersion).toBe('CODE')
  await savedInterface.getByRole('button', { name: '동일 계약 작업 요청 확인' }).click()
  await expect(savedInterface.getByRole('status', { name: '계약 호출 결과' })).toContainText('추가 실행 없음')
  expect(JSON.parse(await savedInterface.getByLabel('계약 호출 응답 JSON').innerText()).receipt.receiptId).toBe(invokeReceipt.receipt.receiptId)
  const mappedJob = JSON.parse(await savedInterface.getByLabel('계약 호출 응답 JSON').innerText()).receipt.jobOrderId as string
  await page.getByRole('navigation', { name: '영역' }).getByRole('button', { name: '운영', exact: true }).click()
  await expect(page.getByRole('table', { name: '실행 목록' }).getByRole('row').filter({ hasText: mappedJob })).toContainText('PHYSICALLY_DONE', { timeout: 90_000 })

  // Mock MES uses the real versioned northbound HTTP endpoint, not Ops mode headers.
  const auth = { Authorization: 'Bearer picasso-e2e-mock-mes-only' }
  const upstream = { externalOrderId: 'MES-E2E-204', interfaceId: 'asset-check', interfaceVersion: 1, input: { assets: [{ id: 'T1', location: 'bay-7' }] } }
  expect((await page.request.post('/integration/v1/job-orders', { data: upstream })).status()).toBe(401)
  const received = await page.request.post('/integration/v1/job-orders', { headers: auth, data: upstream })
  expect(received.status()).toBe(202)
  const receipt = (await received.json()).receipt as { receiptId: string; jobOrderId: string; state: string }
  expect(receipt.state).toBe('ACCEPTED')
  const replay = await page.request.post('/integration/v1/job-orders', { headers: auth, data: upstream })
  expect(replay.status()).toBe(200)
  expect((await replay.json()).receipt.receiptId).toBe(receipt.receiptId)
  expect((await page.request.post('/integration/v1/job-orders', { headers: auth, data: { ...upstream, input: { assets: [{ id: 'T2', location: 'bay-7' }] } } })).status()).toBe(409)
  const rejected = await page.request.post('/integration/v1/job-orders', { headers: auth, data: { ...upstream, externalOrderId: 'MES-E2E-BAD', input: {} } })
  expect(rejected.status()).toBe(202)
  expect((await rejected.json()).receipt.state).toBe('REJECTED')
  // The collector must persist a result without a detail/latest-result GET triggering observation.
  await expect.poll(async()=> (await page.request.get(`/integration/v1/job-orders/${receipt.receiptId}/results/1`,{headers:auth})).status(),{timeout:90_000}).toBe(200)
  await expect.poll(async()=> {
    const rows=await (await page.request.get('http://127.0.0.1:8786/received')).json()
    return rows.some((row:{body:{receiptId:string};attempts:number})=>row.body.receiptId===receipt.receiptId && row.attempts>=2)
  },{timeout:30_000}).toBe(true)
  const requests = page.getByRole('region', { name: '상위 작업 요청' })
  await requests.getByRole('button', { name: '작업 요청 다시 조회' }).click()
  await requests.getByRole('button', { name: 'MES-E2E-204 작업 요청 보기' }).click()
  const receiptDetail = requests.getByRole('article', { name: '작업 요청 상세' })
  await expect(receiptDetail).toContainText(receipt.jobOrderId)
  await expect(receiptDetail).toContainText('PHYSICALLY_DONE', { timeout: 90_000 })
  const polled = await page.request.get(`/integration/v1/job-orders/${receipt.receiptId}`, { headers: auth })
  expect(polled.status()).toBe(200)
  expect((await polled.json()).execution.physicalState).toBe('PHYSICALLY_DONE')
  const resultBase = `/integration/v1/job-orders/${receipt.receiptId}/results`
  const resultReply = await page.request.get(`${resultBase}/latest`, { headers: auth })
  expect(resultReply.status()).toBe(200)
  const result = await resultReply.json()
  expect(result.snapshot.jobResponse.physicalState).toBe('PHYSICALLY_DONE')
  expect(result.acknowledgedAt).toBeNull()
  expect((await page.request.post(`${resultBase}/${result.version}/ack`, { data: { resultHash: result.resultHash } })).status()).toBe(401)
  expect((await page.request.post(`${resultBase}/${result.version}/ack`, { headers: auth, data: { resultHash: '0'.repeat(64) } })).status()).toBe(409)
  const ack = await page.request.post(`${resultBase}/${result.version}/ack`, { headers: auth, data: { resultHash: result.resultHash } })
  expect(ack.status()).toBe(200)
  const acknowledged = await ack.json()
  expect(acknowledged.acknowledgedAt).toBeTruthy()
  const ackAgain = await page.request.post(`${resultBase}/${result.version}/ack`, { headers: auth, data: { resultHash: result.resultHash } })
  expect(await ackAgain.json()).toEqual(acknowledged)
  expect((await (await page.request.get(`${resultBase}/${result.version}`, { headers: auth })).json()).snapshot).toEqual(result.snapshot)
  await requests.getByRole('button', { name: '작업 요청 다시 조회' }).click()
  await expect(receiptDetail.getByRole('region', { name: '외부 결과 수신 확인' })).toContainText(`v${result.version} 수신 확인됨`)
  expect(await page.getByRole('table', { name: '실행 목록' }).getByRole('row').filter({ hasText: receipt.jobOrderId }).count()).toBe(1)
  await expect(receiptDetail.getByLabel('결과 전달 이력')).toContainText('DELIVERED')
  await page.getByRole('navigation',{name:'영역'}).getByRole('button',{name:'운영 개요',exact:true}).click()
  const overview=page.getByRole('region',{name:'운영 개요'})
  await expect(overview.getByRole('link',{name:/총 작업 요청/})).not.toContainText('모름')
  await expect(overview.getByRole('region',{name:'결과 자동 수집'})).toContainText('수집 중')
  const captureStyle='.skip-link { visibility:hidden!important; }'
  await overview.screenshot({path:fileURLToPath(new URL('../../docs/screens/style-preview/overview-fullstack.png',import.meta.url)),style:captureStyle})
  const overviewViewport=page.viewportSize()!
  await page.setViewportSize({width:390,height:900})
  await overview.screenshot({path:fileURLToPath(new URL('../../docs/screens/style-preview/overview-mobile-fullstack.png',import.meta.url)),style:captureStyle})
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(391)
  await page.setViewportSize(overviewViewport)
  await overview.getByRole('link',{name:/작업 요청 거부/}).click()
  await expect(requests.getByLabel('작업 요청 상태',{exact:true})).toHaveValue('REJECTED')
  await requests.getByRole('button',{name:'MES-E2E-BAD 작업 요청 보기'}).click()
  await expect(page).toHaveURL(/#requests\?.*receipt=/)
  await page.reload()
  await expect(requests.getByLabel('작업 요청 상태',{exact:true})).toHaveValue('REJECTED')
  await expect(requests.getByRole('article',{name:'작업 요청 상세'})).toContainText('MES-E2E-BAD')
  await requests.screenshot({path:fileURLToPath(new URL('../../docs/screens/style-preview/workspace-requests-fullstack.png',import.meta.url)),style:captureStyle})
  await page.getByLabel('엔지니어', { exact: true }).check()
  await page.getByRole('navigation', { name: '영역' }).getByRole('button', { name: '임무', exact: true }).click()
  await page.getByRole('navigation', { name: '임무 관리' }).getByRole('button', { name: '호출 연결', exact: true }).click()
  await interfaces.getByRole('button', { name: 'asset-check v1 보기' }).click()
  await savedInterface.getByLabel('공개 변경 사유').fill('연계 시연 종료')
  await savedInterface.getByRole('button', { name: '공개 중지' }).click()
  await expect(interfaces.getByRole('status', { name: '호출 계약 처리 결과' })).toHaveText('asset-check v1: 공개 중지')
  const blocked = await page.request.post('/api/mission-interfaces/asset-check/1/invoke', { headers: { 'X-Ops-Mode': 'operator', 'X-Ops-User': 'local' }, data: { requestId: crypto.randomUUID(), input: { assets: [{ id: 'T1', location: 'bay-7' }] } } })
  expect(blocked.status()).toBe(202)
  expect((await blocked.json()).receipt.state).toBe('REJECTED')
  await page.getByRole('button', { name: '입력 검증·변환 미리보기' }).click()
  await expect(page.getByLabel('변환된 작업 지시 JSON')).toContainText('JO-PREVIEW')
  await expect(page.getByLabel('변환된 작업 지시 JSON')).toContainText('equipmentRequirements')
  await page.getByRole('navigation', { name: '임무 관리' }).getByRole('button', { name: '실행 정의', exact: true }).click()
  const editor = page.getByRole('region', { name: '임무 편집' })
  const missionNotice = editor.getByRole('status', { name: '임무 조작 결과' })
  await editor.getByRole('button', { name: '데이터 정의 템플릿 불러오기' }).click()
  await editor.getByRole('button', { name: '초안 저장' }).click()
  await expect(missionNotice).toHaveText('초안 저장: 초안 1 저장됨')
  await editor.getByRole('button', { name: '검증', exact: true }).click()
  await expect(missionNotice).toHaveText('초안 1 검증: 통과')
  await editor.getByRole('button', { name: '모의 실행', exact: true }).click()
  await expect(missionNotice).toContainText('초안 1 모의 실행: 통과')
  await editor.getByLabel('활성화 사유').fill('데이터 정의로 옮김')
  await editor.getByRole('button', { name: '활성화', exact: true }).click()
  await expect(missionNotice).toHaveText('초안 1 활성화: 버전 1 활성화됨. 다음 작업 지시부터 이 버전을 씁니다')
  await page.getByRole('navigation', { name: '임무 관리' }).getByRole('button', { name: '버전·배포', exact: true }).click()
  const missionVersions = page.getByRole('table', { name: '임무 버전 이력' })
  await expect(missionVersions.getByRole('row', { name: /^버전 1 \(활성\) 초안 1 local 데이터 정의로 옮김 / })).toBeVisible()

  // 셀 대역 신호(S3b 스펙 §8). 사람이 PLC 역할을 하는 정상 조작이라 운영자 모드에서도 한다.
  await page.getByLabel('운영자').check()
  await page.getByRole('button', { name: '운영', exact: true }).click()
  const signals = page.getByRole('table', { name: '셀 대역 신호' })
  await expect(signals.getByRole('row', { name: /^rack_present BOOLEAN false / })).toBeVisible()
  await signals.getByRole('button', { name: 'rack_present 켜기' }).click()
  await expect(page.getByRole('status', { name: '신호 조작 결과' })).toHaveText('rack_present 켜기: 반영됨(값 true)')
  await expect(signals.getByRole('row', { name: /^rack_present BOOLEAN true / })).toBeVisible()

  // 장애 주입(S4a 스펙 §3). 운영자가 데이터 정의 버전 1 로 슬롯 하나짜리 PrepareSequencedRack 을 내고, pick_place 가 도는 동안
  // 엔지니어가 검증 도구 영역에서 스킬 실패를 넣는다. 슬롯 S01 은 아직 채운 적이 없어 근거가 없으므로 단위가 FAILED 이고
  // GRASP_FAILED 인시던트가 선다. 현장 기체의 pick_place 는 이것이 처음이고 강제한 태스크는 자연 실패 추첨을 하지 않는다.
  await page.getByRole('button', { name: '새 작업 작성', exact: true }).click()
  await order.getByLabel('임무').selectOption('PrepareSequencedRack')
  await order.getByLabel('RACK-204.S01').check()
  await order.getByLabel('자재').selectOption('ENGINE-COVER-A')
  await order.getByRole('button', { name: '작업 요청 내기' }).click()
  const rackRuns = executions.getByRole('row').filter({ hasText: 'PrepareSequencedRack' })
  const graspRun = rackRuns.filter({ hasText: 'RACK-204.S01 pick_place' })
  // 작업 지시 직후의 태스크는 ACCEPTED 라 현장이 거부한다. RUNNING 이 보인 뒤에 넣는다. pick_place 는 45초 ±10% 다.
  await expect(graspRun).toContainText('RACK-204.S01 pick_place: RUNNING')
  await page.getByLabel('엔지니어').check()
  await page.getByRole('button', { name: '검증 도구' }).click()
  const faultForm = page.getByRole('form', { name: '장애 주입 폼' })
  await faultForm.getByLabel('기체').selectOption('humanoid-01')
  await faultForm.getByLabel('장애 종류').selectOption('SKILL_EXECUTION_FAILED')
  await faultForm.getByLabel('장애 주입 사유').fill('스킬 실패 시연')
  await faultForm.getByRole('button', { name: '장애 넣기' }).click()
  await expect(page.getByRole('status', { name: '장애 주입 결과' })).toHaveText(
    /^humanoid-01 스킬 실패: 받아들임\(태스크 .+#RACK-204\.S01, RETRIABLE\)$/,
  )
  await page.getByRole('button', { name: '운영', exact: true }).click()
  const incidentTable = page.getByRole('table', { name: '인시던트 목록' })
  const grasp = incidentTable.getByRole('row').filter({ hasText: 'GRASP_FAILED' })
  // 현장 설정은 버전 3(stallWindow 변경), 임무는 데이터 정의 버전 1 이다.
  await expect(grasp).toContainText('humanoid-01')
  await expect(grasp.getByRole('cell').nth(8)).toHaveText('버전 3')
  await expect(grasp.getByRole('cell').nth(9)).toHaveText('버전 1')
  await expect(grasp.getByRole('cell').nth(10)).toHaveText('판단 대상 아님')
  await expect(graspRun).toContainText('RACK-204.S01 pick_place: FAILED')

  // 잔여 파지(P0-b2). pick_place 가 든 채로 실패했으므로 실행 호스트가 humanoid-01 에 표시를 세우고 일반 배정에서 뺀다(관문 확인용으로만
  // 후보가 되어 조치 제안을 얻을 뿐 실행은 서지 않는다, picasso §15.317). 운영자가
  // 현장에서 빈손을 확인해 «기체 빈손 확인» 으로 풀어야 다음 작업 지시(운영자 보류)가 humanoid-01 에 배정된다. mimic 손은 아직 든 채라 실행 호스트가
  // 확인 순간 읽은 스냅숏이 HOLDING 이고 첫 확인은 어긋남(B2)이다. 그 관측을 지목하고 «현장에서 빈손을 확인했다» 를 켜 다시 확인해야 풀린다.
  const graspJobOrder = (await graspRun.getByRole('cell').nth(1).textContent())!.trim()
  await page.getByLabel('운영자').check()
  const residual = page.getByRole('region', { name: '잔여 파지 확인', exact: true })
  const residualRow = residual.getByRole('table', { name: '미해소 잔여 파지' }).getByRole('row').filter({ hasText: graspJobOrder })
  await expect(residualRow).toContainText('humanoid-01')
  await expect(residualRow).toContainText('RACK-204.S01')
  // 배정 가능은 폼만으로 판정하므로 내지 않고 다음 슬롯을 채워 본다. 보관된 요청(S01)은 아래 운영자 보류 단계가 닫는다.
  await order.getByLabel('임무').selectOption('PrepareSequencedRack')
  await order.getByLabel('RACK-204.S02').check()
  await order.getByLabel('자재').selectOption('ENGINE-COVER-A')
  const humanoidEligibility = eligibility.getByRole('row', { name: /^humanoid-01 / })
  await expect(humanoidEligibility.getByRole('cell', { name: '관문 확인용', exact: true })).toHaveText('관문 확인용')
  await expect(humanoidEligibility).toContainText(`잔여 파지 확인 필요: ${graspJobOrder} RACK-204.S01`)
  await residualRow.getByRole('button', { name: '기체 빈손 확인' }).click()
  const emptyForm = residual.getByRole('form', { name: '기체 빈손 확인' })
  await emptyForm.getByLabel('빈손 확인 근거').fill('현장에서 그리퍼 빈손 확인')
  await emptyForm.getByLabel('기체 손에 남은 물체가 없음을 현장에서 직접 확인했습니다').check()
  await emptyForm.getByRole('button', { name: '기체 빈손 확인' }).click()
  await expect(residual.getByRole('status', { name: '빈손 확인 결과' })).toHaveText(
    `humanoid-01 기체 빈손 확인(${graspJobOrder} RACK-204.S01): 발신자가 기체가 아직 든 채라고 말해 해소하지 않음. 현장에서 빈손을 확인했으면 아래 관측을 지목해 다시 확인하십시오`,
  )
  const mismatch = emptyForm.getByRole('group', { name: '발신자 관측과 어긋남' })
  await expect(mismatch).toContainText('HOLDING')
  const reconfirm = mismatch.getByRole('button', { name: '관측을 지목해 다시 확인' })
  await expect(reconfirm).toBeDisabled()
  await mismatch.getByLabel(/^발신자는 지금 .+ 를 들고 있다고 말한다\. 현장에서 빈손을 확인했다$/).check()
  await reconfirm.click()
  await expect(residual.getByRole('status', { name: '빈손 확인 결과' })).toHaveText(
    `humanoid-01 기체 빈손 확인(${graspJobOrder} RACK-204.S01): 빈손 확인이 기록되었습니다. 그 기체는 다음 배정 판정부터 다시 판정됩니다`,
  )
  await expect(residual.getByText('미해소 잔여 파지 표시가 없습니다', { exact: true })).toBeVisible()
  await expect(humanoidEligibility.getByRole('cell', { name: '가능', exact: true })).toHaveText('가능')
  await page.getByLabel('엔지니어').check()

  // 운영자 보류(S4a 스펙 §3). 활성화한 보류 버전은 DB 에 남으므로 맨 끝에 둔다. 런처는 1:1 실시간이고 시계를 밀 수단이 없어
  // 기한 20초를 실제 시간으로 기다린다. 재작업 뒤 대기는 새 기한 20초로 다시 돌므로 다시 돈 것을 본 즉시 신호를 켠다.
  await page.getByRole('button', { name: '임무' }).click()
  await page.getByRole('navigation', { name: '임무 관리' }).getByRole('button', { name: '실행 정의', exact: true }).click()
  await editor.getByRole('button', { name: '운영자 보류 대기 템플릿 불러오기' }).click()
  await editor.getByRole('button', { name: '초안 저장' }).click()
  await expect(missionNotice).toHaveText('초안 저장: 초안 2 저장됨')
  await editor.getByRole('button', { name: '모의 실행', exact: true }).click()
  await expect(missionNotice).toContainText('초안 2 모의 실행: 통과')
  await editor.getByLabel('활성화 사유').fill('운영자 보류 대기 도입')
  await editor.getByRole('button', { name: '활성화', exact: true }).click()
  await expect(missionNotice).toHaveText('초안 2 활성화: 버전 2 활성화됨. 다음 작업 지시부터 이 버전을 씁니다')

  // 셀 대역 신호는 스스로 돌아가지 않는다. 앞에서 켠 rack_present 를 끄지 않으면 대기가 곧바로 끝난다.
  await page.getByLabel('운영자').check()
  await page.getByRole('button', { name: '운영', exact: true }).click()
  await signals.getByRole('button', { name: 'rack_present 끄기' }).click()
  await expect(signals.getByRole('row', { name: /^rack_present BOOLEAN false / })).toBeVisible()
  // 영역을 옮기면 작업 지시 폼이 처음 값(InspectAsset)으로 돌아온다.
  await page.getByRole('button', { name: '새 작업 작성', exact: true }).click()
  await order.getByLabel('임무').selectOption('PrepareSequencedRack')
  await order.getByLabel('RACK-204.S02').check()
  await order.getByLabel('자재').selectOption('ENGINE-COVER-A')
  await order.getByRole('button', { name: '작업 요청 내기' }).click()
  const holdRun = rackRuns.filter({ hasText: 'rack-arrival equipment_wait' })
  await expect(holdRun).toContainText('rack-arrival equipment_wait: RUNNING')
  const held = incidentTable.getByRole('row').filter({ hasText: '보류 중' })
  await expect(held).toContainText('SIGNAL_DEADLINE')
  await expect(held.getByRole('cell').nth(9)).toHaveText('버전 2')
  await expect(held.getByRole('cell').nth(10)).toHaveText('미해결')
  await held.getByRole('button', { name: /상세 보기$/ }).click()
  const incidentDetail = page.getByRole('region', { name: '인시던트 상세' })
  await expect(incidentDetail).toContainText('사람의 판단 없음. 아래 값은 모두 관측입니다')
  // 판단 버튼은 운영자 모드에서만 보인다.
  await page.getByLabel('엔지니어').check()
  await expect(incidentDetail.getByText('보류 중입니다. 운영자 판단은 운영자 모드에서 합니다', { exact: true })).toBeVisible()
  await expect(incidentDetail.getByRole('button', { name: '재작업' })).toHaveCount(0)
  await page.getByLabel('운영자').check()
  const decision = incidentDetail.getByRole('form', { name: '운영자 판단' })
  await decision.getByLabel('판단 사유').fill('랙 재배치 뒤 재작업')
  await decision.getByRole('button', { name: '재작업' }).click()
  await expect(page.getByRole('status', { name: '판단 결과' })).toHaveText(/\/rack-arrival 재작업: 판단이 섰습니다\(incident-\d+\)$/)
  await expect(holdRun).toContainText('rack-arrival equipment_wait: RUNNING', { timeout: 10_000 })
  await signals.getByRole('button', { name: 'rack_present 켜기' }).click()
  await expect(page.getByRole('status', { name: '신호 조작 결과' })).toHaveText('rack_present 켜기: 반영됨(값 true)')
  await expect(holdRun).toContainText('PHYSICALLY_DONE', { timeout: 90_000 })
  const asserted = incidentDetail.getByRole('region', { name: '사람의 판단' })
  await expect(asserted).toContainText('사람이 판단함: local, ')
  await expect(asserted).toContainText('결정 재작업(REWORK)')
  const deadline = incidentTable.getByRole('row').filter({ hasText: 'SIGNAL_DEADLINE' })
  await expect(deadline).toHaveCount(1)
  await expect(deadline.getByRole('cell').nth(10)).toHaveText('판단됨')
  await expect(deadline.getByRole('cell').nth(11)).toHaveText('-')

  // 작업 응답 송신 기록(S4b 스펙 T10·T11). 보류 작업 지시가 낸 응답이 송신 행으로 남는다. 재기동하지 않았으므로 재기동 중복은
  // 없다. 재기동 단계는 통합 시험(RestartRecoveryTest)이 본다.
  const holdJobOrder = (await holdRun.getByRole('cell').nth(1).textContent())!.trim()
  const responseLog = page.getByRole('region', { name: '작업 응답 송신 기록' })
  await responseLog.getByLabel('송신 기록의 작업 지시').selectOption(holdJobOrder)
  const sentRows = responseLog.getByRole('table', { name: '송신 기록 목록' }).getByRole('row').filter({ hasText: holdJobOrder })
  // 처분 칸(열째)은 글자 그대로 송신이다. 재기동 중복의 표시(재기동 중복(송신 안 함))도 송신을 품으므로 포함 검사로는 못 가른다.
  await expect(sentRows.filter({ hasText: 'PHYSICALLY_DONE' }).getByRole('cell').nth(9)).toHaveText('송신')
  await expect(sentRows.filter({ hasText: '재기동 중복' })).toHaveCount(0)
  await expect(responseLog).toContainText('그 가운데 재기동 중복 0건')

  // 결함 감수. 활성 버전 2(운영자 보류 대기)로 슬롯 S03 을 내고, 랙 도착 대기가 도는 동안(로봇 태스크 없음) 제어권 상실을 넣는다.
  // 화면의 장애 주입 양식에는 이 종류가 없어 운영 서비스 API 로 넣는다. 대기가 끝나면 pick_place 의 시작 전 게이트가 실행을 막는다.
  // 운영자가 결함 카드를 보고 사유와 현장 확인을 넣어 감수하면 pick_place 가 나간다. 결함은 기체에 그대로 서 있어 같은 기체의 다음
  // 실행이 다시 막히므로 끝나면 제어권 회복으로 지운다. 대기 기한 20초 안에 신호를 켠다.
  const engineerApi = { 'X-Ops-Mode': 'engineer', 'X-Ops-User': 'local' }
  await signals.getByRole('button', { name: 'rack_present 끄기' }).click()
  await expect(signals.getByRole('row', { name: /^rack_present BOOLEAN false / })).toBeVisible()
  await page.getByRole('button', { name: '새 작업 작성', exact: true }).click()
  await order.getByLabel('임무').selectOption('PrepareSequencedRack')
  // 새 작업 작성은 앞 작업 지시의 슬롯 선택(S02)을 남긴다. 단위를 pick_place 하나로 두려고 끈다.
  await order.getByLabel('RACK-204.S02').uncheck()
  await order.getByLabel('RACK-204.S03').check()
  await order.getByLabel('자재').selectOption('ENGINE-COVER-A')
  const blockSubmitted = page.waitForResponse(response => response.url().endsWith('/api/work-requests/manual') && response.request().method() === 'POST')
  await order.getByRole('button', { name: '작업 요청 내기' }).click()
  const blockJobOrder = (await (await blockSubmitted).json()).receipt.jobOrderId as string
  const blockRun = executions.getByRole('row').filter({ hasText: blockJobOrder })
  await expect(blockRun).toContainText('rack-arrival equipment_wait: RUNNING')
  const lost = await page.request.post('/api/faults', { headers: engineerApi, data: { robotId: 'humanoid-01', kind: 'CONTROL_AUTHORITY_LOST', reason: '제어권 상실 시연' } })
  expect(lost.status(), await lost.text()).toBe(200)
  const lostOutcome = await lost.json()
  expect([lostOutcome.result, lostOutcome.fault?.raised, lostOutcome.fault?.terminated]).toEqual(['SUCCEEDED', true, []])
  await signals.getByRole('button', { name: 'rack_present 켜기' }).click()
  await expect(blockRun).toContainText('결함 차단 1건')
  await expect(blockRun).toContainText('OPERATOR_HOLD')
  await expect(blockRun).toContainText('RACK-204.S03 pick_place: PENDING')
  const blockExecution = (await blockRun.getByRole('cell').first().textContent())!.trim()
  const releaseForm = page.getByRole('form', { name: '결함 수용' })
  await releaseForm.getByLabel('수용할 실행').selectOption(blockExecution)
  const faultCard = releaseForm.getByRole('group', { name: '수용할 결함' }).getByLabel('수용할 결함 1')
  await expect(faultCard).toContainText('CONTROL_AUTHORITY_LOST(CONTROL_AUTHORITY_LOST)')
  await expect(faultCard).toContainText('다른 클라이언트가 제어 권한을 가져갔다')
  await releaseForm.getByLabel('수용 사유').fill('현장에서 제어권 회복 절차 확인')
  await releaseForm.getByLabel('기체 상태를 현장에서 확인했고 이 결함을 안고 계속합니다').check()
  await releaseForm.getByRole('button', { name: '현장 확인 후 결함 수용' }).click()
  await expect(releaseForm.getByRole('status', { name: '결함 수용 결과' })).toHaveText(
    `${blockExecution}(${blockJobOrder}) 결함 수용: 수용했습니다. 실행 호스트의 다음 pump 에서 다음 단위를 냅니다`,
  )
  await expect(blockRun).toContainText('(결함 수용: local, 현장에서 제어권 회복 절차 확인)')
  await expect(blockRun).not.toContainText('결함 차단')
  await expect(blockRun).toContainText('PHYSICALLY_DONE', { timeout: 90_000 })
  const releasedHost = (await (await page.request.get('http://127.0.0.1:8785/host/executions')).json()).executions
    .find((execution: { jobOrderId: string }) => execution.jobOrderId === blockJobOrder)
  expect([releasedHost.jobResponse.blockedBy, releasedHost.releases.length]).toEqual([[], 1])
  const regained = await page.request.post('/api/faults', { headers: engineerApi, data: { robotId: 'humanoid-01', kind: 'CONTROL_AUTHORITY_REGAINED', reason: '제어권 회복' } })
  expect(regained.status(), await regained.text()).toBe(200)
  const regainedOutcome = await regained.json()
  expect([regainedOutcome.result, regainedOutcome.fault?.cleared]).toEqual(['SUCCEEDED', true])

  // A third mission is registered, validated, deployed and executed entirely through the UI.
  await page.getByLabel('엔지니어',{exact:true}).check()
  await page.getByRole('navigation',{name:'영역'}).getByRole('button',{name:'임무',exact:true}).click()
  await page.getByRole('button',{name:'새 임무 만들기',exact:true}).click()
  const creator=page.getByRole('region',{name:'새 임무 생성'})
  await creator.getByLabel('새 임무 ID').fill('LineAssetCheck')
  await creator.getByLabel('새 임무 이름').fill('라인 자산 점검')
  await creator.getByRole('button',{name:'임무 등록 후 실행 정의 열기'}).click()
  const dynamicEditor=page.getByRole('region',{name:'임무 편집'})
  const dynamicNotice=dynamicEditor.getByRole('status',{name:'임무 조작 결과'})
  await expect(dynamicEditor).toContainText('활성 버전 없음')
  await expect(dynamicEditor.locator('textarea').first()).toHaveValue(/LineAssetCheck/)
  await dynamicEditor.getByRole('button',{name:'초안 저장',exact:true}).click()
  await expect(dynamicNotice).toContainText('저장됨')
  await dynamicEditor.getByRole('button',{name:'검증',exact:true}).click()
  await expect(dynamicNotice).toContainText('검증: 통과')
  await dynamicEditor.getByRole('button',{name:'모의 실행',exact:true}).click()
  await expect(dynamicNotice).toContainText('모의 실행: 통과')
  await dynamicEditor.getByLabel('활성화 사유').fill('신규 업무 전체 여정 검증')
  await dynamicEditor.getByRole('button',{name:'활성화',exact:true}).click()
  await expect(dynamicNotice).toContainText('버전 1 활성화됨')
  await page.getByRole('navigation',{name:'임무 관리'}).getByRole('button',{name:'호출 연결',exact:true}).click()
  await page.getByRole('button',{name:'호출 계약 만들기',exact:true}).click()
  const dynamicInterface=page.getByRole('region',{name:'호출 계약 작성'})
  await dynamicInterface.getByLabel('호출 식별자').fill('line-asset-check')
  await dynamicInterface.getByRole('button',{name:'계약 초안 저장',exact:true}).click()
  const dynamicSaved=page.getByRole('region',{name:'선택한 호출 계약'})
  await expect(dynamicSaved).toContainText('LineAssetCheck')
  const dynamicDeployment=dynamicSaved.getByRole('region',{name:'실행 배포'})
  await dynamicDeployment.getByLabel('배포할 임무 버전').selectOption('1')
  await dynamicDeployment.getByLabel('배포 판단 사유').fill('검증한 정의 v1 연결')
  await dynamicDeployment.getByRole('button',{name:'새 실행 배포 적용'}).click()
  await expect(dynamicDeployment).toContainText('실행 배포 · v1')
  await dynamicSaved.getByLabel('공개 변경 사유').fill('신규 미션 호출 허용')
  await dynamicSaved.getByRole('button',{name:'호출 계약 공개',exact:true}).click()
  await expect(page.getByRole('status',{name:'호출 계약 처리 결과'})).toContainText('line-asset-check v1: 공개')
  await page.evaluate(()=>document.fonts.ready)
  await page.getByRole('region',{name:'버전별 호출 계약'}).screenshot({path:fileURLToPath(new URL('../../docs/screens/style-preview/new-mission-contract-fullstack.png',import.meta.url)),style:captureStyle})
  await page.getByLabel('운영자',{exact:true}).check()
  await dynamicSaved.getByText('공개된 계약으로 작업 요청',{exact:true}).click()
  await dynamicSaved.getByRole('button',{name:'이 입력으로 배정 가능 확인'}).click()
  await expect(dynamicSaved.getByRole('table',{name:'기체별 배정 가능'})).toContainText('inspect')
  await expect(dynamicSaved.getByText(/현재 배정 가능한 기체가 있습니다/)).toBeVisible()
  const preflight = dynamicSaved.getByRole('region',{name:'계약 입력의 배정 가능 미리보기'})
  await preflight.screenshot({path:fileURLToPath(new URL('../../docs/screens/style-preview/interface-eligibility-fullstack.png',import.meta.url)),style:captureStyle})
  const preflightViewport=page.viewportSize()!
  await page.setViewportSize({width:390,height:844})
  await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true)
  await preflight.screenshot({path:fileURLToPath(new URL('../../docs/screens/style-preview/interface-eligibility-mobile-fullstack.png',import.meta.url)),style:captureStyle})
  await page.setViewportSize(preflightViewport)
  await dynamicSaved.getByLabel('이 입력으로 실제 작업을 지시합니다').check()
  await dynamicSaved.getByRole('button',{name:'호출 계약으로 작업 요청'}).click()
  await expect(dynamicSaved.getByRole('status',{name:'계약 호출 결과'})).toContainText('ACCEPTED')
  const dynamicReceipt=JSON.parse(await dynamicSaved.getByLabel('계약 호출 응답 JSON').innerText()).receipt
  expect(dynamicReceipt.normalizedOrder.workMasterId).toBe('LineAssetCheck')
  expect(dynamicReceipt.normalizedOrder.missionVersion).toBe('1')
  await page.getByRole('navigation',{name:'영역'}).getByRole('button',{name:'운영',exact:true}).click()
  await expect(page.getByRole('table',{name:'실행 목록'}).getByRole('row').filter({hasText:dynamicReceipt.jobOrderId})).toContainText('PHYSICALLY_DONE',{timeout:90_000})

  // Compare persisted upstream receipts through the validation workspace.
  await expect.poll(async () => (await (await page.request.get(`/api/work-requests/${dynamicReceipt.receiptId}`)).json()).latestResult?.snapshot?.jobResponse?.physicalState).toBe('PHYSICALLY_DONE')
  await page.getByRole('navigation', { name: '영역' }).getByRole('button', { name: '검증 도구', exact: true }).click()
  const comparison = page.getByRole('region', { name: '제조 시나리오 검증' })
  await comparison.getByLabel('작업 요청 A ID').fill(invokeReceipt.receipt.receiptId)
  await comparison.getByLabel('작업 요청 B ID').fill(dynamicReceipt.receiptId)
  await comparison.getByRole('button', { name: '두 작업 요청 비교하기' }).click()
  await expect(comparison.getByRole('status')).toContainText('계약 또는 실행 배포가 다릅니다')
  await expect(comparison.getByRole('article', { name: '작업 요청 B 근거' })).toContainText('PHYSICALLY_DONE')
  await comparison.getByRole('heading', { name: '호출 계약과 실행 근거 비교' }).click()
  await comparison.screenshot({ path: fileURLToPath(new URL('../../docs/screens/style-preview/manufacturing-comparison-fullstack.png', import.meta.url)), style: captureStyle })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await comparison.screenshot({ path: fileURLToPath(new URL('../../docs/screens/style-preview/manufacturing-comparison-mobile-fullstack.png', import.meta.url)), style: captureStyle })
  await page.setViewportSize({ width: 1440, height: 1000 })

  // Lose an ACCEPTED response after the real host committed it; keep the link down while work completes.
  const beforeCut=await (await page.request.get('http://127.0.0.1:8790/status')).json()
  expect((await page.request.post('http://127.0.0.1:8790/cut-after-submit')).ok()).toBe(true)
  const uncertainBody={externalOrderId:'MES-RESTART-1',interfaceId:'line-asset-check',interfaceVersion:1,input:{targets:[{id:'T1',location:'bay-7'}]}}
  const uncertainReply=await page.request.post('/integration/v1/job-orders',{headers:auth,data:uncertainBody})
  expect(uncertainReply.status()).toBe(202)
  const uncertain=(await uncertainReply.json()).receipt
  expect(uncertain.state).toBe('IN_DOUBT')
  await expect.poll(async()=> (await (await page.request.get('/api/work-requests/collector')).json()).state,{timeout:20_000}).toBe('UNAVAILABLE')
  await page.goto(`/#requests?receipt=${uncertain.receiptId}`)
  await page.getByLabel('운영자',{exact:true}).check()
  const review=page.getByRole('region',{name:'현장 확인 기록'})
  await review.getByLabel('현장 관측').selectOption('IN_PROGRESS')
  await review.getByLabel('확인 근거·담당자·인계 내용').fill('로컬 PoC 담당자 확인: 실제 호스트 수락, 연결 단절 중 진행')
  await review.getByRole('button',{name:'현장 확인 기록 저장',exact:true}).click()
  await expect(review.getByRole('status')).toContainText('현장 확인 기록 저장됨')
  await expect(page.getByRole('region',{name:'현장 확인 이력'})).toContainText('로컬 PoC 담당자 확인')
  await expect.poll(async()=>{
   const current=await (await page.request.get('http://127.0.0.1:8785/host/executions')).json()
   return current.executions.find((e:{jobOrderId:string})=>e.jobOrderId===uncertain.jobOrderId)?.physicalState
  },{timeout:90_000}).toBe('PHYSICALLY_DONE')
  const durableFeed=await (await page.request.get('http://127.0.0.1:8785/host/result-events?limit=200')).json()
  const jobEvents=durableFeed.events.filter((e:{snapshot:{jobOrderId:string}})=>e.snapshot.jobOrderId===uncertain.jobOrderId)
  expect(jobEvents.length).toBeGreaterThan(0)
  expect((await page.request.get(`/integration/v1/job-orders/${uncertain.receiptId}/results/1`,{headers:auth})).status()).toBe(404)
  const cursorBefore=(await (await page.request.get('/api/work-requests/collector')).json()).cursor
  expect((await page.request.post('http://127.0.0.1:8788/restart')).ok()).toBe(true)
  await expect.poll(async()=>{try{return (await page.request.get('/api/work-requests/collector')).status()}catch{return 0}},{timeout:60_000}).toBe(200)
  expect((await (await page.request.get('/api/work-requests/collector')).json()).cursor.sequence).toBe(cursorBefore.sequence)
  const replayAfterRestart=await page.request.post('/integration/v1/job-orders',{headers:auth,data:uncertainBody})
  expect(replayAfterRestart.status()).toBe(200)
  expect((await replayAfterRestart.json()).receipt.receiptId).toBe(uncertain.receiptId)
  expect((await (await page.request.get('http://127.0.0.1:8790/status')).json()).submissions).toBe(beforeCut.submissions+1)
  expect((await page.request.post('http://127.0.0.1:8789/restart')).ok()).toBe(true)
  await expect.poll(async()=>{try{return (await page.request.get('http://127.0.0.1:8785/host/result-events')).status()}catch{return 0}},{timeout:60_000}).toBe(200)
  const hostAfterRestart=await (await page.request.get('http://127.0.0.1:8785/host/result-events?limit=200')).json()
  expect(hostAfterRestart).toEqual(durableFeed)
  expect((await page.request.post('http://127.0.0.1:8790/connect')).ok()).toBe(true)
  await expect.poll(async()=> (await page.request.get(`/integration/v1/job-orders/${uncertain.receiptId}/results/1`,{headers:auth})).status(),{timeout:30_000}).toBe(200)
  const restoredResult=await (await page.request.get(`/integration/v1/job-orders/${uncertain.receiptId}/results/latest`,{headers:auth})).json()
  expect(restoredResult.snapshot.jobResponse.jobResponseId).toBe(jobEvents.at(-1).snapshot.jobResponse.jobResponseId)
  expect(restoredResult.snapshot.jobResponse.physicalState).toBe('PHYSICALLY_DONE')
  await expect.poll(async()=> (await (await page.request.get(`/api/work-requests/${uncertain.receiptId}`)).json()).receipt.state).toBe('ACCEPTED')
  await page.reload()
  await expect(page.getByRole('region',{name:'현장 확인 이력'})).toContainText('로컬 PoC 담당자 확인')
  await expect(page.getByRole('region',{name:'저장 결과의 완료 근거'})).toContainText('PHYSICALLY_DONE')
  await page.getByRole('heading',{name:'작업 요청 상세',exact:true}).click()
  await page.getByRole('article',{name:'작업 요청 상세'}).screenshot({path:'../docs/screens/style-preview/reliability-recovery-fullstack.png'})
  expect((await (await page.request.get('http://127.0.0.1:8790/status')).json()).submissions).toBe(beforeCut.submissions+1)

  // 정상 연계 구간에서 UI가 존재하지 않는 API를 호출하지 않았는지 확인한다.
  expect(apiResponses.filter(response => response.status === 404)).toEqual([])
  for (const [method, path] of [
    ['GET', '/api/robots'], ['GET', '/api/adapters'], ['GET', '/api/profiles'],
    ['GET', '/api/job-contracts'], ['POST', '/api/job-orders/preview'],
    ['GET', '/api/mission-interfaces'], ['POST', '/api/mission-interfaces'],
    ['POST', '/api/mission-interfaces/preview'], ['POST','/api/mission-interfaces/asset-check/1/eligibility'], ['POST', '/api/mission-interfaces/asset-check/1/state'],

    ['POST', '/api/robots'], ['POST', '/api/adapters'], ['POST', '/api/adapter-instances'],
    ['POST', '/api/profile-revisions'], ['POST', '/api/robots/humanoid-01/binding'],
    ['POST', '/api/robots/humanoid-01/site-names'], ['POST', '/api/job-orders/eligibility'],
    ['POST', '/api/work-requests/manual'], ['POST', '/api/faults'],
    ['GET', '/api/robots/residual-holds'],
  ]) expect(apiResponses.some(response => response.method === method && response.path === path && response.status === 200)).toBe(true)
  await test.info().attach('api-responses', { body: JSON.stringify(apiResponses, null, 2), contentType: 'application/json' })

  // registry 를 멈춘다. 런처(registry 와 mimic 이 든 프로세스)를 끈다.
  const pidFile = fileURLToPath(new URL('../../build/site.pid', import.meta.url))
  process.kill(Number(readFileSync(pidFile, 'utf8')))
  await page.getByRole('navigation', { name: '영역' }).getByRole('button', { name: '로봇', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('모름: registry 가 답하지 않습니다')
  await expect(page.getByRole('button', { name: 'humanoid-01', exact: true })).toBeVisible()
  // 기체 목록과 어댑터 목록이 각자 직전 값으로 남는다.
  await expect(page.getByRole('region', { name: '기체 목록' }).getByText(/직전 값입니다/)).toBeVisible()
  await page.getByRole('button', { name: '어댑터 관리', exact: true }).click()
  const adapters = page.getByRole('region', { name: '어댑터' })
  await expect(adapters.getByText(/직전 값입니다/)).toBeVisible()
  await expect(adapters.getByRole('row', { name: /fleet-gw-01/ })).toBeVisible()
  await page.getByRole('button', { name: '프로파일 관리', exact: true }).click()
  await expect(profiles.getByText(/직전 값입니다/)).toBeVisible()
})
