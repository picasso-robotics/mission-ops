package dev.picasso.ops.site

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 가상 시계를 실제 시각까지 따라잡게 미는 주기(S3a 스펙 §6.1). 밀 때마다 실제 시각까지 미므로 가상 시각은 실제 시각보다
 * 앞서지 않고 차이는 한 주기 이내다. 그래서 시간 흐름이 1:1 이다(스펙 §6 ③). 1:1 이 깨지면 운영 서비스의 연결 기준 시간
 * 버전 1 의 90초와 허용 범위 하한 60초(S2 스펙 §5)도 다시 정한다.
 */
val TICK: Duration = Duration.ofSeconds(1)

fun main() {
    val root = Path.of("").toAbsolutePath()
    val config = SiteConfig.fromEnv(System.getenv(), root)
    val site = Site.start(config)
    println("registry: ${site.registryUrl} (site=${config.siteId})")
    println("mimic: ${site.robotIds.sorted()} (gRPC 포트 ${site.mimicPort})")
    println("셀 대역: http://127.0.0.1:${site.cellPort}/cell")
    // 플릿 세계는 같은 서버의 /fleet 다. 실행 호스트는 host.fleet.url(환경 변수 HOST_FLEET_URL)에 이 주소를 주면 플릿 포트를 붙인다.
    println("플릿 세계: http://127.0.0.1:${site.cellPort}/fleet/world (인계 자리 ${site.fleet.places})")
    // uplink 가 거절 결과를 버리고 registry 도 남기지 않아서, 선언 전 보고의 거절은 어디에도 안 보인다.
    println("선언 전 mimic 의 생존 보고는 registry 가 거절하며 이 로그에도 남지 않는다. 기체를 선언하면 보고가 붙는다")

    val ticker = Executors.newSingleThreadScheduledExecutor()
    ticker.scheduleAtFixedRate(
        { runCatching { site.advanceTo(Instant.now()) }.onFailure { System.err.println("시간 진행 실패: $it") } },
        TICK.toMillis(),
        TICK.toMillis(),
        TimeUnit.MILLISECONDS,
    )
    Runtime.getRuntime().addShutdownHook(
        Thread {
            ticker.shutdownNow()
            // 진행 중인 advance 가 끝난 뒤 닫는다.
            ticker.awaitTermination(2, TimeUnit.SECONDS)
            site.close()
        },
    )
    Thread.currentThread().join()
}
