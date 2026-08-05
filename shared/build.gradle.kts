plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
