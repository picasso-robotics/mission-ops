package dev.picasso.ops.host

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.client.PicassoClient
import dev.picasso.host.FileEntitlements
import dev.picasso.middleware.AmrFleetPort
import dev.picasso.middleware.ClientRobotPort
import dev.picasso.middleware.Entitlements
import dev.picasso.middleware.IssuingRobotPort
import dev.picasso.middleware.PrefetchingRobotPort
import dev.picasso.middleware.Retention
import dev.picasso.middleware.RobotPort
import dev.picasso.ops.host.cell.CellBandClient
import dev.picasso.ops.host.fleet.HostFleetPort
import dev.picasso.ops.host.fleet.HttpFleetPort
import dev.picasso.ops.host.incident.ApprovalWindow
import dev.picasso.ops.host.lease.GatedFleetPort
import dev.picasso.ops.host.lease.GatedRobotPort
import dev.picasso.ops.host.lease.HostExit
import dev.picasso.ops.host.lease.HostLease
import dev.picasso.ops.host.lease.HostLeadership
import dev.picasso.ops.host.lease.LeaseConnections
import dev.picasso.ops.host.lease.LeaseTiming
import dev.picasso.ops.host.lease.SendAuthority
import dev.picasso.ops.host.mission.MissionVersions
import dev.picasso.ops.host.mission.MockRunner
import dev.picasso.ops.host.mission.StoredMissionCatalog
import dev.picasso.ops.host.permit.CellWorkPermits
import dev.picasso.ops.host.permit.WorkPermitMap
import dev.picasso.ops.host.retire.RegistryRetirements
import dev.picasso.ops.host.store.AuthorityGuard
import dev.picasso.ops.host.store.HostSchema
import dev.picasso.ops.host.store.HostRecords
import dev.picasso.ops.host.store.HostSchemaMigrated
import dev.picasso.ops.host.store.MissionStore
import dev.picasso.ops.host.timings.SiteTimingsReader
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import java.time.Duration
import javax.sql.DataSource

/**
 * 실행 호스트(S3a 스펙 §7, S3b 스펙 §6). 임무 버전을 같은 Postgres 의 자기 스키마 `mission` 에 둔다(S3b T3).
 *
 * Flyway 자동설정은 이름으로 끈다. 마이그레이션은 [HostSchema] 가 맡는다. 통합 시험은 registry·운영 서비스와 한 JVM 에
 * 호스트를 띄우고, 그때 자동설정이 켜지면 registry 마이그레이션 위치를 집어 온다.
 */
@SpringBootApplication(excludeName = ["org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"])
open class MissionHostApplication {

    /** 기본은 실제 시각이다. 시험은 [builder] 로 다른 시계를 먼저 넣고, 그러면 이 빈은 만들어지지 않는다. */
    @Bean
    @ConditionalOnMissingBean(HostClock::class)
    open fun hostClock(): HostClock = HostClock.SYSTEM

    /** 기본은 프로세스 종료다(리스를 잃으면 감독자가 새 인스턴스로 다시 띄운다). 시험은 [builder] 로 기록만 하는 훅을 넣는다. */
    @Bean
    @ConditionalOnMissingBean(HostExit::class)
    open fun hostExit(): HostExit = HostExit.PROCESS

    /** 리스·심장박동 시간 폭(`host.lease.*`, [LeaseTiming]). 규칙에 어긋나면 기동이 멈춘다. */
    @Bean
    open fun leaseTiming(
        @Value("\${host.lease.period}") period: Duration,
        @Value("\${host.lease.expiry}") expiry: Duration,
        @Value("\${host.lease.margin}") margin: Duration,
        @Value("\${host.lease.idle-in-transaction-timeout}") idleInTransaction: Duration,
        @Value("\${host.lease.statement-timeout}") statementTimeout: Duration,
    ): LeaseTiming = LeaseTiming(period, expiry, margin, idleInTransaction, statementTimeout)

