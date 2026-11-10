package dev.picasso.ops.site

import dev.picasso.harness.revision.HttpTestDesk
import dev.picasso.harness.revision.RevisionSuites
import dev.picasso.harness.revision.RevisionTestRunner
import dev.picasso.mimic.cli.MimicCli
import dev.picasso.registry.web.RegistryApplication
import dev.picasso.uplink.report.RegistryLink
import org.flywaydb.core.Flyway
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import java.time.Duration
import java.time.Instant

/**
 * 띄운 가짜 현장 하나. registry 와 mimic 을 이 프로세스 안에 둔다(스펙 §6).
 *
 * mimic CLI 를 쓰지 않는 이유는 스펙 §6 첫 문단이다. CLI 에는 시간을 진행시키는 루프가 없고,
 * registry 는 실행 jar 가 없으며 기동 때 Flyway 를 돌리지 않는다.
 *
 * ## 시계(S3a 스펙 §6.1, T1)
 *
 * 기동 직후 mimic 가상 시계를 실제 시각까지 한 번 민다. 미들웨어는 E2 시간 윈도우의 기준 시각을 mimic 응답 헤더의
 * `state_as_of` 에서 가져오고 마감은 자기 시계로 보므로, EPOCH 에서 시작하면 둘이 어긋난다. 그 뒤 런처는 [advanceTo] 로
 * 실제 시각을 따라잡는다. [now]·시계 밀기·셀 대역의 훑기·[teach]·장애 주입은 모두 `MimicServer.exclusive` 아래에서 돈다. 가상
 * 시계는 다른 스레드에서 읽을 때 최신 값이 보인다는 보장이 없으므로 잠금이 그 가시성도 맡는다.
 */
