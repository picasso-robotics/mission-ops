// 부하 측정 하네스(HA 권위 설계 §10). 운영 코드는 건드리지 않고 같은 JVM 에 Postgres(pg_stat_statements)·mimic·실행 호스트·운영 서비스를 띄워
// 핫스팟마다 한 회차의 실제 시간과 DB 문장 수를 잰다.
//
// `build`·CI 에 들지 않는다. 소스 세트가 `main`·`test` 가 아니라 `load` 라서 `build`(assemble·check)는 이것을 컴파일하지도 돌리지도 않는다.
// 돌리는 법: `./gradlew :load:loadBench --args="all"` (또는 stations·delivery·pump·explain 가운데 하나 이상). 결과는 load/build/load/ 에 남는다.
val load: SourceSet by sourceSets.creating

dependencies {
    "loadImplementation"(project(":site"))
    "loadImplementation"(project(":mission-host"))
    "loadImplementation"(project(":ops-service"))
    // 실행 호스트는 picasso·mimic 을 implementation 으로 든다. 하네스가 미들웨어 크기를 읽고(반사) mimic 을 직접 띄우므로 적는다.
    "loadImplementation"("dev.picasso:picasso")
    "loadImplementation"("dev.picasso:mimic")
    // 인수 시나리오의 대조 변형(takeoverfenced)이 mimic 바닥값을 운영과 같은 파일 저장(`FileFenceStore`)에 둔다.
    "loadImplementation"("dev.picasso:adapter-host")
    // 실제 수신자 변형(takeoverreceiver)이 mission-host 시험 고정물의 수신자(`adapter-host`)·시험 어댑터를 쓴다. 기체 프로파일은 그 고정물이 읽는다.
    "loadImplementation"(testFixtures(project(":mission-host")))
    // 플릿 변형(takeoverfleet)이 현장 플릿 세계(SiteFleet 의 AmrFleetMimic)를 직접 본다(맡긴 수·운반 상태).
    "loadImplementation"("dev.picasso:fleet-mimic")
    // Testcontainers Postgres. registry testFixtures 의 PostgresSupport 는 pg_stat_statements 를 싣지 않아 컨테이너만 같은 판으로 따로 띄운다.
    "loadImplementation"(testFixtures("dev.picasso:registry"))
    "loadImplementation"(platform(libs.spring.boot.bom))
    "loadImplementation"(libs.spring.boot.starter.web)
    "loadImplementation"("org.springframework.boot:spring-boot-starter-jdbc")
    "loadImplementation"(libs.jackson.databind)
    "loadRuntimeOnly"(libs.postgresql)
}

tasks.register<JavaExec>("loadBench") {
    group = "verification"
    description = "부하 핫스팟 측정(HA 권위 설계 §10). build 에 들지 않는다."
    mainClass.set("dev.picasso.ops.load.LoadBenchKt")
    classpath = load.runtimeClasspath
    // 모의 실행 프로파일·mimic 프로파일 경로가 저장소 루트 기준이다(site·mission-host 의 run 과 같음).
    workingDir = rootDir
    jvmArgs("-Xmx4g", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dload.out=${layout.buildDirectory.dir("load").get().asFile}")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
