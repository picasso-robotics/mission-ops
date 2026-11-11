package dev.picasso.ops.load

import java.lang.management.ManagementFactory

/**
 * `./gradlew :load:loadBench --args="<시나리오…> [quick]"`. 시나리오는 stations·delivery·pump·pumpscale·explain·list·all 이다. list(실행 목록 크기,
 * 실행 10000 까지 쌓음)와 pumpscale(기체 100·200)은 all 에 들지 않는다.
 * `quick` 을 붙이면 규모를 줄여 배선만 확인한다. 결과는 `load/build/load/<시나리오>.md` 와 표준 출력에 같이 남는다.
 */
fun main(args: Array<String>) {
    val quick = "quick" in args
    val chosen = args.filter { it != "quick" }.ifEmpty { listOf("all") }
    val all = "all" in chosen
    val env = Report("environment")
    val os = ManagementFactory.getOperatingSystemMXBean()
    env.table(
        listOf("항목", "값"),
        listOf(
            listOf("JVM", "${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}"),
            listOf("OS", "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}"),
            listOf("논리 CPU(JVM)", "${os.availableProcessors}"),
            listOf("최대 힙", "${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MiB"),
            listOf("모드", if (quick) "quick" else "full"),
        ),
    )
    if (all || "explain" in chosen) WorkRequestExplain.run(Report("explain"), if (quick) listOf(1_000) else listOf(10_000, 100_000))
    if (all || "delivery" in chosen) DeliveryLoad.run(Report("delivery"), if (quick) listOf(100) else listOf(100, 1_000, 10_000, 100_000), if (quick) 5 else 20)
    if (all || "stations" in chosen) StationTickLoad.run(Report("stations"), if (quick) listOf(1, 3) else listOf(1, 10, 50, 100), if (quick) 5 else 20)
    if (all || "pump" in chosen) PumpLoad.run(
        Report("pump"),
        if (quick) {
            listOf(PumpLoad.Plan(2, listOf(4)))
        } else {
            listOf(
                PumpLoad.Plan(1, listOf(10, 50)),
                PumpLoad.Plan(10, listOf(10, 100, 300)),
                PumpLoad.Plan(50, listOf(50, 500, 1_000, 2_000)),
            )
        },
        if (quick) 5 else 30,
        combinedStations = if (quick) 2 else 50,
    )
    // 기체를 늘려 잰다(HA 설계 §10 «로봇 수를 늘려 가며»). 기존 pump 표와 견주려고 따로 둔다. all 에 들지 않는다.
    if ("pumpscale" in chosen) PumpLoad.run(
        Report("pumpscale"),
        if (quick) listOf(PumpLoad.Plan(4, listOf(8))) else listOf(PumpLoad.Plan(100, listOf(100, 1_000)), PumpLoad.Plan(200, listOf(200, 1_000))),
        if (quick) 5 else 30,
    )
    if ("list" in chosen) ListLoad.run(Report("list"), if (quick) listOf(10, 20) else listOf(2_000, 10_000), if (quick) 2 else 50, if (quick) 3 else 10)
    System.exit(0)
}