class Site private constructor(
    private val registry: ConfigurableApplicationContext,
    private val mimic: MimicCli.Started,
    private val cell: SiteCell,
    private val runner: AutoCloseable,
    /** mimic 의 발행을 registry 로 보내는 연계. 생존 보고와 태스크 관측은 이 연계의 적재 큐를 거쳐 따로 도는 스레드가 보낸다. */
    private val link: RegistryLink,
    val registryUrl: String,
    val robotIds: Set<String>,
) : AutoCloseable {

    /** 기체들이 함께 보는 가상 시계. `MimicCli` 가 시계 하나를 만들어 모든 기체에 넘긴다. */
    private val clock = requireNotNull(mimic.instance(robotIds.first())) { "기체가 없는 현장이다" }.clock

    /** mimic gRPC 가 열린 포트. 시험은 0 을 주고 여기서 읽는다(S3a 스펙 §6.2). */
    val mimicPort: Int get() = mimic.server.port

    /** 셀 대역 `GET /cell` 과 장애 주입 `POST /faults` 가 열린 루프백 포트(S3a 스펙 §6.3, S4a 스펙 §5). */
    val cellPort: Int get() = cell.port

    /** 셀 대역의 지금 스냅숏. `GET /cell` 이 내는 것과 같다. */
    val cellSnapshot: CellSnapshot get() = cell.snapshot

    /** mimic 의 가상 시각. */
    fun now(): Instant = mimic.server.exclusive { clock.now() }

    /**
     * mimic 의 가상 시계를 민다. 상태 발행이 이 시계로 정해지고, 상태 발행이 곧 생존 보고다. 민 직후 같은 잠금 아래에서
     * 셀 대역이 태스크를 훑는다.
     *
     * 시험용으로 남긴다. 이것으로 밀면 가상 시각이 실제 시각보다 앞서며, 그때 실행 호스트도 이 현장의 시계를 써야 한다.
     *
     * 민 뒤에는 잠금 밖에서 적재 큐가 빌 때까지 기다린다. picasso 의 `RegistryLink.http` 가 생존 보고를 큐로 보내므로
     * (picasso §15.210), 기다리지 않으면 돌아온 직후 registry 에 그 보고가 아직 없다.
     */
    fun advance(by: Duration) {
        mimic.server.exclusive { advanceLocked(by) }
        drain()
    }

    /** [target] 이 가상 시각보다 뒤일 때만 그 차이만큼 민다. 같거나 앞이면 아무것도 하지 않는다(되감지 않는다). */
    fun advanceTo(target: Instant) {
        mimic.server.exclusive {
            val now = clock.now()
            if (target.isAfter(now)) advanceLocked(Duration.between(now, target))
        }
        drain()
    }

    private fun advanceLocked(by: Duration) {
        mimic.server.advance(by)
        cell.scan()
    }

    /** 적재 큐가 빌 때까지 기다린다. 제한 안에 못 비우면 그대로 돌아온다 — 큐는 이어서 민다. */
    private fun drain() {
        link.drain(DRAIN_TIMEOUT)
    }

    /**
     * 현장 안전 계통이 안전 신호의 값을 바꾼 것을 흉내 낸다(셀 문 열림·닫힘, [SiteCell.actuateSafety]). 소프트웨어 쓰기 길은 여전히 안전
     * 신호를 거부한다. 통합 시험이 작업 허가(ADR 52)를 볼 때 쓴다.
     */
    fun actuateSafety(name: String, value: String): SignalWrite = cell.actuateSafety(name, value)

    /** registry 만 멈춘다. 운영 서비스가 «모름» 을 보이는지 볼 때 쓴다(스펙 §3 S1a). 실행기는 집기 실패를 로그에 남기며 폴링을 이어 간다. */
    fun stopRegistry() = registry.close()

    /**
     * 현장에서 이 기체에 명칭을 다시 티칭한다(P2·S1d 스펙 §7). 기체가 아는 명칭은 생존 보고마다 실리므로 다음 [advance] 의
     * 상태 발행부터 registry 에 닿는다.
     */
    fun teach(robotId: String, siteNames: List<String>) {
        val instance = requireNotNull(mimic.instance(robotId)) { "이 현장에 없는 기체다: $robotId" }
        // 읽는 쪽이 advance 안의 상태 발행이므로 같은 잠금 아래에서 쓴다.
        mimic.server.exclusive { instance.knownSiteNames = siteNames }
    }

    /**
     * 이 기체가 호스팅하는 태스크마다 갱신 로그의 상태 이름(적은 순서, S4b 스펙 T5). 통합 시험이 재기동 앞뒤로 새 명령이 나가지
     * 않았는지 대조할 때 쓴다. mimic 은 같은 태스크 id·리비전의 `StartTask` 에 기존 태스크를 돌려주므로, 새 명령이 나가면 새
     * 태스크 id 나 둘째 `ACCEPTED` 로 보인다. mimic 은 RPC 마다 접수한 태스크를 집어 들어(`ACCEPTED` → `RUNNING`) 시계를 밀지
     * 않아도 로그가 이어질 수 있다. 읽기만 하며 엔진 잠금 아래에서 돈다.
     */
    fun taskHistory(robotId: String): Map<String, List<String>> {
        val instance = requireNotNull(mimic.instance(robotId)) { "이 현장에 없는 기체다: $robotId" }
        return mimic.server.exclusive { instance.tasks.all.associate { task -> task.taskId to task.log.from(0).map { it.state.name } } }
    }

    override fun close() {
        try {
            runner.close()
        } finally {
            try {
                cell.close()
            } finally {
                try {
                    // 서버를 내리고 적재 큐를 비운다(MimicCli.Started.close).
                    mimic.close()
                } finally {
                    if (registry.isActive) registry.close()
                }
            }
        }
    }

    companion object {

        fun start(config: SiteConfig): Site {
            // ① registry 스키마는 런처가 올린다. ops 스키마는 운영 서비스의 몫이다(스펙 §6 ①, §7.1).
            migrateRegistrySchema(config.db)

            // ② registry 를 클래스패스로 띄운다. 설정은 실행 인자로만 넣는다(스펙 §10).
            val registry = SpringApplicationBuilder(RegistryApplication::class.java).run(
                "--server.port=${config.registryPort}",
                // 토큰이 공개 저장소의 .env 에 있으므로 이 기계 밖에 열지 않는다. mimic 의 gRPC 는 picasso 가
                // 주소를 정하므로(모든 인터페이스) 여기서 못 바꾼다.
                "--server.address=127.0.0.1",
                "--picasso.db.url=${config.db.url}",
                "--picasso.db.user=${config.db.user}",
                "--picasso.db.password=${config.db.password}",
                "--picasso.operator.token=${config.operatorToken}",
                "--picasso.ingest.token=${config.ingestToken}",
                "--picasso.profile.schema=${config.schema}",
            )
            val port = (registry as WebServerApplicationContext).webServer.port
            val registryUrl = "http://127.0.0.1:$port"

            // ③ mimic 을 가상 시계로 띄운다. 실시간 시계는 advance 에서 오류를 낸다(스펙 §6 ③).
            // ④ 사이트 id 는 .env 의 SITE_ID 하나에서 온다.
            val err = StringBuilder()
            // 폴백 디렉터리는 두지 않는다. 생존 보고는 폴백이 없고, S1 은 태스크 관측을 쓰지 않는다.
            val link = RegistryLink.http(registryUrl, config.ingestToken, config.siteId, null)
            val mimic = try {
                MimicCli().start(
                    robots = config.roster.associate { it.robotId to config.profile(it) },
                    schema = config.schema,
                    // 고정하면 인증 없는 기체 제어 API 표면의 위치가 정해진다(S3a 스펙 §6.2, §12). 실행 호스트가 붙으려면 알아야 한다.
                    port = config.mimicPort,
                    virtual = true,
                    seed = 0L,
                    err = err,
                    link = link,
                    broker = null,
                    site = config.siteId,
                )
            } catch (e: Exception) {
                link.close()
                registry.close()
                throw e
            }
            if (mimic == null) {
                link.close()
                registry.close()
                error("mimic 기동 거부: $err")
            }
            // ⑤ 현장에서 티칭한 명칭을 기체에 넣는다. 명칭은 프로파일이 아니라 현장의 것이다(ADR 35).
            config.roster.forEach { mimic.instance(it.robotId)?.knownSiteNames = it.siteNames }

            // ⑥ 셀 대역. 실행 호스트가 루프백 HTTP 로 읽는다(S3a 스펙 §6.3).
            val cell = try {
                SiteCell(mimic, config.cell, config.cellPort)
            } catch (e: Exception) {
                mimic.close()
                registry.close()
                throw e
            }

            // ⑦ 리비전 시험 실행기. 적재 토큰을 가진 것이 이 프로세스뿐이다(P2·S1d 스펙 §7). 시험에 쓰는 mimic 은
            // 실행기가 시험마다 따로 띄우므로 현장 기체의 보고와 상태를 바꾸지 않는다.
            val runner = try {
                RevisionTestRunner(
                    HttpTestDesk(registryUrl, config.ingestToken),
                    RevisionSuites(config.schema),
                    RUNNER_NAME,
                ).start(RUNNER_INTERVAL)
            } catch (e: Exception) {
                cell.close()
                mimic.close()
                registry.close()
                throw e
            }
            // ⑧ 가상 시계를 실제 시각까지 한 번 민다(S3a 스펙 §6.1). 한 번에 크게 밀어도 된다(MimicServer.advance).
            val site = Site(registry, mimic, cell, runner, link, registryUrl, mimic.robotIds)
            try {
                site.advanceTo(Instant.now())
            } catch (e: Exception) {
                site.close()
                throw e
            }
            return site
        }

        /** 실행기 이름. 시험 결과의 실행 주체로 화면에 보인다. */
        const val RUNNER_NAME = "site-runner"

        /** [advance] 뒤 적재 큐를 기다리는 최대 시간(실제 시간). 적재 요청 하나의 제한(연결 2초 + 요청 3초)보다 길게 둔다. */
        val DRAIN_TIMEOUT: Duration = Duration.ofSeconds(10)

        /** 실행기의 폴링 간격(실제 시간). */
        val RUNNER_INTERVAL: Duration = Duration.ofSeconds(1)

        /** registry jar 의 `classpath:db/migration` 을 기본 스키마(public)에 올린다. registry 시험 픽스처와 같은 설정이다. */
        fun migrateRegistrySchema(db: DbConfig): Int =
            Flyway.configure()
                .dataSource(db.url, db.user, db.password)
                .load()
                .migrate()
                .migrationsExecuted
    }
}