    /**
     * 리더 상태기계와 리스(HA 설계 기록 §4·§7). 인스턴스 id(`mw-<UUID>`)는 프로세스마다 새 값이고 미들웨어·일지·리스 보유자가 같은 값을 쓴다.
     * 리스는 공용 커넥션 풀과 분리한 전용 연결로 획득·갱신한다(같은 DB 주소·계정).
     */
    @Bean
    open fun hostLeadership(
        timing: LeaseTiming,
        exit: HostExit,
        @Value("\${spring.datasource.url}") url: String,
        @Value("\${spring.datasource.username}") username: String,
        @Value("\${spring.datasource.password}") password: String,
    ): HostLeadership = HostLeadership(
        HostLease("mw-${java.util.UUID.randomUUID()}", timing, LeaseConnections.direct(url, username, password, timing)), timing, exit,
    )

    /** DB 쓰기 펜싱(V19). 기록 저장소 둘의 쓰기가 이것을 지난다. 리스 불일치를 보면 리더를 잃음으로 넘긴다. */
    @Bean
    open fun authorityGuard(jdbc: JdbcClient, dataSource: DataSource, leadership: HostLeadership): AuthorityGuard = AuthorityGuard(
        jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)), leadership::claim, leadership::lost,
    )

    /** 같은 기계의 현장 mimic gRPC 에 평문으로 붙는다(S3a 스펙 §7.2). */
    @Bean(destroyMethod = "shutdownNow")
    open fun mimicChannel(@Value("\${host.mimic.port}") port: Int): ManagedChannel {
        require(port in 1..65535) { "mimic gRPC 포트(host.mimic.port, MIMIC_GRPC_PORT)가 틀리다: $port" }
        return ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
    }

    /**
     * 기체 포트. gRPC 포트([ClientRobotPort])를 picasso 의 두 데코레이터로 감싼다(picasso §15.237·§15.267, 한계 §15.279).
     * [PrefetchingRobotPort] 는 기체마다 스냅숏·재생·선언 읽기를 자기 스레드에서 돌려 pump 가 호스트 잠금 아래에서 기체 답을 기다리지
     * 않게 하고, [IssuingRobotPort] 는 출발·취소 명령을 잠금 밖으로 보낸다. 미리 읽은 값은 받은 지 `maxAge`(기본 2초) 안에서만 쓴다.
     * 두 데코레이터의 시계는 미들웨어와 같은 [HostClock] 이다.
     *
     * 출발·취소는 [IssuingRobotPort] 의 송신 스레드에서 gRPC 호출 바로 앞에 리더를 확인한다([GatedRobotPort], 송신 직전 로컬 권위 확인).
     *
     * 시험은 [builder] 로 다른 포트를 먼저 넣고, 그러면 이 빈은 만들어지지 않는다.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(RobotPort::class)
    open fun robotPort(channel: ManagedChannel, clock: HostClock, leadership: HostLeadership): RobotPort =
        HostRobotPort(GatedRobotPort(ClientRobotPort(PicassoClient(channel, CLIENT_ID)), leadership), clock)

    /**
     * 플릿 포트(`host.fleet.url`). 비우면 `AmrFleetPort.None` 이다 — 플릿 운반 단위가 든 계획을 받지 않는다(`FLEET_PORT_ABSENT`). 값이 있으면 현장
     * 플릿 세계 면(`/fleet`, mission-ops 안의 약속)을 부르는 [HttpFleetPort] 를 picasso 의 두 데코레이터로 감싼 [HostFleetPort] 다 — 기체
     * 포트([robotPort])와 대칭이고 기한 예산(미리 읽은 값 `maxAge` 2초, 요청 제한 1초)도 같은 결이다. `mimic` 같은 스위치 값은 없다: 주소가 곧
     * 선언이다. 주소가 틀린 모양(http(s) 아님)이면 기동이 멈춘다. 현장이 지금 안 닿는 것은 기동을 막지 않는다(pump 가 다음 라운드에 다시 본다).
     *
     * 시험은 [builder] 로 다른 포트를 먼저 넣고, 그러면 이 빈은 만들어지지 않는다. 닫는 것은 Spring 이 추론한 `close` 다([HostFleetPort]).
     */
    @Bean
    @ConditionalOnMissingBean(AmrFleetPort::class)
    open fun fleetPort(@Value("\${host.fleet.url}") url: String, clock: HostClock, leadership: HostLeadership): AmrFleetPort =
        fleetPortOf(url, clock, leadership)

    /**
     * 미들웨어 보존 상한([MissionHost.RETENTION], 놓은 작업 지시 표시는 `EVICT` 선언). 운영 설정으로 열지 않는다. 시험은 [builder] 로 작은 상한을
     * 먼저 넣고, 그러면 이 빈은 만들어지지 않는다.
     */
    @Bean
    @ConditionalOnMissingBean(Retention::class)
    open fun hostRetention(): Retention = MissionHost.RETENTION

    /** 셀 대역. 신호 쓰기와 결함 주입 중계는 보내기 직전에 리더를 확인한다. */
    @Bean
    open fun cellBand(@Value("\${host.cell.url}") url: String, leadership: HostLeadership): CellBandClient =
        CellBandClient(url, authority = leadership)

    /**
     * `mission` 마이그레이션. 공용 풀이 아니라 같은 DB 주소·계정의 따로 연 연결로 돈다([migrationDataSource]) — 풀 연결에는 리스용 세션 제한
     * (`statement_timeout`·`idle_in_transaction_session_timeout`, [leaseSessionSettings])이 걸려 있어 데이터가 쌓인 뒤의 색인·백필 마이그레이션이
     * 그 제한에 끊길 수 있다.
     */
    @Bean
    open fun hostSchemaMigrated(
        @Value("\${spring.datasource.url}") url: String,
        @Value("\${spring.datasource.username}") username: String,
        @Value("\${spring.datasource.password}") password: String,
    ): HostSchemaMigrated = HostSchemaMigrated(HostSchema.flyway(migrationDataSource(url, username, password)).migrate().migrationsExecuted)

    /** [migrated] 는 쓰지 않는다. 받는 것만으로 mission 마이그레이션 뒤에 이 빈이 만들어진다. */
    @Bean
    open fun missionStore(
        jdbc: JdbcClient,
        guard: AuthorityGuard,
        @Suppress("UNUSED_PARAMETER") migrated: HostSchemaMigrated,
    ): MissionStore = MissionStore(jdbc, guard)

    /**
     * 기동 때 DB 의 활성 버전으로 카탈로그를 세운다(S3b 스펙 §6.2, T1). 다시 검증하지 않는다. 저장된 정의를 파싱하지 못하면
     * 예외가 나가 기동이 멈춘다.
     */
    @Bean
    open fun missionCatalog(store: MissionStore): StoredMissionCatalog =
        StoredMissionCatalog().apply { restore(store.activeVersions()) }

    /**
     * 현장 시간값 읽기 주기(S3c 스펙 §7.1). 기동 안에서 운영 서비스의 뷰를 한 번 동기로 읽고, 실패하면 미적용으로 뜬 뒤
     * [interval](`host.site-timings.read-interval`, 기본 1초)마다 다시 읽는다. ops 스키마가 아직 없어도 기동은 멈추지 않는다.
     */
    @Bean(destroyMethod = "close")
    open fun siteTimingsReader(
        jdbc: JdbcClient,
        clock: HostClock,
        @Value("\${host.site-timings.read-interval}") interval: Duration,
    ): SiteTimingsReader = SiteTimingsReader(jdbc, clock).start(interval)

    /**
     * 기체 퇴역 읽기 주기(picasso §15.249). [url](`host.registry.url`)이 비어 있으면 읽지 않고 미들웨어에 포트를 붙이지 않는다. 있으면 기동
     * 안에서 registry 의 진단 읽기를 한 번 동기로 읽고 [interval](`host.retirement.read-interval`, 기본 2초)마다 다시 읽는다. registry 가
     * 안 닿아도 기동은 멈추지 않는다.
     */
    @Bean(destroyMethod = "close")
    open fun registryRetirements(
        clock: HostClock,
        @Value("\${host.registry.url}") url: String,
        @Value("\${host.retirement.read-interval}") interval: Duration,
    ): RegistryRetirements = RegistryRetirements(url, clock).start(interval)

    /** 실행 일지, 송신 기록, 인시던트 사본(S4b 스펙 §6.1). pump 뒤 기록은 이 데이터 소스의 트랜잭션 하나로 묶는다(리스 가드 안). */
    @Bean
    open fun hostRecords(
        jdbc: JdbcClient,
        guard: AuthorityGuard,
        json: ObjectMapper,
        @Suppress("UNUSED_PARAMETER") migrated: HostSchemaMigrated,
    ): HostRecords = HostRecords(jdbc, guard, json)

    /**
     * 빈을 만들 때 리스를 얻어 보고, 얻으면 일지로 실행을 복원하고 pump 를 켠다(S4b 스펙 T3). 웹 서버는 그 뒤에 열린다. 못 얻으면 대기로 뜬다
     * ([HostLeadership], 심장박동과 획득 시도만).
     *
     * [permitMapping] 은 현장이 선언한 작업 허가 대응 파일의 경로다(`host.work-permit.mapping`, ADR 52). 비어 있으면 선언하지 않은 현장이고
     * 미들웨어에 허가 포트를 붙이지 않는다. 경로가 있는데 파일을 못 읽거나 모양이 틀리면 기동이 멈춘다 — 선언한 현장을 조용히 선언 안 한
     * 현장으로 띄우지 않는다.
     *
     * [leadership] 은 리더 상태기계다([hostLeadership]). 인스턴스 심장박동도 그 시간 폭(`host.lease.period`·`expiry`)으로 돈다.
     *
     * [revisableRetention] 은 `FAILED`·`UNVERIFIED` 로 정착한 실행을 picasso 메모리에서 놓기 전에 두는 폭이다(`host.settled.revisable-retention`,
     * [MissionHost.DEFAULT_REVISABLE_RETENTION]). 음수면 기동이 멈춘다.
     *
     * [retention] 은 미들웨어 보존 상한이다([hostRetention], 기본 [MissionHost.RETENTION]).
     *
     * [fleet] 는 플릿 포트다([fleetPort], `host.fleet.url`). 없으면(`AmrFleetPort.None`) 플릿 계획 차단이 그대로다.
     *
     * [retirements] 는 registry 주소를 설정했을 때만 미들웨어에 넘긴다([RegistryRetirements.declared]).
     *
     * [entitlementsPath]·[exportDir] 는 인시던트 고리다(`host.incident-loop.entitlements`·`.export`, picasso 한계 §15.176). 비우면 선언 없음,
     * 내보내기 없음이다. 선언 파일 경로가 있는데 못 읽으면 기동이 멈춘다(picasso `FileEntitlements` 는 빈 목록으로 접지 않는다).
     */
    @Bean(destroyMethod = "close")
    open fun missionHost(
        robots: RobotPort,
        cellBand: CellBandClient,
        clock: HostClock,
        catalog: StoredMissionCatalog,
        timings: SiteTimingsReader,
        records: HostRecords,
        store: MissionStore,
        json: ObjectMapper,
        retirements: RegistryRetirements,
        retention: Retention,
        fleet: AmrFleetPort,
        @Value("\${host.work-permit.mapping}") permitMapping: String,
        leadership: HostLeadership,
        @Value("\${host.settled.revisable-retention}") revisableRetention: Duration,
        @Value("\${host.incident-loop.entitlements}") entitlementsPath: String,
        @Value("\${host.incident-loop.export}") exportDir: String,
    ): MissionHost = MissionHost(
        robots, cellBand, clock, catalog, timings, records, store, json, workPermits(permitMapping, clock),
        leadership, revisableRetention,
        retirements.takeIf { it.declared },
        entitlements(entitlementsPath), path(exportDir), retention, fleet,
    ).start()

    /**
     * 승인 호스트(picasso `host-kit`, `POST /approvals`). [port](`host.incident-loop.approval-port`)가 비면 열지 않는다. 열려면 자격 선언
     * 파일(`host.incident-loop.entitlements`)도 있어야 한다 — 없으면 모든 시도가 `NOT_DECLARED` 라 여는 뜻이 없으므로 기동을 멈춘다.
     * 루프백에만 붙고 신원을 인증하지 않는다(picasso §15.3).
     */
    @Bean(destroyMethod = "close")
    open fun approvalWindow(
        host: MissionHost,
        @Value("\${host.incident-loop.approval-port}") port: String,
        @Value("\${host.incident-loop.entitlements}") entitlementsPath: String,
    ): ApprovalWindow {
        val chosen = port.trim().takeIf { it.isNotEmpty() } ?: return ApprovalWindow.CLOSED
        require(entitlementsPath.isNotBlank()) { "승인 호스트를 열려면 자격 선언 파일(host.incident-loop.entitlements)이 있어야 한다" }
        val number = requireNotNull(chosen.toIntOrNull()) { "승인 호스트 포트(host.incident-loop.approval-port)가 숫자가 아니다: $chosen" }
        return ApprovalWindow.open(host, number)
    }

    /**
     * 모의 실행기(S3b 스펙 §6.4). 프로파일과 스키마 경로는 작업 디렉터리 기준으로 푼다. `:mission-host:run` 은 저장소 루트에서
     * 돌고(site 의 `run` 과 같음), 시험은 절대 경로를 실행 인자로 넘긴다. 파일이 없으면 기동에서 멈춘다.
     */
    @Bean
    open fun mockRunner(
        @Value("\${host.mock-run.profile}") profile: String,
        @Value("\${host.mock-run.schema}") schema: String,
        @Value("\${host.mock-run.virtual-limit}") virtualLimit: Duration,
    ): MockRunner = MockRunner(Path.of(profile).toAbsolutePath().normalize(), Path.of(schema).toAbsolutePath().normalize(), virtualLimit)

    @Bean
    open fun missionVersions(
        host: MissionHost,
        store: MissionStore,
        catalog: StoredMissionCatalog,
        runner: MockRunner,
        clock: HostClock,
        json: ObjectMapper,
    ): MissionVersions = MissionVersions(host, store, catalog, runner, clock, json)

    companion object {
        /**
         * 공용 풀(Hikari)의 연결마다 세션 설정을 연결을 열 때 건다(JDBC `options`): `idle_in_transaction_session_timeout`·`statement_timeout`
         * ([LeaseTiming]). 쓰기 가드가 리스 보유자 행을 잠그기 전부터 걸려 있어 잠근 직후 멎은 세션도 DB 가 끊는다. 값은 `host.lease.*` 에서 읽는다.
         */
        @Bean
        @JvmStatic
        fun leaseSessionSettings(environment: org.springframework.core.env.Environment): org.springframework.beans.factory.config.BeanPostProcessor =
            object : org.springframework.beans.factory.config.BeanPostProcessor {
                override fun postProcessBeforeInitialization(bean: Any, beanName: String): Any {
                    if (bean is com.zaxxer.hikari.HikariDataSource) {
                        fun millis(key: String) = Duration.parse(environment.getRequiredProperty(key)).toMillis()
                        val options = "-c idle_in_transaction_session_timeout=${millis("host.lease.idle-in-transaction-timeout")}" +
                            " -c statement_timeout=${millis("host.lease.statement-timeout")}"
                        bean.addDataSourceProperty("options", options)
                    }
                    return bean
                }
            }

        /** 마이그레이션용 연결(풀 없음, 세션 제한 없음). */
        fun migrationDataSource(
            url: String,
            username: String,
            password: String,
        ): DataSource =
            org.springframework.jdbc.datasource.DriverManagerDataSource(url, username, password)

        /** mimic 에 싣는 클라이언트 id. */
        const val CLIENT_ID = "mission-host"

        /**
         * 플릿 주소에서 포트를 짓는다. 비어 있으면 [AmrFleetPort.None] 이다(스위치 값 없음 — 주소가 곧 선언). 주소가 있으면 기동 탐침
         * ([HttpFleetPort.probe])을 한 번 한다 — 4xx 등(예: 다른 서버, `/fleet` 면이 없는 현장의 404·405)이면 오설정이라 기동을 멈춘다. 안 닿거나 5xx 면
         * 일시 장애로 보아 경고만 남기고 뜬다(현장이 늦게 뜨거나 잠시 아플 수 있다). 맡기기·취소는 송신 스레드에서 [authority] 를 확인한다
         * ([GatedFleetPort]).
         */
        fun fleetPortOf(url: String, clock: HostClock, authority: SendAuthority = SendAuthority.ALWAYS): AmrFleetPort {
            val port = url.trim().takeIf { it.isNotEmpty() }?.let { HttpFleetPort(it) } ?: return AmrFleetPort.None
            when (val status = port.probe()) {
                // 안 닿음과 5xx 는 일시 장애일 수 있다(현장이 늦게 뜨거나 잠시 아픔) — 경고만 하고 뜬다. pump 가 다음 라운드에 다시 본다.
                null -> org.slf4j.LoggerFactory.getLogger(MissionHostApplication::class.java).warn("플릿 세계 면이 아직 안 닿는다(기동은 계속): {}", port.url)
                200 -> Unit
                in 500..599 -> org.slf4j.LoggerFactory.getLogger(MissionHostApplication::class.java).warn("플릿 세계 면이 HTTP {} 로 답했다(일시 장애로 보고 기동은 계속): {}", status, port.url)
                // 그 밖(4xx·3xx 등)은 오설정이 확실하다 — 그 주소에 플릿 세계 면이 없다.
                else -> throw IllegalArgumentException("플릿 주소(host.fleet.url)가 현장 플릿 세계 면이 아니다: GET ${port.url}/fleet/world → HTTP $status")
            }
            return HostFleetPort(GatedFleetPort(port, authority), clock, port.url)
        }

        /** 작업 허가 대응 경로에서 포트를 짓는다. 비어 있으면 `null`(선언하지 않은 현장)이다. 경로는 작업 디렉터리 기준이다. */
        fun workPermits(mapping: String, clock: HostClock): CellWorkPermits? =
            mapping.trim().takeIf { it.isNotEmpty() }?.let { CellWorkPermits(WorkPermitMap.read(Path.of(it).toAbsolutePath().normalize()), clock.now()) }

        /** 자격 선언 파일에서 선언을 읽는다. 비어 있으면 선언 없음이다. 경로는 작업 디렉터리 기준이다. */
        fun entitlements(path: String): Entitlements = path(path)?.let { FileEntitlements.read(it) } ?: Entitlements.None

        /** 비어 있으면 `null`, 아니면 작업 디렉터리 기준 절대 경로. */
        fun path(value: String): Path? = value.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it).toAbsolutePath().normalize() }

        /**
         * 설정 파일 이름을 `mission-host` 로 둔다. 같은 JVM 의 registry `application.properties`·운영 서비스 설정과 가리지 않게.
         *
         * @param clock 주면 기본 시계 대신 이것을 쓴다(통합 시험이 현장 시계를 넣는다).
         * @param robots 주면 기본 하위 포트 대신 이것을 쓴다(호스트 시험이 기체 스냅숏을 막는 포트를 넣는다).
         * @param retention 주면 [MissionHost.RETENTION] 대신 이것을 미들웨어 보존 상한으로 쓴다(호스트 시험이 작은 놓은 작업 지시 표시 상한을 넣는다).
         * @param fleet 주면 `host.fleet.url` 대신 이것을 플릿 포트로 쓴다(호스트 시험이 프로세스 안의 플릿 미믹을 넣는다). 닫지 않는다.
         * @param exit 주면 리스를 잃었을 때 프로세스를 끝내는 대신 이것을 부른다(시험이 같은 JVM 에서 잃음을 본다).
         */
        fun builder(
            clock: HostClock? = null, robots: RobotPort? = null, retention: Retention? = null, fleet: AmrFleetPort? = null, exit: HostExit? = null,
        ): SpringApplicationBuilder {
            val builder = SpringApplicationBuilder(MissionHostApplication::class.java)
                .properties("spring.config.name=mission-host")
            if (clock != null || robots != null || retention != null || fleet != null || exit != null) {
                builder.initializers(
                    ApplicationContextInitializer<ConfigurableApplicationContext> {
                        clock?.let { c -> it.beanFactory.registerSingleton("hostClock", c) }
                        robots?.let { r -> it.beanFactory.registerSingleton("testRobotPort", r) }
                        retention?.let { r -> it.beanFactory.registerSingleton("hostRetention", r) }
                        fleet?.let { f -> it.beanFactory.registerSingleton("testFleetPort", f) }
                        exit?.let { e -> it.beanFactory.registerSingleton("hostExit", e) }
                    },
                )
            }
            return builder
        }
    }
}

fun main(args: Array<String>) {
    MissionHostApplication.builder().run(*args)
}
