import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

/**
 * DB 는 반드시 둘로 나눈다. 절대 합치지 말 것.
 *
 * - content: 읽기 전용. 앱 에셋에 번들되고 앱 업데이트마다 통째로 교체된다.
 *   교체가 전제라 마이그레이션이라는 개념이 없다 — 스키마는 언제든 자유롭게 바꿔도 된다.
 * - user: 쓰기 가능. 기기 내부 저장소. 히스토리·설치ID·보유 팩.
 *   여기에 히스토리를 두지 않으면 업데이트마다 유저 아카이브가 날아간다.
 */
sqldelight {
    databases {
        create("ContentDatabase") {
            packageName.set("com.dogdduddy.almanac.db.content")
            srcDirs.setFrom("src/commonMain/sqldelight/content")
        }
        create("UserDatabase") {
            packageName.set("com.dogdduddy.almanac.db.user")
            srcDirs.setFrom("src/commonMain/sqldelight/user")
            // user.db 는 유저 데이터가 들어 있으므로 마이그레이션 검증을 켠다.
            verifyMigrations.set(true)
        }
    }
}

/**
 * RevenueCat 클라이언트 키를 소스에 박지 않는다.
 *
 * 이 부류(공개 SDK 키)는 앱 바이너리에 심기는 값이라 비밀은 아니지만,
 * 저장소를 공개할 수 있으므로 local.properties 에서 읽어 빌드 때 주입한다.
 * 키가 없으면 빈 문자열이 들어가고 앱은 PreviewBilling 으로 떨어진다 —
 * 키 없이도 빌드와 실행이 되어야 CI 와 신규 클론이 막히지 않는다.
 */
val revenueCatKeys: Map<String, String> = run {
    val local = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun read(name: String) =
        (findProperty("almanac.revenuecat.$name") as String?) ?: local.getProperty("almanac.revenuecat.$name") ?: ""
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
        namespace = "com.dogdduddy.almanac.shared"
        compileSdk = 36
        minSdk = 26

        // commonTest 를 JVM 호스트에서 돌리기 위함. 에뮬레이터 없이 결정론 테스트가 돈다.
        withHostTest {}
    }

    // :composeApp 과 타깃 집합을 맞춘다 (iosX64 제외 — 사유는 composeApp/build.gradle.kts 참고)
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "AlmanacKit"
            isStatic = true
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateBillingKeys)
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.sqldelight.runtime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.revenuecat.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.core.ktx)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
            implementation(libs.ktor.client.darwin)
        }
        // DB 를 붙인 테스트는 JVM 호스트에서만 돈다. 인메모리 JDBC 드라이버를 쓴다.
        // 순수 로직(PageSelector)의 크로스플랫폼 동일성은 commonTest 가 이미 양쪽에서 검증한다.
        getByName("androidHostTest").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            // 실제 MET 응답 픽스처로 클라이언트를 검증한다 (네트워크 없이).
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
