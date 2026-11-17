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
 * @param httpFence 현장 HTTP 면(플릿 맡기기·취소, 셀 신호 쓰기, 결함 주입)도 세대를 대조하는가(환경 변수 `SITE_HTTP_FENCE` = `on`). 켜면 [fenceDir] 가
 *   있어야 하고 바닥값을 그 아래 `site/` 의 파일 하나 저장소에 둔다(키 `fleet`·`cell`). 끄면 그 파일을 건드리지 않는다(남은 바닥값은 그대로).
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
    val httpFence: Boolean = false,
) {
    init {
        require(!httpFence || fenceDir != null) { "현장 HTTP 세대 대조(httpFence)는 바닥값 디렉터리(SITE_FENCE_DIR)가 있어야 켠다" }
    }

    val schema: Path get() = root.resolve(PROFILE_SCHEMA)

    /** 기체 바닥값 디렉터리. 플릿·셀 쪽([siteFenceDir])과 나눈다. */
    val robotFenceDir: Path? get() = fenceDir?.resolve(ROBOT_FENCE)

    /** 현장 HTTP 면(플릿·셀) 바닥값 디렉터리. [httpFence] 일 때만 있다. */
    val siteFenceDir: Path? get() = fenceDir?.takeIf { httpFence }?.resolve(SITE_FENCE)

    fun profile(entry: RosterEntry): Path = root.resolve(entry.profile)

    companion object {
        const val PROFILE_SCHEMA = "picasso/profile/schema/capability-profile.schema.json"
        const val ROSTER = "site/robots.json"

        /** [fenceDir] 아래 기체 바닥값 디렉터리 이름. */
        const val ROBOT_FENCE = "robots"

        /** [fenceDir] 아래 현장 HTTP 면(플릿·셀) 바닥값 디렉터리 이름. */
        const val SITE_FENCE = "site"

        /** 현장 HTTP 면 세대 대조 스위치의 환경 변수. 값은 [HTTP_FENCE_ON]·[HTTP_FENCE_OFF] 둘뿐이다. */
        const val HTTP_FENCE_ENV = "SITE_HTTP_FENCE"
        const val HTTP_FENCE_ON = "on"
        const val HTTP_FENCE_OFF = "off"

        /** 두 선택의 뜻. 스위치가 빠졌거나 틀렸을 때 기동 거부 문구에 싣는다. */
        private const val HTTP_FENCE_CHOICES =
            "$HTTP_FENCE_ON = 플릿 맡기기·취소, 셀 신호 쓰기, 결함 주입도 제어자 세대를 대조해 옛 실행 호스트의 늦은 변경을 거절한다" +
                "(실행 호스트를 모두 새 코드로 올린 뒤에 켠다 — 옛 호스트는 세대를 싣지 않아 모든 변경이 거절된다); " +
                "$HTTP_FENCE_OFF = 플릿·셀은 대조하지 않는다(기체만 대조, 옛 거동)"

        /**
         * `SITE_HTTP_FENCE` 를 읽는다. [hasDir] 는 `SITE_FENCE_DIR` 이 있는가다. 디렉터리가 있으면 `on`·`off` 둘 중 하나를 꼭 적어야 하고(빠지거나 그 밖의
         * 값이면 기동 거부), 디렉터리가 없으면 `on` 을 받지 않는다. 디렉터리가 없을 때 빠진 것은 `off` 다. 저장소를 열거나 서버를 띄우기 전에 부른다.
         */
        fun httpFenceOf(raw: String?, hasDir: Boolean): Boolean {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                value == HTTP_FENCE_ON && hasDir -> true
                value == HTTP_FENCE_ON -> throw IllegalArgumentException(
                    "$HTTP_FENCE_ENV=$HTTP_FENCE_ON 인데 SITE_FENCE_DIR 이 없다 — 바닥값을 둘 디렉터리가 있어야 켠다 ($HTTP_FENCE_CHOICES)",
                )
                value == HTTP_FENCE_OFF -> false
                value == null && !hasDir -> false
                value == null -> throw IllegalArgumentException(
                    "SITE_FENCE_DIR 이 있으면 $HTTP_FENCE_ENV 를 $HTTP_FENCE_ON 이나 $HTTP_FENCE_OFF 로 적는다 ($HTTP_FENCE_CHOICES)",
                )
                else -> throw IllegalArgumentException(
                    "$HTTP_FENCE_ENV 는 $HTTP_FENCE_ON 이나 $HTTP_FENCE_OFF 만 받는다: '$value' ($HTTP_FENCE_CHOICES)",
                )
            }
        }

        fun fromEnv(env: Map<String, String>, root: Path): SiteConfig {
            fun need(key: String): String = env[key]?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("환경 변수 $key 가 없다(루트 .env 를 확인)")
            fun port(key: String): Int = need(key).let {
                it.toIntOrNull() ?: throw IllegalArgumentException("$key 가 정수가 아니다: $it")
            }
            val fenceDir = env["SITE_FENCE_DIR"]?.trim()?.takeIf { it.isNotEmpty() }?.let { root.resolve(it).normalize() }
            // 스위치는 다른 무엇보다 먼저 판정한다 — 틀리면 바닥값 저장소도 서버도 열지 않고 멈춘다.
            val httpFence = httpFenceOf(env[HTTP_FENCE_ENV], fenceDir != null)
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
                fenceDir = fenceDir,
                httpFence = httpFence,
            )
        }
    }
}
