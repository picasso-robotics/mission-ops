package dev.picasso.ops.service

import dev.picasso.ops.service.operations.ProfileOperations
import dev.picasso.ops.service.profiles.ProfileListService
import dev.picasso.ops.service.adapters.AdapterListService
import dev.picasso.ops.service.cell.CellSignalOperations
import dev.picasso.ops.service.host.HostClient
import dev.picasso.ops.service.incidents.FaultOperations
import dev.picasso.ops.service.incidents.HoldResolutions
import dev.picasso.ops.service.joborders.JobOrderEligibility
import dev.picasso.ops.service.joborders.JobOrderOperations
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.missions.MissionOperations
import dev.picasso.ops.service.operations.AdapterOperations
import dev.picasso.ops.service.operations.RobotOperations
import dev.picasso.ops.service.registry.RegistryClient
import dev.picasso.ops.service.robots.RobotListService
import dev.picasso.ops.service.settings.SiteSettingsOperations
import dev.picasso.ops.service.settings.SiteSettingsStore
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.ops.service.store.OpsSchemaMigrated
import dev.picasso.ops.service.web.SiteId
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import javax.sql.DataSource

/** 운영 서비스(스펙 §7). Flyway 자동설정을 끄는 이유는 [OpsSchema] 에 있다. */
@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication(exclude = [FlywayAutoConfiguration::class])
open class OpsApplication {

    @Bean
    open fun clock(): Clock = Clock.systemUTC()

    @Bean
    open fun opsSchemaMigrated(dataSource: DataSource): OpsSchemaMigrated =
        OpsSchemaMigrated(OpsSchema.flyway(dataSource).migrate().migrationsExecuted)

    @Bean
    open fun siteId(@Value("\${ops.site-id}") value: String): SiteId {
        require(value.isNotBlank()) { "ops.site-id(SITE_ID)가 비었다" }
        return SiteId(value)
    }

    /** 주소 형식은 [RegistryClient.checkBaseUrl] 이 기동에서 본다. */
    @Bean
    open fun registryClient(
        @Value("\${ops.registry.url}") url: String,
        @Value("\${ops.registry.operator-token}") token: String,
    ): RegistryClient {
        // registry 는 빈 토큰을 전부 401 로 다룬다. 설정 실수를 401 이 아니라 기동에서 드러낸다.
        require(token.isNotBlank()) { "운영자 토큰(ops.registry.operator-token)이 비었다" }
        return RegistryClient(url, token)
    }

    /** 연결 칸의 기준 시간은 현장 설정 버전에서 읽는다(S2 스펙 §6.4). S1 에서는 설정 파일 값이었다. */
    @Bean
    open fun robotList(
        registry: RegistryClient,
        siteId: SiteId,
        clock: Clock,
        settings: SiteSettingsStore,
    ): RobotListService = RobotListService(registry, registry, siteId.value, clock, settings, commissioning = registry)

    @Bean
    open fun adapterList(registry: RegistryClient, siteId: SiteId, clock: Clock): AdapterListService =
        AdapterListService(registry, siteId.value, clock)

    @Bean
    open fun robotOperations(
        registry: RegistryClient,
        log: OperationLog,
        siteId: SiteId,
        clock: Clock,
    ): RobotOperations = RobotOperations(registry, registry, log, siteId.value, clock)

    @Bean
    open fun adapterOperations(
        registry: RegistryClient,
        log: OperationLog,
        siteId: SiteId,
        clock: Clock,
    ): AdapterOperations = AdapterOperations(registry, registry, log, siteId.value, clock)

    @Bean
    open fun profileList(registry: RegistryClient, clock: Clock): ProfileListService = ProfileListService(registry, clock)

    @Bean
    open fun profileOperations(
        registry: RegistryClient,
        log: OperationLog,
        siteId: SiteId,
        clock: Clock,
    ): ProfileOperations = ProfileOperations(registry, registry, registry, registry, log, siteId.value, clock)

    /** [migrated] 는 쓰지 않는다. 받는 것만으로 ops 마이그레이션 뒤에 이 빈이 만들어진다. */
    @Bean
    open fun siteSettings(
        jdbc: JdbcClient,
        @Suppress("UNUSED_PARAMETER") migrated: OpsSchemaMigrated,
    ): SiteSettingsStore = SiteSettingsStore(jdbc)

    /** 새 버전 행과 조작 기록 행을 한 트랜잭션에 넣는다(S2 스펙 §6.2). */
    @Bean
    open fun siteSettingsOperations(
        settings: SiteSettingsStore,
        log: OperationLog,
        dataSource: DataSource,
        clock: Clock,
    ): SiteSettingsOperations =
        SiteSettingsOperations(settings, log, TransactionTemplate(DataSourceTransactionManager(dataSource)), clock)

    /**
     * 실행 호스트 클라이언트(S3a 스펙 §8). 주소 형식은 [HostClient.checkBaseUrl] 이 기동에서 본다. 작업 지시 철회만 요청 제한이 따로다
     * (`ops.host.withdraw-timeout`, 기본 3초, B3).
     */
    @Bean
    open fun hostClient(
        @Value("\${ops.host.url}") url: String,
        @Value("\${ops.host.withdraw-timeout:PT3S}") withdrawTimeout: java.time.Duration,
    ): HostClient = HostClient(url, withdrawTimeout = withdrawTimeout)

    /** 시운전·연결은 기체 목록의 판정을 그대로 쓴다(T3). 기체 목록 빈을 같이 써서 그 직전 값도 같다. */
    @Bean
    open fun jobOrderEligibility(robots: RobotListService, host: HostClient, clock: Clock): JobOrderEligibility =
        JobOrderEligibility(robots, host, clock)

