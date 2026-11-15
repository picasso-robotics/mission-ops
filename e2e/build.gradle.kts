// 통합 시험만 있는 모듈. 한 JVM 에 Postgres·registry·mimic·실행 호스트·운영 서비스를 함께 띄운다(스펙 §10, S3a 스펙 §11).
dependencies {
    testImplementation(project(":site"))
    testImplementation(project(":mission-host"))
    testImplementation(project(":ops-service"))
    testImplementation(testFixtures("dev.picasso:registry"))
    // 운영 서비스의 시간값 범위 사본을 picasso `SiteTimings` 의 범위와 맞댄다(S3c 스펙 §3 5단계, T6). mission-host 는 picasso 를
    // implementation 으로 들어 e2e 의 컴파일 클래스패스에 내지 않는다.
    testImplementation("dev.picasso:picasso")
    // 현장 플릿 세계(Site.fleet 의 AmrFleetMimic)를 시험이 직접 채우고 관측한다(맡긴 횟수). site 는 그것을 implementation 으로 든다.
    testImplementation("dev.picasso:fleet-mimic")
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.jackson.databind)
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("site/robots.json")).withPropertyName("roster")
    inputs.dir(rootProject.file("picasso/profile/profiles")).withPropertyName("profiles")
    inputs.dir(rootProject.file("picasso/profile/schema")).withPropertyName("profileSchema")
    inputs.dir(rootProject.file("mission-host/mock-run")).withPropertyName("mockRunProfile")
}
