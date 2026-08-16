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
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.sqldelight.runtime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
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
