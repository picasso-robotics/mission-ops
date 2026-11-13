// 실행 호스트(S3a 스펙 §7, S3b 스펙 §6). picasso 미들웨어를 세워 mimic 기체에서 작업 지시를 실행하고, 임무 버전을 저장·검증·
// 모의 실행·활성화한다. 운영 서비스가 picasso 를 쓰지 않는 경계(`checkNoPicassoOnMain`)를 지키도록 별도 프로세스로 둔다(결정 1).
// 그 검사는 이 모듈에 걸지 않는다.
plugins {
    application
}

application {
    mainClass.set("dev.picasso.ops.host.MissionHostApplicationKt")
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

dependencies {
    implementation("dev.picasso:picasso")
    // picasso 가 client 를 implementation 으로 들므로 ClientRobotPort 에 넘길 PicassoClient 를 직접 의존한다.
    implementation("dev.picasso:client")
    // 미들웨어 거부의 조치 탐색 응답(`Remedy`)이 capability 타입이고 picasso 가 그것을 implementation 으로 든다. 제출 결과로 옮기려고 직접 의존한다.
    implementation("dev.picasso:capability")
    // 모의 실행이 프로세스 안에 별도 mimic 과 가상 시계를 띄운다(S3b 스펙 §6.4). 하네스가 in-process 로 세우고 mimic 은 그 안에서 돈다.
    implementation("dev.picasso:harness")
    implementation("dev.picasso:mimic")
    // 인시던트 고리(picasso H1). 승인 호스트·자격 선언 파일·번들 쓰기. 설정을 비우면 붙지 않는다(`host.incident-loop.*`).
    implementation("dev.picasso:host-kit")

    // 운영 서비스와 같이 BOM 만 쓰고 Spring Boot 플러그인은 붙이지 않는다. 판이 picasso 카탈로그 밖으로 나가지 않는다.
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.jackson.module.kotlin)
    // 임무 버전 저장(S3b 스펙 §6.1, T3). 같은 Postgres 의 자기 스키마 mission 이다.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(kotlin("test"))
    // 시험용 Postgres 는 registry 의 testFixtures 를 쓴다. 복사하지 않는다(운영 서비스와 같음).
    testImplementation(testFixtures("dev.picasso:registry"))
}

// 저장소 루트에서 돈다. 모의 실행 프로파일·스키마 경로(host.mock-run.*)가 루트 기준이다(site 의 run 과 같음).
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

tasks.withType<Test>().configureEach {
    // 시험이 저장소 파일을 읽는다. 선언하지 않으면 파일을 고쳐도 시험이 UP-TO-DATE 로 넘어간다.
    inputs.dir(rootProject.file("picasso/profile/profiles")).withPropertyName("profiles")
    inputs.dir(rootProject.file("picasso/profile/schema")).withPropertyName("profileSchema")
    inputs.dir(rootProject.file("mission-host/mock-run")).withPropertyName("mockRunProfile")
}
