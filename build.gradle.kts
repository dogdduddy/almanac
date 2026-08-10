plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
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

/** 구운 DB 를 Android 에셋으로 넣는다. */
val syncContentToAndroid = tasks.register<Copy>("syncContentToAndroid") {
    group = "almanac"
    description = "content.db 를 androidApp 에셋으로 복사한다"
    dependsOn(bakeContent)
    enabled = hasCsvDir
    from(bakedContentDb)
    into(layout.projectDirectory.dir("androidApp/src/main/assets"))
}
