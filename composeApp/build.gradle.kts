import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

/**
 * 양 플랫폼이 공유하는 Compose Multiplatform UI.
 *
 * AGP 9 부터 `com.android.application` 과 KMP 플러그인은 같은 모듈에 공존할 수 없다.
 * 그래서 Android 실행 모듈(:androidApp)은 순수 Android 로 두고,
 * 공유 UI 는 이 KMP 라이브러리에 모은다. iOS 는 여기서 나오는 프레임워크를 링크한다.
 */
/**
 * 공유 폰트. Crimson Text (SIL OFL).
 *
 * 앱 화면은 위젯과 달리 폰트 제약이 없으므로 양 플랫폼이 이 리소스를 그대로 쓴다.
 * Android 위젯은 res/font 의 같은 파일을 비트맵으로 굽는다 —
 * 파일이 둘이지만 **같은 폰트여야** 네 표면(안드로이드 앱/위젯, iOS 앱/위젯)이 통일된다.
 */
compose.resources {
    publicResClass = true
    packageOfResClass = "com.dogdduddy.almanac.resources"
    generateResClass = always
}

/**
 * RevenueCat 클라이언트 키를 소스에 박지 않는다. local.properties 에서 읽어 주입한다.
 *
 * **왜 shared 가 아니라 여기인가:** RevenueCat 의 iOS cinterop 이 구버전 Xcode 기준으로
 * 빌드돼 있어 Kotlin/Native **테스트 실행 파일**을 링크할 수 없다
 * (libswiftCompatibility56 등이 Xcode 26 툴체인에 없다).
 * 앱 프레임워크는 Xcode 가 링크하므로 문제없지만, shared 에 두면 shared 의 iOS 테스트가
 * 통째로 못 돈다 — 크로스플랫폼 골든 벡터가 거기 있으므로 잃으면 안 된다.
 *
 * 그래서 결제 구현은 앱 쪽 모듈에 둔다. shared 는 순수 도메인으로 남는다.
 */
val revenueCatKeys: Map<String, String> = run {
    val local = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun read(name: String) =
        (findProperty("almanac.revenuecat.$name") as String?)
            ?: local.getProperty("almanac.revenuecat.$name") ?: ""

    // 스토어에 앱을 처음 올려 패키지명을 등록하는 단계에서는 키가 하나도 없어야 한다.
    // Test Store 키가 실리면 심사에서 반려되고, 실키는 아직 없기 때문이다.
    // 이 플래그를 주면 결제 없는 빌드가 나온다 (앱은 PreviewBilling 으로 정상 동작).
    val bootstrap = (findProperty("almanac.billing.bootstrap") as String?)?.toBoolean() ?: false
    if (bootstrap) return@run mapOf("android" to "", "ios" to "", "test" to "")

    mapOf("android" to read("android"), "ios" to read("ios"), "test" to read("test"))
}

val generateBillingKeys = tasks.register("generateBillingKeys") {
    val outDir = layout.buildDirectory.dir("generated/billingKeys/kotlin")
    val keys = revenueCatKeys
    inputs.property("keys", keys)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.resolve("com/dogdduddy/almanac/billing")
        dir.mkdirs()
        dir.resolve("BillingKeys.kt").writeText(
            """
            package com.dogdduddy.almanac.billing

            /** 빌드 때 local.properties 에서 주입된다. 손으로 고치지 말 것. */
            internal object BillingKeys {
                const val ANDROID: String = "${keys["android"]}"
                const val IOS: String = "${keys["ios"]}"
                const val TEST: String = "${keys["test"]}"
            }
            """.trimIndent()
        )
    }
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.dogdduddy.almanac.ui"
        compileSdk = 36
        minSdk = 26
    }

    // iosX64(인텔 시뮬레이터)는 뺀다 — Compose Multiplatform 1.11.x 가 해당 타깃 아티팩트를
    // 발행하지 않아 의존성 해석이 깨진다. 개발 머신이 Apple Silicon 이라 필요도 없다.
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            export(projects.shared)
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateBillingKeys)
        }
        commonMain.dependencies {
            api(projects.shared)
            api(libs.revenuecat.core)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }
    }
}
