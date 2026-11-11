rootProject.name = "mission-ops"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

// 판은 picasso 의 카탈로그 하나에서 온다(스펙 §4). 여기 따로 적으면 두 저장소의 판이 갈라진다.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") { from(files("picasso/gradle/libs.versions.toml")) }
    }
}

// picasso 서브프로젝트에는 Gradle group 이 없어 좌표가 자동으로 맞지 않는다(스펙 §11).
// 쓰는 모듈만 명시한다. uplink 는 mimic 의 api 로 따라온다. harness 는 가짜 현장이 개정판 시험 실행기를 띄우려고 쓴다.
// picasso(미들웨어)와 client 는 실행 호스트가 쓴다(S3a 스펙 §7.1). capability 는 실행 호스트가 거부의 조치 탐색 응답 타입을 읽으려고 쓴다.
includeBuild("picasso") {
    dependencySubstitution {
        substitute(module("dev.picasso:registry")).using(project(":registry"))
        substitute(module("dev.picasso:mimic")).using(project(":mimic"))
        substitute(module("dev.picasso:harness")).using(project(":harness"))
        substitute(module("dev.picasso:picasso")).using(project(":picasso"))
        substitute(module("dev.picasso:client")).using(project(":client"))
        substitute(module("dev.picasso:capability")).using(project(":capability"))
    }
}

include("site")
include("ops-service")
include("mission-host")
include("e2e")
// 부하 측정 하네스. 소스 세트가 `load` 라 `build` 가 컴파일도 실행도 하지 않는다(load/build.gradle.kts).
include("load")
