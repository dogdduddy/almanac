plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeHotReload) apply false
    alias(libs.plugins.sqldelight) apply false
}

/**
 * 큐레이션 CSV → content.db.
 *
 * CSV 디렉터리는 머신마다 다르므로(구글 드라이브 동기화 폴더) 경로를 하드코딩하지 않는다.
 * `local.properties` 의 `almanac.content.csv.dir` 또는 `-Palmanac.content.csv.dir=...` 로 준다.
 *
 * 경로가 없으면 태스크는 **조용히 건너뛴다** — CSV 없이도 빌드는 되어야 한다.
 * (더미 데이터로 개발을 시작할 수 있어야 하고, CI 에도 CSV 가 없다.)
 */
val contentCsvDir: String? =
    (findProperty("almanac.content.csv.dir") as String?)
        ?: runCatching {
            java.util.Properties().apply {
                file("local.properties").inputStream().use { load(it) }
            }.getProperty("almanac.content.csv.dir")
        }.getOrNull()

val bakedContentDb = layout.buildDirectory.file("content/content.db")

// 설정 캐시가 클로저 안의 스크립트 객체 참조를 직렬화하지 못하므로,
// 태스크 안에서는 아래 **값들만** 캡처한다 (스크립트의 logger/project 를 건드리지 않는다).
val hasCsvDir = contentCsvDir != null
val bakeScript = file("scripts/bake_content.py")
val packsConfig = file("scripts/packs.json")

val bakeContent = tasks.register<Exec>("bakeContent") {
    group = "almanac"
    description = "큐레이션 CSV 를 content.db 로 굽는다"

    val outFile = bakedContentDb.get().asFile

    inputs.file(bakeScript)
    inputs.file(packsConfig)
    contentCsvDir?.let { inputs.dir(file(it)).withPathSensitivity(PathSensitivity.RELATIVE) }
    outputs.file(outFile)
    // onlyIf/doFirst 람다는 스크립트 인스턴스를 캡처해 설정 캐시가 깨진다.
    // enabled 는 평범한 프로퍼티라 안전하다. (출력 디렉터리 생성은 파이썬 쪽이 한다)
    enabled = hasCsvDir

    commandLine(
        "python3", bakeScript.absolutePath,
        "--source", contentCsvDir ?: ".",
        "--out", outFile.absolutePath,
        "--packs", packsConfig.absolutePath,
    )
}

/**
 * **커밋되는 정본.** CSV 는 이 저장소에 없으므로(구글 드라이브) 깨끗한 체크아웃에서
 * 굽는 것은 불가능하다. 그러니 구운 결과물을 정본으로 커밋하고, 번들 사본 3개는
 * 여기서 복사해 만든다. 사본은 생성물이라 gitignore 대상이다.
 *
 * 콘텐츠를 갱신하는 사람만 CSV 를 갖고 `./gradlew updateContent` 로 정본을 갱신한다.
 */
val canonicalContentDb = layout.projectDirectory.file("content/content.db")

/** 새로 구운 DB 를 정본으로 승격한다. CSV 를 가진 사람만 실행된다. */
val updateContent = tasks.register<Copy>("updateContent") {
    group = "almanac"
    description = "구운 content.db 를 커밋되는 정본으로 승격한다"
    dependsOn(bakeContent)
    enabled = hasCsvDir
    from(bakedContentDb)
    into(canonicalContentDb.asFile.parentFile)
}

/**
 * 번들 사본 4개.
 *
 * **네 곳 모두에 넣어야 한다.** 위젯 익스텐션의 NSBundle.mainBundle 은 앱 번들이
 * 아니므로 자기 리소스를 따로 갖는다. 앱만 갱신하면 콘텐츠를 새로 낸 뒤 위젯만
 * 구버전 DB 를 들고 있게 되고, 같은 순간에 앱과 위젯이 다른 문장을 그린다.
 * 데스크톱은 클래스패스 리소스로 싣는다 — 실행 파일 안의 유일한 사본이다.
 */
val contentBundleTargets = mapOf(
    "Android" to "androidApp/src/main/assets",
    "IosApp" to "iosApp/Almanac/Resources",
    "IosWidget" to "iosApp/AlmanacWidget/Resources",
    "Desktop" to "desktopApp/src/main/resources",
)

val syncTasks = contentBundleTargets.map { (name, path) ->
    tasks.register<Copy>("syncContentTo$name") {
        group = "almanac"
        description = "content.db 정본을 $path 로 복사한다"
        dependsOn(updateContent)
        from(canonicalContentDb)
        into(layout.projectDirectory.dir(path))
    }
}

/** 네 번들에 한 번에. */
val syncContent = tasks.register("syncContent") {
    group = "almanac"
    description = "content.db 를 앱·위젯·데스크톱 번들 네 곳에 반영한다"
    dependsOn(syncTasks)
}

/**
 * 네 사본이 정본과 **바이트 단위로 같은지** 검사한다.
 *
 * 파일 존재만 보면 구버전 DB 를 들고 있는 번들을 못 잡는다 — 그게 정확히
 * 위젯만 옛 문장을 그리는 증상이다. 릴리스 빌드는 이걸 통과해야 한다.
 */
val verifyContentDb = tasks.register("verifyContentDb") {
    group = "verification"
    description = "번들 네 곳의 content.db 가 정본과 일치하는지 검사한다"
    dependsOn(syncContent)

    val canonical = canonicalContentDb.asFile
    val copies = contentBundleTargets.values.map { layout.projectDirectory.file("$it/content.db").asFile }

    doLast {
        // 해시 계산을 스크립트 최상위 함수로 빼면 람다가 스크립트 인스턴스를 캡처해
        // 설정 캐시가 깨진다. 여기 안에 둔다.
        val sha256 = { file: File ->
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { byte -> ((byte.toInt() and 0xff) + 0x100).toString(16).substring(1) }
        }

        check(canonical.isFile) {
            "content.db 정본이 없다: ${canonical.path}\n" +
                "큐레이션 CSV 를 가진 상태에서 ./gradlew updateContent 로 굽고 커밋할 것."
        }
        val expected = sha256(canonical)
        val broken = copies.filter { !it.isFile || sha256(it) != expected }
        check(broken.isEmpty()) {
            "번들의 content.db 가 정본과 다르다:\n" +
                broken.joinToString("\n") { "  - ${it.path}" } +
                "\n./gradlew syncContent 로 다시 복사할 것."
        }
    }
}