    @Bean
    open fun jobOrderOperations(
        eligibility: JobOrderEligibility,
        host: HostClient,
        log: OperationLog,
        clock: Clock,
    ): JobOrderOperations = JobOrderOperations(eligibility, host, host, log, clock)

    /** 시운전 완료 기체는 기체 목록의 판정을 그대로 쓴다(S3b 스펙 §7, T7). */
    @Bean
    open fun missionOperations(robots: RobotListService, host: HostClient, log: OperationLog): MissionOperations =
        MissionOperations(robots, host, log)

    @Bean
    open fun cellSignalOperations(host: HostClient, log: OperationLog): CellSignalOperations =
        CellSignalOperations(host, host, log)

    @Bean
    open fun faultOperations(host: HostClient, log: OperationLog): FaultOperations = FaultOperations(host, log)

    /**
     * 재조회 대조의 «요청 직전 실제 시각» 은 운영 서비스 시계에서 읽는다(S4a JSON 계약 §5.4). 재작업은 자원 관리 작업이면 스테이션
     * 조정을 거친다.
     */
    @Bean
    open fun holdResolutions(host: HostClient, log: OperationLog, clock: Clock, requests: dev.picasso.ops.service.requests.WorkRequests,
        stations: dev.picasso.ops.service.stations.StationCoordinator): HoldResolutions =
        HoldResolutions(host, log, clock = clock, rework = dev.picasso.ops.service.stations.StationReworkGuard(requests, stations))

    /** 운영자 실행 취소와 기체 빈손 확인(P0-b2). 재조회 대조의 «요청 직전 실제 시각» 은 운영 서비스 시계에서 읽는다. */
    @Bean
    open fun executionControls(host: HostClient, log: OperationLog, clock: Clock): dev.picasso.ops.service.executions.ExecutionControls =
        dev.picasso.ops.service.executions.ExecutionControls(host, log, clock = clock)

    /** [migrated] 는 쓰지 않는다. 받는 것만으로 ops 마이그레이션 뒤에 이 빈이 만들어진다. */
    @Bean
    open fun operationLog(
        jdbc: JdbcClient,
        @Suppress("UNUSED_PARAMETER") migrated: OpsSchemaMigrated,
    ): OperationLog = OperationLog(jdbc)

    @Bean
    open fun missionProfiles(jdbc:JdbcClient,@Suppress("UNUSED_PARAMETER") migrated:OpsSchemaMigrated)=dev.picasso.ops.service.missions.MissionProfiles(jdbc)

    @Bean
    open fun missionInterfaces(jdbc: JdbcClient, dataSource: DataSource, log: OperationLog,
        @Suppress("UNUSED_PARAMETER") migrated: OpsSchemaMigrated): dev.picasso.ops.service.interfaces.MissionInterfaces =
        dev.picasso.ops.service.interfaces.MissionInterfaces(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)), log)

    /** 결과 버전을 적는 트랜잭션에서 전달 행도 넣는다. 주소·출처는 `WorkResultDelivery` 와 같은 속성이다. */
    @Bean
    open fun workRequests(jdbc: JdbcClient, interfaces: dev.picasso.ops.service.interfaces.MissionInterfaces, operations: JobOrderOperations, host: HostClient, dataSource: DataSource,
        @Value("\${ops.integration.callback-url:}") callbackUrl: String,
        @Value("\${ops.integration.source:mock-mes}") source: String): dev.picasso.ops.service.requests.WorkRequests =
        dev.picasso.ops.service.requests.WorkRequests(jdbc, interfaces, operations, host, TransactionTemplate(DataSourceTransactionManager(dataSource)), durableResults=true,
            delivery=dev.picasso.ops.service.requests.ResultDeliveryQueue(callbackUrl, source))

    /**
     * 이 프로세스의 신원과 심장박동(HA 설계 기록 §8 단계 0). 스테이션 조정기가 남은 상태의 주인이 살아 있는지 묻는다. 닫을 때 멈춤을
     * 적는다. 만료가 주기의 세 배보다 짧으면 기동이 멈춘다.
     */
    @Bean(destroyMethod = "close")
    open fun opsInstance(
        jdbc: JdbcClient,
        @Value("\${ops.instance.heartbeat-period}") period: java.time.Duration,
        @Value("\${ops.instance.heartbeat-expiry}") expiry: java.time.Duration,
        @Suppress("UNUSED_PARAMETER") migrated: OpsSchemaMigrated,
    ): dev.picasso.ops.service.instance.OpsInstance = dev.picasso.ops.service.instance.OpsInstance(jdbc, period, expiry).start()

    @Bean
    open fun stationCoordinator(jdbc:JdbcClient, dataSource:DataSource, requests:dev.picasso.ops.service.requests.WorkRequests,
        interfaces:dev.picasso.ops.service.interfaces.MissionInterfaces, host:HostClient, cell:CellSignalOperations, clock:Clock,
        instance:dev.picasso.ops.service.instance.OpsInstance):dev.picasso.ops.service.stations.StationCoordinator =
        dev.picasso.ops.service.stations.StationCoordinator(jdbc,TransactionTemplate(DataSourceTransactionManager(dataSource)),dev.picasso.ops.service.stations.OpsStationPort(requests,interfaces,host,cell),clock,instance)

    companion object {
        /** 설정 파일 이름을 `ops-service` 로 둔다. 같은 JVM 의 registry `application.properties` 와 가리지 않게(스펙 §7.1). */
        fun builder(): SpringApplicationBuilder =
            SpringApplicationBuilder(OpsApplication::class.java)
                .properties("spring.config.name=ops-service")
    }
}

fun main(args: Array<String>) {
    OpsApplication.builder().run(*args)
}
