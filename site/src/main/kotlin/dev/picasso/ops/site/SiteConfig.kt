package dev.picasso.ops.site

import java.nio.file.Path

data class DbConfig(val url: String, val user: String, val password: String)

/**
 * 런처 설정. 값은 루트 `.env` 에서 환경 변수로 온다(스펙 §4). 사이트 id 와 기체 명부는 한 출처에서 나온다(스펙 §6 ④).
 *
 * @param registryPort 0 이면 무작위 포트(시험).
 * @param mimicPort mimic gRPC 포트. 0 이면 무작위(시험). 실행 호스트가 이 포트로 붙는다(S3a 스펙 §6.2).
 * @param cellPort 셀 대역 `GET /cell` 의 루프백 포트. 0 이면 무작위(시험).
 * @param cell 셀 대역의 제시 자리와 슬롯. 현장마다 다를 값이라 설정에 두고, 런처는 [CellFixture.STANDARD] 를 쓴다.
 * @param fenceDir 수신자(mimic) 제어자 세대 바닥값 디렉터리(환경 변수 `SITE_FENCE_DIR`, picasso ADR 54). 있으면 mimic 이 세대를 대조하고(FENCED)
 *   기체마다 바닥값을 그 아래 `robots/` 의 파일에 둔다(picasso `FileFenceStore`). 없으면 대조하지 않는다(UNFENCED, 오늘 거동). 경로는 수신자 소유의
 *   고정 경로다 — 실행 호스트의 세대·인스턴스마다 바꾸지 않고, DB 를 새로 만들면 함께 비운다(운영 문서).
 */
data class SiteConfig(
    val root: Path,
    val siteId: String,
    val db: DbConfig,
    val registryPort: Int,
    val operatorToken: String,
    val ingestToken: String,
    val roster: List<RosterEntry>,
    val mimicPort: Int = 0,
    val cellPort: Int = 0,
    val cell: CellFixture = CellFixture.STANDARD,
    val fenceDir: Path? = null,
) {
    val schema: Path get() = root.resolve(PROFILE_SCHEMA)

    /** 기체 바닥값 디렉터리. 플릿·셀 쪽(다음 단계)과 나눈다. */
    val robotFenceDir: Path? get() = fenceDir?.resolve(ROBOT_FENCE)

    fun profile(entry: RosterEntry): Path = root.resolve(entry.profile)

    companion object {
        const val PROFILE_SCHEMA = "picasso/profile/schema/capability-profile.schema.json"
        const val ROSTER = "site/robots.json"

        /** [fenceDir] 아래 기체 바닥값 디렉터리 이름. */
        const val ROBOT_FENCE = "robots"

        fun fromEnv(env: Map<String, String>, root: Path): SiteConfig {
            fun need(key: String): String = env[key]?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("환경 변수 $key 가 없다(루트 .env 를 확인)")
            fun port(key: String): Int = need(key).let {
                it.toIntOrNull() ?: throw IllegalArgumentException("$key 가 정수가 아니다: $it")
            }
            return SiteConfig(
                root = root,
                siteId = need("SITE_ID"),
                db = DbConfig(need("PICASSO_DB_URL"), need("PICASSO_DB_USER"), need("PICASSO_DB_PASSWORD")),
                registryPort = port("REGISTRY_PORT"),
                operatorToken = need("PICASSO_OPERATOR_TOKEN"),
                ingestToken = need("PICASSO_INGEST_TOKEN"),
                roster = RobotRoster.read(root.resolve(ROSTER)),
                mimicPort = port("MIMIC_GRPC_PORT"),
                cellPort = port("SITE_CELL_PORT"),
                fenceDir = env["SITE_FENCE_DIR"]?.trim()?.takeIf { it.isNotEmpty() }?.let { root.resolve(it).normalize() },
            )
        }
    }
}
